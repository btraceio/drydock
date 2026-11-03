package app.drydock.tasks;

import app.drydock.domain.RepositoryId;

import java.util.List;
import java.util.Objects;

/**
 * One start plan for a target task (T08a): the per-repository SEGMENTS of the
 * reachable unfinished prerequisite closure plus the target, each in dependency
 * order (D7); the segment DAG as STAGES (stage 1 waits on no open segment, the
 * target's own segment is the last, P4.4); the done and dropped prerequisites
 * as CONTEXT (documents, not work, D7/D13); the DIRECT follow-ups of the
 * target (out of scope, keep in mind, D3); and the refusal reasons when the
 * target cannot start. Computed by {@link StartPlanner}; immutable; titles and
 * ids only, no bodies (T09's prompt builder reads files itself).
 */
public record StartPlan(
        TaskId target,
        List<Segment> segments,
        List<Stage> stages,
        List<Context> context,
        List<FollowUp> followUps,
        List<Refusal> refusals
) {

    public StartPlan {
        Objects.requireNonNull(target, "target");
        segments = List.copyOf(segments);
        stages = List.copyOf(stages);
        context = List.copyOf(context);
        followUps = List.copyOf(followUps);
        refusals = List.copyOf(refusals);
    }

    /** The plan starts something: no refusal stands in the way (D10/D12). */
    public boolean startable() {
        return refusals.isEmpty();
    }

    /** One repository's share of the work: its tasks in dependency order (D7). */
    public record Segment(RepositoryId repository, String projectName, List<TaskDocument> tasks) {
        public Segment {
            Objects.requireNonNull(repository, "repository");
            Objects.requireNonNull(projectName, "projectName");
            tasks = List.copyOf(tasks);
        }
    }

    /** One parallel wave of segments (P4.4); the last stage holds only the target's segment. */
    public record Stage(int number, List<Segment> segments) {
        public Stage {
            if (number < 1) throw new IllegalArgumentException("Stage numbers start at 1, got: " + number);
            segments = List.copyOf(segments);
        }
    }

    /** A done or dropped prerequisite the target's session should know of, not redo (D7, D13). */
    public record Context(TaskId id, RepositoryId repository, String title) { }

    /** A direct follow-up of the target: out of scope, keep in mind (D3/D7). */
    public record FollowUp(TaskId id, RepositoryId repository, String title) { }

    /** Why a target cannot start; the reason is always concrete, never a silent skip (P3, D10, D12). */
    public record Refusal(Kind kind, String reason) {
        public Refusal {
            Objects.requireNonNull(kind, "kind");
            if (reason == null || reason.isBlank()) throw new IllegalArgumentException(
                    "a refusal reason is required, never a silent skip");
        }
    }

    /** What refused the start: an unknown target; one already done or dropped (it starts nothing, D12); a
      * broken reference or a task-level cycle in the planned work (P3); segments waiting on each other
      * although the task graph is acyclic (the project collapse, P4.3); or a planned task already owned
      * by a live session (D10/D12). */
    public enum Kind { TARGET_UNKNOWN, TARGET_FINISHED, BROKEN_REFERENCE, TASK_CYCLE, SEGMENT_CYCLE, OWNED_TASK }

    /** A refused plan: the target, every refusal found, and nothing else. */
    public static StartPlan refused(TaskId target, List<Refusal> refusals) {
        return new StartPlan(target, List.of(), List.of(), List.of(), List.of(), refusals);
    }
}
