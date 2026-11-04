package app.drydock.intellij.review;

import app.drydock.intellij.review.workbench.model.OutboxModel;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.Triage;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** The outbox's session lane over a fake send: the disabled reasons, the busy state and the outcome. */
public class ReviewWorkbenchOutboxPlatformTest extends BasePlatformTestCase {

    private final List<String> sent = new ArrayList<>();
    private CompletableFuture<Optional<String>> next = new CompletableFuture<>();

    private ReviewWorkbenchOutbox outbox() {
        return new ReviewWorkbenchOutbox(() -> {
            sent.add("send");
            return next;
        });
    }

    private static ReviewAnnotation comment(String key, String text) {
        return ReviewAnnotation.human("rs_1", "src/A.java", key, key, new ReviewAnnotation.Message("You", Instant.EPOCH, text));
    }

    public void testWithoutASessionTheButtonIsOffAndSaysWhy() {
        ReviewWorkbenchOutbox outbox = outbox();
        outbox.render(OutboxModel.snapshot(List.of(comment("n5", "Rename it.")), false));
        assertFalse(outbox.send.isEnabled());
        assertEquals(ReviewWorkbenchOutbox.NO_SESSION, outbox.reason.getText());

        outbox.render(OutboxModel.snapshot(List.of(), true));
        assertFalse("nothing to send", outbox.send.isEnabled());
        assertEquals("", outbox.reason.getText());
    }

    public void testASendIsBusyUntilItCompletesAndShowsItsOutcome() {
        ReviewWorkbenchOutbox outbox = outbox();
        outbox.render(OutboxModel.snapshot(List.of(comment("n5", "Rename it.")), true));
        outbox.send.doClick();
        assertEquals(List.of("send"), sent);
        assertEquals(ReviewWorkbenchOutbox.SENDING, outbox.send.getText());
        assertFalse(outbox.send.isEnabled());

        outbox.send.doClick();
        assertEquals("a running send is not started twice", List.of("send"), sent);

        next.complete(Optional.of("Sent to the agent."));
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        assertEquals(ReviewWorkbenchOutbox.SEND, outbox.send.getText());
        assertEquals("Sent to the agent.", outbox.notice.getText());
    }

    public void testFixesLandOnShowsInAPullRequestReviewAndPassesTheChoiceOn() {
        List<Boolean> chosen = new ArrayList<>();
        ReviewWorkbenchOutbox outbox = new ReviewWorkbenchOutbox(new ReviewWorkbenchOutbox.Actions() {
            @Override public CompletableFuture<Optional<String>> send() { return next; }
            @Override public void fixesLandOn(boolean stackedSubPr) { chosen.add(stackedSubPr); }
        });
        outbox.render(OutboxModel.snapshot(List.of(), true, false));
        assertFalse("a local review has no choice", outbox.stackedSubPr.getParent().isVisible());

        outbox.render(OutboxModel.snapshot(List.of(), true, true).withStackedSubPr(true));
        assertTrue(outbox.stackedSubPr.getParent().isVisible());
        assertTrue(outbox.stackedSubPr.isSelected());
        outbox.sameBranch.doClick();
        assertEquals(List.of(false), chosen);
    }

    public void testARowNamesTheFindingItsLineAndTheReviewersComment() {
        OutboxModel.Item commented = new OutboxModel.Item(comment("n5", "Rename it."), true);
        assertEquals("Rename it. — src/A.java:5 · your comment", ReviewWorkbenchOutbox.rowText(commented));
        OutboxModel.Item removed = new OutboxModel.Item(comment("o9", "Gone.").withTriage(Triage.CONFIRMED), false);
        assertEquals("Gone. — src/A.java:9 (removed)", ReviewWorkbenchOutbox.rowText(removed));
    }
}
