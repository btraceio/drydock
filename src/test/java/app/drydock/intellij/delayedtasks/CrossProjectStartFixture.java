package app.drydock.intellij.delayedtasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.delayedtasks.*;
import app.drydock.domain.*;
import app.drydock.intellij.tasks.NewTaskPlan;
import app.drydock.tasks.*;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.TestDialogManager;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.TimeoutUtil;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * The shared real-store fixture of the T38b matrix's single test file (a
 * supervisor-granted split, the T31b/T05c/T11b precedent), booted like the
 * T31b fixture because the committed production javadoc names the platform
 * fixture T38b owns for the visible refusals: a REAL DelayedTaskService,
 * TaskStore and PlanStore over a per-test temp-dir root (JUnit3 fixtures
 * take no @TempDir; Files.createTempDirectory, the SaveTaskLaterPlatformTest
 * precedent), zero mocking of production types -- the confirmer, launcher
 * and listener ride the cross-project flow's OWN injection seams, the drive
 * goes through the committed Continue wiring and PlanDriver, and every
 * refusal dialog is captured through TestDialogManager, never shown. The
 * driven glue is package-private in app.drydock.delayedtasks with no public
 * seam, so crossProject, the whole-plan claim, IN_FLIGHT and the watcher's
 * publication seam are reached reflectively (the T22b/T28b precedent) and
 * the two package-private lambda interfaces through java.lang.reflect.Proxy;
 * every claim a test parks is restored in the teardown. The DONE
 * observations that advance the stages are fed through the production
 * publishWatchedSnapshot seam the watcher itself calls. The seven matrix
 * items live wholly in CrossProjectStartTest, whose javadoc carries the
 * full mapping. Boundaries, honestly: the T37a dialog on screen, its
 * Configure interaction and the real TaskLauncher spawn stay with the
 * platform fixture and the T38a smoke.
 */
