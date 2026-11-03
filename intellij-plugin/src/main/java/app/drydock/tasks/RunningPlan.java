package app.drydock.tasks;

import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonBoolean;
import app.drydock.state.json.JsonValue.JsonNumber;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import app.drydock.state.json.JsonWriter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The persisted state of one running staged plan (T32a, D8/P4.13): the
 * confirmed {@link PlanLaunch}, the per-stage segment state, and the
 * plan-level status with the cancellation generation. This is the portable
 * state the plan store (T33) writes under {@code ~/.drydock/tasks/plans/}
 * -- a value type, deliberately distinct from the Workflow grouping type.
 *
 * <p>Per stage, the state carries the stage number, the task ids that make
 * up the stage's segments, and whether that stage has been launched and has
 * finished; "confirmed" is membership in the launch's confirmed stage
 * indices ({@link #confirmed(int)}). The status is RUNNING while stages
 * remain, COMPLETED only when every stage has finished, or CANCELED; the
 * cancellation generation rides on the launch, incremented on cancel so
 * stale cancel effects can be detected (P4.12).</p>
 *
 * <p>The codec is versioned and loud, mirroring {@link PlanLaunch}: an
 * unknown schema version is refused, and so is every semantically invalid
 * plan -- a target task that is not a task of any stage, a confirmed or
 * listed stage outside the plan's stages, non-contiguous stage numbering, a
 * task in two stages, a stage without a launch selection (the dialog fixes
 * the choices of every segment, D8), a finished stage that was never
 * launched, a status that contradicts the flags. Lists are copied
 * defensively on construction and after parsing; nothing mutable escapes.</p>
 */
public record RunningPlan(PlanLaunch launch, List<StageState> stages, Status status) {

    /** The wire schema version this codec writes; any other is refused loudly. */
    public static final int SCHEMA_VERSION = 1;

    /** Bound on the stages of one plan (mirrors {@link PlanLaunch#MAX_STAGES}). */
    public static final int MAX_STAGES = PlanLaunch.MAX_STAGES;

    /** Bound on the total task ids across all stages. */
    public static final int MAX_TASKS = 4096;

    public RunningPlan {
        Objects.requireNonNull(launch, "launch");
        Objects.requireNonNull(stages, "stages");
        Objects.requireNonNull(status, "status");
        if (stages.size() > MAX_STAGES) {
            throw new IllegalArgumentException("A plan has at most " + MAX_STAGES + " stages");
        }
        List<StageState> copy = new ArrayList<>(stages.size());
        for (StageState stage : stages) {
            copy.add(Objects.requireNonNull(stage, "stage"));
        }
        Set<TaskId> tasks = new HashSet<>();
        for (int i = 0; i < copy.size(); i++) {
            if (copy.get(i).stage() != i + 1) {
                throw new IllegalArgumentException("Stage numbers must be contiguous from 1; stage "
                        + (i + 1) + " is numbered " + copy.get(i).stage());
            }
            for (TaskId task : copy.get(i).tasks()) {
                if (!tasks.add(task)) {
                    throw new IllegalArgumentException("Task '" + task + "' appears in more than one stage");
                }
                if (!launch.selections().containsKey(task)) {
                    throw new IllegalArgumentException("Task '" + task + "' has no launch selection;"
                            + " the plan dialog fixes the choice of every segment (D8)");
                }
            }
        }
        if (tasks.size() > MAX_TASKS) {
            throw new IllegalArgumentException("A plan carries at most " + MAX_TASKS + " tasks");
        }
        if (!tasks.contains(launch.target())) {
            throw new IllegalArgumentException("Plan target '" + launch.target()
                    + "' is not a task of any stage");
        }
        for (int confirmed : launch.confirmedStages()) {
            if (confirmed > copy.size()) {
                throw new IllegalArgumentException("Confirmed stage " + confirmed + " is out of range;"
                        + " the plan has " + copy.size() + " stages");
            }
        }
        boolean allFinished = true;
        for (StageState stage : copy) {
            if (stage.finished() && !stage.launched()) {
                throw new IllegalArgumentException("Stage " + stage.stage()
                        + " is finished but was never launched");
            }
            allFinished &= stage.finished();
        }
        if (status == Status.COMPLETED && !allFinished) {
            throw new IllegalArgumentException("A completed plan has every stage finished");
        }
        if (status == Status.RUNNING && allFinished) {
            throw new IllegalArgumentException("A plan with every stage finished is not running");
        }
        stages = List.copyOf(copy);
    }

    /** The plan-level status (D8/P4.7). Wire names are a stable contract. */
    public enum Status {
        RUNNING("running"), COMPLETED("completed"), CANCELED("canceled");

        private final String wireName;

        Status(String wireName) {
            this.wireName = wireName;
        }

        /** The persisted wire value; never rename an existing one. */
        public String wireName() {
            return wireName;
        }

        static Status fromWire(String wire) {
            for (Status status : values()) {
                if (status.wireName.equals(wire)) {
                    return status;
                }
            }
            throw new IllegalArgumentException("Unrecognized running-plan status wire value: '" + wire + "'");
        }
    }

    /** One stage's state: its number (contiguous from 1), its task ids, and
      * whether it has been launched and has finished. */
    public record StageState(int stage, List<TaskId> tasks, boolean launched, boolean finished) {

        public StageState {
            if (stage < 1) {
                throw new IllegalArgumentException("Stage numbers start at 1, got: " + stage);
            }
            Objects.requireNonNull(tasks, "tasks");
            if (tasks.isEmpty()) {
                throw new IllegalArgumentException("A stage holds at least one task");
            }
            for (TaskId task : tasks) {
                Objects.requireNonNull(task, "task");
            }
            tasks = List.copyOf(tasks);
        }
    }

    /** Whether the user confirmed launching the given stage number. */
    public boolean confirmed(int stage) {
        return launch.confirmedStages().contains(stage);
    }

    /** The cancellation generation this plan's effects must match. */
    public int cancellationGeneration() {
        return launch.cancellationGeneration();
    }

    /** Copy helper: the same plan canceled, with the launch's cancellation
      * generation incremented so stale effects can be detected (P4.12). Pure
      * copy, no decision; stage progression is the T34 reducer. */
    public RunningPlan canceled() {
        return new RunningPlan(launch.withCancellationGeneration(launch.cancellationGeneration() + 1),
                stages, Status.CANCELED);
    }

    /** The serialized form: fixed key order, stable wire values, so the
      * same plan always renders byte-identically. */
    public JsonValue toJsonValue() {
        List<JsonValue> stageObjects = new ArrayList<>(stages.size());
        for (StageState stage : stages) {
            stageObjects.add(JsonObject.empty()
                    .put("stage", JsonNumber.of(stage.stage()))
                    .put("tasks", new JsonArray(stage.tasks().stream()
                            .<JsonValue>map(task -> new JsonString(task.text())).toList()))
                    .put("launched", new JsonBoolean(stage.launched()))
                    .put("finished", new JsonBoolean(stage.finished())));
        }
        return JsonObject.empty()
                .put("schemaVersion", JsonNumber.of(SCHEMA_VERSION))
                .put("status", new JsonString(status.wireName()))
                .put("launch", launch.toJsonValue())
                .put("stages", new JsonArray(stageObjects));
    }

    /** The serialized form as text (a trailing newline, like every core
      * JSON document this codebase writes). */
    public String toJson() {
        return JsonWriter.write(toJsonValue());
    }

    /** Parses the serialized form; every refusal is loud. */
    public static RunningPlan fromJson(String text) {
        Objects.requireNonNull(text, "text");
        return fromJsonValue(JsonParser.parse(text));
    }

    /** Parses the serialized form from an already parsed JSON tree. */
    public static RunningPlan fromJsonValue(JsonValue value) {
        JsonObject root = PlanLaunch.requireObject(value, "a running plan");
        PlanLaunch.requireVersion(root, "running plan", SCHEMA_VERSION);
        Status status = Status.fromWire(PlanLaunch.requireString(root, "status"));
        PlanLaunch launch = PlanLaunch.fromJsonValue(PlanLaunch.requireMember(root, "launch"));
        List<StageState> stages = new ArrayList<>();
        for (JsonValue stage : PlanLaunch.requireArray(root, "stages")) {
            JsonObject object = PlanLaunch.requireObject(stage, "a plan stage");
            List<TaskId> tasks = new ArrayList<>();
            for (JsonValue task : PlanLaunch.requireArray(object, "tasks")) {
                if (!(task instanceof JsonString string)) {
                    throw new IllegalArgumentException("Stage task entries must be strings");
                }
                tasks.add(TaskId.of(string.value()));
            }
            stages.add(new StageState(PlanLaunch.requireInt(object, "stage"), tasks,
                    PlanLaunch.requireBoolean(object, "launched"),
                    PlanLaunch.requireBoolean(object, "finished")));
        }
        return new RunningPlan(launch, stages, status);
    }
}
