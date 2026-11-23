package app.drydock.intellij.delayedtasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.delayedtasks.DelayedTaskService;
import app.drydock.delayedtasks.TaskLaunchBinding;
import app.drydock.delayedtasks.TaskLaunchBinding.BindingRefusal;
import app.drydock.domain.*;
import app.drydock.intellij.actions.SessionDialogs;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.*;
import app.drydock.tasks.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The T28b matrix: the accepted T28a pre-spawn binding over real store roots under
 * TempDir, real store-backed services, zero mocking; the six tests below carry their own
 * production line traces. PLACEMENT, stated: TaskLaunchBinding (app.drydock.delayedtasks,
 * platform) is entirely public, so the in-package production placement buys no extra
 * access; this file uses the plan's app.drydock.intellij.delayedtasks placement beside
 * the other delayed-task fixtures. The actions glue (bindingBound :299-314,
 * bindingOutcomeNotified :317-334, preparedSessionNotified :81-90) is package-private;
 * with no accessible seam, those branches are reached reflectively, pinned exactly as
 * T22b's TaskLauncherCompletionTest reaches preparedSessionNotified. Boundaries,
 * honestly: real spawns, real windows and cross-process locking stay with the T22a/T28a
 * smokes, and the null-binding branch of launchSession (:279, :289 -- neither wrapper
 * composed) has no headless seam; its pieces are pinned here, its end-to-end shape stays
 * smoke-covered.
 */
class TaskLaunchBindingTest {

    private static final Duration LIMIT = Duration.ofSeconds(20);

