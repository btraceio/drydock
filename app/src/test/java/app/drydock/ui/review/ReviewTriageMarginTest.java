package app.drydock.ui.review;

import app.drydock.review.AnnotationStatus;
import app.drydock.review.Confidence;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.Severity;
import app.drydock.review.Triage;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The margin's Confirm / Dismiss / Not sure on an agent's proposal. */
class ReviewTriageMarginTest extends ReviewViewFixture {

    private ReviewAnnotation proposed(String id) {
        return new ReviewAnnotation(scope.id(), id, Optional.of("section-1"), FILE_A, "n1", "n1",
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
        WaitForAsyncUtils.waitForFxEvents();
    }

    private void seedAndRefresh(ReviewAnnotation... findings) {
        for (ReviewAnnotation finding : findings) {
            host.addFinding(scope, finding);
        }
        refresh();
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
}
