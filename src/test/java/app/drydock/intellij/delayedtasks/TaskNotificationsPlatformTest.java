package app.drydock.intellij.delayedtasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.delayedtasks.*;
import app.drydock.domain.*;
import app.drydock.intellij.tasks.NewTaskPlan;
import app.drydock.intellij.tasks.NewTaskPlan.Choice;
import app.drydock.tasks.PlanStore.Claim;
import app.drydock.tasks.*;

import com.intellij.notification.Notification;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.TestDialogManager;
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.openapi.wm.IdeFrame;
import com.intellij.openapi.wm.impl.FocusManagerImpl;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.ServiceContainerUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.TimeoutUtil;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * The T39b matrix for the accepted task balloons (T39a) in the booted IDE:
 * a real DelayedTaskService over a per-test temp-dir store root whose init
 * builds the REAL notification wiring (TaskNotifications.forIde, the "Drydock
 * tasks" balloon group) exactly as the plugin boots it; zero store mocking;
 * driven plans ride the cross-project flow's own seams; snapshots enter
 * through the production publication seam the watcher calls (T31b);
 * pump-park waits only, nothing shown. One test per plan-T39 matrix item,
 * in the plan's listed order; item 3 pins the recovery-scan halt (T36a) because the D16
 * TaskReopened reducer event has no production dispatcher yet. KNOWN RESIDUAL
 * (coordinator-routed, do NOT fix here): the startup-Continue balloon is
 * TEXT-ONLY -- P4.10's one-click Continue is deferred to a follow-up task
 * (item 5 pins it); the ready balloon's Start (D12) rides the live start
 * flow, so stale-object refusals are pinned through Confirm/Reopen instead.
 */
public class TaskNotificationsPlatformTest extends BasePlatformTestCase {

    private Path root;
    private RepositoryId repo;
    private DelayedTaskService service;
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Notification> onProject = new CopyOnWriteArrayList<>();
    private final List<String> dialogs = new CopyOnWriteArrayList<>();
    private volatile List<ManagedAgentSession> sessions = List.of();
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        root = Files.createTempDirectory("drydock-t39b"); repo = RepositoryId.newId();
        VfsRootAccess.allowRootAccess(getTestRootDisposable(), root.toString());
        TestDialogManager.setTestDialog(message -> { dialogs.add(message); return 0; }, getTestRootDisposable());
        getProject().getMessageBus().connect(getTestRootDisposable())
                .subscribe(Notifications.TOPIC, recorder(onProject));
        FocusManagerImpl focus = new FocusManagerImpl(); // the sandbox never shows a window, so the
        focusOn(focus, getProject()); // production focused-frame idiom is fed the test project
        ServiceContainerUtil.replaceService(ApplicationManager.getApplication(), IdeFocusManager.class,
                focus, getTestRootDisposable());
    }
    @Override
    protected void tearDown() throws Exception {
        if (service != null) service.close(); delete(root); super.tearDown();
    }
    public void testRepeatedSnapshotDoesNotRenotifyAndLeavingRearms() throws Exception {
        TaskId alpha = write("t39-alpha", "Alpha", List.of());
        boot();
        publish(); publish(); publish(); // the first primes the baseline: no storm
        assertTrue("a held state never re-announces", onProject.isEmpty());
        update(alpha, TaskState.Actor.AGENT, TaskStatus.DONE); publish(); // parks in review
        Notification review = awaitBalloon("Task review: 'Alpha'", "the review balloon");
        publish(); publish(); // the repeated transition never re-notifies
        assertEquals("exactly one review balloon", List.of(review), balloons("Task review: 'Alpha'"));
        update(alpha, TaskState.Actor.HUMAN, TaskStatus.DONE); publish(); // confirmed: no balloon
        assertEquals("DONE announces nothing", List.of(review), balloons("'Alpha'"));
        update(alpha, TaskState.Actor.HUMAN, TaskStatus.OPEN); publish(); // leaving review re-armed
        awaitBalloon("Task ready: 'Alpha'", "the re-armed ready balloon");
        assertEquals("the re-armed ready balloon", 2, balloons("'Alpha'").size());
        assertEquals("Task ready: 'Alpha'", balloons("Task ready: 'Alpha'").get(0).getTitle());
    }
    public void testConfirmActionResolvesTheLatestState() throws Exception {
        Notification review = reviewBalloon("t39-beta", "Beta");
        assertEquals("the review balloon carries both answers and Show Session", 3, review.getActions().size());
        update(TaskId.of("t39-beta"), TaskState.Actor.HUMAN, TaskStatus.DONE); publish(); // moved on
        dispatch(review, "Confirm Done");
        await(() -> !dialogs.isEmpty(), "the stale balloon's refusal");
        assertTrue("the refusal names the fresh state", dialogs.get(0).contains("no longer awaits review"));
        assertEquals("the stale object was never acted on", TaskStatus.DONE, status(TaskId.of("t39-beta")));
        assertTrue("exactly the one refusal, no re-notify from it", dialogs.size() == 1
                && balloons("Task review: 'Beta'").size() == 1);
    }
    public void testShowSessionWithNoCatalogSessionRefusesVisibly() throws Exception {
        Notification review = reviewBalloon("t39-eta", "Eta"); // no session was ever bound
        dispatch(review, "Show Session");
        await(() -> !dialogs.isEmpty(), "the Show Session refusal");
        assertTrue("the refusal names the task by title", dialogs.get(0).contains("'Eta' has no session left"));
        assertEquals("Show Session answers nothing about the review", TaskStatus.REVIEW, status(TaskId.of("t39-eta")));
    }
    public void testReopenOnADrivenPlanHaltsItAndSurfacesTheReason() throws Exception {
        TaskId gate = write("t39-gate", "Gate work", List.of());
        write("t39-ship", "Ship it", List.of("t39-gate")); Files.createDirectories(root.resolve("owner"));
        ManagedAgentSession agent = new ManagedAgentSession.Builder(ManagedSessionId.newId(), repo,
                "Gate agent", AgentBinding.unlaunched(AgentKind.PI, SessionMode.WRITES),
                SessionWorkspace.inRepository(root.resolve("repo")), Instant.EPOCH).build();
        sessions = List.of(agent); boot();
        drivePlan(gate, new Launch(agent, true)); publish(); // stage 1 launches via the binding
        update(gate, TaskState.Actor.AGENT, TaskStatus.DONE); publish(); // parks in review (D16)
        Notification review = awaitBalloon("Task review: 'Gate work'", "the driven plan's review");
        sessions = List.of(); // the agent's session ends with its reopened work
        dispatch(review, "Reopen");
        await(() -> status(gate) == TaskStatus.OPEN, "the balloon's reopen committing");
        publish();
        Notification halt = awaitBalloon("Drydock plan halted", "the halt reason surfacing");
        assertTrue("the halt names the unreconcilable reservation", halt.getContent()
                .contains("holds a live PREPARE receipt") && halt.getContent().contains("no longer live"));
        assertTrue("the reopened task got no competing balloon", balloons("Task ready: 'Gate work'").isEmpty());
    }
    public void testStaleDroppedTaskRefusesVisibly() throws Exception {
        Notification review = reviewBalloon("t39-delta", "Delta");
        update(TaskId.of("t39-delta"), TaskState.Actor.HUMAN, TaskStatus.DROPPED); publish(); // gone
        dispatch(review, "Confirm Done"); dispatch(review, "Reopen");
        await(() -> dialogs.size() == 2, "both stale actions refusing");
        for (String refusal : dialogs) assertTrue(refusal.contains("no longer awaits review"));
        assertEquals("the stale object was never acted on", TaskStatus.DROPPED, status(TaskId.of("t39-delta")));
    }
    public void testStartupContinuePublishesTextOnly() throws Exception {
        TaskId solo = write("t39-solo", "Solo start", List.of());
        try (PlanStore plans = new PlanStore(root); Claim claim = plans.acquireClaim(solo).get(30, TimeUnit.SECONDS)) {
            plans.save(claim, 0, new RunningPlan(new PlanLaunch(solo, List.of(1), Map.of(solo,
                    new PlanLaunch.Selection(AgentKind.PI, repo, "T39b Project")), 1, List.of(), 0,
                    new PlanLaunch.Origin("t39b-window", root)), List.of(new RunningPlan.StageState(1,
                    List.of(solo), false, false)), RunningPlan.Status.RUNNING), false).get(30, TimeUnit.SECONDS);
        }
        boot(); // the init recovery publishes what the restarted plan needs (P4.10)
        Notification resume = awaitBalloon("Drydock plan needs your Continue", "the Continue");
        assertTrue("the requirement names the paused state", resume.getContent().contains("was paused by a")
                && resume.getContent().contains("nothing launched"));
        assertTrue("the Continue balloon is text-only today (the one-click residual)",
                resume.getActions().isEmpty());
    }
    public void testPublicationReachesTheFocusedProjectWithoutDuplicates() throws Exception {
        write("t39-echo", "Echo", List.of()); boot(); publish(); // the baseline primes quietly
        write("t39-fresh", "Fresh work", List.of()); publish();
        Notification ready = awaitBalloon("Task ready: 'Fresh work'", "the ready balloon");
        assertEquals("Drydock tasks", ready.getGroupId()); // plugin.xml's only task group
        assertEquals("one delivery to the focused project", 1, balloons("Task ready: 'Fresh work'").size());
        publish(); publish(); pump();
        assertEquals("no duplicates when the state republishes", 1, balloons("Task ready: 'Fresh work'").size());
    }
    public void testFailedActionClearsItsBalloonWithNoLoop() throws Exception {
        Notification review = reviewBalloon("t39-grit", "Grit");
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("---------")); // fails all
        try {
            dispatch(review, "Confirm Done");
            await(() -> !dialogs.isEmpty(), "the failed write's visible refusal");
        } finally { Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-xr-x")); }
        assertTrue("the failure is visible: " + dialogs.get(0), dialogs.get(0).contains("could not change"));
        assertTrue("the failed action still cleared its balloon", review.isExpired());
        publish(); publish(); pump(); // the unchanged state must not re-notify (no loop)
        assertEquals("no second balloon for the same transition", 1, balloons("'Grit'").size());
        assertEquals("the failed write changed nothing", TaskStatus.REVIEW, status(TaskId.of("t39-grit")));
    }
    public void testOwnedPlanTasksGetNoCompetingReadyBalloons() throws Exception {
        TaskId gate = write("t39-gate", "Gate work", List.of());
        write("t39-ship", "Ship it", List.of("t39-gate")); boot();
        drivePlan(gate, new Launch(null, false)); // the service now owns the plan's tasks
        write("t39-free", "Free work", List.of()); publish(); // an un-owned task alongside it
        awaitBalloon("Task ready: 'Free work'", "the un-owned ready balloon");
        publish(); publish();
        assertTrue(balloons("Task ready: 'Gate work'").isEmpty());
        assertTrue(balloons("Task ready: 'Ship it'").isEmpty());
    }

    /** The fixture's seams: reserve drives the real T28a binding (a PREPARE receipt whose
      * LAUNCH completion never lands, the ambiguous spawn T36a reconciles). */
    private final class Launch implements PlanDriver.SegmentLauncher, PlanDriver.Listener {
        private final ManagedAgentSession session;
        private final boolean reserve;
        Launch(ManagedAgentSession session, boolean reserve) { this.session = session; this.reserve = reserve; }
        @Override
        public CompletableFuture<Optional<PlanDriver.Prepared>> prepare(PlanDriver.SegmentRequest r) {
            if (reserve) { // the production discipline: bind before anything spawns (T28a)
                for (TaskId task : r.tasks())
                    service.launchBinding(task, root.resolve("owner"), List.of(), 0).bind(session);
            }
            return CompletableFuture.completedFuture(Optional.of(new PlanDriver.Prepared() { }));
        }
        @Override
        public CompletableFuture<PlanDriver.Launched> launch(PlanDriver.Prepared prepared,
                PlanDriver.SegmentRequest r) {
            return CompletableFuture.completedFuture(new PlanDriver.Launched("launch-1",
                    session == null ? "session-x" : session.id().toString()));
        }
        @Override public void surfaced(PlanProgress.Effect effect) { }
        @Override public void halted(String reason, List<PlanDriver.Started> started) { }
    }

    private Notification reviewBalloon(String slug, String title) throws Exception {
        write(slug, title, List.of()); boot(); publish();
        update(TaskId.of(slug), TaskState.Actor.AGENT, TaskStatus.DONE); publish();
        return awaitBalloon("Task review: '" + title + "'", "the review balloon");
    }
    private void update(TaskId task, TaskState.Actor actor, TaskStatus to) throws Exception {
        var outcome = service.updateStatus(task, actor, to).get(30, TimeUnit.SECONDS);
        assertFalse("the fixture's update was refused: " + outcome.refusal(), outcome.refused());
    }
    private void drivePlan(TaskId gate, Launch launch) throws Exception {
        TaskId ship = TaskId.of("t39-ship");
        TaskSnapshot snapshot = service.snapshot().get(30, TimeUnit.SECONDS);
        StartPlan plan = StartPlanner.plan(ship, snapshot, TaskGraph.of(snapshot, repository -> null),
                repository -> null, service.liveSessionIds());
        assertTrue("the startable single-segment plan", plan.startable() && plan.stages().size() == 1);
        Map<RepositoryId, Choice> segments = Map.of(repo, new Choice("Plan start", Optional.empty(),
                AgentKind.PI, SessionMode.WRITES, NewTaskPlan.WorkIn.MAIN_CHECKOUT, "main", "main",
                root.resolve("repo"), NewTaskPlan.OpenIn.THIS_WINDOW, Optional.empty(),
                Optional.empty(), Optional.empty()));
        service.persistConfirmedPlan(plan, new PlanLaunchChoices(ship, plan.stages().stream()
                .map(StartPlan.Stage::number).toList(), segments, Map.of()),
                new PlanLaunch.Origin("t39b-window", root)).get(30, TimeUnit.SECONDS);
        service.continuePlan(ship, launch, launch).get(60, TimeUnit.SECONDS);
    }
    private void boot() throws Exception {
        List<Repository> registered = List.of(new Repository(repo, root.resolve("repo"),
                "T39b Project", Instant.EPOCH, Instant.EPOCH, new RepositorySettings(Optional.empty())));
        service = new DelayedTaskService(root, threads, () -> sessions, () -> registered);
        await(() -> service.recovery() != null, "the service's first recovery pass");
    }
    private void publish() throws Exception {
        Method seam = DelayedTaskService.class.getDeclaredMethod("publishWatchedSnapshot",
                TaskStoreWatcher.WatchedSnapshot.class);
        seam.setAccessible(true);
        seam.invoke(service, new TaskStoreWatcher.WatchedSnapshot(TaskFiles.scan(root), List.of(), null));
    }
    /** The focused-frame seam: FocusManagerImpl's last-focused frame, stubbed to the test project. */
    private static void focusOn(FocusManagerImpl focus, Project project) {
        try {
            Field frame = FocusManagerImpl.class.getDeclaredField("myLastFocusedFrame"); frame.setAccessible(true);
            frame.set(focus, (IdeFrame) Proxy.newProxyInstance(IdeFrame.class.getClassLoader(),
                    new Class<?>[] {IdeFrame.class}, (proxy, method, args) ->
                            "getProject".equals(method.getName()) ? project
                                    : "hashCode".equals(method.getName()) ? System.identityHashCode(proxy) : null));
        } catch (ReflectiveOperationException e) { throw new AssertionError("no focus seam", e); }
    }
    private static Notifications recorder(List<Notification> into) {
        return new Notifications() { @Override public void notify(Notification n) { into.add(n); } };
    }
    private void dispatch(Notification balloon, String actionText) {
        AnAction action = balloon.getActions().stream()
                .filter(a -> actionText.equals(a.getTemplatePresentation().getText())).findFirst()
                .orElseThrow(() -> new AssertionError("no '" + actionText + "' action"));
        action.actionPerformed(AnActionEvent.createEvent(SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, getProject()).add(Notification.KEY, balloon).build(),
                new Presentation(), ActionPlaces.ACTION_SEARCH, ActionUiKind.NONE, null));
    }
    private Notification awaitBalloon(String titlePart, String what) {
        await(() -> !balloons(titlePart).isEmpty(), what);
        return balloons(titlePart).get(0);
    }
    private List<Notification> balloons(String titlePart) {
        return onProject.stream().filter(n -> n.getTitle().contains(titlePart)).toList();
    }
    private TaskStatus status(TaskId task) { // join: an unanswered store fails the test loudly
        return service.snapshot().join().byId().get(task).status();
    }
    private TaskId write(String slug, String title, List<String> after) throws IOException {
        Files.createDirectories(root.resolve(repo.toString()));
        String refs = after.isEmpty() ? "" : "'" + String.join("', '", after) + "'";
        Files.writeString(root.resolve(repo.toString()).resolve(slug + ".md"),
                "---\ntitle: \"" + title + "\"\nstatus: open\n"
                + "done_by: human\ncreated: " + Instant.now() + "\nafter: [" + refs + "]\n"
                + "sessions: []\n---\n\nFixture body.\n");
        return TaskId.of(slug);
    }
    private void await(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean()) {
            assertTrue("timed out waiting for " + what, System.nanoTime() < deadline);
            pump();
        }
    }
    private static void pump() {
        for (int i = 0; i < 3; i++) { PlatformTestUtil.dispatchAllEventsInIdeEventQueue(); TimeoutUtil.sleep(20); }
    }
    private static void delete(Path directory) {
        try (Stream<Path> entries = Files.walk(directory)) {
            entries.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { } });
        } catch (IOException ignored) { }
    }
}
