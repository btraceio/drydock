package app.drydock.review;

import app.drydock.git.UnifiedDiff;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link IntentGrouping#intentsFor(String, UnifiedDiff, Optional)}'s
 * computed-sections path: the id it mints, the case where it mints none at
 * all because {@link Sections#of} found nothing structural, and the kind
 * and risk it carries over from the fallback it replaces.
 */
class IntentGroupingTest {

    /**
     * Four files whose structure {@code Sections} is known to split: {@code
     * m.h}/{@code m.cpp} merge on the same-basename convention, {@code
     * z.cpp} and {@code a.cpp} stay their own units -- three sections from
     * one fallback group, since all four share {@code directory}'s (kind,
     * directory).
     */
    private static UnifiedDiff diffOf(String directory, int insertionsPerFile) {
        List<UnifiedDiff.FileDiff> files = new ArrayList<>();
        for (String name : List.of("z.cpp", "a.cpp", "m.h", "m.cpp")) {
            files.add(new UnifiedDiff.FileDiff(directory + "/" + name, "M", insertionsPerFile, 0,
                    false, false, List.of(new UnifiedDiff.Hunk("@@", List.of(new UnifiedDiff.Line(
                            UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(), OptionalInt.of(1),
                            "void go() { helperOne(); }"))))));
        }
        return new UnifiedDiff(files);
    }

    // ---- a genuinely computed grouping mints its own id --------------------

    @Test
    void computedSectionsMintDistinctContentDerivedIds() {
        UnifiedDiff diff = diffOf("src", 1);
        IntentGrouping grouping = new IntentGrouping();
        List<ReviewIntent> intents =
                grouping.intentsFor("scope", diff, Optional.of(ChangeGraph.of(diff)));

        assertTrue(intents.size() > 1,
                "the m.h/m.cpp convention pair must produce a non-degenerate split");
        for (ReviewIntent intent : intents) {
            assertTrue(intent.id().startsWith("computed:"),
                    "a genuinely computed section must not reuse a fallback id: " + intent.id());
        }
        assertEquals(intents.size(), intents.stream().map(ReviewIntent::id).distinct().count(),
                "every computed section must have its own id");
    }

    @Test
    void theSameSectionMintsTheSameIdAcrossASeparateRebuild() {
        UnifiedDiff diff = diffOf("src", 1);
        IntentGrouping grouping = new IntentGrouping();
        List<String> first = grouping.intentsFor("scope", diff, Optional.of(ChangeGraph.of(diff)))
                .stream().map(ReviewIntent::id).toList();
        List<String> second = grouping.intentsFor("scope", diff, Optional.of(ChangeGraph.of(diff)))
                .stream().map(ReviewIntent::id).toList();

        assertEquals(first, second,
                "hashing over a section's own sorted hunk ids must be reproducible across a rebuild");
    }

    /**
     * The discriminating case a positional {@code "computed:" + number}
     * cannot pass: the SAME diff's own sections, reached through a
     * DIFFERENT topological order. An unrelated extra file that sorts
     * before {@code src/a.cpp} is enough on its own -- it becomes its own
     * standalone section ahead of everything else, pushing a.cpp's
     * position back with none of a.cpp's own hunks touched. A positional id
     * would re-point at the section now sitting where a.cpp used to.
     */
    @Test
    void aSurvivingSectionKeepsItsIdAfterAnUnrelatedFileShiftsTheOrder() {
        UnifiedDiff diffWithout = diffOf("src", 1);
        IntentGrouping groupingWithout = new IntentGrouping();
        List<ReviewIntent> before =
                groupingWithout.intentsFor("scope", diffWithout, Optional.of(ChangeGraph.of(diffWithout)));
        ReviewIntent survivorBefore = before.stream()
                .filter(intent -> intent.title().startsWith("a.cpp"))
                .findFirst().orElseThrow();
        assertEquals(0, before.indexOf(survivorBefore), "a.cpp must lead before the extra file exists");

        List<UnifiedDiff.FileDiff> files = new ArrayList<>(diffWithout.files());
        files.add(new UnifiedDiff.FileDiff("other/n.cpp", "M", 1, 0, false, false,
                List.of(new UnifiedDiff.Hunk("@@", List.of(new UnifiedDiff.Line(
                        UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(), OptionalInt.of(1),
                        "class Standalone {};"))))));
        UnifiedDiff diffWith = new UnifiedDiff(files);
        IntentGrouping groupingWith = new IntentGrouping();
        List<ReviewIntent> after =
                groupingWith.intentsFor("scope", diffWith, Optional.of(ChangeGraph.of(diffWith)));
        ReviewIntent survivorAfter = after.stream()
                .filter(intent -> intent.id().equals(survivorBefore.id()))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "a.cpp's id must still be present after the reorder: " + after));

        assertNotEquals(0, after.indexOf(survivorAfter),
                "the extra file must actually have shifted a.cpp's position, or this test proves nothing");
        assertEquals(survivorBefore.hunkIds(), survivorAfter.hunkIds(),
                "a.cpp's own hunks must be unaffected by an unrelated file elsewhere in the diff");
    }

    // ---- nothing structural: the fallback's own ids survive -----------------

    @Test
    void aStructurelessDiffKeepsTheFallbacksOwnIdentity() {
        UnifiedDiff diff = new UnifiedDiff(List.of(
                new UnifiedDiff.FileDiff("src/A.java", "M", 1, 0, false, false, List.of(
                        new UnifiedDiff.Hunk("@@", List.of(new UnifiedDiff.Line(
                                UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(), OptionalInt.of(1), "x"))))),
                new UnifiedDiff.FileDiff("lib/B.java", "M", 1, 0, false, false, List.of(
                        new UnifiedDiff.Hunk("@@", List.of(new UnifiedDiff.Line(
                                UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(), OptionalInt.of(1), "y")))))));
        IntentGrouping grouping = new IntentGrouping();

        List<ReviewIntent> computed =
                grouping.intentsFor("scope", diff, Optional.of(ChangeGraph.of(diff)));
        List<ReviewIntent> fallback = FallbackIntents.group(diff);

        assertEquals(fallback, computed,
                "Sections.of degenerating to the (kind, directory) clustering must not restate it "
                        + "under a fresh computed: identity -- that would orphan a finding recorded "
                        + "against the fallback's own id the moment the graph finished");
    }

    // ---- a computed section carries over kind and risk ---------------------

    @Test
    void computedSectionsCarryOverTheFallbacksKindAndRisk() {
        // Under "test/", all four files classify as ReviewIntent.Kind.TESTS;
        // 4 files x 40 declared insertions each is 160 total churn, inside
        // FallbackIntents' MED band (over 100, at or under 400).
        UnifiedDiff diff = diffOf("test", 40);
        List<ReviewIntent> fallback = FallbackIntents.group(diff);
        assertEquals(1, fallback.size(), "all four files share one (kind, directory) fallback group");
        assertEquals(ReviewIntent.Kind.TESTS, fallback.get(0).kind());
        assertEquals(ReviewIntent.Risk.MED, fallback.get(0).risk());

        IntentGrouping grouping = new IntentGrouping();
        List<ReviewIntent> computed =
                grouping.intentsFor("scope", diff, Optional.of(ChangeGraph.of(diff)));
        assertTrue(computed.size() > 1, "the m.h/m.cpp convention pair must still split");
        for (ReviewIntent intent : computed) {
            assertEquals(ReviewIntent.Kind.TESTS, intent.kind(),
                    "a computed section must not flatten to CHANGE when its hunks are all tests");
            assertEquals(ReviewIntent.Risk.MED, intent.risk(),
                    "a computed section must not flatten to NONE when its hunks carry real churn");
        }
    }
}
