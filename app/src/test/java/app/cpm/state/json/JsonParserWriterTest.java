package app.cpm.state.json;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonParserWriterTest {

    @Test
    void roundTripsObjectsArraysStringsNumbersBooleansAndNull() {
        JsonValue.JsonObject obj = JsonValue.JsonObject.empty();
        obj.put("name", new JsonValue.JsonString("hello \"world\"\nwith\ttab"));
        obj.put("count", JsonValue.JsonNumber.of(42));
        obj.put("ratio", JsonValue.JsonNumber.of(3.5));
        obj.put("enabled", new JsonValue.JsonBoolean(true));
        obj.put("nothing", JsonValue.JsonNull.INSTANCE);
        obj.put("items", new JsonValue.JsonArray(List.of(
                new JsonValue.JsonString("a"), new JsonValue.JsonString("b"))));

        String written = JsonWriter.write(obj);
        JsonValue parsed = JsonParser.parse(written);

        assertEquals(obj, parsed);
    }

    @Test
    void parsesUnicodeEscape() {
        JsonValue parsed = JsonParser.parse("\"\\u0041\\u00e9\"");
        assertEquals(new JsonValue.JsonString("Aé"), parsed);
    }

    @Test
    void rejectsTruncatedInput() {
        assertThrows(JsonParseException.class, () -> JsonParser.parse("{\"a\": [1, 2,"));
    }

    @Test
    void rejectsTrailingGarbage() {
        assertThrows(JsonParseException.class, () -> JsonParser.parse("{}garbage"));
    }

    @Test
    void rejectsUnterminatedString() {
        assertThrows(JsonParseException.class, () -> JsonParser.parse("\"unterminated"));
    }

    @Test
    void parsesEmptyObjectAndArray() {
        assertEquals(JsonValue.JsonObject.empty(), JsonParser.parse("{}"));
        assertEquals(new JsonValue.JsonArray(List.of()), JsonParser.parse("[]"));
    }

    @Test
    void rejectsPathologicallyDeepNestingWithoutStackOverflow() {
        // Untrusted input (transcripts, gh/GitHub output) must hit the depth
        // guard as a JsonParseException, never escape as StackOverflowError.
        int depth = 100_000;
        String deepArray = "[".repeat(depth) + "]".repeat(depth);
        assertThrows(JsonParseException.class, () -> JsonParser.parse(deepArray));

        String deepObject = "{\"a\":".repeat(depth) + "1" + "}".repeat(depth);
        assertThrows(JsonParseException.class, () -> JsonParser.parse(deepObject));
    }

    @Test
    void acceptsReasonableNesting() {
        int depth = 100;
        String nested = "[".repeat(depth) + "42" + "]".repeat(depth);
        assertTrue(JsonParser.parse(nested) instanceof JsonValue.JsonArray);
    }

    @Test
    void parsesNestedStructures() {
        JsonValue parsed = JsonParser.parse("{\"a\": {\"b\": [1, 2, 3]}}");
        assertTrue(parsed instanceof JsonValue.JsonObject);
    }
}
