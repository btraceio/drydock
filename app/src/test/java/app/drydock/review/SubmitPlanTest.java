package app.drydock.review;

import app.drydock.github.GitHubLineAnchor;
import app.drydock.github.GitHubReviewRequest;
import app.drydock.github.GitHubReviewRequest.Event;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void anAutoApprovedHunkStillCountsAsApproval() {
        // Decision has three constants (ReviewVerdict.java:17-20). AUTO_APPROVED
        // is the agent's own assertion that a hunk needed no human verdict -- it approves.
        assertEquals(Event.APPROVE, SubmitPlan.preselect(List.of(
                ReviewVerdict.Decision.AUTO_APPROVED, ReviewVerdict.Decision.APPROVED)));
        assertEquals(Event.REQUEST_CHANGES, SubmitPlan.preselect(List.of(
                ReviewVerdict.Decision.AUTO_APPROVED, ReviewVerdict.Decision.CHANGES)));
    }

    @Test
    void nothingToDecideIsAPlainComment() {
        // Reachable only when the scope has no hunks to decide: submitReview()
        // refuses to submit while any hunk is unsettled.
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
        assertTrue(plan.posting().isEmpty(), "an excluded finding must not be in posting either -- "
                + "that list is what the host clears postToPr over after a successful post");
        assertTrue(plan.refusals().isEmpty());
    }

    /**
     * A finding resolved (or fixed) after {@code postToPr} was left {@code
     * true} must not post: the margin's default filter hides resolved
     * cards, so its toggle is not even reachable without switching to "all"
     * -- posting it anyway would publish something the human never had a
     * real chance to opt back out of.
     */
    @Test
    void aResolvedFindingIsNeitherPostedNorRefusedEvenWithPostToPrTrue() {
        ReviewAnnotation resolved = finding("f6", "src/Foo.java", "n40", "n40")
                .withStatus(AnnotationStatus.RESOLVED);
        assertTrue(resolved.postToPr(), "the fixture must actually exercise the resolved check, "
                + "not postToPr already being false");
        SubmitPlan.DiffIndex index = new SubmitPlan.DiffIndex(
                Map.of("src/Foo.java n40", 1), Map.of("src/Foo.java n40", 0));

        SubmitPlan plan = SubmitPlan.of(List.of(resolved), List.of(), index);

        assertTrue(plan.comments().isEmpty());
        assertTrue(plan.posting().isEmpty());
        assertTrue(plan.refusals().isEmpty());
    }

    /** {@code FIXED} is the other resolved status ({@link ReviewAnnotation#resolved()}); both must be excluded. */
    @Test
    void aFixedFindingIsAlsoExcluded() {
        ReviewAnnotation fixed = finding("f7", "src/Foo.java", "n40", "n40")
                .withStatus(AnnotationStatus.FIXED);
        SubmitPlan.DiffIndex index = new SubmitPlan.DiffIndex(
                Map.of("src/Foo.java n40", 1), Map.of("src/Foo.java n40", 0));

        SubmitPlan plan = SubmitPlan.of(List.of(fixed), List.of(), index);

        assertTrue(plan.comments().isEmpty());
        assertTrue(plan.posting().isEmpty());
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

    private static final SubmitPlan.DiffIndex EMPTY_INDEX = new SubmitPlan.DiffIndex(Map.of(), Map.of());

    private static java.util.function.BiFunction<String, String, Optional<String>> lookup() {
        return (file, key) -> file.equals("src/Foo.java") && key.equals("n500")
                ? Optional.of("callers.forEach(Caller::run);") : Optional.empty();
    }

    @Test
    void aFindingOutsideTheDiffBecomesABodyNoteWithTheLineExcerpt() {
        ReviewAnnotation outside = finding("f9", "src/Foo.java", "n500", "n500");

        SubmitPlan plan = SubmitPlan.of(List.of(outside), List.of(), EMPTY_INDEX, lookup());

        assertTrue(plan.comments().isEmpty());
        assertTrue(plan.refusals().isEmpty());
        assertEquals(1, plan.bodyNotes().size());
        SubmitPlan.BodyNote note = plan.bodyNotes().get(0);
        assertEquals(outside.key(), note.key());
        assertEquals("src/Foo.java", note.file());
        assertEquals("500", note.lineLabel());
        assertEquals("callers.forEach(Caller::run);", note.excerpt());
        assertEquals("Something looks off here.", note.body());
        assertEquals(List.of(outside.key()), plan.posting(),
                "a note is posted, so postToPr must be cleared for it after a successful post");
    }

    @Test
    void composeBodyAppendsTheNotesWithTheirExcerptsToTheSummary() {
        ReviewAnnotation outside = finding("f10", "src/Foo.java", "n500", "n500");
        SubmitPlan plan = SubmitPlan.of(List.of(outside), List.of(), EMPTY_INDEX, lookup());

        String body = plan.composeBody("LGTM");

        assertTrue(body.startsWith("LGTM\n\nComments on lines outside this diff:\n"), body);
        assertTrue(body.contains("- `src/Foo.java:500` — Something looks off here."), body);
        assertTrue(body.contains("callers.forEach(Caller::run);"), body);
    }

    @Test
    void composeBodyIsTheSummaryUnchangedWithoutNotes() {
        assertEquals("LGTM", SubmitPlan.of(List.of(), List.of(), EMPTY_INDEX, lookup()).composeBody("LGTM"));
    }

    @Test
    void aNoteWithoutAnExcerptStillComposes() {
        ReviewAnnotation outside = finding("f11", "src/Other.java", "n7", "n7");
        SubmitPlan plan = SubmitPlan.of(List.of(outside), List.of(), EMPTY_INDEX, lookup());

        assertEquals("", plan.bodyNotes().get(0).excerpt());
        assertTrue(plan.composeBody("").contains("- `src/Other.java:7` — Something looks off here."));
    }

    @Test
    void aSpanAcrossTwoHunksStaysARefusalEvenWithALookup() {
        ReviewAnnotation spanning = finding("f12", "src/Foo.java", "n40", "n80");
        SubmitPlan.DiffIndex index = new SubmitPlan.DiffIndex(
                Map.of("src/Foo.java n40", 1, "src/Foo.java n80", 2),
                Map.of("src/Foo.java n40", 0, "src/Foo.java n80", 1));

        SubmitPlan plan = SubmitPlan.of(List.of(spanning), List.of(), index, lookup());

        assertEquals(1, plan.refusals().size());
        assertTrue(plan.bodyNotes().isEmpty());
    }

    @Test
    void theThreeArgumentOfStillRefusesALineOutsideTheDiff() {
        ReviewAnnotation outside = finding("f13", "src/Foo.java", "n500", "n500");

        SubmitPlan plan = SubmitPlan.of(List.of(outside), List.of(), EMPTY_INDEX);

        assertEquals(1, plan.refusals().size());
        assertTrue(plan.bodyNotes().isEmpty());
    }

    @Test
    void composeBodyFencesTheExcerptAndKeepsAMultiLineBodyInsideItsBullet() {
        ReviewAnnotation.Message message = new ReviewAnnotation.Message("Reviewer", Instant.now(),
                "This caller still passes null.\nIt will throw now.");
        ReviewAnnotation outside = ReviewAnnotation.human("scope-1", "src/Foo.java", "n500", "n500", message);
        String excerpt = "List<T> *p = `a` + ```b```;";
        SubmitPlan plan = SubmitPlan.of(List.of(outside), List.of(), EMPTY_INDEX,
                (file, key) -> Optional.of(excerpt));

        String body = plan.composeBody("LGTM");

        assertEquals("""
                LGTM

                Comments on lines outside this diff:
                - `src/Foo.java:500` — This caller still passes null.
                  It will throw now.

                  ````
                  List<T> *p = `a` + ```b```;
                  ````""", body);
    }

    /**
     * The submit sheet's per-finding editing rewords through withBodies: a
     * comment's and a body note's text replaced by the finding's key, the
     * anchors, routes, refusals and preselected event carried over
     * unchanged, and unknown keys ignored.
     */
    @Test
    void withBodiesRewordsBothRoutesByKeyAndCarriesEverythingElseOver() {
        ReviewAnnotation.Key inlineKey = new ReviewAnnotation.Key("rs", "f-inline");
        ReviewAnnotation.Key noteKey = new ReviewAnnotation.Key("rs", "f-note");
        GitHubReviewRequest.Comment comment = new GitHubReviewRequest.Comment("src/A.java", "original inline",
                new GitHubLineAnchor.Anchor(12, GitHubLineAnchor.Side.RIGHT, OptionalInt.empty(), Optional.empty()));
        SubmitPlan.BodyNote note = new SubmitPlan.BodyNote(noteKey, "src/B.java", "500", "excerpt", "original note");
        SubmitPlan plan = new SubmitPlan(GitHubReviewRequest.Event.APPROVE, List.of(comment),
                List.of(inlineKey, noteKey), List.of(), List.of(note));

        SubmitPlan reworded = plan.withBodies(Map.of(inlineKey, "reworded inline", noteKey, "reworded note"));

        assertEquals("reworded inline", reworded.comments().getFirst().body());
        assertEquals(12, reworded.comments().getFirst().anchor().line());
        assertEquals("reworded note", reworded.bodyNotes().getFirst().body());
        assertEquals("excerpt", reworded.bodyNotes().getFirst().excerpt());
        assertEquals(List.of(inlineKey, noteKey), reworded.posting());
        assertEquals(GitHubReviewRequest.Event.APPROVE, reworded.preselected());

        // An unknown key is ignored, and the empty map is the same plan.
        assertEquals(plan, plan.withBodies(Map.of(new ReviewAnnotation.Key("rs", "nowhere"), "x")));
    }

    @Test
    void aMisalignedPlanIsRejectedAtConstruction() {
        GitHubReviewRequest.Comment comment = new GitHubReviewRequest.Comment("src/A.java", "body",
                new GitHubLineAnchor.Anchor(12, GitHubLineAnchor.Side.RIGHT, OptionalInt.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new SubmitPlan(GitHubReviewRequest.Event.APPROVE, List.of(comment), List.of(), List.of(), List.of()),
                "posting must align with comments plus body notes -- the editing keys on it");
    }
}
