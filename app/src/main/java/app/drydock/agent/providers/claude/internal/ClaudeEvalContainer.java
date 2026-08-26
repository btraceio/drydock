package app.drydock.agent.providers.claude.internal;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.drydock.agent.api.EvalTokenResolver;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.state.json.JsonParseException;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import app.drydock.state.json.JsonWriter;

/**
 * Runs an eval-mode Claude session inside a container so the eval-specific
 * {@code x-target-account: eval} request header can be carried in a local
 * settings file, instead of injected through the host's omlx_proxy.
 *
 * <p><b>Why a container at all.</b> Claude Code's managed settings
 * ({@code /Library/Application Support/ClaudeCode/managed-settings.json} on
 * macOS, {@code /etc/claude-code/managed-settings.json} on Linux) are the
 * highest-precedence scope and cannot be overridden by env vars or
 * {@code --settings}. They pin {@code ANTHROPIC_BASE_URL} at the host
 * omlx_proxy and carry {@code ANTHROPIC_CUSTOM_HEADERS}; drydock cannot add
 * a header to them. But inside a container there is no managed-settings
 * file, so a settings file drydock seeds in {@code CLAUDE_CONFIG_DIR} is the
 * only source of truth. That seed is copied from the host's managed
 * settings (so auth, base URL, and telemetry stay the same) with two
 * edits: the base URL is rewritten to {@code host.docker.internal} so the
 * container can still reach the host's omlx_proxy, and
 * {@code x-target-account: eval} is appended to
 * {@code ANTHROPIC_CUSTOM_HEADERS}. The auth token is resolved on the host
 * (where {@code ddtool} lives) and passed in as {@code ANTHROPIC_API_KEY},
 * so {@code apiKeyHelper} is dropped from the seed.</p>
 *
 * <p><b>Container runtime.</b> Targets the {@code docker} CLI against
 * whatever context is active -- Colima on macOS, a local or remote daemon
 * on Linux. The image is built once by the {@code claudeEvalImage} Gradle
 * task; {@link #probe} checks both that the daemon answers and that the
 * image is present, so {@code evalAvailable()} honestly gates the UI
 * checkbox.</p>
 *
 * <p><b>Mounts.</b> A git worktree's {@code .git} is a file pointing back
 * into the main repo's object store via an absolute path, so for git to
 * work inside the container both the worktree and the main repo root are
 * bind-mounted at their original host paths (option 4a). The activity-hook
 * script, the activity state directory, and the per-session MCP config
 * file are likewise mounted at their host paths, so the existing
 * {@code --settings}/{@code --mcp-config} flags resolve unchanged and the
 * host-side activity watcher keeps reading the same files.</p>
 *
 * <p>All methods are blocking and must be called off the JavaFX application
 * thread. {@link #probe} is run once at provider init (background) and its
 * result cached.</p>
 */
public class ClaudeEvalContainer {

    private static final Logger LOG = System.getLogger(ClaudeEvalContainer.class.getName());

    /** The image tag built by the {@code claudeEvalImage} Gradle task. */
    private static final String IMAGE = System.getProperty(
            "app.drydock.eval.claude.image", "drydock-claude-eval:latest");

    /**
     * Managed-settings locations, in precedence order (macOS first, then Linux).
     * Resolved per call (not cached at class-load) so a test can point the
     * {@code app.drydock.eval.claude.managedSettings} system property at a
     * fixture before calling {@link #seedSettings}.
     */
    private static List<Path> managedSettingsPaths() {
        String override = System.getProperty("app.drydock.eval.claude.managedSettings");
        if (override != null && !override.isBlank()) {
            return List.of(Path.of(override));
        }
        return List.of(
                Path.of("/Library/Application Support/ClaudeCode/managed-settings.json"),
                Path.of("/etc/claude-code/managed-settings.json"));
    }

    /** The header that routes traffic to the eval account. */
    private static final String EVAL_HEADER = "x-target-account: eval";

    /** Base URL rewritten to reach the host's omlx_proxy from inside the container. */
    private static final String CONTAINER_BASE_URL = "http://host.docker.internal:4000";

    private static final String AUTH_TOKEN_ENV = "ANTHROPIC_API_KEY";
    private static final String CONFIG_DIR_ENV = "CLAUDE_CONFIG_DIR";
    private static final String CUSTOM_HEADERS_KEY = "ANTHROPIC_CUSTOM_HEADERS";
    private static final String BASE_URL_KEY = "ANTHROPIC_BASE_URL";
    private static final String API_KEY_HELPER_KEY = "apiKeyHelper";
    private static final String HELPER_TTL_KEY = "CLAUDE_CODE_API_KEY_HELPER_TTL_MS";

