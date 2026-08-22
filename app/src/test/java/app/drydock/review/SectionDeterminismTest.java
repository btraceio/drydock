package app.drydock.review;

import app.drydock.git.UnifiedDiff;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Calling the computed layer stable is a claim the code has to keep
 * (spec §9.5). The cheapest way to lose it is a hash-ordered collection, and
 * the hardest place to notice is a single JVM, which usually agrees with
 * itself. The cross-process half of that check is the running-app pass; this
 * pins the in-process half and the shape the other half compares.
 */
class SectionDeterminismTest {

    private static UnifiedDiff diff() {
        List<UnifiedDiff.FileDiff> files = new java.util.ArrayList<>();
        for (String path : List.of("src/z.cpp", "src/a.cpp", "src/m.h", "src/m.cpp")) {
            files.add(new UnifiedDiff.FileDiff(path, "M", 1, 0, false, false,
                    List.of(new UnifiedDiff.Hunk("@@", List.of(new UnifiedDiff.Line(
                            UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(), OptionalInt.of(1),
                            "void go() { helperOne(); }"))))));
        }
        return new UnifiedDiff(files);
    }

    private static List<String> titles() {
        UnifiedDiff diff = diff();
        return Sections.of(diff, ChangeGraph.of(diff)).stream()
                .map(Sections.Section::title).toList();
    }

    @Test
    void theSameDiffProducesTheSameSectionsEveryTime() {
        assertEquals(titles(), titles());
    }

    @Test
    void theSameDiffProducesTheSameHunkOrderEveryTime() {
        UnifiedDiff diff = diff();
        assertEquals(Sections.of(diff, ChangeGraph.of(diff)).stream()
                        .map(Sections.Section::hunkIds).toList(),
                Sections.of(diff, ChangeGraph.of(diff)).stream()
                        .map(Sections.Section::hunkIds).toList());
    }

    /** A reviewer's grouping still wins; the computed one is the fallback. */
    @Test
    void aReviewerGroupingIsNotRecomputed() {
        IntentGrouping grouping = new IntentGrouping();
        ReviewIntent supplied = new ReviewIntent("agent-1", 1, "Crash-protected resolve()",
                ReviewIntent.Kind.CHANGE, ReviewIntent.Risk.HIGH, "",
                List.of(ReviewIntent.hunkId("src/a.cpp", 0)), java.util.Optional.empty(), false);
        grouping.set("scope-1", List.of(supplied));

        UnifiedDiff diff = diff();
        List<ReviewIntent> intents = grouping.intentsFor("scope-1", diff,
                java.util.Optional.of(ChangeGraph.of(diff)));

        assertEquals(List.of("Crash-protected resolve()"),
                intents.stream().map(ReviewIntent::title).toList());
    }
}
