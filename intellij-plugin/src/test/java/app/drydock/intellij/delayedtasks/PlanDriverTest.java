package app.drydock.intellij.delayedtasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.RepositoryId;
import app.drydock.delayedtasks.PlanDriver;
import app.drydock.tasks.PlanLaunch;
import app.drydock.tasks.PlanStore;
import app.drydock.tasks.RunningPlan;
import app.drydock.tasks.TaskId;
import app.drydock.tasks.TaskStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static app.drydock.delayedtasks.PlanDriver.*;
import static app.drydock.tasks.PlanProgress.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The T35b matrix, porting the T35a smoke over the accepted {@link PlanDriver}: a REAL
 * {@link PlanStore} over a real temp dir -- only the launcher is faked, that is the point.
 * The lane runs on a same-thread executor, so every settle is driven by the test completing a
 * parked launch future: pump-park waits only, no sleeps, nothing shown. Boundaries, honestly:
 * the real spawn, the real window selection and the production SegmentLauncher wiring (T38)
 * stay smoke-covered -- here the preparation's empty
 * answer is the window-less seam, and the duplicate-flight guard (PlanDriver.java:222-227)
 * is defense-in-depth behind the reducer's launch-attempt set that a duplicate exercises.
 */
class PlanDriverTest {

    private static final RepositoryId REPO_A = RepositoryId.of("11111111-1111-1111-1111-111111111111");
    private static final RepositoryId REPO_B = RepositoryId.of("22222222-2222-2222-2222-222222222222");
    private static final PlanLaunch.Origin ORIGIN = new PlanLaunch.Origin("win-7",
            Path.of("/tmp/worktrees/plan"));
    private static final long REVISION = 7L;
    private static final PlanLaunch.Selection SEL_A = new PlanLaunch.Selection(AgentKind.CLAUDE, REPO_A, "alpha-proj");
    private static final PlanLaunch.Selection SEL_B = new PlanLaunch.Selection(AgentKind.CLAUDE, REPO_B, "bravo-proj");

    @TempDir Path root; private final List<Harness> harnesses = new ArrayList<>();

    @AfterEach
    void stopEverything() { for (Harness h : harnesses) { h.driver.close(); h.store.close(); } }

    /** One parked preparation: its launch future is completed by the test, nothing else. */
    private record Preparation(CompletableFuture<Launched> flight) implements Prepared { }

    /** One reported halt: why the plan stopped and which siblings keep running. */
    private record Halt(String reason, List<Started> started) { }

    /** The harness: the store is REAL, the launcher and the recorded surface are the fakes. */
    private final class Harness implements SegmentLauncher, Listener {

        final PlanStore store = new PlanStore(root);
        final PlanDriver driver = new PlanDriver(Runnable::run, store, this, this);
        volatile boolean refusesPreparation;
        final List<SegmentRequest> prepared = new CopyOnWriteArrayList<>();
        final List<SegmentRequest> launched = new CopyOnWriteArrayList<>();
        private final List<Preparation> parked = new CopyOnWriteArrayList<>();
        final List<Effect> surfaces = new CopyOnWriteArrayList<>();
        final List<Halt> halts = new CopyOnWriteArrayList<>();
        Harness() { harnesses.add(this); }
        @Override public CompletableFuture<Optional<Prepared>> prepare(SegmentRequest request) {
            prepared.add(request);
            if (refusesPreparation) return CompletableFuture.completedFuture(Optional.empty());
            Preparation token = new Preparation(new CompletableFuture<>()); parked.add(token);
            return CompletableFuture.completedFuture(Optional.of(token));
        }

        @Override public CompletableFuture<Launched> launch(Prepared made, SegmentRequest request) {
            launched.add(request); return ((Preparation) made).flight();
        }

        @Override public void surfaced(Effect effect) { surfaces.add(effect); }
        @Override public void halted(String reason, List<Started> started) { halts.add(new Halt(reason, started)); }

        int launchesOf(int stage) { return (int) launched.stream().filter(r -> r.stage() == stage).count(); }

        List<Preparation> pending() { return parked.stream().filter(t -> !t.flight().isDone()).toList(); }
    }

