package app.drydock.workspace;

import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonBoolean;
import app.drydock.state.json.JsonValue.JsonNull;
import app.drydock.state.json.JsonValue.JsonNumber;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ProxyBootstrap contract on a fake runner: the idempotent bootstrap
 * sequence, the gateway-only config transform, the SHA-256 short-circuit,
 * the health-poll window, the auth-check refusal, and the secrecy rule
 * (AC-19, INV-18) — no gateway_headers value, omlx_key, or config content
 * ever reaching a log line or an exception message.
 */
class ProxyBootstrapTest {

    private static final String HOST = "workspace-test";
    private static final String BOX_HOME = "/home/bits";

    private static final List<String> BATCH_OPTIONS = List.of(
            "-o", "BatchMode=yes",
            "-o", "ConnectTimeout=5",
            "-o", "ServerAliveInterval=3",
            "-o", "ServerAliveCountMax=2");

    private static final String HOME_REMOTE = "'sh' '-c' 'printf %s \"$HOME\"'";
    private static final String SHA_REMOTE =
            "'sha256sum' '/home/bits/omlx_proxy' '/home/bits/.omlx_proxy'";
    private static final String CURL_REMOTE =
            "'curl' '-fsS' '-o' '/dev/null' 'http://127.0.0.1:4000/'";
    private static final String START_REMOTE = "'sh' '-c' 'mkdir -p ~/.drydock; nohup setsid"
            + " uv run ~/omlx_proxy >~/.drydock/omlx_proxy.log 2>&1 </dev/null &'";
    private static final String DDTOOL_REMOTE = "'sh' '-c' 'ddtool auth token"
            + " rapid-ai-platform --datacenter us1.ddbuild.io --http-header >/dev/null'";

    /** Carries the secrets whose non-appearance in logs and exceptions AC-19 pins. */
    private static final String CONFIG_TEXT = """
            {
              "routing": {"enabled": true, "model": "haiku"},
              "chain_headroom": true,
              "local_haiku": "mlx-community/Llama-3.2-1B",
              "local_sonnet": "mlx-community/Qwen2.5-7B",
              "local_opus": "mlx-community/Llama-3.3-70B",
              "proxy_port": 8787,
              "omlx_key": "sk-secret-omlx-key-value",
              "omlx_url": "https://omlx.example.invalid",
              "gateway_headers": {"Authorization": "Bearer secret-gateway-token",
                                  "x-custom": "secret-header-value"},
              "other_member": {"kept": true}
            }
            """;

    private static final List<String> SECRET_MATERIAL = List.of(
            "sk-secret-omlx-key-value", "secret-gateway-token", "secret-header-value");

    /** A fake CommandRunner recording every run; behavior decided by a responder. */
    private static final class FakeRunner implements CommandRunner {

        interface Responder {
            ProcessResult respond(List<String> argv) throws IOException;
        }

        final List<List<String>> argvRuns = new ArrayList<>();
        final List<ProcessRunner.Options> optionsRuns = new ArrayList<>();
        final List<Integer> caps = new ArrayList<>();
        Responder responder = argv -> new ProcessResult(0, "", "");

        @Override
        public ProcessResult run(List<String> argv, ProcessRunner.Options options, int maxOutputBytes)
                throws IOException, InterruptedException {
            argvRuns.add(List.copyOf(argv));
            optionsRuns.add(options);
            caps.add(maxOutputBytes);
            return responder.respond(argv);
        }
    }

    /**
     * A stateful fake box: missing artifacts until shipped, health only
     * after the nohup start, per-field knobs for the failure-path tests.
     */
    private static final class ScriptedBox implements FakeRunner.Responder {

        final Path localProxy;
        boolean boxFilesPresent;
        String proxyBoxHexOverride;
        boolean curlHealthyFromTheStart;
        boolean curlAlwaysFails;
        int ddtoolExit;
        int scpExit;
        String stagedConfigHex;
        Path stagedPath;
        Set<PosixFilePermission> stagedPermissions;
        boolean proxyStarted;

        ScriptedBox(Path localProxy) {
            this.localProxy = localProxy;
        }

