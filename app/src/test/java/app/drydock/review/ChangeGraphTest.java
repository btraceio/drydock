package app.drydock.review;

import app.drydock.git.UnifiedDiff;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one matching rule (spec §4.2): a use resolves to a declaration only
 * when EXACTLY ONE changed declaration in the scope carries that name, and
 * only across files. Ambiguous names mint nothing -- a false edge sends a
 * reviewer to unrelated code and is worse than a missing one -- and
 * intra-file edges are noise from short-name matching.
 */
class ChangeGraphTest {

    private static UnifiedDiff.FileDiff file(String path, String... added) {
        List<UnifiedDiff.Line> lines = new ArrayList<>();
        int n = 1;
        for (String text : added) {
            lines.add(new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD,
                    OptionalInt.empty(), OptionalInt.of(n++), text));
        }
        return new UnifiedDiff.FileDiff(path, "M", added.length, 0, false, false,
                List.of(new UnifiedDiff.Hunk("@@", lines)));
    }

    @Test
    void aUniqueDeclarationUsedInAnotherFileMintsAnEdge() {
        ChangeGraph graph = ChangeGraph.of(new UnifiedDiff(List.of(
                file("src/Guards.java", "class JmpCtxScope { }"),
                file("src/Profiler.java", "void go() { new JmpCtxScope(); }"))));

        assertTrue(graph.filesReferencedBy("src/Profiler.java").contains("src/Guards.java"));
        assertTrue(graph.filesReferencing("src/Guards.java").contains("src/Profiler.java"));
    }

    /** Two declarations of one name cannot be told apart, so neither is linked. */
    @Test
    void anAmbiguousNameMintsNoEdge() {
        ChangeGraph graph = ChangeGraph.of(new UnifiedDiff(List.of(
                file("src/A.java", "class Helper { }"),
                file("src/B.java", "class Helper { }"),
                file("src/C.java", "void go() { new Helper(); }"))));

        assertEquals(List.of(), List.copyOf(graph.filesReferencedBy("src/C.java")));
    }

    @Test
    void aReferenceWithinOneFileMintsNoEdge() {
        ChangeGraph graph = ChangeGraph.of(new UnifiedDiff(List.of(
                file("src/Guards.java", "class JmpCtxScope { }", "void go() { new JmpCtxScope(); }"))));

        assertEquals(List.of(), List.copyOf(graph.filesReferencedBy("src/Guards.java")));
    }

    @Test
    void aDeclarationIsFoundByName() {
        ChangeGraph graph = ChangeGraph.of(new UnifiedDiff(List.of(
                file("src/Guards.java", "class JmpCtxScope { }"))));

        assertEquals(Optional.of("src/Guards.java"), graph.fileDeclaring("JmpCtxScope"));
    }

    /** Determinism: iteration order is a property this graph must keep (spec §9.5). */
    @Test
    void everyExposedCollectionIsSorted() {
        ChangeGraph graph = ChangeGraph.of(new UnifiedDiff(List.of(
                file("src/Z.java", "class Zed { }"),
                file("src/A.java", "void go() { new Zed(); }"))));

        assertEquals(List.of("src/A.java", "src/Z.java"), List.copyOf(graph.files()));
    }
}
