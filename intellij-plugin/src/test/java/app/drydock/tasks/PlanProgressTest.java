package app.drydock.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.RepositoryId;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static app.drydock.tasks.PlanProgress.AwaitingConfirmation;
import static app.drydock.tasks.PlanProgress.CancelPlan;
import static app.drydock.tasks.PlanProgress.ContinuePlan;
import static app.drydock.tasks.PlanProgress.Effect;
import static app.drydock.tasks.PlanProgress.Event;
import static app.drydock.tasks.PlanProgress.Halted;
import static app.drydock.tasks.PlanProgress.HumanConfirmed;
import static app.drydock.tasks.PlanProgress.LaunchReported;
import static app.drydock.tasks.PlanProgress.LaunchStage;
import static app.drydock.tasks.PlanProgress.PlanCanceled;
import static app.drydock.tasks.PlanProgress.PlanCompleted;
import static app.drydock.tasks.PlanProgress.PlanStarted;
import static app.drydock.tasks.PlanProgress.Refused;
import static app.drydock.tasks.PlanProgress.RestartRequested;
import static app.drydock.tasks.PlanProgress.Result;
import static app.drydock.tasks.PlanProgress.State;
import static app.drydock.tasks.PlanProgress.TaskEdited;
import static app.drydock.tasks.PlanProgress.TaskObserved;
import static app.drydock.tasks.PlanProgress.TaskReopened;
import static app.drydock.tasks.PlanProgress.apply;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T34b acceptance matrix for the accepted {@link PlanProgress} pure reducer
 * (D8/D12/D16/N1, P4.4-8, P4.12): the diamond lifecycle with the target started
 * exactly once, duplicate/reordered idempotence, instant completion at the last
 * stage's launch point, review waiting with Confirm advancement, Reopen halts
 * with Continue-only resumption and RestartRequested refusals, TaskEdited
 * no-ops, revision-mismatch refusals, and launch-failure/Cancel semantics; real
 * plans via the public {@link RunningPlan}/{@link PlanLaunch} constructors, no
 * mocking.
 */
class PlanProgressTest {

    private static final RepositoryId REPO =
            RepositoryId.of("11111111-1111-1111-1111-111111111111");
    private static final PlanLaunch.Origin ORIGIN = new PlanLaunch.Origin("win-1",
            Path.of("/tmp/worktrees/alpha"));

    private static TaskId id(String text) { return TaskId.of(text); }

    private static List<TaskId> tasks(String... texts) {
        List<TaskId> tasks = new ArrayList<>();
        for (String text : texts) {
            tasks.add(id(text));
        }
        return tasks;
    }

    private static RunningPlan.StageState stage(int number, String... taskIds) {
        return new RunningPlan.StageState(number, tasks(taskIds), false, false);
    }

    /** A running plan over the given stages; the target is the last stage's first task (D12). */
    private static RunningPlan plan(RunningPlan.StageState... stages) {
        List<Integer> confirmed = new ArrayList<>();
        Map<TaskId, PlanLaunch.Selection> selections = new LinkedHashMap<>();
        for (int i = 0; i < stages.length; i++) {
            confirmed.add(i + 1);
            for (TaskId task : stages[i].tasks()) {
                selections.put(task, new PlanLaunch.Selection(AgentKind.CLAUDE, REPO,
                        "drydock-plugin"));
            }
        }
        TaskId target = stages[stages.length - 1].tasks().get(0);
        PlanLaunch launch = new PlanLaunch(target, confirmed, selections, 7L, List.of(), 0, ORIGIN);
        return new RunningPlan(launch, List.of(stages), RunningPlan.Status.RUNNING);
    }

    /** The diamond: stage 1 runs a-task and b-task in parallel, stage 2 is the target. */
    private static RunningPlan diamond() {
        return plan(stage(1, "a-task", "b-task"), stage(2, "target-task"));
    }

    private static TaskObserved observed(String task, TaskStatus status) {
        return new TaskObserved(id(task), status, true, 7L);
    }

