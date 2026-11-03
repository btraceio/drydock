package app.drydock.intellij.review;

import app.drydock.intellij.review.workbench.model.InboxModel;
import app.drydock.intellij.review.workbench.model.WorkbenchState;
import app.drydock.review.AnnotationStatus;
import app.drydock.review.Confidence;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.Severity;
import app.drydock.review.Triage;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.TestActionEvent;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.UIUtil;

import javax.swing.AbstractButton;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The workbench inbox pane over a fake review: cards per tab, a decision
 * completing its card, auto-advance to the next card to triage, the notice
 * line, the tab toggles and the empty state.
 */
public class ReviewWorkbenchInboxPlatformTest extends BasePlatformTestCase {

    private final List<String> calls = new ArrayList<>();
    private CompletableFuture<Optional<String>> next = CompletableFuture.completedFuture(Optional.empty());

    private final ReviewWorkbenchInbox.Actions actions = new ReviewWorkbenchInbox.Actions() {
        @Override public CompletableFuture<Optional<String>> requestFix(ReviewAnnotation f) { return record("requestFix " + f.id()); }
        @Override public CompletableFuture<Optional<String>> dismiss(ReviewAnnotation f) { return record("dismiss " + f.id()); }
        @Override public CompletableFuture<Optional<String>> undo(ReviewAnnotation f) { return record("undo " + f.id()); }
        @Override public CompletableFuture<Optional<String>> comment(ReviewAnnotation f, String t) { return record("comment " + f.id()); }
        @Override public CompletableFuture<Optional<String>> challenge(ReviewAnnotation f, String t) { return record("challenge " + f.id()); }
        @Override public void showInDiff(ReviewAnnotation f) { calls.add("showInDiff " + f.id()); }
        @Override public void choose(WorkbenchState.InboxChoice c) { calls.add("choose " + c.tab() + " " + c.scope()); }
    };

    private CompletableFuture<Optional<String>> record(String call) {
        calls.add(call);
        return next;
    }

    public void testADecisionCompletesItsCardAndAdvancesToTheNextCardToTriage() {
        ReviewWorkbenchInbox inbox = new ReviewWorkbenchInbox(actions, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        try {
            ReviewAnnotation a = finding("a", Triage.PROPOSED);
            ReviewAnnotation b = finding("b", Triage.PROPOSED);
            inbox.render(state(List.of(a, b)));
            assertEquals(2, cards(inbox).size());

            click(cards(inbox).get(0), "Request fix");
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            assertEquals(List.of("requestFix a"), calls);
            assertFalse("completed when the future did", cards(inbox).get(0).isBusy());

            // The decision's snapshot: a left To Triage.
            inbox.render(state(List.of(a.withTriage(Triage.CONFIRMED), b)));
            assertEquals(List.of("requestFix a", "showInDiff b"), calls);
            assertEquals(1, cards(inbox).size());
        } finally {
            Disposer.dispose(inbox);
        }
    }

    public void testAnOutcomesTextShowsInTheNoticeLine() {
        next = CompletableFuture.completedFuture(Optional.of("No agent session is bound to this review: nothing was sent."));
        ReviewWorkbenchInbox inbox = new ReviewWorkbenchInbox(actions, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        try {
            inbox.render(state(List.of(finding("a", Triage.PROPOSED))));
            click(cards(inbox).get(0), "Won't fix");
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();

            assertTrue(UIUtil.uiTraverser(inbox).filter(JBLabel.class).toList().stream().anyMatch(label ->
                    label.isVisible() && label.getText().startsWith("No agent session")));
        } finally {
            Disposer.dispose(inbox);
        }
    }

    public void testTheTabTogglesShowCountsAndChooseTheirTab() {
        ReviewWorkbenchInbox inbox = new ReviewWorkbenchInbox(actions, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        try {
            inbox.render(state(List.of(finding("a", Triage.PROPOSED), finding("b", Triage.CONFIRMED))));
            ToggleAction outbox = (ToggleAction) inbox.tabActions.getChildActionsOrStubs()[1];
            AnActionEvent event = TestActionEvent.createTestEvent(outbox);
            outbox.update(event);
            assertEquals("Outbox (1)", event.getPresentation().getText());
            assertFalse(outbox.isSelected(event));

            outbox.setSelected(event, true);
            assertEquals(List.of("choose OUTBOX ALL"), calls);
        } finally {
            Disposer.dispose(inbox);
        }
    }

    public void testAnEmptyTabSaysSo() {
        ReviewWorkbenchInbox inbox = new ReviewWorkbenchInbox(actions, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        try {
            inbox.render(state(List.of()));
            assertTrue(cards(inbox).isEmpty());
            assertTrue(UIUtil.uiTraverser(inbox).filter(JBLabel.class).toList().stream()
                    .anyMatch(label -> ReviewWorkbenchInbox.EMPTY_TEXT.equals(label.getText())));
        } finally {
            Disposer.dispose(inbox);
        }
    }

    public void testTheChallengeMessagesSayWhatHappened() {
        assertEquals(Optional.empty(), ReviewTabProvider.challengeMessage(ReviewTriageActions.ChallengeOutcome.State.BLANK));
        assertEquals(Optional.of(ReviewTabProvider.FINDING_GONE),
                ReviewTabProvider.challengeMessage(ReviewTriageActions.ChallengeOutcome.State.MISSING));
        for (ReviewTriageActions.ChallengeOutcome.State state : List.of(
                ReviewTriageActions.ChallengeOutcome.State.REFUSED, ReviewTriageActions.ChallengeOutcome.State.FAILED)) {
            assertTrue(state + " keeps the comment",
                    ReviewTabProvider.challengeMessage(state).orElseThrow().endsWith("Your comment is kept."));
        }
        assertTrue(ReviewTabProvider.challengeMessage(ReviewTriageActions.ChallengeOutcome.State.NO_SESSION)
                .orElseThrow().endsWith("nothing was sent."));
    }

    private static WorkbenchState state(List<ReviewAnnotation> findings) {
        return WorkbenchState.of("drydock", WorkbenchState.Mode.LOCAL, new ReviewTabModel.Snapshot(List.of(), 0, 0),
                WorkbenchState.Filter.ALL, Optional.empty(), WorkbenchState.View.CHANGES_ONLY, findings,
                new WorkbenchState.InboxChoice(InboxModel.Tab.TO_TRIAGE, InboxModel.Scope.ALL));
    }

    private static List<FindingInlayCard> cards(ReviewWorkbenchInbox inbox) {
        return UIUtil.uiTraverser(inbox).filter(FindingInlayCard.class).toList();
    }

    private static void click(FindingInlayCard card, String text) {
        UIUtil.uiTraverser(card).filter(AbstractButton.class).toList().stream()
                .filter(button -> text.equals(button.getText())).findFirst()
                .orElseThrow(() -> new AssertionError("no " + text)).doClick();
    }

    private static ReviewAnnotation finding(String id, Triage triage) {
        return new ReviewAnnotation("s1", id, "A.java", "n1", "n1", Severity.BLOCKING, Confidence.HIGH,
                Optional.of("Title " + id), "Claude", Instant.EPOCH, List.of(), Optional.empty(), Optional.empty(),
                List.of(), List.of(new ReviewAnnotation.Message("Claude", Instant.EPOCH, "body")), Optional.empty(),
                AnnotationStatus.OPEN, Optional.empty(), false, triage, Optional.empty());
    }
}
