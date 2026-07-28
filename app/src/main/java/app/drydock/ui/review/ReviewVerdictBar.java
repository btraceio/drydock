package app.drydock.ui.review;

import app.drydock.review.ReviewIntent;
import app.drydock.review.ReviewVerdict;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.Optional;

/**
 * The verdict bar (spec §4.6). It sits <strong>below both columns</strong>
 * and is always in the layout, so collapsing the findings margin -- or every
 * rail at once -- can never take the primary action away with it.
 *
 * <p>Approving an intent with an open blocking finding is refused inline
 * rather than disabled silently: the reader is told which condition is
 * unmet, and the way out (resolve it, or downgrade it after a discussion) is
 * in the margin beside them.</p>
 */
final class ReviewVerdictBar extends VBox {

    /** What the bar needs from its host. All calls happen on the FX thread. */
    interface Host {
        void approve(ReviewIntent intent);

        void requestChanges(ReviewIntent intent);

        /** "Ask the agent to fix it" -- hands the intent's findings to the bound session. */
        void askAgentToFix(ReviewIntent intent);

        /** {@code u} -- undoes this intent's verdict. */
        void undo(ReviewIntent intent);

        /** {@code n} -- moves to the next unsettled intent. */
        void nextUnsettled();

        /** {@code ⏎} -- submits the review, once everything is settled. */
        void submit();
    }

    private final Host host;

    private final Label intentLabel = new Label();
    private final Button approveButton = new Button("Approve intent");
    private final Button requestChangesButton = new Button("Request change");
    private final Button askAgentButton = new Button("Ask the agent to fix it");
    private final Button undoButton = new Button("change");
    private final Label settledLabel = new Label();
    private final Label refusalLabel = new Label();
    private final Label progressLabel = new Label();
    private final Region progressFill = new Region();
    private final Region progressTrack = new Region();
    private final Label hintLabel = new Label("press ? for shortcuts");
    private final Button submitButton = new Button("Submit review ⏎");
    private final HBox actionRow = new HBox(10);

    private ReviewIntent intent;
    private Optional<ReviewVerdict> verdict = Optional.empty();
    private boolean blocked;
    private int settledCount;
    private int totalCount;

    ReviewVerdictBar(Host host) {
        this.host = host;
        getStyleClass().add("review-verdict-bar");

        intentLabel.getStyleClass().add("review-verdict-intent");

        approveButton.getStyleClass().addAll("review-verdict-action", "primary");
        approveButton.setTooltip(new Tooltip("Approve this intent (a)"));
        approveButton.setOnAction(e -> withIntent(host::approve));

        requestChangesButton.getStyleClass().add("review-verdict-action");
        requestChangesButton.setTooltip(new Tooltip("Request changes on this intent (r)"));
        requestChangesButton.setOnAction(e -> withIntent(host::requestChanges));

        askAgentButton.getStyleClass().add("review-verdict-action");
        askAgentButton.setTooltip(new Tooltip("Hand this intent's open findings to the bound session"));
        askAgentButton.setOnAction(e -> withIntent(host::askAgentToFix));

        undoButton.getStyleClass().add("review-verdict-action");
        undoButton.setTooltip(new Tooltip("Undo this intent's verdict (u)"));
        undoButton.setOnAction(e -> withIntent(host::undo));

        settledLabel.getStyleClass().add("review-verdict-settled");
        refusalLabel.getStyleClass().add("review-verdict-refusal");
        refusalLabel.setVisible(false);
        refusalLabel.setManaged(false);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        progressLabel.getStyleClass().add("review-verdict-progress-label");
        actionRow.setAlignment(Pos.CENTER_LEFT);
        actionRow.getStyleClass().add("review-verdict-actions");

        progressTrack.getStyleClass().add("review-progress-track");
        progressFill.getStyleClass().add("review-progress-fill");
        HBox progressBar = new HBox(progressFill);
        progressBar.getStyleClass().add("review-progress");
        progressTrack.setMinWidth(120);
        progressTrack.setMaxWidth(120);

        hintLabel.getStyleClass().add("review-verdict-hint");
        submitButton.getStyleClass().addAll("review-verdict-action", "primary");
        submitButton.setTooltip(new Tooltip("Submit the review (⏎)"));
        submitButton.setOnAction(e -> host.submit());

        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        HBox footer = new HBox(10, progressLabel, progressBar, hintLabel, footerSpacer, submitButton);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.getStyleClass().add("review-verdict-footer");

        getChildren().setAll(actionRow, footer);
        render();
    }

    private void withIntent(java.util.function.Consumer<ReviewIntent> action) {
        if (intent != null) {
            action.accept(intent);
        }
    }

    /**
     * Updates everything the bar shows.
     *
     * @param blocked whether an open blocking finding refuses approval of this intent
     */
    void update(ReviewIntent currentIntent, Optional<ReviewVerdict> currentVerdict, boolean blocked,
                int settled, int total) {
        this.intent = currentIntent;
        this.verdict = currentVerdict;
        this.blocked = blocked;
        this.settledCount = settled;
        this.totalCount = total;
        render();
    }

    private void render() {
        if (intent == null) {
            intentLabel.setText("no intent");
            actionRow.getChildren().setAll(intentLabel);
            progressLabel.setText("");
            progressFill.setPrefWidth(0);
            submitButton.setDisable(true);
            return;
        }
        intentLabel.setText("intent " + intent.number());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label right = new Label(settledCount >= totalCount
                ? "all settled — ⏎ submits"
                : (totalCount - settledCount) + " left · n jumps to the next");
        right.getStyleClass().add("review-verdict-hint");

        if (verdict.isPresent()) {
            settledLabel.setText(verdict.get().decision().label());
            settledLabel.getStyleClass().removeIf(styleClass -> styleClass.startsWith("decision-"));
            settledLabel.getStyleClass().add("decision-" + verdict.get().decision().wireName());
            actionRow.getChildren().setAll(intentLabel, settledLabel, undoButton, spacer, right);
        } else {
            refusalLabel.setText("⚠ a blocking finding is still open");
            refusalLabel.setVisible(blocked);
            refusalLabel.setManaged(blocked);
            approveButton.pseudoClassStateChanged(
                    javafx.css.PseudoClass.getPseudoClass("refused"), blocked);
            actionRow.getChildren().setAll(intentLabel, approveButton, requestChangesButton,
                    askAgentButton, refusalLabel, spacer, right);
        }

        progressLabel.setText(settledCount + "/" + totalCount + " intents settled");
        progressTrack.setPrefWidth(120);
        progressFill.setPrefWidth(totalCount == 0 ? 0 : 120.0 * settledCount / totalCount);
        submitButton.setDisable(false);
        submitButton.setText(settledCount >= totalCount
                ? "Submit review ⏎"
                : "Submit (" + (totalCount - settledCount) + " left)");
    }

    /** Test-only: whether approval is currently being refused. */
    boolean diagBlocked() {
        return blocked;
    }
}
