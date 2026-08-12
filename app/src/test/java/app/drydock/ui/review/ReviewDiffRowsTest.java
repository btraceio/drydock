package app.drydock.ui.review;

import app.drydock.git.UnifiedDiff;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The diff column's row model (spec §4.4), headless. */
class ReviewDiffRowsTest {

    private static ReviewDiffRows.Options defaults() {
        return ReviewDiffRows.Options.defaults(3000);
    }

    @Test
    void eachHunkBecomesOneCardWithAHeaderAndAClosedBottom() {
        UnifiedDiff diff = diff(file("Sidebar.java",
                hunk(context(112), add(113), context(114))));

        List<ReviewDiffRow> rows = ReviewDiffRows.build(diff, defaults());

        assertEquals(4, rows.size());
        assertInstanceOf(ReviewDiffRow.HunkHeader.class, rows.get(0));
        assertEquals(ReviewDiffRow.Edge.TOP, rows.get(0).edge());
        assertEquals(ReviewDiffRow.Edge.BODY, rows.get(1).edge());
        assertEquals(ReviewDiffRow.Edge.BODY, rows.get(2).edge());
        assertEquals(ReviewDiffRow.Edge.BOTTOM, rows.get(3).edge(),
                "the card's last row must close it, or the card has no bottom border");
    }

    @Test
    void theHeaderCarriesTheFileAndItsLineRange() {
        UnifiedDiff diff = diff(file("Sidebar.java", hunk(context(112), add(113), context(124))));

        ReviewDiffRow.HunkHeader header =
                (ReviewDiffRow.HunkHeader) ReviewDiffRows.build(diff, defaults()).get(0);

        assertEquals("Sidebar.java", header.file());
        assertEquals("L112–124", header.range());
        assertEquals(113, header.startLine(),
                "the ⤢ jumps to the first CHANGED line, not to the leading context");
    }

    /**
     * A hunk opens with several context lines, so jumping to its first line
     * landed the Explorer above everything the reader clicked ⤢ to see.
     */
    @Test
    void theJumpTargetSkipsTheLeadingContext() {
        UnifiedDiff diff = diff(file("A.java", hunk(
                context(40), context(41), context(42), add(43), context(44))));

        ReviewDiffRow.HunkHeader header =
                (ReviewDiffRow.HunkHeader) ReviewDiffRows.build(diff, defaults()).get(0);

        assertEquals(43, header.startLine());
    }

    /**
     * A deletion has no new-file line of its own; the context line that
     * follows it is where the deleted code used to be, and the closest thing
     * to it that still exists in the file.
     */
    @Test
    void aPureDeletionJumpsToTheLineAfterIt() {
        UnifiedDiff diff = diff(file("A.java", hunk(
                context(10), del(11), del(12), context(11))));

        ReviewDiffRow.HunkHeader header =
                (ReviewDiffRow.HunkHeader) ReviewDiffRows.build(diff, defaults()).get(0);

        assertEquals(11, header.startLine());
    }

    /** An all-context hunk has no change to aim at; the old behaviour stands. */
    @Test
    void aHunkWithNoChangeFallsBackToItsFirstLine() {
        UnifiedDiff diff = diff(file("A.java", hunk(context(7), context(8))));

        assertEquals(7, ReviewDiffRows.startLine(
                new UnifiedDiff.Hunk("@@ @@", List.of(context(7), context(8)))));
        assertFalse(ReviewDiffRows.build(diff, defaults()).isEmpty());
    }

    @Test
    void aLongRunOfUnchangedLinesCollapses() {
        UnifiedDiff diff = diff(file("A.java", hunk(
                context(1), context(2), context(3), context(4), context(5), context(6), add(7))));

        List<ReviewDiffRow> rows = ReviewDiffRows.build(diff, defaults());

        ReviewDiffRow.CollapsedRun run = (ReviewDiffRow.CollapsedRun) rows.get(1);
        assertEquals(6, run.count());
        assertEquals(3, rows.size(), "the six context lines must become one row");
    }

    @Test
    void aShortRunOfUnchangedLinesIsCheaperToReadThanToCollapse() {
        UnifiedDiff diff = diff(file("A.java", hunk(context(1), context(2), add(3))));

        List<ReviewDiffRow> rows = ReviewDiffRows.build(diff, defaults());

        assertTrue(rows.stream().noneMatch(ReviewDiffRow.CollapsedRun.class::isInstance));
        assertEquals(4, rows.size());
    }

    @Test
    void expandingOneRunLeavesTheOthersCollapsed() {
        UnifiedDiff diff = diff(file("A.java", hunk(
                context(1), context(2), context(3), context(4), context(5),
                add(6),
                context(7), context(8), context(9), context(10), context(11))));

        List<ReviewDiffRow> collapsed = ReviewDiffRows.build(diff, defaults());
        assertEquals(2, collapsed.stream().filter(ReviewDiffRow.CollapsedRun.class::isInstance).count());

        ReviewDiffRow.RunKey first = ((ReviewDiffRow.CollapsedRun) collapsed.get(1)).key();
        List<ReviewDiffRow> expanded = ReviewDiffRows.build(diff,
                new ReviewDiffRows.Options(true, Set.of(first), 3000));

        assertEquals(5, expanded.stream().filter(ReviewDiffRow.Line.class::isInstance)
                .filter(row -> ((ReviewDiffRow.Line) row).line().kind() == UnifiedDiff.Line.Kind.CONTEXT)
                .count(), "only the expanded run's lines come back");
        assertEquals(1, expanded.stream().filter(ReviewDiffRow.CollapsedRun.class::isInstance).count());
    }

