package app.drydock.mcp;

import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonBoolean;
import app.drydock.state.json.JsonValue.JsonNumber;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Terse accessors and builders over the in-repo sealed {@code JsonValue}. */
final class JsonPeek {

    private JsonPeek() {
    }

    static JsonValue field(JsonValue value, String key) {
        return ((JsonObject) value).get(key);
    }

    static List<JsonValue> array(JsonValue value, String key) {
        return ((JsonArray) field(value, key)).elements();
    }

    static String str(JsonValue value, String key) {
        return ((JsonString) field(value, key)).value();
    }

    static int num(JsonValue value, String key) {
        return ((JsonNumber) field(value, key)).asInt();
    }

    static boolean bool(JsonValue value, String key) {
        return ((JsonBoolean) field(value, key)).value();
    }

    /** Builds a flat string-valued argument object; most tool arguments are strings. */
    static JsonObject args(String... keysAndValues) {
        Map<String, JsonValue> members = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            members.put(keysAndValues[i], new JsonString(keysAndValues[i + 1]));
        }
        return new JsonObject(members);
    }

    /** As {@link #args}, plus one boolean member. */
    static JsonObject argsWithFlag(String flagKey, boolean flag, String... keysAndValues) {
        JsonObject object = args(keysAndValues);
        return object.put(flagKey, new JsonBoolean(flag));
    }

    static JsonObject noArgs() {
        return JsonObject.empty();
    }
}
