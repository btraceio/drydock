package app.drydock.lsp;

import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonBoolean;
import app.drydock.state.json.JsonValue.JsonNull;
import app.drydock.state.json.JsonValue.JsonNumber;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Spec section 9 readiness/lifecycle coverage, driven entirely by fake
 * processes, fake clients and a manual clock — no test launches jdt.ls,
 * and no test sleeps a real minute: every §5/§6 duration (60 s, 5 min,
 * 10 min) elapses by advancing the injected scheduler.
 */
@Timeout(value = 60)
class JdtServerManagerTest {

    @TempDir
    Path tempDir;

    private Harness harness;

    @BeforeEach
    void setUp() throws IOException {
        Path root = Files.createDirectories(tempDir.resolve("wt/repo"));
        Path jdtHome = tempDir.resolve("jdt");
        Files.createDirectories(jdtHome.resolve("config_mac"));
        Path plugins = Files.createDirectories(jdtHome.resolve("plugins"));
        Path launcher = plugins.resolve("org.eclipse.equinox.launcher_1.6.900.v20240613-0350.jar");
        Files.writeString(launcher, "launcher");
        Path dataRoot = Files.createDirectories(tempDir.resolve("lsp"));
        harness = new Harness(root, jdtHome, dataRoot);
    }

    @AfterEach
    void tearDown() {
        if (harness != null && harness.manager != null) {
            harness.manager.close();
        }
    }

    // ------------------------------------------------------------- fixtures

    /** A manual clock + task queue: advancing virtual time fires due tasks in deadline order. */
    private static final class ManualScheduler implements JdtServerManager.Scheduler {
        private record Task(long deadline, Runnable runnable) {
        }

        private long nowMillis;
        private final List<Task> tasks = new ArrayList<>();

        @Override
        public Instant now() {
            return Instant.ofEpochMilli(nowMillis);
        }

        @Override
        public void execute(Runnable task) {
            task.run(); // inline: the whole start pipeline stays deterministic on the test thread
        }

        @Override
        public Timer afterMillis(long delayMillis, Runnable task) {
            Task pending = new Task(nowMillis + delayMillis, task);
            tasks.add(pending);
            return () -> tasks.remove(pending);
        }

        void advanceMillis(long millis) {
            nowMillis += millis;
            while (true) {
                Task due = tasks.stream().filter(t -> t.deadline() <= nowMillis)
                        .min(Comparator.comparingLong(Task::deadline)).orElse(null);
                if (due == null) {
                    return;
                }
                tasks.remove(due);
                due.runnable().run();
            }
        }
    }

    private static final class FakeProcess implements JdtServerManager.ServerProcess {
        final List<String> command;
        final CompletableFuture<Integer> exit = new CompletableFuture<>();
        final AtomicInteger closeCount = new AtomicInteger();
        final List<String> events;

        FakeProcess(List<String> command, List<String> events) {
            this.command = command;
            this.events = events;
            events.add("spawn");
        }

        @Override
        public InputStream stdout() {
            return InputStream.nullInputStream();
        }

        @Override
        public OutputStream stdin() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public CompletableFuture<Integer> exitFuture() {
            return exit;
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
            events.add("process-close");
            exit.complete(0); // the real process's exit signal fires on close, too
        }

        void crash() {
            exit.complete(1);
        }
    }

    private record Sent(String method, JsonValue params) {
    }

    private record Asked(String method, JsonValue params, CompletableFuture<JsonValue> response) {
    }

    private static final class FakeClient implements JdtServerManager.ClientConnection {
        final List<Asked> requests = new CopyOnWriteArrayList<>();
        final List<Sent> notifications = new CopyOnWriteArrayList<>();
        final AtomicInteger closeCount = new AtomicInteger();
        final List<String> events;
        JdtServerManager.ServerNotifications server;
        JsonObject settings;

        FakeClient(List<String> events) {
            this.events = events;
        }

        @Override
        public CompletableFuture<JsonValue> request(String method, JsonValue params) {
            CompletableFuture<JsonValue> response = new CompletableFuture<>();
            requests.add(new Asked(method, params, response));
            events.add("request:" + method);
            if ("shutdown".equals(method)) { // a well-behaved server answers shutdown promptly
                response.complete(JsonNull.INSTANCE);
            }
            return response;
        }