    /** One RUNNING plan of the given stages: tasks alternate between the two repository
      * shares (one segment per share, D7); the target is the last stage's first task (D12). */
    @SafeVarargs
    private static RunningPlan plan(List<TaskId>... stages) {
        Map<TaskId, PlanLaunch.Selection> selections = new LinkedHashMap<>();
        List<RunningPlan.StageState> state = new ArrayList<>();
        List<Integer> confirmed = new ArrayList<>();
        int share = 0;
        for (int number = 1; number <= stages.length; number++) {
            confirmed.add(number);
            state.add(new RunningPlan.StageState(number, stages[number - 1], false, false));
            for (TaskId task : stages[number - 1]) { selections.put(task,
                    share++ % 2 == 0 ? SEL_A : SEL_B); }
        }
        TaskId target = stages[stages.length - 1].get(0);
        return new RunningPlan(new PlanLaunch(target, confirmed, selections, REVISION, List.of(),
                0, ORIGIN), state, RunningPlan.Status.RUNNING);
    }

    /** Persists the plan (paused as asked) and releases the seeding claim; the driver re-takes it. */
    private static void seed(PlanStore store, TaskId id, RunningPlan plan, boolean paused) {
        PlanStore.Claim claim = await(store.acquireClaim(id));
        await(store.save(claim, 0, plan, paused));
        claim.close();
    }
    /** One bounded wait on a store or driver future (PlanStoreTest's own discipline). */
    private static <T> T await(CompletableFuture<T> future) {
        try { return future.get(25, TimeUnit.SECONDS); }
        catch (Exception e) { throw new AssertionError("No answer within the bound: " + e, e); }
    }
    /** The visible refusal a failed driver step answers with; null when the step succeeded. */
    private static String refusalOf(CompletableFuture<?> step) {
        try { step.get(25, TimeUnit.SECONDS); return null; }
        catch (ExecutionException e) { return String.valueOf(e.getCause().getMessage()); }
        catch (Exception e) { return String.valueOf(e); }
    }
    /** Pump-park (spin and park, never a bare sleep) until the condition holds, within a bound. */
    private static void pumpUntil(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) throw new AssertionError("Timed out: " + what);
            LockSupport.parkNanos(1_000_000);
        }
    }
    private static PlanStore.PlanRecord persisted(PlanStore store, TaskId id) {
        return await(store.load(id)).orElseThrow(() -> new AssertionError("no plan record"));
    }

    /** Matrix 1 and 3: the shares of one stage launch in parallel behind the flight barrier; a
      * duplicate in-flight event is a no-op: no second launch, no advance. */
    @Test
    void oneStagesSegmentsLaunchTogetherAndDuplicateEventsAreNoOps() {
        TaskId id = TaskId.of("parallel-a"); Harness d = new Harness(); // the plan id is the target
        seed(d.store, id, plan(List.of(id, TaskId.of("parallel-b"))), true); await(d.driver.start(id));
        await(d.driver.accept(new ContinuePlan())); // the durable pause resumes explicitly
        pumpUntil(() -> d.launched.size() == 2, "both shares of stage 1 in flight");
        assertEquals(2, d.pending().size(), "parallel: in flight, none settled yet");
        List<String> intents = persisted(d.store, id).plan().launch().launchIds();
        assertEquals(2, intents.size(), "two minted stage-1 ids are the launch permission");
        await(d.driver.accept(new ContinuePlan())); // matrix 3: the duplicate in-flight event
        assertEquals(2, d.launched.size(), "a duplicate launches nothing twice");
        d.pending().get(0).flight().complete(new Launched("id-a", "sess-a"));
        assertFalse(persisted(d.store, id).plan().stages().get(0).launched(),
                "the barrier holds until every segment settles");
        d.pending().get(0).flight().complete(new Launched("id-b", "sess-b"));
        assertTrue(persisted(d.store, id).plan().stages().get(0).launched(), "covered: launched");
        assertEquals(2, persisted(d.store, id).plan().launch().launchIds().size(),
                "the report carries empty launch ids; the intent was already persisted");
    }

    /** Matrix 2: stage N+1 never starts until stage N settles its launch report. */
    @Test
    void theNextStageWaitsForThePreviousStageToSettle() {
        TaskId id = TaskId.of("barrier-b"); Harness d = new Harness();
        seed(d.store, id, plan(List.of(TaskId.of("barrier-a")), List.of(id)), true); await(d.driver.start(id));
        await(d.driver.accept(new ContinuePlan()));
        pumpUntil(() -> d.launchesOf(1) == 1, "stage 1 launched");
        await(d.driver.accept(new TaskObserved(TaskId.of("barrier-a"), TaskStatus.DONE, false,
                REVISION)));
        assertEquals(0, d.launchesOf(2), "stage 2 waits for stage 1 to settle");
        d.pending().get(0).flight().complete(new Launched("id-a", "sess-a"));
        pumpUntil(() -> d.launchesOf(2) == 1, "stage 2 starts after the settle");
        RunningPlan after = persisted(d.store, id).plan();
        assertTrue(after.stages().get(0).launched() && after.stages().get(0).finished(),
                "stage 1 is launched and finished");
        assertFalse(after.stages().get(1).launched(), "stage 2 is in flight, not yet covered");
    }

    /** Matrix 4: a late failure halts future work and reports the started sibling without
      * killing it; a Continue never re-launches the attempted stage. */
    @Test
    void aDelayedFailedSiblingHaltsAndReportsTheStartedSiblings() {
        TaskId id = TaskId.of("sibling-a"); Harness d = new Harness(); // the plan id is the target
        seed(d.store, id, plan(List.of(id, TaskId.of("sibling-b"))), true); await(d.driver.start(id));
        await(d.driver.accept(new ContinuePlan()));
        pumpUntil(() -> d.pending().size() == 2, "both shares in flight");
        d.pending().get(0).flight().complete(new Launched("id-a", "sess-a"));
        d.pending().get(0).flight().completeExceptionally(
                new IllegalStateException("the agent of bravo-proj refused"));
        assertEquals(1, d.halts.size(), "the halt is reported once");
        String reason = d.halts.get(0).reason();
        assertTrue(reason.contains("1 of 2 segments") && reason.contains(
                "bravo-proj: java.lang.IllegalStateException: the agent of bravo-proj refused")
                && reason.contains("keep running"), "names the failed share: " + reason);
        Started started = d.halts.get(0).started().get(0);
        assertEquals(List.of(id), started.tasks()); assertEquals("sess-a", started.sessionId());
        assertTrue(persisted(d.store, id).plan().launch().launchIds()
                .contains(started.launchId()), "its launch intent is persisted");
        assertTrue(d.surfaces.stream().anyMatch(e -> e instanceof Refused refused
                && refused.reason().contains("not a launch")), "the failure is visible");
        assertFalse(persisted(d.store, id).plan().stages().get(0).launched(),
                "a failed launch is not a launch (T34a)");
        await(d.driver.accept(new TaskObserved(TaskId.of("barrier-a"), TaskStatus.DONE, false,
                REVISION)));
        await(d.driver.accept(new ContinuePlan()));
        assertEquals(2, d.launched.size(),
                "the halt holds: no further stage starts and nothing re-launches");
    }

    /** Matrix 5: cancellation advances the generation so stale completions never advance; a
      * disposed driver resolves without partial state: the record keeps its last good save. */
    @Test
    void cancellationAndDisposalResolveInFlightLaunchesWithoutPartialState() throws Exception {
        TaskId id = TaskId.of("cancel-a"); Harness d = new Harness(); // the plan id is the target
        seed(d.store, id, plan(List.of(id, TaskId.of("cancel-b"))), true); await(d.driver.start(id));
        await(d.driver.accept(new ContinuePlan()));
        pumpUntil(() -> d.pending().size() == 2, "stage 1 in flight");
        await(d.driver.accept(new CancelPlan()));
        assertTrue(d.surfaces.stream().anyMatch(e -> e instanceof PlanCanceled canceled
                && canceled.cancellationGeneration() == 1), "the cancel surfaces");
        assertEquals(RunningPlan.Status.CANCELED, persisted(d.store, id).plan().status());
        d.pending().forEach(t -> t.flight().complete(new Launched("id-c", "sess-c")));
        assertFalse(persisted(d.store, id).plan().stages().get(0).launched(),
                "stale completions of the old generation never advance (P4.12)");
        assertTrue(d.halts.isEmpty(), "a stale settle is a logged no-op, not a halt");
        await(d.driver.accept(new ContinuePlan())); // the canceled plan resumes explicitly
        pumpUntil(() -> d.launchesOf(1) == 4, "the stage launches afresh");
        RunningPlan resumed = persisted(d.store, id).plan();
        assertEquals(4, resumed.launch().launchIds().size(), "the fresh intents append");
        d.driver.close();
        PlanStore.Claim released = await(d.store.acquireClaim(id));
        assertTrue(released.valid(), "disposing the driver releases its plan claim");
        released.close();
        d.pending().forEach(t -> t.flight().complete(new Launched("id-d", "sess-d")));
        assertEquals(resumed, persisted(d.store, id).plan(),
                "completions after disposal leave the last good save untouched");
    }

    /** Matrix 6: a window-less preparation, the durable restart pause of a lost claim, and a
      * released claim are visible refusals; none of the three launches anything. */
    @Test
    void windowlessAndClaimLossRefuseVisiblyWithoutLaunching() {
        Harness d = new Harness();
        assertEquals("The driver has not started its plan yet; await start first",
                refusalOf(d.driver.accept(new ContinuePlan())), "an un-started driver refuses");
        TaskId id = TaskId.of("claim-loss-a"); // the plan id is the target task (D12)
        d.refusesPreparation = true; // no window: the preparation answers empty
        seed(d.store, id, plan(List.of(id)), true); await(d.driver.start(id));
        await(d.driver.accept(new ContinuePlan()));
        assertEquals(1, d.prepared.size(), "the window-less share is prepared");
        assertEquals(0, d.launched.size(), "a refused preparation launches nothing");
        assertTrue(d.halts.size() == 1 && d.halts.get(0).reason().contains(
                "the fresh preparation of the segment of alpha-proj refused"),
                "the refusal is surfaced as a halt naming the window-less segment");
        Harness e = new Harness(); TaskId pausedId = TaskId.of("restart-pause-a");
        seed(e.store, pausedId, plan(List.of(pausedId)), false); await(e.driver.start(pausedId));
        assertTrue(persisted(e.store, pausedId).paused(), "re-taking the claim durably pauses");
        await(e.driver.accept(new ContinuePlan()));
        pumpUntil(() -> e.launched.size() == 1, "only the explicit Continue resumes");
        Harness g = new Harness(); TaskId stuckId = TaskId.of("paused-refusal-a");
        seed(g.store, stuckId, plan(List.of(stuckId)), false); await(g.driver.start(stuckId));
        await(g.driver.accept(new PlanStarted(REVISION))); // an implicit start is refused
        assertTrue(g.surfaces.stream().anyMatch(one -> one instanceof Refused refused
                && refused.reason().contains("durably paused")), "the pause is visible");
        assertEquals(0, g.prepared.size(), "no launch permission");
        Harness f = new Harness(); TaskId heldId = TaskId.of("claim-release-a");
        seed(f.store, heldId, plan(List.of(heldId)), true); await(f.driver.start(heldId));
        f.store.close(); // releases every claim this instance holds
        await(f.driver.accept(new ContinuePlan()));
        assertTrue(f.surfaces.stream().anyMatch(one -> one instanceof Refused refused
                && refused.reason().contains("not held (P4.13)")), "the loss is visible");
        assertEquals(0, f.launched.size(), "no claim, no launch permission");
    }

    /** Matrix 7: a failed intent save is a visible refusal: no launch id takes effect, nothing
      * launches, and the file keeps exactly its last good bytes. */
    @Test
    void aFailedIntentSaveIsAVisibleRefusalThatLaunchesNothing() throws Exception {
        TaskId id = TaskId.of("save-failure-target");
        Harness d = new Harness();
        seed(d.store, id, plan(List.of(id)), true); await(d.driver.start(id));
        Path planFile = root.resolve("plans").resolve(id.text() + ".json"); // a torn tail
        Files.writeString(planFile, "{\"schemaVersion\":1,\"revision\"", StandardCharsets.UTF_8);
        await(d.driver.accept(new ContinuePlan()));
        assertEquals(0, d.prepared.size(), "no permission, no dispatch");
        assertTrue(d.surfaces.stream().anyMatch(e -> e instanceof Refused refused
                && refused.reason().contains("cannot be saved")), "the failure is visible");
        assertEquals("{\"schemaVersion\":1,\"revision\"", Files.readString(planFile,
                StandardCharsets.UTF_8), "the failed save touched nothing");
        await(d.driver.accept(new ContinuePlan()));
        assertEquals(0, d.launched.size(), "a retry launches nothing");
    }
}
