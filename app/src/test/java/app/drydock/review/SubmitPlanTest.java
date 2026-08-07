package app.drydock.review;

import app.drydock.github.GitHubReviewRequest.Event;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What Submit would post, and what it refuses to post. */
class SubmitPlanTest {

    @Test
    void oneRequestForChangesDecidesTheWholeReview() {
        assertEquals(Event.REQUEST_CHANGES, SubmitPlan.preselect(List.of(
                ReviewVerdict.Decision.APPROVED, ReviewVerdict.Decision.CHANGES)));
    }

    @Test
    void allApprovedIsAnApproval() {
        assertEquals(Event.APPROVE, SubmitPlan.preselect(List.of(
                ReviewVerdict.Decision.APPROVED, ReviewVerdict.Decision.APPROVED)));
    }

    @Test
    void anAutoApprovedIntentStillCountsAsApproval() {
        // Decision has three constants (ReviewVerdict.java:17-20). AUTO_APPROVED
        // is drydock deciding an intent needed no human verdict -- it approves.
        assertEquals(Event.APPROVE, SubmitPlan.preselect(List.of(
                ReviewVerdict.Decision.AUTO_APPROVED, ReviewVerdict.Decision.APPROVED)));
        assertEquals(Event.REQUEST_CHANGES, SubmitPlan.preselect(List.of(
                ReviewVerdict.Decision.AUTO_APPROVED, ReviewVerdict.Decision.CHANGES)));
    }

    @Test
    void nothingToDecideIsAPlainComment() {
        // Reachable only when the scope has no counted intents: submitReview()
        // refuses to submit while any counted intent is unsettled.
        assertEquals(Event.COMMENT, SubmitPlan.preselect(List.of()));
    }

    @Test
    void approvalNeedsNoSummaryButTheOtherTwoDo() {
        assertFalse(SubmitPlan.needsSummary(Event.APPROVE));
        assertTrue(SubmitPlan.needsSummary(Event.COMMENT));
        assertTrue(SubmitPlan.needsSummary(Event.REQUEST_CHANGES));
    }

    private static ReviewAnnotation finding(String id, String file, String startKey, String endKey) {
        ReviewAnnotation.Message firstMessage =
                new ReviewAnnotation.Message("Reviewer", Instant.now(), "Something looks off here.");
        return ReviewAnnotation.human("scope-1", file, startKey, endKey, firstMessage);
        // human() sets id via UUID, but we don't need control over id for these tests.
    }

    @Test
    void aFindingOutsideTheDiffIsRefused() {
        ReviewAnnotation outside = finding("f1", "src/Foo.java", "n91", "n91");
        SubmitPlan.DiffIndex index = new SubmitPlan.DiffIndex(Map.of(), Map.of());

        SubmitPlan plan = SubmitPlan.of(List.of(outside), List.of(), index);

        assertTrue(plan.comments().isEmpty());
        assertEquals(1, plan.refusals().size());
        assertEquals(outside.key(), plan.refusals().get(0).key());
    }

    @Test
    void aFindingSpanningTwoHunksIsRefused() {
        ReviewAnnotation spanning = finding("f2", "src/Foo.java", "n40", "n80");
        SubmitPlan.DiffIndex index = new SubmitPlan.DiffIndex(
                Map.of("src/Foo.java n40", 1, "src/Foo.java n80", 2),
                Map.of("src/Foo.java n40", 0, "src/Foo.java n80", 1));

        SubmitPlan plan = SubmitPlan.of(List.of(spanning), List.of(), index);

        assertTrue(plan.comments().isEmpty());
        assertEquals(1, plan.refusals().size());
        assertEquals(spanning.key(), plan.refusals().get(0).key());
    }

    @Test
    void aReversedRangeIsRefused() {
        ReviewAnnotation reversed = finding("f3", "src/Foo.java", "n80", "n40");
        SubmitPlan.DiffIndex index = new SubmitPlan.DiffIndex(
                Map.of("src/Foo.java n80", 5, "src/Foo.java n40", 1),
                Map.of("src/Foo.java n80", 0, "src/Foo.java n40", 0));

        SubmitPlan plan = SubmitPlan.of(List.of(reversed), List.of(), index);

        assertTrue(plan.comments().isEmpty());
        assertEquals(1, plan.refusals().size());
        assertEquals(reversed.key(), plan.refusals().get(0).key());
    }

    /**
     * Finding 1: git routinely interleaves hunks (-a +b -c +d), so a range
     * dragged from +b to -c has start on RIGHT (an {@code n} key) and end on
     * LEFT (an {@code o} key) with positions still in diff order -- the
     * reversed-range check above waves it through. GitHubLineAnchor.of would
     * then emit {@code start_side: RIGHT} with {@code side: LEFT}, which
     * GitHub rejects, and since the whole review posts as one atomic
     * request, that single rejection would 422 every other comment in it.
     */
    @Test
    void aRightToLeftCrossSideRangeIsRefused() {
        ReviewAnnotation rightToLeft = finding("f6", "src/Foo.java", "n1", "o2");
        SubmitPlan.DiffIndex index = new SubmitPlan.DiffIndex(
                Map.of("src/Foo.java n1", 1, "src/Foo.java o2", 2),
                Map.of("src/Foo.java n1", 0, "src/Foo.java o2", 0));

        SubmitPlan plan = SubmitPlan.of(List.of(rightToLeft), List.of(), index);

        assertTrue(plan.comments().isEmpty());
        assertEquals(1, plan.refusals().size());
        assertEquals(rightToLeft.key(), plan.refusals().get(0).key());
    }

