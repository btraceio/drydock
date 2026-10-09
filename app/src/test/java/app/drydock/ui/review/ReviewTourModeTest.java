package app.drydock.ui.review;

import app.drydock.domain.SessionActivity;
import app.drydock.git.UnifiedDiff;
import app.drydock.mcp.McpActivityLog;
import app.drydock.review.ReviewVerdict;
import app.drydock.review.tour.StepProgress;
import app.drydock.review.tour.TourFingerprint;
import app.drydock.review.tour.TourRecord;
import app.drydock.testing.FxSync;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        FxSync.waitForFxEvents();
        assertEquals(StepProgress.Decision.NONE, progress("s1").decision());
    }

    @Test
    void answeringRightThenApprovingPassesTheStepAndApprovesItsHunks() {
        press(KeyCode.DIGIT2).release(KeyCode.DIGIT2);
        press(KeyCode.A).release(KeyCode.A);
        FxSync.waitForFxEvents();
        assertEquals(StepProgress.Decision.PASSED, progress("s1").decision());
        assertEquals(Optional.of(ReviewVerdict.Decision.APPROVED), verdictOfHunk(FILE_A, 0));
        assertEquals(Optional.of(ReviewVerdict.Decision.APPROVED), verdictOfHunk(FILE_A, 1));
        assertEquals(Optional.empty(), verdictOfHunk(FILE_B, 0));
        assertEquals("s2", ReviewDiagFxThread.call(view::diagCurrentStepId), "advanced to the next unsettled step");
    }

    @Test
    void aWrongAnswerKeepsTheStepOpenOnTheAlternate() {
        press(KeyCode.DIGIT1).release(KeyCode.DIGIT1);
        FxSync.waitForFxEvents();
        assertEquals(1, progress("s1").check("c1").attempt());
    }

    @Test
    void vSwitchesToTheHunkDiffAndBack() {
        press(KeyCode.V).release(KeyCode.V);
        FxSync.waitForFxEvents();
        assertEquals(SessionReviewView.ReviewMode.DIFF, ReviewDiagFxThread.call(view::diagMode));
        assertTrue(lookup(".review-findings-margin").tryQuery().isPresent(), "the findings margin is back");
        assertTrue(ReviewDiagFxThread.call(view::getLeft) == null, "and the hunk diff has no left rail");
        press(KeyCode.V).release(KeyCode.V);
        FxSync.waitForFxEvents();
        assertEquals(SessionReviewView.ReviewMode.TOUR, ReviewDiagFxThread.call(view::diagMode));
    }

    @Test
    void anApprovalInTheHunkDiffIsRecordedAsAnOverride() throws Exception {
        press(KeyCode.V).release(KeyCode.V);
        FxSync.waitForFxEvents();
        focusDiffColumn();
        press(KeyCode.SHIFT).press(KeyCode.A).release(KeyCode.A).release(KeyCode.SHIFT);
        FxSync.waitForFxEvents();
        assertTrue(!host.tours.forScope(scope.id()).orElseThrow().hunkOverrides().isEmpty());
        assertEquals(StepProgress.Decision.NONE, progress("s1").decision(), "the step itself is not passed");
        // Which file the click lands in depends on layout (at 1400x900 it is
        // FILE_B), so check every hunk the approval actually overrode.
        Set<String> overridden = Set.copyOf(host.tours.forScope(scope.id()).orElseThrow().hunkOverrides().keySet());
        press(KeyCode.V).release(KeyCode.V);
        FxSync.waitForFxEvents();
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
        FxSync.waitForFxEvents();
        assertFailureShown("Could not reach this session's agent.");
        interact(view::refreshReviewState);
        FxSync.waitForFxEvents();
        assertFailureShown("Could not reach this session's agent.");
    }

    @Test
    void aTourThatNeverArrivesShowsItsFailureAndKeepsIt() {
        host.reviewers.add("claude");
        try {
            withoutTour();
            interact(view::diagRunReview);
            FxSync.waitForFxEvents();
            assertTrue(lookup("Building tour…").tryQuery().isPresent());
            interact(view::diagExpireTourWait);
            FxSync.waitForFxEvents();
            assertFailureShown("No tour arrived.");
            interact(view::refreshReviewState);
            FxSync.waitForFxEvents();
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
        FxSync.waitForFxEvents();
        assertEquals(SessionReviewView.ReviewMode.TOUR, ReviewDiagFxThread.call(view::diagMode));
        assertEquals(Optional.of(ReviewVerdict.Decision.APPROVED), verdictOfHunk(FILE_A, 0));
        assertTrue(ReviewDiagFxThread.call(() -> host.tours.forScope(scope.id()).orElseThrow()
                .hunkOverrides().containsKey(digest)), "seeded as a hunk override");
        assertTrue(ReviewDiagFxThread.call(() -> host.tours.forScope(scope.id()).orElseThrow().seeded()),
                "seeding is recorded on the tour, so it happens once");
    }

    /**
     * A record that was already seeded -- as one loaded from disk after a
     * restart is: a new tour instance, no decisions, no overrides -- keeps
     * the stored verdicts as derived ones rather than turning them into
     * permanent overrides. Nothing in a fresh view distinguishes this from
     * a fresh record instance, which is what this test hands in.
     */
    @Test
    void aSeededRecordIsNotSeededAgain() {
        String digest = digestOfHunk(FILE_A, 0);
        interact(() -> {
            host.tours.remove(scope.id());
            view.refreshReviewState();
            host.store.putVerdict(new ReviewVerdict(scope.id(), digest, ReviewVerdict.Decision.APPROVED,
                    Optional.empty(), Instant.now(), host.baseCommit, host.headCommit));
            host.tours.put(TourRecord.fresh(tour(scope.id(), host.diff), host.diff).withSeeded(true));
            view.refreshReviewState();
        });
        FxSync.waitForFxEvents();
        assertEquals(SessionReviewView.ReviewMode.TOUR, ReviewDiagFxThread.call(view::diagMode));
        assertTrue(ReviewDiagFxThread.call(() -> host.tours.forScope(scope.id()).orElseThrow()
                .hunkOverrides().isEmpty()), "no override was made from a derived verdict");
    }

    /** FILE_B's one hunk now adds a different line; FILE_A is as the tour was written against. */
    private static UnifiedDiff movedDiff() {
        return new UnifiedDiff(List.of(
                file(FILE_A, "void foo();", "void bar();"),
                file(FILE_B, "void qux();")));
    }

    private void showDiff(UnifiedDiff diff) {
        interact(() -> view.diagShowDiff(scope, diff));
        FxSync.waitForFxEvents();
    }

    @Test
    void aMovedDiffKeepsTheUntouchedStepAndAsksTheAgentOnceForTheChangedOne() {
        host.tourRefreshDispatches.clear();
        press(KeyCode.DIGIT2).release(KeyCode.DIGIT2);
        press(KeyCode.A).release(KeyCode.A);
        FxSync.waitForFxEvents();
        assertEquals(StepProgress.Decision.PASSED, progress("s1").decision());

        showDiff(movedDiff());

        assertEquals(StepProgress.Decision.PASSED, progress("s1").decision());
        assertFalse(progress("s1").stale());
        assertTrue(progress("s2").stale());
        assertEquals(TourFingerprint.of(movedDiff()), ReviewDiagFxThread.call(() ->
                host.tours.forScope(scope.id()).orElseThrow().tour().diffFingerprint()));
        assertEquals(List.of("s2/1"), host.tourRefreshDispatches);
        assertEquals("s2", ReviewDiagFxThread.call(view::diagCurrentStepId));
        assertTrue(lookup("This step's code changed; the agent is re-writing it.").tryQuery().isPresent());

        showDiff(movedDiff());
        assertEquals(List.of("s2/1"), host.tourRefreshDispatches, "the same diff again asks nothing");

        showDiff(host.diff);
        showDiff(movedDiff());
        assertEquals(List.of("s2/1", "s2/1"), host.tourRefreshDispatches,
                "back to the original diff asks once for it; the moved diff was already asked about");
    }

    @Test
    void anInlineHarnessIsNotAskedAutomaticallyUntilItCanBe() {
        host.tourRefreshDispatches.clear();
        host.supportsAutomaticRecheck = false;
        try {
            showDiff(movedDiff());
            assertTrue(progress("s2").stale(), "the tour is migrated regardless");
            assertEquals(List.of(), host.tourRefreshDispatches);
        } finally {
            host.supportsAutomaticRecheck = true;
        }
        showDiff(movedDiff());
        assertEquals(List.of("s2/1"), host.tourRefreshDispatches, "no claim was taken, so a later publish asks");
    }

    @Test
    void aBusyAgentIsNotInterruptedAndIsAskedOnceIdle() {
        host.tourRefreshDispatches.clear();
        host.agentActivity = SessionActivity.BUSY;
        try {
            showDiff(movedDiff());
            assertTrue(progress("s2").stale());
            assertEquals(List.of(), host.tourRefreshDispatches);
        } finally {
            host.agentActivity = SessionActivity.IDLE;
        }
        showDiff(movedDiff());
        assertEquals(List.of("s2/1"), host.tourRefreshDispatches);
    }

    @Test
    void aRefreshThatCouldNotBeHandedOverIsAskedAgainNextTime() {
        host.tourRefreshDispatches.clear();
        host.tourRefreshHandOffSucceeds = false;
        try {
            showDiff(movedDiff());
            assertEquals(List.of("s2/1"), host.tourRefreshDispatches);
        } finally {
            host.tourRefreshHandOffSucceeds = true;
        }
        showDiff(host.diff);
        showDiff(movedDiff());
        assertEquals(List.of("s2/1", "s2/1", "s2/1"), host.tourRefreshDispatches,
                "the failed hand-off released its claim");
    }

    private void assertFailureShown(String message) {
        assertEquals(SessionReviewView.ReviewMode.TOUR, ReviewDiagFxThread.call(view::diagMode));
        assertTrue(lookup(message).tryQuery().isPresent(), message);
        assertTrue(lookup("Retry").tryQuery().isPresent(), "Retry");
        assertTrue(lookup("Open diff review").tryQuery().isPresent(), "Open diff review");
    }

    /** Whether some label in the board currently shows a line containing {@code fragment}. */
    private boolean showsOnStepPanel(String fragment) {
        return ReviewDiagFxThread.call(() -> lookup(node -> node instanceof Label label
                        && label.getText().contains(fragment)).tryQuery().isPresent());
    }

    /** Drops the scope's tour; with no run pending the board falls back to the hunk diff. */
    private void withoutTour() {
        interact(() -> {
            host.tours.remove(scope.id());
            view.refreshReviewState();
        });
        FxSync.waitForFxEvents();
        assertEquals(SessionReviewView.ReviewMode.DIFF, ReviewDiagFxThread.call(view::diagMode));
    }

    @Test
    void aRefreshThatChangesNothingLeavesTheStepPanelAlone() {
        // Every store write (an agent's finding, say) refreshes the board; a
        // rebuilt panel would drop a half-typed answer and its focus.
        Node before = lookup(".step-choice").query();
        interact(view::refreshReviewState);
        FxSync.waitForFxEvents();
        assertSame(before, lookup(".step-choice").query());
    }

    @Test
    void runReviewWithoutATourShowsBuildingTour() {
        host.reviewers.add("claude");
        try {
            withoutTourInTourMode();
            clickOn("Run review");
            FxSync.waitForFxEvents();
            assertEquals(1, host.reviewRuns.size(), "the outline's Run review asked the host");
            assertTrue(lookup("Building tour…").tryQuery().isPresent(), "the outline says the tour is coming");
            assertEquals(SessionReviewView.ReviewMode.TOUR, ReviewDiagFxThread.call(view::diagMode));
        } finally {
            host.reviewers.clear();
        }
    }

    /**
     * The wait is never silent: under "Building tour…" a live line says how
     * long the ask has been out, what the agent is doing, and the drydock
     * calls it has made for this scope -- whether or not the MCP panel is
     * open. A reviewer staring at an unchanging "Building tour…" and a 0-call
     * panel is exactly how a review whose ask never landed burns 15 minutes.
     */
    @Test
    void theBuildingTourWaitShowsItsProgress() {
        host.reviewers.add("claude");
        try {
            withoutTourInTourMode();
            clickOn("Run review");
            FxSync.waitForFxEvents();
            String progress = ReviewDiagFxThread.call(view::diagTourPendingProgress);
            // Anchored on the state and call-count, not the clock: a one-second
            // tick between the click and this read must not fail the assertion.
            assertTrue(progress.contains("agent idle · no drydock calls"), progress);
            assertTrue(showsOnStepPanel("No drydock calls yet"),
                    "the step panel says the same thing the line does");

            // The agent's first call moves the line; one made for another
            // scope does not.
            interact(() -> {
                activityLog.record(new McpActivityLog.Entry(Instant.now(), McpActivityLog.Direction.INBOUND,
                        "review_scope", "{\"scopeId\":\"x\"}", Optional.of(scope.id()), 12, false));
                view.diagRefreshTourPendingProgress();
            });
            FxSync.waitForFxEvents();
            progress = ReviewDiagFxThread.call(view::diagTourPendingProgress);
            assertTrue(progress.contains("1 drydock call"), progress);
            assertTrue(progress.contains("last review_scope"), progress);
            assertFalse(progress.contains("review_comments"), progress);
            interact(() -> activityLog.record(new McpActivityLog.Entry(Instant.now(),
                    McpActivityLog.Direction.OUTBOUND, "review_comments", "[]",
                    Optional.of("other-scope"), 4, false)));
            interact(view::diagRefreshTourPendingProgress);
            FxSync.waitForFxEvents();
            progress = ReviewDiagFxThread.call(view::diagTourPendingProgress);
            assertTrue(progress.contains("1 drydock call"), progress);

            // And a failed call -- the tour's own validation list, say -- is
            // progress too, the same row the panel shows as failed.
            interact(() -> {
                activityLog.record(new McpActivityLog.Entry(Instant.now(), McpActivityLog.Direction.INBOUND,
                        "review_tour", "{\"scopeId\":\"x\"}", Optional.of(scope.id()), 40, true));
                view.diagRefreshTourPendingProgress();
            });
            FxSync.waitForFxEvents();
            progress = ReviewDiagFxThread.call(view::diagTourPendingProgress);
            assertTrue(progress.contains("2 drydock calls"), progress);
        } finally {
            host.reviewers.clear();
        }
    }

    /**
     * The wait auto-opens the app-wide MCP console (the wiring made visible
     * while the agent builds) and closes it again when the tour arrives --
     * through the same host seam; the console itself lives at the workspace
     * now (see MainWorkspace#toggleMcpConsole).
     */
    @Test
    void theWaitAutoOpensTheConsoleAndItsArrivalClosesIt() {
        host.reviewers.add("claude");
        try {
            withoutTourInTourMode();
            host.consoleAutoOpens = true;
            clickOn("Run review");
            FxSync.waitForFxEvents();
            assertEquals(1, host.consoleOpenedCount, "the run's wait opened the console");

            interact(() -> {
                host.tours.put(TourRecord.fresh(tour(scope.id(), host.diff), host.diff));
                view.refreshReviewState();
            });
            FxSync.waitForFxEvents();
            assertEquals(1, host.consoleClosedCount, "the tour arriving closed what the wait opened");
        } finally {
            host.reviewers.clear();
        }
    }

    /** A console the READER opened is not the wait's to close. */
    @Test
    void aWaitThatOpenedNoConsoleClosesNothing() {
        host.reviewers.add("claude");
        try {
            withoutTourInTourMode();
            clickOn("Run review");
            FxSync.waitForFxEvents();
            assertEquals(0, host.consoleOpenedCount, "the fake console was off; nothing was asked to open");

            interact(() -> {
                host.tours.put(TourRecord.fresh(tour(scope.id(), host.diff), host.diff));
                view.refreshReviewState();
            });
            FxSync.waitForFxEvents();
            assertEquals(0, host.consoleClosedCount, "no close of what was never opened");
        } finally {
            host.reviewers.clear();
        }
    }

    @Test
    void theWaitsProgressLineRetiresWhenTheTourArrives() {
        host.reviewers.add("claude");
        try {
            withoutTourInTourMode();
            clickOn("Run review");
            FxSync.waitForFxEvents();
            assertTrue(lookup(".tour-pending-progress").tryQuery().isPresent());
            interact(() -> {
                host.tours.put(TourRecord.fresh(tour(scope.id(), host.diff), host.diff));
                view.refreshReviewState();
            });
            FxSync.waitForFxEvents();
            assertFalse(lookup(".tour-pending-progress").tryQuery().isPresent(),
                    "no elapsed count can outlive the wait it counted");
            interact(view::diagRefreshTourPendingProgress);
            FxSync.waitForFxEvents();
            assertFalse(lookup(".tour-pending-progress").tryQuery().isPresent(),
                    "the stopped ticker's next tick would write nothing");
        } finally {
            host.reviewers.clear();
        }
    }

    @Test
    void theStepPanelsBuildingMessageCountsTheCallsWhenThereAreAny() {
        host.reviewers.add("claude");
        try {
            withoutTourInTourMode();
            clickOn("Run review");
            FxSync.waitForFxEvents();
            interact(() -> activityLog.record(new McpActivityLog.Entry(Instant.now(),
                    McpActivityLog.Direction.INBOUND, "review_scope", "{}",
                    Optional.of(scope.id()), 12, false)));
            interact(view::refreshReviewState);
            FxSync.waitForFxEvents();
            assertFalse(showsOnStepPanel("No drydock calls yet"),
                    "with calls on record the message points at the panel below");
        } finally {
            host.reviewers.clear();
        }
    }

    @Test
    void buildingTourOffersTheHunkDiffWhileTheWaitKeepsRunning() {
        host.reviewers.add("claude");
        try {
            withoutTourInTourMode();
            clickOn("Run review");
            FxSync.waitForFxEvents();
            assertTrue(lookup("Open diff review").tryQuery().isPresent(), "Open diff review while building");
            assertTrue(lookup("Cancel").tryQuery().isPresent(), "Cancel while building");
            clickOn("Open diff review");
            FxSync.waitForFxEvents();
            assertEquals(SessionReviewView.ReviewMode.DIFF, ReviewDiagFxThread.call(view::diagMode));
            assertTrue(ReviewDiagFxThread.call(view::diagTourPending), "the wait is still pending");
            assertTrue(ReviewDiagFxThread.call(view::diagTourWaitRunning), "and still running");
            interact(() -> {
                host.tours.put(TourRecord.fresh(tour(scope.id(), host.diff), host.diff));
                view.refreshReviewState();
            });
            FxSync.waitForFxEvents();
            assertEquals(SessionReviewView.ReviewMode.DIFF, ReviewDiagFxThread.call(view::diagMode),
                    "an arriving tour does not yank the reader back");
            assertEquals(Optional.of("The tour is ready \u2014 press v"), ReviewDiagFxThread.call(view::diagNotice));
            assertFalse(ReviewDiagFxThread.call(view::diagTourPending));
        } finally {
            host.reviewers.clear();
        }
    }

    @Test
    void cancellingBuildingTourReturnsToTheNoTourState() {
        host.reviewers.add("claude");
        try {
            withoutTourInTourMode();
            clickOn("Run review");
            FxSync.waitForFxEvents();
            clickOn("Cancel");
            FxSync.waitForFxEvents();
            assertFalse(ReviewDiagFxThread.call(view::diagTourPending));
            assertFalse(ReviewDiagFxThread.call(view::diagTourWaitRunning));
            assertEquals(SessionReviewView.ReviewMode.TOUR, ReviewDiagFxThread.call(view::diagMode));
            assertTrue(lookup("No tour yet.").tryQuery().isPresent());
            assertTrue(lookup("Run review").tryQuery().isPresent());
        } finally {
            host.reviewers.clear();
        }
    }

    @Test
    void theNoTourStateAfterCancelSurvivesARefreshAndARerunWaitsAfresh() {
        host.reviewers.add("claude");
        try {
            // From the hunk diff, without v: the top bar's Run review (its
            // button needs a session, so the diag hook stands in) puts the
            // board into tour mode only because a wait is pending.
            interact(() -> {
                host.tours.remove(scope.id());
                view.refreshReviewState();
            });
            FxSync.waitForFxEvents();
            assertEquals(SessionReviewView.ReviewMode.DIFF, ReviewDiagFxThread.call(view::diagMode));
            interact(view::diagRunReview);
            FxSync.waitForFxEvents();
            assertEquals(SessionReviewView.ReviewMode.TOUR, ReviewDiagFxThread.call(view::diagMode));
            clickOn("Cancel");
            FxSync.waitForFxEvents();
            // Any store write refreshes the board; Cancel chose the tour's
            // no-tour screen, and a refresh must not drop it to the hunk diff.
            interact(view::refreshReviewState);
            FxSync.waitForFxEvents();
            assertEquals(SessionReviewView.ReviewMode.TOUR, ReviewDiagFxThread.call(view::diagMode));
            assertTrue(lookup("No tour yet.").tryQuery().isPresent(), "the no-tour screen is still shown");

            clickOn("Run review");
            FxSync.waitForFxEvents();
            assertEquals(2, host.reviewRuns.size(), "the second Run review asked the host again");
            assertTrue(ReviewDiagFxThread.call(view::diagTourPending), "a fresh wait is pending");
            assertTrue(ReviewDiagFxThread.call(view::diagTourWaitRunning), "and running");
            assertTrue(lookup("Building tour…").tryQuery().isPresent());
        } finally {
            host.reviewers.clear();
        }
    }

    @Test
    void aRunThatCannotStartOffersRetryAndTheDiffReview() {
        withoutTourInTourMode();
        clickOn("Run review");
        FxSync.waitForFxEvents();
        assertTrue(lookup("Could not reach this session's agent.").tryQuery().isPresent());
        assertTrue(lookup("Retry").tryQuery().isPresent());
        clickOn("Open diff review");
        FxSync.waitForFxEvents();
        assertEquals(SessionReviewView.ReviewMode.DIFF, ReviewDiagFxThread.call(view::diagMode));
    }

    /** Drops the scope's tour and switches to tour mode, where the outline offers Run review. */
    private void withoutTourInTourMode() {
        interact(() -> {
            host.tours.remove(scope.id());
            view.refreshReviewState();
        });
        FxSync.waitForFxEvents();
        assertEquals(SessionReviewView.ReviewMode.DIFF, ReviewDiagFxThread.call(view::diagMode),
                "no tour and no run pending: the hunk diff");
        press(KeyCode.V).release(KeyCode.V);
        FxSync.waitForFxEvents();
        assertEquals(SessionReviewView.ReviewMode.TOUR, ReviewDiagFxThread.call(view::diagMode));
    }
}
