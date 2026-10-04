package app.drydock.ui.review;

import app.drydock.review.tour.StepProgress;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** In tour mode the verdict bar and the diff header talk about steps, not hunks and files. */
class ReviewTourWordingTest extends ReviewTourFixture {

    private boolean shown(String text) {
        return lookup(text).tryQuery().filter(ReviewTourWordingTest::treeVisible).isPresent();
    }

    private static boolean treeVisible(Node node) {
        for (Node at = node; at != null; at = at.getParent()) {
            if (!at.isVisible()) {
                return false;
            }
        }
        return true;
    }

    private String text(String selector) {
        return ReviewDiagFxThread.call(() -> ((Labeled) lookup(selector).query()).getText());
    }

    @Test
    void theVerdictBarNamesTheStepAndCountsSteps() {
        assertTrue(shown("Approve step"));
        assertTrue(shown("Request changes on step"));
        assertFalse(shown("Ask the agent to fix it"), "send-back lives in the blocker banner in a tour");
        assertEquals("0/2 steps reviewed", text(".review-verdict-progress-label"));
        assertEquals("2 steps left · n jumps to the next", text(".review-verdict-hint"));
    }

    @Test
    void aStepWithChangesRequestedIsDecidedAndNoLongerCountsAsLeft() {
        interact(() -> {
            host.tours.mutate(scope.id(), record -> record.withProgress(record.progress("s1")
                    .withDecision(StepProgress.Decision.CHANGES, Optional.empty())));
            view.refreshReviewState();
        });
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals("1/2 steps reviewed", text(".review-verdict-progress-label"));
        assertEquals("1 step left · n jumps to the next", text(".review-verdict-hint"));
    }

    @Test
    void theDiffHeaderShowsTheCurrentStep() {
        assertEquals("step 1 of 2 · Guards header", text(".review-diff-summary"));

        press(KeyCode.CLOSE_BRACKET).release(KeyCode.CLOSE_BRACKET);
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals("step 2 of 2 · Guards source", text(".review-diff-summary"));
    }

    @Test
    void enteringTheHunkDiffPutsTheCurrentFileAndItsHunkCountsOnTheBarAtOnce() {
        press(KeyCode.V).release(KeyCode.V);
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals("1/2 · " + FILE_A, text(".review-verdict-target"), "no [ / ] needed to get off the step");
        assertEquals("0/3 hunks reviewed", text(".review-verdict-progress-label"),
                "every hunk of the diff, not the tour's 2 steps");
        assertEquals("3 hunks left · n jumps to the next", text(".review-verdict-hint"));
        assertTrue(shown("Submit (3 left)"));
    }

    @Test
    void theHunkDiffKeepsItsOwnWording() {
        press(KeyCode.V).release(KeyCode.V);
        WaitForAsyncUtils.waitForFxEvents();

        assertFalse(shown("Approve step"));
        assertTrue(text(".review-verdict-progress-label").endsWith("hunks reviewed"));
        assertFalse(text(".review-diff-summary").startsWith("step "));
        assertTrue(lookup(".review-diff-summary").query() instanceof Label);
    }
}
