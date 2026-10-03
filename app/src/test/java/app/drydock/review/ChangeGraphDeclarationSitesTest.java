package app.drydock.review;

import app.drydock.git.UnifiedDiff;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;
import java.util.SortedSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A declaration site is where a changed declaration sits, down to its line
 * key, so a tour step can tell which symbols its anchors declare (spec §6).
 */
class ChangeGraphDeclarationSitesTest {

    @Test
    void aDeclarationOnAnAddedRowIsASiteWithItsLineKey() {
        ChangeGraph graph = ChangeGraph.of(holderDiff());

        assertTrue(graph.declarationSites().contains(
                        new ChangeGraph.DeclarationSite("foo", "src/A.java", "n3")),
                "sites were " + graph.declarationSites());
    }

    @Test
    void aDeclarationOnAContextRowIsNotASite() {
        SortedSet<ChangeGraph.DeclarationSite> sites = ChangeGraph.of(holderDiff()).declarationSites();

        assertTrue(sites.stream().noneMatch(site -> site.name().equals("bar")),
                "bar sits on a context row: " + sites);
        assertTrue(sites.stream().noneMatch(site -> site.name().equals("Holder")),
                "Holder sits on a context row: " + sites);
        assertEquals(1, sites.size(), "only foo is declared on a changed row: " + sites);
    }

    /** {@code class Holder} and {@code bar} are context; only {@code foo} on n3 is added. */
    private static UnifiedDiff holderDiff() {
        List<UnifiedDiff.Line> lines = List.of(
                context(1, 1, "class Holder {"),
                context(2, 2, "    void bar() { }"),
                new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(), OptionalInt.of(3),
                        "    void foo() { }"),
                context(3, 4, "}"));
        UnifiedDiff.FileDiff file = new UnifiedDiff.FileDiff("src/A.java", "M", 1, 0, false, false,
                List.of(new UnifiedDiff.Hunk("@@ -1,3 +1,4 @@", lines)));
        return new UnifiedDiff(List.of(file));
    }

    private static UnifiedDiff.Line context(int oldLine, int newLine, String text) {
        return new UnifiedDiff.Line(UnifiedDiff.Line.Kind.CONTEXT, OptionalInt.of(oldLine),
                OptionalInt.of(newLine), text);
    }
}
