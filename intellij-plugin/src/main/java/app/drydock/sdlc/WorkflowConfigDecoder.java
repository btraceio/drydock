package app.drydock.sdlc;

import app.drydock.sdlc.WorkflowResolution.ProblemCategory;
import app.drydock.sdlc.WorkflowResolution.WorkflowProblem;
import app.drydock.state.json.JsonParseException;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonNull;
import app.drydock.state.json.JsonValue.JsonNumber;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Pure decoder for the text of {@code .sdlc/config.json} (version 1). It reads
 * no file, expands nothing and never consults installed skills: the caller
 * supplies text it proved exists. The document and its namespace containers
 * are validated structurally for every target (a malformed root invalidates
 * every consuming invocation); only the selected leaf's definition is
 * validated, so an invalid unrelated leaf never blocks a valid selected one.
 */
public final class WorkflowConfigDecoder {

    static final int MAX_DEPTH = 16;
    private static final String ROOT = "config";
    private static final Set<String> ROOT_KEYS = Set.of("version", "newTask", "actions");
    private static final Set<String> LEAF_KEYS = Set.of("skill", "file", "text", "include");

    private WorkflowConfigDecoder() {
    }

    /** Which source a selected leaf declares. */
    public enum SourceType { SKILL, FILE, TEXT }

    /** A validated, not yet resolved leaf: {@code value} is a repository-relative path or literal text. */
    public record Definition(String configKey, SourceType type, String value, List<String> include) {
        public Definition {
            Objects.requireNonNull(configKey, "configKey");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(value, "value");
            include = List.copyOf(include);
        }
    }

    public sealed interface Decoded {
        /** The omitted container/leaf: the caller's default applies. */
        record Missing(String reason) implements Decoded {
        }

        record Leaf(Definition definition) implements Decoded {
        }

        /** Never to be treated as {@link Missing}. */
        record Invalid(WorkflowProblem problem) implements Decoded {
        }
    }

    /** The configuration key path of a target's leaf. */
    public static String configKey(WorkflowTarget target) {
        return switch (target) {
            case FREE_FORM_PRE_PROMPT -> "newTask.prePrompt";
            case ISSUE_IMPLEMENT -> "actions.issue.implement";
            case ISSUE_TRIAGE -> "actions.issue.triage";
            case PULL_REQUEST_REVIEW -> "actions.pullRequest.review";
            case LOCAL_CHANGES_REVIEW -> "actions.localChanges.review";
        };
    }

    public static Decoded decode(String configText, WorkflowTarget target) {
        Objects.requireNonNull(target, "target");
        String key = configKey(target);
        JsonValue root;
        try {
            root = JsonParser.parseStrict(configText, MAX_DEPTH);
        } catch (JsonParseException e) {
            String m = String.valueOf(e.getMessage());
            ProblemCategory c = m.toLowerCase().contains("duplicate") ? ProblemCategory.DUPLICATE
                    : m.toLowerCase().contains("depth") ? ProblemCategory.LIMIT_EXCEEDED : ProblemCategory.SCHEMA;
            return invalid(ROOT, c, "config.json is not valid strict JSON: " + m);
        }
        if (!(root instanceof JsonObject obj)) {
            return invalid(ROOT, ProblemCategory.TYPE, "config.json must be an object");
        }
        Optional<Decoded.Invalid> bad = unknownKeys(ROOT, obj, ROOT_KEYS);
        if (bad.isPresent()) {
            return bad.get();
        }
        JsonValue version = obj.get("version");
        if (version == null) {
            return invalid("version", ProblemCategory.VERSION, "version is required");
        }
        if (!(version instanceof JsonNumber n)) {
            return invalid("version", ProblemCategory.TYPE, "version must be the integer 1");
        }
        if (!n.literal().matches("-?(0|[1-9][0-9]*)")) {
            return invalid("version", ProblemCategory.TYPE, "version must be an integer");
        }
        if (!n.literal().equals("1")) {
            return invalid("version", ProblemCategory.VERSION, "unsupported version " + n.literal());
        }
        // Structural check of every present namespace container, independent of the target.
        Optional<Decoded.Invalid> structure = container("newTask", obj.get("newTask"), Set.of("prePrompt"));
        if (structure.isEmpty()) {
            structure = container("actions", obj.get("actions"), Set.of("issue", "pullRequest", "localChanges"));
        }
        JsonValue actions = obj.get("actions");
        if (structure.isEmpty() && actions instanceof JsonObject a) {
            structure = container("actions.issue", a.get("issue"), Set.of("implement", "triage"));
            if (structure.isEmpty()) {
                structure = container("actions.pullRequest", a.get("pullRequest"), Set.of("review"));
            }
            if (structure.isEmpty()) {
                structure = container("actions.localChanges", a.get("localChanges"), Set.of("review"));
            }
        }
        if (structure.isPresent()) {
            return structure.get();
        }
        JsonValue leaf = obj;
        for (String part : key.split("\\.")) {
            leaf = leaf instanceof JsonObject o ? o.get(part) : null;
            if (leaf == null) {
                return new Decoded.Missing(key + " is not configured");
            }
        }
        return leaf(key, leaf);
    }

