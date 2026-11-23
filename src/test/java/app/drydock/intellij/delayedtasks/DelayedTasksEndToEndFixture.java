package app.drydock.intellij.delayedtasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.delayedtasks.*;
import app.drydock.domain.*;
import app.drydock.intellij.tasks.NewTaskPlan;
import app.drydock.tasks.*;

import com.intellij.notification.Notification;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.TestDialogManager;
import com.intellij.openapi.wm.*;
import com.intellij.openapi.wm.impl.FocusManagerImpl;
import com.intellij.testFramework.*;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.TimeoutUtil;

import java.io.IOException;
import java.lang.reflect.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * The shared booted-services harness of the T43b integrated matrix, the final
 * task of the delayed-tasks plan (a supervisor-granted two-file split, the
 * CrossProjectStartFixture/T38b precedent): a REAL DelayedTaskService per
 * test over a per-test temp root -- real watcher, real notification wiring,
 * real plan recovery -- booted exactly as the plugin boots it, plus the two-
 * stage driven-plan machinery (only the launcher is faked, through the
 * service's own caller-supplied SegmentLauncher seam: never a real agent
 * spawn), the balloon capture (TestDialogManager and the focused-frame stub,
 * nothing ever shown) and the reflective publication seam the store watcher
 * itself calls. Pump-park waits only. The five matrix tests live wholly in
 * DelayedTasksEndToEndTest: (1) source-file edit through the watcher refresh
 * with the D6/P5 unsaved-buffer rule, (2) agent done->REVIEW blocking plan
 * advancement until the human Confirm, (3) retained worktree context after a
 * prerequisite FORGET, (4) the authenticated in-process MCP get/update
 * round-trip through the real backend router, (5) the published tree state
 * after a driven stage. Cross-process contention stays with T43a; the known
 * residuals (text-only Continue balloon, undispatched TaskReopened,
 * unconsumed worktreeRemovalPending) are pinned there and by T39b/T41b.
 */
abstract class DelayedTasksEndToEndFixture extends BasePlatformTestCase {

    static final long SECONDS = 30; // one bounded future or spin

