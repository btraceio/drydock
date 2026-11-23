package app.drydock.intellij.delayedtasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.delayedtasks.DelayedTaskService;
import app.drydock.delayedtasks.PlanDriver;
import app.drydock.delayedtasks.PlanRecovery;
import app.drydock.domain.*;
import app.drydock.tasks.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static app.drydock.delayedtasks.PlanDriver.*;
import static app.drydock.delayedtasks.PlanRecovery.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The T36b matrix, porting the never-committed T36a smoke over the accepted recovery: a REAL
 * task store, plan store and run-receipt registry over a real temp dir -- zero store mocking,
 * only the launcher is faked, that is the point. Every wait is a bounded future await; nothing
 * sleeps and nothing is shown. The REAL SPAWN and the production launcher composition (T38)
 * stay smoke-covered (the parked fake launch is the window-less seam); a foreign driver is
 * covered as the same-JVM second claim (P4.13 refuses a foreign process identically).
 */
class PlanRecoveryTest {

    private static final RepositoryId REPO = RepositoryId.of("11111111-1111-1111-1111-111111111111");
    private static final String LIVE = "33333333-3333-3333-3333-333333333333",
            DEAD = "44444444-4444-4444-4444-444444444444";
    private static final Path WORKTREE = Path.of("/tmp/worktrees/alpha");
    private static final PlanLaunch.Origin ORIGIN = new PlanLaunch.Origin("win-7",
            Path.of("/tmp/worktrees/plan"));
    private static final PlanLaunch.Selection SEL = new PlanLaunch.Selection(AgentKind.CLAUDE,
            REPO, "alpha-proj");
    private static final Repository ALPHA = new Repository(REPO, WORKTREE, "alpha", Instant.now(),
            Instant.now(), RepositorySettings.DEFAULT);
    private static final ManagedAgentSession LIVE_ONE = new ManagedAgentSession(
            ManagedSessionId.of(LIVE), REPO, "the live session", AgentBinding.unlaunched(
            AgentKind.CLAUDE), new SessionWorkspace(WORKTREE, Optional.empty(), false),
            SessionStatus.INACTIVE, Instant.now(), Instant.now(), Optional.empty(), PrLink.none(),
            false, Optional.empty());

    @TempDir Path store;
    @TempDir Path side;
    private final FakeLauncher launcher = new FakeLauncher();
    private final List<ManagedAgentSession> live = new CopyOnWriteArrayList<>();
    private DelayedTaskService service;

    @AfterEach void stopEverything() { if (service != null) service.close(); }

    /** The fake seam AND its own token: the preparation succeeds, the launch parks forever
      * (the real spawn is smoke-covered); the counts are the zero-startup-launch proof. */
    private static final class FakeLauncher implements SegmentLauncher, Listener, Prepared {
        int prepared; int launches;
        @Override public CompletableFuture<Optional<Prepared>> prepare(SegmentRequest request) {
            prepared++; return CompletableFuture.completedFuture(Optional.of(this));
        }
        @Override public CompletableFuture<Launched> launch(Prepared made, SegmentRequest request) {
            launches++; return new CompletableFuture<>(); // parked: the settle is never ours
        }
        @Override public void surfaced(PlanProgress.Effect effect) { }
        @Override public void halted(String reason, List<Started> started) { }
    }

    /** One catalog task document (marked DONE when asked), through the real single writer. */
    private TaskId task(String title, boolean done) throws Exception {
        try (TaskStore tasks = new TaskStore(store)) {
            TaskDocument created = tasks.create(REPO, title, List.of());
            if (done) tasks.update(created.id(), new TaskFilePatch.Updates(null, TaskStatus.DONE,
                    null, null, null, null));
            return created.id();
        }
    }

    /** Persists one RUNNING, unpaused single-stage plan over the target (the crash's durable
      * bytes), then releases the seeding claim: nothing drives it. */
    private void seedPlan(TaskId target, boolean stageLaunched) throws Exception {
        RunningPlan plan = new RunningPlan(new PlanLaunch(target, List.of(1), Map.of(target, SEL),
                7L, List.of(), 0, ORIGIN), List.of(new RunningPlan.StageState(1, List.of(target),
                stageLaunched, false)), RunningPlan.Status.RUNNING);
        try (PlanStore plans = new PlanStore(store)) {
            PlanStore.Claim claim = await(plans.acquireClaim(target));
            await(plans.save(claim, 0, plan, false)); claim.close();
        }
    }

