package app.drydock.ui.review;

import app.drydock.ui.TestStages;
import app.drydock.git.DiffService;
import app.drydock.git.UnifiedDiff;
import app.drydock.review.AnnotationStatus;
import app.drydock.review.Confidence;
import app.drydock.review.HunkDigest;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.ReviewIntent;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import app.drydock.review.ReviewVerdict;
import app.drydock.review.Severity;
import app.drydock.review.SessionReviewScopes;
import app.drydock.review.SubmitPlan;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Region;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private SessionReviewView view;
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
        view = new SessionReviewView(host, diffService, null);
        Scene scene = new Scene(view, 1400, 900);
        scene.getStylesheets().addAll(
                getClass().getResource("/app/drydock/ui/app.css").toExternalForm(),
                getClass().getResource("/app/drydock/ui/theme-dark.css").toExternalForm());
        TestStages.show(stage, scene);
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

        // m is a toggle, not a one-way collapse: the return trip is the half
        // a reader is stuck without, and it has its own guard (the manual
        // collapse is remembered, so the responsive solver must not re-apply
        // it on the way back).
        type(KeyCode.M);
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(lookup(".review-findings-scroll").query().isVisible(),
                "pressing m again must bring the cards back");
    }

    /**
     * The only control that can withdraw a comment before Submit publishes
     * it to the pull request. {@code ReviewAnnotation.human} mints {@code
     * postToPr = true} and that default is persisted -- a real pointer click
     * on the card's toggle (not {@code Button.fire()}, which bypasses actual
     * event delivery) must flip it to {@code false} in the store, and {@code
     * SubmitPlan.of} must then drop the finding from both {@code comments}
     * and {@code posting}.
     */
    @Test
    void theIncludeExcludeToggleWithdrawsAHumanCommentFromTheStoreAndFromTheSubmitPlan() {
        ReviewAnnotation human = ReviewAnnotation.human(scopeId(), "src/Main.java", "n1", "n1",
                new ReviewAnnotation.Message("You", Instant.now(), "note to self"));
        seed(human);
        assertTrue(host.store.byId(scope.id(), human.key().id()).orElseThrow().postToPr(),
                "precondition: ReviewAnnotation.human defaults to posting");

        clickOn("Included in review");
        WaitForAsyncUtils.waitForFxEvents();

        ReviewAnnotation afterToggle = host.store.byId(scope.id(), human.key().id()).orElseThrow();
        assertFalse(afterToggle.postToPr(), "a real click on the toggle must withdraw the comment");

        SubmitPlan.DiffIndex index = new SubmitPlan.DiffIndex(
                Map.of("src/Main.java n1", 0), Map.of("src/Main.java n1", 0));
        SubmitPlan plan = SubmitPlan.of(List.of(afterToggle), List.of(), index);
        assertTrue(plan.comments().isEmpty(), "an excluded finding must not become a SubmitPlan comment");
        assertTrue(plan.posting().isEmpty(), "an excluded finding must not be marked as posting either");
    }

    // ---- verdicts -----------------------------------------------------------

    @Test
    void approvingAnIntentRecordsAVerdict() {
        seed();

        type(KeyCode.A);

        assertEquals(ReviewVerdict.Decision.APPROVED,
                host.store.verdict(scope.id(), digestOfMain()).orElseThrow().decision());
    }

    /** Spec §4.6: approval is refused while a blocking finding of the intent is open. */
    @Test
    void approvalIsRefusedWhileABlockingFindingIsOpen() {
        seed(finding("f1", Severity.BLOCKING));

        type(KeyCode.A);

        assertTrue(host.store.verdict(scope.id(), digestOfMain()).isEmpty(),
                "an open blocking finding must refuse approval");
        assertFalse(lookup(".review-verdict-refusal").queryAll().isEmpty(),
                "the refusal must be visible, not silent");
    }

    @Test
    void resolvingTheBlockerLetsTheApprovalThrough() {
        seed(finding("f1", Severity.BLOCKING));
        type(KeyCode.A);
        assertTrue(host.store.verdict(scope.id(), digestOfMain()).isEmpty());

        host.store.mutate(new ReviewAnnotation.Key(scope.id(), "f1"),
                current -> current.withStatus(AnnotationStatus.RESOLVED));
        interact(view::refreshReviewState);
        type(KeyCode.A);

        assertTrue(host.store.verdict(scope.id(), digestOfMain()).isPresent());
    }

    /** A human downgrade after a discussion is the other way past a blocker. */
    @Test
    void downgradingTheSeverityAlsoLetsTheApprovalThrough() {
        seed(finding("f1", Severity.BLOCKING));

        interact(() -> fire(".review-card-action", "Downgrade"));
        type(KeyCode.A);

        assertTrue(host.store.verdict(scope.id(), digestOfMain()).isPresent());
        assertEquals(Severity.BLOCKING, host.store.byId(scope.id(), "f1").orElseThrow().severity(),
                "the reviewer's original opinion is kept alongside the override");
    }

    @Test
    void requestChangesAndUndoRoundTrip() {
        seed();

        type(KeyCode.R);
        assertEquals(ReviewVerdict.Decision.CHANGES,
                host.store.verdict(scope.id(), digestOfMain()).orElseThrow().decision());

        type(KeyCode.U);
        assertTrue(host.store.verdict(scope.id(), digestOfMain()).isEmpty());
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

    /**
     * Submitting early jumps to the first unsettled intent rather than
     * posting a partial review -- and says so. The jump alone was the
     * original defect's other half: Submit silently moved the reader and did
     * nothing else, with no explanation, so the button read as broken.
     */
    @Test
    void submitJumpsToTheFirstUnsettledIntentWhenIncomplete() {
        seed();
        type(KeyCode.CLOSE_BRACKET);
        type(KeyCode.A);

        type(KeyCode.ENTER);

        assertTrue(host.submittedScopes.isEmpty(), "an incomplete review must not be posted");
        assertEquals("1 · Main.java", intentLabel());
        assertTrue(submitRefusal().toLowerCase(Locale.ROOT).contains("verdict"),
                "the message must say what is blocking: " + submitRefusal());
    }

    /**
     * A PR scope whose diff FAILED has nothing to anchor a comment to, so
     * Submit must keep refusing -- but visibly. The bug this pins is the
     * guard doing nothing at all, silently and for the rest of the session,
     * because {@code ReviewDiffColumn.reload} clears {@code
     * displayedScopeId} on a failure and nothing here ever retries it (see
     * {@link SessionReviewView#submitReview}).
     */
    @Test
    void submitOnAFailedDiffShowsAVisibleRefusalInsteadOfDoingNothing() {
        ReviewScope pr = seedWithNoDiffInTheColumn(mintPrScope(),
                new DiffOutcome.Failed("Could not diff /wt/feat"));

        type(KeyCode.ENTER);

        assertTrue(submitRefusal().toLowerCase(Locale.ROOT).contains("failed"),
                "the message must say why: " + submitRefusal());
        assertTrue(host.submittedScopes.isEmpty(),
                "a PR scope with no diff to anchor comments to must not reach host.submit");
        assertFalse(pr.pr().isEmpty(), "this fixture is only meaningful for a PR scope");
    }

    /**
     * The sibling of the failed case, and the reason the guard cannot simply
     * key on "no displayed diff": the window between selecting a scope and
     * its diff landing is temporary, so the refusal has to invite a retry
     * rather than declare the diff broken.
     */
    @Test
    void submitWhileTheDiffIsStillLoadingRefusesWithARetryableMessage() {
        seedWithNoDiffInTheColumn(mintPrScope(), new DiffOutcome.Diffing());

        type(KeyCode.ENTER);

        assertTrue(submitRefusal().toLowerCase(Locale.ROOT).contains("still loading"),
                "a diff still in flight must not read as a broken one: " + submitRefusal());
        assertTrue(host.submittedScopes.isEmpty());
    }

    /**
     * The fall-through the other two guard: a non-PR scope posts no comments
     * either way ({@code Host#submit} takes it straight to the Finish
     * hand-off), so a FAILED diff must not leave IT stuck refusing for the
     * rest of the session the way a PR scope correctly is.
     */
    @Test
    void submitOnAFailedDiffStillReachesTheHostForANonPrScope() {
        ReviewScope local = seedWithNoDiffInTheColumn(mintScope(),
                new DiffOutcome.Failed("Could not diff /wt/feat"));
        assertTrue(local.pr().isEmpty(), "this scope must not be a PR");

        type(KeyCode.ENTER);

        assertEquals(List.of(local.id()), host.submittedScopes,
                "a failed diff must not strand the merge-and-finish path");
    }

    /**
     * The whole reason {@code submitReview()} builds the {@code DiffIndex}
     * from {@code ReviewDiffColumn.displayedDiff()} rather than from the
     * rendered rows: a run of unchanged lines longer than {@code
     * ReviewDiffRows.COLLAPSE_THRESHOLD} folds into a single collapsed-run
     * row and disappears from the rows entirely, but every one of those
     * lines is still a perfectly valid GitHub anchor.
     */
    @Test
    void submitBuildsTheDiffIndexFromTheRealDiffNotTheCollapsedRows() {
        host.diff = fileWithALongUnchangedRun();
        seed();

        // n3 sits inside the collapsed run above the change at n20 -- rendered
        // as a folded "N unchanged" row, never as its own Line row.
        assertTrue(lookup(".review-collapsed-run").tryQuery().isPresent(),
                "the fixture must actually produce a collapsed run, or this test proves nothing");

        type(KeyCode.A);
        type(KeyCode.ENTER);

        assertEquals(List.of(scope.id()), host.submittedScopes);
        SubmitPlan.DiffIndex index = host.submittedIndexes.get(scope.id());
        assertTrue(index.positionOfKey().containsKey("src/Big.java n3"),
                "a line hidden behind a collapsed run must still be in the index built from the diff");
        assertTrue(index.hunkOfKey().containsKey("src/Big.java n3"));
        assertTrue(index.positionOfKey().containsKey("src/Big.java n20"),
                "the changed line itself must be indexed too");
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

    /**
     * Focus mode is a toggle, not a one-way collapse -- {@code f} twice must
     * return the rails. Its condition is {@code !(intentsCollapsedByUser &amp;&amp;
     * marginCollapsedByUser)}, so it is the one shortcut whose second press
     * takes a different branch than its first.
     */
    @Test
    void fTogglesFocusModeRatherThanOnlyCollapsing() {
        seed(finding("f1", Severity.QUESTION));
        assertTrue(lookup(".review-findings-scroll").query().isVisible());

        type(KeyCode.F);
        WaitForAsyncUtils.waitForFxEvents();
        assertFalse(lookup(".review-findings-scroll").query().isVisible(), "the margin collapses");

        type(KeyCode.F);
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(lookup(".review-findings-scroll").query().isVisible(),
                "pressing f again must give the rails back");
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

    /** The same checkout as {@link #mintScope}, as the pull request it carries. */
    private ReviewScope mintPrScope() {
        scope = registry.mint(ReviewScopeRegistry.spec(ReviewScope.Kind.PR,
                Path.of("/repo"), Optional.of(Path.of("/wt/feat")), "master", "feat",
                Optional.of(new ReviewScope.PullRequestRef(42, Optional.empty())), Optional.empty()));
        return scope;
    }

    /**
     * Shows {@code minted} with {@code outcome} recorded and <em>nothing</em>
     * in the diff column -- the state Submit's stale-diff guard exists for.
     *
     * <p>The host supplies its own body, which is what makes this
     * deterministic: {@code bodyFor} returns early and never asks the column
     * to diff anything, so {@code displayedScopeId} stays empty rather than
     * being decided by whether an async git call against a path that does
     * not exist happened to fail before the keystroke.</p>
     */
    private ReviewScope seedWithNoDiffInTheColumn(ReviewScope minted, DiffOutcome outcome) {
        host.body = Optional.of(new Region());
        interact(() -> view.showScopes(new SessionReviewScopes.Scopes(minted, Optional.empty()),
                SessionReviewScopes.Choice.LOCAL));
        interact(() -> view.diagPublishOutcome(minted.id(), outcome));
        WaitForAsyncUtils.waitForFxEvents();
        return minted;
    }

    /**
     * Fix round 2. "Ask the agent to fix it" hands the intent's open findings
     * to the bound session -- and with no session bound it hands over
     * NOTHING while looking exactly as though it worked. That is the defect
     * ruling 1 legislated against for the Explorer jump and round 1 fixed on
     * the fan-in popover; this is the same defect on the verdict bar, and it
     * is a button a reader can press all day for no effect and no word.
     */
    @Test
    void askingTheAgentWithNoBoundSessionSaysSoOnTheBar() {
        seed(finding("f1", Severity.NIT));
        host.sessionBound = false;

        clickAskAgent();

        assertTrue(host.handedOffPrompts.isEmpty(), "nothing can be sent with no session");
        assertEquals("⚠ nothing to send, or nowhere to send it", askRefusal(),
                "a click that handed nothing over must say so");
    }

    /** The other half: a hand-off that WORKED must not leave a refusal on the bar. */
    @Test
    void askingTheAgentWithASessionBoundReportsNoRefusal() {
        seed(finding("f1", Severity.NIT));
        host.sessionBound = true;

        clickAskAgent();

        assertEquals(1, host.handedOffPrompts.size(), "the findings must reach the session");
        assertEquals("", askRefusal(), "a hand-off that worked must say nothing");
    }

    /**
     * The refusal describes ONE CLICK, not a state, so anything that
     * re-renders the bar supersedes it -- otherwise a message about a click
     * the reader has long moved on from sits there looking current.
     */
    @Test
    void theAskRefusalIsClearedByTheNextBarUpdate() {
        seed(finding("f1", Severity.NIT));
        host.sessionBound = false;
        clickAskAgent();
        assertFalse(askRefusal().isBlank());

        type(KeyCode.CLOSE_BRACKET);

        assertEquals("", askRefusal(), "moving to another intent must clear it");
    }

    /**
     * Round 3, item 3. The verdict-bar fit test measures a literal it holds
     * itself, so it cannot see this string at all -- and this string is the
     * one a reader gets. Driven through a real refused submit, at the code
     * column's floor, so the two cannot drift: lengthen the production
     * message and this fails, whatever the fit test's own copy says.
     */
    @Test
    void theRealSubmitRefusalFitsAtTheCodeColumnFloor() {
        seed();
        // Narrowed here, restored in tearDown: TestFX's primary stage
        // outlives the test AND the class, and a stage left at 560px makes
        // every later test in this JVM click at coordinates its own scene
        // never laid out. (It cost this file one failure before the restore
        // went in.)
        interact(() -> view.getScene().getWindow().setWidth(RailLayout.CODE_MIN_WIDTH));
        WaitForAsyncUtils.waitForFxEvents();

        type(KeyCode.ENTER);
        interact(() -> view.getScene().getRoot().layout());
        WaitForAsyncUtils.waitForFxEvents();

        Node label = lookup(".review-verdict-submit-refusal").queryAll().stream()
                .filter(Node::isVisible)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no submit refusal is showing"));
        double got = label.getBoundsInLocal().getWidth();
        double wanted = ((Label) label).prefWidth(-1);
        assertTrue(got + 0.5 >= wanted, "'" + ((Label) label).getText() + "' got "
                + Math.round(got) + " of " + Math.round(wanted) + "px at the "
                + (int) RailLayout.CODE_MIN_WIDTH + "px floor");
        // And it is the PRODUCTION string, not one this file keeps in step by
        // hand -- which is what lets ReviewVerdictBarFitTest loop all four.
        assertEquals("⚠ " + SessionReviewView.NEEDS_VERDICT.reason(), ((Label) label).getText());
    }

    private void clickAskAgent() {
        interact(() -> lookup(".review-verdict-action").queryAll().stream()
                .map(Button.class::cast)
                .filter(button -> "Ask the agent to fix it".equals(button.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no Ask-the-agent button found"))
                .fire());
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** The text of the verdict bar's ask-refusal label; blank when it is not showing. */
    private String askRefusal() {
        return lookup(".review-verdict-ask-refusal").queryAll().stream()
                .filter(Node::isVisible)
                .map(node -> ((Label) node).getText())
                .findFirst()
                .orElse("");
    }

    /** The text of the verdict bar's submit-refusal label; blank when it is not showing. */
    private String submitRefusal() {
        return lookup(".review-verdict-submit-refusal").queryAll().stream()
                .filter(Node::isVisible)
                .map(node -> ((Label) node).getText())
                .findFirst()
                .orElse("");
    }

    /**
     * One file whose change at line 20 is surrounded by unchanged context
     * long enough to fold: {@code ReviewDiffRows.COLLAPSE_THRESHOLD} is 4, so
     * the nineteen context lines ahead of it become a single collapsed-run
     * row and n3 is not rendered at all.
     */
    private static UnifiedDiff fileWithALongUnchangedRun() {
        List<UnifiedDiff.Line> lines = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            UnifiedDiff.Line.Kind kind = i == 20
                    ? UnifiedDiff.Line.Kind.ADD
                    : UnifiedDiff.Line.Kind.CONTEXT;
            lines.add(new UnifiedDiff.Line(kind, OptionalInt.of(i), OptionalInt.of(i),
                    "int field" + i + " = " + i + ";"));
        }
        return new UnifiedDiff(List.of(new UnifiedDiff.FileDiff("src/Big.java", "M", 1, 0, false,
                false, List.of(new UnifiedDiff.Hunk("@@ -1,40 +1,40 @@", lines)))));
    }

    /** Shows the board on one scope and seeds the store with {@code findings}. */
    private void seed(ReviewAnnotation... findings) {
        // See tearDown: the stage is shared across classes, so start every
        // board from a known width rather than from whatever the last one
        // left.
        interact(() -> {
            view.getScene().getWindow().setWidth(1400);
            view.getScene().getWindow().setHeight(900);
        });
        WaitForAsyncUtils.waitForFxEvents();
        ReviewScope minted = mintScope();
        for (ReviewAnnotation finding : findings) {
            host.store.upsert(finding);
        }
        // The view renders the diff column for a worktree scope; the fake's
        // diff is what the by-file intent fallback groups.
        interact(() -> view.showScopes(new SessionReviewScopes.Scopes(minted, Optional.empty()),
                SessionReviewScopes.Choice.LOCAL));
        interact(() -> view.diagPublishOutcome(minted.id(),
                new DiffOutcome.Loaded(host.diff)));
        // showScopes already asked the diff column for a real diff of the
        // (nonexistent) worktree path, which fails asynchronously and
        // otherwise leaves the column's displayedScopeId unset -- Submit's
        // stale-diff guard (SessionReviewView#submitReview) would then
        // refuse every submit in this file. diagShowDiff publishes the
        // fake's synthetic diff as belonging to this scope, same as a real
        // diff landing would, so that guard sees a match.
        interact(() -> view.diagShowDiff(minted, host.diff));
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

    /**
     * The digest {@code src/Main.java}'s only hunk is approved under -- what
     * a verdict is keyed by now that sections may overlap. The intent id
     * ({@code auto:change:src}) keys nothing.
     */
    private static String digestOfMain() {
        return HunkDigest.of("src/Main.java", file("src/Main.java").hunks().get(0));
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
