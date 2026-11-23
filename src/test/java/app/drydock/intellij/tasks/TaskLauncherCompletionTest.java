package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.ModelTiers;
import app.drydock.domain.PrLink;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.IsolatedCatalog;
import app.drydock.intellij.actions.SessionDialogs;
import app.drydock.projects.SharedProject;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.TestDialogManager;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.TimeoutUtil;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * The T22b completion matrix, porting the T22a smoke's eight items into the
 * booted IDE: the observable launch future completes on EVERY path (resolved
 * chain, refused preparation, failed worktree and window, cancel, failed
 * spawn, late completion past a timed-out wait, disposed host), and the
 * prepared-session callback defaults to a no-op that never fails a launch.
 *
 * <p>The fixture drives the launch-chain SEAMS, exactly as the smoke did:
 * TrackedLaunch's own completion points (what the production start calls at
 * each branch), the real prepareTask refusal chain, and the production
 * preparedSessionNotified. Waits pump the EDT queue and poll a deadline; no
 * window is ever shown; nothing spawns. Boundary, stated plainly: a real
 * end-to-end launch (the shown dialog's choice, registration, a real
 * worktree and window, the real agent spawn) is NOT exercised here -- it
 * stays with the T22a smoke and the manual path. The two reflective
 * invocations below reach branches with no accessible seam (chooseTask
 * always shows its dialog; launchSession always spawns), pinned to the
 * exact production branch they enter, first statement first.</p>
 */
public class TaskLauncherCompletionTest extends BasePlatformTestCase {

    private final List<Path> dirs = new ArrayList<>();

    @Override
    protected void setUp() throws Exception {
        IsolatedCatalog.assertSandboxed(); // before anything can touch the plugin (T14 discipline)
        super.setUp();
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        try { plugin.initialization().get(60, TimeUnit.SECONDS); } catch (Exception e) { fail("init: " + e); }
    }

    @Override
    protected void tearDown() throws Exception {
        for (Path dir : dirs) { delete(dir); }
        super.tearDown();
    }

    // 1. The chain resolving completes the observable with the session itself.
    public void testTheChainResolvingCompletesTheObservableWithTheSessionItself() {
        ManagedAgentSession started = session("the chain's own session");
        TaskLauncher.TrackedLaunch tracked = new TaskLauncher.TrackedLaunch();
        CompletableFuture<ManagedAgentSession> launch = new CompletableFuture<>();
        tracked.launched(launch);
        assertFalse("before the chain resolves, the observable is pending", tracked.future().isDone());
        ApplicationManager.getApplication().executeOnPooledThread(() -> launch.complete(started));
        await(() -> tracked.future().isDone(), "the launch chain resolving");
        assertSame("the observable completes with the launch's own session, by identity", started,
                resultOf(tracked.future()));
    }

    // 2. A refused preparation completes its future empty and surfaces the reason.
    public void testARefusedPreparationCompletesEmptyAndSurfacesItsReason() throws Exception {
        Path nowhere = Files.createTempDirectory("drydock-launch-refused");
        dirs.add(nowhere);
        List<String> shown = new ArrayList<>();
        TestDialogManager.setTestDialog(m -> { shown.add(m); return Messages.OK; }, getTestRootDisposable());
        CompletableFuture<Optional<TaskLauncher.PreparedTask>> prepared =
                TaskLauncher.prepareTask(getProject(), Optional.of(nowhere), Optional.empty());
        await(() -> prepared.isDone() && !shown.isEmpty(), "the refusal completing and surfacing");
        assertTrue("a refused preparation completes the future, never leaves it pending",
                prepared.isDone() && !prepared.isCancelled() && !prepared.isCompletedExceptionally());
        assertEquals("the refusal's outcome is empty", Optional.empty(), prepared.get(5, TimeUnit.SECONDS));
        assertTrue("the refusal surfaces its reason to the user: " + shown,
                shown.get(0).contains("is not a git repository"));
    }

    // 3. The worktree failure and the window that did not open complete exceptionally.
    public void testTheWorktreeAndWindowFailuresCompleteExceptionally() throws Exception {
        TaskLauncher.TrackedLaunch worktree = new TaskLauncher.TrackedLaunch();
        IllegalStateException refused = new IllegalStateException("worktree creation refused");
        worktree.failed(refused);
        assertTrue("a worktree failure completes the observable, never leaves it pending",
                worktree.future().isDone());
        assertSame("the completion carries the real failure, unchanged", refused, failureOf(worktree.future()));
        Path worktreePath = Files.createTempDirectory("drydock-window-refused");
        dirs.add(worktreePath);
        TaskLauncher.TrackedLaunch window = new TaskLauncher.TrackedLaunch();
        window.windowDidNotOpen(worktreePath);
        assertTrue("a window that did not open completes the observable, never leaves it pending",
                window.future().isDone());
        Throwable failure = failureOf(window.future());
        assertTrue("the completion is the window's own failure", failure instanceof IllegalStateException);
        assertEquals("its message is the production one, traced to the accessor",
                TaskLauncher.windowDidNotOpen(worktreePath), failure.getMessage());
    }