    /** The receipt seeding: PREPARE only, +LAUNCH, or the FORGET that releases a reservation;
      * the owner catalog is an existing path (T27: history rows stay writable). */
    private void receipt(TaskId task, String session, boolean launched, boolean forgotten)
            throws Exception {
        try (TaskRuns runs = new TaskRuns(store)) {
            if (forgotten) runs.forget(task);
            else { runs.reserve(task, session, WORKTREE, Files.createTempFile(side, "owner",
                    ".json"), List.of()); if (launched) runs.launch(task, session); }
        }
    }

    /** The live-catalog session ids of this simulated restart (T07 caller-supplied liveness). */
    private Set<String> liveIds() {
        List<String> ids = new ArrayList<>(); live.forEach(o -> ids.add(o.id().toString()));
        return Set.copyOf(ids); }

    /** The persisted record, re-read through a fresh store. */
    private PlanStore.PlanRecord persisted(TaskId plan) throws Exception {
        try (PlanStore plans = new PlanStore(store)) {
            return await(plans.load(plan)).orElseThrow(() -> new AssertionError("no record"));
        }
    }

    /** The read-only restart scan over the real root, catalog and liveness. */
    private Report scan() throws Exception { return PlanRecovery.scan(store,
            TaskFiles.scan(store), liveIds()); }

    /** A second driver's Continue, over its own store instance of the same root (P4.13). */
    private Outcome foreignContinue(TaskId plan) throws Exception {
        try (PlanStore plans = new PlanStore(store)) {
            return PlanRecovery.continuePlan(store, plans, plan, TaskFiles.scan(store), liveIds(),
                    Set.of(REPO));
        }
    }

    /** The simulated restart: the production service over the crashed root, every operation on
      * the calling thread (no sleeps). */
    private DelayedTaskService restart() throws Exception {
        service = new DelayedTaskService(store, Runnable::run, () -> List.copyOf(live),
                () -> List.of(ALPHA)); await(service.initialize()); return service;
    }

    /** The service's explicit Continue through the fake seam (the only driving path). */
    private Outcome continuePlan(TaskId plan) throws Exception {
        return await(service.continuePlan(plan, launcher, launcher));
    }

    /** One bounded wait; a store or driver answer always arrives or fails visibly. */
    private static <T> T await(CompletableFuture<T> future) {
        try { return future.get(25, TimeUnit.SECONDS); }
        catch (Exception e) { throw new AssertionError("No answer within the bound: " + e, e); }
    }

    /** The visible failure message of a refused future; null when it completed normally. */
    private static String failureOf(CompletableFuture<?> future) {
        try { future.get(25, TimeUnit.SECONDS); return null; }
        catch (Exception e) { return String.valueOf((e instanceof ExecutionException x
                && x.getCause() != null ? x.getCause() : e).getMessage()); }
    }

    /** An executor that parks every command until released, so the initialization runs late. */
    private static final class ParkingExecutor implements Executor {
        private final List<Runnable> parked = new ArrayList<>();
        private volatile boolean released;
        @Override public void execute(Runnable command) {
            if (released) command.run(); else parked.add(command);
        }
        void release() { released = true; parked.forEach(Runnable::run); }
    }

    /** Matrix 1 -- crash before the intent: nothing is persisted, so nothing is recovered,
      * nothing launches, and an unknown plan is a visible refusal, never a guessed drive. */
    @Test
    void crashBeforeIntentLeavesNothingToRecoverAndLaunchesNothing() throws Exception {
        assertTrue(scan().plans().isEmpty() && scan().unreadable().isEmpty(),
                "no recovery input"); restart();
        assertTrue(service.recovery() != null && service.recovery().plans().isEmpty(),
                "init publishes the (empty) recovery: nothing to require");
        assertEquals(0, launcher.launches, "no launch path fired");
        Outcome unknown = foreignContinue(TaskId.of("never-persisted"));
        assertTrue(unknown instanceof Refused refused && refused.reason().contains(
                "has no persisted plan; a confirmed start persists it first (T38)"), "the unknown"
                + " plan is refused visibly (PlanRecovery.java:142): " + unknown);
    }

