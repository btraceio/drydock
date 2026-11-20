package app.drydock.review;

import app.drydock.git.UnifiedDiff;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link DiffSelection}: the same-hunk rule over an editor selection, ported from the app's DiffLineSelection. */
class DiffSelectionTest {

    // Hunk 1: old 9..11 / new 9..12 (context 9, del o10, add n10, add n11, context 11/12).
    // Hunk 2: old 30..31 / new 31..32 (context 30/31, add n32).
    private static final UnifiedDiff.FileDiff FILE = new UnifiedDiff.FileDiff("A.java", "M", 3, 1, false, false,
            List.of(
                    new UnifiedDiff.Hunk("@@ -9,3 +9,4 @@", List.of(
                            row(UnifiedDiff.Line.Kind.CONTEXT, 9, 9),
                            row(UnifiedDiff.Line.Kind.DEL, 10, 0),
                            row(UnifiedDiff.Line.Kind.ADD, 0, 10),
                            row(UnifiedDiff.Line.Kind.ADD, 0, 11),
                            row(UnifiedDiff.Line.Kind.CONTEXT, 11, 12))),
                    new UnifiedDiff.Hunk("@@ -30,2 +31,2 @@", List.of(
                            row(UnifiedDiff.Line.Kind.CONTEXT, 30, 31),
                            row(UnifiedDiff.Line.Kind.ADD, 0, 32)))));

    @Test
    void aSelectionInsideOneHunkIsItsRange() {
        assertEquals(Optional.of(new DiffSelection.Range("A.java", "n10", "n12", false)),
                DiffSelection.resolve(FILE, DiffSelection.Side.NEW, 10, 12));
    }

    @Test
    void theDirectionDoesNotMatter() {
        assertEquals(DiffSelection.resolve(FILE, DiffSelection.Side.NEW, 10, 12),
                DiffSelection.resolve(FILE, DiffSelection.Side.NEW, 12, 10));
    }

    @Test
    void aSingleLineIsAOneLineRange() {
        assertEquals(Optional.of(new DiffSelection.Range("A.java", "n11", "n11", false)),
                DiffSelection.resolve(FILE, DiffSelection.Side.NEW, 11, 11));
    }

    @Test
    void aSelectionAcrossHunksStopsAtTheAnchorsHunk() {
        DiffSelection.Range range = DiffSelection.resolve(FILE, DiffSelection.Side.NEW, 11, 32).orElseThrow();
        assertEquals("n11", range.startKey());
        assertEquals("n12", range.endKey(), "the last row of the anchor's hunk");
        assertTrue(range.clamped());
    }

    @Test
    void aSelectionPastTheHunksLastRowIsClampedToo() {
        DiffSelection.Range range = DiffSelection.resolve(FILE, DiffSelection.Side.NEW, 31, 40).orElseThrow();
        assertEquals("n31", range.startKey());
        assertEquals("n32", range.endKey());
        assertTrue(range.clamped());
    }

    @Test
    void aSelectionStartingOutsideEveryHunkHasNoRange() {
        assertEquals(Optional.empty(), DiffSelection.resolve(FILE, DiffSelection.Side.NEW, 3, 10),
                "unchanged code above the hunk: GitHub accepts comments on diff lines only");
        assertEquals(Optional.empty(), DiffSelection.resolve(FILE, DiffSelection.Side.NEW, 20, 20));
    }

    @Test
    void theOldSideKeysDeletedLinesByTheirOldNumberAndContextByTheNew() {
        assertEquals(Optional.of(new DiffSelection.Range("A.java", "o10", "o10", false)),
                DiffSelection.resolve(FILE, DiffSelection.Side.OLD, 10, 10));
        DiffSelection.Range range = DiffSelection.resolve(FILE, DiffSelection.Side.OLD, 9, 11).orElseThrow();
        assertEquals("n9", range.startKey(), "a context line exists in the new file: its key is the new one");
        assertEquals("n12", range.endKey());
        assertFalse(range.clamped());
    }

    @Test
    void anAddedLineDoesNotExistOnTheOldSide() {
        assertEquals(Optional.empty(), DiffSelection.resolve(FILE, DiffSelection.Side.OLD, 32, 32),
                "old line 32 is in no hunk");
    }

    private static UnifiedDiff.Line row(UnifiedDiff.Line.Kind kind, int oldLine, int newLine) {
        return new UnifiedDiff.Line(kind,
                oldLine == 0 ? OptionalInt.empty() : OptionalInt.of(oldLine),
                newLine == 0 ? OptionalInt.empty() : OptionalInt.of(newLine), "x");
    }
}
