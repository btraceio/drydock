package app.drydock.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.RepositoryId;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonBoolean;
import app.drydock.state.json.JsonValue.JsonNumber;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import app.drydock.state.json.JsonWriter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The confirmed launch selections of a staged cross-project start (T32a,
 * D8/P4.5): what the plan dialog froze when the user confirmed, as an
 * immutable value that can be persisted, reloaded and resumed (P4.13).
 *
 * <p>Every field is a PLAIN STORED VALUE -- task ids, stage numbers, the
 * harness kind's persisted name, the repository identity as a
 * {@link RepositoryId}, the origin window and worktree as text. Project
 * objects, Inputs, function objects, MCP credentials and transient agent
 * availability are NEVER carried: they are reconstructed at execution, and
 * the codec's serialized surface is strings, numbers, booleans, lists and
 * objects only, which keeps that exclusion structural rather than
 * conventional.</p>
 *
 * <p>The codec is versioned (D8/P4.13): {@link #SCHEMA_VERSION} is written
 * into every serialized form and any other version is refused loudly --
 * never silently reinterpreted. Wire values are stable: keys are written in
 * one fixed order, {@link AgentKind#persistedName()} is the harness wire
 * contract, and the per-task selections are keyed by task id text in sorted
 * order, so serializing the same value twice yields identical bytes.
 * Unknown members inside a known version are ignored, matching the
 * application state codec's discipline; missing or wrongly typed known
 * members are refused, and so is every semantically invalid value (a bad
 * task id, an unknown agent kind, a negative revision or generation).</p>
 */
public record PlanLaunch(
        TaskId target,
        List<Integer> confirmedStages,
        Map<TaskId, Selection> selections,
        long graphRevision,
        List<String> launchIds,
        int cancellationGeneration,
        Origin origin) {

    /** The wire schema version this codec writes; any other is refused loudly. */
    public static final int SCHEMA_VERSION = 1;

    /** Bound on the confirmed stage indices and on the stage list of a plan. */
    public static final int MAX_STAGES = 128;

    /** Bound on any one list or map the launch carries. */
    public static final int MAX_ENTRIES = 4096;

    public PlanLaunch {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(confirmedStages, "confirmedStages");
        Objects.requireNonNull(selections, "selections");
        Objects.requireNonNull(launchIds, "launchIds");
        Objects.requireNonNull(origin, "origin");
        if (confirmedStages.size() > MAX_STAGES) {
            throw new IllegalArgumentException("A plan launches at most " + MAX_STAGES + " stages");
        }
        if (selections.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("A plan carries at most " + MAX_ENTRIES + " launch selections");
        }
        if (launchIds.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("A plan references at most " + MAX_ENTRIES + " launch receipts");
        }
        int previous = 0;
        for (Integer stage : confirmedStages) {
            if (stage == null) {
                throw new IllegalArgumentException("Confirmed stage indices must not contain null entries");
            }
            if (stage <= previous) {
                throw new IllegalArgumentException("Confirmed stage indices must be positive and strictly"
                        + " increasing (stage numbers start at 1), got: " + stage);
            }
            previous = stage;
        }
        for (Map.Entry<TaskId, Selection> entry : selections.entrySet()) {
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("Launch selection for '" + entry.getKey()
                        + "' must not be null");
            }
        }
        for (String launchId : launchIds) {
            if (launchId == null) {
                throw new IllegalArgumentException("Launch receipt ids must not contain null entries");
            }
        }
        if (graphRevision < 0) {
            throw new IllegalArgumentException("Graph/policy revision must not be negative, got: "
                    + graphRevision);
        }
        if (cancellationGeneration < 0) {
            throw new IllegalArgumentException("Cancellation generation must not be negative, got: "
                    + cancellationGeneration);
        }
        confirmedStages = List.copyOf(confirmedStages);
        selections = Map.copyOf(selections);
        launchIds = List.copyOf(launchIds);
    }

    /** One task's launch selection, exactly as the plan dialog froze it: the
      * chosen harness kind plus the task's repository identity as STORED values
      * (the repository id and the display slug), never live objects. */
    public record Selection(AgentKind agent, RepositoryId repository, String projectName) {

        public Selection {
            Objects.requireNonNull(agent, "agent");
            Objects.requireNonNull(repository, "repository");
            if (projectName == null || projectName.isBlank()) {
                throw new IllegalArgumentException("A launch selection needs the project's display slug");
            }
        }
    }

    /** The origin the plan was started from (P4.9): the window the sessions
      * open in and the worktree the plan belongs to, recorded as plain
      * provenance text -- ids and paths, never window or project objects. */
    public record Origin(String windowId, Path worktree) {

        public Origin {
            if (windowId == null || windowId.isBlank()) {
                throw new IllegalArgumentException("The origin window id is required provenance");
            }
            Objects.requireNonNull(worktree, "worktree");
            worktree = worktree.toAbsolutePath().normalize();
        }
    }

    /** Copy helper: the same launch with another cancellation generation --
     * the counter incremented on cancel so stale effects can be detected
     * (P4.12). Pure copy, no decision; the progression rule is T34. */
    public PlanLaunch withCancellationGeneration(int generation) {
        return new PlanLaunch(target, confirmedStages, selections, graphRevision, launchIds, generation,
                origin);
    }

    /** The serialized form: a self-describing JSON object with fixed key
      * order and sorted selection keys, so the same value always renders
      * byte-identically. */
    public JsonValue toJsonValue() {
        JsonObject root = JsonObject.empty()
                .put("schemaVersion", JsonNumber.of(SCHEMA_VERSION))
                .put("target", new JsonString(target.text()))
                .put("graphRevision", JsonNumber.of(graphRevision))
                .put("confirmedStages", new JsonArray(confirmedStages.stream()
                        .<JsonValue>map(stage -> JsonNumber.of(stage.longValue())).toList()))
                .put("launchIds", new JsonArray(
                        launchIds.stream().<JsonValue>map(JsonString::new).toList()))
                .put("cancellationGeneration", JsonNumber.of(cancellationGeneration));
        JsonObject selectionObject = JsonObject.empty();
        List<TaskId> ids = new ArrayList<>(selections.keySet());
        ids.sort(Comparator.comparing(TaskId::text));
        for (TaskId id : ids) {
            Selection selection = selections.get(id);
            selectionObject.put(id.text(), JsonObject.empty()
                    .put("agent", new JsonString(selection.agent().persistedName()))
                    .put("repository", new JsonString(selection.repository().toString()))
                    .put("projectName", new JsonString(selection.projectName())));
        }
        root.put("selections", selectionObject);
        root.put("origin", JsonObject.empty()
                .put("windowId", new JsonString(origin.windowId()))
                .put("worktree", new JsonString(origin.worktree().toString())));
        return root;
    }

    /** The serialized form as text (a trailing newline, like every core
      * JSON document this codebase writes). */
    public String toJson() {
        return JsonWriter.write(toJsonValue());
    }

    /** Parses the serialized form; every refusal is loud. */
    public static PlanLaunch fromJson(String text) {
        Objects.requireNonNull(text, "text");
        return fromJsonValue(JsonParser.parse(text));
    }

    /** Parses the serialized form from an already parsed JSON tree. */
    public static PlanLaunch fromJsonValue(JsonValue value) {
        JsonObject root = requireObject(value, "a plan launch");
        requireVersion(root, "plan launch", SCHEMA_VERSION);
        List<Integer> stages = new ArrayList<>();
        for (JsonValue stage : requireArray(root, "confirmedStages")) {
            stages.add(requireIntValue(stage, "a confirmed stage index"));
        }
        JsonObject selectionObject = requireObject(requireMember(root, "selections"), "launch selections");
        Map<TaskId, Selection> selections = new LinkedHashMap<>();
        for (Map.Entry<String, JsonValue> entry : selectionObject.members().entrySet()) {
            selections.put(TaskId.of(entry.getKey()), selection(entry.getValue()));
        }
        List<String> launchIds = new ArrayList<>();
        for (JsonValue launchId : requireArray(root, "launchIds")) {
            if (!(launchId instanceof JsonString text)) {
                throw new IllegalArgumentException("Launch receipt ids must be strings");
            }
            launchIds.add(text.value());
        }
        return new PlanLaunch(
                TaskId.of(requireString(root, "target")),
                stages,
                selections,
                requireLong(root, "graphRevision"),
                launchIds,
                requireInt(root, "cancellationGeneration"),
                origin(requireObject(requireMember(root, "origin"), "an origin window")));
    }

    private static Selection selection(JsonValue value) {
        JsonObject object = requireObject(value, "a launch selection");
        String agentWire = requireString(object, "agent");
        AgentKind agent = AgentKind.fromPersisted(agentWire)
                .orElseThrow(() -> new IllegalArgumentException("Unrecognized agent kind wire value: '"
                        + agentWire + "'"));
        return new Selection(agent, RepositoryId.of(requireString(object, "repository")),
                requireString(object, "projectName"));
    }

    private static Origin origin(JsonObject object) {
        return new Origin(requireString(object, "windowId"), Path.of(requireString(object, "worktree")));
    }

    /** Loud version guard: any schemaVersion other than the expected one is
      * refused, never guessed at. */
    static void requireVersion(JsonObject root, String what, int expected) {
        JsonValue value = root.get("schemaVersion");
        if (!(value instanceof JsonNumber number)) {
            throw new IllegalArgumentException("Missing or non-numeric schemaVersion for " + what);
        }
        int version;
        try {
            version = number.asInt();
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Non-integer schemaVersion for " + what + ": "
                    + number.literal());
        }
        if (version != expected) {
            throw new IllegalArgumentException("Unsupported " + what + " schemaVersion: " + version
                    + " (expected " + expected + ")");
        }
    }

    static JsonObject requireObject(JsonValue value, String what) {
        if (!(value instanceof JsonObject object)) {
            throw new IllegalArgumentException("Expected a JSON object for " + what);
        }
        return object;
    }

    static JsonValue requireMember(JsonObject object, String key) {
        JsonValue value = object.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing field: " + key);
        }
        return value;
    }

    static List<JsonValue> requireArray(JsonObject object, String key) {
        JsonValue value = requireMember(object, key);
        if (!(value instanceof JsonArray array)) {
            throw new IllegalArgumentException("Expected a JSON array for " + key);
        }
        return array.elements();
    }

    static String requireString(JsonObject object, String key) {
        JsonValue value = requireMember(object, key);
        if (!(value instanceof JsonString string)) {
            throw new IllegalArgumentException("Missing or non-string field: " + key);
        }
        return string.value();
    }

    static long requireLong(JsonObject object, String key) {
        JsonValue value = requireMember(object, key);
        if (!(value instanceof JsonNumber number)) {
            throw new IllegalArgumentException("Missing or non-numeric field: " + key);
        }
        try {
            return number.asLong();
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Field " + key + " is not an integer: " + number.literal());
        }
    }

    static int requireInt(JsonObject object, String key) {
        long value = requireLong(object, key);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Field " + key + " does not fit an int: " + value);
        }
        return (int) value;
    }

    static boolean requireBoolean(JsonObject object, String key) {
        JsonValue value = requireMember(object, key);
        if (!(value instanceof JsonBoolean bool)) {
            throw new IllegalArgumentException("Missing or non-boolean field: " + key);
        }
        return bool.value();
    }

    static int requireIntValue(JsonValue value, String what) {
        if (!(value instanceof JsonNumber number)) {
            throw new IllegalArgumentException("Expected a JSON number for " + what);
        }
        try {
            return number.asInt();
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not an integer " + what + ": " + number.literal());
        }
    }
}
