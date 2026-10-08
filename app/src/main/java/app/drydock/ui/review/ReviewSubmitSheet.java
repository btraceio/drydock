package app.drydock.ui.review;

import app.drydock.github.GitHubLineAnchor.Anchor;
import app.drydock.github.GitHubLineAnchor.Side;
import app.drydock.github.GitHubReviewRequest.Comment;
import app.drydock.github.GitHubReviewRequest.Event;
import app.drydock.review.ReviewAnnotation;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * What the review approves without having verified it: steps approved
     * with an override, hunks approved in the hunk diff, and agent findings
     * the human never confirmed or dismissed.
     */
    public record Unverified(int stepOverrides, int hunkOverrides, long untriagedFindings) {

        boolean any() {
            return stepOverrides > 0 || hunkOverrides > 0 || untriagedFindings > 0;
        }

        /** The sheet's one-line summary; only the non-zero parts appear. */
        String describe() {
            List<String> parts = new ArrayList<>();
            if (stepOverrides > 0) {
                parts.add(stepOverrides + (stepOverrides == 1 ? " step" : " steps")
                        + " approved without passing checks");
            }
            if (hunkOverrides > 0) {
                parts.add(hunkOverrides + (hunkOverrides == 1 ? " hunk" : " hunks")
                        + " approved in the hunk diff");
            }
            if (untriagedFindings > 0) {
                parts.add(untriagedFindings + (untriagedFindings == 1 ? " agent finding" : " agent findings")
                        + " not reviewed");
            }
            return "Not verified: " + String.join(" · ", parts);
        }
    }

    private final SubmitPlan plan;
    private final Unverified unverified;
    /**
     * The human's rewordings for this submission, keyed by the finding's
     * annotation key ({@code plan.posting()} aligns with the comments, in
     * order; body notes carry their key). Empty until an edit commits, and
     * visible in the row as an "edited" chip -- what posts may differ from
     * what the board shows, and the difference must never be silent.
     */
    private final Map<ReviewAnnotation.Key, String> editedBodies = new LinkedHashMap<>();
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
    private final Label verificationNote = new Label();

    private final Button cancelButton = new Button("Cancel");
    private final Button submitButton = new Button("Submit review");
    private final HBox footer = new HBox(10);

    /** Set by {@link #showUnavailable}; keeps Submit disabled regardless of the live rule below. */
    private boolean unavailable;

    public ReviewSubmitSheet(SubmitPlan plan, ReviewScope.PullRequestRef pr,
                              BiConsumer<Event, String> onSubmit, Runnable onCancel) {
        this(plan, pr, new Unverified(0, 0, 0), onSubmit, onCancel);
    }

    public ReviewSubmitSheet(SubmitPlan plan, ReviewScope.PullRequestRef pr, Unverified unverified,
                              BiConsumer<Event, String> onSubmit, Runnable onCancel) {
        this.plan = plan;
        this.unverified = unverified;
        this.onSubmit = onSubmit;
        this.onCancel = onCancel;

        getStyleClass().addAll("modal", "review-submit-sheet");
        setMaxWidth(560);
        setMaxHeight(680);

        Label title = new Label("Submit review on #" + pr.number());
        title.getStyleClass().add("modal-title");

        verificationNote.getStyleClass().add("review-submit-verification");
        verificationNote.setWrapText(true);
        hide(verificationNote);

        VBox content = new VBox(14, buildEventPicker(), buildSummaryField(), verificationNote,
                buildCommentsBlock());
        buildBodyNotesBlock().ifPresent(content.getChildren()::add);
        buildUnverifiedBlock().ifPresent(content.getChildren()::add);
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
        // Enter submits from anywhere EXCEPT the summary itself (a TextArea
        // consumes plain Enter as a newline, so the two never collide) --
        // the last screen before an irreversible post should not need the
        // mouse.
        submitButton.setDefaultButton(true);
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
        summaryField.setPromptText("Leave a summary comment… ⌘⏎ submits");
        summaryField.setWrapText(true);
        summaryField.setPrefRowCount(4);
        // Enter inside the composer is a newline; the submit gesture from
        // inside it is ⌘⏎ -- the same contract the step panel's free-text
        // answer uses, so every multi-line box on the board submits the
        // same way.
        summaryField.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ENTER && event.isShortcutDown()) {
                submit();
                event.consume();
            }
        });
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

    /** The edits the sheet has collected; the caller folds them into the plan before posting. */
    public Map<ReviewAnnotation.Key, String> editedBodies() {
        return Map.copyOf(editedBodies);
    }

    private String bodyFor(ReviewAnnotation.Key key, String original) {
        return editedBodies.getOrDefault(key, original);
    }

    /**
     * Every comment the plan would post: {@code file:L40–48} (or a single
     * line) plus its first line -- and, when the human reworded it for the
     * post, the edited text and an "edited" chip, because the posted body
     * now differs from the finding the board still shows.
     */
    private Region buildCommentsBlock() {
        Label header = new Label("Inline comments (" + plan.comments().size() + ")");
        header.getStyleClass().addAll("modal-hint", "review-submit-route-inline");

        VBox rows = new VBox(6);
        rows.getStyleClass().add("review-submit-comments");
        for (int i = 0; i < plan.comments().size(); i++) {
            rows.getChildren().add(commentRow(plan.comments().get(i), plan.posting().get(i)));
        }

        VBox block = new VBox(6, header, rows);
        block.getStyleClass().add("review-submit-comments-block");
        return block;
    }

    /** Comments on lines GitHub cannot anchor: they travel in the review body, so say so. */
    private Optional<Region> buildBodyNotesBlock() {
        if (plan.bodyNotes().isEmpty()) {
            return Optional.empty();
        }
        Label header = new Label("In the review body (" + plan.bodyNotes().size() + ")");
        header.getStyleClass().addAll("modal-hint", "review-submit-route-body");
        VBox rows = new VBox(6);
        for (SubmitPlan.BodyNote note : plan.bodyNotes()) {
            rows.getChildren().add(editableRow(note.location(), note.key(), note.body(),
                    "review-submit-note-location"));
        }
        VBox block = new VBox(6, header, rows);
        block.getStyleClass().add("review-submit-body-notes");
        return Optional.of(block);
    }

    private Optional<Region> buildUnverifiedBlock() {
        if (!unverified.any()) {
            return Optional.empty();
        }
        Label line = new Label(unverified.describe());
        line.getStyleClass().addAll("modal-hint", "review-submit-unverified");
        line.setWrapText(true);
        return Optional.of(new VBox(line));
    }

    /** One posting row: where, the text that will post, and the Edit that rewords it. */
    private Region commentRow(Comment comment, ReviewAnnotation.Key key) {
        return editableRow(locationOf(comment), key, comment.body(), "review-submit-comment-location");
    }

    /**
     * A row whose text can be reworded for the post. The edit changes what
     * is POSTED, not the stored finding -- the board keeps the original,
     * so the row carries an "edited" chip to keep the difference from being
     * silent. The editor is a multi-line composer: ⌘⏎ saves it, Esc drops
     * the draft, the same gestures the summary uses.
     */
    private Region editableRow(String locationText, ReviewAnnotation.Key key, String originalBody,
                               String locationStyleClass) {
        HBox row = new HBox(8);
        row.getStyleClass().add("review-submit-comment-row");
        Runnable[] holder = new Runnable[1];
        Runnable refresh = () -> {
            Label location = new Label(locationText);
            location.getStyleClass().add(locationStyleClass);
            Label body = new Label(firstLine(bodyFor(key, originalBody)));
            body.getStyleClass().add("review-submit-comment-body");
            HBox.setHgrow(body, Priority.ALWAYS);
            body.setMaxWidth(Double.MAX_VALUE);
            row.getChildren().setAll(location, body);
            if (editedBodies.containsKey(key)) {
                Label chip = new Label("edited");
                chip.getStyleClass().add("review-submit-edited-chip");
                row.getChildren().add(chip);
            }
            Button edit = new Button("Edit");
            edit.getStyleClass().add("review-submit-edit-button");
            edit.setOnAction(e -> row.getChildren().setAll(editor(row, key, originalBody, holder[0])));
            row.getChildren().add(edit);
        };
        holder[0] = refresh;
        refresh.run();
        return row;
    }

    /** The row's edit state: a multi-line draft with ⌘⏎ to save and Esc to drop. */
    private Region editor(HBox row, ReviewAnnotation.Key key, String originalBody, Runnable refresh) {
        TextArea draft = new TextArea(bodyFor(key, originalBody));
        draft.getStyleClass().add("review-composer-input");
        draft.setWrapText(true);
        draft.setPrefRowCount(3);
        Label hint = new Label("⌘⏎ saves · Esc cancels");
        hint.getStyleClass().add("modal-hint");
        draft.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            switch (event.getCode()) {
                case ENTER -> {
                    if (event.isShortcutDown()) {
                        String text = draft.getText() == null ? "" : draft.getText().strip();
                        if (!text.isEmpty()) {
                            editedBodies.put(key, text);
                        }
                        refresh.run();
                        event.consume();
                    }
                }
                case ESCAPE -> {
                    refresh.run();
                    event.consume();
                }
                default -> { }
            }
        });
        VBox editor = new VBox(4, draft, hint);
        editor.getStyleClass().add("review-submit-editor");
        // The draft takes the focus the click that opened it came from --
        // typing is the whole point of the state.
        javafx.application.Platform.runLater(draft::requestFocus);
        return editor;
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
     * What the fresh-head verification found, shown above the routes: the
     * human must know whether the anchors were checked against the diff as
     * reviewed or against the pull request's current head before deciding
     * to post.
     */
    public void showVerificationNote(String note) {
        verificationNote.setText(note);
        show(verificationNote);
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
