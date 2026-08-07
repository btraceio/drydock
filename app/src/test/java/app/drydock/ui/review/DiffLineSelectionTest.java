package app.drydock.ui.review;

import app.drydock.git.UnifiedDiff;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a gutter drag is allowed to select (spec: the hunk clamp). */
class DiffLineSelectionTest {

    private static ReviewDiffRow.Line line(String file, int newLine) {
        return new ReviewDiffRow.Line(file,
                new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(),
                        OptionalInt.of(newLine), "code"),
                "n" + newLine, ReviewDiffRow.Edge.BODY);
    }

    private static ReviewDiffRow.HunkHeader header(String file) {
        return new ReviewDiffRow.HunkHeader(file, "L1-2", 1, false, false);
    }

    @Test
    void aDragDownwardsSelectsTheRunItCrossed() {
        List<ReviewDiffRow> rows = List.of(header("A.java"), line("A.java", 10),
                line("A.java", 11), line("A.java", 12));

        DiffLineSelection.Range range = DiffLineSelection.resolve(rows, 1, 3).orElseThrow();

        assertEquals("A.java", range.file());
        assertEquals("n10", range.startKey());
        assertEquals("n12", range.endKey());
    }

    @Test
    void aDragUpwardsIsTheSameRange() {
        List<ReviewDiffRow> rows = List.of(header("A.java"), line("A.java", 10),
                line("A.java", 11), line("A.java", 12));

        DiffLineSelection.Range range = DiffLineSelection.resolve(rows, 3, 1).orElseThrow();

        assertEquals("n10", range.startKey(), "the range is diff order, not gesture order");
        assertEquals("n12", range.endKey());
    }

    @Test
    void aDragAcrossAHunkHeaderClampsAtTheHunkItStartedIn() {
        // GitHub rejects a comment whose ends are in different hunks, and the
        // whole review fails with it -- so the selection never offers one.
        List<ReviewDiffRow> rows = List.of(header("A.java"), line("A.java", 10), line("A.java", 11),
                header("A.java"), line("A.java", 80), line("A.java", 81));

        DiffLineSelection.Range range = DiffLineSelection.resolve(rows, 1, 5).orElseThrow();

        assertEquals("n10", range.startKey());
        assertEquals("n11", range.endKey(), "clamped at the last line before the next hunk header");
    }

    @Test
    void aDragIntoAnotherFileClampsToo() {
        List<ReviewDiffRow> rows = List.of(header("A.java"), line("A.java", 10),
                header("B.java"), line("B.java", 5));

        DiffLineSelection.Range range = DiffLineSelection.resolve(rows, 1, 3).orElseThrow();

        assertEquals("A.java", range.file());
        assertEquals("n10", range.endKey());
    }

    @Test
    void aRangeSpanningACollapsedRunStaysLegal() {
        // CollapsedRun is keyed by (file, hunkIndex, runIndex) and never leaves
        // its hunk, so crossing one is not crossing a hunk boundary.
        List<ReviewDiffRow> rows = List.of(header("A.java"), line("A.java", 10),
                new ReviewDiffRow.CollapsedRun("A.java", 0, 0, 20, ReviewDiffRow.Edge.BODY),
                line("A.java", 31));

        DiffLineSelection.Range range = DiffLineSelection.resolve(rows, 1, 3).orElseThrow();

        assertEquals("n10", range.startKey());
        assertEquals("n31", range.endKey());
    }

    @Test
    void anAnchorOnANonLineRowSelectsNothing() {
        List<ReviewDiffRow> rows = List.of(header("A.java"), line("A.java", 10));

        assertTrue(DiffLineSelection.resolve(rows, 0, 1).isEmpty());
        assertEquals(Optional.empty(), DiffLineSelection.resolve(rows, 99, 1));
    }
}