    /**
     * The legal cross-side shape -- start on the deleted line, end on its
     * post-image replacement, GitHub's own "comment on lines -55 to +58" --
     * must still be accepted; the reversed-SIDE guard must not overreach
     * into refusing every cross-side range.
     */
    @Test
    void aLeftToRightCrossSideRangeIsAccepted() {
        ReviewAnnotation leftToRight = finding("f7", "src/Foo.java", "o1", "n2");
        SubmitPlan.DiffIndex index = new SubmitPlan.DiffIndex(
                Map.of("src/Foo.java o1", 1, "src/Foo.java n2", 2),
                Map.of("src/Foo.java o1", 0, "src/Foo.java n2", 0));

        SubmitPlan plan = SubmitPlan.of(List.of(leftToRight), List.of(), index);

        assertTrue(plan.refusals().isEmpty());
        assertEquals(1, plan.comments().size());
        assertEquals(1, plan.posting().size());
    }

    /** Same-side ranges (RIGHT->RIGHT here) are untouched by the cross-side guard. */
    @Test
    void aSameSideRangeIsUnaffectedByTheCrossSideGuard() {
        ReviewAnnotation sameSide = finding("f8", "src/Foo.java", "n1", "n2");
        SubmitPlan.DiffIndex index = new SubmitPlan.DiffIndex(
                Map.of("src/Foo.java n1", 1, "src/Foo.java n2", 2),
                Map.of("src/Foo.java n1", 0, "src/Foo.java n2", 0));

        SubmitPlan plan = SubmitPlan.of(List.of(sameSide), List.of(), index);

        assertTrue(plan.refusals().isEmpty());
        assertEquals(1, plan.comments().size());
    }

    @Test
    void commentsAndPostingStayParallel() {
        ReviewAnnotation ok = finding("f4", "src/Foo.java", "n40", "n40");
        SubmitPlan.DiffIndex index = new SubmitPlan.DiffIndex(
                Map.of("src/Foo.java n40", 1),
                Map.of("src/Foo.java n40", 0));

        SubmitPlan plan = SubmitPlan.of(List.of(ok), List.of(), index);

        assertEquals(1, plan.comments().size());
        assertEquals(plan.comments().size(), plan.posting().size());
        assertEquals(ok.key(), plan.posting().get(0));
    }

    @Test
    void postToPrFalseIsNeitherPostedNorRefused() {
        ReviewAnnotation notPosting = finding("f5", "src/Foo.java", "n91", "n91").withPostToPr(false);
        SubmitPlan.DiffIndex index = new SubmitPlan.DiffIndex(Map.of(), Map.of());

        SubmitPlan plan = SubmitPlan.of(List.of(notPosting), List.of(), index);

        assertTrue(plan.comments().isEmpty());
        assertTrue(plan.refusals().isEmpty());
    }

    @Test
    void commentBodyIsTheHumanReplyNotTheAgentFinding() {
        ReviewAnnotation.Message agentMessage =
                new ReviewAnnotation.Message("Reviewer", Instant.now(), "The agent's own finding text.");
        ReviewAnnotation.Message humanReply =
                new ReviewAnnotation.Message("You", Instant.now(), "The human's reply.");
        ReviewAnnotation withReply = ReviewAnnotation.human("scope-1", "src/Foo.java", "n40", "n40", agentMessage)
                .withReply(humanReply);
        SubmitPlan.DiffIndex index = new SubmitPlan.DiffIndex(
                Map.of("src/Foo.java n40", 1), Map.of("src/Foo.java n40", 0));

        SubmitPlan plan = SubmitPlan.of(List.of(withReply), List.of(), index);

        assertEquals(1, plan.comments().size());
        assertEquals("The human's reply.", plan.comments().get(0).body());
    }

    @Test
    void commentBodyIsTheLastHumanReplyNotTheLastMessageOverall() {
        ReviewAnnotation.Message agentMessage =
                new ReviewAnnotation.Message("Reviewer", Instant.now(), "The agent's own finding text.");
        ReviewAnnotation.Message humanReply =
                new ReviewAnnotation.Message("You", Instant.now(), "The human's reply.");
        ReviewAnnotation.Message laterAgentMessage =
                new ReviewAnnotation.Message("Reviewer", Instant.now(), "A later agent follow-up.");
        ReviewAnnotation withTrailingAgentMessage =
                ReviewAnnotation.human("scope-1", "src/Foo.java", "n40", "n40", agentMessage)
                        .withReply(humanReply)
                        .withReply(laterAgentMessage);
        SubmitPlan.DiffIndex index = new SubmitPlan.DiffIndex(
                Map.of("src/Foo.java n40", 1), Map.of("src/Foo.java n40", 0));

        SubmitPlan plan = SubmitPlan.of(List.of(withTrailingAgentMessage), List.of(), index);

        assertEquals(1, plan.comments().size());
        assertEquals("The human's reply.", plan.comments().get(0).body());
    }

    @Test
    void commentBodyFallsBackToTheFindingsOwnMessageWithNoHumanReply() {
        ReviewAnnotation.Message agentMessage =
                new ReviewAnnotation.Message("Reviewer", Instant.now(), "The agent's own finding text.");
        ReviewAnnotation agentOnly =
                ReviewAnnotation.human("scope-1", "src/Foo.java", "n40", "n40", agentMessage);
        SubmitPlan.DiffIndex index = new SubmitPlan.DiffIndex(
                Map.of("src/Foo.java n40", 1), Map.of("src/Foo.java n40", 0));

        SubmitPlan plan = SubmitPlan.of(List.of(agentOnly), List.of(), index);

        assertEquals(1, plan.comments().size());
        assertEquals("The agent's own finding text.", plan.comments().get(0).body());
    }
}