    // 4. A cancelled launch completes the observable instead of leaving it pending.
    public void testACancelledLaunchCompletesTheObservable() {
        TaskLauncher.TrackedLaunch cancelled = new TaskLauncher.TrackedLaunch();
        cancelled.cancelled();
        assertTrue("a cancelled launch completes the observable", cancelled.future().isDone());
        assertTrue("a cancelled launch is cancelled, not merely done", cancelled.future().isCancelled());
        Exception observed = null;
        try { cancelled.future().get(5, TimeUnit.SECONDS); } catch (Exception e) { observed = e; }
        assertTrue("the wait observes the cancellation, not some other outcome",
                observed instanceof CancellationException);
    }

    // 5. The launch chain failing completes the observable with the spawn failure.
    public void testTheChainFailingCompletesTheObservableWithTheSpawnFailure() {
        TaskLauncher.TrackedLaunch tracked = new TaskLauncher.TrackedLaunch();
        CompletableFuture<ManagedAgentSession> launch = new CompletableFuture<>();
        tracked.launched(launch);
        IllegalStateException spawn = new IllegalStateException("the harness never started");
        ApplicationManager.getApplication().executeOnPooledThread(() -> launch.completeExceptionally(spawn));
        await(() -> tracked.future().isDone(), "the failing launch chain");
        assertSame("the observable fails exactly when the launch fails, with its failure", spawn,
                failureOf(tracked.future()));
    }