        @Override
        public void notification(String method, JsonValue params) {
            notifications.add(new Sent(method, params));
            events.add("notify:" + method);
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
            events.add("client-close");
        }

        void notifyServer(String method, JsonValue params) {
            server.onNotification(method, params);
        }

        void respondTo(String method, JsonValue result) {
            requests.stream()
                    .filter(a -> a.method().equals(method) && !a.response().isDone())
                    .findFirst()
                    .ifPresentOrElse(a -> a.response().complete(result), () -> fail("no pending " + method));
        }

        List<Asked> asked(String method) {
            return requests.stream().filter(a -> a.method().equals(method)).toList();
        }

        List<Sent> sent(String method) {
            return notifications.stream().filter(s -> s.method().equals(method)).toList();
        }
    }

    private static final class Harness {
        final Path root;
        final Path jdtHome;
        final Path dataRoot;
        final ManualScheduler time = new ManualScheduler();
        final List<FakeProcess> processes = new CopyOnWriteArrayList<>();
        final List<FakeClient> clients = new CopyOnWriteArrayList<>();
        final List<String> events = new CopyOnWriteArrayList<>();
        volatile IOException spawnFailure;
        volatile JdtServerManager.JavaProbe probe = javaHome -> "21.0.1";
        volatile JdtServerManager manager;

        Harness(Path root, Path jdtHome, Path dataRoot) {
            this.root = root;
            this.jdtHome = jdtHome;
            this.dataRoot = dataRoot;
        }

        FakeProcess process() {
            return processes.get(processes.size() - 1);
        }

        FakeClient client() {
            return clients.get(clients.size() - 1);
        }

        JdtServerManager newManagerFor(Path worktreeRoot, Path javaHome) {
            return new JdtServerManager(worktreeRoot, new JdtServerManager.LaunchConfig(jdtHome, javaHome),
                    probe,
                    (command, workingDirectory) -> {
                        if (spawnFailure != null) {
                            throw spawnFailure;
                        }
                        FakeProcess process = new FakeProcess(command, events);
                        processes.add(process);
                        return process;
                    },
                    (process, settings, listener) -> {
                        FakeClient client = new FakeClient(events);
                        client.server = listener;
                        client.settings = settings;
                        clients.add(client);
                        return client;
                    },
                    time, dataRoot);
        }

        JdtServerManager newManager(Path javaHome) {
            manager = newManagerFor(root, javaHome);
            return manager;
        }
    }

    // -------------------------------------------------------- json + driving

    private static JsonString str(String value) {
        return new JsonString(value);
    }

    private static JsonNumber num(long value) {
        return JsonNumber.of(value);
    }

    private static JsonObject obj(Object... members) {
        LinkedHashMap<String, JsonValue> map = new LinkedHashMap<>();
        for (int i = 0; i < members.length; i += 2) {
            map.put((String) members[i], (JsonValue) members[i + 1]);
        }
        return new JsonObject(map);
    }

    private Path srcFile() {
        return harness.root.resolve("src/Foo.java");
    }

    private JdtServerManager manager() {
        return harness.manager;
    }

    /** A tier-3-eligible query: empty unless INDEXED, triggers the lazy start side effects. */
    private void query() {
        assertTrue(manager().references(srcFile(), 5, 3).isEmpty(),
                "a query outside INDEXED must fall through immediately");
    }

    private void respondInitialize() {
        harness.client().respondTo("initialize", JsonNull.INSTANCE);
    }

    private void serviceReady() {
        harness.client().notifyServer("language/status", obj("type", str("ServiceReady")));
    }

    private void importStarted(String id) {
        harness.client().notifyServer("language/progressReport",
                obj("taskType", str("Importing Gradle project"), "id", str(id), "status", str("Started")));
    }

    private void importCompleted(String id) {
        harness.client().notifyServer("language/progressReport",
                obj("taskType", str("Importing Gradle project"), "id", str(id), "status", str("Completed")));
    }

    private void importError(String id) {
        harness.client().notifyServer("language/progressReport",
                obj("taskType", str("Importing Gradle project"), "id", str(id), "status", str("Error"),
                        "message", str("Gradle daemon died")));
    }

