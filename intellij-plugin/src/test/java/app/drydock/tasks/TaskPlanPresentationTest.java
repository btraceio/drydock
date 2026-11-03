package app.drydock.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.delayedtasks.DelayedTaskService;
import app.drydock.domain.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static app.drydock.tasks.DelayedTaskRows.PlanPhase.*;
import static app.drydock.tasks.TaskStatus.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The T40b committed matrix for the T40 plan layer, over REAL stores under
 * TempDir roots with no mocking: the target's stage n/m annotation (its
 * placement under a session row included, P4.12), the review wait, the halt
 * reason, the Cancel effect mapping before/within/between stages, the canceled
 * snapshot's Cancel offer, and the no-plan-file-I/O derivation proven with a
 * mode-000 plans dir. Every annotation is the renderer's exact composition --
 * published plan views, a task snapshot, the live-session view -- so a render
 * path composing them cannot read a plan file (P4.12).
 */
class TaskPlanPresentationTest {

    private static final RepositoryId REPO = RepositoryId.of("11111111-1111-1111-1111-111111111111");
    private static final PlanLaunch.Origin ORIGIN = new PlanLaunch.Origin("win-1", Path.of("/tmp/wt/alpha"));
    private static final String SESSION = "22222222-2222-2222-2222-222222222222";
    private static final String GONE = "33333333-3333-3333-3333-333333333333";

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    // 1. Progress and target placement (P4.12): the annotation lands on the plan's
    // TARGET only, and survives the target moving under its session row.
    @Test
    void annotationLandsOnTheTargetAndSurvivesItsSessionRow(@TempDir Path dir) throws Exception {
        try (Scene scene = scene(dir.resolve("tasks"), new Shape(null, DONE), new Shape(null, DONE),
                new Shape(SESSION, STARTED), true, true, true)) {
            assertEquals("stage 2/2 running", scene.annotation(scene.target()).orElseThrow().text());
            assertTrue(scene.annotation(scene.a()).isEmpty() && scene.annotation(scene.b()).isEmpty());
            TaskNode.DelayedTaskRow row = rowOf(scene, scene.target());
            assertEquals(Optional.of(SESSION), DelayedTaskRows.attachedSession(row, Set.of(SESSION)),
                    "the started target moves under its session row when that session shows one (D10)");
            assertTrue(DelayedTaskRows.attachedSession(row, Set.of()).isEmpty(),
                    "the same target sits in the section when no session shows it");
            assertEquals(scene.annotation(scene.target()), scene.annotation(row.id()),
                    "the annotation keys on the task id: the move under the session keeps it (P4.12)");
        }
    }

    // 2. Review wait (D16): the review is all that holds the launched stage.
    @Test
    void reviewWaitSurfacesWhileTheReviewGateHolds(@TempDir Path dir) throws Exception {
        try (Scene scene = scene(dir.resolve("tasks"), new Shape(null, REVIEW), new Shape(null, DONE),
                new Shape(null, OPEN), true, false, false)) {
            assertEquals("stage 1/2 waiting", scene.annotation(scene.target()).orElseThrow().text());
        }
    }

    // 3. Halt reason (P4.8/D16): both halt derivations carry the halted word, each
    // from its own reason input -- a lost session, and a task reopened mid-plan.
    @Test
    void haltCarriesTheReasonItWasDerivedFrom(@TempDir Path dir) throws Exception {
        assertHalted(dir.resolve("interrupted"), new Shape(GONE, STARTED),
                "a current-stage task with no live session halts (the interrupted reason)");
        assertHalted(dir.resolve("reopened"), new Shape(null, DONE, OPEN),
                "a launched stage's task reading OPEN halts (the reopened reason)");
    }

    // 4. Cancel before/within/between stages (P4.12): the effect mapping per phase
    // -- reservations released, launched sessions untouched, and the next stage never starts.
    @Test
    void cancelBeforeWithinAndBetweenStagesMapsTheEffects(@TempDir Path dir) throws Exception {
        Cancel before = cancelOf(dir.resolve("before"), false, false);
        before.assertCanceledAndBumped();
        assertEquals(List.of(before.a(), before.b(), before.target()), before.released(),
                "before the stages: every unlaunched reservation is released");
        assertFalse(before.aLive() || before.bLive() || before.targetLive(), "no receipt stays live");
        Cancel within = cancelOf(dir.resolve("within"), true, false);
        within.assertCanceledAndBumped();
        assertEquals(List.of(within.target()), within.released(),
                "within a stage: only the unlaunched stage's reservation is released");
        assertTrue(within.aLive() && within.bLive(),
                "the launched stage's sessions keep running, never killed (P4.12)");
        Cancel between = cancelOf(dir.resolve("between"), true, true);
        between.assertCanceledAndBumped();
        assertEquals(List.of(between.target()), between.released(), "between stages: the future releases");
        assertTrue(between.aLive() && between.bLive(), "the finished stage's receipts stay as history");
        assertEquals(List.of(new PlanProgress.Refused("the plan is canceled; it never auto-resumes,"
                        + " an explicit Continue event is required")),
                PlanProgress.apply(PlanProgress.State.of(between.persisted()),
                        new PlanProgress.PlanStarted(1L)).effects(),
                "the next stage never starts: a canceled plan refuses a start");
    }

