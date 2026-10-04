package app.drydock.mcp;

import app.drydock.review.HunkDigest;
import app.drydock.review.HunkIds;
import app.drydock.review.ReviewVerdict;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import app.drydock.state.json.JsonWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.Optional;

import static app.drydock.mcp.JsonPeek.field;
import static app.drydock.mcp.JsonPeek.str;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The array-valued review arguments (findings, steps, assessments) are
 * declared as JSON-Schema arrays, and a client that sends one as a JSON
 * string anyway -- a schema-respecting one that was told "string" before, or
 * a model that stringifies nested JSON -- is still understood.
 */
class McpToolRouterStringArrayTest extends McpRouterFixture {

    private static final String FINDINGS = """
            [{"id":"f-1","anchor":{"file":"src/Widget.java","startKey":"n1"},
              "severity":"nit","confidence":"high","body":"hello"}]""";

    private static final String STEPS = """
            [{"id":"s1","title":"T","narrative":"Why.",
              "anchors":[{"file":"src/Widget.java","startKey":"n1","endKey":"n5"}],
              "checks":[{"id":"c1","kind":"predict","prompt":"P?","choices":[{"text":"a"},{"text":"b"}],"answer":0,"explanation":"E.",
               "alternates":[{"id":"c1_alt","kind":"risk","prompt":"R?","explanation":"E."}]}]},
             {"id":"s2","title":"T","narrative":"Why.",
              "anchors":[{"file":"src/WidgetUser.java","startKey":"n1","endKey":"n6"}],
              "checks":[{"id":"c2","kind":"predict","prompt":"P?","choices":[{"text":"a"},{"text":"b"}],"answer":0,"explanation":"E.",
               "alternates":[{"id":"c2_alt","kind":"risk","prompt":"R?","explanation":"E."}]}]}]""";

    private JsonObject args(String key, JsonValue value) {
        return JsonObject.empty().put("scopeId", new JsonString(scopeId())).put(key, value);
    }

    private static JsonValue asString(String json) {
        return new JsonString(JsonWriter.write(JsonParser.parse(json)));
    }

    @ParameterizedTest
    @CsvSource({"review_finding,findings", "review_tour,steps", "review_recheck,assessments"})
    void theArrayArgumentsAreDeclaredAsArrays(String tool, String argument) {
        JsonValue descriptor = router.toolDescriptors().stream()
                .filter(d -> str(d, "name").equals(tool)).findFirst().orElseThrow();
        JsonValue property = field(field(field(descriptor, "inputSchema"), "properties"), argument);
        assertEquals("array", str(property, "type"));
        assertEquals("object", str(field(property, "items"), "type"));
    }

    @Test
    void findingsSentAsAJsonStringAreStoredLikeTheArrayForm() throws Exception {
        router.call(callerId(), "review_finding", args("findings", JsonParser.parse(FINDINGS)));
        var asArray = context.findingsOf(scopeId());
        context.annotations.clear();

        router.call(callerId(), "review_finding", args("findings", asString(FINDINGS)));

        assertEquals(1, asArray.size());
        var fromString = context.findingsOf(scopeId());
        assertEquals(1, fromString.size());
        // Timestamps differ between the two calls; everything the caller sent must not.
        assertEquals(asArray.get(0).id(), fromString.get(0).id());
        assertEquals(asArray.get(0).startKey(), fromString.get(0).startKey());
        assertEquals(asArray.get(0).severity(), fromString.get(0).severity());
        assertEquals(asArray.get(0).confidence(), fromString.get(0).confidence());
        assertEquals(asArray.get(0).thread().get(0).text(), fromString.get(0).thread().get(0).text());
    }

    @Test
    void stepsSentAsAJsonStringAreStoredLikeTheArrayForm() throws Exception {
        router.call(callerId(), "review_tour", args("steps", asString(STEPS)));

        assertEquals(2, context.tourOf(scopeId()).orElseThrow().tour().steps().size());
    }

    @Test
    void assessmentsSentAsAJsonStringAreStoredLikeTheArrayForm() throws Exception {
        var file = context.reviewDiff.files().get(0);
        context.verdicts.add(new ReviewVerdict(scopeId(), HunkDigest.of(file.path(),
                file.hunks().get(0)), ReviewVerdict.Decision.APPROVED, Optional.empty(), Instant.EPOCH,
                "base-1", "head-1"));
        String assessments = "[{\"hunkId\":\"" + HunkIds.hunkId("src/Widget.java", 0)
                + "\",\"affected\":true,\"why\":\"changed\"}]";

        router.call(callerId(), "review_recheck", args("assessments", asString(assessments)));

        assertEquals(1, context.assessments.size());
        assertTrue(context.assessments.get(0).affected());
    }

    @ParameterizedTest
    @CsvSource({"review_finding,findings", "review_tour,steps", "review_recheck,assessments"})
    void aStringThatIsNotAJsonArrayIsRejectedNamingTheArgument(String tool, String argument) {
        for (String bad : new String[] {"not json at all", "{\"id\":\"x\"}", "[1,"}) {
            McpToolException error = assertThrows(McpToolException.class,
                    () -> router.call(callerId(), tool, args(argument, new JsonString(bad))), bad);
            assertTrue(error.getMessage().contains(argument), error.getMessage());
        }
    }
}
