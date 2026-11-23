package app.drydock.mcp;

import app.drydock.tasks.TaskId;
import app.drydock.tasks.TaskStatus;
import app.drydock.state.json.JsonParseException;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonNull;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Decodes the task tools' arguments and builds their descriptors, keeping
 * the wire format and its bounds out of {@link TaskTools}' dispatch logic
 * (the same split {@link ReviewToolCodec} gives the review tools).
 *
 * <p>Every helper applies the router's argument rules: a required argument
 * may not be missing or blank, a present-but-wrong-typed argument is
 * refused instead of read as absent, and every refusal names the argument
 * and what would differ -- a malformed call is a visible refusal, never an
 * escaped exception. Sizes are bounded per argument kind, and the 'after'
 * list is accepted as a real array or as a JSON string holding one, the
 * same stringified-array tolerance the router gives its own array
 * arguments (models stringify nested JSON).</p>
 */
final class TaskToolCodec {

    /** A title is one bounded line (the id slug already caps at 60). */
    static final int MAX_TITLE_CHARS = 200;
    /** A session note or a create body is one paragraph (P7: one note, no breaks). */
    static final int MAX_PARAGRAPH_CHARS = 4_000;
    /** A project is named by a short display slug. */
    static final int MAX_PROJECT_CHARS = 100;
    /** After references are stored texts; a task needs few, so the batch is bounded. */
    static final int MAX_AFTER_REFS = 32;
    /** One after entry is a bare id or '<project>:<id>', both far below this. */
    static final int MAX_AFTER_REF_CHARS = 240;

    private TaskToolCodec() {
    }

    /** Required non-blank string argument. */
    static String requiredStringArg(JsonObject args, String key) throws McpToolException {
        Optional<String> value = optionalStringArg(args, key);
        if (value.isEmpty()) {
            throw new McpToolException("Missing required argument '" + key + "'.");
        }
        return value.get();
    }

    /** Optional string argument; blank or absent is absent, a wrong type is refused. */
    static Optional<String> optionalStringArg(JsonObject args, String key) throws McpToolException {
        if (!args.has(key)) {
            return Optional.empty();
        }
        if (!(args.get(key) instanceof JsonString string)) {
            throw new McpToolException("Argument '" + key + "' must be a string.");
        }
        String text = string.value();
        return text == null || text.isBlank() ? Optional.empty() : Optional.of(text);
    }

    /** The required one-line title (D14). */
    static String titleArg(JsonObject args) throws McpToolException {
        return boundedLine(requiredStringArg(args, "title"), "title", MAX_TITLE_CHARS);
    }

    /** An optional one-line project slug (D14). */
    static Optional<String> projectArg(JsonObject args) throws McpToolException {
        Optional<String> value = optionalStringArg(args, "project");
        return value.isEmpty() ? Optional.empty()
                : Optional.of(boundedLine(value.get(), "project", MAX_PROJECT_CHARS));
    }

    /** An optional one-paragraph note or body (P7), bounded; empty when absent. */
    static Optional<String> paragraphArg(JsonObject args, String key) throws McpToolException {
        Optional<String> value = optionalStringArg(args, key);
        return value.isEmpty() ? Optional.empty()
                : Optional.of(bounded(value.get(), key, "one paragraph without line breaks",
                        MAX_PARAGRAPH_CHARS));
    }

    /** One bounded line of the given kind, or a concrete refusal naming it. */
    private static String boundedLine(String text, String key, int maxChars) throws McpToolException {
        return bounded(text, key, "one line without line breaks", maxChars);
    }

    private static String bounded(String text, String key, String shape, int maxChars)
            throws McpToolException {
        for (int i = 0; i < text.length(); i++) {
            if (isBreak(text.charAt(i))) {
                throw new McpToolException("Argument '" + key + "' must be " + shape + ".");
            }
        }
        if (text.length() > maxChars) {
            throw new McpToolException("Argument '" + key + "' must be at most " + maxChars
                    + " characters; got " + text.length() + ".");
        }
        return text;
    }

    /** The line breaks the store itself refuses inside one note. */
    private static boolean isBreak(char c) {
        return c == '\n' || c == '\r' || c == '\u0085' || c == '\u2028' || c == '\u2029';
    }

    /** A required task id, refused concretely when the text is not one. */
    static TaskId idArg(JsonObject args) throws McpToolException {
        String text = requiredStringArg(args, "id");
        try {
            return TaskId.of(text);
        } catch (IllegalArgumentException e) {
            throw new McpToolException("Argument 'id' must be a task id (lowercase letters, digits"
                    + " and inner hyphens); got '" + text + "'.");
        }
    }

    /** A required status, one of the five stored words, refused concretely otherwise. */
    static TaskStatus statusArg(JsonObject args) throws McpToolException {
        String text = requiredStringArg(args, "status");
        try {
            return TaskStatus.fromStored(text);
        } catch (IllegalArgumentException e) {
            throw new McpToolException("Argument 'status' must be one of open, started, review,"
                    + " done or dropped; got '" + text + "'.");
        }
    }

    /**
     * The optional after references: a bounded array of one-line texts,
     * sent as a real array or as a JSON string holding one.
     */
    static List<String> afterArg(JsonObject args) throws McpToolException {
        JsonValue value = args.get("after");
        if (value == null || value instanceof JsonNull) {
            return List.of();
        }
        if (value instanceof JsonString text) {
            try {
                value = JsonParser.parse(text.value());
            } catch (JsonParseException e) {
                throw new McpToolException("after must be an array (or a JSON string holding one);"
                        + " the string is not valid JSON: " + e.getMessage());
            }
        }
        if (!(value instanceof JsonArray array)) {
            throw new McpToolException("Argument 'after' must be an array of task reference texts"
                    + " (bare ids or '<project>:<id>').");
        }
        List<JsonValue> elements = array.elements();
        if (elements.size() > MAX_AFTER_REFS) {
            throw new McpToolException("Argument 'after' names at most " + MAX_AFTER_REFS
                    + " prerequisites; got " + elements.size() + ".");
        }
        List<String> references = new ArrayList<>(elements.size());
        for (JsonValue element : elements) {
            if (!(element instanceof JsonString text)) {
                throw new McpToolException("Every entry of 'after' must be a string (a bare task"
                        + " id or '<project>:<id>').");
            }
            references.add(boundedLine(text.value(), "after", MAX_AFTER_REF_CHARS));
        }
        return List.copyOf(references);
    }

    /** The router's descriptor shape: name, description and a JSON-schema input. */
    static JsonValue descriptor(String name, String description, JsonObject properties,
                                String... required) {
        JsonObject schema = JsonObject.empty()
                .put("type", new JsonString("object"))
                .put("properties", properties);
        if (required.length > 0) {
            schema = schema.put("required", new JsonArray(Stream.of(required)
                    .map(argument -> (JsonValue) new JsonString(argument)).toList()));
        }
        return JsonObject.empty()
                .put("name", new JsonString(name))
                .put("description", new JsonString(description))
                .put("inputSchema", schema);
    }

    static JsonValue schemaString(String description) {
        return JsonObject.empty()
                .put("type", new JsonString("string"))
                .put("description", new JsonString(description));
    }

    static JsonValue schemaStringArray(String description) {
        return JsonObject.empty()
                .put("type", new JsonString("array"))
                .put("items", JsonObject.empty().put("type", new JsonString("string")))
                .put("description", new JsonString(description));
    }
}
