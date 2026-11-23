package app.drydock.intellij.delayedtasks;

import app.drydock.delayedtasks.PlanDriver;
import app.drydock.domain.RepositoryCatalog;
import app.drydock.tasks.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * The T38b matrix over the committed cross-project start (T38a), on the
 * shared booted-IDE real-store fixture CrossProjectStartFixture (a
 * supervisor-granted split, the T31b precedent; the fixture's javadoc
 * carries the reflective glue, the temp-dir store and the honest
 * boundaries): the full 7-item plan-T38 matrix maps 1+7 to the first test
 * below, and 2, 3, 4, 5 and 6 each to their own test. Item 1's shape: two
 * projects share the PARALLEL first stage -- with per-repository segments
 * (D7/D8) a parallel stage plus the target's own final stage spans those
 * two projects' shares plus the target's project; a target sharing a
 * project with the parallel share would collapse both into one segment,
 * one stage -- and the target launches LAST, after its stage. Item 7 is
 * asserted inside every launch the fixture's seam records: no launch ever
 * runs before the confirmed persistence (the plan record and its minted
 * launch intent are read STORE-BACKED at launch time, and any missing or
 * pre-persist state is a violation the first test fails on). Items 2, 3
 * and 5 assert the whole-start refusals both in the flow's returned
 * answer and VISIBLY, through the captured refusal dialog of the booted
 * IDE. Red-green, honestly: the first run parked a deliberate TEST-setup
 * flaw -- the target's project was missing from the fixture's registered
 * catalog, so the explicit Continue halted ('no longer registered',
 * P4.10) and no stage launched; the second red was structural -- a plain-
 * JVM draft passing from=null crashed refuse() in an app-present JVM, so
 * the fixture boots the IDE the committed production javadoc names as
 * T38b's for the visible dialog path.
 */
public class CrossProjectStartTest extends CrossProjectStartFixture {

    // 1 + 7. The confirmed plan persists before any effect; both stages' segments
    // launch, the target last, never before the confirmed persistence.
    public void testConfirmedPlanPersistsBeforeEffectsAndDrivesBothStagesWithTheTargetLast()
            throws Exception {
        Optional<String> refused = cross(() -> Optional.of(choices(Map.of())));
        assertTrue("the confirmed start refuses nothing", refused.isEmpty());
        await(() -> launched.size() == 2, "both segments of the parallel stage to launch");
        assertEquals("stage 1 is both projects (D7)", Set.of(repoA, repoB), Set.copyOf(
                launched.stream().map(PlanDriver.SegmentRequest::repository).toList()));
        assertTrue("the target never launches before its own stage", launched.stream()
                .noneMatch(r -> r.tasks().contains(t)));
        finish(a, repoA); // the launched sessions complete their tasks in the store
        finish(b, repoB);
        publish(); // the watcher's publication seam feeds the DONE observations
        await(() -> launched.size() == 3, "the final stage to launch the target");
        assertEquals("the target's launch is the final stage's own", 2, launched.get(2).stage());
        assertEquals("the target's segment is the target", List.of(t), launched.get(2).tasks());
        await(() -> record() != null && record().plan().stages().get(1).launched(),
                "the launched stages to persist");
        RunningPlan running = record().plan();
        assertEquals("every stage launched", List.of(true, true), running.stages().stream()
                .map(RunningPlan.StageState::launched).toList());
        assertTrue("stage 1 is finished, the target's stage still runs", running.stages().get(0)
                .finished() && !running.stages().get(1).finished());
        assertEquals("one persisted launch intent per segment, before each launch (P4.13)", 3,
                running.launch().launchIds().size());
        Path window = Path.of(getProject().getBasePath());
        assertEquals("the confirmed origin rides the plan (P4.9)", new PlanLaunch.Origin(
                RepositoryCatalog.canonicalize(window).toString(), window),
                running.launch().origin());
        assertEquals("each task carries its segment's confirmed selection", repoB,
                running.launch().selections().get(b).repository());
        assertTrue("launch/persist ordering", violations.isEmpty());
        await(() -> Collections.disjoint(inFlight(), Set.of(a, b, t)),
                "the whole-plan claim to release once the Continue is fed");
    }

    // 2. The graph changed while the confirmation was open: the WHOLE start refuses.
    public void testAChangedGraphAtConfirmationRefusesTheWholeStart() throws Exception {
        Optional<String> refused = cross(() -> {
            try { finish(a, repoA); } // the store moved under the open confirmation
            catch (IOException e) { throw new UncheckedIOException(e); }
            return Optional.of(choices(Map.of())); });
        assertTrue("a stale graph refuses visibly", refused.isPresent());
        assertTrue("the refusal names the change and stops everything",
                refused.get().contains("The plan changed while its confirmation was open")
                        && refused.get().contains("nothing was started"));
        await(() -> !shown.isEmpty(), "the stale graph's visible refusal");
        assertTrue("the whole-start refusal surfaces as the dialog, verbatim", shown.get(0)
                .contains("The plan changed while its confirmation was open"));
        assertNothingStarted();
    }

