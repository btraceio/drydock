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
}
