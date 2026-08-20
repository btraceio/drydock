package app.drydock.ui.review;

import app.drydock.github.GitHubLineAnchor.Anchor;
import app.drydock.github.GitHubLineAnchor.Side;
import app.drydock.github.GitHubReviewRequest.Comment;
import app.drydock.github.GitHubReviewRequest.Event;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.ReviewScope;
import app.drydock.review.SubmitPlan;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The submit sheet, driven headless: the disabled-Submit rule must be LIVE
 * (re-evaluated on every event-toggle change and every keystroke, not just
 * at open), every comment and refusal the plan carries must be visible, and
 * a failed post must re-open the sheet with GitHub's own message rather than
 * silently discarding the draft.
 *
 * <p>The plan is built directly here -- no git repo, no {@code gh}, no
 * network -- exactly as {@link SubmitPlan#of} would have produced it, but
 * without needing a real diff to produce it from.</p>
 */
class ReviewSubmitSheetTest extends ApplicationTest {

    private static final ReviewScope.PullRequestRef PR = new ReviewScope.PullRequestRef(7, Optional.empty());

    private Stage stage;
    private ReviewSubmitSheet sheet;
    private final List<Object[]> submitted = new java.util.ArrayList<>();
    private final AtomicReference<Boolean> cancelled = new AtomicReference<>(false);

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        SubmitPlan plan = new SubmitPlan(Event.COMMENT, List.of(), List.of(), List.of());
        sheet = new ReviewSubmitSheet(plan, PR,
                (event, summary) -> submitted.add(new Object[] { event, summary }),
                () -> cancelled.set(true));
        Scene scene = new Scene(sheet, 640, 720);
        scene.getStylesheets().addAll(
                getClass().getResource("/app/drydock/ui/app.css").toExternalForm(),
                getClass().getResource("/app/drydock/ui/theme-dark.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
    }

    /**
     * The live rule: blank + COMMENT preselected disables Submit; typing a
     * summary enables it; switching to Approve enables it even with the
     * summary blank again; switching back to Request changes disables it
     * once more. Each step re-evaluates the rule against the CURRENT toggle,
     * not the one at open.
     */
    @Test
    void submitDisabledRuleIsLiveAcrossEventAndSummaryChanges() {
        Button submit = submitButton();
        assertTrue(submit.isDisabled(), "COMMENT preselected with a blank summary must start disabled");

        interact(() -> summaryField().setText("looks good"));
        WaitForAsyncUtils.waitForFxEvents();
        assertFalse(submit.isDisabled(), "a non-blank summary must enable Submit under COMMENT");

        fireToggle("Approve");
        assertFalse(submit.isDisabled(), "Approve never needs a summary, blank or not");

        interact(() -> summaryField().clear());
        WaitForAsyncUtils.waitForFxEvents();
        assertFalse(submit.isDisabled(), "Approve stays enabled even once the summary is cleared");

        fireToggle("Request changes");
        assertTrue(submit.isDisabled(), "Request changes with a blank summary must disable Submit again");
    }

    @Test
    void everyCommentAndRefusalIsListed() {
        Comment ranged = new Comment("src/Foo.java",
                "tighten this loop\nsecond line is not shown",
                new Anchor(48, Side.RIGHT, OptionalInt.of(40), Optional.of(Side.RIGHT)));
        Comment single = new Comment("src/Bar.java", "nit: rename",
                new Anchor(12, Side.RIGHT, OptionalInt.empty(), Optional.empty()));
        SubmitPlan.Refusal refusal = new SubmitPlan.Refusal(
                new ReviewAnnotation.Key("scope-1", "finding-3"),
                "line o5 is not in this diff");
        SubmitPlan plan = new SubmitPlan(Event.APPROVE, List.of(ranged, single), List.of(), List.of(refusal));

        rebuildSheet(plan);

        assertEquals(Set.of("src/Foo.java:L40–48", "src/Bar.java:L12"),
                Set.copyOf(queryLabels(".review-submit-comment-location")));

        assertEquals(Set.of("tighten this loop", "nit: rename"),
                Set.copyOf(queryLabels(".review-submit-comment-body")));

        assertEquals(Set.of("line o5 is not in this diff"),
                Set.copyOf(queryLabels(".review-submit-refusal")));

        // The two Set comparisons above cannot catch a row that pairs the
        // right location with the WRONG body -- a location/body swap would
        // still pass both. Pin the pairing per row: this sheet exists to
        // show the human exactly what will be published under their name.
        Map<String, String> expectedBodyByLocation = Map.of(
                "src/Foo.java:L40–48", "tighten this loop",
                "src/Bar.java:L12", "nit: rename");
        Set<Node> rows = lookup(".review-submit-comment-row").queryAll();
        assertEquals(2, rows.size());
        for (Node node : rows) {
            HBox row = (HBox) node;
            String location = ((Label) row.lookup(".review-submit-comment-location")).getText();
            String body = ((Label) row.lookup(".review-submit-comment-body")).getText();
            assertEquals(expectedBodyByLocation.get(location), body,
                    "row for " + location + " must carry its own body, not another row's");
        }
    }

    /**
     * Finding 7 of the second review pass: {@code startLine} and {@code
     * line} come from two different namespaces on a cross-side anchor (the
     * old file's line count and the new file's), so printing them bare --
     * {@code src/Foo.java:L120–48} -- reads as backwards or nonsense on the
     * last screen before an irreversible post. Each end must name its own
     * side; same-side anchors (proven by {@link #everyCommentAndRefusalIsListed})
     * must keep their existing bare form.
     */
    @Test
    void aCrossSideAnchorLabelsEachEndWithItsOwnSide() {
        Comment crossSide = new Comment("src/Foo.java", "selecting across the deletion",
                new Anchor(48, Side.RIGHT, OptionalInt.of(120), Optional.of(Side.LEFT)));
        SubmitPlan plan = new SubmitPlan(Event.APPROVE, List.of(crossSide), List.of(), List.of());

        rebuildSheet(plan);

        assertEquals(List.of("src/Foo.java:L120(-)–L48(+)"), queryLabels(".review-submit-comment-location"));
    }

    /** An empty refusals list must not render the block at all -- not even empty. */
    @Test
    void anEmptyRefusalsListRendersNoBlock() {
        assertTrue(lookup(".review-submit-refusals").tryQuery().isEmpty());
    }

    @Test
    void showPostingDisablesFooterAndShowErrorReopensWithTheMessage() {
        Button submit = submitButton();
        Button cancel = cancelButton();
        interact(() -> sheet.showPosting());
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(submit.isDisabled(), "showPosting must disable Submit");
        assertTrue(cancel.isDisabled(), "showPosting must disable the whole footer, including Cancel");
        assertTrue(progressRow().isVisible(), "showPosting must show the progress row");

        interact(() -> sheet.showError("422: body is required"));
        WaitForAsyncUtils.waitForFxEvents();

        assertFalse(cancel.isDisabled(), "showError must re-enable the footer");
        // The fixture sheet opens with COMMENT preselected and a blank
        // summary -- SubmitPlan.needsSummary(COMMENT) is true, so the live
        // rule that disabled Submit before showPosting() must still hold
        // after showError(): an error must never hand the human a clickable
        // Submit that GitHub is guaranteed to 422 again.
        assertTrue(submit.isDisabled(),
                "showError must not force-enable Submit; the live disabled rule still applies");
        assertFalse(progressRow().isVisible(), "showError must hide the progress row -- no path may strand it");
        Label error = (Label) lookup(".review-error-callout").queryAll().stream()
                .filter(node -> node.isVisible())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no visible error label after showError"));
        assertTrue(error.getText().contains("422: body is required"),
                "the error label must show GitHub's own message: " + error.getText());
        assertTrue(sheet.isVisible(), "the sheet must stay open on failure so the draft survives");
    }

    /**
     * {@code showUnavailable} is sticky: once the open-time {@code gh} check
     * fails, Submit must stay disabled even if the human goes on to type a
     * summary that would otherwise satisfy the live rule.
     */
    @Test
    void showUnavailableKeepsSubmitDisabledEvenAfterTypingASummary() {
        Button submit = submitButton();
        interact(() -> sheet.showUnavailable("gh is not installed"));
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(submit.isDisabled(), "showUnavailable must leave Submit disabled");

        interact(() -> summaryField().setText("looks good"));
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(submit.isDisabled(),
                "the unavailable flag is sticky -- a summary must not override it");
    }

    @Test
    void cancelInvokesOnCancelAndNeverOnSubmit() {
        interact(() -> cancelButton().fire());
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(cancelled.get(), "Cancel must invoke onCancel");
        assertTrue(submitted.isEmpty(), "Cancel must never invoke onSubmit");
    }

    /**
     * Every other test in this class drives the toggle and the buttons
     * through {@code fire()}, which invokes the {@code onAction} handler
     * directly and bypasses real JavaFX event delivery entirely -- exactly
     * the gap that let a total click regression through two review rounds on
     * this branch (a detached node, or a node whose {@code setOnAction} was
     * dropped, would still pass every {@code fire()}-based assertion here).
     * This test drives the SAME sequence a human does: a real click focuses
     * the summary field, real keystrokes fill it in, a real click on
     * "Approve" changes the toggle, and a real click on "Submit review"
     * fires it -- proving the whole chain is actually wired into the scene
     * graph, not just that the lambda works when called directly.
     */
    @Test
    void aRealPointerClickOnSubmitInvokesOnSubmitWithTheChosenEventAndSummary() {
        Comment comment = new Comment("src/Foo.java", "existing finding",
                new Anchor(12, Side.RIGHT, OptionalInt.empty(), Optional.empty()));
        SubmitPlan plan = new SubmitPlan(Event.COMMENT, List.of(comment), List.of(), List.of());
        rebuildSheet(plan);

        clickOn(".review-composer-input");
        write("Looks good, one nit addressed.");

        clickOn("Approve");
        WaitForAsyncUtils.waitForFxEvents();

        clickOn("Submit review");
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(1, submitted.size(), "a real click on Submit must invoke onSubmit exactly once");
        assertEquals(Event.APPROVE, submitted.get(0)[0],
                "the event handed to onSubmit must be the one the real click selected");
        assertEquals("Looks good, one nit addressed.", submitted.get(0)[1],
                "the summary handed to onSubmit must be what was actually typed");
    }

    // ---- helpers ------------------------------------------------------------

    private void rebuildSheet(SubmitPlan plan) {
        interact(() -> {
            sheet = new ReviewSubmitSheet(plan, PR,
                    (event, summary) -> submitted.add(new Object[] { event, summary }),
                    () -> cancelled.set(true));
            Scene scene = new Scene(sheet, 640, 720);
            scene.getStylesheets().addAll(
                    getClass().getResource("/app/drydock/ui/app.css").toExternalForm(),
                    getClass().getResource("/app/drydock/ui/theme-dark.css").toExternalForm());
            stage.setScene(scene);
        });
        WaitForAsyncUtils.waitForFxEvents();
    }

    private Button submitButton() {
        return (Button) lookup(".review-submit-footer .button").queryAll().stream()
                .filter(node -> ((Button) node).getText().equals("Submit review"))
                .findFirst().orElseThrow();
    }

    private Button cancelButton() {
        return (Button) lookup(".review-submit-footer .button").queryAll().stream()
                .filter(node -> ((Button) node).getText().equals("Cancel"))
                .findFirst().orElseThrow();
    }

    private TextArea summaryField() {
        return (TextArea) lookup(".review-composer-input").query();
    }

    private Node progressRow() {
        return lookup(".review-submit-progress").query();
    }

    private void fireToggle(String text) {
        interact(() -> lookup(".review-submit-event-button").queryAll().stream()
                .map(ToggleButton.class::cast)
                .filter(button -> button.getText().equals(text))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no toggle labelled '" + text + "'"))
                .fire());
        WaitForAsyncUtils.waitForFxEvents();
    }

    private List<String> queryLabels(String selector) {
        return lookup(selector).queryAll().stream()
                .map(node -> ((Label) node).getText())
                .toList();
    }
}
