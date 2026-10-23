package app.drydock.intellij.review;

import app.drydock.git.UnifiedDiff;
import app.drydock.intellij.review.ReviewDiffRequests.Plan;
import com.intellij.diff.fragments.DiffFragment;
import com.intellij.diff.fragments.LineFragment;
import com.intellij.diff.fragments.LineFragmentImpl;
import com.intellij.diff.util.DiffUserDataKeys;
import com.intellij.diff.util.DiffUserDataKeysEx;
import com.intellij.diff.util.Side;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.UserDataHolderBase;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Headless AC16 tests for the pure half of {@link ReviewDiffRequests}: the
 * {@link Plan} record, the builders, the line-key mapping, and the user data
 * the request will carry. {@code toRequest} needs a running IDE and is
 * exercised live (AC24); everything it puts on the request comes from
 * {@code applyUserData}, which is asserted here.
 */
class ReviewDiffRequestsTest {

    private static final LineFragment FRAGMENT =
            new LineFragmentImpl(0, 1, 0, 1, 0, 3, 0, 3, List.<DiffFragment>of());

    // --- fixtures ------------------------------------------------------------

    private static UnifiedDiff.Line context(int oldLine, int newLine) {
        return new UnifiedDiff.Line(UnifiedDiff.Line.Kind.CONTEXT,
                OptionalInt.of(oldLine), OptionalInt.of(newLine), "ctx");
    }

