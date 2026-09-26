package app.drydock.mcp;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.IntentGrouping;
import app.drydock.review.ReviewIntent;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
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

    /**
     * A reads that is not an array of strings is rejected, not read as an
     * empty list. Every shape here would otherwise decode as "declared
     * nothing" and put the rail in the exact reverse of the asserted order,
     * with no diagnostic anywhere and nothing echoed back to notice it by --
     * the bare string most of all, which is simply one dependency written
     * without the brackets.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "\"the-guard\"",            // one dependency, no brackets
            "{\"0\":\"the-guard\"}",    // an object rather than an array
            "[7]",                      // an array of the wrong element type
            "[\"the-guard\",5]",        // one good entry, one not
            "[null]",                   // a null where an id belongs
    })
    void aMalformedReadsRejectsTheBatchRatherThanDecodingToNothing(String malformed) {
        McpToolException thrown = assertThrows(McpToolException.class,
                () -> ReviewToolCodec.intentsFromJson(parse("""
                        [{"id":"uses-it","title":"A","hunkIds":[],"reads":%s},
                         {"id":"the-guard","title":"B","hunkIds":[]}]
                        """.formatted(malformed))));

        assertTrue(thrown.getMessage().contains("'uses-it'"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("reads"), thrown.getMessage());
    }

    /**
     * An explicit null is absent, not broken -- it is how several clients
     * spell an omitted optional field, and refusing a batch over it would
     * reject a grouping that declared nothing wrong.
     */
    @Test
    void anExplicitNullReadsIsTheSameAsNoReadsAtAll() throws Exception {
        List<ReviewIntent> intents = ReviewToolCodec.intentsFromJson(parse("""
                [{"id":"a","title":"A","hunkIds":[],"reads":null}]
                """));

        assertEquals(List.of(), intents.get(0).reads());
    }

    /**
     * The same grouping ordered twice comes out identical -- the branch's
     * determinism bar (SectionDeterminismTest), which nothing covered for the
     * reads path.
     *
     * <p>Twenty intents, ids ANTI-correlated with array position: {@code i19}
     * arrives first and {@code i00} last, while the declared chain makes the
     * only correct order {@code i00..i19}. A fixture where array order and
     * the right answer agree cannot tell a stable ordering from no ordering
     * at all, and one small enough to come out right by accident cannot tell
     * either.</p>
     */
    @Test
    void theSameGroupingOrdersIdenticallyEveryTime() {
        List<ReviewIntent> supplied = new ArrayList<>();
        for (int n = CHAIN_LENGTH - 1; n >= 0; n--) {
            supplied.add(intent(chainId(n), "Intent " + n,
                    n == 0 ? List.of() : List.of(chainId(n - 1))));
        }
        List<String> foundationFirst = new ArrayList<>();
        for (int n = 0; n < CHAIN_LENGTH; n++) {
            foundationFirst.add(chainId(n));
        }

        IntentGrouping grouping = new IntentGrouping();
        grouping.set("scope-1", supplied);
        List<String> first = ids(grouping);
        // Set AGAIN, on the same instance: a grouping is replaced in place far
        // more often than a fresh one is built, and that is the path that
        // could carry state from the previous ordering.
        grouping.set("scope-1", supplied);
        List<String> second = ids(grouping);
        IntentGrouping fresh = new IntentGrouping();
        fresh.set("scope-1", supplied);

        assertEquals(foundationFirst, first);
        assertEquals(first, second);
        assertEquals(first, ids(fresh));
    }

    private static final int CHAIN_LENGTH = 20;

    /** Fixed width, so id order and array order stay genuinely opposed. */
    private static String chainId(int n) {
        return "i%02d".formatted(n);
    }

    private static List<String> ids(IntentGrouping grouping) {
        return grouping.intentsFor("scope-1", emptyDiff(), Optional.empty())
                .stream().map(ReviewIntent::id).toList();
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
