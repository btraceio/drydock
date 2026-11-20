package app.drydock.workspace;

import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.process.ProcessTimeoutException;
import app.drydock.process.SshCommandBuilder;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonParseException;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonBoolean;
import app.drydock.state.json.JsonValue.JsonNull;
import app.drydock.state.json.JsonValue.JsonNumber;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonWriter;

import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The idempotent box-side bootstrap for the gateway-only AI proxy that every
 * persistent workspace-box session routes through
 * ({@code ANTHROPIC_BASE_URL=http://127.0.0.1:4000}).
 * {@code omlx_proxy} runs <em>unmodified</em> on the box in gateway-only mode
 * — proven end-to-end on a real box
 * ({@code workspace-persistent-sessions/find-omlx-proxy-portable}: scp the
 * binary, derive a gateway-only config, {@code nohup setsid uv run
 * ~/omlx_proxy}) — so this class only ships the two artifacts and keeps the
 * box running them.
 *
 * <p>Gateway auth is the box's own ddtool vault session
 * ({@code workspace-persistent-sessions/find-box-gateway-auth-ddtool}: the
 * proxy shells out to {@code ddtool auth token rapid-ai-platform …} itself,
 * and personal keys are 401-rejected — which is exactly why the derived config
 * drops {@code omlx_key}/{@code omlx_url}); fresh boxes carry their own cached
 * vault session ({@code workspace-persistent-sessions/find-box-vault-session-fresh}),
 * so bootstrap runs one non-interactive ddtool probe as its final sanity check
 * instead of any login flow. Its exit status is the only thing read: the
 * check's stdout is redirected to {@code /dev/null} <em>on the box</em> and its
 * output never enters this JVM.</p>
 *
 * <p>Secrecy (INV-18) is structural: no token, vault output,
 * {@code gateway_headers} value, {@code omlx_key}, or config content is ever
 * placed in a log line, an exception message, or a notification. Failure
 * reasons are built only from fixed labels, exit codes, and ssh/scp
 * transport stderr — the ddtool result contributes its exit code alone.</p>
 *
 * <p>Every external command runs through the {@link CommandRunner} seam as
 * an argument list with an explicit timeout (INV-5); the ssh/scp command
 * lines are built in {@link SshCommandBuilder} (INV-7), the scp copy rides
 * {@link BoxShell#copy}. {@link #ensure} is blocking — file I/O, ssh, and
 * polling — so it must never run on the EDT (INV-8); the launch path owns
 * the progress indication around it. A failed bootstrap
 * ({@link BootstrapException}) fails visibly with its reason and blocks the
 * launch (INV-19): a missing local proxy or config, a failed copy, a proxy
 * that never becomes healthy, or a failed ddtool check.</p>
 */
public final class ProxyBootstrap {

    private static final Logger LOG = System.getLogger(ProxyBootstrap.class.getName());

    /** ssh's own failure exit; every other exit code is the remote command's, forwarded by ssh. */
    private static final int SSH_FAILURE_EXIT = 255;

    /** The Mac-side proxy binary this class ships to the box (find-omlx-proxy-portable). */
    public static final Path DEFAULT_LOCAL_PROXY = Path.of("/usr/local/bin/omlx_proxy");

    /** The Mac-side proxy config; resolved from {@code user.home} at construction time. */
    public static Path defaultLocalConfig() {
        return Path.of(System.getProperty("user.home"), ".omlx_proxy");
    }

    /** The proxy's box-side address; the same URL the agent environment carries. */
    public static final String PROXY_URL = "http://127.0.0.1:4000/";

    /** One-word round trips (home probe, sha256sum, curl, start, ddtool check) get the box short budget. */
    static final Duration REMOTE_TIMEOUT = BoxShell.SHORT_COMMAND_TIMEOUT;

    /** scp of the ~500 KB proxy script and the small config; clone-scale is far above this. */
    static final Duration COPY_TIMEOUT = Duration.ofMinutes(2);

    /** ASM-13: a first {@code uv run} dependency resolve must finish inside this window. */
    public static final Duration HEALTH_POLL_WINDOW = Duration.ofMinutes(5);

    /** The gap between health polls inside the window. */
    static final Duration HEALTH_POLL_INTERVAL = Duration.ofSeconds(2);

    /**
     * Every ssh round trip's output cap (INV-20): the home path, two
     * sha256sum lines, and empty curl/ddtool stdout are a few hundred bytes;
     * anything larger is drift this class refuses to interpret.
     */
    static final int MAX_OUTPUT_BYTES = 64 * 1024;

    /** Bounded half of the sha256sum parse (INV-20): two files yield at most two lines. */
    private static final int MAX_HASH_LINES = 16;

    /**
     * Resolves the box home as one word through an explicit inner shell, so
     * {@code $HOME} is expanded by that shell rather than trusted to survive
     * the outer remote shell's quoting.
     */
    private static final String HOME_COMMAND = "printf %s \"$HOME\"";

    /**
     * The proven start shape (find-omlx-proxy-portable), verbatim: {@code
     * nohup} survives the shell's exit, {@code setsid} detaches the session,
     * {@code uv run} resolves the proxy's inline dependencies, and the three
     * redirections leave ssh's pipes free. The {@code mkdir -p} guards the
     * log redirect — without it a fresh box fails the redirect and the
     * bootstrap would surface as "never became healthy" instead of the true
     * reason. The trailing {@code &} makes the inner shell exit immediately.
     */
    private static final String START_COMMAND =
            "mkdir -p ~/.drydock; nohup setsid uv run ~/omlx_proxy"
                    + " >~/.drydock/omlx_proxy.log 2>&1 </dev/null &";

    /**
     * The vault sanity check (find-box-gateway-auth-ddtool): the exact
     * command the proxy itself runs for gateway auth, with stdout discarded
     * <em>on the box</em> (INV-18) — only its exit status crosses the wire.
     */
    private static final String DDTOOL_CHECK =
            "ddtool auth token rapid-ai-platform --datacenter us1.ddbuild.io"
                    + " --http-header >/dev/null";

    private final CommandRunner runner;
    private final BoxShell shell;
    private final Path localProxy;
    private final Path localConfig;
    private final Duration pollInterval;
    private final Duration healthWindow;

    /** The production shape: standard local artifact paths and the 5-minute health window. */
    public ProxyBootstrap(CommandRunner runner) {
        this(runner, DEFAULT_LOCAL_PROXY, defaultLocalConfig(),
                HEALTH_POLL_INTERVAL, HEALTH_POLL_WINDOW);
    }

    /**
     * The full shape; the last two parameters exist so tests can shrink the
     * health-poll rhythm without real sleeping.
     */
    ProxyBootstrap(CommandRunner runner, Path localProxy, Path localConfig,
                   Duration pollInterval, Duration healthWindow) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.shell = new BoxShell(runner);
        this.localProxy = Objects.requireNonNull(localProxy, "localProxy");
        this.localConfig = Objects.requireNonNull(localConfig, "localConfig");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
        this.healthWindow = Objects.requireNonNull(healthWindow, "healthWindow");
    }

    /** The typed bootstrap refusal (INV-19): its message is the visible reason, and it is secret-free (INV-18). */
    public static final class BootstrapException extends Exception {

        BootstrapException(String reason) {
            super(reason);
        }

        /** The one-line reason the launch path surfaces; never carries token or config material. */
        public String reason() {
            return getMessage();
        }
    }

    /**
     * Makes the box serve the gateway-only proxy, doing only what is missing:
     * resolves the box home, copies an artifact over scp only when its
     * SHA-256 differs from the box-side value (or the box file is absent),
     * starts the proxy only when the health check fails, and finishes with
     * the ddtool vault check. A second run on a bootstrapped box issues no
     * scp and no start command. Blocking — never call on the EDT.
     */
    public void ensure(String host) throws InterruptedException, BootstrapException {
        Objects.requireNonNull(host, "host");
        try {
            doEnsure(host);
        } catch (BootstrapException e) {
            LOG.log(Level.WARNING, host + ": proxy bootstrap failed: " + e.reason());
            throw e;
        }
    }

    private void doEnsure(String host) throws InterruptedException, BootstrapException {
        requireArtifact(localProxy, "omlx_proxy binary");
        requireArtifact(localConfig, "omlx_proxy config");

        final String configText;
        try {
            configText = Files.readString(localConfig, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BootstrapException(
                    "the local omlx_proxy config at " + localConfig + " could not be read");
        }
        final JsonValue derived;
        try {
            derived = gatewayOnly(JsonParser.parse(configText));
        } catch (JsonParseException | IllegalArgumentException e) {
            // The parser's message quotes fragments of the file it rejected;
            // the reason stays content-free (INV-18).
            throw new BootstrapException(
                    "the local omlx_proxy config at " + localConfig + " is not a valid JSON object");
        }

        Path staged = null;
        try {
            try {
                staged = stageConfig(derived);
            } catch (IOException e) {
                throw new BootstrapException("staging the box proxy config failed");
            }
            String home = boxHome(host);
            Map<String, String> boxHashes = boxHashes(host, home);
            String boxProxy = home + "/omlx_proxy";
            String boxConfig = home + "/.omlx_proxy";
            if (!sha256Hex(localProxy).equals(boxHashes.get(boxProxy))) {
                copyArtifact(host, localProxy, boxProxy, "the omlx_proxy binary");
            }
            if (!sha256Hex(staged).equals(boxHashes.get(boxConfig))) {
                copyArtifact(host, staged, boxConfig, "the gateway-only proxy config");
            }
            if (!healthy(host)) {
                startProxy(host);
                awaitHealthy(host);
            }
            authCheck(host);
        } finally {
            if (staged != null) {
                try {
                    Files.deleteIfExists(staged);
                } catch (IOException e) {
                    // The 0600 temp file in the system temp dir is the only
                    // secret-bearing artifact this class ever writes locally;
                    // a failed delete is logged (path only, never content) so
                    // it cannot pass silently.
                    LOG.log(Level.WARNING, "could not delete the staged proxy config " + staged);
                }
            }
        }
    }

    /**
     * The pure gateway-only config transform: keeps every member, then sets
     * {@code routing.enabled=false}, {@code chain_headroom=false},
     * {@code local_haiku}/{@code local_sonnet}/{@code local_opus} to null and
     * {@code proxy_port} to 4000, removes {@code omlx_key} and
     * {@code omlx_url} (the box authenticates through its own vault session,
     * and personal keys are 401-rejected — find-box-gateway-auth-ddtool),
     * and keeps {@code gateway_headers} untouched. The input tree is never
     * mutated.
     *
     * @throws IllegalArgumentException when {@code config} is not a JSON
     *         object, or its {@code routing} member is neither an object nor
     *         null — the caller folds both into its typed config failure
     */
    public static JsonValue gatewayOnly(JsonValue config) {
        if (!(config instanceof JsonObject root)) {
            throw new IllegalArgumentException("config must be a JSON object");
        }
        JsonObject result = new JsonObject(new LinkedHashMap<>(root.members()));
        JsonObject routing;
        if (result.get("routing") instanceof JsonObject existing) {
            routing = new JsonObject(new LinkedHashMap<>(existing.members()));
        } else if (!result.has("routing") || result.get("routing") instanceof JsonNull) {
            routing = JsonObject.empty();
        } else {
            throw new IllegalArgumentException("routing must be a JSON object");
        }
        routing.put("enabled", new JsonBoolean(false));
        result.put("routing", routing);
        result.put("chain_headroom", new JsonBoolean(false));
        result.put("local_haiku", JsonNull.INSTANCE);
        result.put("local_sonnet", JsonNull.INSTANCE);
        result.put("local_opus", JsonNull.INSTANCE);
        result.put("proxy_port", JsonNumber.of(4000));
        result.members().remove("omlx_key");
        result.members().remove("omlx_url");
        return result;
    }

    private void requireArtifact(Path path, String what) throws BootstrapException {
        if (Files.notExists(path)) {
            throw new BootstrapException("the local " + what + " is missing at " + path);
        }
        if (!Files.isReadable(path)) {
            throw new BootstrapException("the local " + what + " at " + path + " is not readable");
        }
    }

    private Path stageConfig(JsonValue derived) throws IOException {
        Path staged = Files.createTempFile("drydock-omlx-config-", ".json");
        // 0600 before a single byte is written: the derived config still
        // carries the gateway headers, and a shared-readable temp file would
        // leak them to every local user for the copy's duration.
        Files.setPosixFilePermissions(staged, PosixFilePermissions.fromString("rw-------"));
        Files.writeString(staged, JsonWriter.write(derived), StandardCharsets.UTF_8);
        return staged;
    }

    /** One box round trip resolving {@code $HOME} through an explicit inner shell. */
    private String boxHome(String host) throws InterruptedException, BootstrapException {
        ProcessResult result = runBox(host, List.of("sh", "-c", HOME_COMMAND),
                "resolving the box home directory");
        if (result.exitCode() != 0) {
            throw new BootstrapException("resolving the box home directory failed (exit "
                    + result.exitCode() + ")");
        }
        String home = result.stdout().strip();
        if (home.isBlank() || !home.startsWith("/") || home.contains("\n")) {
            throw new BootstrapException("the box home directory could not be determined");
        }
        return home;
    }

    /**
     * The box-side hashes of the two artifacts, keyed by absolute path. A
     * file that is missing on the box is simply absent from the map — its
     * local hash cannot match, so it is copied; that is the plan's "or the
     * box file is absent". Exit 1 carrying the missing-file marker is the
     * only partial failure accepted (GNU sha256sum still hashes the files
     * that exist); every other non-zero exit is a typed failure, never an
     * empty result (INV-9).
     */
    private Map<String, String> boxHashes(String host, String home)
            throws InterruptedException, BootstrapException {
        String proxyPath = home + "/omlx_proxy";
        String configPath = home + "/.omlx_proxy";
        ProcessResult result = runBox(host, List.of("sha256sum", proxyPath, configPath),
                "the box-side sha256sum");
        if (result.exitCode() != 0 && !(result.exitCode() == 1
                && result.stderr().contains("No such file or directory"))) {
            throw new BootstrapException("the box-side sha256sum failed (exit " + result.exitCode()
                    + "): " + ProcessRunner.excerpt(
                    result.stderr().isBlank() ? result.stdout() : result.stderr()));
        }
        return parseHashLines(result.stdout());
    }

    private static Map<String, String> parseHashLines(String stdout) throws BootstrapException {
        String[] lines = stdout.split("\n", -1);
        if (lines.length > MAX_HASH_LINES) {
            throw new BootstrapException("the box-side sha256sum produced more output than expected");
        }
        Map<String, String> hashes = new HashMap<>();
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            // A sha256sum line is "<64 hex>  <path>"; a line that does not
            // parse is drift, and the file it would have named simply counts
            // as absent — the copy is always the safe outcome.
            int separator = line.indexOf("  ");
            if (separator != 64) {
                continue;
            }
            hashes.put(line.substring(separator + 2),
                    line.substring(0, 64).toLowerCase(Locale.ROOT));
        }
        return hashes;
    }

    private void copyArtifact(String host, Path local, String remotePath, String what)
            throws InterruptedException, BootstrapException {
        BoxShell.BoxResult result = shell.copy(local, host, remotePath, COPY_TIMEOUT);
        if (!(result instanceof BoxShell.BoxResult.Ok)) {
            // describe() carries the scp transport stderr only — the payload
            // content never crosses a log or a message (INV-18).
            throw new BootstrapException("copying " + what + " to the box failed: " + result.describe());
        }
    }

    /** The health probe: curl succeeds exactly when the proxy answers on 127.0.0.1:4000. */
    private boolean healthy(String host) throws InterruptedException, BootstrapException {
        ProcessResult result = runBox(host,
                List.of("curl", "-fsS", "-o", "/dev/null", PROXY_URL),
                "the proxy health check");
        return result.exitCode() == 0;
    }

    /** Fires the proven nohup/setsid start shape; the trailing {@code &} makes the box side return at once. */
    private void startProxy(String host) throws InterruptedException, BootstrapException {
        ProcessResult result = runBox(host, List.of("sh", "-c", START_COMMAND),
                "starting the box proxy");
        if (result.exitCode() != 0) {
            throw new BootstrapException("starting the box proxy failed on the box (exit "
                    + result.exitCode() + "): " + ProcessRunner.excerpt(
                    result.stderr().isBlank() ? result.stdout() : result.stderr()));
        }
    }

    /**
     * Polls health until the window closes. One bad round trip — unhealthy,
     * unreachable, timed out — is retried inside the window; the deadline
     * decides, never a single poll.
     */
    private void awaitHealthy(String host) throws InterruptedException, BootstrapException {
        long deadline = System.nanoTime() + healthWindow.toNanos();
        while (true) {
            try {
                if (healthy(host)) {
                    return;
                }
            } catch (BootstrapException e) {
                // retried below until the deadline
            }
            if (System.nanoTime() - deadline >= 0) {
                throw new BootstrapException("the box proxy did not become healthy on "
                        + PROXY_URL + " within " + healthWindow.toSeconds() + " s"
                        + " — the box's ~/.drydock/omlx_proxy.log has the proxy's own output");
            }
            Thread.sleep(pollInterval.toMillis());
        }
    }

    /**
     * The final sanity check: the ddtool vault mint the proxy itself depends
     * on (find-box-gateway-auth-ddtool, find-box-vault-session-fresh). Exit
     * status only — its stdout is discarded on the box and its exit code is
     * the only value this class reads from it (INV-18).
     */
    private void authCheck(String host) throws InterruptedException, BootstrapException {
        ProcessResult result = runBox(host, List.of("sh", "-c", DDTOOL_CHECK),
                "the box ddtool vault check");
        if (result.exitCode() != 0) {
            throw new BootstrapException("the box's ddtool vault check failed (exit "
                    + result.exitCode() + "); the box cannot mint a gateway token");
        }
    }

    /**
     * Runs one ssh round trip through the {@link CommandRunner} seam and
     * turns every transport-level failure into the typed refusal it is:
     * unreachable (exit 255, with the {@code workspaces ssh-config} hint,
     * ASM-9), timed out, ssh missing, and output past the cap are all
     * distinct reasons (INV-9) — never an empty result standing in for a
     * failure. A remote exit code other than 255 is returned for the caller
     * to interpret, because each step means something different by it.
     */
    private ProcessResult runBox(String host, List<String> remoteArgs, String what)
            throws InterruptedException, BootstrapException {
        List<String> argv = SshCommandBuilder.remoteCommand(host, remoteArgs);
        ProcessResult result;
        try {
            result = runner.run(argv,
                    new ProcessRunner.Options(null, REMOTE_TIMEOUT, true, Map.of()),
                    MAX_OUTPUT_BYTES);
        } catch (ProcessTimeoutException e) {
            throw new BootstrapException(what + " timed out after " + REMOTE_TIMEOUT.toSeconds() + " s");
        } catch (IOException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (message.contains("exceeded")) {
                throw new BootstrapException(what + " produced more output than the bootstrap accepts");
            }
            throw new BootstrapException(what + " could not run: ssh is not installed");
        }
        if (result.exitCode() == SSH_FAILURE_EXIT) {
            throw new BootstrapException(what + " failed: the box is unreachable (ssh exit 255): "
                    + ProcessRunner.excerpt(result.stderr().isBlank() ? result.stdout() : result.stderr())
                    + " — " + BoxShell.SSH_CONFIG_HINT);
        }
        return result;
    }

    private static String sha256Hex(Path file) throws BootstrapException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new BootstrapException("the local omlx_proxy artifact at " + file
                    + " could not be read for hashing");
        }
    }
}
