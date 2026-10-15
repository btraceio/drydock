package app.drydock.lsp;

import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonBoolean;
import app.drydock.state.json.JsonValue.JsonNull;
import app.drydock.state.json.JsonValue.JsonNumber;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * One lazy, per-worktree jdt.ls lifecycle and readiness owner (spec
 * docs/superpowers/specs/2026-10-08-lsp-tier3-usage-resolution.md, §3-6).
 * Only {@link Readiness#INDEXED} serves queries; every other state — and every
 * start/validation/spawn failure — returns empty immediately so the tier below
 * answers (never blocks; the server starts lazily on the first eligible query,
 * never at construction). Readiness: STARTING → READY (initialize response +
 * language/status ServiceReady) → INDEXING (first import/build task from
 * language/progressReport or $/progress) → INDEXED (all started tasks completed; no
 * task within 60 s of ServiceReady is a cached workspace → INDEXED); a task error or
 * the amended 10-minute never-completes cap (from the FIRST started task) is terminal
 * NOT_INDEXED for the session — a late completion is never accepted. close() is
 * idempotent: a live server gets the protocol shutdown request, exit notification, then
 * {@link LspServerProcess#close()}; a mid-startup server is cancelled; one restart after a
 * 30 s crash backoff, a second crash within 5 minutes disables the session (WARNING
 * logged). The -data dir under ~/.drydock/lsp/<slug>/ is kept across closes (jdt.ls's import
 * cache) and only deleteCache() removes it. Sync-on-query: didOpen then full-text
 * didChange, only when a file's mtime advanced, one sync per file per query, never a
 * didClose, never outside the worktree root. FX-free, thread-safe on any service
 * executor; blocking work runs on the injected scheduler; all seams are injected so no
 * test needs a real jdt.ls or real time.
 */
public final class JdtServerManager implements AutoCloseable {

    private static final Logger LOG = System.getLogger(JdtServerManager.class.getName());
    static final long NO_IMPORT_TASK_MILLIS = 60_000;        // §6: no task within 60 s of ServiceReady = cached
    static final long IMPORT_CAP_MILLIS = 600_000;           // §6 amended: never-completing task, from FIRST start
    static final long IDLE_STOP_MILLIS = 600_000;             // §5: idle stop 10 min after the last query
    static final long CRASH_BACKOFF_MILLIS = 30_000;          // §5: one restart, 30 s backoff after a crash
    static final long CRASH_DISABLE_WINDOW_MILLIS = 300_000; // §5: second crash within 5 min disables session
    static final long SHUTDOWN_GRACE_MILLIS = 2_000;         // bound on the protocol shutdown request
    static final long JAVA_PROBE_TIMEOUT_MILLIS = 10_000;    // ProcessRunner timeout for java -version
    private static final Pattern VERSION_PATTERN = Pattern.compile("version \"([^\"]+)\"");

    // ------------------------------------------------------------ public types

    /** The readiness machine of spec section 6; only INDEXED serves queries. */
    public enum Readiness {
        NOT_STARTED, STARTING, READY, INDEXING, INDEXED, NOT_INDEXED, STOPPED, DOWN, DISABLED, INVALID, CLOSED
    }

    /** One location result, LSP-native: lines/characters are 0-based, uri is the server's file uri. */
    public record Location(String uri, int line, int character) {
    }

    /**
     * The immutable, pre-configuration launch inputs: the unpacked jdt.ls directory
     * ({@code plugins/} + {@code config_mac}) and the optional JDK the server runs under
     * ({@code null} = the {@code java} on PATH), normalized to absolute so T5 can map raw
     * user config without this package depending on it.
     */
    public record LaunchConfig(Path jdtHome, Path javaHome) {
        public LaunchConfig {
            Objects.requireNonNull(jdtHome, "jdtHome");
            jdtHome = jdtHome.toAbsolutePath().normalize();
            javaHome = javaHome == null ? null : javaHome.toAbsolutePath().normalize();
        }
    }

    /**
     * The java -version probe seam; production runs it through
     * {@link ProcessRunner} ({@link #processRunnerJavaProbe()}), tests inject a function.
     * Returns the reported version (e.g. "17.0.2"); throws with version/stderr detail.
     */
    @FunctionalInterface
    public interface JavaProbe {
        String probe(Path javaHome) throws Exception;
    }

    /** The outcome of section 3 validation: everything a launch needs, or a refusal naming what was found. */
    public sealed interface ValidationResult {
        record Valid(Path jdtHome, Path launcherJar, String javaExecutable, String javaVersion)
                implements ValidationResult { }

        /** A human-readable refusal (shown inline by the settings row, in the WARNING log, and in the hint). */
        record Invalid(String message) implements ValidationResult { }
    }

    /** The process seam over {@link LspServerProcess}, the app's only long-lived child; faked in tests. */
    public interface ServerProcess extends AutoCloseable {
        InputStream stdout();

        OutputStream stdin();

        /** Completes when the child exits (crash or clean exit alike); stdout EOF is its companion signal. */
        CompletableFuture<Integer> exitFuture();

        @Override void close();
    }

    /** Produces the long-lived server process; a failure here is retryable on the next query. */
    @FunctionalInterface
    public interface ProcessFactory {
        ServerProcess spawn(List<String> command, Path workingDirectory) throws IOException;
    }

    /** The client seam over {@link LspClient} used for queries, notifications and the shutdown exchange. */
    public interface ClientConnection extends AutoCloseable {
        CompletableFuture<JsonValue> request(String method, JsonValue params);

        void notification(String method, JsonValue params) throws IOException;

        @Override void close();
    }

    /** Server-to-client notifications, delivered on the client's reader thread. */
    @FunctionalInterface
    public interface ServerNotifications {
        void onNotification(String method, JsonValue params);
    }

    /** Produces the client; {@code settings} is the settings.java block it answers workspace/configuration with. */
    @FunctionalInterface
    public interface ClientFactory {
        ClientConnection connect(ServerProcess process, JsonObject settings,
                                 ServerNotifications listener) throws IOException;
    }

    /**
     * The clock-and-delay seam: tests drive a manual implementation so no test sleeps
     * a real minute; production uses wall time and a small pool. {@code execute} runs
     * background (potentially blocking) pipeline work — never the FX thread.
     */
    public interface Scheduler {
        Instant now();

        void execute(Runnable task);

        Timer afterMillis(long delayMillis, Runnable task);

        interface Timer { void cancel(); }
    }

    /** The failure every in-flight query future completes with when the tier cannot answer it. */
    public static final class QueryUnavailableException extends RuntimeException {
        QueryUnavailableException(String message) { super(message); }
    }

    // ----------------------------------------------------------------- fields

    private final Path root;
    private final LaunchConfig config;
    private final JavaProbe javaProbe;
    private final ProcessFactory processFactory;
    private final ClientFactory clientFactory;
    private final Scheduler scheduler;
    private final RealScheduler ownedScheduler;
    private final Path dataDir;
    private final Object lock = new Object();
    private final Object syncLock = new Object();
    private final List<CompletableFuture<List<Location>>> inFlight = new CopyOnWriteArrayList<>();
    private final Map<Path, SyncedDocument> synced = new HashMap<>();
    private final LinkedHashSet<String> openTasks = new LinkedHashSet<>();
    private Readiness state = Readiness.NOT_STARTED;
    private ServerProcess process;
    private ClientConnection client;
    private String invalidMessage, notIndexedReason;
    private Instant lastQueryAt, lastCrashAt;
    private boolean initializeResponded, serviceReadySeen, closedFlag;
    /**
     * The current server run's tag, guarded by {@link #lock}: bumped when a start begins,
     * on every detach (idle stop, graceful release, initialize failure, close) and when a
     * crash of the current run is accepted. Every run-originated callback (exit, initialize
     * response, notification) carries the tag it was registered with and is a no-op unless
     * it still equals this value, so a detached run can never touch a newer one.
     */
    private long generation;
    private Scheduler.Timer idleTimer, noTaskTimer, capTimer;

    private record SyncedDocument(long mtimeMillis, int version) { }

    /** The live server pair, captured for release outside the manager lock. */
    private record Captured(ClientConnection client, ServerProcess process) { }

    // ------------------------------------------------------------ constructors

    /**
     * Production constructor: real process, real client, real time, cache under
     * ~/.drydock/lsp/; the probe stays injectable so T5 can validate without a real JDK.
     */
    public JdtServerManager(Path worktreeRoot, LaunchConfig config, JavaProbe javaProbe) {
        this(worktreeRoot, config, javaProbe,
                (command, workingDirectory) ->
                        new RealServerProcess(LspServerProcess.start(command, workingDirectory)),
                (process, settings, listener) -> new RealClientConnection(
                        new LspClient(process.stdout(), process.stdin(), settings, listener::onNotification)),
                null, null);
    }

    /**
     * Full-seam constructor. {@code scheduler} and {@code dataRoot} may be {@code null} for the
     * production defaults (a small owned scheduled pool; ~/.drydock/lsp); injected values
     * are never released by this class.
     */
    public JdtServerManager(Path worktreeRoot, LaunchConfig config, JavaProbe javaProbe,
                            ProcessFactory processFactory, ClientFactory clientFactory,
                            Scheduler scheduler, Path dataRoot) {
        this.root = Objects.requireNonNull(worktreeRoot, "worktreeRoot").toAbsolutePath().normalize();
        this.config = Objects.requireNonNull(config, "config");
        this.javaProbe = Objects.requireNonNull(javaProbe, "javaProbe");
        this.processFactory = Objects.requireNonNull(processFactory, "processFactory");
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory");
        this.ownedScheduler = scheduler == null ? new RealScheduler() : null;
        this.scheduler = scheduler == null ? ownedScheduler : scheduler;
        this.dataDir = dataDirFor(this.root, dataRoot);
    }

    // --------------------------------------------------------------- query API

    /**
     * Tier-3 references at the given LSP-native (0-based) position, always with
     * {@code includeDeclaration: true} (§7). Present only when INDEXED; every other state
     * — including a start this query triggered — returns empty immediately so the lower
     * tier answers. Locations keep 0-based LSP lines as-is. The future fails (never hangs)
     * with {@link QueryUnavailableException} or the transport's error.
     */
    public Optional<CompletableFuture<List<Location>>> references(Path file, int line, int character) {
        return startQuery(file, "textDocument/references", line, character, true);
    }

    /** Tier-3 definition; same contract as {@link #references(Path, int, int)}. */
    public Optional<CompletableFuture<List<Location>>> definition(Path file, int line, int character) {
        return startQuery(file, "textDocument/definition", line, character, false);
    }

    /** The current readiness; safe from any thread. */
    public Readiness state() {
        synchronized (lock) {
            return state;
        }
    }

    /** A short reviewer-facing explanation per readiness state (spec section 8: quiet, never a dialog). */
    public String hint() {
        synchronized (lock) {
            return switch (state) {
                case NOT_STARTED -> "language server not started";
                case STARTING -> "language server starting";
                case READY, INDEXING -> "importing project — exact references not ready yet";
                case INDEXED -> "language server indexed";
                case NOT_INDEXED -> "project import failed this session — exact references unavailable (" + notIndexedReason + ')';
                case STOPPED -> "language server idle-stopped; it starts again on the next query";
                case DOWN -> "language server crashed; it restarts on a later query";
                case DISABLED -> "language server disabled for this session after repeated crashes";
                case INVALID -> invalidMessage == null ? "language server configuration is invalid" : invalidMessage;
                case CLOSED -> "language server closed";
            };
        }
    }

    // Only INDEXED serves queries; an unserved query may trigger one lazy start/restart (crash backoff honored).
    private Optional<CompletableFuture<List<Location>>> startQuery(Path file, String method,
                                                                   int line, int character, boolean references) {
        Path normalized = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        boolean attemptStart = false;
        ClientConnection serve = null;
        synchronized (lock) {
            lastQueryAt = scheduler.now();
            if (state == Readiness.INDEXED) {
                serve = client;
                rescheduleIdleTimerLocked();
            } else if (!closedFlag && (state == Readiness.NOT_STARTED || state == Readiness.STOPPED
                    || (state == Readiness.DOWN && backoffElapsedLocked()))) {
                attemptStart = true; // terminal states: unavailable now
            } else if (!closedFlag) {
                rescheduleIdleTimerLocked(); // any query while the server is live re-arms the idle stop
            }
        }
        if (attemptStart) scheduler.execute(this::attemptStart);
        if (serve == null) return Optional.empty();
        try { // a crash or close can race the query between the check and the sync or the write
            syncIfStale(normalized, serve);
            return Optional.of(trackQuery(serve.request(method,
                    queryParameters(normalized, line, character, references))));
        } catch (RuntimeException e) { // e.g. IllegalStateException from a client closed since the check
            LOG.log(Level.DEBUG, "tier-3 query ''{0}'' could not be sent: {1}", method, e.toString());
            return Optional.empty();
        }
    }

    private CompletableFuture<List<Location>> trackQuery(CompletableFuture<JsonValue> raw) {
        CompletableFuture<List<Location>> result = new CompletableFuture<>();
        raw.whenComplete((value, error) -> {
            if (error != null) result.completeExceptionally(error);
            else {
                try { result.complete(parseLocations(value)); }
                catch (RuntimeException e) { result.completeExceptionally(e); }
            }
        });
        inFlight.add(result);
        result.whenComplete((value, error) -> inFlight.remove(result));
        return result;
    }

    // ------------------------------------------------------------- start path

    private void attemptStart() {
        final long run;
        synchronized (lock) {
            if (closedFlag || (state != Readiness.NOT_STARTED && state != Readiness.STOPPED
                    && state != Readiness.DOWN)) return;
            state = Readiness.STARTING;
            resetRunStateLocked();
            run = ++generation;
        }
        ValidationResult validation = validate(config, javaProbe);
        if (validation instanceof ValidationResult.Invalid invalid) {
            synchronized (lock) {
                if (closedFlag) return;
                state = Readiness.INVALID;
                invalidMessage = invalid.message();
            }
            LOG.log(Level.WARNING, "jdt.ls for {0} did not start: {1}", root, invalid.message());
            return;
        }
        ValidationResult.Valid valid = (ValidationResult.Valid) validation;
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) { retryableStartFailure("data directory " + dataDir + ": " + e); return; }
        ServerProcess spawned;
        try {
            spawned = processFactory.spawn(launchCommand(valid, dataDir), null);
        } catch (IOException e) { // §8: spawn fails → tiers 1-2; retry on the next query
            retryableStartFailure("language server spawn failed: " + e);
            return;
        }
        ClientConnection connected;
        try {
            connected = clientFactory.connect(spawned, javaSettings(),
                    (method, params) -> onNotification(run, method, params));
        } catch (IOException e) {
            closeQuietly(spawned);
            retryableStartFailure("language server client failed to connect: " + e);
            return;
        }
        synchronized (lock) {
            if (closedFlag || run != generation) { // closed or superseded during startup: cancel it
                closeQuietly(connected);
                closeQuietly(spawned);
                return;
            }
            this.process = spawned;
            this.client = connected;
            spawned.exitFuture().whenComplete((code, error) -> scheduler.execute(() -> handleProcessExit(run)));
            rescheduleIdleTimerLocked();
        }
        CompletableFuture<JsonValue> initialized = connected.request("initialize", initializeParams());
        initialized.whenComplete((value, error) ->
                scheduler.execute(() -> onInitializeResponse(run, connected, value, error)));
    }

    // Tied to its run: a response from a detached/superseded run changes nothing (no state, no send).
    private void onInitializeResponse(long run, ClientConnection from, JsonValue value, Throwable error) {
        Captured toRelease = null;
        synchronized (lock) {
            if (closedFlag || run != generation) return;
            if (error != null) { // the server is unusable: release it, let the next query retry
                toRelease = detachServerLocked();
                state = Readiness.NOT_STARTED;
                resetRunStateLocked();
            } else {
                initializeResponded = true;
                maybeReadyLocked();
            }
        }
        if (toRelease != null) {
            closeQuietly(toRelease.client());
            closeQuietly(toRelease.process());
            LOG.log(Level.WARNING, "jdt.ls for {0} failed to initialize (retry next query): {1}",
                    root, String.valueOf(error));
            return;
        }
        try { // the mandatory post-initialize handshake, on the issuing client, outside the lock
            from.notification("initialized", JsonObject.empty());
        } catch (IOException | RuntimeException e) { // write failure, or a stop/crash closed it since
            LOG.log(Level.DEBUG, "could not send initialized for {0}: {1}", root, e.toString());
        }
    }

    // ------------------------------------------------------- notification path
    // A late completion after NOT_INDEXED (or any terminal/stop state) is never accepted, and
    // neither is a notification from a detached/superseded run (its reader may outlive the detach).
    private void onNotification(long run, String method, JsonValue params) {
        JsonObject p = params instanceof JsonObject o ? o : JsonObject.empty();
        boolean stopServer = false;
        synchronized (lock) {
            if (closedFlag || run != generation || (state != Readiness.STARTING && state != Readiness.READY
                    && state != Readiness.INDEXING)) return;
            if ("language/status".equals(method) && "ServiceReady".equals(string(p, "type"))) {
                serviceReadySeen = true;
                maybeReadyLocked();
            } else if ("language/progressReport".equals(method)) {
                stopServer = progressReportLocked(p);
            } else if ("$/progress".equals(method)) {
                dollarProgressLocked(p);
            }
        }
        if (stopServer) gracefulRelease(run); // NOT_INDEXED: tier off for this server, -data cache kept
    }

    // Returns whether the server should be released because NOT_INDEXED was just reached.
    private boolean progressReportLocked(JsonObject p) {
        if (state == Readiness.STARTING) return false; // tasks matter only once ServiceReady arrived
        String status = string(p, "status");
        String taskType = string(p, "taskType");
        String id = string(p, "id");
        String key = "pr:" + (id != null ? id : taskType);
        boolean importOrBuild = isImportOrBuildName(taskType) || openTasks.contains(key);
        if ("Started".equals(status) && importOrBuild) {
            enterIndexingLocked(key);
        } else if ("Completed".equals(status)) {
            completeTaskLocked(key);
        } else if ("Error".equals(status) && importOrBuild) {
            String message = string(p, "message");
            return notIndexedLocked("an import/build task reported an error"
                    + (message == null || message.isBlank() ? "" : ": " + message));
        }
        return false;
    }

    private void dollarProgressLocked(JsonObject p) {
        if (state == Readiness.STARTING) return;
        String token = tokenKey(p.get("token"));
        if (token == null) return;
        JsonObject value = p.get("value") instanceof JsonObject v ? v : JsonObject.empty();
        String kind = string(value, "kind");
        if ("begin".equals(kind) && isImportOrBuildName(string(value, "title"))) {
            enterIndexingLocked("progress:" + token);
        } else if ("end".equals(kind)) completeTaskLocked("progress:" + token);
    }

    private void maybeReadyLocked() {
        if (state != Readiness.STARTING || !initializeResponded || !serviceReadySeen) return;
        state = Readiness.READY;
        // §6: 60 s without an import task = cached workspace with nothing to import.
        noTaskTimer = scheduler.afterMillis(NO_IMPORT_TASK_MILLIS, this::onNoImportTaskTimeout);
        LOG.log(Level.DEBUG, "jdt.ls for {0} is ServiceReady", root);
    }

    private void enterIndexingLocked(String taskKey) {
        // One-way machine: tasks after INDEXED/NOT_INDEXED change nothing.
        if (state == Readiness.INDEXED || state == Readiness.NOT_INDEXED) return;
        if (state == Readiness.READY) {
            state = Readiness.INDEXING;
            noTaskTimer = cancelTimer(noTaskTimer);
            LOG.log(Level.DEBUG, "jdt.ls for {0} started importing", root);
        }
        if (state == Readiness.INDEXING) {
            openTasks.add(taskKey);
            // The cap runs from the FIRST started task; armed here and only here.
            if (capTimer == null) capTimer = scheduler.afterMillis(IMPORT_CAP_MILLIS, this::onImportCapTimeout);
        }
    }

    private void completeTaskLocked(String taskKey) {
        if (openTasks.remove(taskKey) && openTasks.isEmpty() && state == Readiness.INDEXING) {
            state = Readiness.INDEXED;
            capTimer = cancelTimer(capTimer);
            LOG.log(Level.DEBUG, "jdt.ls for {0} finished importing; serving tier-3 queries", root);
        }
    }

    private void onNoImportTaskTimeout() {
        synchronized (lock) {
            if (state != Readiness.READY) return;
            state = Readiness.INDEXED;
            LOG.log(Level.DEBUG, "no import task within 60 s of ServiceReady for {0}; cached workspace", root);
        }
    }

    private void onImportCapTimeout() {
        boolean stopServer;
        long run;
        synchronized (lock) {
            run = generation;
            stopServer = state == Readiness.INDEXING
                    && notIndexedLocked("import did not complete within 10 minutes of the first started task");
        }
        if (stopServer) gracefulRelease(run);
    }

    // Returns whether the server should be released; NOT_INDEXED is sticky for the session.
    private boolean notIndexedLocked(String reason) {
        if (state == Readiness.NOT_INDEXED || state == Readiness.DISABLED
                || state == Readiness.CLOSED || state == Readiness.INVALID) return false;
        state = Readiness.NOT_INDEXED;
        notIndexedReason = reason;
        noTaskTimer = cancelTimer(noTaskTimer);
        capTimer = cancelTimer(capTimer);
        LOG.log(Level.WARNING, "jdt.ls for {0} will not be indexed this session: {1}", root, reason);
        return true;
    }

    // Only the CURRENT run's exit is a crash (§5). A detached run (idle stop, NOT_INDEXED release,
    // failed initialize, close) was released on purpose by its detacher, which owns closing it;
    // its late exit is stale and touches nothing -- never a newer run's client or process.
    private void handleProcessExit(long run) {
        Captured captured;
        synchronized (lock) {
            if (closedFlag || run != generation || process == null) return;
            captured = new Captured(client, process);
            client = null;
            process = null;
            generation++; // the crashed run's later callbacks (initialize failure, notifications) are stale
            cancelAllTimersLocked();
            failInFlightLocked();
            Instant now = scheduler.now();
            if (lastCrashAt != null
                    && Duration.between(lastCrashAt, now).toMillis() < CRASH_DISABLE_WINDOW_MILLIS) {
                state = Readiness.DISABLED;
                LOG.log(Level.WARNING, "second jdt.ls crash within 5 min for {0}; tier 3 disabled"
                        + " for this worktree session", root);
            } else {
                state = Readiness.DOWN;
                lastCrashAt = now;
                LOG.log(Level.WARNING, "jdt.ls for {0} exited unexpectedly; it restarts once, 30 s"
                        + " from now", root);
            }
        }
        closeQuietly(captured.client());
        closeQuietly(captured.process());
    }

    // Idle stop (§5): 10 minutes after the last query, protocol shutdown, state discarded.
    private void onIdleTimeout() {
        Captured captured;
        boolean graceful;
        synchronized (lock) {
            idleTimer = null;
            if (closedFlag || client == null || process == null) return;
            Instant last = lastQueryAt == null ? scheduler.now() : lastQueryAt;
            if (Duration.between(last, scheduler.now()).toMillis() < IDLE_STOP_MILLIS) return; // stale
            graceful = state == Readiness.READY || state == Readiness.INDEXING || state == Readiness.INDEXED;
            state = Readiness.STOPPED;
            resetRunStateLocked();
            captured = detachServerLocked();
        }
        LOG.log(Level.DEBUG, "jdt.ls for {0} idle for 10 minutes; stopping (the -data cache is kept)", root);
        if (graceful) scheduler.execute(() -> shutdownCaptured(captured));
        else { closeQuietly(captured.client()); closeQuietly(captured.process()); }
    }

    private void rescheduleIdleTimerLocked() {
        idleTimer = cancelTimer(idleTimer);
        idleTimer = scheduler.afterMillis(IDLE_STOP_MILLIS, this::onIdleTimeout);
    }

    private boolean backoffElapsedLocked() {
        return lastCrashAt != null
                && Duration.between(lastCrashAt, scheduler.now()).toMillis() >= CRASH_BACKOFF_MILLIS;
    }

    // Detaches the live server under the lock (an intentional stop, not a crash); the caller owns
    // closing the captured pair. Bumping the run tag makes the detached run's later exit,
    // initialize response and notifications stale, so none of them can touch a newer run.
    private Captured detachServerLocked() {
        Captured captured = new Captured(client, process);
        client = null;
        process = null;
        generation++;
        return captured;
    }

    // Protocol shutdown → exit → process close, in that order (spec section 5); bounded (~2 s + close).
    private static void shutdownCaptured(Captured captured) {
        ClientConnection c = captured.client();
        ServerProcess p = captured.process();
        if (c == null && p == null) return;
        if (c != null) {
            try {
                c.request("shutdown", null).get(SHUTDOWN_GRACE_MILLIS, TimeUnit.MILLISECONDS);
            } catch (Exception e) { LOG.log(Level.DEBUG, "shutdown request unanswered: {0}", e.toString()); }
            try {
                c.notification("exit", null);
            } catch (IOException | RuntimeException e) { // e.g. the reader closed the client on EOF
                LOG.log(Level.DEBUG, "exit notification failed: {0}", e.toString());
            }
        }
        closeQuietly(c);
        closeQuietly(p);
    }

    // Releases run {@code run}'s server with the protocol shutdown sequence, off the caller's
    // thread; a no-op when that run was already detached or superseded.
    private void gracefulRelease(long run) {
        Captured captured;
        synchronized (lock) {
            if (run != generation) return;
            captured = detachServerLocked();
        }
        if (captured.client() == null && captured.process() == null) return;
        scheduler.execute(() -> shutdownCaptured(captured));
    }

    private void resetRunStateLocked() {
        openTasks.clear();
        initializeResponded = false;
        serviceReadySeen = false;
        noTaskTimer = cancelTimer(noTaskTimer);
        capTimer = cancelTimer(capTimer);
        synchronized (syncLock) { synced.clear(); } // a fresh run knows nothing of synced buffers
    }

    private void cancelAllTimersLocked() {
        idleTimer = cancelTimer(idleTimer);
        noTaskTimer = cancelTimer(noTaskTimer);
        capTimer = cancelTimer(capTimer);
    }

    private void retryableStartFailure(String message) {
        synchronized (lock) {
            if (closedFlag) return;
            state = Readiness.NOT_STARTED; // spec section 8: spawn fails → tiers 1-2; retry on the next query
        }
        LOG.log(Level.WARNING, "jdt.ls for {0} failed to start (retrying on the next query): {1}", root, message);
    }

    /**
     * Sync-on-query (§6): one didOpen/didChange per file per query, only when the
     * disk mtime advanced past the last sync; never a didClose; never outside the root.
     */
    private void syncIfStale(Path file, ClientConnection serve) {
        if (!file.startsWith(root)) {
            LOG.log(Level.DEBUG, "not syncing {0}: outside the worktree root {1}", file, root);
            return;
        }
        synchronized (syncLock) {
            long mtime; String text;
            try {
                mtime = Files.getLastModifiedTime(file).toMillis();
            } catch (IOException e) {
                return;
            }
            SyncedDocument doc = synced.get(file);
            if (doc != null && doc.mtimeMillis() >= mtime) return;
            try {
                text = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                return;
            }
            try {
                if (doc == null) {
                    serve.notification("textDocument/didOpen", didOpenParams(file.toUri().toString(), text));
                } else {
                    serve.notification("textDocument/didChange",
                            didChangeParams(file.toUri().toString(), doc.version() + 1, text));
                }
                synced.put(file, new SyncedDocument(mtime, doc == null ? 1 : doc.version() + 1));
            } catch (IOException e) { LOG.log(Level.DEBUG, "text sync for {0} failed: {1}", file, e.toString()); }
        }
    }

    private void failInFlightLocked() {
        for (CompletableFuture<List<Location>> query : inFlight) {
            query.completeExceptionally(
                    new QueryUnavailableException("language server unavailable for this query"));
        }
        inFlight.clear();
    }

    /**
     * Idempotent release. A live server gets the protocol shutdown request then exit
     * notification before {@link LspServerProcess#close()}; a mid-startup server is
     * cancelled, not waited on. The -data directory is kept (jdt.ls's import cache; only
     * deleteCache() removes it). Never call on the FX thread — it blocks at most the
     * shutdown grace plus the process's bounded close.
     */
    @Override
    public void close() {
        Captured captured;
        boolean graceful;
        synchronized (lock) {
            if (closedFlag) return;
            closedFlag = true;
            graceful = state == Readiness.READY || state == Readiness.INDEXING
                    || state == Readiness.INDEXED || state == Readiness.NOT_INDEXED;
            state = Readiness.CLOSED;
            cancelAllTimersLocked();
            failInFlightLocked();
            captured = detachServerLocked();
        }
        if (graceful) shutdownCaptured(captured);
        else { closeQuietly(captured.client()); closeQuietly(captured.process()); }
        if (ownedScheduler != null) ownedScheduler.shutdown();
    }

    /** Removes this worktree's ~/.drydock/lsp/<slug> directory (T5's worktree-removal
     *  callback); idempotent; logs (never throws) when the tree cannot be removed. */
    public void deleteCache() {
        deleteCacheDir(dataDir);
    }

    /**
     * The jdt.ls -data directory a manager for {@code worktreeRoot} uses: {@code dataRoot}
     * ({@code null} = ~/.drydock/lsp, resolved at call time) / the readable-basename-plus-stable-hash
     * slug of the normalized absolute root. Pure: touches no filesystem. The single slug calculation,
     * shared with the constructor so the two can never drift.
     */
    public static Path dataDirFor(Path worktreeRoot, Path dataRoot) {
        Path normalized = Objects.requireNonNull(worktreeRoot, "worktreeRoot").toAbsolutePath().normalize();
        Path base = dataRoot == null ? Path.of(System.getProperty("user.home"), ".drydock", "lsp") : dataRoot;
        return base.resolve(worktreeSlug(normalized));
    }

    /**
     * Removes the given cache directory tree (see {@link #dataDirFor}); idempotent; logs (never
     * throws) on IO failure. Needs no manager, so the worktree-removal path builds none.
     */
    public static void deleteCacheDir(Path dataDir) {
        if (!Files.exists(dataDir)) return;
        try (Stream<Path> paths = Files.walk(dataDir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) { // stop at the first failure; the rest stays for a retry
                    LOG.log(Level.WARNING, "could not delete cache entry {0}: {1}", path, e.toString());
                    return;
                }
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "could not walk language server cache {0}: {1}", dataDir, e.toString());
        }
    }

    /**
     * §3 validation, run on save (by the settings wiring) and again at every server start
     * (the directory may have changed): normalized paths, config_mac present, exactly one
     * plugins/org.eclipse.equinox.launcher_*.jar (zero/several refused, reporting what was
     * found), and a Java 17+ probe. Blocking — never call on the FX thread.
     */
    public static ValidationResult validate(LaunchConfig config, JavaProbe probe) {
        Path jdtHome = config.jdtHome();
        if (!Files.isDirectory(jdtHome)) {
            return new ValidationResult.Invalid("jdt.ls directory does not exist: " + jdtHome);
        }
        if (!Files.exists(jdtHome.resolve("config_mac"))) {
            return new ValidationResult.Invalid("jdt.ls directory has no config_mac: " + jdtHome);
        }
        Path plugins = jdtHome.resolve("plugins");
        List<Path> launchers = launcherJars(plugins);
        if (launchers.size() != 1) {
            return new ValidationResult.Invalid(launcherReport(jdtHome, plugins, launchers));
        }
        Path javaHome = config.javaHome();
        String executable = javaHome == null ? "java" : javaHome.resolve("bin/java").toString();
        String where = javaHome == null ? "the java command on PATH" : executable;
        String version;
        try {
            version = probe.probe(javaHome);
        } catch (Exception e) {
            return new ValidationResult.Invalid("Java 17+ probe failed for " + where + ": "
                    + (e.getMessage() == null || e.getMessage().isBlank() ? e.toString() : e.getMessage()));
        }
        Integer major = majorVersion(version);
        if (major == null || major < 17) {
            return new ValidationResult.Invalid("jdt.ls requires Java 17+; " + where
                    + (major == null ? " reported an unparseable version '" + version + "'"
                    : " reports version " + version));
        }
        return new ValidationResult.Valid(jdtHome, launchers.get(0), executable, version);
    }

    /** The production probe: {@code java -version} through ProcessRunner, short timeout, version from stderr. */
    public static JavaProbe processRunnerJavaProbe() {
        return javaHome -> {
            String executable = javaHome == null ? "java" : javaHome.resolve("bin/java").toString();
            ProcessResult result = ProcessRunner.run(List.of(executable, "-version"), null,
                    Duration.ofMillis(JAVA_PROBE_TIMEOUT_MILLIS));
            String stderr = result.stderr() == null ? "" : result.stderr();
            if (result.exitCode() != 0) throw new IOException(executable + " -version exited with "
                    + result.exitCode() + (stderr.isBlank() ? "" : ": " + ProcessRunner.excerpt(stderr)));
            Matcher matcher = VERSION_PATTERN.matcher(stderr);
            if (!matcher.find()) throw new IOException("no version reported by " + executable + " -version"
                    + (stderr.isBlank() ? "" : "; output: " + ProcessRunner.excerpt(stderr)));
            return matcher.group(1);
        };
    }

    // Glob the versioned launcher jar; zero or several matches are the caller's refusal to report.
    private static List<Path> launcherJars(Path plugins) {
        if (!Files.isDirectory(plugins)) return List.of();
        try (DirectoryStream<Path> stream =
                Files.newDirectoryStream(plugins, "org.eclipse.equinox.launcher_*.jar")) {
            List<Path> found = new ArrayList<>();
            for (Path candidate : stream) {
                if (Files.isRegularFile(candidate)) found.add(candidate);
            }
            found.sort(Comparator.comparing(path -> path.getFileName().toString()));
            return found;
        } catch (IOException e) {
            return List.of();
        }
    }

    // Names what was found: the matches when several, the directory contents when none.
    private static String launcherReport(Path jdtHome, Path plugins, List<Path> launchers) {
        String expected = "expected exactly one plugins/org.eclipse.equinox.launcher_*.jar under " + jdtHome;
        if (launchers.size() > 1) {
            String names = launchers.stream().map(path -> path.getFileName().toString())
                    .reduce((a, b) -> a + ", " + b).orElse("");
            return expected + ", found " + launchers.size() + ": " + names;
        }
        if (!Files.isDirectory(plugins)) return expected + ", found none (there is no plugins directory)";
        try (Stream<Path> entries = Files.list(plugins)) {
            List<String> names = entries.map(p -> p.getFileName().toString()).sorted().limit(5).toList();
            return expected + ", found none"
                    + (names.isEmpty() ? " (plugins/ is empty)"
                    : " (plugins/ contains: " + String.join(", ", names) + ")");
        } catch (IOException e) {
            return expected + ", found none (plugins/ could not be listed)";
        }
    }

    private static Integer majorVersion(String version) {
        if (version == null) return null;
        Matcher matcher = Pattern.compile("^(\\d+)").matcher(version.strip());
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    // The exact §4 argument list (the launcher jar is the resolved glob match).
    private static List<String> launchCommand(ValidationResult.Valid valid, Path dataDir) {
        return List.of(
                valid.javaExecutable(),
                "-Declipse.application=org.eclipse.jdt.ls.core.id1",
                "-Dosgi.bundles.defaultStartLevel=4",
                "-Declipse.product=org.eclipse.jdt.ls.core.product",
                "-Xmx1G",
                "--add-modules=ALL-SYSTEM",
                "--add-opens", "java.base/java.util=ALL-UNNAMED",
                "--add-opens", "java.base/java.lang=ALL-UNNAMED",
                "-jar", valid.launcherJar().toString(),
                "-configuration", valid.jdtHome().resolve("config_mac").toString(),
                "-data", dataDir.toString());
    }

    // §4 initialize payload: one worktree folder, no unimplemented capabilities, no bundles.
    private JsonObject initializeParams() {
        return JsonObject.empty()
                .put("workspaceFolders", new JsonArray(List.of(
                        JsonObject.empty()
                                .put("uri", new JsonString(root.toUri().toString()))
                                .put("name", new JsonString(root.getFileName() == null
                                        ? root.toString() : root.getFileName().toString())))))
                .put("capabilities", JsonObject.empty())
                .put("initializationOptions", JsonObject.empty()
                        .put("extendedClientCapabilities", JsonObject.empty()
                                .put("progressReportProvider", new JsonBoolean(true)))
                        .put("settings", JsonObject.empty().put("java", javaSettings())));
    }

    /** The settings.java block (§4); also the exact workspace/configuration answer. */
    private static JsonObject javaSettings() {
        return JsonObject.empty()
                .put("import", JsonObject.empty()
                        .put("gradle", JsonObject.empty()
                                .put("enabled", new JsonBoolean(true))
                                .put("wrapper", JsonObject.empty()
                                        .put("enabled", new JsonBoolean(true)))))
                .put("configuration", JsonObject.empty()
                        .put("updateBuildConfiguration", new JsonString("automatic")));
    }

    private static JsonObject queryParameters(Path file, int line, int character, boolean references) {
        JsonObject params = JsonObject.empty()
                .put("textDocument", JsonObject.empty()
                        .put("uri", new JsonString(file.toUri().toString())))
                .put("position", JsonObject.empty()
                        .put("line", JsonNumber.of(line))
                        .put("character", JsonNumber.of(character)));
        return references ? params.put("context", JsonObject.empty()
                .put("includeDeclaration", new JsonBoolean(true))) : params;
    }

    private static JsonObject didOpenParams(String uri, String text) {
        return JsonObject.empty().put("textDocument", JsonObject.empty()
                .put("uri", new JsonString(uri))
                .put("languageId", new JsonString("java"))
                .put("version", JsonNumber.of(1))
                .put("text", new JsonString(text)));
    }

    private static JsonObject didChangeParams(String uri, int version, String text) {
        return JsonObject.empty()
                .put("textDocument", JsonObject.empty()
                        .put("uri", new JsonString(uri))
                        .put("version", JsonNumber.of(version)))
                .put("contentChanges", new JsonArray(List.of(
                        JsonObject.empty().put("text", new JsonString(text)))));
    }

    // Definition answers one Location, a Location array, or LocationLinks; references answer Locations.
    private static List<Location> parseLocations(JsonValue result) {
        if (result instanceof JsonNull) return List.of();
        JsonArray array;
        if (result instanceof JsonArray a) array = a;
        else if (result instanceof JsonObject single) array = new JsonArray(List.of(single));
        else throw new IllegalStateException("unexpected location payload: " + result);
        List<Location> locations = new ArrayList<>();
        for (JsonValue element : array.elements()) {
            if (!(element instanceof JsonObject o)) continue;
            String uri = string(o, "uri");
            JsonObject range = o.get("range") instanceof JsonObject r ? r : null;
            if (uri == null) {
                uri = string(o, "targetUri");
                range = o.get("targetRange") instanceof JsonObject r ? r : null;
            }
            JsonObject start = range != null && range.get("start") instanceof JsonObject s ? s : null;
            if (uri == null || start == null) {
                continue; // a malformed entry is skipped, never fatal: it only fails to upgrade a row
            }
            locations.add(new Location(uri, intMember(start, "line"), intMember(start, "character")));
        }
        return locations;
    }

    // An import/build task name, per §6 ("Importing Gradle project", "Building…").
    private static boolean isImportOrBuildName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("import") || lower.contains("build");
    }

    private static String string(JsonObject o, String key) {
        return o.get(key) instanceof JsonString s ? s.value() : null;
    }

    private static String tokenKey(JsonValue token) {
        if (token instanceof JsonString s) return s.value();
        return token instanceof JsonNumber n ? n.literal() : null;
    }

    private static int intMember(JsonObject o, String key) {
        return o.get(key) instanceof JsonNumber n ? n.asInt() : 0;
    }

    // Filesystem-safe, collision-resistant: readable basename + stable hash of the
    // normalized absolute root, so same-named checkouts never share a -data directory.
    private static String worktreeSlug(Path root) {
        String name = root.getFileName() == null ? "worktree" : root.getFileName().toString();
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.isBlank() || safe.equals(".") || safe.equals("..")) safe = "worktree";
        return safe + "-" + stableHash(root);
    }

    private static String stableHash(Path root) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(root.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(12);
            for (int i = 0; i < 6; i++) hex.append(String.format("%02x", digest[i]));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("no SHA-256", e); }
    }

    private static Scheduler.Timer cancelTimer(Scheduler.Timer timer) {
        if (timer != null) timer.cancel();
        return null;
    }

    private static void closeQuietly(ClientConnection client) {
        if (client != null) client.close();
    }

    private static void closeQuietly(ServerProcess process) {
        if (process != null) process.close();
    }

    // Production seams: thin adapters over T1/T2 and a wall-clock scheduler.

    private record RealServerProcess(LspServerProcess d) implements ServerProcess {
        @Override public InputStream stdout() { return d.stdout(); }
        @Override public OutputStream stdin() { return d.stdin(); }
        @Override public CompletableFuture<Integer> exitFuture() { return d.exitFuture(); }
        @Override public void close() { d.close(); }
    }

    private record RealClientConnection(LspClient d) implements ClientConnection {
        @Override public CompletableFuture<JsonValue> request(String m, JsonValue p) { return d.request(m, p); }
        @Override public void notification(String m, JsonValue p) throws IOException { d.notification(m, p); }
        @Override public void close() { d.close(); }
    }

    // Wall clock plus a single-thread virtual-thread pool.
    private static final class RealScheduler implements Scheduler {
        private final ScheduledExecutorService pool =
                Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
        @Override public Instant now() { return Instant.now(); }
        @Override public void execute(Runnable task) { pool.execute(task); }
        @Override public Timer afterMillis(long delayMillis, Runnable task) {
            ScheduledFuture<?> handle = pool.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
            return () -> handle.cancel(false);
        }
        void shutdown() { pool.shutdownNow(); }
    }
}
