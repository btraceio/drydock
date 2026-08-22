package app.drydock.mcp;

import app.drydock.git.UnifiedDiff;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonWriter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static app.drydock.mcp.JsonPeek.bool;
import static app.drydock.mcp.JsonPeek.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The agent has to be able to see the grouping it is being asked to name
 * (spec §5.5). An agent that cannot regroups from scratch and loses the
 * header conventions and the dependency order -- arriving back at prose
 * titles over structurally worse sections.
 */
class McpToolRouterSectionsTest extends McpRouterFixture {

    @Test
    void reviewScopeOmitsSectionsUnlessAsked() {
        String response = callReviewScope(scopeId(), null);

        assertFalse(response.contains("\"sections\""));
        assertEquals(0, graphBuilds(), "an unrequested call must never build the graph, not just omit it on the wire");
    }

    @Test
    void reviewScopeIncludesSectionsWhenAsked() {
        String response = callReviewScope(scopeId(), "sections");

        assertTrue(response.contains("\"sections\""));
        assertTrue(response.contains("\"hunkIds\""));
        assertEquals(1, graphBuilds());
    }

    /** An unknown include is ignored, not an error: it is an optional read. */
    @Test
    void anUnknownIncludeIsIgnored() {
        String response = callReviewScope(scopeId(), "nonsense");

        assertFalse(response.contains("\"sections\""));
        assertEquals(0, graphBuilds());
    }

    /**
     * A multi-page read must not re-parse the same diff once per page: the
     * grouping cannot have changed between pages of the same read, so it is
     * offered only on the cursor-absent first page, and the graph is built
     * at most once for the whole read even if the agent still asks on every
     * page.
     */
    @Test
    void sectionsAreOmittedOnALaterPageAndTheGraphIsNotRebuilt() {
        JsonValue first = callReviewScopeValue(scopeId(), "sections", null, 400);
        String cursor = cursorOf(first);
        assertNotNull(cursor, "the tiny budget must force a second page");
        assertEquals(1, graphBuilds());

        String second = callReviewScope(scopeId(), "sections", cursor);

        assertFalse(second.contains("\"sections\""));
        assertEquals(1, graphBuilds(), "a later page must not rebuild the graph for a payload that cannot have changed");
    }

    /**
     * Sections overlap by design (spec §5.6): a shared foundation file
     * appears in every section that needs it, so the payload scales as
     * sections x shared files, not by file count -- it must be charged
     * against the same budget hunks pays from, not added on top of it
     * unaccounted.
     */
    @Test
    void sectionsAreChargedAgainstTheByteBudget() {
        JsonValue withSections = callReviewScopeValue(scopeId(), "sections", null, 2000);
        JsonValue withoutSections = callReviewScopeValue(scopeId(), null, null, 2000);

        int hunksWithSections = ((JsonArray) field(withSections, "hunks")).elements().size();
        int hunksWithoutSections = ((JsonArray) field(withoutSections, "hunks")).elements().size();

        assertTrue(hunksWithSections < hunksWithoutSections,
                "the same budget must yield fewer hunks once sections are charged against it: "
                        + hunksWithSections + " vs " + hunksWithoutSections);
    }

    /**
     * When the grouping alone is bigger than the whole budget, it is
     * reported anyway -- truncating it mid-array would hand the agent a
     * lie -- but the overage must be visible, not silent.
     */
    @Test
    void aSectionsPayloadBiggerThanTheBudgetIsEmittedWithTheOverageFlagged() {
        // One shared foundation file plus eight users of it means eight
        // sections each repeating that foundation -- big enough on its own
        // to outgrow even the smallest maxBytes the router allows (the
        // caller-supplied value is clamped to at least 1_000).
        context.reviewDiff = manySectionsSharingAFoundationDiff(8);

        JsonValue result = callReviewScopeValue(scopeId(), "sections", null, 1);

        assertTrue(JsonWriter.write(result).contains("\"sections\""), "the grouping must never be dropped");
        assertTrue(bool(result, "sectionsOverBudget"));
    }

    /**
     * A parse-edge-case failure while building the grouping must cost only
     * that one optional extra, never the whole call: the agent still gets
     * hunks, scope and files even though its opt-in extra could not be
     * computed.
     */
    @Test
    void aSectionsBuildFailureDegradesGracefully() {
        makeGraphBuildingFail();

        JsonValue result = callReviewScopeValue(scopeId(), "sections", null, McpToolRouter.DEFAULT_SCOPE_BYTES);

        assertFalse(((JsonObject) result).has("sections"), "a failed build must be omitted, not fail the call");
        assertTrue(((JsonArray) field(result, "hunks")).elements().size() > 0, "hunks must still be reported");
        assertNotNull(field(result, "scope"));
        assertNotNull(field(result, "files"));
    }

    // ---- fixtures -----------------------------------------------------------

    /**
     * One shared foundation file plus {@code count} independent files that
     * each reference it -- the shared-file overlap spec §5.6 describes:
     * {@code Shared} is not one file among many, it is the foundation
     * REPEATED in every one of the {@code count} sections that needs it, so
     * the payload scales with {@code count}, not with the file count (which
     * is only {@code count + 1}).
     */
    private static UnifiedDiff manySectionsSharingAFoundationDiff(int count) {
        List<UnifiedDiff.FileDiff> files = new ArrayList<>();
        files.add(oneFile("src/Shared.java",
                "public class Shared {",
                "    static int value() { return 1; }",
                "}"));
        for (int i = 0; i < count; i++) {
            String name = "User" + i;
            files.add(oneFile("src/" + name + ".java",
                    "public class " + name + " {",
                    "    void use() {",
                    "        int v = Shared.value();",
                    "    }",
                    "}"));
        }
        return new UnifiedDiff(files);
    }

    private static UnifiedDiff.FileDiff oneFile(String path, String... added) {
        List<UnifiedDiff.Line> lines = new ArrayList<>();
        int n = 1;
        for (String text : added) {
            lines.add(new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(), OptionalInt.of(n++), text));
        }
        return new UnifiedDiff.FileDiff(path, "A", added.length, 0, false, false,
                List.of(new UnifiedDiff.Hunk("@@", lines)));
    }
}
