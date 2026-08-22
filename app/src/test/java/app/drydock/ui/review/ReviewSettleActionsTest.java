package app.drydock.ui.review;

import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
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

    /**
     * With the diff column acting AND a gutter selection open, {@code a}
     * must settle the hunk under the cursor -- not always the section's
     * first hunk. {@code FILE_A}'s second hunk is what gets selected, so
     * settling "hunk one, not the anchor" a second time (in a section
     * still holding an unsettled first hunk) is the one outcome that would
     * pass if HUNK mode quietly fell back to the anchor regardless of the
     * open selection.
     *
     * <p>A bare press, not a full click: {@link ReviewDiffColumn}'s gutter
     * finalizes a completed click by OPENING THE COMMENT COMPOSER and
     * moving real keyboard focus into its text field, which then swallows
     * {@code a} as a typed character rather than a shortcut ({@code
     * handleShortcut} explicitly declines while the event target is a
     * {@code TextInputControl}). {@code setOnMousePressed} alone already
     * paints the selection (see {@code extendSelection}), so a press with
     * no matching release proves the wiring end to end without also
     * hitting that focus steal -- which is a genuine seam this task found
     * and did not close: there is no discovered way, with the composer
     * unchanged, to both hold a gutter selection AND have {@code a}/
     * {@code r} read as shortcuts immediately afterward from the mouse
     * alone. Reported rather than worked around by loosening the
     * {@code TextInputControl} guard, which exists to keep the SAME key
     * from typing into an open composer.</p>
     */
    @Test
    void withAGutterSelectionOpenApproveSettlesTheSelectedHunkNotTheAnchor() {
        moveTo(gutterForFileASecondHunk());
        press(MouseButton.PRIMARY);
        try {
            press(KeyCode.A).release(KeyCode.A);
            WaitForAsyncUtils.waitForFxEvents();

            assertEquals(1, view.diagSectionState(0).settledHunks());
            assertTrue(host.store.verdict(scope.id(), digestOfSecondHunkOfFileA()).isPresent(),
                    "the SELECTED hunk must be the one settled");
            assertTrue(host.store.verdict(scope.id(), digestOfFirstHunkOfFileA()).isEmpty(),
                    "the anchor hunk must be untouched -- a selection was open");
        } finally {
            release(MouseButton.PRIMARY);
        }
    }

    /**
     * Asserts the RENDERED Approve button, not {@code view.settleUnit()}:
     * an assertion on the model alone shipped once already while the bar
     * itself still read "acts on: section" after a diff-column click,
     * because nothing re-rendered it -- a test that cannot catch the bug it
     * was written for is worse than no test.
     */
    @Test
    void theBarNamesTheUnitAnActionWillHit() {
        focusRail();
        assertEquals("Approve (section)", approveButtonText());

        focusDiffColumn();
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals("Approve (hunk)", approveButtonText());
    }

    private String approveButtonText() {
        String[] text = new String[1];
        interact(() -> text[0] = lookup(".review-verdict-action").queryAll().stream()
                .map(Button.class::cast)
                .map(Button::getText)
                .filter(t -> t.startsWith("Approve ("))
                .findFirst()
                .orElse("<no approve button found>"));
        return text[0];
    }
}