    private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));

    /** {@code gitdir: <path>} in a worktree's {@code .git} file. */
    private static final Pattern GITDIR_LINE = Pattern.compile("^gitdir:\\s+(.+)$");

    private final Path stateDirectory;
    private final EvalTokenResolver tokenResolver;
    /** sessionKey -> setup; populated by {@link #mark}, read by the command builder, cleared by {@link #unmark}. */
    private final Map<String, EvalSetup> setups = new ConcurrentHashMap<>();

    public ClaudeEvalContainer(Path stateDirectory) {
        this(stateDirectory, new app.drydock.agent.providers.claude.internal.DtoolEvalTokenResolver());
    }

    /** For tests: inject the token resolver (e.g. a stub returning a fixed token). */
    public ClaudeEvalContainer(Path stateDirectory, EvalTokenResolver tokenResolver) {
        this.stateDirectory = Objects.requireNonNull(stateDirectory, "stateDirectory");
        this.tokenResolver = Objects.requireNonNull(tokenResolver, "tokenResolver");
    }

    /** The per-session eval setup: the resolved auth token, its JWT expiry, and the seeded config dir. */
    public record EvalSetup(String token, Instant tokenExpiry, Path configDir) { }

    /**
     * Whether the container runtime is reachable and the eval image is
     * present. Best-effort: any failure (refused, timeout, non-zero,
     * missing image) is {@code false}, so eval mode is disabled rather than
     * half-working.
     */
    public boolean probe() {
        try {
            ProcessResult res = ProcessRunner.run(
                    List.of("docker", "image", "inspect", IMAGE),
                    null, java.time.Duration.ofSeconds(5));
            if (res.exitCode() != 0) {
                LOG.log(Level.DEBUG, () -> "eval image probe failed (exit " + res.exitCode()
                        + "); run ./gradlew claudeEvalImage. stderr: "
                        + ProcessRunner.excerpt(res.stderr()));
                return false;
            }
            return true;
        } catch (Exception e) {
            LOG.log(Level.DEBUG, () -> "docker probe failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Seeds the config dir for {@code sessionKey}: writes a settings.json
     * derived from the host's managed settings (with the eval header and
     * rewritten base URL), resolves a fresh auth token, and stashes the
     * result for the command builder. Idempotent -- re-resolves a fresh
     * token on every call (so a resume refreshes an expiring token).
     *
     * @return the setup, or {@code Optional#empty()} if the token could not
     *         be resolved (the launch will then fail loudly in the command
     *         builder rather than silently shipping an unauthenticated
     *         container)
     */
    public Optional<EvalSetup> mark(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return Optional.empty();
        }
        Path configDir = stateDirectory.resolve("eval").resolve(sessionKey);
        try {
            Files.createDirectories(configDir);
            seedSettings(configDir);
        } catch (IOException e) {
            LOG.log(Level.WARNING, () -> "Could not seed eval config dir " + configDir + ": " + e.getMessage());
            return Optional.empty();
        }
        Optional<EvalTokenResolver.ResolvedToken> resolved = tokenResolver.resolveToken();
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        EvalTokenResolver.ResolvedToken token = resolved.get();
        EvalSetup setup = new EvalSetup(token.token(), token.expiry().orElse(null), configDir);
        setups.put(sessionKey, setup);
        return Optional.of(setup);
    }

    /** Reverses {@link #mark}: deletes the per-session config dir and drops the stash. Idempotent. */
    public void unmark(String sessionKey) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return;
        }
        setups.remove(sessionKey);
        Path configDir = stateDirectory.resolve("eval").resolve(sessionKey);
        try {
            deleteRecursively(configDir);
        } catch (IOException e) {
            LOG.log(Level.DEBUG, () -> "Could not delete eval config dir " + configDir + ": " + e.getMessage());
        }
    }

    /** The stashed setup for {@code sessionKey}, or empty if {@link #mark} has not run (or failed). */
    public Optional<EvalSetup> setupFor(String sessionKey) {
        return Optional.ofNullable(setups.get(sessionKey));
    }

    /**
     * Writes the inner claude command to {@code <configDir>/entrypoint.sh}
     * and builds the {@code docker run} wrapper that executes it inside the
     * container. The inner command is written to a file (not passed as a
     * docker CMD argument) to avoid nested shell-quoting: the inner command
     * already single-quotes its own arguments, and embedding it inside
     * another {@code sh -c '...'} would require escaping those quotes.
     *
     * @param innerCommand   the bare {@code claude ...} command string the
     *                       provider would normally run on the host
     * @param worktree       the session's working directory (a worktree or
     *                       a repo root), mounted at its own path
     * @param mcpConfigFile  the per-session {@code --mcp-config} file, if any
     * @param hooksDir       the activity-hook script + settings dir
     *                       ({@code <stateDir>/hooks})
     * @param activityDir    the activity state-word dir ({@code <stateDir>/activity})
     */
    public String wrap(EvalSetup setup, String innerCommand, Path worktree, Optional<Path> mcpConfigFile,
                       Path hooksDir, Path activityDir) throws IOException {
        // The auth token is written to an owner-only file in the mounted
        // config dir, and the entrypoint exports it from there -- it never
        // appears in `ps` on the host or in the command preview the UI
        // shows. A 6-hour-lived credential on the argv would be a real
        // exposure (AGENTS.md: "a credential must never be a command-line
        // argument"), and the command string is reachable from log lines
        // and the preview pane.
        Path tokenFile = setup.configDir().resolve("auth-token");
        writeOwnerOnly(tokenFile, setup.token());

        // The entrypoint reads the token from the file, exports it, then
        // runs the bare `claude ...` command. The image's ENTRYPOINT is
        // `sh`, so `docker run <img> <entrypoint>` becomes
        // `sh <entrypoint>` -- no extra `sh` here, or it would be
        // `sh sh <entrypoint>` and fail with "cannot open sh".
        Path entrypoint = setup.configDir().resolve("entrypoint.sh");
        Files.writeString(entrypoint,
                "#!/bin/sh\n"
                + "export " + AUTH_TOKEN_ENV + "=\"$(cat " + shellQuote(tokenFile.toString()) + ")\"\n"
                + innerCommand + "\n",
                StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(entrypoint, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX FS: the container's sh will still read it; the exec bit is cosmetic then.
        }

        Path mainRepoRoot = resolveMainRepoRoot(worktree);
        StringBuilder cmd = new StringBuilder("docker run --rm -it");
        cmd.append(" --add-host=host.docker.internal:host-gateway");
        cmd.append(" -e ").append(CONFIG_DIR_ENV).append('=').append(shellQuote(setup.configDir().toString()));
        cmd.append(" -v ").append(shellQuote(setup.configDir().toString()))
                .append(':').append(shellQuote(setup.configDir().toString()));
        cmd.append(" -v ").append(shellQuote(mainRepoRoot.toString()))
                .append(':').append(shellQuote(mainRepoRoot.toString()));
        if (!mainRepoRoot.equals(worktree)) {
            cmd.append(" -v ").append(shellQuote(worktree.toString()))
                    .append(':').append(shellQuote(worktree.toString()));
        }
        cmd.append(" -v ").append(shellQuote(hooksDir.toString()))
                .append(':').append(shellQuote(hooksDir.toString()));
        cmd.append(" -v ").append(shellQuote(activityDir.toString()))
                .append(':').append(shellQuote(activityDir.toString()));
        mcpConfigFile.ifPresent(f -> cmd.append(" -v ").append(shellQuote(f.toString()))
                .append(':').append(shellQuote(f.toString())));
        cmd.append(" -w ").append(shellQuote(worktree.toString()));
        cmd.append(' ').append(IMAGE);
        cmd.append(' ').append(shellQuote(entrypoint.toString()));
        return cmd.toString();
    }

    /**
     * Reads the host's managed settings, applies the eval edits, and writes
     * {@code <configDir>/settings.json}. If no managed-settings file is
     * present, seeds from an empty object (the container then runs with only
     * the eval header and the token drydock passes). Package-private for
     * unit testing.
     *
     * <p>Mutates the parsed {@link JsonObject} in place: {@link #readManagedSettings}
     * returns a fresh parse on every call, so the mutation is local. If that
     * ever stops being true, this method would corrupt shared state.
     */
    void seedSettings(Path configDir) throws IOException {
        JsonObject root = readManagedSettings().orElseGet(JsonObject::empty);
        JsonObject env = root.members().containsKey("env")
                ? asObject(root.members().get("env"))
                : new JsonObject(new LinkedHashMap<>());
        // Rewrite base URL so the container reaches the host's omlx_proxy.
        putMember(env, BASE_URL_KEY, new JsonString(CONTAINER_BASE_URL));
        // Append the eval header to the custom-headers block.
        String headers = env.members().containsKey(CUSTOM_HEADERS_KEY)
                ? asString(env.members().get(CUSTOM_HEADERS_KEY)) : "";
        if (!headers.isEmpty() && !headers.endsWith("\n")) {
            headers += "\n";
        }
        if (!containsHeader(headers, EVAL_HEADER)) {
            headers += EVAL_HEADER + "\n";
        }
        putMember(env, CUSTOM_HEADERS_KEY, new JsonString(headers));
        // The token is passed as ANTHROPIC_API_KEY env, so the helper is dead weight.
        env.members().remove(HELPER_TTL_KEY);
        putMember(root, "env", env);
        root.members().remove(API_KEY_HELPER_KEY);

        Path target = configDir.resolve("settings.json");
        writeAtomically(target, JsonWriter.write(root));
    }

    /** True if {@code headers} already names the {@code name: value} header line. */
    private static boolean containsHeader(String headers, String header) {
        String name = header.substring(0, header.indexOf(':'));
        for (String line : headers.split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static void putMember(JsonObject obj, String key, JsonValue value) {
        obj.members().put(key, value);
    }

    private static JsonObject asObject(JsonValue v) {
        return v instanceof JsonObject o ? o : new JsonObject(new LinkedHashMap<>());
    }

    private static String asString(JsonValue v) {
        return v instanceof JsonString s ? s.value() : "";
    }

    private Optional<JsonObject> readManagedSettings() {
        for (Path p : managedSettingsPaths()) {
            try {
                if (Files.isReadable(p)) {
                    String content = Files.readString(p, StandardCharsets.UTF_8);
                    JsonValue parsed = JsonParser.parse(content);
                    return parsed instanceof JsonObject o ? Optional.of(o) : Optional.empty();
                }
            } catch (IOException | JsonParseException e) {
                LOG.log(Level.WARNING, () -> "Could not read managed settings " + p + ": " + e.getMessage());
            }
        }
        return Optional.empty();
    }

    /**
     * Resolves the main repo root backing a worktree. A worktree's {@code .git}
     * is a file reading {@code gitdir: <mainRepo>/.git/worktrees/<name>}; the
     * main repo root is three {@code getParent()} calls up from that path. If
     * {@code .git} is a directory (not a worktree), the worktree itself is
     * the repo root.
     */
    static Path resolveMainRepoRoot(Path worktree) {
        Path git = worktree.resolve(".git");
        if (Files.isDirectory(git)) {
            return worktree;
        }
        try {
            String content = Files.readString(git, StandardCharsets.UTF_8).strip();
            Matcher m = GITDIR_LINE.matcher(content);
            if (m.matches()) {
                Path gitdir = Path.of(m.group(1).trim());
                // <mainRepo>/.git/worktrees/<name> -> up three levels -> <mainRepo>
                Path mainRepo = gitdir.getParent() != null ? gitdir.getParent().getParent() : null;
                if (mainRepo != null && mainRepo.getParent() != null) {
                    mainRepo = mainRepo.getParent();
                }
                if (mainRepo != null && Files.isDirectory(mainRepo.resolve(".git"))) {
                    return mainRepo;
                }
            }
        } catch (IOException e) {
            LOG.log(Level.DEBUG, () -> "Could not read worktree .git file at " + git + ": " + e.getMessage());
        }
        // Fallback: mount only the worktree. git operations inside the
        // container may fail, but the working directory is correct.
        LOG.log(Level.WARNING, () -> "Eval container: could not resolve the main repo root backing worktree "
                + worktree + "; mounting only the worktree. Git operations inside the container may fail.");
        return worktree;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            var paths = stream.sorted(java.util.Comparator.reverseOrder()).toList();
            for (Path p : paths) {
                Files.deleteIfExists(p);
            }
        }
    }

    /** Writes {@code content} to an owner-only file (the auth token). */
    private static void writeOwnerOnly(Path target, String content) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temp, content, StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX FS: best-effort; the file is in an owner-only dir.
        }
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temp, content, StandardCharsets.UTF_8);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** POSIX single-quote, so paths with spaces (e.g. "Application Support") survive the shell. */
    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