        @Override
        public ProcessResult respond(List<String> argv) throws IOException {
            if (argv.get(0).equals("scp")) {
                String destination = argv.get(argv.size() - 1);
                if (destination.endsWith(":" + BOX_HOME + "/.omlx_proxy")) {
                    stagedPath = Path.of(argv.get(2));
                    stagedPermissions = Files.getPosixFilePermissions(stagedPath);
                    if (stagedConfigHex == null) {
                        stagedConfigHex = shaHex(stagedPath);
                    }
                }
                if (scpExit != 0) {
                    return new ProcessResult(scpExit, "", "scp: permission denied");
                }
                return new ProcessResult(0, "", "");
            }
            String remote = argv.get(argv.size() - 1);
            if (remote.contains("printf %s \"$HOME\"")) {
                return new ProcessResult(0, BOX_HOME, "");
            }
            if (remote.contains("sha256sum")) {
                if (!boxFilesPresent) {
                    return new ProcessResult(1, "",
                            "sha256sum: " + BOX_HOME + "/omlx_proxy: No such file or directory\n"
                                    + "sha256sum: " + BOX_HOME + "/.omlx_proxy: No such file or directory");
                }
                String proxyHex = proxyBoxHexOverride != null ? proxyBoxHexOverride : shaHex(localProxy);
                return new ProcessResult(0,
                        proxyHex + "  " + BOX_HOME + "/omlx_proxy\n"
                                + stagedConfigHexForMatch() + "  " + BOX_HOME + "/.omlx_proxy\n", "");
            }
            if (remote.contains("curl")) {
                if (curlAlwaysFails) {
                    return new ProcessResult(7, "", "curl: (7) Failed to connect to 127.0.0.1 port 4000");
                }
                if (proxyStarted || curlHealthyFromTheStart) {
                    return new ProcessResult(0, "", "");
                }
                return new ProcessResult(7, "", "curl: (7) Failed to connect to 127.0.0.1 port 4000");
            }
            if (remote.contains("nohup")) {
                proxyStarted = true;
                return new ProcessResult(0, "", "");
            }
            if (remote.contains("ddtool")) {
                return new ProcessResult(ddtoolExit, "", "");
            }
            throw new AssertionError("unexpected argv: " + argv);
        }

        private String stagedConfigHexForMatch() throws IOException {
            if (stagedConfigHex != null) {
                return stagedConfigHex;
            }
            // The sha256sum probe runs while the staged temp file exists, so
            // the matching hash can be taken straight from it.
            try (var files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
                for (Path candidate : files
                        .filter(f -> f.getFileName().toString().startsWith("drydock-omlx-config-"))
                        .toList()) {
                    return shaHex(candidate);
                }
            }
            throw new AssertionError("no staged config found in the temp dir");
        }
    }

