package app.drydock.ui.review;

import app.drydock.github.GitHubLineAnchor.Anchor;
import app.drydock.github.GitHubLineAnchor.Side;
import app.drydock.github.GitHubReviewRequest.Comment;
import app.drydock.github.GitHubReviewRequest.Event;
import app.drydock.review.ReviewScope;
import app.drydock.review.SubmitPlan;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * The submit sheet: the last thing a human sees before a review posts to a
 * public, hard-to-undo pull request. Shown by {@code MainWorkspace} inside
 * {@code ModalLayer}, so it must be {@code public} unlike its package-private
 * siblings here, which only {@link SessionReviewView} ever builds.
 *
 * <p>Everything the sheet renders comes from a {@link SubmitPlan} handed in
 * at construction -- comments already resolved, refusals already explained.
 * The sheet's own job is narrower: pick the review event, collect the
 * summary GitHub requires for two of the three, and never let a click reach
 * the network with a body GitHub is guaranteed to reject.</p>
 */
public final class ReviewSubmitSheet extends VBox {

    private final SubmitPlan plan;
    private final BiConsumer<Event, String> onSubmit;
    private final Runnable onCancel;

    private final ToggleGroup eventGroup = new ToggleGroup();
    private final ToggleButton approveButton = new ToggleButton("Approve");
    private final ToggleButton commentButton = new ToggleButton("Comment");
    private final ToggleButton requestChangesButton = new ToggleButton("Request changes");
    private final TextArea summaryField = new TextArea();

    private final ProgressIndicator progress = new ProgressIndicator();
    private final Label progressLabel = new Label("Posting review…");
    private final HBox progressRow = new HBox(8, progress, progressLabel);

    private final Label errorLabel = new Label();
    private final Label unavailableLabel = new Label();

    private final Button cancelButton = new Button("Cancel");
    private final Button submitButton = new Button("Submit review");
    private final HBox footer = new HBox(10);

    /** Set by {@link #showUnavailable}; keeps Submit disabled regardless of the live rule below. */
    private boolean unavailable;

    public ReviewSubmitSheet(SubmitPlan plan, ReviewScope.PullRequestRef pr,
                              BiConsumer<Event, String> onSubmit, Runnable onCancel) {
        this.plan = plan;
        this.onSubmit = onSubmit;
        this.onCancel = onCancel;

        getStyleClass().addAll("modal", "review-submit-sheet");
        setMaxWidth(560);
        setMaxHeight(680);

        Label title = new Label("Submit review on #" + pr.number());
        title.getStyleClass().add("modal-title");

        VBox content = new VBox(14, buildEventPicker(), buildSummaryField(), buildCommentsBlock());
        buildRefusalsBlock().ifPresent(content.getChildren()::add);

        ScrollPane scroll = new ScrollPane(content);
        scroll.getStyleClass().add("review-submit-scroll");
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        progress.setPrefSize(14, 14);
        progressLabel.getStyleClass().add("modal-hint");
        progressRow.setAlignment(Pos.CENTER_LEFT);
        progressRow.getStyleClass().add("review-submit-progress");
        setProgressVisible(false);

        errorLabel.getStyleClass().add("review-error-callout");
        errorLabel.setWrapText(true);
        hide(errorLabel);

        unavailableLabel.getStyleClass().add("review-error-callout");
        unavailableLabel.setWrapText(true);
        hide(unavailableLabel);

        cancelButton.getStyleClass().add("review-verdict-action");
        cancelButton.setOnAction(e -> onCancel.run());
        submitButton.getStyleClass().addAll("review-verdict-action", "primary");
        submitButton.setOnAction(e -> submit());
        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        footer.getChildren().setAll(footerSpacer, cancelButton, submitButton);
        footer.getStyleClass().add("review-submit-footer");

        getChildren().setAll(title, scroll, progressRow, unavailableLabel, errorLabel, footer);

        selectPreselected();
        updateSubmitEnabled();
    }

    // ---- event picker ---------------------------------------------------

    private Region buildEventPicker() {
        approveButton.setToggleGroup(eventGroup);
        commentButton.setToggleGroup(eventGroup);
        requestChangesButton.setToggleGroup(eventGroup);
        for (ToggleButton button : new ToggleButton[] { approveButton, commentButton, requestChangesButton }) {
            button.getStyleClass().add("review-submit-event-button");
        }
        // A ToggleGroup deselects its toggle on a second click of the same
        // button by default -- exactly the state this sheet must never be
        // in, since currentEvent() has to always resolve to one of the three.
        eventGroup.selectedToggleProperty().addListener((obs, previous, now) -> {
            if (now == null) {
                previous.setSelected(true);
            } else {
                updateSubmitEnabled();
            }
        });

        HBox row = new HBox(8, approveButton, commentButton, requestChangesButton);
        row.getStyleClass().add("review-submit-event-row");
        return row;
    }

    private void selectPreselected() {
        switch (plan.preselected()) {
            case APPROVE -> approveButton.setSelected(true);
            case COMMENT -> commentButton.setSelected(true);
            case REQUEST_CHANGES -> requestChangesButton.setSelected(true);
        }
    }

    private Event currentEvent() {
        if (approveButton.isSelected()) {
            return Event.APPROVE;
        }
        if (requestChangesButton.isSelected()) {
            return Event.REQUEST_CHANGES;
        }
        return Event.COMMENT;
    }

    // ---- summary ----------------------------------------------------------

    private Region buildSummaryField() {
        summaryField.getStyleClass().add("review-composer-input");
        summaryField.setPromptText("Leave a summary comment…");
        summaryField.setWrapText(true);
        summaryField.setPrefRowCount(4);
        // The disabled rule is live: every keystroke re-evaluates it against
        // whichever event is selected right now, not just the one at open.
        summaryField.textProperty().addListener((obs, old, text) -> updateSubmitEnabled());
        return summaryField;
    }