    private static LaunchReported covered(int stage, String... taskIds) {
        return new LaunchReported(stage, tasks(taskIds), List.of("receipt-" + stage),
                LaunchReported.Outcome.COVERED, null, 7L, 0);
    }

    private static void refusedWith(State before, Event event, String reason) {
        Result result = apply(before, event);
        assertEquals(List.of(new Refused(reason)), result.effects());
        assertEquals(before, result.state());
    }

    @Test
    void diamondLifecycleLaunchesStagesInOrderAndStartsTheTargetOnce() {
        Result result = apply(State.of(diamond()), new PlanStarted(7L));
        assertEquals(List.of(new LaunchStage(1, tasks("a-task", "b-task"), 0)), result.effects());
        result = apply(result.state(), covered(1, "a-task", "b-task"));
        assertTrue(result.effects().isEmpty());
        assertTrue(result.state().plan().stages().get(0).launched());
        assertEquals(List.of("receipt-1"), result.state().plan().launch().launchIds());
        result = apply(result.state(), observed("a-task", TaskStatus.DONE));
        assertTrue(result.effects().isEmpty());
        result = apply(result.state(), observed("b-task", TaskStatus.DONE));
        assertEquals(List.of(new LaunchStage(2, tasks("target-task"), 0)), result.effects());
        assertTrue(result.state().plan().stages().get(0).finished());
        assertFalse(result.state().plan().stages().get(1).launched());
        result = apply(result.state(), covered(2, "target-task"));
        assertTrue(result.effects().isEmpty());
        result = apply(result.state(), observed("target-task", TaskStatus.DONE));
        assertEquals(List.of(new PlanCompleted()), result.effects());
        assertEquals(RunningPlan.Status.COMPLETED, result.state().plan().status());
        assertTrue(result.state().plan().stages().get(1).finished());
        // a completed plan never auto-resumes: PlanStarted is refused (P4.7)
        refusedWith(result.state(), new PlanStarted(7L), "the plan is completed; it never"
                + " auto-resumes, an explicit Continue event is required");
    }

    @Test
    void duplicateAndReorderedEventsNeverLaunchAStageTwice() {
        List<Effect> emitted = new ArrayList<>();
        Result result = apply(State.of(diamond()), new PlanStarted(7L));
        emitted.addAll(result.effects());
        result = apply(result.state(), covered(1, "a-task", "b-task"));
        emitted.addAll(result.effects());
        // reordered: b completes before a; only the last completion advances
        result = apply(result.state(), observed("b-task", TaskStatus.DONE));
        emitted.addAll(result.effects());
        result = apply(result.state(), observed("a-task", TaskStatus.DONE));
        emitted.addAll(result.effects());
        State advanced = result.state();
        // duplicates: the done event, PlanStarted and the launch report replayed
        result = apply(advanced, observed("a-task", TaskStatus.DONE));
        assertTrue(result.effects().isEmpty());
        result = apply(result.state(), new PlanStarted(7L));
        assertTrue(result.effects().isEmpty());
        result = apply(result.state(), covered(1, "a-task", "b-task"));
        assertTrue(result.effects().isEmpty());
        assertEquals(advanced, result.state());
        // despite the reorder and the duplicates, each stage launched exactly once
        assertEquals(List.of(new LaunchStage(1, tasks("a-task", "b-task"), 0),
                new LaunchStage(2, tasks("target-task"), 0)), emitted);
    }

    @Test
    void completionIsInstantAtTheLastStageLaunchPoint() {
        Result result = apply(State.of(plan(stage(1, "target-task"))), new PlanStarted(7L));
        assertEquals(List.of(new LaunchStage(1, tasks("target-task"), 0)), result.effects());
        // the target is already done when the covered launch report arrives
        result = apply(result.state(), observed("target-task", TaskStatus.DONE));
        assertTrue(result.effects().isEmpty());
        assertFalse(result.state().plan().stages().get(0).finished());
        result = apply(result.state(), covered(1, "target-task"));
        assertEquals(List.of(new PlanCompleted()), result.effects());
        assertEquals(RunningPlan.Status.COMPLETED, result.state().plan().status());
        assertTrue(result.state().plan().stages().get(0).finished());
    }

