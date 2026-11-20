package app.drydock.intellij.review;

import app.drydock.git.UnifiedDiff;
import com.intellij.diff.fragments.LineFragment;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AC13: the fragment builder maps hunk rows to {@code LineFragmentImpl}s
 * whose line ranges equal the changed-row runs and whose offsets equal the
 * line-start offsets in the passed texts. The assertions run against the
 * platform-free {@link ReviewFragments.Run} route; one test additionally
 * checks the {@code LineFragmentImpl} construction consumes a run verbatim.
 */
class ReviewFragmentsTest {

    // ---- fixtures ------------------------------------------------------

    private static UnifiedDiff.FileDiff file(String kind, UnifiedDiff.Hunk... hunks) {
        return new UnifiedDiff.FileDiff("a.txt", kind, 0, 0, false, false, List.of(hunks));
    }

    private static UnifiedDiff.Hunk hunk(UnifiedDiff.Line... lines) {
        return new UnifiedDiff.Hunk("@@", List.of(lines));
    }

    private static UnifiedDiff.Line ctx(int oldLine, int newLine, String text) {
        return new UnifiedDiff.Line(UnifiedDiff.Line.Kind.CONTEXT,
                OptionalInt.of(oldLine), OptionalInt.of(newLine), text);
    }

    private static UnifiedDiff.Line del(int oldLine, String text) {
        return new UnifiedDiff.Line(UnifiedDiff.Line.Kind.DEL,
                OptionalInt.of(oldLine), OptionalInt.empty(), text);
    }

