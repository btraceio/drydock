package app.drydock.intellij.review;

import app.drydock.intellij.review.workbench.model.OutboxModel;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.Triage;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The outbox dock over fake actions: the disabled reasons, the busy states and
 * outcomes, the tabs, the collapsed bar's quick actions and an item's place.
 */
public class ReviewWorkbenchOutboxPlatformTest extends BasePlatformTestCase {

    private final List<String> sent = new ArrayList<>();
    private CompletableFuture<Optional<String>> next = new CompletableFuture<>();

    @Override
    protected void tearDown() throws Exception {
        try {
            PropertiesComponent.getInstance().unsetValue(ReviewWorkbenchOutbox.OPEN_KEY);
        } finally {
            super.tearDown();
        }
    }

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
        assertFalse(outbox.quickSend.isEnabled());
        assertEquals(ReviewWorkbenchOutbox.NO_SESSION, outbox.reason.getText());

        outbox.render(OutboxModel.snapshot(List.of(), true));
        assertFalse("nothing to send", outbox.send.isEnabled());
        assertEquals(ReviewWorkbenchOutbox.SEND, outbox.send.getText());
        assertEquals("", outbox.reason.getText());
    }

    public void testASendIsBusyUntilItCompletesAndShowsItsOutcome() {
        ReviewWorkbenchOutbox outbox = outbox();
        outbox.render(OutboxModel.snapshot(List.of(comment("n5", "Rename it.")), true));
        assertEquals("Send 1 to Session", outbox.quickSend.getText());
        outbox.quickSend.doClick();
        assertEquals(List.of("send"), sent);
        assertEquals(ReviewWorkbenchOutbox.SENDING, outbox.send.getText());
        assertFalse(outbox.send.isEnabled());

        outbox.send.doClick();
        assertEquals("a running send is not started twice", List.of("send"), sent);

        next.complete(Optional.of("Sent to the agent."));
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        assertEquals("Send 1 to Session", outbox.send.getText());
        assertEquals("Sent to the agent.", outbox.notice.getText());
    }

    public void testFixesLandOnShowsInAPullRequestReviewAndPassesTheChoiceOn() {
        List<Boolean> chosen = new ArrayList<>();
        ReviewWorkbenchOutbox outbox = new ReviewWorkbenchOutbox(new ReviewWorkbenchOutbox.Actions() {
            @Override public CompletableFuture<Optional<String>> send() { return next; }
            @Override public void fixesLandOn(boolean stackedSubPr) { chosen.add(stackedSubPr); }
        });
        outbox.render(OutboxModel.snapshot(List.of(), true, false));
        assertFalse("a local review has no choice", outbox.stackedSubPr.getParent().getParent().isVisible());

        outbox.render(OutboxModel.snapshot(List.of(), true, true).withStackedSubPr(true));
        assertTrue(outbox.stackedSubPr.getParent().getParent().isVisible());
        assertTrue(outbox.stackedSubPr.isSelected());
        outbox.sameBranch.doClick();
        assertEquals(List.of(false), chosen);
    }

    public void testThePrLaneShowsInAPullRequestReviewAndIsBusyWhileItChecks() {
        CompletableFuture<Optional<String>> opened = new CompletableFuture<>();
        List<String> asked = new ArrayList<>();
        ReviewWorkbenchOutbox outbox = new ReviewWorkbenchOutbox(new ReviewWorkbenchOutbox.Actions() {
            @Override public CompletableFuture<Optional<String>> send() { return next; }
            @Override public CompletableFuture<Optional<String>> submitReview() { asked.add("submit"); return opened; }
        });
        outbox.render(OutboxModel.snapshot(List.of(), true, false));
        outbox.showPrTab(true);
        assertFalse("a local review has no Pull request tab", outbox.prTabShown());
        assertFalse("nor its quick action", outbox.quickPost.isVisible());

        ReviewAnnotation forPr = comment("n5", "Rename it.").withTriage(Triage.CONFIRMED).withPostToPr(true);
        outbox.render(OutboxModel.snapshot(List.of(forPr), true, true));
        outbox.showPrTab(true);
        assertTrue(outbox.prTabShown());
        assertTrue("showing a tab opens the dock", outbox.isOpen());
        assertEquals("1 comment to post", ReviewWorkbenchOutbox.prCountsText(OutboxModel.snapshot(List.of(forPr), true, true)));
        outbox.submitReview.doClick();
        assertEquals(List.of("submit"), asked);
        assertEquals(ReviewWorkbenchOutbox.CHECKING, outbox.submitReview.getText());
        assertFalse(outbox.submitReview.isEnabled());
        assertFalse(outbox.quickPost.isEnabled());

        opened.complete(Optional.of("gh is not installed"));
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        assertEquals(ReviewWorkbenchOutbox.SUBMIT_REVIEW, outbox.submitReview.getText());
        assertEquals("gh is not installed", outbox.prNotice.getText());
        assertEquals("the outcome also shows on the bar", "gh is not installed", outbox.notice.getText());
    }

    public void testTheTabsCountTheirLanesAndAPullRequestTabShowsOnlyInAPrReview() {
        ReviewAnnotation forPr = comment("n5", "Rename it.").withTriage(Triage.CONFIRMED).withPostToPr(true);
        ReviewAnnotation toFix = comment("n7", "Fix it.").withTriage(Triage.CONFIRMED);
        OutboxModel.Snapshot pr = OutboxModel.snapshot(List.of(forPr), true, true);
        assertEquals("Pull request (1)", ReviewWorkbenchOutbox.tabLabel(true, pr));
        assertEquals(1, pr.forPr().size());
        assertEquals("Session (1)", ReviewWorkbenchOutbox.tabLabel(false,
                OutboxModel.snapshot(List.of(comment("n5", "Rename it.")), true)));

        ReviewWorkbenchOutbox outbox = outbox();
        assertEquals("Pull request and Session", 2, outbox.tabActions.getChildActionsOrStubs().length);
        outbox.render(pr);
        outbox.showPrTab(true);
        assertTrue(outbox.prTabShown());

        outbox.render(OutboxModel.snapshot(List.of(toFix), true, false));
        assertFalse("a local review falls back to the Session tab", outbox.prTabShown());
    }

    public void testTheDockOpensAndCollapsesAndTheBarKeepsItsQuickActionsOnlyCollapsed() {
        ReviewWorkbenchOutbox outbox = outbox();
        outbox.render(OutboxModel.snapshot(List.of(comment("n5", "Rename it.")), true, true));
        assertFalse(outbox.isOpen());
        assertTrue(outbox.quickSend.getParent().isVisible());

        outbox.setOpen(true);
        assertTrue(PropertiesComponent.getInstance().getBoolean(ReviewWorkbenchOutbox.OPEN_KEY, false));
        assertFalse("open, the actions sit beside the list", outbox.quickSend.getParent().isVisible());
        outbox.setOpen(false);
        assertTrue(outbox.quickSend.getParent().isVisible());
    }

    public void testAnItemsPlaceNamesItsLineOrTheWholeChange() {
        assertEquals("src/A.java:5", ReviewWorkbenchOutbox.place(comment("n5", "Rename it.")));
        assertEquals("src/A.java:9 (removed)", ReviewWorkbenchOutbox.place(comment("o9", "Gone.")));
        assertEquals("the whole change", ReviewWorkbenchOutbox.place(ReviewAnnotation.human("rs_1", "", "", "",
                new ReviewAnnotation.Message("You", Instant.EPOCH, "Split this PR."))));
    }
}