    // 5. The canceled snapshot no longer offers Cancel (the tree's offer rule).
    @Test
    void canceledSnapshotNoLongerOffersCancel(@TempDir Path dir) throws Exception {
        try (Scene scene = scene(dir.resolve("tasks"), new Shape(null), new Shape(null, OPEN),
                new Shape(null, OPEN), false, false, false)) {
            assertTrue(cancelOffered(scene, scene.target()), "a running plan with unfinished work offers it");
            await(scene.service().cancelPlan(scene.target()));
            assertFalse(cancelOffered(scene, scene.target()),
                    "the canceled snapshot no longer offers Cancel, through the CURRENT published record");
        }
    }

    // 6. Render from snapshots (P4.12): reads no plan file -- proven by a mode-000 plans dir.
    @Test
    void annotationDerivesWithNoPlanFileIo(@TempDir Path dir) throws Exception {
        Path root = dir.resolve("tasks");
        try (Scene scene = scene(root, new Shape(SESSION, STARTED), new Shape(SESSION, STARTED),
                new Shape(null, OPEN), true, false, false)) {
            assertEquals("stage 1/2 running", scene.annotation(scene.target()).orElseThrow().text());
            Path plansDir = root.resolve("plans");
            Files.setPosixFilePermissions(plansDir, PosixFilePermissions.fromString("---------"));
            try {
                boolean refused = false; // the lock has teeth: a direct plan-file read fails
                try { Files.readString(plansDir.resolve(scene.target().text() + ".json")); }
                catch (Exception e) { refused = true; }
                assertTrue(refused, "mode 000 refuses a plan-file read (the proof has teeth)");
                TaskSnapshot fresh = await(scene.service().snapshot()); // task files only, never plans
                Optional<DelayedTaskRows.PlanProgress> derived = DelayedTaskRows
                        .planOf(scene.service().publishedPlans(), scene.target())
                        .flatMap(record -> DelayedTaskRows.planProgress(record, fresh,
                                scene.service().liveSessionIds()));
                assertEquals("stage 1/2 running", derived.orElseThrow().text(),
                        "the derivation reads no plan file: it survives the unreadable plans dir");
            } finally {
                Files.setPosixFilePermissions(plansDir, PosixFilePermissions.fromString("rwxr-xr-x"));
            }
        }
    }

    /** Whether the CURRENT published plan of the task still offers Cancel. */
    private static boolean cancelOffered(Scene scene, TaskId task) {
        return DelayedTaskRows.planOf(scene.service().publishedPlans(), task)
                .map(record -> DelayedTaskRows.cancelOffered(record.plan())).orElse(false);
    }

    private static TaskNode.DelayedTaskRow rowOf(Scene scene, TaskId task) {
        return DelayedTaskRows.rows(scene.snapshot(), scene.service().liveSessionIds(),
                repository -> "project-alpha", Instant.now()).stream()
                .filter(row -> row.id().equals(task)).findFirst().orElseThrow();
    }

    /** One task's stored shape: its optional session and its status history. */
    private record Shape(String session, TaskStatus... history) { }

    /** One open scenario: a real store, a persisted diamond plan, a live service. */
    private Scene scene(Path root, Shape a, Shape b, Shape target, boolean stageOneLaunched,
            boolean stageOneFinished, boolean stageTwoLaunched) throws Exception {
        RepositoryId repository = RepositoryId.newId();
        TaskId first = create(root, repository, "Stage one first", a);
        TaskId second = create(root, repository, "Stage one second", b);
        TaskId goal = create(root, repository, "Plan target", target);
        try (PlanStore plans = new PlanStore(root); // persisted at revision 1, unpaused (T33a)
                PlanStore.Claim claim = await(plans.acquireClaim(goal))) {
            await(plans.save(claim, 0, diamond(first, second, goal, stageOneLaunched, stageOneFinished,
                    stageTwoLaunched), false));
        }
        List<ManagedAgentSession> catalog = List.of(session(ManagedSessionId.of(SESSION), repository, root));
        DelayedTaskService service = new DelayedTaskService(root, executor, () -> catalog, List::of);
        await(service.initialize());
        awaitPublication(service, goal); // the recovery pass publishes late, off the init future
        return new Scene(service, await(service.snapshot()), first, second, goal);
    }

    /** Item 3's shape: one halted diamond, asserted and closed. */
    private void assertHalted(Path root, Shape a, String reason) throws Exception {
        try (Scene scene = scene(root, a, new Shape(null, DONE), new Shape(null, OPEN), true, false,
                false)) {
            DelayedTaskRows.PlanProgress halted = scene.annotation(scene.target()).orElseThrow();
            assertEquals(new DelayedTaskRows.PlanProgress(1, 2, HALTED), halted, reason);
            assertEquals("stage 1/2 halted", halted.text());
        }
    }

