package app.cpm.state.json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A minimal, dependency-free JSON document model.
 *
 * <p>This is intentionally tiny: only what {@link app.cpm.state.JsonApplicationStateRepository}
 * needs to read and write the application state file (plan section 17). Per
 * plan rule 27.16, a small hand-rolled JSON reader/writer was chosen over
 * pulling in a JSON library dependency (Jackson/Gson/org.json) because the
 * schema is small, fully controlled by this application, and does not need
 * annotation-driven binding, streaming, or schema validation -- the things
 * a general-purpose JSON library would actually buy over ~200 lines of
 * hand-written parsing/writing code.</p>
 */
public sealed interface JsonValue {

    record JsonObject(Map<String, JsonValue> members) implements JsonValue {
        public JsonObject {
            members = new LinkedHashMap<>(Objects.requireNonNull(members, "members"));
        }

        public static JsonObject empty() {
            return new JsonObject(new LinkedHashMap<>());
        }

        public JsonObject put(String key, JsonValue value) {
            members.put(key, value);
            return this;
        }

        public JsonValue get(String key) {
            return members.get(key);
        }

        public boolean has(String key) {
            return members.containsKey(key);
        }
    }

    record JsonArray(List<JsonValue> elements) implements JsonValue {
        public JsonArray {
            elements = List.copyOf(Objects.requireNonNull(elements, "elements"));
        }

        public static JsonArray of(List<JsonValue> elements) {
            return new JsonArray(elements);
        }
    }

    record JsonString(String value) implements JsonValue {
        public JsonString {
            Objects.requireNonNull(value, "value");
        }
    }

    record JsonNumber(String literal) implements JsonValue {
        public JsonNumber {
            Objects.requireNonNull(literal, "literal");
        }

        public static JsonNumber of(long value) {
            return new JsonNumber(Long.toString(value));
        }

        public static JsonNumber of(double value) {
            return new JsonNumber(Double.toString(value));
        }

        public long asLong() {
            return Long.parseLong(literal);
        }

        public int asInt() {
            return Integer.parseInt(literal);
        }

        public double asDouble() {
            return Double.parseDouble(literal);
        }
    }

    record JsonBoolean(boolean value) implements JsonValue {
    }

    record JsonNull() implements JsonValue {
        public static final JsonNull INSTANCE = new JsonNull();
    }
}
