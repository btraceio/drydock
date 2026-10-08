package app.drydock.ui.review;

import app.drydock.review.AnnotationStatus;
import app.drydock.review.Confidence;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.Severity;
import app.drydock.review.Triage;
import app.drydock.testing.FxSync;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The margin's Confirm / Dismiss / Not sure on an agent's proposal. */
class ReviewTriageMarginTest extends ReviewViewFixture {

    private ReviewAnnotation proposed(String id) {
        return new ReviewAnnotation(scope.id(), id, FILE_A, "n1", "n1",
                Severity.QUESTION, Confidence.HIGH, Optional.of("Title " + id), "Claude", Instant.EPOCH,
                List.of(), Optional.empty(), Optional.empty(), List.of(),
                List.of(new ReviewAnnotation.Message("Claude", Instant.EPOCH, "body of " + id)),
                Optional.empty(), AnnotationStatus.OPEN, Optional.empty(), false,
                Triage.PROPOSED, Optional.empty());
    }

    private Triage storedTriage(String id) {
        return host.store.forScope(scope.id()).stream()
                .filter(f -> f.id().equals(id)).findFirst().orElseThrow().triage();
    }

    /** The real host refreshes the view when the store changes; the fake host leaves that to the test. */
    private void refresh() {
        interact(view::refreshReviewState);
        FxSync.waitForFxEvents();
    }

    private void seedAndRefresh(ReviewAnnotation... findings) {
        for (ReviewAnnotation finding : findings) {
            host.addFinding(scope, finding);
        }
        refresh();
    }

    private ReviewAnnotation confirmed(String id) {
        return new ReviewAnnotation(scope.id(), id, FILE_A, "n2", "n2",
                Severity.QUESTION, Confidence.HIGH, Optional.of("Title " + id), "Claude", Instant.EPOCH,
                List.of(), Optional.empty(), Optional.empty(), List.of(),
                List.of(new ReviewAnnotation.Message("Claude", Instant.EPOCH, "body of " + id)),
                Optional.empty(), AnnotationStatus.OPEN, Optional.empty(), false,
                Triage.CONFIRMED, Optional.empty());
    }

    /**
     * The walk after a deep review: its amendments arrive as proposals, and
     * the proposed filter is the delta queue -- only what this round's
     * reviewer has not triaged yet, so the second pass is walked without
     * re-reading the whole margin.
     */
    @Test
    void theProposedFilterShowsOnlyUntriagedFindings() {
        seedAndRefresh(proposed("f1"), confirmed("f2"), proposed("f3"));

        interact(() -> lookup("proposed").queryAs(Button.class).fire());
        FxSync.waitForFxEvents();

        assertEquals(2, lookup(".review-finding-card").queryAll().size(),
                "the delta: proposals only, the confirmed finding is out of the walk");
        assertTrue(lookup(".review-card-proposed").queryAll().size() >= 1,
                "what shows is untriaged");

        interact(() -> lookup("open").queryAs(Button.class).fire());
        FxSync.waitForFxEvents();
        assertEquals(3, lookup(".review-finding-card").queryAll().size(),
                "open is the round-trip home: unresolved and not dismissed, confirmed included");
    }

    @Test
    void aProposalShowsItsChipAndCannotBePostedYet() {
        seedAndRefresh(proposed("f1"));

        assertEquals(1, lookup(".review-card-proposed").queryAll().size());
        assertEquals("Proposed", ((Label) lookup(".review-card-proposed").query()).getText());
        Button post = lookup("Post to PR").queryButton();
        assertTrue(post.isDisabled(), "an unconfirmed finding must not be postable");
    }

    @Test
    void confirmingStoresTheTriageAndEnablesPosting() {
        seedAndRefresh(proposed("f1"));

        clickOn("Confirm");
        refresh();

        assertEquals(Triage.CONFIRMED, storedTriage("f1"));
        assertEquals(0, lookup(".review-card-proposed").queryAll().size());
        assertTrue(!lookup("Post to PR").queryButton().isDisabled());
    }

    @Test
    void dismissingNeedsAReasonAndRecordsItInTheThread() {
        seedAndRefresh(proposed("f2"));

        clickOn("Dismiss…");
        Button dismiss = lookup("Dismiss").queryButton();
        assertTrue(dismiss.isDisabled(), "a dismissal without a reason is refused");
        clickOn(".review-dismiss-reason");
        write("not a leak");
        assertTrue(!dismiss.isDisabled());
        clickOn("Dismiss");
        refresh();

        assertEquals(Triage.DISMISSED, storedTriage("f2"));
        List<ReviewAnnotation.Message> thread = host.store.forScope(scope.id()).stream()
                .filter(f -> f.id().equals("f2")).findFirst().orElseThrow().thread();
        assertEquals("Dismissed: not a leak", thread.get(thread.size() - 1).text());
    }

    @Test
    void aProposalsPatchCannotBeAppliedUntilItIsConfirmed() {
        ReviewAnnotation withPatch = new ReviewAnnotation(scope.id(), "f3", FILE_A,
                "n1", "n1", Severity.QUESTION, Confidence.HIGH, Optional.of("Title f3"), "Claude", Instant.EPOCH,
                List.of(), Optional.of(new ReviewAnnotation.Patch("--- a\n+++ b\n", "guard it")), Optional.empty(),
                List.of(), List.of(new ReviewAnnotation.Message("Claude", Instant.EPOCH, "body of f3")),
                Optional.empty(), AnnotationStatus.OPEN, Optional.empty(), false, Triage.PROPOSED, Optional.empty());
        seedAndRefresh(withPatch);

        assertTrue(lookup("Apply patch").queryButton().isDisabled(), "only a confirmed finding goes to the author");
        interact(() -> host.applyPatch(scope, withPatch));
        assertEquals(AnnotationStatus.OPEN, host.store.forScope(scope.id()).getFirst().status());
        assertTrue(host.handedOffPrompts.isEmpty(), "the host refuses it too");

        clickOn("Confirm");
        refresh();
        assertTrue(!lookup("Apply patch").queryButton().isDisabled());
    }

    @Test
    void aDismissedCardCanBeReopenedToProposed() {
        seedAndRefresh(proposed("f4"));
        clickOn("Dismiss…");
        clickOn(".review-dismiss-reason");
        write("not a leak");
        clickOn("Dismiss");
        refresh();
        assertEquals(Triage.DISMISSED, storedTriage("f4"));

        // Dismissed cards show once the margin lists every finding, not just open ones.
        clickOn("open");
        refresh();
        clickOn(lookup(".review-card-reopen").queryButton());
        refresh();

        assertEquals(Triage.PROPOSED, storedTriage("f4"));
        List<ReviewAnnotation.Message> thread = host.store.forScope(scope.id()).stream()
                .filter(f -> f.id().equals("f4")).findFirst().orElseThrow().thread();
        assertEquals("Reopened", thread.get(thread.size() - 1).text(), "the agent sees it was taken back");
        assertEquals(1, lookup(".review-card-proposed").queryAll().size(), "triage is offered again");
    }
}