    private static UnifiedDiff.Line add(int newLine) {
        return new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD,
                OptionalInt.empty(), OptionalInt.of(newLine), "added");
    }

    private static UnifiedDiff.Line del(int oldLine) {
        return new UnifiedDiff.Line(UnifiedDiff.Line.Kind.DEL,
                OptionalInt.of(oldLine), OptionalInt.empty(), "deleted");
    }

    private static UnifiedDiff.FileDiff file(UnifiedDiff.Line... lines) {
        return new UnifiedDiff.FileDiff("src/A.java", "M", 0, 0, false, false,
                List.of(new UnifiedDiff.Hunk("@@", List.of(lines))));
    }

    private static Plan planFor(UnifiedDiff.FileDiff file) {
        return ReviewDiffRequests.plan(file, "left", "right", List.of(FRAGMENT));
    }

    // --- AC16: the file row scrolls to the first added line ------------------

    @Test
    void fileRowScrollsToFirstAddedLineOnTheRight() {
        UnifiedDiff.FileDiff file = file(context(1, 1), add(2), add(3), context(4, 4));
        Plan plan = planFor(file);
        assertEquals(Side.RIGHT, plan.scrollToSide());
        assertEquals(1, plan.scrollToLine()); // first add row, newLine 2, minus 1
    }

    @Test
    void fileRowPrefersTheFirstAddedLineEvenWhenADeletionComesFirst() {
        UnifiedDiff.FileDiff file = file(del(5), context(6, 4), add(7));
        Plan plan = planFor(file);
        assertEquals(Side.RIGHT, plan.scrollToSide());
        assertEquals(6, plan.scrollToLine()); // add row newLine 7, minus 1
    }

    @Test
    void deletionOnlyFileRowScrollsToTheFirstDeletedLineOnTheLeft() {
        UnifiedDiff.FileDiff file = file(context(2, 2), del(3), del(4));
        Plan plan = planFor(file);
        assertEquals(Side.LEFT, plan.scrollToSide());
        assertEquals(2, plan.scrollToLine()); // first deleted oldLine 3, minus 1
    }

    @Test
    void fileRowWithoutChangedLinesScrollsToTheTopOfTheRightSide() {
        UnifiedDiff.FileDiff file = file(context(1, 1), context(2, 2));
        Plan plan = planFor(file);
        assertEquals(Side.RIGHT, plan.scrollToSide());
        assertEquals(0, plan.scrollToLine());
    }

    // --- AC16: the line-key mapping, key -> position only --------------------

    @Test
    void nKeyScrollsToTheRightSideOneLineBeforeTheGitLine() {
        ReviewDiffRequests.ScrollTarget target = ReviewDiffRequests.scrollTarget("n12");
        assertEquals(Side.RIGHT, target.side());
        assertEquals(11, target.line());
    }

    @Test
    void oKeyScrollsToTheLeftSideOneLineBeforeTheGitLine() {
        ReviewDiffRequests.ScrollTarget target = ReviewDiffRequests.scrollTarget("o7");
        assertEquals(Side.LEFT, target.side());
        assertEquals(6, target.line());
    }

    @Test
    void findingRowPlanScrollsToTheMappedKeyPosition() {
        Plan nPlan = ReviewDiffRequests.plan("left", "right", List.of(FRAGMENT),
                ReviewDiffRequests.scrollTarget("n42"));
        assertEquals(Side.RIGHT, nPlan.scrollToSide());
        assertEquals(41, nPlan.scrollToLine());

        Plan oPlan = ReviewDiffRequests.plan("left", "right", List.of(FRAGMENT),
                ReviewDiffRequests.scrollTarget("o3"));
        assertEquals(Side.LEFT, oPlan.scrollToSide());
        assertEquals(2, oPlan.scrollToLine());
    }

    @Test
    void malformedLineKeysAreRefusedNeverReAnchored() {
        for (String bad : new String[]{"x3", "n", "o", "nabc", "n-4", "", "42"}) {
            assertThrows(IllegalArgumentException.class, () -> ReviewDiffRequests.scrollTarget(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> ReviewDiffRequests.scrollTarget(null));
    }

    // --- AC16: the request user data -----------------------------------------

    @Test
    void requestUserDataCarriesExactlyTheFourReviewKeys() {
        Plan plan = planFor(file(add(2)));
        UserDataHolderBase holder = new UserDataHolderBase();
        ReviewDiffRequests.applyUserData(holder, plan);

        Set<Key<?>> keys = Stream.of(holder.getUserMap().getKeys()).collect(Collectors.toSet());
        assertEquals(Set.of(
                DiffUserDataKeys.FORCE_READ_ONLY,
                DiffUserDataKeysEx.CUSTOM_DIFF_COMPUTER,
                DiffUserDataKeys.SCROLL_TO_LINE,
                ReviewDiffRequests.REQUEST_KEY), keys);
    }

    @Test
    void requestUserDataIsReadOnlyAndCarriesTheScrollLineAndRequestKey() {
        UnifiedDiff.FileDiff file = file(del(9));
        Plan plan = planFor(file);
        UserDataHolderBase holder = new UserDataHolderBase();
        ReviewDiffRequests.applyUserData(holder, plan);

        assertEquals(Boolean.TRUE, holder.getUserData(DiffUserDataKeys.FORCE_READ_ONLY));
        assertEquals(Pair.create(Side.LEFT, 8), holder.getUserData(DiffUserDataKeys.SCROLL_TO_LINE));
        assertEquals(Boolean.TRUE, holder.getUserData(ReviewDiffRequests.REQUEST_KEY));
    }

    @Test
    void customDiffComputerReturnsThePrecomputedFragmentsWhateverItIsAsked() {
        Plan plan = planFor(file(add(2)));
        UserDataHolderBase holder = new UserDataHolderBase();
        ReviewDiffRequests.applyUserData(holder, plan);

        DiffUserDataKeysEx.DiffComputer computer = holder.getUserData(DiffUserDataKeysEx.CUSTOM_DIFF_COMPUTER);
        // Pure: the inputs are ignored, the precomputed list comes back.
        assertSame(plan.fragments(), computer.compute("anything", "else", null, true, null));
        assertSame(plan.fragments(), computer.compute(null, null, null, false, null));
    }

    @Test
    void theRequestKeyIsTheOneStaticKeyMintedFromItsName() {
        // The static key is minted from REQUEST_KEY_NAME (its name is the
        // constant, not some other string).
        assertEquals(ReviewDiffRequests.REQUEST_KEY_NAME, ReviewDiffRequests.REQUEST_KEY.toString());
        // Key identity is per-instance: a re-minted key with the same name
        // is a different key, which is why the single static REQUEST_KEY is
        // the one every reader must use.
        assertNotEquals(ReviewDiffRequests.REQUEST_KEY, Key.create(ReviewDiffRequests.REQUEST_KEY_NAME));
    }

    @Test
    void aPlanRefusesTextCarryingACarriageReturn() {
        // EditorFactory.createDocument throws on a CR, and the fragments
        // must address the very text the documents hold: the plan accepts
        // only LF-normalized text (ReviewContents normalizes both sides).
        UnifiedDiff.FileDiff file = file(context(1, 1), add(2));
        assertThrows(IllegalArgumentException.class,
                () -> ReviewDiffRequests.plan(file, "ctx\r\n", "ctx\nadded\n", List.of(FRAGMENT)));
        assertThrows(IllegalArgumentException.class,
                () -> ReviewDiffRequests.plan(file, "ctx\n", "ctx\r\nadded\r\n", List.of(FRAGMENT)));
        Plan plan = ReviewDiffRequests.plan(file,
                ReviewContents.normalizeLineSeparators("ctx\r\n"),
                ReviewContents.normalizeLineSeparators("ctx\r\nadded\r\n"), List.of(FRAGMENT));
        assertEquals("ctx\nadded\n", plan.rightText().toString());
    }

    // --- the plan carries its inputs ------------------------------------------

    @Test
    void planCarriesTheTextsAndFragmentsItWasBuiltWith() {
        Plan plan = ReviewDiffRequests.plan(file(add(2)), "left text", "right text", List.of(FRAGMENT));
        assertEquals("left text", plan.leftText().toString());
        assertEquals("right text", plan.rightText().toString());
        assertEquals(List.of(FRAGMENT), plan.fragments());
    }

    @Test
    void fileRowPlanAcrossSeveralHunksFindsTheFirstAddRow() {
        UnifiedDiff.FileDiff file = new UnifiedDiff.FileDiff("src/B.java", "M", 2, 1, false, false,
                List.of(
                        new UnifiedDiff.Hunk("@@", List.of(context(1, 1), del(2))),
                        new UnifiedDiff.Hunk("@@", List.of(context(3, 2), add(3), add(4)))));
        Plan plan = planFor(file);
        assertEquals(Side.RIGHT, plan.scrollToSide());
        assertEquals(2, plan.scrollToLine()); // hunk 2's first add row, newLine 3, minus 1
    }
}