    /** A run's identity must not be its line numbers, or a re-diff re-collapses what the user opened. */
    @Test
    void runKeysAreStableAcrossAReDiffThatShiftsLineNumbers() {
        UnifiedDiff before = diff(file("A.java", hunk(
                context(1), context(2), context(3), context(4), context(5), add(6))));
        UnifiedDiff after = diff(file("A.java", hunk(
                context(41), context(42), context(43), context(44), context(45), add(46))));

        ReviewDiffRow.RunKey keyBefore = ((ReviewDiffRow.CollapsedRun)
                ReviewDiffRows.build(before, defaults()).get(1)).key();
        List<ReviewDiffRow> rows = ReviewDiffRows.build(after,
                new ReviewDiffRows.Options(true, Set.of(keyBefore), 3000));

        assertTrue(rows.stream().noneMatch(ReviewDiffRow.CollapsedRun.class::isInstance),
                "the same run must stay expanded after the lines move");
    }

    /** `c` drops unchanged lines outright -- a collapsed-run row would still be a row about context. */
    @Test
    void hidingContextDropsUnchangedLinesEntirely() {
        UnifiedDiff diff = diff(file("A.java", hunk(
                context(1), context(2), context(3), context(4), context(5), add(6), del(7))));

        List<ReviewDiffRow> rows = ReviewDiffRows.build(diff,
                new ReviewDiffRows.Options(false, Set.of(), 3000));

        assertTrue(rows.stream().noneMatch(ReviewDiffRow.CollapsedRun.class::isInstance));
        assertEquals(3, rows.size(), "header plus the two changed lines");
        assertEquals(ReviewDiffRow.Edge.BOTTOM, rows.get(2).edge());
    }

    @Test
    void aHunkWithNothingLeftToShowProducesNoEmptyCard() {
        UnifiedDiff diff = diff(file("A.java",
                hunk(context(1), context(2)),
                hunk(add(9))));

        List<ReviewDiffRow> rows = ReviewDiffRows.build(diff,
                new ReviewDiffRows.Options(false, Set.of(), 3000));

        assertEquals(2, rows.size());
        assertEquals("A.java", ((ReviewDiffRow.HunkHeader) rows.get(0)).file());
    }

    @Test
    void everyFileContributesItsOwnCards() {
        UnifiedDiff diff = new UnifiedDiff(List.of(
                file("A.java", hunk(add(1))),
                file("B.java", hunk(add(1), add(2)))));

        List<ReviewDiffRow> rows = ReviewDiffRows.build(diff, defaults());

        List<String> headers = rows.stream()
                .filter(ReviewDiffRow.HunkHeader.class::isInstance)
                .map(row -> ((ReviewDiffRow.HunkHeader) row).file())
                .toList();
        assertEquals(List.of("A.java", "B.java"), headers);
    }

    /** The cap is a notice, never a silent drop. */
    @Test
    void theRenderCapTruncatesWithANotice() {
        List<UnifiedDiff.Hunk> hunks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            hunks.add(hunk(add(i + 1), add(i + 2)));
        }
        UnifiedDiff diff = diff(new UnifiedDiff.FileDiff("A.java", "M", 100, 0, false, false, hunks));

        List<ReviewDiffRow> rows = ReviewDiffRows.build(diff, new ReviewDiffRows.Options(true, Set.of(), 12));

        assertInstanceOf(ReviewDiffRow.Truncation.class, rows.get(rows.size() - 1));
        assertTrue(rows.size() <= 13, "expected the cap to hold, got " + rows.size());
    }

    @Test
    void anEmptyDiffSaysSoInsteadOfRenderingNothing() {
        List<ReviewDiffRow> rows = ReviewDiffRows.build(new UnifiedDiff(List.of()), defaults());

        assertEquals(1, rows.size());
        assertEquals("No changes in this scope.", ((ReviewDiffRow.Message) rows.get(0)).text());
    }

    @Test
    void lineRowsCarryTheStableAnchorKey() {
        UnifiedDiff diff = diff(file("A.java", hunk(add(7), del(3))));

        List<ReviewDiffRow> rows = ReviewDiffRows.build(diff, defaults());

        assertEquals("n7", ((ReviewDiffRow.Line) rows.get(1)).lineKey());
        assertEquals("o3", ((ReviewDiffRow.Line) rows.get(2)).lineKey());
        assertFalse(rows.isEmpty());
    }

    // ---- fixtures -----------------------------------------------------------

    private static UnifiedDiff diff(UnifiedDiff.FileDiff... files) {
        return new UnifiedDiff(List.of(files));
    }

    private static UnifiedDiff.FileDiff file(String path, UnifiedDiff.Hunk... hunks) {
        return new UnifiedDiff.FileDiff(path, "M", 0, 0, false, false, List.of(hunks));
    }

    private static UnifiedDiff.Hunk hunk(UnifiedDiff.Line... lines) {
        return new UnifiedDiff.Hunk("@@ hunk @@", List.of(lines));
    }

    private static UnifiedDiff.Line context(int line) {
        return new UnifiedDiff.Line(UnifiedDiff.Line.Kind.CONTEXT,
                OptionalInt.of(line), OptionalInt.of(line), "    context " + line);
    }

    private static UnifiedDiff.Line add(int line) {
        return new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD,
                OptionalInt.empty(), OptionalInt.of(line), "    added " + line);
    }

    private static UnifiedDiff.Line del(int line) {
        return new UnifiedDiff.Line(UnifiedDiff.Line.Kind.DEL,
                OptionalInt.of(line), OptionalInt.empty(), "    deleted " + line);
    }
}
