package app.drydock.tasks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The pure stage-progression rule of one confirmed staged plan (T34a, D8/D12/D16/N1,
 * P4.4-8): {@link #apply(State, Event)} maps an immutable {@link State} and one {@link Event}
 * to the next state plus the {@link Effect}s the driver (T35) must execute. Effects are
 * RETURNED, never executed here; no I/O, no clock, no store, no session access. The reducer is
 * deterministic and total: no event order can throw, and duplicate or reordered events never
 * launch a stage twice (a null task id degrades to a no-op or refusal, never a wrong answer,
 * because every stage lookup misses).
 *
 * <p>State is the accepted {@link RunningPlan} plus bookkeeping it does not persist: observed
 * task statuses, the halt flag, and the stage numbers whose {@link LaunchStage} effect has been
 * emitted. A stage's {@code launched} flag flips only when the driver reports a launch covering
 * every stage task (a partial, failed or ownership-overlapping launch is not a launch); the attempt
 * set survives that refusal, so nothing starts twice without the explicit {@link ContinuePlan}
 * of a restart. A halt (interrupted or reopened task, P4.8) and a restart both require that
 * Continue -- never an auto-resume.</p>
 */
public final class PlanProgress {

    /** One progression input; the driver feeds these in store-watch order. */
    public sealed interface Event permits PlanStarted, TaskObserved, HumanConfirmed, TaskReopened,
            LaunchReported, TaskEdited, CancelPlan, ContinuePlan, RestartRequested {
    }

    /** The plan entered the running state: stage 1 is confirmed and starts (D8/P4.6). */
    public record PlanStarted(long graphRevision) implements Event { }

    /** The store watcher reports one task's status; "interrupted" is derived here: started
      * with no live session (P1/D10, P4.8). */
    public record TaskObserved(TaskId task, TaskStatus status, boolean sessionLive, long graphRevision)
            implements Event {
        public TaskObserved { Objects.requireNonNull(status, "status"); }
    }

    /** The human confirmed a task in review: review becomes done, the plan continues (D16). */
    public record HumanConfirmed(TaskId task, long graphRevision) implements Event { }

    /** The human reopened a task: review becomes open; progression HALTS until Continue (D16/P4.8). */
    public record TaskReopened(TaskId task, long graphRevision) implements Event { }

    /** The driver reports one stage launch's outcome (T35); only COVERED is a launch. */
    public record LaunchReported(int stage, List<TaskId> tasks, List<String> launchIds, Outcome outcome,
            String failure, long graphRevision, int cancellationGeneration) implements Event {

        /** COVERED: every stage task started. PARTIAL: some missing. FAILED: the attempt failed.
          * OVERLAPPING_OWNERSHIP: a task was owned by another live session. */
        public enum Outcome { COVERED, PARTIAL, FAILED, OVERLAPPING_OWNERSHIP }

        public LaunchReported {
            Objects.requireNonNull(tasks, "tasks");
            Objects.requireNonNull(launchIds, "launchIds");
            Objects.requireNonNull(outcome, "outcome");
            tasks = List.copyOf(tasks);
            launchIds = List.copyOf(launchIds);
        }
    }

    /** A note, title or body edit on a task: unrelated to progression. */
    public record TaskEdited(TaskId task) implements Event { }

    /** Cancel the plan (P4.12): no future segment starts; started sessions keep running. */
    public record CancelPlan() implements Event { }

    /** The explicit Continue: clears a halt and resumes or restarts the plan (P4.8/P4.10). */
    public record ContinuePlan() implements Event { }

    /** A restart attempt: always refused; only Continue resumes, never an auto-resume. */
    public record RestartRequested() implements Event { }

    /** What the driver must do or show; returned, never executed here. */
    public sealed interface Effect permits LaunchStage, AwaitingConfirmation, Halted, Refused,
            PlanCanceled, PlanCompleted {
    }

    /** Start every task of one stage in parallel (D8); the last stage's launch is the target's
      * start (D12); stale once the generation no longer matches the plan's (P4.12). */
    public record LaunchStage(int stage, List<TaskId> tasks, int cancellationGeneration)
            implements Effect { }

    /** A task in review holds its stage back until the human confirms (D16); never an auto-done. */
    public record AwaitingConfirmation(TaskId task, int stage) implements Effect { }

    /** Progression halted (interrupted or reopened task): only Continue resumes (P4.8). */
    public record Halted(String reason) implements Effect { }

    /** A refused event with its concrete reason; the state is unchanged. */
    public record Refused(String reason) implements Effect { }

    /** The plan was canceled; launches under older generations are stale (P4.12). */
    public record PlanCanceled(int cancellationGeneration) implements Effect { }

    /** Every stage finished: the plan completed, instantly where a launch would have fired. */
    public record PlanCompleted() implements Effect { }

    /** The reducer state: the plan, the observed task statuses, the halt flag and the stage numbers
      * whose launch effect has been emitted; immutable in and out. */
    public record State(RunningPlan plan, Map<TaskId, TaskStatus> statuses, boolean halted,
            Set<Integer> launchAttempts) {
        public State {
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(statuses, "statuses");
            Objects.requireNonNull(launchAttempts, "launchAttempts");
            statuses = Map.copyOf(statuses);
            launchAttempts = Set.copyOf(launchAttempts);
        }

        /** The progression state of a plan entering the running state. */
        public static State of(RunningPlan plan) { return new State(plan, Map.of(), false, Set.of()); }
    }

    /** One reducer step: the next state and the effects the driver must execute. */
    public record Result(State state, List<Effect> effects) {
        public Result {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(effects, "effects");
            effects = List.copyOf(effects);
        }
    }

    /** The reducer itself: total and deterministic over every event, whatever the order. */
    public static Result apply(State state, Event event) {
        Objects.requireNonNull(state, "state"); Objects.requireNonNull(event, "event");
        return switch (event) {
            case PlanStarted started -> planStarted(state, started);
            case TaskObserved observed -> taskObserved(state, observed);
            case HumanConfirmed confirmed -> humanConfirmed(state, confirmed);
            case TaskReopened reopened -> taskReopened(state, reopened);
            case LaunchReported reported -> launchReported(state, reported);
            case TaskEdited ignored -> new Result(state, List.of());
            case CancelPlan cancel -> cancelPlan(state);
            case ContinuePlan resume -> continuePlan(state);
            case RestartRequested restart -> restartRequested(state);
        };
    }

    private static Result planStarted(State state, PlanStarted event) {
        String stale = revisionMismatch(state, event.graphRevision()); if (stale != null) return refused(state, stale);
        RunningPlan plan = state.plan();
        if (plan.status() != RunningPlan.Status.RUNNING) return refused(state, "the plan is "
                + plan.status().wireName() + "; it never auto-resumes, an explicit Continue event is required");
        return launchStage(state, 1);
    }

    private static Result taskObserved(State state, TaskObserved event) {
        String stale = revisionMismatch(state, event.graphRevision()); if (stale != null) return refused(state, stale);
        RunningPlan plan = state.plan();
        Optional<RunningPlan.StageState> stage = stageOf(plan, event.task());
        if (stage.isEmpty()) return new Result(state, List.of()); // watcher noise outside the plan
        Map<TaskId, TaskStatus> statuses = new HashMap<>(state.statuses());
        statuses.put(event.task(), event.status());
        boolean interrupted = plan.status() == RunningPlan.Status.RUNNING
                && event.status() == TaskStatus.STARTED && !event.sessionLive()
                && currentStage(plan).map(cur -> cur.tasks().contains(event.task())).orElse(false);
        State next = new State(plan, statuses, state.halted() || interrupted, state.launchAttempts());
        if (interrupted && !state.halted()) return new Result(next, List.of(new Halted("task '"
                + event.task().text() + "' of stage " + stage.orElseThrow().stage()
                + " is interrupted (no live session); the plan halts until an explicit Continue (P4.8)")));
        return evaluate(next);
    }

    private static Result humanConfirmed(State state, HumanConfirmed event) {
        String stale = revisionMismatch(state, event.graphRevision()); if (stale != null) return refused(state, stale);
        RunningPlan plan = state.plan();
        if (stageOf(plan, event.task()).isEmpty()) return refused(state, "task '" + event.task()
                + "' is not a task of this plan; a confirmation outside the plan cannot advance it");
        Map<TaskId, TaskStatus> statuses = new HashMap<>(state.statuses());
        statuses.put(event.task(), TaskStatus.DONE);
        return evaluate(new State(plan, statuses, state.halted(), state.launchAttempts()));
    }

    private static Result taskReopened(State state, TaskReopened event) {
        String stale = revisionMismatch(state, event.graphRevision()); if (stale != null) return refused(state, stale);
        RunningPlan plan = state.plan();
        Optional<RunningPlan.StageState> stage = stageOf(plan, event.task());
        if (stage.isEmpty()) return refused(state, "task '" + event.task() + "' is not a task of this"
                + " plan; reopening it cannot halt the plan");
        Map<TaskId, TaskStatus> statuses = new HashMap<>(state.statuses());
        statuses.put(event.task(), TaskStatus.OPEN);
        boolean halts = plan.status() == RunningPlan.Status.RUNNING;
        State next = new State(plan, statuses, state.halted() || halts, state.launchAttempts());
        if (halts && !state.halted()) return new Result(next, List.of(new Halted("task '"
                + event.task().text() + "' of stage " + stage.orElseThrow().stage()
                + " was reopened; the plan halts until an explicit Continue (D16)")));
        return new Result(next, List.of());
    }

    private static Result launchReported(State state, LaunchReported event) {
        String stale = revisionMismatch(state, event.graphRevision()); if (stale != null) return refused(state, stale);
        RunningPlan plan = state.plan();
        if (event.cancellationGeneration() != plan.cancellationGeneration()) return refused(state,
                "stale launch report for stage " + event.stage() + ": cancellation generation "
                + event.cancellationGeneration() + " does not match the plan's "
                + plan.cancellationGeneration() + " (P4.12)");
        if (event.stage() < 1 || event.stage() > plan.stages().size()) return refused(state,
                "launch report names stage " + event.stage() + ", which this plan does not have");
        RunningPlan.StageState stage = plan.stages().get(event.stage() - 1);
        if (!state.launchAttempts().contains(event.stage())) return refused(state, "launch report"
                + " for stage " + event.stage() + " arrives without a launch this plan emitted (stale or foreign)");
        if (stage.launched()) return new Result(state, List.of()); // duplicate confirmation: idempotent
        boolean covered = new HashSet<>(event.tasks()).equals(new HashSet<>(stage.tasks()));
        if (event.outcome() != LaunchReported.Outcome.COVERED || !covered) return refused(state,
                notALaunchReason(event, stage));
        RunningPlan launched = withStage(plan, event.stage(), true, stage.finished(), plan.status());
        PlanLaunch launch = plan.launch();
        List<String> receipts = new ArrayList<>(launch.launchIds());
        receipts.addAll(event.launchIds());
        RunningPlan confirmed = new RunningPlan(new PlanLaunch(launch.target(),
                launch.confirmedStages(), launch.selections(), launch.graphRevision(), receipts,
                launch.cancellationGeneration(), launch.origin(), launch.stacks()),
                launched.stages(), launched.status());
        return evaluate(new State(confirmed, state.statuses(), state.halted(), state.launchAttempts()));
    }

    private static Result cancelPlan(State state) {
        RunningPlan plan = state.plan();
        if (plan.status() != RunningPlan.Status.RUNNING) return new Result(state, List.of()); // idempotent
        RunningPlan canceled = plan.canceled();
        return new Result(new State(canceled, state.statuses(), state.halted(),
                state.launchAttempts()), List.of(new PlanCanceled(canceled.cancellationGeneration())));
    }

    private static Result continuePlan(State state) {
        RunningPlan plan = state.plan();
        if (plan.status() == RunningPlan.Status.COMPLETED
                || plan.stages().stream().allMatch(RunningPlan.StageState::finished)) {
            return refused(state, "nothing to resume: every stage of the plan is finished");
        }
        Set<Integer> attempts = state.launchAttempts();
        if (plan.status() == RunningPlan.Status.CANCELED) {
            attempts = launchedAttempts(plan, attempts); // a restart may start what never launched
            plan = new RunningPlan(plan.launch(), plan.stages(), RunningPlan.Status.RUNNING);
        }
        State resumed = new State(plan, state.statuses(), false, attempts);
        RunningPlan.StageState stage = currentStage(plan).orElseThrow(); // unfinished stages remain
        if (!stage.launched() && !attempts.contains(stage.stage())) return launchStage(resumed, stage.stage());
        return evaluate(resumed);
    }

    private static Result restartRequested(State state) {
        RunningPlan.Status status = state.plan().status();
        if (status == RunningPlan.Status.RUNNING) return refused(state,
                "the plan is running; there is nothing to restart");
        return refused(state, "a " + status.wireName() + " plan never auto-resumes;"
                + " an explicit Continue event is required");
    }

    /** Completes the current stage when every task is done or dropped, waits (never auto-dones)
      * when only a review is left, never launches the current stage (only entry, advancement and
      * Continue emit launches), and completes the plan instantly at the last stage's launch point. */
    private static Result evaluate(State state) {
        RunningPlan plan = state.plan();
        if (plan.status() != RunningPlan.Status.RUNNING || state.halted()) return new Result(state, List.of());
        RunningPlan.StageState stage = currentStage(plan).orElseThrow(); // RUNNING: one unfinished
        if (!stage.launched()) return new Result(state, List.of()); // awaiting the launch report
        List<TaskId> inReview = new ArrayList<>();
        boolean restTerminal = true;
        for (TaskId task : stage.tasks()) {
            TaskStatus status = state.statuses().get(task);
            if (status == TaskStatus.REVIEW) inReview.add(task);
            else if (status != TaskStatus.DONE && status != TaskStatus.DROPPED) restTerminal = false;
        }
        if (!inReview.isEmpty() && restTerminal) { // the review is all that holds the stage (D16)
            List<Effect> waiting = new ArrayList<>();
            for (TaskId task : inReview) waiting.add(new AwaitingConfirmation(task, stage.stage()));
            return new Result(state, waiting);
        }
        if (!restTerminal) return new Result(state, List.of()); // open work, besides any review
        boolean lastStage = stage.stage() >= plan.stages().size();
        RunningPlan finished = withStage(plan, stage.stage(), stage.launched(), true, lastStage
                ? RunningPlan.Status.COMPLETED : plan.status());
        if (lastStage) return new Result(new State(finished, state.statuses(), state.halted(),
                state.launchAttempts()), List.of(new PlanCompleted()));
        return launchStage(new State(finished, state.statuses(), state.halted(), state.launchAttempts()),
                stage.stage() + 1);
    }

    /** Emits one stage's launch signal exactly once; a duplicate or reordered entry is a no-op. */
    private static Result launchStage(State state, int number) {
        RunningPlan plan = state.plan();
        if (number < 1 || number > plan.stages().size()) return refused(state, "stage " + number
                + " does not exist; this plan has " + plan.stages().size() + " stages");
        RunningPlan.StageState stage = plan.stages().get(number - 1);
        if (stage.launched() || state.launchAttempts().contains(number)) return new Result(state, List.of());
        Set<Integer> attempts = new HashSet<>(state.launchAttempts());
        attempts.add(number);
        return new Result(new State(plan, state.statuses(), state.halted(), attempts),
                List.of(new LaunchStage(number, stage.tasks(), plan.cancellationGeneration())));
    }

    /** Why a launch report is not a launch, with a concrete reason for each outcome. */
    private static String notALaunchReason(LaunchReported event, RunningPlan.StageState stage) {
        String detail = event.failure() == null ? "" : ": " + event.failure();
        return switch (event.outcome()) {
            case PARTIAL, COVERED -> "the launch of stage " + event.stage() + " started " + event.tasks()
                    + " instead of the stage's tasks " + stage.tasks() + "; a stage launch that did"
                    + " not cover all its tasks is not a launch";
            case OVERLAPPING_OWNERSHIP -> "the launch of stage " + event.stage() + " overlaps"
                    + " ownership outside the confirmed plan" + detail + "; it is refused, not a launch";
            case FAILED -> "the launch of stage " + event.stage() + " failed" + detail
                    + "; a failed launch is not a launch";
        };
    }

    /** The refusal for a revision-carrying event off the plan's graph/policy revision; null when it matches. */
    private static String revisionMismatch(State state, long revision) {
        long expected = state.plan().launch().graphRevision();
        return revision == expected ? null : "graph/policy revision " + revision + " does not match"
                + " the plan's " + expected + "; the event is refused, the state is unchanged";
    }

    private static Result refused(State state, String reason) { return new Result(state, List.of(new Refused(reason))); }

    private static Optional<RunningPlan.StageState> currentStage(RunningPlan plan) {
        return plan.stages().stream().filter(stage -> !stage.finished()).findFirst();
    }

    private static Optional<RunningPlan.StageState> stageOf(RunningPlan plan, TaskId task) {
        return plan.stages().stream().filter(stage -> stage.tasks().contains(task)).findFirst();
    }

    private static Set<Integer> launchedAttempts(RunningPlan plan, Set<Integer> attempts) {
        Set<Integer> kept = new HashSet<>();
        for (RunningPlan.StageState stage : plan.stages()) {
            if (stage.launched() && attempts.contains(stage.stage())) kept.add(stage.stage());
        }
        return kept;
    }

    private static RunningPlan withStage(RunningPlan plan, int number, boolean launched,
            boolean finished, RunningPlan.Status status) {
        List<RunningPlan.StageState> stages = new ArrayList<>();
        for (RunningPlan.StageState stage : plan.stages()) {
            stages.add(stage.stage() == number ? new RunningPlan.StageState(stage.stage(),
                    stage.tasks(), launched, finished) : stage);
        }
        return new RunningPlan(plan.launch(), stages, status);
    }

    private PlanProgress() { }
}
