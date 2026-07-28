package app.drydock.ui.review;

import app.drydock.git.UnifiedDiff;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The symbol lens's local index (spec §4.4). Pure, so no toolkit is needed. */
class SymbolIndexTest {

    @Test
    void aSymbolOnSeveralLinesIsIndexedWithAllOfThem() {
        SymbolIndex index = SymbolIndex.of(diff(
                context(1, "private DragTracker tracker;"),
                add(2, "tracker = new DragTracker(event);"),
                context(3, "return tracker;")));

        SymbolIndex.Entry entry = index.lookup("tracker").orElseThrow();
        assertEquals(3, entry.occurrences().size());
    }

    /** The chips the popover draws: on a changed line, or merely near one. */
    @Test
    void occurrencesKnowWhetherTheyAreOnAChangedLine() {
        SymbolIndex index = SymbolIndex.of(diff(
                context(1, "int width = 220;"),
                add(2, "int width = 240;"),
                context(3, "use(width);")));

        SymbolIndex.Entry entry = index.lookup("width").orElseThrow();
        assertEquals(1, entry.inDiffCount());
        assertEquals(3, entry.occurrences().size());
    }

    /** A symbol with one occurrence is not worth a popover -- you are looking at it. */
    @Test
    void aSymbolThatAppearsOnceGetsNoEntry() {
        SymbolIndex index = SymbolIndex.of(diff(add(1, "int loneliness = 1;")));

        assertTrue(index.lookup("loneliness").isEmpty());
        assertFalse(index.hasEntry("loneliness"));
    }

    /** Keywords are not symbols; a lens on `return` teaches the underline means nothing. */
    @Test
    void keywordsAreNotIndexed() {
        SymbolIndex index = SymbolIndex.of(diff(
                add(1, "return value;"),
                add(2, "return other;")));

        assertFalse(index.hasEntry("return"));
    }

    @Test
    void veryShortIdentifiersAreNoiseAndAreSkipped() {
        SymbolIndex index = SymbolIndex.of(diff(
                add(1, "for (int i = 0; i < n; i++) {"),
                add(2, "sum += i;")));

        assertFalse(index.hasEntry("i"));
    }

    @Test
    void symbolsAreIndexedAcrossFiles() {
        UnifiedDiff diff = new UnifiedDiff(List.of(
                file("A.java", add(1, "Sidebar sidebar = new Sidebar();")),
                file("B.java", context(9, "sidebar.resize();"))));

        SymbolIndex.Entry entry = SymbolIndex.of(diff).lookup("sidebar").orElseThrow();

        assertEquals(List.of("A.java", "B.java"),
                entry.occurrences().stream().map(SymbolIndex.Occurrence::file).distinct().toList());
    }

    @Test
    void anEmptyDiffIndexesNothing() {
        assertEquals(0, SymbolIndex.of(new UnifiedDiff(List.of())).size());
    }

    // ---- fixtures -----------------------------------------------------------

    private static UnifiedDiff diff(UnifiedDiff.Line... lines) {
        return new UnifiedDiff(List.of(file("A.java", lines)));
    }

    private static UnifiedDiff.FileDiff file(String path, UnifiedDiff.Line... lines) {
        return new UnifiedDiff.FileDiff(path, "M", 0, 0, false,
                List.of(new UnifiedDiff.Hunk("@@", List.of(lines))));
    }

    private static UnifiedDiff.Line context(int line, String text) {
        return new UnifiedDiff.Line(UnifiedDiff.Line.Kind.CONTEXT,
                OptionalInt.of(line), OptionalInt.of(line), text);
    }

    private static UnifiedDiff.Line add(int line, String text) {
        return new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD,
                OptionalInt.empty(), OptionalInt.of(line), text);
    }
}