    private static Optional<Decoded.Invalid> container(String path, JsonValue v, Set<String> allowed) {
        if (v == null) {
            return Optional.empty();
        }
        if (!(v instanceof JsonObject o)) {
            return Optional.of((Decoded.Invalid) invalid(path, ProblemCategory.TYPE, path + " must be an object"));
        }
        return unknownKeys(path, o, allowed);
    }

    private static Optional<Decoded.Invalid> unknownKeys(String path, JsonObject o, Set<String> allowed) {
        for (String k : o.members().keySet()) {
            if (!allowed.contains(k)) {
                return Optional.of((Decoded.Invalid) invalid(path, ProblemCategory.UNKNOWN_FIELD,
                        "unknown key '" + k + "' in " + path));
            }
        }
        return Optional.empty();
    }

    private static Decoded leaf(String key, JsonValue v) {
        if (v instanceof JsonNull || !(v instanceof JsonObject o)) {
            return invalid(key, ProblemCategory.TYPE, key + " must be an object");
        }
        Optional<Decoded.Invalid> unknown = unknownKeys(key, o, LEAF_KEYS);
        if (unknown.isPresent()) {
            return unknown.get();
        }
        List<SourceType> present = new ArrayList<>();
        for (SourceType t : SourceType.values()) {
            if (o.has(t.name().toLowerCase())) {
                present.add(t);
            }
        }
        if (present.size() != 1) {
            return invalid(key, ProblemCategory.SCHEMA, key + " must declare exactly one of skill, file or text");
        }
        SourceType type = present.get(0);
        JsonValue sv = o.get(type.name().toLowerCase());
        if (!(sv instanceof JsonString s)) {
            return invalid(key, ProblemCategory.TYPE, type.name().toLowerCase() + " must be a string");
        }
        if (s.value().isBlank()) {
            return invalid(key, ProblemCategory.BLANK, type.name().toLowerCase() + " must not be blank");
        }
        if (type == SourceType.SKILL && !s.value().substring(s.value().lastIndexOf('/') + 1).equals("SKILL.md")) {
            return invalid(key, ProblemCategory.UNSUPPORTED_SOURCE, "skill must name a SKILL.md file");
        }
        List<String> include = new ArrayList<>();
        if (o.has("include")) {
            if (!(o.get("include") instanceof JsonArray arr)) {
                return invalid(key, ProblemCategory.TYPE, "include must be an array");
            }
            for (JsonValue e : arr.elements()) {
                if (!(e instanceof JsonString es)) {
                    return invalid(key, ProblemCategory.TYPE, "include entries must be strings");
                }
                if (es.value().isBlank()) {
                    return invalid(key, ProblemCategory.BLANK, "include entries must not be blank");
                }
                include.add(es.value());
            }
        }
        return new Decoded.Leaf(new Definition(key, type, s.value(), include));
    }

    private static Decoded invalid(String key, ProblemCategory c, String message) {
        return new Decoded.Invalid(new WorkflowProblem(key, Optional.empty(), c, message));
    }
}