    /** One canceled diamond's observable effects: the releases, the receipts, the plan. */
    private record Cancel(boolean canceled, TaskId a, TaskId b, TaskId target, List<TaskId> released,
            boolean aLive, boolean bLive, boolean targetLive, RunningPlan persisted) {

        /** The committed cancel: durably canceled, the generation bumped once. */
        void assertCanceledAndBumped() {
            assertTrue(canceled(), "the cancel commits");
            assertEquals(RunningPlan.Status.CANCELED, persisted().status());
            assertEquals(1, persisted().cancellationGeneration(),
                    "bumped once, so in-flight launch intents are stale (future effects off)");
        }
    }

    /** Reserves the diamond for real, cancels it through the service seam, gathers. */
    private Cancel cancelOf(Path root, boolean stageOneLaunched, boolean stageOneFinished) throws Exception {
        try (Scene scene = scene(root, new Shape(null), new Shape(null), new Shape(null),
                stageOneLaunched, stageOneFinished, false)) {
            TaskId a = scene.a();
            TaskId b = scene.b();
            TaskId target = scene.target();
            try (TaskRuns runs = new TaskRuns(root)) {
                for (TaskId task : List.of(a, b, target)) { runs.reserve(task, SESSION, root, root, List.of()); }
                if (stageOneLaunched) { runs.launch(a, SESSION); runs.launch(b, SESSION); }
            }
            DelayedTaskService.PlanCancel outcome = await(scene.service().cancelPlan(target));
            RunningPlan persisted = DelayedTaskRows.planOf(scene.service().publishedPlans(), target)
                    .map(PlanStore.PlanRecord::plan).orElseThrow();
            try (TaskRuns runs = new TaskRuns(root)) {
                return new Cancel(outcome.canceled(), a, b, target, outcome.released(),
                        live(runs, a), live(runs, b), live(runs, target), persisted);
            }
        }
    }

    private static boolean live(TaskRuns runs, TaskId task) throws Exception {
        return runs.byTask(task).map(TaskRun::live).orElse(false);
    }

    /** Spins until the service's late recovery pass publishes the plan's view. */
    private static void awaitPublication(DelayedTaskService service, TaskId task) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (DelayedTaskRows.planOf(service.publishedPlans(), task).isEmpty()) {
            if (System.nanoTime() - deadline > 0) {
                throw new IllegalStateException("the plan of '" + task.text() + "' never published");
            }
            Thread.sleep(5);
        }
    }

    /** One open scenario's held pieces: the service, the snapshot, the ids. */
    private record Scene(DelayedTaskService service, TaskSnapshot snapshot, TaskId a, TaskId b,
            TaskId target) implements AutoCloseable {

        /** The renderer's exact composition: published views, snapshot, live view. */
        Optional<DelayedTaskRows.PlanProgress> annotation(TaskId task) {
            return DelayedTaskRows.planOf(service.publishedPlans(), task).flatMap(record ->
                    DelayedTaskRows.planProgress(record, snapshot, service.liveSessionIds()));
        }

        @Override
        public void close() {
            service.close();
        }
    }

    /** The two-stage diamond: stage one holds a and b, stage two the target. */
    private static RunningPlan diamond(TaskId a, TaskId b, TaskId target, boolean stageOneLaunched,
            boolean stageOneFinished, boolean stageTwoLaunched) {
        Map<TaskId, PlanLaunch.Selection> selections = new LinkedHashMap<>();
        for (TaskId task : List.of(a, b, target)) {
            selections.put(task, new PlanLaunch.Selection(AgentKind.CLAUDE, REPO, "drydock-plugin"));
        }
        PlanLaunch launch = new PlanLaunch(target, List.of(1, 2), selections, 1L, List.of(), 0, ORIGIN);
        return new RunningPlan(launch, List.of(
                new RunningPlan.StageState(1, List.of(a, b), stageOneLaunched, stageOneFinished),
                new RunningPlan.StageState(2, List.of(target), stageTwoLaunched, false)),
                RunningPlan.Status.RUNNING);
    }

    /** One task stored per its shape: the root is laid out first (D2's layout). */
    private static TaskId create(Path root, RepositoryId repository, String title, Shape shape)
            throws Exception {
        Files.createDirectories(root);
        try (TaskStore store = new TaskStore(root)) {
            TaskId id = store.create(repository, title,
                    shape.session() == null ? List.of() : List.of(shape.session())).id();
            for (TaskStatus status : shape.history()) {
                store.update(id, new TaskFilePatch.Updates(null, status, null, null, null, null));
            }
            return id;
        }
    }

    private static ManagedAgentSession session(ManagedSessionId id, RepositoryId repository, Path root) {
        return new ManagedAgentSession(id, repository, "session " + id,
                AgentBinding.unlaunched(AgentKind.CLAUDE),
                new SessionWorkspace(root, Optional.empty(), false), SessionStatus.INACTIVE,
                Instant.now(), Instant.now(), Optional.empty(), PrLink.none(), false, Optional.empty());
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(20, TimeUnit.SECONDS);
    }
}
