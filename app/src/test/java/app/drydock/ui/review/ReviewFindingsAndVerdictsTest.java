package app.drydock.ui.review;

import app.drydock.git.DiffService;
import app.drydock.git.UnifiedDiff;
import app.drydock.review.AnnotationStatus;
import app.drydock.review.Confidence;
import app.drydock.review.QueueAssembly;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.ReviewIntent;
import app.drydock.review.ReviewItem;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import app.drydock.review.ReviewVerdict;
import app.drydock.review.Severity;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The findings margin, the verdict bar and the parts of the keyboard table
 * they own -- driven through the headless harness against a real
 * {@code AnnotationStore}.
 */
class ReviewFindingsAndVerdictsTest extends ApplicationTest {

    private final DiffService diffService = new DiffService();
    private final ReviewScopeRegistry registry = new ReviewScopeRegistry();
    private FakeReviewHost host;
    private ReviewDestinationView view;
    private ReviewScope scope;

    @Override
    public void start(Stage stage) {
        try {
            host = new FakeReviewHost(Files.createTempDirectory("drydock-margin")
                    .resolve("annotations.json"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // Two files in DIFFERENT directories, so the fallback grouping yields
        // two intents to settle: it clusters by directory, so two files under
        // one would be a single card. src sorts before web, which is what
        // makes Main.java intent 1.
        host.diff = new UnifiedDiff(List.of(
                file("src/Main.java"), file("web/Other.java")));
        view = new ReviewDestinationView(host, diffService);
        Scene scene = new Scene(view, 1400, 900);
        scene.getStylesheets().addAll(
                getClass().getResource("/app/drydock/ui/app.css").toExternalForm(),
                getClass().getResource("/app/drydock/ui/theme-dark.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
    }

    @AfterEach
    void tearDown() {
        diffService.close();
        host.store.close();
    }

    // ---- the margin ---------------------------------------------------------

    @Test
    void findingsRenderAsCardsBesideTheCode() {
        seed(finding("f1", Severity.BLOCKING), finding("f2", Severity.NIT));

        assertEquals(2, lookup(".review-finding-card").queryAll().size());
        assertTrue(lookup(".review-severity-pill").queryAll().stream()
                .map(node -> ((Label) node).getText())
                .toList()
                .containsAll(List.of("blocking", "nit")));
    }

    /** Most severe first, so the thing that blocks approval is not below the fold. */
    @Test
    void cardsAreOrderedWorstFirst() {
        seed(finding("nit", Severity.NIT), finding("block", Severity.BLOCKING));

        List<String> pills = lookup(".review-severity-pill").queryAll().stream()
                .map(node -> ((Label) node).getText())
                .toList();
        assertEquals(List.of("blocking", "nit"), pills);
    }

    @Test
    void theOpenFilterHidesResolvedFindings() {
        seed(finding("f1", Severity.QUESTION), finding("f2", Severity.QUESTION));
        host.store.mutate(new ReviewAnnotation.Key(scope.id(), "f2"),
                current -> current.withStatus(AnnotationStatus.RESOLVED));
        interact(view::refreshReviewState);

        assertEquals(1, lookup(".review-finding-card").queryAll().size());

        interact(() -> ((Button) List.copyOf(lookup(".review-filter-button").queryAll()).get(1)).fire());
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(2, lookup(".review-finding-card").queryAll().size());
    }

    /**
     * The done-when for this milestone: a reply typed into one card must not
     * appear in another. Card nodes are cached per (scopeId, id) for exactly
     * this reason.
     */
    @Test
    void perThreadDraftsDoNotLeakBetweenFindings() {
        seed(finding("f1", Severity.QUESTION), finding("f2", Severity.QUESTION));

        TextArea first = replyBoxOf(0);
        TextArea second = replyBoxOf(1);
        interact(() -> first.setText("draft for the first finding"));

        assertEquals("draft for the first finding", first.getText());
        assertEquals("", second.getText(), "a draft must never appear in another finding's card");
        assertNotSame(first, second);
    }

    /** A store change elsewhere must not wipe an in-progress draft. */
    @Test
    void aDraftSurvivesAnUnrelatedStoreChange() {
        seed(finding("f1", Severity.QUESTION), finding("f2", Severity.QUESTION));
        TextArea draft = replyBoxOf(0);
        interact(() -> draft.setText("still typing"));

        host.store.mutate(new ReviewAnnotation.Key(scope.id(), "f2"),
                current -> current.withStatus(AnnotationStatus.RESOLVED));
        interact(view::refreshReviewState);

        assertEquals("still typing", replyBoxOf(0).getText());
    }

    @Test
    void replyingAppendsToTheThreadAndClearsTheBox() {
        seed(finding("f1", Severity.QUESTION));
        TextArea reply = replyBoxOf(0);
        interact(() -> reply.setText("I disagree"));

        interact(() -> fire(".review-card-action", "Reply"));

        assertEquals(2, host.store.byId(scope.id(), "f1").orElseThrow().thread().size());
        assertEquals("", replyBoxOf(0).getText());
    }

    @Test
    void anAskChipPostsItsQuestionAsTheHuman() {
        ReviewAnnotation withAsk = finding("f1", Severity.BLOCKING).withStatus(AnnotationStatus.OPEN);
        ReviewAnnotation asked = new ReviewAnnotation(scopeId(), "f1", withAsk.intentId(),
                withAsk.file(), withAsk.startKey(), withAsk.endKey(), withAsk.severity(),
                withAsk.confidence(), withAsk.title(), withAsk.author(), withAsk.at(),
                withAsk.evidence(), withAsk.patch(), withAsk.deviatesFrom(),
                List.of(new ReviewAnnotation.Ask("Why is it a leak?", "Explain why this leaks.")),
                withAsk.thread(), Optional.empty(), withAsk.status(), withAsk.github(), withAsk.postToPr());
        seed(asked);

        interact(() -> ((Button) lookup(".review-ask-chip").query()).fire());
        WaitForAsyncUtils.waitForFxEvents();

        List<ReviewAnnotation.Message> thread = host.store.byId(scope.id(), "f1").orElseThrow().thread();
        assertEquals("Explain why this leaks.", thread.get(thread.size() - 1).text());
        assertEquals("You", thread.get(thread.size() - 1).author(), "an ASK is the human speaking");
    }

    /** Applying a proposed patch is a human click and a hand-off, never drydock editing the tree. */
    @Test
    void applyPatchIsAHumanClickThatHandsOffToTheSession() {
        ReviewAnnotation base = finding("f1", Severity.BLOCKING);
        seed(new ReviewAnnotation(scopeId(), "f1", base.intentId(), base.file(), base.startKey(),
                base.endKey(), base.severity(), base.confidence(), base.title(), base.author(),
                base.at(), base.evidence(),
                Optional.of(new ReviewAnnotation.Patch("--- a\n+++ b\n", "one line in onRelease")),
                base.deviatesFrom(), base.asks(), base.thread(), Optional.empty(), base.status(),
                base.github(), base.postToPr()));

        assertTrue(host.handedOffPrompts.isEmpty());
        interact(() -> fire(".review-card-action", "Apply patch"));

        assertEquals(1, host.handedOffPrompts.size());
        assertEquals(AnnotationStatus.SENT,
                host.store.byId(scope.id(), "f1").orElseThrow().status());
    }

    @Test
    void collapsingTheMarginKeepsTheCountVisible() {
        seed(finding("f1", Severity.BLOCKING));

        type(KeyCode.M);
        WaitForAsyncUtils.waitForFxEvents();

        // queryAll() ignores visibility, so assert on what actually collapsed.
        assertFalse(lookup(".review-findings-scroll").query().isVisible(), "the cards collapse away");
        Label count = lookup(".review-margin-collapsed-count").query();
        assertEquals("1", count.getText(), "the count survives the collapse");
        assertTrue(count.getStyleClass().contains("severity-blocking"),
                "the strip is coloured by the worst severity: " + count.getStyleClass());
    }

    // ---- verdicts -----------------------------------------------------------

    @Test
    void approvingAnIntentRecordsAVerdict() {
        seed();

        type(KeyCode.A);

        assertEquals(ReviewVerdict.Decision.APPROVED,
                host.store.verdict(scope.id(), "auto:change:src").orElseThrow().decision());
    }

    /** Spec §4.6: approval is refused while a blocking finding of the intent is open. */
    @Test
    void approvalIsRefusedWhileABlockingFindingIsOpen() {
        seed(finding("f1", Severity.BLOCKING));

        type(KeyCode.A);

        assertTrue(host.store.verdict(scope.id(), "auto:change:src").isEmpty(),
                "an open blocking finding must refuse approval");
        assertFalse(lookup(".review-verdict-refusal").queryAll().isEmpty(),
                "the refusal must be visible, not silent");
    }

    @Test
    void resolvingTheBlockerLetsTheApprovalThrough() {
        seed(finding("f1", Severity.BLOCKING));
        type(KeyCode.A);
        assertTrue(host.store.verdict(scope.id(), "auto:change:src").isEmpty());

        host.store.mutate(new ReviewAnnotation.Key(scope.id(), "f1"),
                current -> current.withStatus(AnnotationStatus.RESOLVED));
        interact(view::refreshReviewState);
        type(KeyCode.A);

        assertTrue(host.store.verdict(scope.id(), "auto:change:src").isPresent());
    }

    /** A human downgrade after a discussion is the other way past a blocker. */
    @Test
    void downgradingTheSeverityAlsoLetsTheApprovalThrough() {
        seed(finding("f1", Severity.BLOCKING));

        interact(() -> fire(".review-card-action", "Downgrade"));
        type(KeyCode.A);

        assertTrue(host.store.verdict(scope.id(), "auto:change:src").isPresent());
        assertEquals(Severity.BLOCKING, host.store.byId(scope.id(), "f1").orElseThrow().severity(),
                "the reviewer's original opinion is kept alongside the override");
    }

    @Test
    void requestChangesAndUndoRoundTrip() {
        seed();

        type(KeyCode.R);
        assertEquals(ReviewVerdict.Decision.CHANGES,
                host.store.verdict(scope.id(), "auto:change:src").orElseThrow().decision());

        type(KeyCode.U);
        assertTrue(host.store.verdict(scope.id(), "auto:change:src").isEmpty());
    }

    @Test
    void bracketsMoveBetweenIntents() {
        seed();

        assertEquals("1 · Main.java", intentLabel());
        type(KeyCode.CLOSE_BRACKET);
        assertEquals("2 · Other.java", intentLabel());
        type(KeyCode.CLOSE_BRACKET);
        assertEquals("2 · Other.java", intentLabel(), "the intent index clamps at the last one");
        type(KeyCode.OPEN_BRACKET);
        assertEquals("1 · Main.java", intentLabel());
    }

    @Test
    void nJumpsToTheNextUnsettledIntent() {
        seed();
        type(KeyCode.A);

        type(KeyCode.N);

        assertEquals("2 · Other.java", intentLabel());
    }

    /** Submitting early jumps to the first unsettled intent rather than posting a partial review. */
    @Test
    void submitJumpsToTheFirstUnsettledIntentWhenIncomplete() {
        seed();
        type(KeyCode.CLOSE_BRACKET);
        type(KeyCode.A);

        type(KeyCode.ENTER);

        assertTrue(host.submittedScopes.isEmpty(), "an incomplete review must not be posted");
        assertEquals("1 · Main.java", intentLabel());
    }

    @Test
    void submitPostsOneReviewOnceEverythingIsSettled() {
        seed();
        type(KeyCode.A);
        type(KeyCode.CLOSE_BRACKET);
        type(KeyCode.A);

        type(KeyCode.ENTER);

        assertEquals(List.of(scope.id()), host.submittedScopes);
    }

    /** Collapsing every rail must never take the primary action with it (spec §4.6). */
    @Test
    void theVerdictBarSurvivesFocusMode() {
        seed(finding("f1", Severity.QUESTION));

        type(KeyCode.F);
        WaitForAsyncUtils.waitForFxEvents();

        assertFalse(lookup(".review-findings-scroll").query().isVisible(), "the margin collapses");
        assertTrue(lookup(".review-verdict-action").queryAll().stream().anyMatch(Node::isVisible),
                "the verdict bar must stay reachable with every rail collapsed");
        assertEquals("1 · Main.java", intentLabel());
    }

    @Test
    void shiftFWidensTheMarginToTheWholeReview() {
        ReviewAnnotation other = new ReviewAnnotation(scopeId(), "f_other",
                Optional.of("auto:change:web"), "web/Other.java", "n1", "n1",
                Severity.QUESTION, Confidence.HIGH, Optional.of("elsewhere"), "Claude",
                Instant.EPOCH, List.of(), Optional.empty(), Optional.empty(), List.of(),
                List.of(new ReviewAnnotation.Message("Claude", Instant.EPOCH, "elsewhere")),
                Optional.empty(), AnnotationStatus.OPEN, Optional.empty(), false);
        seed(finding("f1", Severity.QUESTION), other);

        assertEquals(1, lookup(".review-finding-card").queryAll().size(),
                "only the current intent's findings by default");

        press(KeyCode.SHIFT).press(KeyCode.F).release(KeyCode.F).release(KeyCode.SHIFT);
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(2, lookup(".review-finding-card").queryAll().size());
    }

    // ---- fixtures -----------------------------------------------------------

    private String scopeId() {
        return scope == null ? mintScope().id() : scope.id();
    }

    private ReviewScope mintScope() {
        if (scope == null) {
            scope = registry.mint(ReviewScopeRegistry.spec(ReviewScope.Kind.WORKTREE,
                    Path.of("/repo"), Optional.of(Path.of("/wt/feat")), "master", "feat",
                    Optional.empty(), Optional.empty()));
        }
        return scope;
    }

    /** Seeds the queue with one item and the store with {@code findings}. */
    private void seed(ReviewAnnotation... findings) {
        ReviewScope minted = mintScope();
        for (ReviewAnnotation finding : findings) {
            host.store.upsert(finding);
        }
        // The view renders the diff column for a worktree scope; the fake's
        // diff is what the by-file intent fallback groups.
        interact(() -> view.setItems(new QueueAssembly(List.of(new ReviewItem(minted, ReviewItem.Group.AGENTS,
                "feat", "drydock · vs master")), true, true), List.of("repo")));
        interact(() -> view.diagPublishOutcome(minted.id(),
                new DiffOutcome.Loaded(host.diff)));
        interact(view::refreshReviewState);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private ReviewAnnotation finding(String id, Severity severity) {
        return new ReviewAnnotation(scopeId(), id, Optional.of("auto:change:src"),
                "src/Main.java", "n1", "n1", severity, Confidence.HIGH,
                Optional.of("Title " + id), "Claude", Instant.EPOCH, List.of(),
                Optional.empty(), Optional.empty(), List.of(),
                List.of(new ReviewAnnotation.Message("Claude", Instant.EPOCH, "body of " + id)),
                Optional.empty(), AnnotationStatus.OPEN, Optional.empty(), false);
    }

    private static UnifiedDiff.FileDiff file(String path) {
        return new UnifiedDiff.FileDiff(path, "M", 1, 0, false, false,
                List.of(new UnifiedDiff.Hunk("@@", List.of(new UnifiedDiff.Line(
                        UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(), OptionalInt.of(1), "x")))));
    }

    private String intentLabel() {
        return ((Label) lookup(".review-verdict-intent").query()).getText();
    }

    private TextArea replyBoxOf(int cardIndex) {
        List<Node> cards = List.copyOf(lookup(".review-finding-card").queryAll());
        javafx.scene.Parent card = (javafx.scene.Parent) cards.get(cardIndex);
        return (TextArea) card.lookupAll(".review-reply-input").iterator().next();
    }

    /** Fires the button with {@code text} among the nodes matching {@code selector}. */
    private void fire(String selector, String text) {
        lookup(selector).queryAll().stream()
                .map(Button.class::cast)
                .filter(button -> button.getText().equals(text))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + selector + " labelled '" + text + "'"))
                .fire();
    }

    private void type(KeyCode key) {
        interact(view::requestFocus);
        press(key).release(key);
        WaitForAsyncUtils.waitForFxEvents();
    }
}
