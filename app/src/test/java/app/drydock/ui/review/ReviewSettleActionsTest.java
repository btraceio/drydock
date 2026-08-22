package app.drydock.ui.review;

import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading is per hunk; settling usually is not (spec §9.6). The unit follows
 * focus rather than adding a parallel key set -- the same rule {@code [} and
 * {@code ]} already follow -- and the bar names the unit, because a key whose
 * target depends on focus must say what it is about to do.
 */
class ReviewSettleActionsTest extends ReviewViewFixture {

    @Test
    void withTheRailFocusedApproveSettlesTheWholeSection() {
        focusRail();
        press(KeyCode.A).release(KeyCode.A);
        WaitForAsyncUtils.waitForFxEvents();

        SectionStates.SectionState state = view.diagSectionState(0);
        assertEquals(state.totalHunks(), state.settledHunks());
    }

    @Test
    void withTheDiffColumnFocusedApproveSettlesOneHunk() {
        focusDiffColumn();
        press(KeyCode.A).release(KeyCode.A);
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(1, view.diagSectionState(0).settledHunks());
    }

    @Test
    void shiftApproveSettlesEveryHunkOfTheCurrentFile() {
        focusDiffColumn();
        press(KeyCode.SHIFT).press(KeyCode.A).release(KeyCode.A).release(KeyCode.SHIFT);
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(hunkCountOfCurrentFile(), view.diagSectionState(0).settledHunks());
    }

    /** Settling a shared hunk has to be visible where it lands. */
    @Test
    void settlingASectionShowsItsSharedHunksSettledInTheOtherSection() {
        focusRail();
        press(KeyCode.A).release(KeyCode.A);
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(view.diagSectionState(1).settledElsewhere().contains("①"),
                "section ② must name ① as where its shared hunk was settled, got: "
                        + view.diagSectionState(1).settledElsewhere());
    }

    @Test
    void theBarNamesTheUnitAnActionWillHit() {
        focusRail();
        assertEquals(SessionReviewView.SettleUnit.SECTION, view.settleUnit());

        focusDiffColumn();
        assertEquals(SessionReviewView.SettleUnit.HUNK, view.settleUnit());
    }
}