    Path root;
    DelayedTaskService service;
    final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    final List<Notification> balloons = new CopyOnWriteArrayList<>();
    final List<Path> cleanup = new ArrayList<>();

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        TestDialogManager.setTestDialog(message -> 0, getTestRootDisposable()); // no dialog may hang a test
        getProject().getMessageBus().connect(getTestRootDisposable())
                .subscribe(Notifications.TOPIC, new Notifications() {
                    @Override public void notify(Notification n) { balloons.add(n); }
                });
        FocusManagerImpl focus = new FocusManagerImpl(); // the sandbox never shows a window, so the
        focusOn(focus, getProject()); // production focused-frame idiom is fed the test project
        ServiceContainerUtil.replaceService(ApplicationManager.getApplication(), IdeFocusManager.class,
                focus, getTestRootDisposable());
    }

    @Override
    protected void tearDown() throws Exception {
        if (service != null) service.close();
        if (root != null) delete(root);
        for (Path dir : cleanup) delete(dir);
        super.tearDown();
    }

    /** One driven plan's pieces: the stage-1 task, the target, the target's driving
      * session (null when the drive binds none), and the fake launcher. */
    record Driven(TaskId gate, TaskId ship, String shipSession, FakeLauncher launcher) { }

    /** Boots the real service over a fresh temp root with the two-repository catalog,
      * plans the gate-then-ship two-stage graph and drives it through the explicit
      * Continue over the launcher seam (the service's own injection point). */
    Driven drivePlan(boolean bind) throws Exception {
        root = Files.createTempDirectory("drydock-t43b-plan");
        RepositoryId alpha = RepositoryId.newId(), bravo = RepositoryId.newId();
        Map<RepositoryId, Path> worktrees = Map.of(alpha, root.resolve("alpha"), bravo, root.resolve("bravo"));
        Map<RepositoryId, ManagedAgentSession> agents = bind
                ? Map.of(alpha, agent(alpha, worktrees), bravo, agent(bravo, worktrees)) : Map.of();
        List<Repository> registered = List.of(
                new Repository(alpha, worktrees.get(alpha), "alpha-proj", Instant.EPOCH,
                        Instant.EPOCH, new RepositorySettings(Optional.empty())),
                new Repository(bravo, worktrees.get(bravo), "bravo-proj", Instant.EPOCH,
                        Instant.EPOCH, new RepositorySettings(Optional.empty())));
        service = new DelayedTaskService(root, threads, () -> List.copyOf(agents.values()), () -> registered);
        await(() -> service.recovery() != null, "the service's first recovery pass");
        TaskId gate = create("Gate work", alpha, List.of());
        TaskId ship = create("Ship it", bravo, List.of(gate.text()));
        TaskSnapshot snapshot = service.snapshot().get(SECONDS, TimeUnit.SECONDS);
        Map<RepositoryId, String> slugs = Map.of(alpha, "alpha-proj", bravo, "bravo-proj");
        StartPlan plan = StartPlanner.plan(ship, snapshot, TaskGraph.of(snapshot, slugs::get), slugs::get,
                service.liveSessionIds());
        assertTrue("the startable two-stage plan", plan.startable() && plan.stages().size() == 2);
        Map<RepositoryId, NewTaskPlan.Choice> segments = new LinkedHashMap<>();
        for (RepositoryId repository : List.of(alpha, bravo))
            segments.put(repository, choice(worktrees.get(repository)));
        service.persistConfirmedPlan(plan, new PlanLaunchChoices(ship, List.of(1, 2), segments, Map.of()),
                new PlanLaunch.Origin("t43b-window", root)).get(SECONDS, TimeUnit.SECONDS);
        FakeLauncher launcher = new FakeLauncher(bind ? agents : null);
        PlanRecovery.Outcome driven = service.continuePlan(ship, launcher, launcher).get(60, TimeUnit.SECONDS);
        assertTrue("the driven Continue proceeds: " + driven, driven instanceof PlanRecovery.Proceed);
        await(() -> launcher.launched.size() == 1, "stage 1 to launch through the launcher seam");
        return new Driven(gate, ship, bind ? agents.get(bravo).id().toString() : null, launcher);
    }

    /** The controlled launcher (never a real spawn): every launch answers a minted id
      * immediately; prepare binds the driving session into the stage's tasks when the
      * placement coverage is asked for (the real T28a pre-spawn binding). */
    final class FakeLauncher implements PlanDriver.SegmentLauncher, PlanDriver.Listener {
        final List<PlanDriver.SegmentRequest> launched = new CopyOnWriteArrayList<>();
        final List<PlanProgress.Effect> effects = new CopyOnWriteArrayList<>();
        private final Map<RepositoryId, ManagedAgentSession> agents;
        FakeLauncher(Map<RepositoryId, ManagedAgentSession> agents) { this.agents = agents; }
        @Override public CompletableFuture<Optional<PlanDriver.Prepared>> prepare(
                PlanDriver.SegmentRequest request) {
            if (agents != null) for (TaskId task : request.tasks())
                service.launchBinding(task, root.resolve("owner"), List.of(), 0)
                        .bind(agents.get(request.repository()));
            return CompletableFuture.completedFuture(Optional.of(new PlanDriver.Prepared() { }));
        }
        @Override public CompletableFuture<PlanDriver.Launched> launch(PlanDriver.Prepared made,
                PlanDriver.SegmentRequest request) {
            launched.add(request);
            return CompletableFuture.completedFuture(new PlanDriver.Launched("launch-" + launched.size(),
                    agents == null ? "session-x" : agents.get(request.repository()).id().toString()));
        }
        @Override public void surfaced(PlanProgress.Effect effect) { effects.add(effect); }
        @Override public void halted(String reason, List<PlanDriver.Started> started) { }
    }

    private static ManagedAgentSession agent(RepositoryId repository, Map<RepositoryId, Path> worktrees) {
        return new ManagedAgentSession.Builder(ManagedSessionId.newId(), repository, "Plan agent",
                AgentBinding.unlaunched(AgentKind.PI, SessionMode.WRITES),
                SessionWorkspace.inRepository(worktrees.get(repository)), Instant.EPOCH).build();
    }

    private static NewTaskPlan.Choice choice(Path worktree) {
        return new NewTaskPlan.Choice("Plan start", Optional.empty(), AgentKind.PI, SessionMode.WRITES,
                NewTaskPlan.WorkIn.MAIN_CHECKOUT, "main", "main", worktree, NewTaskPlan.OpenIn.THIS_WINDOW,
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    // ---- shared seams and waits --------------------------------------------------

    TaskId create(String title, RepositoryId repository, List<String> after) throws Exception {
        var outcome = service.create(title, after, repository).get(SECONDS, TimeUnit.SECONDS);
        assertFalse("the task creation was refused: " + outcome.refusal(), outcome.refused());
        return outcome.document().id();
    }

    void update(TaskId task, TaskState.Actor actor, TaskStatus to) throws Exception {
        var outcome = service.updateStatus(task, actor, to).get(SECONDS, TimeUnit.SECONDS);
        assertFalse("the update was refused: " + outcome.refusal(), outcome.refused());
    }

    TaskStatus status(TaskId task) { // join: an unanswered store fails the test loudly
        return service.snapshot().join().byId().get(task).status();
    }

    /** The production publication seam the store watcher itself calls (T31b). */
    void publish() {
        try {
            Method seam = DelayedTaskService.class.getDeclaredMethod("publishWatchedSnapshot",
                    TaskStoreWatcher.WatchedSnapshot.class);
            seam.setAccessible(true);
            seam.invoke(service, new TaskStoreWatcher.WatchedSnapshot(TaskFiles.scan(root), List.of(), null));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("no publication seam", e);
        } catch (IOException e) {
            throw new AssertionError("the store scan failed", e);
        }
    }

    Notification awaitBalloon(String title, String what) {
        await(() -> balloons.stream().anyMatch(n -> title.equals(n.getTitle())), what);
        return balloons.stream().filter(n -> title.equals(n.getTitle())).findFirst().orElseThrow();
    }

    /** The balloon's own action, dispatched through the platform's event machinery. */
    void dispatch(Notification balloon, String actionText) {
        AnAction action = balloon.getActions().stream()
                .filter(a -> actionText.equals(a.getTemplatePresentation().getText())).findFirst()
                .orElseThrow(() -> new AssertionError("no '" + actionText + "' action"));
        action.actionPerformed(AnActionEvent.createEvent(SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, getProject()).add(Notification.KEY, balloon).build(),
                new Presentation(), ActionPlaces.ACTION_SEARCH, ActionUiKind.NONE, null));
    }

    /** Spins (never sleeps bare) until the condition holds, pumping the EDT between polls. */
    void await(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SECONDS);
        while (!condition.getAsBoolean()) {
            assertTrue("timed out waiting for " + what, System.nanoTime() < deadline);
            pump();
        }
    }

    /** Drains rounds of the EDT queue (the test runs on it) so the refreshes run. */
    static void pump() {
        for (int i = 0; i < 3; i++) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            TimeoutUtil.sleep(20);
        }
    }

    /** The focused-frame seam: FocusManagerImpl's last-focused frame, stubbed to the test project. */
    private static void focusOn(FocusManagerImpl focus, Project project) {
        try {
            Field frame = FocusManagerImpl.class.getDeclaredField("myLastFocusedFrame");
            frame.setAccessible(true);
            frame.set(focus, (IdeFrame) Proxy.newProxyInstance(IdeFrame.class.getClassLoader(),
                    new Class<?>[] {IdeFrame.class}, (proxy, method, args) ->
                            "getProject".equals(method.getName()) ? project
                                    : "hashCode".equals(method.getName()) ? System.identityHashCode(proxy)
                                    : null));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("no focus seam", e);
        }
    }

    static void delete(Path directory) {
        if (directory == null) {
            return;
        }
        try (Stream<Path> entries = Files.walk(directory)) {
            entries.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