    private void dollarBegin(String token, String title) {
        harness.client().notifyServer("$/progress",
                obj("token", str(token), "value", obj("kind", str("begin"), "title", str(title))));
    }

    private void dollarEnd(String token) {
        harness.client().notifyServer("$/progress",
                obj("token", str(token), "value", obj("kind", str("end"))));
    }

    private void driveToIndexed() {
        query();
        assertEquals(JdtServerManager.Readiness.STARTING, manager().state());
        respondInitialize();
        serviceReady();
        assertEquals(JdtServerManager.Readiness.READY, manager().state());
        importStarted("t1");
        assertEquals(JdtServerManager.Readiness.INDEXING, manager().state());
        importCompleted("t1");
        assertEquals(JdtServerManager.Readiness.INDEXED, manager().state());
    }

    // -------------------------------------------------------- lazy start

    @Test
    void constructionSpawnsNothingAndFirstQueryStartsLazily() {
        harness.newManager(null);
        assertTrue(harness.processes.isEmpty(), "an idle worktree pays nothing until queried");
        query();
        assertEquals(1, harness.processes.size());
        assertEquals(JdtServerManager.Readiness.STARTING, manager().state());
        // Unavailability is immediate: another query before readiness falls through, no new server.
        assertTrue(manager().definition(srcFile(), 1, 1).isEmpty());
        assertEquals(1, harness.processes.size());
        assertEquals(JdtServerManager.Readiness.STARTING, manager().state());
    }

    // ------------------------------------------------------ config validation

    @Test
    void zeroLauncherJarsIsRefusedNamingWhatWasFound() throws IOException {
        Files.writeString(harness.jdtHome.resolve("plugins/other.plugin_1.jar"), "x");
        Files.delete(harness.jdtHome.resolve(
                "plugins/org.eclipse.equinox.launcher_1.6.900.v20240613-0350.jar"));
        harness.newManager(null);
        query();
        assertEquals(JdtServerManager.Readiness.INVALID, manager().state());
        assertTrue(harness.processes.isEmpty(), "an invalid config never spawns the server");
        assertTrue(manager().hint().contains("found none"), manager().hint());
        assertTrue(manager().hint().contains("other.plugin_1.jar"), manager().hint());
    }

    @Test
    void multipleLauncherJarsIsRefusedNamingBothMatches() throws IOException {
        Files.writeString(harness.jdtHome.resolve(
                "plugins/org.eclipse.equinox.launcher_1.6.400.v2023.01.jar"), "x");
        harness.newManager(null);
        query();
        assertEquals(JdtServerManager.Readiness.INVALID, manager().state());
        String hint = manager().hint();
        assertTrue(hint.contains("org.eclipse.equinox.launcher_1.6.900.v20240613-0350.jar"), hint);
        assertTrue(hint.contains("org.eclipse.equinox.launcher_1.6.400.v2023.01.jar"), hint);
        assertTrue(hint.contains("found 2"), hint);
    }

    @Test
    void missingConfigMacIsRefused() throws IOException {
        try (Stream<Path> children = Files.list(harness.jdtHome)) {
            for (Path child : children.toList()) {
                if ("config_mac".equals(child.getFileName().toString())) {
                    deleteTree(child);
                }
            }
        }
        harness.newManager(null);
        query();
        assertEquals(JdtServerManager.Readiness.INVALID, manager().state());
        assertTrue(manager().hint().contains("config_mac"), manager().hint());
        assertTrue(harness.processes.isEmpty());
    }

    @Test
    void javaOlderThan17IsRefusedWithTheVersionNamed() {
        harness.probe = javaHome -> "11.0.2";
        harness.newManager(null);
        query();
        assertEquals(JdtServerManager.Readiness.INVALID, manager().state());
        String hint = manager().hint();
        assertTrue(hint.contains("Java 17+"), hint);
        assertTrue(hint.contains("11.0.2"), hint);
        assertTrue(harness.processes.isEmpty());
    }

