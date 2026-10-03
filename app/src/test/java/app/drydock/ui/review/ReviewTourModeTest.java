package app.drydock.ui.review;

import app.drydock.review.ReviewVerdict;
import app.drydock.review.tour.TourRecord;
import app.drydock.review.tour.StepProgress;
import javafx.scene.Node;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewTourModeTest extends ReviewTourFixture {

    @Test
    void aScopeWithATourOpensInTourModeOnTheFirstStep() {
        assertEquals(SessionReviewView.ReviewMode.TOUR, ReviewDiagFxThread.call(view::diagMode));
        assertEquals("s1", ReviewDiagFxThread.call(view::diagCurrentStepId));
    }

    @Test
    void approvingBeforeTheCheckIsAnsweredDoesNotPassTheStep() {
        press(KeyCode.A).release(KeyCode.A);
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(StepProgress.Decision.NONE, progress("s1").decision());
    }

    @Test
    void answeringRightThenApprovingPassesTheStepAndApprovesItsHunks() {
        press(KeyCode.DIGIT2).release(KeyCode.DIGIT2);
        press(KeyCode.A).release(KeyCode.A);
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(StepProgress.Decision.PASSED, progress("s1").decision());
        assertEquals(Optional.of(ReviewVerdict.Decision.APPROVED), verdictOfHunk(FILE_A, 0));
        assertEquals(Optional.of(ReviewVerdict.Decision.APPROVED), verdictOfHunk(FILE_A, 1));
        assertEquals(Optional.empty(), verdictOfHunk(FILE_B, 0));
        assertEquals("s2", ReviewDiagFxThread.call(view::diagCurrentStepId), "advanced to the next unsettled step");
    }

    @Test
    void aWrongAnswerKeepsTheStepOpenOnTheAlternate() {
        press(KeyCode.DIGIT1).release(KeyCode.DIGIT1);
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(1, progress("s1").check("c1").attempt());
    }

    @Test
    void vSwitchesToTheHunkDiffAndBack() {
        press(KeyCode.V).release(KeyCode.V);
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(SessionReviewView.ReviewMode.DIFF, ReviewDiagFxThread.call(view::diagMode));
        assertTrue(lookup(".review-intent-card").tryQuery().isPresent(), "today's intent rail is back");
        press(KeyCode.V).release(KeyCode.V);
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(SessionReviewView.ReviewMode.TOUR, ReviewDiagFxThread.call(view::diagMode));
    }

    @Test
    void anApprovalInTheHunkDiffIsRecordedAsAnOverride() throws Exception {
        press(KeyCode.V).release(KeyCode.V);
        WaitForAsyncUtils.waitForFxEvents();
        focusDiffColumn();
        press(KeyCode.SHIFT).press(KeyCode.A).release(KeyCode.A).release(KeyCode.SHIFT);
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(!host.tours.forScope(scope.id()).orElseThrow().hunkOverrides().isEmpty());
        assertEquals(StepProgress.Decision.NONE, progress("s1").decision(), "the step itself is not passed");
        // Which file the click lands in depends on layout (at 1400x900 it is
        // FILE_B), so check every hunk the approval actually overrode.
        Set<String> overridden = Set.copyOf(host.tours.forScope(scope.id()).orElseThrow().hunkOverrides().keySet());
        press(KeyCode.V).release(KeyCode.V);
        WaitForAsyncUtils.waitForFxEvents();
        for (String digest : overridden) {
            assertEquals(Optional.of(ReviewVerdict.Decision.APPROVED),
                    ReviewDiagFxThread.call(() -> host.store.verdict(scope.id(), digest).map(ReviewVerdict::decision)),
                    "re-deriving in tour mode keeps the hunk-diff approval on " + digest);
        }
    }

    @Test
    void aFailedRunFromTheHunkDiffShowsItsFailureAndKeepsIt() {
        withoutTour();
        interact(view::diagRunReview);
        WaitForAsyncUtils.waitForFxEvents();
        assertFailureShown("Could not reach this session's agent.");
        interact(view::refreshReviewState);
        WaitForAsyncUtils.waitForFxEvents();
        assertFailureShown("Could not reach this session's agent.");
    }

    @Test
    void aTourThatNeverArrivesShowsItsFailureAndKeepsIt() {
        host.reviewers.add("claude");
        try {
            withoutTour();
            interact(view::diagRunReview);
            WaitForAsyncUtils.waitForFxEvents();
            assertTrue(lookup("Building tour…").tryQuery().isPresent());
            interact(view::diagExpireTourWait);
            WaitForAsyncUtils.waitForFxEvents();
            assertFailureShown("No tour arrived.");
            interact(view::refreshReviewState);
            WaitForAsyncUtils.waitForFxEvents();
            assertFailureShown("No tour arrived.");
        } finally {
            host.reviewers.clear();
        }
    }

    @Test
    void verdictsFromBeforeTheTourSurviveItsFirstRender() {
        String digest = digestOfHunk(FILE_A, 0);
        interact(() -> {
            host.tours.remove(scope.id());
            view.refreshReviewState();
            host.store.putVerdict(new ReviewVerdict(scope.id(), digest, ReviewVerdict.Decision.APPROVED,
                    Optional.empty(), Instant.now(), host.baseCommit, host.headCommit));
            host.tours.put(TourRecord.fresh(tour(scope.id(), host.diff), host.diff));
            view.refreshReviewState();
        });
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(SessionReviewView.ReviewMode.TOUR, ReviewDiagFxThread.call(view::diagMode));
        assertEquals(Optional.of(ReviewVerdict.Decision.APPROVED), verdictOfHunk(FILE_A, 0));
        assertTrue(ReviewDiagFxThread.call(() -> host.tours.forScope(scope.id()).orElseThrow()
                .hunkOverrides().containsKey(digest)), "seeded as a hunk override");
    }

    private void assertFailureShown(String message) {
        assertEquals(SessionReviewView.ReviewMode.TOUR, ReviewDiagFxThread.call(view::diagMode));
        assertTrue(lookup(message).tryQuery().isPresent(), message);
        assertTrue(lookup("Retry").tryQuery().isPresent(), "Retry");
        assertTrue(lookup("Open diff review").tryQuery().isPresent(), "Open diff review");
    }

    /** Drops the scope's tour; with no run pending the board falls back to the hunk diff. */
    private void withoutTour() {
        interact(() -> {
            host.tours.remove(scope.id());
            view.refreshReviewState();
        });
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(SessionReviewView.ReviewMode.DIFF, ReviewDiagFxThread.call(view::diagMode));
    }

    @Test
    void aRefreshThatChangesNothingLeavesTheStepPanelAlone() {
        // Every store write (an agent's finding, say) refreshes the board; a
        // rebuilt panel would drop a half-typed answer and its focus.
        Node before = lookup(".step-choice").query();
        interact(view::refreshReviewState);
        WaitForAsyncUtils.waitForFxEvents();
        assertSame(before, lookup(".step-choice").query());
    }

    @Test
    void runReviewWithoutATourShowsBuildingTour() {
        host.reviewers.add("claude");
        try {
            withoutTourInTourMode();
            clickOn("Run review");
            WaitForAsyncUtils.waitForFxEvents();
            assertEquals(1, host.reviewRuns.size(), "the outline's Run review asked the host");
            assertTrue(lookup("Building tour…").tryQuery().isPresent(), "the outline says the tour is coming");
            assertEquals(SessionReviewView.ReviewMode.TOUR, ReviewDiagFxThread.call(view::diagMode));
        } finally {
            host.reviewers.clear();
        }
    }

    @Test
    void aRunThatCannotStartOffersRetryAndTheDiffReview() {
        withoutTourInTourMode();
        clickOn("Run review");
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(lookup("Could not reach this session's agent.").tryQuery().isPresent());
        assertTrue(lookup("Retry").tryQuery().isPresent());
        clickOn("Open diff review");
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(SessionReviewView.ReviewMode.DIFF, ReviewDiagFxThread.call(view::diagMode));
    }

    /** Drops the scope's tour and switches to tour mode, where the outline offers Run review. */
    private void withoutTourInTourMode() {
        interact(() -> {
            host.tours.remove(scope.id());
            view.refreshReviewState();
        });
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(SessionReviewView.ReviewMode.DIFF, ReviewDiagFxThread.call(view::diagMode),
                "no tour and no run pending: the hunk diff");
        press(KeyCode.V).release(KeyCode.V);
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(SessionReviewView.ReviewMode.TOUR, ReviewDiagFxThread.call(view::diagMode));
    }
}