    @Test
    void reviewWaitsForTheHumanConfirmationAndNeverAutoDones() {
        Result result = apply(State.of(diamond()), new PlanStarted(7L));
        result = apply(result.state(), covered(1, "a-task", "b-task"));
        // a enters review while b still runs: no advance, no premature hold
        result = apply(result.state(), observed("a-task", TaskStatus.REVIEW));
        assertTrue(result.effects().isEmpty());
        // b done: the review is all that holds stage 1 back (D16)
        result = apply(result.state(), observed("b-task", TaskStatus.DONE));
        assertEquals(List.of(new AwaitingConfirmation(id("a-task"), 1)), result.effects());
        State waiting = result.state();
        // review observation noise restates the hold, never auto-dones it
        result = apply(waiting, observed("a-task", TaskStatus.REVIEW));
        assertEquals(List.of(new AwaitingConfirmation(id("a-task"), 1)), result.effects());
        // the human confirm (done) advances to the target's stage
        result = apply(waiting, new HumanConfirmed(id("a-task"), 7L));
        assertEquals(List.of(new LaunchStage(2, tasks("target-task"), 0)), result.effects());
        assertTrue(result.state().plan().stages().get(0).finished());
    }

    @Test
    void reopenHaltsThePlanAndOnlyContinueResumesIt() {
        // an interrupted current-stage task (started, no live session) halts too (P4.8)
        Result result = apply(State.of(diamond()), new PlanStarted(7L));
        result = apply(result.state(), covered(1, "a-task", "b-task"));
        result = apply(result.state(), new TaskObserved(id("a-task"), TaskStatus.STARTED,
                false, 7L));
        assertEquals(List.of(new Halted("task 'a-task' of stage 1 is interrupted"
                + " (no live session); the plan halts until an explicit Continue (P4.8)")),
                result.effects());
        assertTrue(result.state().halted());
        // the reopened current-stage task halts the same way
        result = apply(State.of(diamond()), new PlanStarted(7L));
        result = apply(result.state(), covered(1, "a-task", "b-task"));
        result = apply(result.state(), observed("a-task", TaskStatus.DONE));
        result = apply(result.state(), observed("b-task", TaskStatus.DONE));
        result = apply(result.state(), covered(2, "target-task"));
        result = apply(result.state(), new TaskReopened(id("target-task"), 7L));
        assertEquals(List.of(new Halted("task 'target-task' of stage 2 was reopened;"
                + " the plan halts until an explicit Continue (D16)")), result.effects());
        State halted = result.state();
        // a completion during the halt does not advance the plan
        result = apply(halted, observed("target-task", TaskStatus.DONE));
        assertTrue(result.effects().isEmpty());
        assertTrue(result.state().halted());
        assertFalse(result.state().plan().stages().get(1).finished());
        // RestartRequested is refused in every state; only Continue resumes
        refusedWith(halted, new RestartRequested(), "the plan is running; there is nothing"
                + " to restart");
        result = apply(result.state(), new ContinuePlan());
        assertEquals(List.of(new PlanCompleted()), result.effects());
        assertEquals(RunningPlan.Status.COMPLETED, result.state().plan().status());
        refusedWith(result.state(), new RestartRequested(), "a completed plan never auto-resumes;"
                + " an explicit Continue event is required");
        refusedWith(State.of(diamond().canceled()), new RestartRequested(), "a canceled plan never"
                + " auto-resumes; an explicit Continue event is required");
    }

    @Test
    void unrelatedTaskEditsAreNoOps() {
        State fresh = State.of(diamond());
        Result result = apply(fresh, new TaskEdited(id("a-task")));
        assertTrue(result.effects().isEmpty());
        assertEquals(fresh, result.state());
        // also mid-flight, after a stage launch
        Result running = apply(fresh, new PlanStarted(7L));
        result = apply(running.state(), new TaskEdited(id("target-task")));
        assertTrue(result.effects().isEmpty());
        assertEquals(running.state(), result.state());
    }