    @Test
    void missingJavaProbeDetailIsPreservedInTheRefusal() {
        harness.probe = javaHome -> {
            throw new IOException("no java executable on PATH");
        };
        harness.newManager(null);
        query();
        assertEquals(JdtServerManager.Readiness.INVALID, manager().state());
        assertTrue(manager().hint().contains("no java executable on PATH"), manager().hint());
        assertTrue(harness.processes.isEmpty());
    }

    // ------------------------------------------------------------ spawn/crash

    @Test
    void spawnFailureFallsThroughAndRetriesOnTheNextQuery() {
        harness.newManager(null);
        harness.spawnFailure = new IOException("no such executable");
        query();
        assertEquals(JdtServerManager.Readiness.NOT_STARTED, manager().state(),
                "a spawn failure stays retryable, never terminal");
        assertTrue(harness.processes.isEmpty());
        harness.spawnFailure = null;
        query();
        assertEquals(1, harness.processes.size(), "the next query retries the spawn");
        assertEquals(JdtServerManager.Readiness.STARTING, manager().state());
    }

    @Test
    void closeDuringStartupCancelsIt() {
        harness.newManager(null);
        query(); // STARTING, initialize pending
        manager().close();
        assertEquals(JdtServerManager.Readiness.CLOSED, manager().state());
        assertEquals(1, harness.process().closeCount.get());
        assertEquals(1, harness.client().closeCount.get());
        assertTrue(harness.client().asked("shutdown").isEmpty(),
                "a mid-startup server is cancelled, not gracefully shut down");
        // A late initialize response completes nothing: no initialized notification is sent.
        respondInitialize();
        assertTrue(harness.client().sent("initialized").isEmpty());
        manager().close(); // idempotent
        assertEquals(1, harness.process().closeCount.get());
    }