@SuppressWarnings("unchecked")
abstract class CrossProjectStartFixture extends BasePlatformTestCase
        implements PlanDriver.SegmentLauncher, PlanDriver.Listener {

    private static final Class<?> CONFIRMER = type("DelayedTaskStart$PlanConfirmer");
    private static final Class<?> FACTORY = type("DelayedTaskStart$LauncherFactory");

    Path root;
    final RepositoryId repoA = RepositoryId.newId(), repoB = RepositoryId.newId(),
            repoT = RepositoryId.newId();
    final Map<RepositoryId, Path> roots = new LinkedHashMap<>();
    final Map<RepositoryId, String> slugs = new LinkedHashMap<>();
    final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    final List<TaskId> parked = new ArrayList<>();
    final List<PlanDriver.SegmentRequest> launched = new CopyOnWriteArrayList<>();
    final List<String> violations = new CopyOnWriteArrayList<>();
    final List<String> shown = new CopyOnWriteArrayList<>();
    Map<TaskId, DoneBy> expectedDoneBy = Map.of();
    DelayedTaskService service;
    TaskId a; TaskId b; TaskId t; StartPlan plan;
    long launches;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        root = Files.createTempDirectory("drydock-t38b");
        TestDialogManager.setTestDialog(message -> { shown.add(message); return 0; },
                getTestRootDisposable());
        roots.put(repoA, root.resolve("alpha")); roots.put(repoB, root.resolve("bravo"));
        roots.put(repoT, root.resolve("target")); slugs.put(repoA, "alpha-proj");
        slugs.put(repoB, "bravo-proj"); slugs.put(repoT, "target-proj");
        a = write("cps-a", "Free project alpha", repoA, List.of());
        b = write("cps-b", "Free project bravo", repoB, List.of());
        t = write("cps-target", "Ship it everywhere", repoT, List.of(a.text(), b.text()));
        List<Repository> catalog = new ArrayList<>();
        for (RepositoryId id : List.of(repoA, repoB, repoT)) { catalog.add(new Repository(id,
                roots.get(id), slugs.get(id), Instant.EPOCH, Instant.EPOCH,
                new RepositorySettings(Optional.empty()))); }
        service = new DelayedTaskService(root, executor, List::of, () -> catalog);
        service.initialize().get(30, TimeUnit.SECONDS);
        TaskSnapshot snapshot = service.snapshot().get(30, TimeUnit.SECONDS);
        plan = StartPlanner.plan(t, snapshot, TaskGraph.of(snapshot, slugs::get), slugs::get,
                service.liveSessionIds());
        assertTrue("the fixture is a startable two-stage cross-project plan", plan.startable()
                && plan.segments().size() == 3 && plan.stages().size() == 2);
        assertEquals("stage 1 is both projects in parallel (D7)", Set.of(repoA, repoB),
                Set.copyOf(plan.stages().get(0).segments().stream()
                        .map(StartPlan.Segment::repository).toList()));
        assertTrue("the final stage is the target's own alone", plan.stages().get(1).segments()
                .size() == 1 && plan.stages().get(1).segments().get(0).repository().equals(repoT));
    }

    @Override
    protected void tearDown() throws Exception {
        inFlight().removeAll(parked); // every claim a test parked is restored here
        if (service != null) { service.close(); }
        delete(root);
        super.tearDown();
    }

    /** crossProject itself has no public seam; the two package-private lambda
      * interfaces are driven through Proxy (the T22b/T28b precedent). */
    Optional<String> cross(Supplier<Optional<PlanLaunchChoices>> confirm) throws Exception {
        Method flow = DelayedTaskStart.class.getDeclaredMethod("crossProject", Project.class,
                DelayedTaskService.class, Function.class, Function.class, StartPlan.class,
                CONFIRMER, FACTORY, PlanDriver.Listener.class);
        flow.setAccessible(true);
        Function<RepositoryId, String> names = slugs::get;
        Function<RepositoryId, Path> paths = roots::get;
        Supplier<Object> self = () -> this;
        return (Optional<String>) flow.invoke(null, new Object[] {getProject(), service, names,
                paths, plan, proxy(CONFIRMER, confirm), proxy(FACTORY, self), this});
    }

    private static Object proxy(Class<?> seam, Supplier<?> answer) {
        return Proxy.newProxyInstance(CrossProjectStartFixture.class.getClassLoader(),
                new Class<?>[] {seam}, (target, called, args) -> switch (called.getName()) {
                    case "confirm", "launcher" -> answer.get();
                    case "hashCode" -> System.identityHashCode(target);
                    case "equals" -> target == args[0];
                    case "toString" -> "seam";
                    default -> null; });
    }

    // the launch seam: every launch verifies its own confirmed persistence first
    @Override
    public CompletableFuture<Optional<PlanDriver.Prepared>> prepare(
            PlanDriver.SegmentRequest request) {
        String name = request.projectName();
        try {
            PlanStore.PlanRecord record = record();
            if (record == null) { violations.add("the segment of " + name + " launched before the"
                    + " confirmed plan persisted (P4.13)"); }
            if (record != null && record.plan().launch().launchIds().size() < segmentsThrough(
                    request.stage())) { violations.add("the segment of " + name + " launched"
                    + " before its persisted launch intent (P4.13)"); }
            if (!expectedDoneBy.isEmpty() && launched.isEmpty()) {
                TaskSnapshot files = TaskFiles.scan(root);
                expectedDoneBy.forEach((task, policy) -> { if (files.byId().get(task).doneBy()
                        != policy) { violations.add("the confirmed done_by of '" + task + "' was"
                        + " not persisted before the launch effects (D16)"); } });
            }
        } catch (IOException | RuntimeException failure) {
            violations.add("the segment of " + name + " could not verify its own persistence: "
                    + failure);
        }
        return CompletableFuture.completedFuture(Optional.of(new PlanDriver.Prepared() { }));
    }

    @Override
    public CompletableFuture<PlanDriver.Launched> launch(PlanDriver.Prepared token,
            PlanDriver.SegmentRequest request) {
        launched.add(request);
        long id = ++launches;
        return CompletableFuture.completedFuture(new PlanDriver.Launched("launch-" + id,
                "session-" + id));
    }

    @Override
    public void surfaced(PlanProgress.Effect effect) {
        if (effect instanceof PlanProgress.Refused refused) { violations.add("the plan refused"
                + " a step: " + refused.reason()); }
    }

    @Override
    public void halted(String reason, List<PlanDriver.Started> started) {
        violations.add("the plan halted: " + reason + " (started siblings keep running)"); }

    void assertNothingStarted() throws Exception {
        assertEquals("no segment ever launched", List.of(), launched);
        assertNull("no plan persisted", record());
        assertTrue("no partial reservations anywhere", noReservations());
        await(() -> Collections.disjoint(inFlight(), Set.of(a, b, t)), "the claim to release");
    }

    PlanStore.PlanRecord record() {
        try { return ((PlanStore) field(service.getClass(), "plans", service)).load(t)
                .get(10, TimeUnit.SECONDS).orElse(null); }
        catch (Exception e) { throw new AssertionError("the plan store did not answer", e); }
    }

    private int segmentsThrough(int stage) {
        int count = 0;
        for (StartPlan.Stage one : plan.stages()) {
            if (one.number() <= stage) { count += one.segments().size(); } }
        return count;
    }

    PlanLaunchChoices choices(Map<TaskId, DoneBy> doneBy) {
        Map<RepositoryId, NewTaskPlan.Choice> segments = new LinkedHashMap<>();
        for (RepositoryId repository : List.of(repoA, repoB, repoT)) { segments.put(repository,
                new NewTaskPlan.Choice("Plan start", Optional.empty(),
                repository == repoB ? AgentKind.CLAUDE : AgentKind.PI, SessionMode.WRITES,
                NewTaskPlan.WorkIn.MAIN_CHECKOUT, "main", "main", roots.get(repository),
                NewTaskPlan.OpenIn.THIS_WINDOW, Optional.empty(), Optional.empty(),
                Optional.empty())); }
        return new PlanLaunchChoices(t, List.of(1, 2), segments, doneBy);
    }

    TaskId write(String slug, String title, RepositoryId repository, List<String> after)
            throws IOException {
        TaskId id = TaskId.of(slug);
        Files.createDirectories(root.resolve(repository.toString()));
        String refs = after.isEmpty() ? "" : "'" + String.join("', '", after) + "'";
        Files.writeString(root.resolve(repository.toString()).resolve(id.text() + ".md"),
                "---\ntitle: \"" + title + "\"\nstatus: open\ndone_by: human\ncreated: "
                + Instant.now() + "\nafter: [" + refs + "]\nsessions: []\n---\n\nBody.\n");
        return id;
    }

    void finish(TaskId id, RepositoryId repository) throws IOException {
        Path file = root.resolve(repository.toString()).resolve(id.text() + ".md");
        Files.writeString(file, Files.readString(file).replace("status: open", "status: done")
                .replace("sessions: []", "sessions: [session-done]"));
    }

    /** The production publication seam the store watcher itself calls. */
    void publish() throws Exception {
        Method seam = DelayedTaskService.class.getDeclaredMethod("publishWatchedSnapshot",
                TaskStoreWatcher.WatchedSnapshot.class);
        seam.setAccessible(true);
        seam.invoke(service, new TaskStoreWatcher.WatchedSnapshot(TaskFiles.scan(root),
                List.of(), null));
    }

    List<TaskId> claimWholePlan() throws Exception {
        Method claim = DelayedTaskStart.class.getDeclaredMethod("claim", StartPlan.class,
                TaskId.class);
        claim.setAccessible(true);
        List<TaskId> held = (List<TaskId>) claim.invoke(null, plan, t);
        if (held == null) { throw new AssertionError("the plan was already claimed before the test"); }
        parked.addAll(held);
        return held;
    }

    static Set<TaskId> inFlight() {
        return (Set<TaskId>) field(DelayedTaskStart.class, "IN_FLIGHT", null);
    }

    static Object field(Class<?> owner, String name, Object target) {
        try { Field found = owner.getDeclaredField(name); found.setAccessible(true);
            return found.get(target); }
        catch (ReflectiveOperationException e) { throw new AssertionError("no " + name, e); }
    }

    boolean noReservations() throws Exception {
        try (TaskRuns runs = new TaskRuns(root)) {
            return runs.byTask(a).isEmpty() && runs.byTask(b).isEmpty() && runs.byTask(t).isEmpty();
        }
    }

    /** Spins (never sleeps bare) until the condition holds, pumping the EDT queue
      * between polls (the T31b fixture's discipline). */
    void await(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean()) {
            assertTrue("timed out waiting for " + what, System.nanoTime() < deadline);
            for (int i = 0; i < 3; i++) {
                PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
                TimeoutUtil.sleep(20);
            }
        }
    }

    static void delete(Path directory) {
        try (Stream<Path> entries = Files.walk(directory)) {
            entries.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private static Class<?> type(String nested) {
        try { return Class.forName("app.drydock.delayedtasks." + nested); }
        catch (ClassNotFoundException e) { throw new AssertionError("no seam " + nested, e); }
    }
}
