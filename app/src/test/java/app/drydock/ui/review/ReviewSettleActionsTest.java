package app.drydock.ui.review;

import app.drydock.review.HunkDigest;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading is per hunk, and so is settling (spec §9.6): {@code a}/{@code r}
 * act on the current file's next unread hunk wherever focus is, {@code
 * ⇧A}/{@code ⇧R} on the whole file, and the bar names the unit its buttons
 * will hit.
 */
class ReviewSettleActionsTest extends ReviewViewFixture {

    @Test
    void approveWithNothingFocusedSettlesOneHunk() {
        interact(view::requestFocus);
        press(KeyCode.A).release(KeyCode.A);
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(1, settledHunksOf(FILE_A));
        assertTrue(host.store.verdict(scope.id(), digestOfFirstHunkOfFileA()).isPresent());
    }

    @Test
    void withTheDiffColumnFocusedApproveSettlesOneHunk() throws TimeoutException {
        focusDiffColumn();
        String afterFocus = view.diagFocusSnapshot();
        press(KeyCode.A).release(KeyCode.A);
        WaitForAsyncUtils.waitForFxEvents();
        String afterPress = view.diagFocusSnapshot();

        assertEquals(1, settledHunksOf(FILE_A),
                () -> "after focusDiffColumn(): " + afterFocus + " | after a-press: " + afterPress);
    }

    @Test
    void shiftApproveSettlesEveryHunkOfTheCurrentFile() throws TimeoutException {
        focusDiffColumn();
        press(KeyCode.SHIFT).press(KeyCode.A).release(KeyCode.A).release(KeyCode.SHIFT);
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(hunkCountOfCurrentFile(), settledHunksOf(FILE_A));
        assertEquals(0, settledHunksOf(FILE_B), "⇧A is the current file, not the review");
    }

    /**
     * With a gutter selection open, {@code a} must settle the hunk under the
     * cursor -- not the file's first unread hunk. {@code FILE_A}'s second
     * hunk is what gets selected, while its first is still unread, so this
     * is the one outcome that would pass if {@code a} quietly fell back to
     * the first unread hunk regardless of the open selection.
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

            assertEquals(1, settledHunksOf(FILE_A));
            assertTrue(host.store.verdict(scope.id(), digestOfSecondHunkOfFileA()).isPresent(),
                    "the SELECTED hunk must be the one settled");
            assertTrue(host.store.verdict(scope.id(), digestOfFirstHunkOfFileA()).isEmpty(),
                    "the first unread hunk must be untouched -- a selection was open");
        } finally {
            release(MouseButton.PRIMARY);
        }
    }

    /**
     * Asserts the RENDERED Approve button: the unit no longer depends on
     * focus, so the button reads the same with the diff column focused as
     * without.
     */
    @Test
    void theBarNamesTheUnitAnActionWillHitWhereverFocusIs() throws TimeoutException {
        assertEquals("Approve (next unread hunk)", approveButtonText());

        focusDiffColumn();
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals("Approve (next unread hunk)", approveButtonText());
    }

    /**
     * A real press-then-release on the bar's Approve button (not {@code
     * fire()}): pressing moves Scene focus onto the button, and the press
     * must still settle exactly one hunk of the file the bar names.
     */
    @Test
    void aRealMousePressOnApproveSettlesOneHunk() throws TimeoutException {
        focusDiffColumn();

        clickOn(".review-verdict-action");
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(1, settledHunksOf(FILE_A));
    }

    /**
     * The bar's Approve is a settle like {@code a}: {@code u} afterwards
     * undoes what the click recorded, not the keyboard settle before it.
     */
    @Test
    void undoAfterAMouseSettleUndoesTheMouseSettle() {
        interact(view::requestFocus);
        press(KeyCode.A).release(KeyCode.A);
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(host.store.verdict(scope.id(), digestOfFirstHunkOfFileA()).isPresent());

        clickOn(".review-verdict-action");
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(host.store.verdict(scope.id(), digestOfSecondHunkOfFileA()).isPresent());

        interact(view::requestFocus);
        press(KeyCode.U).release(KeyCode.U);
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(host.store.verdict(scope.id(), digestOfSecondHunkOfFileA()).isEmpty(),
                "u undoes the click's settle");
        assertTrue(host.store.verdict(scope.id(), digestOfFirstHunkOfFileA()).isPresent(),
                "and leaves the keyboard settle before it alone");
    }

    private long settledHunksOf(String file) {
        return host.diff.files().stream()
                .filter(candidate -> candidate.path().equals(file))
                .flatMap(candidate -> candidate.hunks().stream()
                        .map(hunk -> HunkDigest.of(file, hunk)))
                .filter(digest -> host.store.verdict(scope.id(), digest).isPresent())
                .count();
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