    /**
     * GitHub 422s {@code COMMENT}/{@code REQUEST_CHANGES} with an empty body.
     * A disabled button beats that round trip, and the rule stays live across
     * both the event toggle and every keystroke in the summary.
     */
    private void updateSubmitEnabled() {
        if (unavailable) {
            submitButton.setDisable(true);
            return;
        }
        boolean blankSummary = summaryField.getText() == null || summaryField.getText().isBlank();
        submitButton.setDisable(SubmitPlan.needsSummary(currentEvent()) && blankSummary);
    }

    // ---- what will (and will not) post -------------------------------------

    /** Every comment the plan would post: {@code file:L40–48} (or a single line) plus its first line. */
    private Region buildCommentsBlock() {
        Label header = new Label("Posting " + plan.comments().size()
                + (plan.comments().size() == 1 ? " comment" : " comments"));
        header.getStyleClass().add("modal-hint");

        VBox rows = new VBox(6);
        rows.getStyleClass().add("review-submit-comments");
        for (Comment comment : plan.comments()) {
            rows.getChildren().add(commentRow(comment));
        }

        VBox block = new VBox(6, header, rows);
        block.getStyleClass().add("review-submit-comments-block");
        return block;
    }

    private static Region commentRow(Comment comment) {
        Label location = new Label(locationOf(comment));
        location.getStyleClass().add("review-submit-comment-location");
        Label body = new Label(firstLine(comment.body()));
        body.getStyleClass().add("review-submit-comment-body");
        HBox row = new HBox(8, location, body);
        row.getStyleClass().add("review-submit-comment-row");
        return row;
    }

    /**
     * {@code file:L40–48} for a same-side range, {@code file:L40} for a
     * single line. A cross-side range labels each end -- {@code
     * file:L120(-)–L48(+)} -- because {@code startLine} and {@code line} are
     * numbers from two different namespaces (the old file and the new file)
     * and, printed bare, read as backwards or nonsense on the last screen
     * before an irreversible post.
     */
    private static String locationOf(Comment comment) {
        Anchor anchor = comment.anchor();
        if (anchor.startLine().isEmpty()) {
            return comment.path() + ":L" + anchor.line();
        }
        Side startSide = anchor.startSide().orElseThrow();
        Side endSide = anchor.side();
        if (startSide != endSide) {
            return comment.path() + ":L" + anchor.startLine().getAsInt() + sideMark(startSide)
                    + "–L" + anchor.line() + sideMark(endSide);
        }
        return comment.path() + ":L" + anchor.startLine().getAsInt() + "–" + anchor.line();
    }

    private static String sideMark(Side side) {
        return side == Side.LEFT ? "(-)" : "(+)";
    }

    private static String firstLine(String body) {
        int newline = body.indexOf('\n');
        return newline < 0 ? body : body.substring(0, newline);
    }

    /**
     * What will NOT post, and why -- present only when {@link SubmitPlan#refusals()}
     * is non-empty. Hiding the block outright rather than rendering it empty:
     * an empty "not posting" heading would read as a claim that something was
     * refused when nothing was.
     */
    private Optional<Region> buildRefusalsBlock() {
        if (plan.refusals().isEmpty()) {
            return Optional.empty();
        }
        Label header = new Label("Not posting (" + plan.refusals().size() + ")");
        header.getStyleClass().add("modal-hint");

        VBox rows = new VBox(4);
        for (SubmitPlan.Refusal refusal : plan.refusals()) {
            Label reason = new Label(refusal.reason());
            reason.getStyleClass().add("review-submit-refusal");
            reason.setWrapText(true);
            rows.getChildren().add(reason);
        }

        VBox block = new VBox(6, header, rows);
        block.getStyleClass().add("review-submit-refusals");
        return Optional.of(block);
    }

    // ---- submit / progress / failure ---------------------------------------

    private void submit() {
        showPosting();
        onSubmit.accept(currentEvent(), summaryField.getText() == null ? "" : summaryField.getText());
    }

    /**
     * Disables the footer and shows progress -- the click must visibly do
     * something before the result arrives (AGENTS.md). The caller drives what
     * happens next: {@link #showError} on failure, or the sheet is closed on
     * success.
     */
    public void showPosting() {
        hide(errorLabel);
        setProgressVisible(true);
        footer.setDisable(true);
    }

    /**
     * GitHub's own message, shown inline. The footer re-enables and the sheet
     * stays open so the summary and event choice survive -- nothing here is
     * worth re-typing after a failed post.
     */
    public void showError(String message) {
        setProgressVisible(false);
        footer.setDisable(false);
        errorLabel.setText("⚠ " + message);
        show(errorLabel);
        // The footer's own disable just lifted; the submit button's private
        // disable state -- the live rule -- was never touched by showPosting,
        // so nothing further is owed to it here except making it visible again.
    }

    /**
     * The open-time {@code gh} check failed. Submit stays disabled regardless
     * of what the human types next -- there is nowhere for the review to go.
     */
    public void showUnavailable(String reason) {
        unavailable = true;
        unavailableLabel.setText("⚠ " + reason);
        show(unavailableLabel);
        updateSubmitEnabled();
    }

    private void setProgressVisible(boolean visible) {
        progressRow.setVisible(visible);
        progressRow.setManaged(visible);
    }

    private static void hide(Label label) {
        label.setVisible(false);
        label.setManaged(false);
    }

    private static void show(Label label) {
        label.setVisible(true);
        label.setManaged(true);
    }
}