    private static UnifiedDiff.Line add(int newLine, String text) {
        return new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD,
                OptionalInt.empty(), OptionalInt.of(newLine), text);
    }

    // ---- AC13 cases ------------------------------------------------------

    @Test
    void additionConsumesNewSideOnlyAndCollapsesOldSideToTheInsertionPoint() {
        String left = "one\ntwo\nthree\n";
        String right = "one\ntwo\nX\nthree\n";
        UnifiedDiff.FileDiff file = file("M", hunk(
                ctx(1, 1, "one"), ctx(2, 2, "two"), add(3, "X"), ctx(4, 4, "three")));

        assertEquals(List.of(new ReviewFragments.Run(
                2, 2, 2, 3,
                8, 8, 8, 10)), ReviewFragments.runsFor(file, left, right));
    }

    @Test
    void deletionConsumesOldSideOnlyAndCollapsesNewSideToTheInsertionPoint() {
        String left = "one\nX\ntwo\n";
        String right = "one\ntwo\n";
        UnifiedDiff.FileDiff file = file("M", hunk(
                ctx(1, 1, "one"), del(2, "X"), ctx(3, 2, "two")));

        assertEquals(List.of(new ReviewFragments.Run(
                1, 2, 1, 1,
                4, 6, 4, 4)), ReviewFragments.runsFor(file, left, right));
    }

    @Test
    void replaceConsumesBothSides() {
        String left = "a\nb\nc\n";
        String right = "a\nX\nc\n";
        UnifiedDiff.FileDiff file = file("M", hunk(
                ctx(1, 1, "a"), del(2, "b"), add(2, "X"), ctx(3, 3, "c")));

        assertEquals(List.of(new ReviewFragments.Run(
                1, 2, 1, 2,
                2, 4, 2, 4)), ReviewFragments.runsFor(file, left, right));
    }

    @Test
    void contextSplitsTwoRunsInsideOneGitHunk() {
        String left = "A\nB\nC\nD\nE\n";
        String right = "A\nX\nC\nD\nY\n";
        UnifiedDiff.FileDiff file = file("M", hunk(
                ctx(1, 1, "A"), del(2, "B"), add(2, "X"),
                ctx(3, 3, "C"), ctx(4, 4, "D"),
                del(5, "E"), add(5, "Y")));

        assertEquals(List.of(
                new ReviewFragments.Run(1, 2, 1, 2, 2, 4, 2, 4),
                new ReviewFragments.Run(4, 5, 4, 5, 8, 10, 8, 10)),
                ReviewFragments.runsFor(file, left, right));
    }

    @Test
    void severalHunksEachContributeTheirRuns() {
        String left = "0\n1\n2\n3\n4\n5\n6\n7\n8\n9\n";
        String right = "0\n1'\n2\n3\n4\n5\n6\n7\n8'\n9\n";
        UnifiedDiff.FileDiff file = file("M",
                hunk(ctx(1, 1, "0"), del(2, "1"), add(2, "1'"),
                        ctx(3, 3, "2"), ctx(4, 4, "3")),
                hunk(ctx(8, 8, "7"), del(9, "8"), add(9, "8'"), ctx(10, 10, "9")));

        assertEquals(List.of(
                new ReviewFragments.Run(1, 2, 1, 2, 2, 4, 2, 5),
                new ReviewFragments.Run(8, 9, 8, 9, 16, 18, 17, 20)),
                ReviewFragments.runsFor(file, left, right));
    }

    @Test
    void addedFileHasNoLeftTextAndCollapsesItAtOffsetZero() {
        UnifiedDiff.FileDiff file = file("A", hunk(add(1, "a"), add(2, "b")));

        assertEquals(List.of(new ReviewFragments.Run(
                0, 0, 0, 2,
                0, 0, 0, 4)), ReviewFragments.runsFor(file, "", "a\nb\n"));
    }

    @Test
    void deletedFileHasNoRightTextAndCollapsesItAtOffsetZero() {
        UnifiedDiff.FileDiff file = file("D", hunk(del(1, "a"), del(2, "b")));

        assertEquals(List.of(new ReviewFragments.Run(
                0, 2, 0, 0,
                0, 4, 0, 0)), ReviewFragments.runsFor(file, "a\nb\n", ""));
    }

    @Test
    void endOffsetClampsToTextLengthWhenTheFileHasNoTrailingNewline() {
        String left = "a\nb\nc";
        String right = "a\nb\nC";
        UnifiedDiff.FileDiff file = file("M", hunk(
                ctx(1, 1, "a"), ctx(2, 2, "b"), del(3, "c"), add(3, "C")));

        // Line 3 starts at 4 on both sides, but the text ends at 5: the end
        // offset clamps to 5, not the nonexistent start of a following line.
        assertEquals(List.of(new ReviewFragments.Run(
                2, 3, 2, 3,
                4, 5, 4, 5)), ReviewFragments.runsFor(file, left, right));
    }

    @Test
    void crlfLineTerminatorsArePartOfTheOffsets() {
        String left = "a\r\nb\r\nc\r\n";
        String right = "a\r\nB\r\nc\r\n";
        UnifiedDiff.FileDiff file = file("M", hunk(
                ctx(1, 1, "a"), del(2, "b"), add(2, "B"), ctx(3, 3, "c")));

        // Line 2 spans offsets 3..6, terminator CR included.
        assertEquals(List.of(new ReviewFragments.Run(
                1, 2, 1, 2,
                3, 6, 3, 6)), ReviewFragments.runsFor(file, left, right));
    }

    /**
     * The preview's contract: the texts are LF-normalized before the
     * fragments are computed (ReviewContents), so the offsets are the
     * normalized text's "line 2 spans 2..4", never the CRLF text's 3..6,
     * and they address the very texts the plan (and the documents built
     * from it) carries; the plan refuses the raw CRLF text outright.
     */
    @Test
    void crlfTextIsNormalizedBeforeTheOffsetsAreComputedAndThePlanRefusesRawCr() {
        String left = ReviewContents.normalizeLineSeparators("a\r\nb\r\nc\r\n");
        String right = ReviewContents.normalizeLineSeparators("a\r\nB\r\nc\r\n");
        UnifiedDiff.FileDiff file = file("M", hunk(
                ctx(1, 1, "a"), del(2, "b"), add(2, "B"), ctx(3, 3, "c")));

        assertEquals(List.of(new ReviewFragments.Run(
                1, 2, 1, 2,
                2, 4, 2, 4)), ReviewFragments.runsFor(file, left, right));
        assertEquals("B\n", right.substring(2, 4), "the offsets address the normalized text's line 2");

        List<LineFragment> fragments = ReviewFragments.fragmentsFor(file, left, right);
        ReviewDiffRequests.Plan plan = ReviewDiffRequests.plan(file, left, right, fragments);
        assertEquals(right, plan.rightText().toString(), "the plan carries the text the offsets were computed over");
        assertThrows(IllegalArgumentException.class,
                () -> ReviewDiffRequests.plan(file, "a\r\nb\r\nc\r\n", "a\r\nB\r\nc\r\n", fragments));
    }

    // ---- the platform seam ------------------------------------------------

    @Test
    void fragmentsForConsumesRunsVerbatimWithNoInnerFragments() {
        String left = "one\nX\ntwo\n";
        String right = "one\ntwo\n";
        UnifiedDiff.FileDiff file = file("M", hunk(
                ctx(1, 1, "one"), del(2, "X"), ctx(3, 2, "two")));
        ReviewFragments.Run run = ReviewFragments.runsFor(file, left, right).getFirst();

        List<LineFragment> fragments = ReviewFragments.fragmentsFor(file, left, right);

        assertEquals(1, fragments.size());
        LineFragment fragment = fragments.get(0);
        assertEquals(run.startLine1(), fragment.getStartLine1());
        assertEquals(run.endLine1(), fragment.getEndLine1());
        assertEquals(run.startLine2(), fragment.getStartLine2());
        assertEquals(run.endLine2(), fragment.getEndLine2());
        assertEquals(run.startOffset1(), fragment.getStartOffset1());
        assertEquals(run.endOffset1(), fragment.getEndOffset1());
        assertEquals(run.startOffset2(), fragment.getStartOffset2());
        assertEquals(run.endOffset2(), fragment.getEndOffset2());
        assertTrue(fragment.getInnerFragments().isEmpty());
    }

    @Test
    void fragmentsForCollapsesTheMissingSideOfAnAddedFileAtOffsetZero() {
        UnifiedDiff.FileDiff file = file("A", hunk(add(1, "a"), add(2, "b")));

        List<LineFragment> fragments = ReviewFragments.fragmentsFor(file, "", "a\nb\n");

        assertEquals(1, fragments.size());
        LineFragment fragment = fragments.get(0);
        assertEquals(0, fragment.getStartLine1());
        assertEquals(0, fragment.getEndLine1());
        assertEquals(0, fragment.getStartOffset1());
        assertEquals(0, fragment.getEndOffset1());
        assertEquals(2, fragment.getEndLine2());
        assertEquals(4, fragment.getEndOffset2());
    }
}