    // 6. A timed-out wait touches nothing; the late completion stays accurate.
    public void testATimedOutWaitTouchesNothingAndALateCompletionStaysAccurate() {
        ManagedAgentSession session = session("slow but real");
        CompletableFuture<ManagedAgentSession> slowLaunch = new CompletableFuture<>();
        TaskLauncher.TrackedLaunch tracked = new TaskLauncher.TrackedLaunch();
        tracked.launched(slowLaunch);
        AtomicReference<String> waitOutcome = new AtomicReference<>();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                tracked.future().get(150, TimeUnit.MILLISECONDS);
                waitOutcome.set("completed");
            } catch (TimeoutException e) { waitOutcome.set("timed out"); }
            catch (Exception e) { waitOutcome.set("failed: " + e); }
        });
        await(() -> waitOutcome.get() != null, "the bounded wait returning");
        assertEquals("a slow launch is observable as a timed-out wait", "timed out", waitOutcome.get());
        assertFalse("the timed-out wait leaves the observable incomplete", tracked.future().isDone());
        assertTrue("the timed-out wait leaves the launch itself untouched",
                !slowLaunch.isDone() && !slowLaunch.isCancelled() && !slowLaunch.isCompletedExceptionally());
        slowLaunch.complete(session); // late, off the wait: the real chain resolving
        await(() -> tracked.future().isDone(), "the late completion landing");
        assertSame("the late completion is still accurate to the real outcome", session,
                resultOf(tracked.future()));
        assertTrue("nothing marked the launch failed or cancelled",
                slowLaunch.isDone() && !slowLaunch.isCancelled() && !slowLaunch.isCompletedExceptionally());
    }

    // 7. A disposed host completes the outstanding chosen future: no pending, no leak.
    public void testADisposedHostCompletesTheOutstandingChosenFuture() {
        CompletableFuture<Optional<TaskLauncher.ChosenTask>> chosen = new CompletableFuture<>();
        // No accessible seam: chooseTask always shows its dialog. The production
        // chooseChosen checks the host FIRST, before any dialog is built.
        chooseChosen(disposedHost(), prepared(Path.of(System.getProperty("user.dir"))), chosen);
        assertTrue("a disposed host completes the chosen future, never leaves it pending", chosen.isDone());
        assertEquals("it completes empty: nothing was ever shown or launched", Optional.empty(),
                chosen.getNow(null));
    }

    // 8. The prepared-session callback: no-op default, invoked once, failures swallowed.
    public void testThePreparedSessionCallbackDefaultsToNoopAndSwallowsFailures() {
        ManagedAgentSession session = session("prepared for binding");
        SessionDialogs.PreparedSessionCallback none = new SessionDialogs.PreparedSessionCallback() { };
        RuntimeException fromDefault = null;
        try { none.prepared(session); } catch (RuntimeException e) { fromDefault = e; }
        assertNull("the default callback does nothing and throws nothing", fromDefault);
        assertSame("the default passes the prepared session through unchanged", session,
                preparedSessionNotified(none, session));
        List<ManagedAgentSession> seen = new ArrayList<>();
        SessionDialogs.PreparedSessionCallback capturing = new SessionDialogs.PreparedSessionCallback() {
            @Override public void prepared(ManagedAgentSession prepared) { seen.add(prepared); }
        };
        assertSame("a real callback is invoked with the prepared session", session,
                preparedSessionNotified(capturing, session));
        assertEquals("and it is invoked exactly once, with that session", List.of(session), seen);
        SessionDialogs.PreparedSessionCallback throwing = new SessionDialogs.PreparedSessionCallback() {
            @Override public void prepared(ManagedAgentSession prepared) {
                throw new IllegalStateException("binding failed");
            }
        };
        assertSame("a failing callback is swallowed: the session still passes through", session,
                preparedSessionNotified(throwing, session));
    }

    // ---- seams and helpers --------------------------------------------------

    /** A prepared session, built headlessly the way the T22a smoke built one. */
    private static ManagedAgentSession session(String displayName) {
        Instant now = Instant.now();
        return new ManagedAgentSession(ManagedSessionId.newId(), RepositoryId.newId(), displayName,
                AgentBinding.unlaunched(AgentKind.PI),
                SessionWorkspace.inRepository(Path.of(System.getProperty("user.dir"))),
                SessionStatus.RUNNING, now, now, Optional.empty(), PrLink.none(), false, Optional.empty());
    }

    /** A prepared task over dialog inputs, as this package's own fixtures build them. */
    private static TaskLauncher.PreparedTask prepared(Path root) {
        NewTaskPlan.Inputs inputs = new NewTaskPlan.Inputs(SharedProject.local(RepositoryId.newId(), root,
                "profiler", Instant.EPOCH), "profiler", Optional.empty(), List.of(), AgentKind.PI, "main",
                List.of("main"), List.of("origin"), Set.of(), root, false, kind -> Optional.empty(),
                kind -> List.of(), kind -> ModelTiers.EMPTY, kind -> false);
        return new TaskLauncher.PreparedTask(inputs);
    }

    /** A stub host whose isDisposed() is true: the only thing the branch touches. */
    private static Project disposedHost() {
        return (Project) Proxy.newProxyInstance(TaskLauncherCompletionTest.class.getClassLoader(),
                new Class<?>[]{Project.class}, (proxy, method, args) ->
                "isDisposed".equals(method.getName()) ? Boolean.TRUE
                        : method.getReturnType() == boolean.class ? Boolean.FALSE : null);
    }

    /** The production chooseChosen's disposed-host branch; scrutinized: first statement first. */
    private static void chooseChosen(Project from, TaskLauncher.PreparedTask preparedTask,
            CompletableFuture<Optional<TaskLauncher.ChosenTask>> chosen) {
        try {
            Method branch = TaskLauncher.class.getDeclaredMethod("chooseChosen", Project.class,
                    TaskLauncher.PreparedTask.class, Optional.class, NewTaskPreset.class,
                    CompletableFuture.class);
            branch.setAccessible(true);
            branch.invoke(null, from, preparedTask, Optional.empty(), NewTaskPreset.empty(), chosen);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** The production preparedSessionNotified; no accessible seam: launchSession always spawns. */
    private static ManagedAgentSession preparedSessionNotified(SessionDialogs.PreparedSessionCallback callback,
            ManagedAgentSession session) {
        try {
            Method notified = SessionDialogs.class.getDeclaredMethod("preparedSessionNotified",
                    SessionDialogs.PreparedSessionCallback.class, ManagedAgentSession.class);
            notified.setAccessible(true);
            return (ManagedAgentSession) notified.invoke(null, callback, session);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** The session a done observable completes with. */
    private static ManagedAgentSession resultOf(CompletableFuture<ManagedAgentSession> future) {
        try { return future.get(5, TimeUnit.SECONDS); }
        catch (Exception e) { throw new AssertionError("no session: " + e, e); }
    }

    /** The real failure a done future observes, null when it observes none. */
    private static Throwable failureOf(CompletableFuture<?> future) {
        try { future.get(5, TimeUnit.SECONDS); return null; }
        catch (ExecutionException e) { return e.getCause(); }
        catch (Exception e) { return e; } // cancelled or interrupted: itself the outcome
    }

    /** Spins (never sleeps) until the condition holds, draining the EDT queue between polls. */
    private void await(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (!condition.getAsBoolean()) {
            assertTrue("timed out waiting for " + what, System.nanoTime() < deadline);
            for (int i = 0; i < 3; i++) { PlatformTestUtil.dispatchAllEventsInIdeEventQueue(); TimeoutUtil.sleep(20); }
        }
    }

    private static void delete(Path directory) {
        try (Stream<Path> entries = Files.walk(directory)) {
            entries.sorted(java.util.Comparator.reverseOrder()).forEach(
                    p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { } });
        } catch (IOException ignored) { }
    }
}
