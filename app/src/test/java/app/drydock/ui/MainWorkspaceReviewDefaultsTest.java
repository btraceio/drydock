package app.drydock.ui;

import app.drydock.review.ReviewAnnotation;
import app.drydock.review.Severity;
import app.drydock.review.Triage;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The confirm-promotes-to-posting rule ({@code MainWorkspace.setTriage}),
 * as a pure static so the default needs no workspace to be pinned:
 * confirming a finding is the human vouching for it, so it posts to the
 * pull request by default -- the hand-toggled "Post to PR" per confirmed
 * finding was the recurring complaint.
 */
class MainWorkspaceReviewDefaultsTest {

    private static ReviewAnnotation proposedAgentFinding() {
        return ReviewAnnotation.human("rs_1", "src/A.java", "n4", "n4",
                        new ReviewAnnotation.Message("Claude", Instant.EPOCH, "the claim"))
                .withTriage(Triage.PROPOSED)
                .withPostToPr(false);
    }

    @Test
    void confirmingPromotesAnExcludedFindingOntoThePrByDefault() {
        ReviewAnnotation confirmed = MainWorkspace.confirmedForPosting(
                proposedAgentFinding(), Triage.CONFIRMED);

        assertTrue(confirmed.triage() == Triage.CONFIRMED);
        assertTrue(confirmed.postToPr(),
                "confirmed means vouched for: it posts unless the human says otherwise");
    }

    @Test
    void theExplicitOptOutSurvivesARedundantConfirm() {
        // The margin's toggle is enabled only on a confirmed finding, so the
        // one exclusion a human can actually make is postToPr=false on a
        // finding whose triage is already CONFIRMED. A redundant confirm of
        // that state must not undo it -- only the transition in promotes.
        ReviewAnnotation excludedAfterConfirm = proposedAgentFinding()
                .withTriage(Triage.CONFIRMED).withPostToPr(false);

        ReviewAnnotation reconfirmed = MainWorkspace.confirmedForPosting(
                excludedAfterConfirm, Triage.CONFIRMED);
        assertFalse(reconfirmed.postToPr(), "an exclusion the human made stays made");
    }

    @Test
    void aDismissedFindingReopenedAndConfirmedPostsByDefaultAgain() {
        // The reopen path (dismissed -> proposed -> confirm) is the one real
        // way a finding comes back to confirmed, and there the promotion is
        // wanted: the human took the dismissal back.
        ReviewAnnotation reopened = proposedAgentFinding().withTriage(Triage.DISMISSED)
                .withTriage(Triage.PROPOSED);

        ReviewAnnotation confirmed = MainWorkspace.confirmedForPosting(reopened, Triage.CONFIRMED);
        assertTrue(confirmed.postToPr());
    }

    @Test
    void anythingButConfirmLeavesPostingAlone() {
        ReviewAnnotation dismissed = MainWorkspace.confirmedForPosting(
                proposedAgentFinding(), Triage.DISMISSED);
        assertFalse(dismissed.postToPr());
        assertFalse(dismissed.triage() == Triage.CONFIRMED);
    }

    @Test
    void anAlreadyPostingFindingIsUntouched() {
        ReviewAnnotation own = ReviewAnnotation.human("rs_1", "src/A.java", "n4", "n4",
                new ReviewAnnotation.Message("You", Instant.EPOCH, "my comment"));
        assertTrue(own.postToPr());

        ReviewAnnotation confirmed = MainWorkspace.confirmedForPosting(own, Triage.CONFIRMED);
        assertTrue(confirmed.postToPr());
    }

    @Test
    void theRuleIsBoundedByCountsWithoutTouchingSeveritySemantics() {
        // The plan itself still refuses to post anything that does not
        // count (unconfirmed, resolved) -- the default only fills in the
        // silent postToPr=false; it does not bypass the counts() gate, and
        // blocksApproval stays a property of the severity.
        ReviewAnnotation confirmed = MainWorkspace.confirmedForPosting(
                proposedAgentFinding().withSeverityOverride(Severity.BLOCKING), Triage.CONFIRMED);
        assertTrue(confirmed.blocksApproval());
        assertTrue(confirmed.counts());
    }
}
