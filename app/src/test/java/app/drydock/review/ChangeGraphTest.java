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
        assertTrue(graph.declarationsIn("src/Guards.java").contains("JmpCtxScope"));
        assertTrue(graph.changedDeclarations().contains("JmpCtxScope"));
    }

    /**
     * A use counts wherever it sits in the diff window, not only on a
     * changed line. The node set is already restricted to changed files, so
     * this cannot pull in unrelated code -- it only connects files already
     * under review together, and "the declaration's behaviour changed
     * without touching most of its call sites" is the coupling this graph
     * exists to surface. Requiring the use itself to be edited too would
     * split that section for edge purity the node-set restriction already
     * gives for free.
     */
    @Test
    void aContextLineUseStillMintsAnEdge() {
        List<UnifiedDiff.Line> profilerLines = List.of(
                new UnifiedDiff.Line(UnifiedDiff.Line.Kind.CONTEXT,
                        OptionalInt.of(1), OptionalInt.of(1), "JmpCtxScope local;"),
                new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD,
                        OptionalInt.empty(), OptionalInt.of(2), "int unrelatedEdit = 1;"));
        UnifiedDiff.FileDiff profiler = new UnifiedDiff.FileDiff("src/Profiler.java", "M", 1, 0,
                false, false, List.of(new UnifiedDiff.Hunk("@@", profilerLines)));

        ChangeGraph graph = ChangeGraph.of(new UnifiedDiff(List.of(
                file("src/Guards.java", "class JmpCtxScope { }"),
                profiler)));

        assertTrue(graph.filesReferencedBy("src/Profiler.java").contains("src/Guards.java"));
    }

    /**
     * Fan-in per SYMBOL, which is a different question from fan-in per file:
     * a file declaring several changed names has one file-level fan-in and
     * its names have their own. Anything asking which symbol a group of
     * files is ABOUT has to ask this one, or it answers with whichever name
     * sorted first.
     */
    @Test
    void fanInIsCountedPerSymbolNotPerDeclaringFile() {
        ChangeGraph graph = ChangeGraph.of(new UnifiedDiff(List.of(
                file("src/Core.java", "class AaaHelper { }", "class ZzzEngine { }"),
                file("src/One.java", "void one() { new ZzzEngine(); }"),
                file("src/Two.java", "void two() { new ZzzEngine(); }"))));

        assertEquals(List.of("src/One.java", "src/Two.java"),
                List.copyOf(graph.filesReferencingSymbol("ZzzEngine")));
        assertEquals(List.of(), List.copyOf(graph.filesReferencingSymbol("AaaHelper")));
        assertEquals(List.of(), List.copyOf(graph.filesReferencingSymbol("NeverSeen")));
        // The file both names live in has the union as ITS fan-in, which is
        // exactly why it cannot stand in for either name's.
        assertEquals(List.of("src/One.java", "src/Two.java"),
                List.copyOf(graph.filesReferencing("src/Core.java")));
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