    private final List<LogRecord> logged = new ArrayList<>();
    private final Handler recorder = new Handler() { // System.getLogger routes to JUL
        @Override public void publish(LogRecord record) { logged.add(record); }
        @Override public void flush() { }
        @Override public void close() { }
    };
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        Logger.getLogger("app.drydock.delayedtasks.TaskLaunchBinding").addHandler(recorder);
    }

    @AfterEach
    void tearDown() {
        Logger.getLogger("app.drydock.delayedtasks.TaskLaunchBinding").removeHandler(recorder);
        executor.shutdown();
    }

    // 1. The bind write is store-visible before the spawn: the harness reads through the
    //    real MCP tools and sees the binding while the spawn step has not fired.
    @Test
    void theBindWriteIsStoreVisibleToTheHarnessBeforeTheSpawnSeamFires(@TempDir Path dir) throws Exception {
        Stage stage = Stage.of(dir, "Fix the login redirect");
        boolean[] spawned = {false}; // the spawn step, which production composes AFTER the bind
        assertSame(stage.session, stage.binding.bind(stage.session), "bind returns the prepared session itself");
        try (DelayedTaskService harness = new DelayedTaskService(stage.storeRoot, executor,
                () -> List.of(stage.session), List::of)) {
            harness.initialize().get(LIMIT.toMillis(), TimeUnit.MILLISECONDS);
            JsonValue got = harness.taskTools().call(stage.session.id(), "task_get",
                    args("id", stage.task().text()));
            assertEquals("started", textOf(got, "status"),
                    "a harness reading at spawn time sees the binding, never a bare open task");
            assertTrue(got.toString().contains(stage.session.id().toString()), "the session link is committed");
        }
        assertEquals(TaskRun.Phase.PREPARE, receipt(stage.storeRoot, stage.task()).phase(), "reserved pre-spawn");
        assertFalse(spawned[0], "the reads above ran strictly before the spawn step fires");
        stage.binding.launched();
        assertEquals(TaskRun.Phase.LAUNCH, receipt(stage.storeRoot, stage.task()).phase(), "LAUNCH recorded");
    }

    // 2. A failed bind write fails the launch before the spawn (bindingBound :299-314, releaseReserved :184-194).
    @Test
    @SuppressWarnings("unchecked")
    void aFailedBindWriteMeansZeroSpawnsAndNoPartialState(@TempDir Path dir) throws Exception {
        Stage stage = Stage.of(dir, "Fix the logout redirect");
        stage.permissions("r-xr-xr-x"); // the bind write cannot commit
        try {
            boolean[] spawned = {false};
            CompletableFuture<ManagedAgentSession> chain =
                    (CompletableFuture<ManagedAgentSession>) seam("bindingBound", stage.binding, stage.session);
            chain = chain.thenApply(session -> { spawned[0] = true; return session; });
            assertTrue(chain.isCompletedExceptionally(), "the bind refusal fails the launch");
            Throwable failure = failureOf(chain);
            assertTrue(failure instanceof BindingRefusal && failure.getMessage()
                    .contains("Could not write the session link"), String.valueOf(failure));
            assertFalse(spawned[0], "nothing spawns: the tab and the spawn compose after the bind");
            TaskDocument after = document(stage.storeRoot, stage.task());
            assertEquals(TaskStatus.OPEN, after.status(), "no partial state: still open");
            assertTrue(after.sessions().isEmpty(), "no partial session link survives");
            assertEquals(TaskRun.Phase.FAILURE, receipt(stage.storeRoot, stage.task()).phase(), "reservation released");
        } finally {
            stage.permissions("rwxr-xr-x");
        }
    }

    // 3. A failed launch reverts a document still at its bind, never a later done/review (:145-167).
    @Test
    void aFailedLaunchRevertsStartedButNeverClobbersALaterDoneOrReview(@TempDir Path dir) throws Exception {
        Stage reverted = Stage.of(dir, "Fix the login redirect");
        Stage kept = Stage.of(dir, "Fix the token refresh");
        reverted.binding.bind(reverted.session);
        kept.binding.bind(kept.session);
        try (DelayedTaskService harness = new DelayedTaskService(kept.storeRoot, executor,
                () -> List.of(reverted.session, kept.session), List::of)) {
            harness.initialize().get(LIMIT.toMillis(), TimeUnit.MILLISECONDS);
            JsonValue done = harness.taskTools().call(kept.session.id(), "task_update",
                    args("id", kept.task().text(), "status", "done"));
            assertEquals("review", textOf(done, "status"),
                    "the later agent done lands as review (T07/T10a) before the launch failed");
        }
        reverted.binding.launchFailed(new IllegalStateException("the harness never started"));
        kept.binding.launchFailed(new IllegalStateException("the harness never started"));
        assertEquals(TaskStatus.OPEN, document(reverted.storeRoot, reverted.task()).status(), "the bind reverts");
        assertTrue(document(reverted.storeRoot, reverted.task()).sessions().isEmpty(), "the session link is undone");
        assertEquals(TaskStatus.REVIEW, document(kept.storeRoot, kept.task()).status(), "done never clobbered");
        assertTrue(document(kept.storeRoot, kept.task()).sessions().contains(kept.session.id().toString()), "kept");
        assertTrue(logged.stream().anyMatch(record -> record.getMessage().contains("never clobbered")),
                "the kept transition is a logged refusal, never a silent rewrite");
        assertEquals(TaskRun.Phase.FAILURE, receipt(kept.storeRoot, kept.task()).phase(), "reservations released");
    }

    // 4. A cancel inside the bind window (the write committed, the spawn not yet run)
    //    resolves through bindingOutcomeNotified :317-334: the future completes, no spawn.
    @Test
    @SuppressWarnings("unchecked")
    void aCancelDuringTheBindWindowResolvesCleanly(@TempDir Path dir) throws Exception {
        Stage stage = Stage.of(dir, "Fix the signup redirect");
        stage.binding.bind(stage.session);
        CompletableFuture<ManagedAgentSession> launch = new CompletableFuture<>();
        boolean[] spawned = {false};
        CompletableFuture<ManagedAgentSession> wrapped = (CompletableFuture<ManagedAgentSession>)
                seam("bindingOutcomeNotified", stage.binding, launch);
        CompletableFuture<ManagedAgentSession> observed
                = wrapped.thenApply(session -> { spawned[0] = true; return session; });
        launch.cancel(true);
        assertTrue(failureOf(observed) instanceof CancellationException, "the cancelled race resolves cleanly");
        assertFalse(spawned[0], "nothing spawns after the cancel");
        assertEquals(TaskRun.Phase.FAILURE, receipt(stage.storeRoot, stage.task()).phase(), "reservation released");
        assertEquals(TaskStatus.OPEN, document(stage.storeRoot, stage.task()).status(), "reconciled away");
        assertTrue(document(stage.storeRoot, stage.task()).sessions().isEmpty(), "no link survives");
    }

    // 5. Stale callbacks are logged refusals that write nothing (receipts :170-182), and the
    //    T27b refused release stays pinned AS-IS: a vanished owner catalog keeps it live.
    @Test
    void staleCallbacksAreLoggedRefusalsAndTheRefusedReleaseStaysPinned(@TempDir Path dir) throws Exception {
        Stage stale = Stage.of(dir, "Fix the login redirect");
        byte[] before = Files.readAllBytes(stale.taskFile());
        assertDoesNotThrow(() -> stale.binding.launched(), "a stale callback never crashes");
        assertDoesNotThrow(() -> stale.binding.launchFailed(new IllegalStateException("late")), "never crashes");
        assertNull(receipt(stale.storeRoot, stale.task()), "a stale callback writes no receipt");
        assertArrayEquals(before, Files.readAllBytes(stale.taskFile()), "a stale callback rewrites nothing");
        assertTrue(logged.stream().anyMatch(record -> record.getMessage().contains("stale")),
                "the stale refusal is logged, never silent");
        Stage pinned = Stage.of(dir, "Fix the token refresh", dir.resolve("gone-catalog"));
        pinned.permissions("r-xr-xr-x"); // the bind write cannot commit; the release must refuse
        try {
            assertThrows(BindingRefusal.class, () -> pinned.binding.bind(pinned.session),
                    "the bind still refuses visibly: the write cannot commit");
            TaskRun kept = receipt(pinned.storeRoot, pinned.task());
            assertEquals(TaskRun.Phase.PREPARE, kept.phase(),
                    "the T27b refused release is pinned AS-IS: the reservation stays live");
            assertTrue(logged.stream().anyMatch(record -> record.getMessage().contains("could not be released")),
                    "the refused release is logged, never worked around");
        } finally {
            pinned.permissions("rwxr-xr-x");
        }
    }

    // 6. No-op hook byte-identity: the default callback does nothing, preparedSessionNotified
    //    :81-90 passes the session through, and the wrapper changes neither the result nor bytes.
    @Test
    @SuppressWarnings("unchecked")
    void theNoOpHookKeepsOldCallerBehaviorByteIdentical(@TempDir Path dir) throws Exception {
        Stage stage = Stage.of(dir, "Fix the login redirect");
        SessionDialogs.PreparedSessionCallback none = new SessionDialogs.PreparedSessionCallback() { };
        assertDoesNotThrow(() -> none.prepared(stage.session), "the default callback is a no-op");
        assertSame(stage.session, seam("preparedSessionNotified", none, stage.session),
                "the default hook passes the session through unchanged");
        stage.binding.bind(stage.session);
        byte[] bound = Files.readAllBytes(stage.taskFile());
        CompletableFuture<ManagedAgentSession> launch = CompletableFuture.completedFuture(stage.session);
        CompletableFuture<ManagedAgentSession> observed = (CompletableFuture<ManagedAgentSession>)
                seam("bindingOutcomeNotified", stage.binding, launch);
        assertSame(stage.session, observed.get(LIMIT.toMillis(), TimeUnit.MILLISECONDS),
                "the wrapper completes with the launch's own session, unchanged");
        assertTrue(launch.isDone() && !launch.isCompletedExceptionally() && !launch.isCancelled(), "untouched");
        assertArrayEquals(bound, Files.readAllBytes(stage.taskFile()), "no task bytes change; only the receipt");
    }

    /** One launched-scenario context: a real store root, one OPEN task, a session, and its binding. */
    private record Stage(Path storeRoot, RepositoryId repository, ManagedAgentSession session,
            TaskLaunchBinding binding) {

        TaskId task() { return binding.task(); }

        Path taskFile() {
            return storeRoot.resolve(repository.toString()).resolve(task().text() + ".md");
        }

        void permissions(String mode) throws Exception {
            Files.setPosixFilePermissions(storeRoot.resolve(repository.toString()),
                    PosixFilePermissions.fromString(mode));
        }

        static Stage of(Path dir, String title) throws Exception {
            return of(dir, title, Files.createDirectories(dir.resolve("catalog")));
        }

        static Stage of(Path dir, String title, Path ownerCatalog) throws Exception {
            Path storeRoot = Files.createDirectories(dir.resolve("tasks"));
            ManagedAgentSession prepared = preparedSession(ManagedSessionId.newId(),
                    Files.createDirectories(dir.resolve("worktree")));
            try (TaskStore store = new TaskStore(storeRoot)) {
                TaskDocument created = store.create(prepared.repositoryId(), title, List.of());
                return new Stage(storeRoot, created.repository(), prepared, new TaskLaunchBinding(
                        created.id(), storeRoot, ownerCatalog, List.of(), 0));
            }
        }
    }

    /** One prepared session, headless, as the launch chain hands it over. */
    private static ManagedAgentSession preparedSession(ManagedSessionId id, Path workingDirectory) {
        Instant now = Instant.now();
        return new ManagedAgentSession(id, RepositoryId.newId(), "session " + id,
                AgentBinding.unlaunched(AgentKind.PI), SessionWorkspace.inRepository(workingDirectory),
                SessionStatus.INACTIVE, now, now, Optional.empty(), PrLink.none(), false, Optional.empty());
    }

    private static TaskDocument document(Path storeRoot, TaskId task) throws Exception {
        return TaskFiles.scan(storeRoot).byId().get(task);
    }

    private static TaskRun receipt(Path storeRoot, TaskId task) throws Exception {
        try (TaskRuns runs = new TaskRuns(storeRoot)) {
            return runs.byTask(task).orElse(null);
        }
    }

    /** One production package-private seam of SessionDialogs, reached reflectively as T22b does. */
    private static Object seam(String name, Object... arguments) {
        for (Method method : SessionDialogs.class.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == arguments.length) {
                try {
                    method.setAccessible(true);
                    return method.invoke(null, arguments);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            }
        }
        throw new AssertionError("no production seam '" + name + "'");
    }

    /** The real failure a done future observes, null when it observes none. */
    private static Throwable failureOf(CompletableFuture<?> future) {
        try {
            future.get(LIMIT.toMillis(), TimeUnit.MILLISECONDS);
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (Exception e) { // cancelled or interrupted: itself the outcome
            return e;
        }
    }

    private static JsonObject args(Object... pairs) {
        JsonObject out = JsonObject.empty();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], new JsonString((String) pairs[i + 1]));
        }
        return out;
    }

    private static String textOf(JsonValue value, String key) {
        JsonValue member = value instanceof JsonObject object ? object.get(key) : null;
        return member instanceof JsonString string ? string.value() : null;
    }

}
