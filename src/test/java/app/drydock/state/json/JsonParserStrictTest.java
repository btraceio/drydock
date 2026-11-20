package app.drydock.state.json;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonParserStrictTest {

    private static void rejects(String json) {
        assertThrows(JsonParseException.class, () -> JsonParser.parseStrict(json, 32), json);
    }

    private static String nested(int levels) {
        return "[".repeat(levels) + "]".repeat(levels);
    }

    @Test
    void acceptsValidDocumentSameAsLegacyParse() {
        String json = " {\"a\":[1,-0,0.5,1e+2,\"x\\u00e9\\ud83d\\ude00\"],\"b\":{\"c\":null,\"d\":true}}\r\n";
        assertEquals(JsonParser.parse(json), JsonParser.parseStrict(json, 32));
    }

    @Test
    void rejectsDuplicateMembersAtAnyLevelButLegacyKeepsLastWins() {
        rejects("{\"a\":1,\"a\":2}");
        rejects("{\"x\":{\"a\":1,\"b\":2,\"a\":null}}");
        rejects("[{\"a\":1,\"a\":1}]");
        assertDoesNotThrow(() -> JsonParser.parseStrict("{\"a\":{\"a\":1},\"b\":{\"a\":2}}", 32));
        assertEquals(JsonParser.parse("{\"a\":2}"), JsonParser.parse("{\"a\":1,\"a\":2}"));
    }

    @Test
    void rejectsNonRfcNumbers() {
        for (String n : new String[] {"01", "-01", "00", "1.", ".5", "-", "+1", "1e", "1e+", "\u0661", "1.\u0661"}) {
            rejects("[" + n + "]");
        }
        assertDoesNotThrow(() -> JsonParser.parseStrict("[0,-0,0.0,10,1E-2]", 32));
        assertDoesNotThrow(() -> JsonParser.parse("[01]"));
    }

    @Test
    void rejectsNonRfcWhitespace() {
        for (String ws : new String[] {"\u000b", "\u000c", "\u00a0", "\u2003", "\u001c", "\ufeff"}) {
            rejects(ws + "{}");
            rejects("{\"a\":" + ws + "1}");
            rejects("[1" + ws + "]");
        }
        assertDoesNotThrow(() -> JsonParser.parseStrict(" \t\r\n[ \t\r\n1 \t\r\n] ", 32));
        assertDoesNotThrow(() -> JsonParser.parse("[\u000c1]"));
    }

    @Test
    void rejectsInvalidUnicodeScalars() {
        rejects("[\"\\ud800\"]");
        rejects("[\"\\udc00\"]");
        rejects("[\"\\ud83dx\"]");
        rejects("[\"\\ud83d\\u0041\"]");
        rejects("[\"\\ude00\\ud83d\"]");
        rejects("[\"\ud800\"]");
        rejects("[\"\udc00\"]");
        rejects("[\"\ud83dx\"]");
        rejects("[\"\\u+123\"]");
        rejects("[\"\\u-123\"]");
        rejects("[\"\\u12\"]");
        rejects("{\"\\ud800\":1}");
        assertEquals("\ud83d\ude00",
                ((JsonValue.JsonString) ((JsonValue.JsonArray) JsonParser.parseStrict("[\"\ud83d\ude00\"]", 32))
                        .elements().get(0)).value());
    }

    @Test
    void enforcesCallerDepthCapExactly() {
        assertDoesNotThrow(() -> JsonParser.parseStrict(nested(32), 32));
        rejects(nested(33));
        rejects("{\"a\":" .repeat(33) + "1" + "}".repeat(33));
        assertDoesNotThrow(() -> JsonParser.parse(nested(33)));
        assertThrows(JsonParseException.class, () -> JsonParser.parseStrict(nested(3), 2));
        assertThrows(IllegalArgumentException.class, () -> JsonParser.parseStrict("[]", 0));
    }

    @Test
    void stillRejectsMalformedAndTrailingContent() {
        rejects("");
        rejects("{}x");
        rejects("{\"a\":1,}");
        rejects("[\"a\nb\"]");
        rejects("nul");
    }
}
