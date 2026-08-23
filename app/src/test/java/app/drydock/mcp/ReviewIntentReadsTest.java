package app.drydock.mcp;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.IntentGrouping;
import app.drydock.review.ReviewIntent;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The agent asserts, drydock renders the assertion and never verifies it --
 * the ReviewIntent.Collapse precedent (spec §8). With reads present the
 * rail's order is the agent's declared dependency order; without it, the
 * agent's array order stands.
 */
class ReviewIntentReadsTest {

    @Test
    void readsOrdersTheRailFoundationFirst() {
        IntentGrouping grouping = new IntentGrouping();
        grouping.set("scope-1", List.of(
                intent("uses-it", "Crash-protected resolve()", List.of("the-guard")),
                intent("the-guard", "JmpCtxScope guard", List.of())));

        assertEquals(List.of("JmpCtxScope guard", "Crash-protected resolve()"),
                titles(grouping));
    }

    /**
     * Three intents, so the order cannot be right by accident: a two-node
     * graph comes out the same under most rules, and a chain also pins that
     * a transitive dependent lands after BOTH of the things beneath it
     * rather than merely after the one it names.
     */
    @Test
    void readsOrdersAWholeChainAndNotJustTheOnePairItNames() {
        IntentGrouping grouping = new IntentGrouping();
        grouping.set("scope-1", List.of(
                intent("uses-it", "Crash-protected resolve()", List.of("the-guard")),
                intent("on-top", "Resolver cache", List.of("uses-it")),
                intent("the-guard", "JmpCtxScope guard", List.of())));

        assertEquals(List.of("JmpCtxScope guard", "Crash-protected resolve()", "Resolver cache"),
                titles(grouping));
    }

    @Test
    void withoutReadsTheAgentsArrayOrderStands() {
        IntentGrouping grouping = new IntentGrouping();
        grouping.set("scope-1", List.of(
                intent("b", "Second", List.of()), intent("a", "First", List.of())));

        assertEquals(List.of("Second", "First"), titles(grouping));
    }

    /**
     * With SOME intents declaring reads the graph path runs for all of them,
     * so the ones that declared nothing must still come out in the order the
     * agent listed them -- the array order is the tie-break, not a fallback
     * that only applies when nothing at all declares anything.
     */
    @Test
    void intentsThatDeclareNothingKeepTheirArrayOrderAmongThemselves() {
        IntentGrouping grouping = new IntentGrouping();
        grouping.set("scope-1", List.of(
                intent("z", "Zulu", List.of()),
                intent("y", "Yankee", List.of()),
                intent("x", "X-ray", List.of("w")),
                intent("w", "Whiskey", List.of())));

        // X-ray drops behind Whiskey because it says it is built on it; Zulu,
        // Yankee and Whiskey, which say nothing about each other, stay in the
        // order they arrived in rather than being resorted by id or title.
        assertEquals(List.of("Zulu", "Yankee", "Whiskey", "X-ray"), titles(grouping));
    }

    /** A cycle among asserted dependencies is named, not broken silently. */
    @Test
    void aReadsCycleIsKeptTogetherRatherThanBrokenArbitrarily() {
        IntentGrouping grouping = new IntentGrouping();
        grouping.set("scope-1", List.of(
                intent("a", "A", List.of("b")), intent("b", "B", List.of("a"))));

        assertEquals(2, grouping.intentsFor("scope-1", emptyDiff(), Optional.empty()).size());
    }

    /**
     * A cycle does NOT reject the batch (controller ruling 3): entangled work
     * is a thing an agent may honestly describe. Its members come back as one
     * unit in tie-break order, and whatever depends on the unit lands after
     * ALL of it -- with a fourth intent present so a cycle that was quietly
     * ignored instead of collapsed would give a different answer.
     */
    @Test
    void aReadsCycleIsOrderedAsOneUnitAndKeepsTheBatch() {
        IntentGrouping grouping = new IntentGrouping();
        grouping.set("scope-1", List.of(
                intent("dependent", "Built on both", List.of("a")),
                intent("a", "Tangled A", List.of("b")),
                intent("b", "Tangled B", List.of("a")),
                intent("loner", "Unrelated", List.of())));

        assertEquals(List.of("Tangled A", "Tangled B", "Built on both", "Unrelated"),
                titles(grouping));
    }

    /** Numbering stays dense 1..N over the order reads produced, not the array order. */
    @Test
    void theRailIsRenumberedOverTheReadsOrder() {
        IntentGrouping grouping = new IntentGrouping();
        grouping.set("scope-1", List.of(
                intent("uses-it", "Crash-protected resolve()", List.of("the-guard")),
                intent("the-guard", "JmpCtxScope guard", List.of())));

        List<ReviewIntent> intents = grouping.intentsFor("scope-1", emptyDiff(), Optional.empty());
        assertEquals(1, intents.get(0).number());
        assertEquals("the-guard", intents.get(0).id());
        assertEquals(2, intents.get(1).number());
        // The declaration survives the renumbering, so the rail can still say
        // what the agent asserted about this card.
        assertEquals(List.of("the-guard"), intents.get(1).reads());
    }

    /**
     * A batch is all-or-nothing, so a reads naming nothing is rejected whole.
     *
     * <p>{@code parse} is the fixture's JSON helper -- the same
     * {@code JsonParser.parse(String)} the other codec tests use.</p>
     */
    @Test
    void readsNamingAnUnknownIntentRejectsTheBatch() {
        McpToolException thrown = assertThrows(McpToolException.class,
                () -> ReviewToolCodec.intentsFromJson(parse("""
                        [{"id":"a","title":"A","hunkIds":[],"reads":["nonexistent"]}]
                        """)));

        // The agent has to know WHICH declaration to fix, not merely that one
        // of them is wrong.
        assertTrue(thrown.getMessage().contains("nonexistent"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("'a'"), thrown.getMessage());
    }

    /** A reads may name an intent declared LATER in the same array. */
    @Test
    void readsMayNameAnIntentThatComesLaterInTheBatch() throws Exception {
        List<ReviewIntent> intents = ReviewToolCodec.intentsFromJson(parse("""
                [{"id":"a","title":"A","hunkIds":[],"reads":["b"]},
                 {"id":"b","title":"B","hunkIds":[]}]
                """));

        assertEquals(List.of("b"), intents.get(0).reads());
        assertEquals(List.of(), intents.get(1).reads());
    }

    private static List<String> titles(IntentGrouping grouping) {
        return grouping.intentsFor("scope-1", emptyDiff(), Optional.empty())
                .stream().map(ReviewIntent::title).toList();
    }

    private static ReviewIntent intent(String id, String title, List<String> reads) {
        return new ReviewIntent(id, 0, title, ReviewIntent.Kind.CHANGE, ReviewIntent.Risk.NONE,
                "", List.of(), Optional.empty(), false, reads);
    }

    private static UnifiedDiff emptyDiff() {
        return new UnifiedDiff(List.of());
    }

    private static JsonValue parse(String json) {
        return JsonParser.parse(json);
    }
}