    // 3. One save failure: the whole start refuses -- zero launches, no reservations.
    public void testOneSaveFailureRefusesTheWholeStartWithZeroLaunches() throws Exception {
        Files.createFile(root.resolve("plans")); // the plans family cannot be written
        Optional<String> refused = cross(() -> Optional.of(choices(Map.of())));
        assertTrue("the failed save refuses", refused.isPresent());
        assertTrue("a failed save is no launch permission (P4.13)",
                refused.get().contains("could not be persisted")
                        && refused.get().contains("nothing was started"));
        await(() -> !shown.isEmpty(), "the failed save's visible refusal");
        assertTrue("the whole-start refusal surfaces as the dialog", shown.get(0)
                .contains("could not be persisted"));
        assertNothingStarted();
    }

    // 4. The approved done_by persists before any effect and is respected.
    public void testTheApprovedDoneByPersistsBeforeEffectsAndIsRespected() throws Exception {
        expectedDoneBy = Map.of(a, DoneBy.AGENT);
        Optional<String> refused = cross(() -> Optional.of(choices(Map.of(a, DoneBy.AGENT))));
        assertTrue("the confirmed start refuses nothing", refused.isEmpty());
        await(() -> launched.size() == 2, "stage 1 to launch");
        TaskSnapshot settled = TaskFiles.scan(root);
        assertEquals("the changed policy is persisted in the task store", DoneBy.AGENT,
                settled.byId().get(a).doneBy());
        assertEquals("an unchanged policy is not rewritten", DoneBy.HUMAN,
                settled.byId().get(b).doneBy());
        TaskState state = TaskState.of(settled, TaskGraph.of(settled, slugs::get), Set.of());
        assertEquals("the agent may close the approved task (D16)", TaskStatus.DONE,
                state.validateUpdate(a, TaskState.Actor.AGENT, TaskStatus.DONE).resulting());
        assertEquals("a human-validated task keeps its review gate", TaskStatus.REVIEW,
                state.validateUpdate(b, TaskState.Actor.AGENT, TaskStatus.DONE).resulting());
        assertTrue("policy before effects", violations.isEmpty());
        await(() -> Collections.disjoint(inFlight(), Set.of(a, b, t)),
                "the whole-plan claim to release once the Continue is fed");
    }

    // 5. A duplicate start is an atomic no-op: nothing confirmed, nothing written.
    public void testADuplicateStartIsAnAtomicNoOp() throws Exception {
        List<TaskId> held = claimWholePlan(); // the first start's own whole-plan claim
        Set<TaskId> before = Set.copyOf(inFlight());
        assertTrue("the parked claim holds the whole plan", before.containsAll(held));
        List<String> confirmed = new ArrayList<>();
        Optional<String> refused = cross(() -> {
            confirmed.add("reached");
            return Optional.of(choices(Map.of())); });
        assertTrue("the duplicate start refuses visibly", refused.isPresent());
        assertTrue("the refusal names the in-flight start",
                refused.get().contains("A start already in flight covers the plan"));
        await(() -> !shown.isEmpty(), "the duplicate start's visible refusal");
        assertTrue("the no-op refusal surfaces as the dialog", shown.get(0)
                .contains("A start already in flight covers the plan"));
        assertTrue("the duplicate start never reaches the confirmation", confirmed.isEmpty());
        assertEquals("and never launches anything", List.of(), launched);
        assertNull("nothing is persisted", record());
        assertEquals("the no-op touches no claim atomically", before, Set.copyOf(inFlight()));
    }

    // 6. A canceled confirmation: zero writes, zero reservations.
    public void testACanceledConfirmationWritesAndReservesNothing() throws Exception {
        List<TaskDocument> before = TaskFiles.scan(root).documents();
        Optional<String> refused = cross(Optional::empty);
        assertTrue("a canceled confirmation is no error", refused.isEmpty());
        assertEquals("no segment ever launched", List.of(), launched);
        assertEquals("zero task writes", before, TaskFiles.scan(root).documents());
        assertFalse("no plan family was ever created", Files.exists(root.resolve("plans")));
        assertNull("nothing is persisted", record());
        assertTrue("zero reservations", noReservations());
        await(() -> Collections.disjoint(inFlight(), Set.of(a, b, t)), "the claim to release");
    }
}
