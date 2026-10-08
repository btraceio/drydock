package app.drydock.ui;

import app.drydock.review.AnnotationStore;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.Severity;
import app.drydock.review.Triage;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    @Test
    void freshHeadClassificationSkipsOnlyWhenTheShasMatch() {
        MainWorkspace.FreshHead unchanged = MainWorkspace.classifyFreshHead("abc123", "abc123");
        assertTrue(unchanged.state() == MainWorkspace.FreshHead.State.UNCHANGED);
        assertTrue(unchanged.note().isEmpty(), "nothing to say when the PR still carries the reviewed head");

        MainWorkspace.FreshHead moved = MainWorkspace.classifyFreshHead("def4567890", "abc123");
        assertTrue(moved.state() == MainWorkspace.FreshHead.State.MOVED);
        assertTrue(moved.note().contains("def4567"), "the note names the new head: " + moved.note());
        assertTrue(moved.note().contains("re-verified"), moved.note());

        MainWorkspace.FreshHead unanswered = MainWorkspace.classifyFreshHead(null, "abc123");
        assertTrue(unanswered.state() == MainWorkspace.FreshHead.State.UNCERTAIN);
        assertTrue(unanswered.note().contains("gh did not answer"), unanswered.note());
        assertTrue(unanswered.note().contains("as reviewed"),
                "uncertain says what was checked instead of implying a check that did not happen");
    }

    /**
     * The sheet's final wording, persisted on Posted: the board's original
     * body stays (the "edited" chip's difference is never silent), and the
     * thread carries what actually reached GitHub -- a later round reads
     * what was posted, not what was drafted.
     */
    @Test
    void postedEditsLandInTheFindingThreadsBesideTheOriginals() throws Exception {
        java.nio.file.Path store = java.nio.file.Files.createTempDirectory("drydock-edits")
                .resolve("annotations.json");
        AnnotationStore annotations = new AnnotationStore(store);
        try {
            ReviewAnnotation finding = ReviewAnnotation.human("rs_scope", "src/A.java", "n3", "n3",
                    new ReviewAnnotation.Message("You", Instant.parse("2026-10-08T10:00:00Z"),
                            "needs a null check"));
            annotations.upsert(finding);
            ReviewAnnotation.Key key = finding.key();

            MainWorkspace.recordEditsInThreads(annotations,
                    java.util.Map.of(key, "needs a null check (see loadConfig)"));

            ReviewAnnotation updated = annotations.forScope("rs_scope").stream()
                    .filter(f -> f.key().equals(key)).findFirst().orElseThrow();
            assertEquals(2, updated.thread().size(), "the original message stays; the posted wording appends");
            assertEquals("needs a null check", updated.thread().get(0).text());
            assertEquals("You", updated.thread().get(1).author());
            assertTrue(updated.thread().get(1).text().startsWith("As posted to the PR: "),
                    updated.thread().get(1).text());
            assertTrue(updated.thread().get(1).text().endsWith("(see loadConfig)"),
                    updated.thread().get(1).text());
        } finally {
            annotations.close();
        }
    }
}