    /** Matrix 2 -- crash after the intent: the plan recovers to the documented state -- a
      * published Continue requirement (PlanRecovery.java:113-114) -- and ONLY the explicit
      * Continue drives it. */
    @Test
    void crashAfterIntentRecoversToTheDocumentedStateAndRequiresContinue() throws Exception {
        TaskId plan = task("crash after intent", false); seedPlan(plan, false);
        Recovered recovered = scan().plans().get(0); assertEquals(Verdict.CONTINUE, recovered.verdict());
        assertFalse(recovered.paused(), "the crash left the durable flag exactly as it was");
        assertEquals("the restart paused the plan at stage 1; an explicit Continue resumes it"
                + " -- nothing launched (P4.10)", recovered.requirement());
        restart(); assertEquals(0, launcher.launches, "the scan and the init launched nothing");
        assertTrue(continuePlan(plan) instanceof Proceed, "the explicit Continue drives");
        assertEquals(1, launcher.prepared, "prepared"); assertEquals(1, launcher.launches, "launched once");
    }

    /** Matrix 3 -- a crash between the spawn and the receipt save: the PREPARE receipt of a
      * LIVE session is the durable proof (launch-id reconciliation, PlanRecovery.java:196);
      * the Continue reuses the session and marks the stage launched, never re-spawning. */
    @Test
    void aCrashBetweenSpawnAndReceiptSaveReconcilesByReceipts() throws Exception {
        TaskId plan = task("crash between spawn and receipt", false);
        seedPlan(plan, false); receipt(plan, LIVE, false, false); live.add(LIVE_ONE);
        Recovered recovered = scan().plans().get(0);
        assertEquals(Verdict.CONTINUE, recovered.verdict());
        assertTrue(recovered.requirement().contains("the restart interrupted the launch of stage 1")
                && recovered.requirement().contains("never re-spawning (P9)"), "the covered"
                + " stage's published requirement (PlanRecovery.java:118-120)");
        restart(); Outcome outcome = continuePlan(plan); assertTrue(outcome instanceof Proceed
                proceed && proceed.reusedSessions().equals(List.of(LIVE)), "reconciled, the live"
                + " session reused");
        assertTrue(persisted(plan).plan().stages().get(0).launched(), "durably marked launched");
        assertEquals(0, launcher.launches, "the known session is REUSED, never re-spawned");
    }

    /** Matrix 4 -- a stage that finished while the IDE was closed: the LAUNCH receipt of a
      * dead session proves the spawn (PlanRecovery.java:198) and the DONE the work (line 200). */
    @Test
    void aStageFinishedWhileClosedIsRecognizedNotRelaunched() throws Exception {
        TaskId plan = task("finished while closed", true); seedPlan(plan, true);
        receipt(plan, DEAD, true, false);
        assertEquals(Verdict.CONTINUE, scan().plans().get(0).verdict(),
                "a published Continue, not a relaunch");
        restart(); Outcome outcome = continuePlan(plan); assertTrue(outcome instanceof Proceed,
                "recognized, not halted");
        assertEquals(0, launcher.launches, "the finished stage never launches again");
        RunningPlan after = persisted(plan).plan();
        assertTrue(after.stages().get(0).finished() && after.status() == RunningPlan.Status.COMPLETED,
                "the observed DONE completes the plan without a launch");
    }

    /** Matrix 5 -- zero startup launches: the init recovery PUBLISHES the requirement and
      * nothing else (P4.10) -- the fake launcher's counts stay at zero. */
    @Test
    void initRecoveryPublishesRequirementsOnlyAndNeverLaunches() throws Exception {
        TaskId plan = task("startup publication", false); seedPlan(plan, false);
        Report published = restart().recovery(); assertNotNull(published, "published");
        assertEquals(1, published.plans().size(), "the one requirement is published");
        assertEquals(0, launcher.prepared, "no preparation at startup");
        assertEquals(0, launcher.launches, "no launch path fires during recovery (P4.10)");
    }