    @Test
    void eventsOffTheGraphRevisionAreRefusedWithTheStateUnchanged() {
        State fresh = State.of(diamond());
        String reason = "graph/policy revision 8 does not match the plan's 7; the event is"
                + " refused, the state is unchanged";
        refusedWith(fresh, new PlanStarted(8L), reason);
        refusedWith(fresh, new TaskObserved(id("a-task"), TaskStatus.DONE, true, 8L), reason);
        refusedWith(fresh, new HumanConfirmed(id("a-task"), 8L), reason);
        refusedWith(fresh, new TaskReopened(id("target-task"), 8L), reason);
        refusedWith(fresh, new LaunchReported(1, tasks("a-task", "b-task"), List.of("r1"),
                LaunchReported.Outcome.COVERED, null, 8L, 0), reason);
    }

    @Test
    void launchFailuresAreNotLaunchesAndCancelStopsFutureSegmentsOnly() {
        State attempted = apply(State.of(diamond()), new PlanStarted(7L)).state();
        assertFalse(attempted.plan().stages().get(0).launched());
        // a failed, partial or ownership-overlapping launch is refused, not a launch
        refusedWith(attempted, new LaunchReported(1, tasks("a-task", "b-task"), List.of("r1"),
                LaunchReported.Outcome.FAILED, "spawn error", 7L, 0),
                "the launch of stage 1 failed: spawn error; a failed launch is not a launch");
        refusedWith(attempted, new LaunchReported(1, tasks("a-task"), List.of("r1"),
                LaunchReported.Outcome.PARTIAL, null, 7L, 0),
                "the launch of stage 1 started [a-task] instead of the stage's tasks"
                + " [a-task, b-task]; a stage launch that did not cover all its tasks"
                + " is not a launch");
        refusedWith(attempted, new LaunchReported(1, tasks("a-task", "b-task"), List.of("r1"),
                LaunchReported.Outcome.OVERLAPPING_OWNERSHIP, "session of win-2 owns b", 7L, 0),
                "the launch of stage 1 overlaps ownership outside the confirmed plan:"
                + " session of win-2 owns b; it is refused, not a launch");
        // a report for a stage whose launch this plan never emitted is refused too
        refusedWith(attempted, covered(2, "target-task"), "launch report for stage 2 arrives"
                + " without a launch this plan emitted (stale or foreign)");
        // a covered stage-1 launch runs, then the plan is canceled mid-flight
        Result result = apply(attempted, covered(1, "a-task", "b-task"));
        assertTrue(result.effects().isEmpty());
        result = apply(result.state(), new CancelPlan());
        assertEquals(List.of(new PlanCanceled(1)), result.effects());
        assertEquals(RunningPlan.Status.CANCELED, result.state().plan().status());
        assertEquals(1, result.state().plan().cancellationGeneration());
        // the already-launched stage keeps its sessions running (P4.12)
        assertTrue(result.state().plan().stages().get(0).launched());
        assertFalse(result.state().plan().stages().get(0).finished());
        // completions after the cancel start no new segment
        result = apply(result.state(), observed("a-task", TaskStatus.DONE));
        assertTrue(result.effects().isEmpty());
        result = apply(result.state(), observed("b-task", TaskStatus.DONE));
        assertTrue(result.effects().isEmpty());
        // launch reports under the pre-cancel generation are stale
        refusedWith(result.state(), new LaunchReported(2, tasks("target-task"), List.of("r9"),
                LaunchReported.Outcome.COVERED, null, 7L, 0),
                "stale launch report for stage 2: cancellation generation 0 does not match"
                + " the plan's 1 (P4.12)");
        // Continue restarts: stage 1 finishes from the completions, the target stage
        // starts under the new generation
        result = apply(result.state(), new ContinuePlan());
        assertEquals(List.of(new LaunchStage(2, tasks("target-task"), 1)), result.effects());
        assertEquals(RunningPlan.Status.RUNNING, result.state().plan().status());
    }
}