    /** Captures the JUL records ProxyBootstrap (and BoxShell) emit. */
    private static final class Recording extends Handler {
        final List<String> messages = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            messages.add(record.getMessage());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    private static String shaHex(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    private static ProxyBootstrap bootstrap(FakeRunner runner, Path localProxy, Path localConfig) {
        return new ProxyBootstrap(runner, localProxy, localConfig,
                Duration.ofMillis(5), ProxyBootstrap.HEALTH_POLL_WINDOW);
    }

    private static List<String> ssh(String remote) {
        List<String> argv = new ArrayList<>();
        argv.add("ssh");
        argv.addAll(BATCH_OPTIONS);
        argv.add("--");
        argv.add(HOST);
        argv.add(remote);
        return argv;
    }

    private static void writeArtifacts(Path localProxy, Path localConfig) throws IOException {
        Files.writeString(localProxy, "#!/usr/bin/env uv\n# the proxy script\n");
        Files.writeString(localConfig, CONFIG_TEXT);
    }

    // ------------------------------------------------------------------
    // Idempotency (AC-19): a second run copies and starts nothing
    // ------------------------------------------------------------------

    @Test
    void aSecondRunOnABootstrappedBoxIssuesNoScpAndNoNohup(
            @TempDir Path dir) throws Exception {
        Path localProxy = dir.resolve("omlx_proxy");
        Path localConfig = dir.resolve(".omlx_proxy");
        writeArtifacts(localProxy, localConfig);

        FakeRunner runner = new FakeRunner();
        ScriptedBox box = new ScriptedBox(localProxy);
        runner.responder = box;
        ProxyBootstrap bootstrap = bootstrap(runner, localProxy, localConfig);

        bootstrap.ensure(HOST);
        int coldRuns = runner.argvRuns.size();
        assertEquals(8, coldRuns, "cold run: home, sha256sum, 2x scp, curl, nohup, curl, ddtool");

        box.boxFilesPresent = true;
        bootstrap.ensure(HOST);

        List<List<String>> warmRuns = runner.argvRuns.subList(coldRuns, runner.argvRuns.size());
        assertEquals(4, warmRuns.size(), "warm run: home, sha256sum, curl, ddtool only");
        for (List<String> argv : warmRuns) {
            assertFalse(argv.get(0).equals("scp"), "no scp on an already-bootstrapped box: " + argv);
            assertFalse(String.join(" ", argv).contains("nohup"), "no start on a healthy box: " + argv);
        }
        assertEquals(List.of(HOME_REMOTE, SHA_REMOTE, CURL_REMOTE, DDTOOL_REMOTE),
                warmRuns.stream().map(argv -> argv.get(argv.size() - 1)).toList());
    }

    // ------------------------------------------------------------------
    // The pinned cold-bootstrap command sequence (INV-5/6/7, AC-20)
    // ------------------------------------------------------------------

    @Test
    void aColdBootstrapIssuesExactlyThePinnedCommandSequence(@TempDir Path dir) throws Exception {
        Path localProxy = dir.resolve("omlx_proxy");
        Path localConfig = dir.resolve(".omlx_proxy");
        writeArtifacts(localProxy, localConfig);

        FakeRunner runner = new FakeRunner();
        runner.responder = new ScriptedBox(localProxy);
        bootstrap(runner, localProxy, localConfig).ensure(HOST);

        assertEquals(8, runner.argvRuns.size(),
                "cold run: home, sha256sum, 2x scp, curl, nohup, curl, ddtool");
        assertEquals(ssh(HOME_REMOTE), runner.argvRuns.get(0));
        assertEquals(ssh(SHA_REMOTE), runner.argvRuns.get(1));
        assertEquals(
                List.of("scp", "--", localProxy.toString(), HOST + ":" + BOX_HOME + "/omlx_proxy"),
                runner.argvRuns.get(2));
        assertEquals(ssh(CURL_REMOTE), runner.argvRuns.get(4));
        assertEquals(ssh(START_REMOTE), runner.argvRuns.get(5));
        assertEquals(ssh(CURL_REMOTE), runner.argvRuns.get(6));
        assertEquals(ssh(DDTOOL_REMOTE), runner.argvRuns.get(7));

        // The staged-config scp: the destination is pinned; the local side is
        // the 0600 temp file, whose exact name is random by design.
        List<String> configScp = runner.argvRuns.get(3);
        assertEquals("scp", configScp.get(0));
        assertEquals("--", configScp.get(1));
        assertTrue(configScp.get(2).contains("drydock-omlx-config-"),
                "the staged temp file is the scp source: " + configScp);
        assertTrue(configScp.get(2).endsWith(".json"), configScp.toString());
        assertEquals(HOST + ":" + BOX_HOME + "/.omlx_proxy", configScp.get(3));

        // Every ssh round trip: 10 s budget, stdin discarded, bounded output;
        // every scp: the 2-minute copy budget. Argument lists, never shells.
        for (int i = 0; i < runner.argvRuns.size(); i++) {
            ProcessRunner.Options options = runner.optionsRuns.get(i);
            List<String> argv = runner.argvRuns.get(i);
            assertTrue(options.discardInput(), argv.toString());
            if (argv.get(0).equals("ssh")) {
                assertEquals(ProxyBootstrap.MAX_OUTPUT_BYTES, runner.caps.get(i), argv.toString());
                assertEquals(Duration.ofSeconds(10), options.timeout(), argv.toString());
                assertEquals("--", argv.get(argv.size() - 3), "-- immediately before the host");
                assertEquals(HOST, argv.get(argv.size() - 2));
            } else {
                assertEquals("scp", argv.get(0));
                assertEquals("--", argv.get(1));
                assertEquals(Duration.ofMinutes(2), options.timeout(), argv.toString());
                assertEquals(BoxShell.MAX_OUTPUT_BYTES, runner.caps.get(i), argv.toString());
            }
        }
    }

    // ------------------------------------------------------------------
    // The gateway-only config transform (AC-19)
    // ------------------------------------------------------------------

    @Test
    void theTransformForcesGatewayOnlyFieldsAndDropsTheSecretMembers() {
        JsonValue source = JsonParser.parse(CONFIG_TEXT);
        JsonValue transformed = ProxyBootstrap.gatewayOnly(source);

        assertTrue(transformed instanceof JsonObject);
        JsonObject config = (JsonObject) transformed;

        JsonObject routing = (JsonObject) config.get("routing");
        assertFalse(((JsonBoolean) routing.get("enabled")).value());
        assertEquals("haiku", ((JsonString) routing.get("model")).value(),
                "routing keeps its other members");

        assertFalse(((JsonBoolean) config.get("chain_headroom")).value());
        assertTrue(config.get("local_haiku") instanceof JsonNull);
        assertTrue(config.get("local_sonnet") instanceof JsonNull);
        assertTrue(config.get("local_opus") instanceof JsonNull);
        assertEquals(4000, ((JsonNumber) config.get("proxy_port")).asInt());

        assertFalse(config.has("omlx_key"), "personal keys are 401-rejected by the gateway");
        assertFalse(config.has("omlx_url"));

        JsonObject headers = (JsonObject) config.get("gateway_headers");
        assertEquals("Bearer secret-gateway-token",
                ((JsonString) headers.get("Authorization")).value(),
                "gateway_headers pass through untouched");
        assertEquals("secret-header-value", ((JsonString) headers.get("x-custom")).value());

        assertTrue(config.has("other_member"), "every other member is kept");

        // Pure: the input tree is not mutated.
        JsonObject originalRouting = (JsonObject) ((JsonObject) source).get("routing");
        assertTrue(((JsonBoolean) originalRouting.get("enabled")).value());
    }

    @Test
    void theTransformCreatesRoutingWhenTheConfigOmitsIt() {
        JsonValue transformed = ProxyBootstrap.gatewayOnly(
                JsonParser.parse("{\"omlx_key\": \"sk-x\", \"proxy_port\": 1}"));
        JsonObject config = (JsonObject) transformed;
        JsonObject routing = (JsonObject) config.get("routing");
        assertFalse(((JsonBoolean) routing.get("enabled")).value());
        assertFalse(config.has("omlx_key"));
    }

    @Test
    void theTransformRefusesANonObjectConfig() {
        assertThrows(IllegalArgumentException.class,
                () -> ProxyBootstrap.gatewayOnly(JsonParser.parse("[1, 2]")));
        assertThrows(IllegalArgumentException.class,
                () -> ProxyBootstrap.gatewayOnly(JsonParser.parse("{\"routing\": 3}")));
    }

    // ------------------------------------------------------------------
    // The SHA-256 short-circuit (AC-19)
    // ------------------------------------------------------------------

    @Test
    void matchingHashesSkipEveryCopy(@TempDir Path dir) throws Exception {
        Path localProxy = dir.resolve("omlx_proxy");
        Path localConfig = dir.resolve(".omlx_proxy");
        writeArtifacts(localProxy, localConfig);

        FakeRunner runner = new FakeRunner();
        ScriptedBox box = new ScriptedBox(localProxy);
        box.boxFilesPresent = true;
        box.curlHealthyFromTheStart = true;
        runner.responder = box;

        bootstrap(runner, localProxy, localConfig).ensure(HOST);

        assertEquals(4, runner.argvRuns.size(), "home, sha256sum, curl, ddtool — nothing else");
        assertTrue(runner.argvRuns.stream().noneMatch(argv -> argv.get(0).equals("scp")));
        assertTrue(runner.argvRuns.stream().noneMatch(argv ->
                String.join(" ", argv).contains("nohup")));
    }

    @Test
    void aDifferingHashCopiesOnlyThatArtifact(@TempDir Path dir) throws Exception {
        Path localProxy = dir.resolve("omlx_proxy");
        Path localConfig = dir.resolve(".omlx_proxy");
        writeArtifacts(localProxy, localConfig);

        FakeRunner runner = new FakeRunner();
        ScriptedBox box = new ScriptedBox(localProxy);
        box.boxFilesPresent = true;
        box.proxyBoxHexOverride = "0".repeat(64); // the box binary drifted
        box.curlHealthyFromTheStart = true;
        runner.responder = box;

        bootstrap(runner, localProxy, localConfig).ensure(HOST);

        List<List<String>> copies = runner.argvRuns.stream()
                .filter(argv -> argv.get(0).equals("scp")).toList();
        assertEquals(1, copies.size(), "only the drifted artifact is copied");
        assertEquals(HOST + ":" + BOX_HOME + "/omlx_proxy", copies.get(0).get(3));
    }

    // ------------------------------------------------------------------
    // The health-poll window (AC-19, ASM-13)
    // ------------------------------------------------------------------

    @Test
    void anUnhealthyProxyIsPolledUntilTheWindowClosesAndThenFailsVisibly(
            @TempDir Path dir) throws Exception {
        Path localProxy = dir.resolve("omlx_proxy");
        Path localConfig = dir.resolve(".omlx_proxy");
        writeArtifacts(localProxy, localConfig);

        FakeRunner runner = new FakeRunner();
        ScriptedBox box = new ScriptedBox(localProxy);
        box.curlAlwaysFails = true;
        runner.responder = box;

        ProxyBootstrap bootstrap = new ProxyBootstrap(runner, localProxy, localConfig,
                Duration.ofMillis(5), Duration.ofMillis(200));
        ProxyBootstrap.BootstrapException failure = assertThrows(
                ProxyBootstrap.BootstrapException.class, () -> bootstrap.ensure(HOST));

        assertTrue(failure.reason().contains("did not become healthy"), failure.reason());

        long curlRuns = runner.argvRuns.stream()
                .filter(argv -> String.join(" ", argv).contains("curl")).count();
        assertTrue(curlRuns >= 2, "health is polled inside the window, not probed once");
        long starts = runner.argvRuns.stream()
                .filter(argv -> String.join(" ", argv).contains("nohup")).count();
        assertEquals(1, starts, "the start command fires exactly once");
        assertTrue(runner.argvRuns.stream().noneMatch(argv ->
                String.join(" ", argv).contains("ddtool")),
                "a proxy that never became healthy blocks before the auth check");
    }

    // ------------------------------------------------------------------
    // The ddtool auth check (AC-19, INV-19)
    // ------------------------------------------------------------------

    @Test
    void aFailedDdtoolCheckFailsTheLaunchWithItsReason(@TempDir Path dir) throws Exception {
        Path localProxy = dir.resolve("omlx_proxy");
        Path localConfig = dir.resolve(".omlx_proxy");
        writeArtifacts(localProxy, localConfig);

        FakeRunner runner = new FakeRunner();
        ScriptedBox box = new ScriptedBox(localProxy);
        box.ddtoolExit = 1;
        runner.responder = box;

        ProxyBootstrap.BootstrapException failure = assertThrows(
                ProxyBootstrap.BootstrapException.class,
                () -> bootstrap(runner, localProxy, localConfig).ensure(HOST));

        assertTrue(failure.reason().contains("ddtool"), failure.reason());
        assertTrue(failure.reason().contains("exit 1"), failure.reason());
        assertEquals(DDTOOL_REMOTE, runner.argvRuns.get(runner.argvRuns.size() - 1).get(
                runner.argvRuns.get(runner.argvRuns.size() - 1).size() - 1),
                "the ddtool check is the last box command");
    }

    // ------------------------------------------------------------------
    // Local refusals (INV-19): missing artifacts, malformed config
    // ------------------------------------------------------------------

    @Test
    void aMissingLocalProxyRefusesBeforeAnyBoxCommand(@TempDir Path dir) throws Exception {
        Path localConfig = dir.resolve(".omlx_proxy");
        Files.writeString(localConfig, CONFIG_TEXT);
        FakeRunner runner = new FakeRunner();

        ProxyBootstrap.BootstrapException failure = assertThrows(
                ProxyBootstrap.BootstrapException.class,
                () -> bootstrap(runner, dir.resolve("omlx_proxy"), localConfig).ensure(HOST));

        assertTrue(failure.reason().contains("missing"), failure.reason());
        assertTrue(failure.reason().contains("omlx_proxy binary"), failure.reason());
        assertTrue(runner.argvRuns.isEmpty(), "the refusal precedes every box command");
    }

    @Test
    void aMissingLocalConfigRefusesBeforeAnyBoxCommand(@TempDir Path dir) throws Exception {
        Path localProxy = dir.resolve("omlx_proxy");
        Files.writeString(localProxy, "#!/usr/bin/env uv\n");
        FakeRunner runner = new FakeRunner();

        ProxyBootstrap.BootstrapException failure = assertThrows(
                ProxyBootstrap.BootstrapException.class,
                () -> bootstrap(runner, localProxy, dir.resolve(".omlx_proxy")).ensure(HOST));

        assertTrue(failure.reason().contains("missing"), failure.reason());
        assertTrue(failure.reason().contains("omlx_proxy config"), failure.reason());
        assertTrue(runner.argvRuns.isEmpty());
    }

    @Test
    void aMalformedLocalConfigIsATypedFailure(@TempDir Path dir) throws Exception {
        Path localProxy = dir.resolve("omlx_proxy");
        Path localConfig = dir.resolve(".omlx_proxy");
        Files.writeString(localProxy, "#!/usr/bin/env uv\n");
        for (String malformed : List.of("{oops", "[]", "\"just a string\"")) {
            Files.writeString(localConfig, malformed);
            FakeRunner runner = new FakeRunner();

            ProxyBootstrap.BootstrapException failure = assertThrows(
                    ProxyBootstrap.BootstrapException.class,
                    () -> bootstrap(runner, localProxy, localConfig).ensure(HOST));

            assertTrue(failure.reason().contains("not a valid JSON object"), failure.reason());
            assertTrue(runner.argvRuns.isEmpty(), "the refusal precedes every box command");
        }
    }

    // ------------------------------------------------------------------
    // Failed copy, unreachable box, drifted sha256sum (INV-9, INV-19)
    // ------------------------------------------------------------------

    @Test
    void aFailedCopyFailsTheLaunchWithItsReason(@TempDir Path dir) throws Exception {
        Path localProxy = dir.resolve("omlx_proxy");
        Path localConfig = dir.resolve(".omlx_proxy");
        writeArtifacts(localProxy, localConfig);

        FakeRunner runner = new FakeRunner();
        ScriptedBox box = new ScriptedBox(localProxy);
        box.scpExit = 1;
        runner.responder = box;

        ProxyBootstrap.BootstrapException failure = assertThrows(
                ProxyBootstrap.BootstrapException.class,
                () -> bootstrap(runner, localProxy, localConfig).ensure(HOST));

        assertTrue(failure.reason().contains("copying the omlx_proxy binary to the box failed"),
                failure.reason());
        assertTrue(failure.reason().contains("scp: permission denied"), failure.reason());
    }

    @Test
    void anUnreachableBoxFailsWithTheSshConfigHint(@TempDir Path dir) throws Exception {
        Path localProxy = dir.resolve("omlx_proxy");
        Path localConfig = dir.resolve(".omlx_proxy");
        writeArtifacts(localProxy, localConfig);

        FakeRunner runner = new FakeRunner();
        runner.responder = argv -> new ProcessResult(255, "",
                "ssh: connect to host " + HOST + " port 22: Operation timed out");

        ProxyBootstrap.BootstrapException failure = assertThrows(
                ProxyBootstrap.BootstrapException.class,
                () -> bootstrap(runner, localProxy, localConfig).ensure(HOST));

        assertTrue(failure.reason().contains("unreachable"), failure.reason());
        assertTrue(failure.reason().contains(BoxShell.SSH_CONFIG_HINT), failure.reason());
    }

    @Test
    void aDriftedSha256sumExitIsAFailureNeverAnEmptyResult(@TempDir Path dir) throws Exception {
        Path localProxy = dir.resolve("omlx_proxy");
        Path localConfig = dir.resolve(".omlx_proxy");
        writeArtifacts(localProxy, localConfig);

        FakeRunner runner = new FakeRunner();
        runner.responder = argv -> {
            String remote = argv.get(argv.size() - 1);
            if (remote.contains("printf %s \"$HOME\"")) {
                return new ProcessResult(0, BOX_HOME, "");
            }
            if (remote.contains("sha256sum")) {
                return new ProcessResult(2, "", "sha256sum: unrecognized option");
            }
            throw new AssertionError("unexpected argv: " + argv);
        };

        ProxyBootstrap.BootstrapException failure = assertThrows(
                ProxyBootstrap.BootstrapException.class,
                () -> bootstrap(runner, localProxy, localConfig).ensure(HOST));

        assertTrue(failure.reason().contains("sha256sum failed (exit 2)"), failure.reason());
        assertTrue(runner.argvRuns.stream().noneMatch(argv -> argv.get(0).equals("scp")),
                "a failed hash probe never silently degrades into a copy");
    }

    // ------------------------------------------------------------------
    // The staged config's lifecycle: 0600, deleted in finally
    // ------------------------------------------------------------------

    @Test
    void theStagedConfigIs0600AndDeletedAfterwards(@TempDir Path dir) throws Exception {
        Path localProxy = dir.resolve("omlx_proxy");
        Path localConfig = dir.resolve(".omlx_proxy");
        writeArtifacts(localProxy, localConfig);

        FakeRunner runner = new FakeRunner();
        ScriptedBox box = new ScriptedBox(localProxy);
        runner.responder = box;

        bootstrap(runner, localProxy, localConfig).ensure(HOST);

        assertEquals(PosixFilePermissions.fromString("rw-------"), box.stagedPermissions,
                "the staged config is world-inaccessible while it carries the gateway headers");
        assertTrue(Files.notExists(box.stagedPath), "the temp file is deleted in finally");
    }

    // ------------------------------------------------------------------
    // Secrecy (AC-19, INV-18): no secret material in logs or exceptions
    // ------------------------------------------------------------------

    @Test
    void noSecretMaterialEverReachesALogOrAnException(@TempDir Path dir) throws Exception {
        Path localProxy = dir.resolve("omlx_proxy");
        Path localConfig = dir.resolve(".omlx_proxy");
        writeArtifacts(localProxy, localConfig);

        Logger bootstrapLogger = Logger.getLogger(ProxyBootstrap.class.getName());
        Logger boxLogger = Logger.getLogger(BoxShell.class.getName());
        Recording recorder = new Recording();
        bootstrapLogger.addHandler(recorder);
        boxLogger.addHandler(recorder);
        List<String> exceptionMessages = new ArrayList<>();
        try {
            // A full successful bootstrap first: every command runs.
            FakeRunner okRunner = new FakeRunner();
            okRunner.responder = new ScriptedBox(localProxy);
            bootstrap(okRunner, localProxy, localConfig).ensure(HOST);

            // Then the failing auth check: the refusal path logs its reason.
            FakeRunner failRunner = new FakeRunner();
            ScriptedBox failBox = new ScriptedBox(localProxy);
            failBox.ddtoolExit = 1;
            failRunner.responder = failBox;
            try {
                bootstrap(failRunner, localProxy, localConfig).ensure(HOST);
            } catch (ProxyBootstrap.BootstrapException e) {
                exceptionMessages.add(e.reason());
            }
        } finally {
            bootstrapLogger.removeHandler(recorder);
            boxLogger.removeHandler(recorder);
        }

        assertFalse(exceptionMessages.isEmpty(), "the failing run must have refused");
        List<String> everything = new ArrayList<>(recorder.messages);
        everything.addAll(exceptionMessages);
        assertFalse(everything.isEmpty(), "the runs must have logged their outcomes");
        for (String message : everything) {
            for (String secret : SECRET_MATERIAL) {
                assertFalse(message.contains(secret),
                        "secret material leaked into a log or exception: " + message);
            }
        }
    }

    // ------------------------------------------------------------------
    // The ddtool check reads exit status only (INV-18)
    // ------------------------------------------------------------------

    @Test
    void theDdtoolCheckDiscardsItsOutputOnTheBoxItself(@TempDir Path dir) throws Exception {
        Path localProxy = dir.resolve("omlx_proxy");
        Path localConfig = dir.resolve(".omlx_proxy");
        writeArtifacts(localProxy, localConfig);

        FakeRunner runner = new FakeRunner();
        ScriptedBox box = new ScriptedBox(localProxy);
        box.curlHealthyFromTheStart = true;
        box.boxFilesPresent = true;
        runner.responder = box;

        bootstrap(runner, localProxy, localConfig).ensure(HOST);

        String ddtoolRemote = runner.argvRuns.stream()
                .map(argv -> argv.get(argv.size() - 1))
                .filter(remote -> remote.contains("ddtool")).findFirst().orElseThrow();
        assertTrue(ddtoolRemote.endsWith(">/dev/null'"),
                "the vault token's stdout is discarded on the box: " + ddtoolRemote);
    }
}
