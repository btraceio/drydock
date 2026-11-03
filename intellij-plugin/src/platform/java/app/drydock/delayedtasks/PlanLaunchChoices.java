package app.drydock.delayedtasks;

import app.drydock.domain.RepositoryId;
import app.drydock.intellij.tasks.NewTaskPlan.Choice;
import app.drydock.tasks.DoneBy;
import app.drydock.tasks.TaskId;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The launch choices the plan dialog froze when the user confirmed (T37a,
 * D8/P4.5): the per-SEGMENT launch choice -- agent, model, subagent tiers,
 * mode, branch, directory and window, exactly the value the existing New
 * Session dialog's Configure action returned -- the confirmed done_by of
 * every planned task (the agent-may-close checkboxes, D16), and the stage
 * numbers the confirmation covers.
 *
 * <p>Immutable after close, the {@link NewDelayedTaskDialog.Result}
 * precedent: every collection is defensively copied with
 * {@link List#copyOf}/{@link Map#copyOf}, so mutating the maps a caller
 * passed in, or the ones it reads back, changes nothing the confirmation
 * froze. The record carries VALUES only: a {@link Choice} is a plain value,
 * the done_by is the stored policy word, and no live Project, Inputs,
 * window or executor ever travels here -- execution rebuilds those (P4.5:
 * the dialog only produces choices; launching is the launch
 * chain's business, never this value's).</p>
 */
public record PlanLaunchChoices(
        TaskId target,
        List<Integer> confirmedStages,
        Map<RepositoryId, Choice> segments,
        Map<TaskId, DoneBy> doneBy) {

    public PlanLaunchChoices {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(confirmedStages, "confirmedStages");
        Objects.requireNonNull(segments, "segments");
        Objects.requireNonNull(doneBy, "doneBy");
        int previous = 0;
        for (Integer stage : confirmedStages) {
            if (stage == null) {
                throw new IllegalArgumentException("Confirmed stages must not contain null entries");
            }
            if (stage <= previous) {
                throw new IllegalArgumentException("Confirmed stage indices must be positive and strictly"
                        + " increasing (stage numbers start at 1), got: " + stage);
            }
            previous = stage;
        }
        for (Choice choice : segments.values()) {
            if (choice == null) {
                throw new IllegalArgumentException("A segment's launch choice must not be null");
            }
        }
        for (DoneBy policy : doneBy.values()) {
            if (policy == null) {
                throw new IllegalArgumentException("The confirmed done_by must not be null");
            }
        }
        confirmedStages = List.copyOf(confirmedStages);
        segments = Map.copyOf(segments);
        doneBy = Map.copyOf(doneBy);
    }

    /**
     * The confirmed done_by of one planned task. A task absent from the
     * map keeps its stored policy -- the dialog confirmed no change for it
     * -- and a missing stored policy defaults HUMAN, as every task does.
     */
    public DoneBy doneByOf(TaskId task, DoneBy stored) {
        return doneBy.getOrDefault(task, stored != null ? stored : DoneBy.HUMAN);
    }
}