    @Test
    void eofFailsInFlightQueries() {
        harness.newManager(null);
        driveToIndexed();
        Optional<CompletableFuture<List<JdtServerManager.Location>>> result =
                manager().definition(srcFile(), 3, 7);
        assertTrue(result.isPresent());
        assertFalse(result.get().isDone());
        harness.process().crash();
        assertTrue(result.get().isCompletedExceptionally(), "a crash must fail its in-flight queries");
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> result.get().get(1, TimeUnit.SECONDS));
        assertInstanceOf(JdtServerManager.QueryUnavailableException.class, failure.getCause());
        assertEquals(JdtServerManager.Readiness.DOWN, manager().state());
    }

    @Test
    void oneRestartAfterBackoffThenDisabledOnSecondCrashWithinFiveMinutes() {
        harness.newManager(null);
        driveToIndexed();
        harness.process().crash();
        assertEquals(JdtServerManager.Readiness.DOWN, manager().state());
        // Inside the 30 s backoff: unavailable, no restart.
        harness.time.advanceMillis(5_000);
        query();
        assertEquals(1, harness.processes.size());
        // After the backoff: the same query pattern restarts the server automatically.
        harness.time.advanceMillis(25_000);
        query();
        assertEquals(2, harness.processes.size());
        assertEquals(JdtServerManager.Readiness.STARTING, manager().state());
        // The restarted server reaches INDEXED, then crashes again within 5 minutes.
        respondInitialize();
        serviceReady();
        importStarted("r1");
        importCompleted("r1");
        assertEquals(JdtServerManager.Readiness.INDEXED, manager().state());
        harness.process().crash();
        assertEquals(JdtServerManager.Readiness.DISABLED, manager().state());
        assertTrue(manager().hint().contains("disabled"), manager().hint());
        // The tier stays off for the session: no further spawns.
        harness.time.advanceMillis(60_000);
        query();
        assertEquals(2, harness.processes.size());
    }

    // ------------------------------------------------------ launch/initialize

    @Test
    void launchCommandAndInitializePayloadAreExact() throws IOException {
        Path javaHome = Files.createDirectories(tempDir.resolve("jdk17"));
        harness.newManager(javaHome);
        query();
        Path dataDir;
        try (Stream<Path> children = Files.list(harness.dataRoot)) {
            List<Path> dirs = children.toList();
            assertEquals(1, dirs.size(), "one per-worktree -data dir under ~/.drydock/lsp/");
            dataDir = dirs.get(0);
        }
        assertTrue(dataDir.getFileName().toString().matches("^repo-[0-9a-f]{12}$"),
                "readable basename plus stable hash: " + dataDir.getFileName());
        List<String> expectedCommand = List.of(
                javaHome.resolve("bin/java").toString(),
                "-Declipse.application=org.eclipse.jdt.ls.core.id1",
                "-Dosgi.bundles.defaultStartLevel=4",
                "-Declipse.product=org.eclipse.jdt.ls.core.product",
                "-Xmx1G",
                "--add-modules=ALL-SYSTEM",
                "--add-opens", "java.base/java.util=ALL-UNNAMED",
                "--add-opens", "java.base/java.lang=ALL-UNNAMED",
                "-jar", harness.jdtHome.resolve(
                        "plugins/org.eclipse.equinox.launcher_1.6.900.v20240613-0350.jar").toString(),
                "-configuration", harness.jdtHome.resolve("config_mac").toString(),
                "-data", dataDir.toString());
        assertEquals(expectedCommand, harness.process().command);
        // Initialize payload: one worktree folder, empty capabilities, extendedClientCapabilities,
        // the settings.java block, and no bundles.
        Asked initialize = harness.client().asked("initialize").get(0);
        JsonObject expectedSettings = obj(
                "import", obj("gradle", obj("enabled", new JsonBoolean(true),
                        "wrapper", obj("enabled", new JsonBoolean(true)))),
                "configuration", obj("updateBuildConfiguration", str("automatic")));
        JsonObject expected = obj(
                "workspaceFolders", new JsonArray(List.of(
                        obj("uri", str(harness.root.toUri().toString()), "name", str("repo")))),
                "capabilities", obj(),
                "initializationOptions", obj(
                        "extendedClientCapabilities", obj("progressReportProvider", new JsonBoolean(true)),
                        "settings", obj("java", expectedSettings)));
        assertEquals(expected, initialize.params());
        JsonObject initializationOptions = (JsonObject) ((JsonObject) initialize.params())
                .get("initializationOptions");
        assertFalse(initializationOptions.has("bundles"), "no debug/test OSGi bundles");
        // The client is handed the same settings.java block for workspace/configuration answers.
        assertEquals(expectedSettings, harness.client().settings);
        // After the initialize response: the mandatory initialized notification, before ServiceReady.
        respondInitialize();
        assertEquals(1, harness.client().sent("initialized").size());
    }

    // ------------------------------------------------------- readiness machine

    @Test
    void readinessMachineViaProgressReportServesQueriesWithLspNativeLines() throws Exception {
        harness.newManager(null);
        driveToIndexed();
        // Only INDEXED serves: the query's request carries 0-based positions and includeDeclaration.
        Optional<CompletableFuture<List<JdtServerManager.Location>>> result =
                manager().references(srcFile(), 9, 4);
        assertTrue(result.isPresent());
        Asked references = harness.client().asked("textDocument/references").get(0);
        JsonObject expectedParams = obj(
                "textDocument", obj("uri", str(srcFile().toUri().toString())),
                "position", obj("line", num(9), "character", num(4)),
                "context", obj("includeDeclaration", new JsonBoolean(true)));
        assertEquals(expectedParams, references.params());
        // A Location array completes the future, 0-based lines carried as-is.
        JsonArray answer = new JsonArray(List.of(
                obj("uri", str(srcFile().toUri().toString()),
                        "range", obj("start", obj("line", num(41), "character", num(2)))),
                obj("uri", str(harness.root.resolve("src/Bar.java").toUri().toString()),
                        "range", obj("start", obj("line", num(7), "character", num(0))))));
        harness.client().respondTo("textDocument/references", answer);
        List<JdtServerManager.Location> locations = result.get().get(2, TimeUnit.SECONDS);
        assertEquals(2, locations.size());
        assertEquals(new JdtServerManager.Location(srcFile().toUri().toString(), 41, 2), locations.get(0));
        assertEquals(new JdtServerManager.Location(
                harness.root.resolve("src/Bar.java").toUri().toString(), 7, 0), locations.get(1));
    }

    @Test
    void serviceReadyAloneNeverReachesIndexedBeforeSixtySeconds() {
        harness.newManager(null);
        query();
        respondInitialize();
        serviceReady();
        assertEquals(JdtServerManager.Readiness.READY, manager().state());
        harness.time.advanceMillis(59_999);
        assertEquals(JdtServerManager.Readiness.READY, manager().state());
        assertTrue(manager().references(srcFile(), 0, 0).isEmpty());
        harness.time.advanceMillis(1);
        assertEquals(JdtServerManager.Readiness.INDEXED, manager().state(),
                "60 s without an import task is a cached workspace: straight to INDEXED");
    }

    @Test
    void serviceReadyBeforeTheInitializeResponseStillBecomesReady() {
        harness.newManager(null);
        query();
        serviceReady(); // arrives before the response is completed
        assertEquals(JdtServerManager.Readiness.STARTING, manager().state());
        respondInitialize();
        assertEquals(JdtServerManager.Readiness.READY, manager().state());
    }

    @Test
    void readinessTracksTheDollarProgressSource() {
        harness.newManager(null);
        query();
        respondInitialize();
        serviceReady();
        assertEquals(JdtServerManager.Readiness.READY, manager().state());
        dollarBegin("token-1", "Building project");
        assertEquals(JdtServerManager.Readiness.INDEXING, manager().state());
        dollarEnd("token-1");
        assertEquals(JdtServerManager.Readiness.INDEXED, manager().state());
        assertTrue(manager().definition(srcFile(), 0, 0).isPresent());
    }

    @Test
    void overlappingTasksAllNeedCompletionBeforeIndexed() {
        harness.newManager(null);
        query();
        respondInitialize();
        serviceReady();
        importStarted("t1");
        importStarted("t2");
        importStarted("t3");
        assertEquals(JdtServerManager.Readiness.INDEXING, manager().state());
        importCompleted("t1");
        importCompleted("t3");
        assertEquals(JdtServerManager.Readiness.INDEXING, manager().state(),
                "an unfinished task keeps the workspace unindexed");
        assertTrue(manager().references(srcFile(), 0, 0).isEmpty());
        importCompleted("t2");
        assertEquals(JdtServerManager.Readiness.INDEXED, manager().state());
    }

    @Test
    void taskErrorIsTerminalNotIndexedAndStopsTheServer() throws IOException {
        harness.newManager(null);
        query();
        respondInitialize();
        serviceReady();
        importStarted("t1");
        importError("t1");
        assertEquals(JdtServerManager.Readiness.NOT_INDEXED, manager().state());
        assertTrue(manager().hint().contains("import"), manager().hint());
        assertTrue(manager().hint().contains("Gradle daemon died"), manager().hint());
        // The tier stays off for this server: the process is released, the -data cache kept.
        assertTrue(harness.events.contains("request:shutdown"), "server released: " + harness.events);
        assertTrue(harness.events.contains("process-close"));
        assertTrue(Files.exists(onlyDataDir()));
        // No query is ever served, and a late completion changes nothing.
        assertTrue(manager().references(srcFile(), 0, 0).isEmpty());
        importCompleted("t1");
        assertEquals(JdtServerManager.Readiness.NOT_INDEXED, manager().state());
    }

    @Test
    void tenMinuteCapFromTheFirstStartedTaskIgnoresLateCompletions() {
        harness.newManager(null);
        query();
        respondInitialize();
        serviceReady();
        importStarted("t1"); // the cap runs from this FIRST start
        harness.time.advanceMillis(60_000);
        query(); // a later query re-arms the 10-minute idle stop beyond the cap
        harness.time.advanceMillis(60_000);
        importStarted("t2"); // a later task does not extend the cap
        harness.time.advanceMillis(600_000 - 120_000 - 1);
        assertEquals(JdtServerManager.Readiness.INDEXING, manager().state());
        harness.time.advanceMillis(1);
        assertEquals(JdtServerManager.Readiness.NOT_INDEXED, manager().state());
        assertTrue(manager().hint().contains("10 minutes"), manager().hint());
        assertTrue(manager().references(srcFile(), 0, 0).isEmpty());
        // A completion arriving after the cap is never accepted.
        importCompleted("t1");
        importCompleted("t2");
        assertEquals(JdtServerManager.Readiness.NOT_INDEXED, manager().state());
    }

    // -------------------------------------------------------------- idle stop

    @Test
    void idleStopAfterTenMinutesShutsDownInOrderAndResetsForColdStart() throws IOException {
        harness.newManager(null);
        driveToIndexed();
        harness.time.advanceMillis(600_000 - 1);
        assertEquals(JdtServerManager.Readiness.INDEXED, manager().state());
        assertTrue(harness.events.stream().noneMatch(e -> e.equals("request:shutdown")));
        harness.time.advanceMillis(1);
        assertEquals(JdtServerManager.Readiness.STOPPED, manager().state());
        // Protocol shutdown, then exit, then process close — in that order.
        int shutdown = harness.events.indexOf("request:shutdown");
        int exit = harness.events.indexOf("notify:exit");
        int closed = harness.events.indexOf("process-close");
        assertTrue(shutdown >= 0 && exit >= 0 && closed >= 0, harness.events.toString());
        assertTrue(shutdown < exit && exit < closed, harness.events.toString());
        assertTrue(Files.exists(onlyDataDir()), "the -data import cache survives the idle stop");
        // The next query cold-starts a fresh server and a fresh readiness run.
        query();
        assertEquals(2, harness.processes.size());
        assertEquals(JdtServerManager.Readiness.STARTING, manager().state());
    }

    // ---------------------------------------------------------- sync-on-query

    @Test
    void syncOnQueryDidOpenThenFullTextDidChangeOnlyWhenMtimeAdvances() throws Exception {
        harness.newManager(null);
        driveToIndexed();
        Path file = srcFile();
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class Foo {}\n");
        Files.setLastModifiedTime(file, FileTime.fromMillis(1_000_000));
        assertTrue(manager().definition(file, 0, 6).isPresent());
        List<Sent> didOpen = harness.client().sent("textDocument/didOpen");
        assertEquals(1, didOpen.size());
        JsonObject doc = (JsonObject) ((JsonObject) didOpen.get(0).params()).get("textDocument");
        assertEquals(file.toUri().toString(), ((JsonString) doc.get("uri")).value());
        assertEquals("java", ((JsonString) doc.get("languageId")).value());
        assertEquals(1, ((JsonNumber) doc.get("version")).asInt());
        assertEquals("class Foo {}\n", ((JsonString) doc.get("text")).value());
        // Same mtime: a second query syncs nothing further.
        assertTrue(manager().references(file, 0, 0).isPresent());
        assertEquals(1, harness.client().sent("textDocument/didOpen").size());
        assertEquals(0, harness.client().sent("textDocument/didChange").size());
        // Advanced mtime: exactly one full-text didChange, no range key, version bumped.
        Files.writeString(file, "class Foo { int x; }\n");
        Files.setLastModifiedTime(file, FileTime.fromMillis(2_000_000));
        assertTrue(manager().definition(file, 0, 6).isPresent());
        List<Sent> didChange = harness.client().sent("textDocument/didChange");
        assertEquals(1, didChange.size());
        JsonObject changeParams = (JsonObject) didChange.get(0).params();
        JsonObject change = (JsonObject) changeParams.get("textDocument");
        assertEquals(2, ((JsonNumber) change.get("version")).asInt());
        JsonArray contentChanges = (JsonArray) changeParams.get("contentChanges");
        assertEquals(1, contentChanges.elements().size());
        JsonObject fullText = (JsonObject) contentChanges.elements().get(0);
        assertEquals("class Foo { int x; }\n", ((JsonString) fullText.get("text")).value());
        assertFalse(fullText.has("range"), "full text, never a ranged change");
        // Opened buffers are never didClosed.
        assertTrue(harness.client().sent("textDocument/didClose").isEmpty());
    }

    @Test
    void filesOutsideTheWorktreeRootAreNeverSynced() {
        harness.newManager(null);
        driveToIndexed();
        Path outside = tempDir.resolve("outside/Other.java");
        assertTrue(manager().definition(outside, 1, 1).isPresent(),
                "outside-root files are still queried, just never synced");
        assertTrue(harness.client().sent("textDocument/didOpen").isEmpty());
        assertTrue(harness.client().sent("textDocument/didChange").isEmpty());
        Asked asked = harness.client().asked("textDocument/definition").get(0);
        JsonObject textDocument = (JsonObject) ((JsonObject) asked.params()).get("textDocument");
        assertEquals(outside.toUri().toString(), ((JsonString) textDocument.get("uri")).value());
    }

    // ----------------------------------------------------------- cache/paths

    @Test
    void dataDirIsKeptOnCloseAndRemovedByDeleteCache() throws IOException {
        harness.newManager(null);
        driveToIndexed();
        Path dataDir = onlyDataDir();
        manager().close();
        assertTrue(Files.isDirectory(dataDir), "an ordinary close keeps the import cache");
        manager().deleteCache();
        assertFalse(Files.exists(dataDir), "deleteCache removes the worktree's cache only");
        manager().deleteCache(); // idempotent
        // Nothing else under the lsp root is touched.
        assertEquals(0, Files.list(harness.dataRoot).count());
    }

    @Test
    void sameNamedSiblingWorktreesGetDistinctDataDirs() throws IOException {
        harness.newManager(null);
        driveToIndexed();
        // A sibling worktree with the SAME readable basename must not share a -data dir.
        Path otherRoot = Files.createDirectories(tempDir.resolve("wt2/repo"));
        JdtServerManager other = harness.newManagerFor(otherRoot, null);
        assertTrue(other.references(otherRoot.resolve("src/Foo.java"), 1, 1).isEmpty());
        assertEquals(JdtServerManager.Readiness.STARTING, other.state());
        respondInitialize();
        serviceReady();
        importStarted("o1");
        importCompleted("o1");
        assertEquals(JdtServerManager.Readiness.INDEXED, other.state());
        try (Stream<Path> children = Files.list(harness.dataRoot)) {
            List<String> names = children.map(path -> path.getFileName().toString()).sorted().toList();
            assertEquals(2, names.size(), names.toString());
            for (String name : names) {
                assertTrue(name.matches("^repo-[0-9a-f]{12}$"), name);
            }
            assertNotEquals(names.get(0), names.get(1), "the stable hash must separate same-named roots");
        }
        other.close();
    }

    // --------------------------------------------------------- production probe

    @Test
    void productionProbeParsesStderrAndPreservesFailureDetail() throws Exception {
        Path javaHome = Files.createDirectories(tempDir.resolve("jdk/bin")).getParent();
        Path java = javaHome.resolve("bin/java");
        Files.writeString(java, "#!/bin/sh\necho 'openjdk version \"17.0.2\" 2024-01-01' >&2\n");
        Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwxr-xr-x"));
        JdtServerManager.JavaProbe probe = JdtServerManager.processRunnerJavaProbe();
        assertEquals("17.0.2", probe.probe(javaHome));
        JdtServerManager.ValidationResult ok =
                JdtServerManager.validate(new JdtServerManager.LaunchConfig(harness.jdtHome, javaHome), probe);
        assertInstanceOf(JdtServerManager.ValidationResult.Valid.class, ok);
        // Old version: refused with the version named in the message.
        Files.writeString(java, "#!/bin/sh\necho 'java version \"1.8.0_292\"' >&2\n");
        JdtServerManager.ValidationResult old =
                JdtServerManager.validate(new JdtServerManager.LaunchConfig(harness.jdtHome, javaHome), probe);
        assertInstanceOf(JdtServerManager.ValidationResult.Invalid.class, old);
        assertTrue(((JdtServerManager.ValidationResult.Invalid) old).message().contains("1.8.0_292"),
                ((JdtServerManager.ValidationResult.Invalid) old).message());
        // A failing executable keeps its exit code and stderr in the refusal.
        Files.writeString(java, "#!/bin/sh\necho 'segmentation fault in jli' >&2\nexit 3\n");
        IOException failure = assertThrows(IOException.class, () -> probe.probe(javaHome));
        assertTrue(failure.getMessage().contains("exited with 3"), failure.getMessage());
        assertTrue(failure.getMessage().contains("segmentation fault in jli"), failure.getMessage());
    }

    // ------------------------------------------------------------- helpers

    private Path onlyDataDir() throws IOException {
        try (Stream<Path> children = Files.list(harness.dataRoot)) {
            List<Path> dirs = children.toList();
            assertEquals(1, dirs.size(), "exactly one cache dir: " + dirs);
            return dirs.get(0);
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