    /** Matrix 6 -- the known existing session is reused, and a second driver is refused
      * visibly (P4.13, the one-driver claim) until the owner stops driving. */
    @Test
    void theExistingSessionIsReusedAndTheForeignDriverRefused() throws Exception {
        TaskId plan = task("existing session, foreign driver", false);
        seedPlan(plan, false); receipt(plan, LIVE, false, false); live.add(LIVE_ONE);
        DelayedTaskService owner = restart(); PlanRecovery.Outcome continued = continuePlan(plan);
        assertTrue(continued instanceof Proceed proceed && proceed.reusedSessions().equals(
                List.of(LIVE)), "the live session is reused: " + continued);
        assertEquals(0, launcher.launches, "reused, not re-spawned");
        Outcome foreign = foreignContinue(plan);
        assertTrue(foreign instanceof Refused refused && refused.reason().contains(
                "already driven by") && refused.reason().contains("one driver per plan (P4.13)"),
                "the second driver is refused visibly: " + foreign);
        owner.close(); Outcome takeover = foreignContinue(plan);
        assertTrue(takeover instanceof Proceed, "once the owner stops, a fresh driver"
                + " continues: " + takeover);
    }

    /** Matrix 7 -- the close/init race: an initialization that completes after the close is
      * discarded whole (publishOrClose/publishRecovery) -- no partial recovery, no watcher,
      * the crashed bytes untouched, the root cleanly recoverable for the next start. */
    @Test
    void theCloseInitRaceResolvesWithoutPartialState() throws Exception {
        TaskId plan = task("close during init", false); seedPlan(plan, false);
        Path file = store.resolve("plans").resolve(plan.text() + ".json");
        byte[] crashed = Files.readAllBytes(file); ParkingExecutor parking = new ParkingExecutor();
        DelayedTaskService racing = new DelayedTaskService(store, parking, List::of, List::of);
        racing.close(); // the close wins while the initialization is still parked
        parking.release(); // the late initialization completes after the close
        assertNull(racing.recovery(), "no partial recovery"); assertNull(
                racing.watchedSnapshot(), "no watcher");
        assertTrue(failureOf(racing.snapshot()).contains("is disposed"), "refuses visibly");
        assertArrayEquals(crashed, Files.readAllBytes(file), "the persisted plan is untouched");
        assertEquals(0, launcher.launches, "nothing launched");
        assertEquals(Verdict.CONTINUE, scan().plans().get(0).verdict(), "the same root"
                + " recovers cleanly for the next start");
    }

    /** Matrix 8 -- the ambiguous intent: a live PREPARE receipt of a gone session, with the
      * work not done, stays HALTED with its verbatim remediation (PlanRecovery.java:201-205)
      * and is never auto-retried; the receipt's release remedies it. */
    @Test
    void ambiguousIntentStaysHaltedWithItsVerbatimRemediation() throws Exception {
        TaskId plan = task("ambiguous intent", false);
        seedPlan(plan, false); receipt(plan, DEAD, false, false);
        String remediation = "task '" + plan.text() + "' holds a live PREPARE receipt by session '"
                + DEAD + "', which is no longer live, and its work is not done; whether that"
                + " spawn happened cannot be told from the durable record. Remediation: release"
                + " the reservation (the session's Done or a receipt forget), then continue; never"
                + " retried automatically (P4/P4.10)";
        Recovered recovered = scan().plans().get(0);
        assertEquals(Verdict.HALTED, recovered.verdict()); assertEquals(remediation,
                recovered.requirement());
        restart(); Outcome first = continuePlan(plan); assertTrue(first instanceof Halted halted
                && remediation.equals(halted.reason()), "the verbatim remediation: " + first);
        assertTrue(continuePlan(plan) instanceof Halted, "never auto-retried");
        assertEquals(0, launcher.launches, "nothing launched"); receipt(plan, DEAD, false, true); // release
        assertTrue(continuePlan(plan) instanceof Proceed, "after the release it proceeds");
        assertEquals(1, launcher.launches, "the released stage launches fresh, once");
    }
}
