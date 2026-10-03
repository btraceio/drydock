package app.drydock.mcp;

import app.drydock.review.tour.TourRecord;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static app.drydock.mcp.JsonPeek.field;
import static app.drydock.mcp.JsonPeek.num;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fixture's diff: src/Widget.java and src/WidgetUser.java, both added,
 * one hunk each, rows n1..n5 and n1..n6 (see McpRouterFixture.parseableDiff).
 */
class McpToolRouterTourTest extends McpRouterFixture {

    private static final String CHECK = """
            {"id":"%s","kind":"predict","prompt":"What happens?",
             "choices":[{"text":"a"},{"text":"b"}],"answer":0,"explanation":"Because.",
             "alternates":[{"id":"%s_alt","kind":"risk","prompt":"Risk?","explanation":"E."}]}""";

    private static String step(String id, String file, String start, String end, String checkId) {
        return """
                {"id":"%s","title":"T %s","narrative":"Why.",
                 "anchors":[{"file":"%s","startKey":"%s","endKey":"%s"}],
                 "checks":[%s]}""".formatted(id, id, file, start, end, CHECK.formatted(checkId, checkId));
    }

    private JsonObject tourArgs(String... steps) {
        JsonObject args = JsonObject.empty().put("scopeId", new JsonValue.JsonString(scopeId()));
        args.put("steps", JsonParser.parse("[" + String.join(",", steps) + "]"));
        return args;
    }

    private JsonObject coveringArgs() {
        return tourArgs(step("s1", "src/Widget.java", "n1", "n5", "c1"),
                step("s2", "src/WidgetUser.java", "n1", "n6", "c2"));
    }

    @Test
    void aCoveringTourIsStoredWithFreshProgress() throws Exception {
        JsonValue result = router.call(callerId(), "review_tour", coveringArgs());

        assertEquals(2, num(result, "steps"));
        assertEquals(2, num(result, "checks"));
        TourRecord stored = context.tourOf(scopeId()).orElseThrow();
        assertEquals(2, stored.tour().steps().size());
        assertEquals(1, stored.progress().get("s1").hunkDigests().size());
    }

    @Test
    void anUncoveredHunkRejectsTheWholeTourAndStoresNothing() {
        McpToolException error = assertThrows(McpToolException.class, () -> router.call(callerId(), "review_tour",
                tourArgs(step("s1", "src/Widget.java", "n1", "n5", "c1"))));
        assertTrue(error.getMessage().contains("hunk h_src/WidgetUser.java_0"), error.getMessage());
        assertTrue(error.getMessage().contains("nothing stored"), error.getMessage());
        assertTrue(context.tourOf(scopeId()).isEmpty());
    }

    @Test
    void aMalformedStepIsRejectedNamingItsPath() {
        McpToolException error = assertThrows(McpToolException.class, () -> router.call(callerId(), "review_tour",
                tourArgs(step("s1", "src/Widget.java", "n1", "n5", "c1").replace("\"answer\":0", "\"answer\":\"0\""))));
        assertTrue(error.getMessage().contains("steps[0].checks[0].answer"), error.getMessage());
    }

    @Test
    void anImpactNoteOnALineThatDoesNotExistIsRejected() {
        context.excerptAnswer = Optional.empty();
        String noted = step("s1", "src/Widget.java", "n1", "n5", "c1")
                .replace("\"checks\":", "\"impactNotes\":[{\"file\":\"src/Other.java\",\"line\":4000,\"text\":\"x\"}],\"checks\":");
        McpToolException error = assertThrows(McpToolException.class, () -> router.call(callerId(), "review_tour",
                tourArgs(noted, step("s2", "src/WidgetUser.java", "n1", "n6", "c2"))));
        assertTrue(error.getMessage().contains("src/Other.java:4000"), error.getMessage());
    }

    @Test
    void reviewStateReportsTheTourAndWhetherItIsCurrent() throws Exception {
        router.call(callerId(), "review_tour", coveringArgs());

        JsonValue state = router.call(callerId(), "review_state", JsonPeek.args("scopeId", scopeId()));

        JsonValue tour = field(state, "tour");
        assertTrue(JsonPeek.bool(tour, "current"));
        assertEquals(2, JsonPeek.array(tour, "steps").size());
    }
}
