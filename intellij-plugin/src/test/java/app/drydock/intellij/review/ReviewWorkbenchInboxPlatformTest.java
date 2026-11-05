package app.drydock.intellij.review;

import app.drydock.intellij.review.workbench.model.InboxModel;
import app.drydock.intellij.review.workbench.model.WorkbenchState;
import app.drydock.review.AnnotationStatus;
import app.drydock.review.Confidence;
import app.drydock.review.DiffSelection;
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
import com.intellij.ui.components.JBTextArea;
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
        @Override public CompletableFuture<Optional<String>> postToPr(ReviewAnnotation f) { return record("postToPr " + f.id()); }
        @Override public CompletableFuture<Optional<String>> requestFix(ReviewAnnotation f) { return record("requestFix " + f.id()); }
        @Override public CompletableFuture<Optional<String>> dismiss(ReviewAnnotation f) { return record("dismiss " + f.id()); }
        @Override public CompletableFuture<Optional<String>> undo(ReviewAnnotation f) { return record("undo " + f.id()); }
        @Override public CompletableFuture<Optional<String>> comment(ReviewAnnotation f, String t) { return record("comment " + f.id()); }
        @Override public CompletableFuture<Optional<String>> challenge(ReviewAnnotation f, String t) { return record("challenge " + f.id()); }
        @Override public void showInDiff(ReviewAnnotation f) { calls.add("showInDiff " + f.id()); }
        @Override public void choose(WorkbenchState.InboxChoice c) { calls.add("choose " + c.tab() + " " + c.scope()); }
        @Override public CompletableFuture<Optional<String>> submit(ReviewWorkbenchInbox.Composer c, String text,
                                                                    Severity severity) {
            return record("submit " + c.mode() + " " + c.range().startKey() + " " + text
                    + (c.mode() == ReviewWorkbenchInbox.Composer.Mode.COMMENT ? " " + severity : ""));
        }
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

    public void testTheComposerSubmitsItsRangeAndClosesWhenTheWriteSucceeds() {
        next = CompletableFuture.completedFuture(Optional.of(ReviewTabProvider.COMMENT_ADDED));
        ReviewWorkbenchInbox inbox = new ReviewWorkbenchInbox(actions, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        try {
            ReviewWorkbenchInbox.Composer composer = new ReviewWorkbenchInbox.Composer(
                    ReviewWorkbenchInbox.Composer.Mode.COMMENT, new DiffSelection.Range("A.java", "n10", "n12", false));
            inbox.openComposer(composer);
            assertEquals(Optional.of(composer), inbox.composer());

            JBTextArea text = inbox.composerText;
            text.setText("  Use a try-with-resources here.  ");
            submitButton(inbox, "Add Comment").doClick();
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();

            assertEquals(List.of("submit COMMENT n10 Use a try-with-resources here. QUESTION"), calls);
            assertEquals(Optional.empty(), inbox.composer());
        } finally {
            Disposer.dispose(inbox);
        }
    }

    public void testTheComposerSwitchesToAQuestionAndACommentCarriesItsSeverity() {
        next = CompletableFuture.completedFuture(Optional.empty());
        ReviewWorkbenchInbox inbox = new ReviewWorkbenchInbox(actions, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        try {
            DiffSelection.Range range = new DiffSelection.Range("A.java", "n10", "n12", false);
            inbox.openComposer(new ReviewWorkbenchInbox.Composer(ReviewWorkbenchInbox.Composer.Mode.COMMENT, range));
            assertTrue(inbox.commentMode.isSelected());
            assertTrue("Question until another is picked", inbox.severities.get(Severity.QUESTION).isSelected());
            inbox.severities.get(Severity.BLOCKING).doClick();
            inbox.composerText.setText("This leaks the stream.");
            submitButton(inbox, "Add Comment").doClick();
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            assertEquals(List.of("submit COMMENT n10 This leaks the stream. BLOCKING"), calls);

            calls.clear();
            inbox.openComposer(new ReviewWorkbenchInbox.Composer(ReviewWorkbenchInbox.Composer.Mode.COMMENT, range));
            inbox.composerText.setText("Why is this cached?");
            inbox.askMode.doClick();
            assertEquals(Optional.of(new ReviewWorkbenchInbox.Composer(ReviewWorkbenchInbox.Composer.Mode.ASK, range)),
                    inbox.composer());
            assertFalse("a question has no severity", inbox.severities.get(Severity.NIT).getParent().isVisible());
            assertEquals("the text is kept", "Why is this cached?", inbox.composerText.getText());
            submitButton(inbox, "Ask Agent").doClick();
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            assertEquals(List.of("submit ASK n10 Why is this cached?"), calls);
        } finally {
            Disposer.dispose(inbox);
        }
    }

    public void testAFailedWriteKeepsTheComposerOpenWithItsText() {
        next = CompletableFuture.failedFuture(new IllegalStateException("disk full"));
        ReviewWorkbenchInbox inbox = new ReviewWorkbenchInbox(actions, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        try {
            inbox.openComposer(new ReviewWorkbenchInbox.Composer(
                    ReviewWorkbenchInbox.Composer.Mode.COMMENT, new DiffSelection.Range("A.java", "n10", "n10", false)));
            JBTextArea text = inbox.composerText;
            text.setText("Rename this.");
            submitButton(inbox, "Add Comment").doClick();
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();

            assertTrue(inbox.composer().isPresent());
            assertEquals("Rename this.", text.getText());
            assertTrue(text.isEnabled());
        } finally {
            Disposer.dispose(inbox);
        }
    }

    private static AbstractButton submitButton(ReviewWorkbenchInbox inbox, String text) {
        return UIUtil.uiTraverser(inbox).filter(AbstractButton.class).toList().stream()
                .filter(button -> text.equals(button.getText())).findFirst()
                .orElseThrow(() -> new AssertionError("no " + text));
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

    public void testAPullRequestReviewsInboxPostsAFindingToThePr() {
        ReviewWorkbenchInbox inbox = new ReviewWorkbenchInbox(actions, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        try {
            ReviewAnnotation a = finding("a", Triage.PROPOSED);
            inbox.render(state(List.of(a)));
            assertFalse("a local review has no Post to PR", UIUtil.uiTraverser(cards(inbox).get(0))
                    .filter(AbstractButton.class).toList().stream().anyMatch(b -> "Post to PR".equals(b.getText())));

            inbox.render(state(List.of(a), WorkbenchState.Mode.PR));
            click(cards(inbox).get(0), "Post to PR");
            assertEquals(List.of("postToPr a"), calls);
        } finally {
            Disposer.dispose(inbox);
        }
    }

    private static WorkbenchState state(List<ReviewAnnotation> findings) {
        return state(findings, WorkbenchState.Mode.LOCAL);
    }

    private static WorkbenchState state(List<ReviewAnnotation> findings, WorkbenchState.Mode mode) {
        return WorkbenchState.of("drydock", mode, new ReviewTabModel.Snapshot(List.of(), 0, 0),
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
