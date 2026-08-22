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

import java.util.List;
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

        /** {@code [} -- the intent before this one. */
        void previousIntent();

        /** {@code ]} -- the intent after this one. */
        void nextIntent();
    }

    private final Host host;

    private final Label intentLabel = new Label();
    private final Button previousButton = new Button("‹");
    private final Button nextButton = new Button("›");
    private final Button approveButton = new Button("Approve intent");
    private final Button requestChangesButton = new Button("Request change");
    private final Button askAgentButton = new Button("Ask the agent to fix it");
    private final Button undoButton = new Button("change");
    private final Label settledLabel = new Label();
    private final Label refusalLabel = new Label();
    private final Label progressLabel = new Label();
    /** "3 left · n jumps to the next" -- the first thing dropped when the row is tight. */
    private final Label navHint = new Label();
    private final Region actionSpacer = new Region();
    private final Region progressFill = new Region();
    private final Region progressTrack = new Region();
    private final Label hintLabel = new Label("press ? for shortcuts");
    /**
     * Why a Submit click did nothing -- distinct from {@link #refusalLabel},
     * which explains why an INTENT cannot be approved. This one covers
     * Submit itself refusing (see {@link #showSubmitRefused}): styled with
     * the same {@code review-verdict-refusal} class so the two read as the
     * same kind of message, but never both are the same {@link Label} --
     * they can be true independently (a blocking finding AND a diff that
     * has not landed) and each has its own place in the layout.
     */
    private final Label submitRefusalLabel = new Label();
    private final Button submitButton = new Button("Submit review ⏎");
    private final HBox actionRow = new HBox(10);

    private ReviewIntent intent;
    /**
     * The SECTION's decision, derived from its hunks by {@code VerdictMerge}
     * -- not a stored {@link ReviewVerdict}. Sections overlap and so cannot
     * own a verdict of their own; what the bar shows is what their hunks add
     * up to.
     */
    private Optional<ReviewVerdict.Decision> decision = Optional.empty();
    private boolean blocked;
    private int settledHunks;
    private int totalHunks;

    ReviewVerdictBar(Host host) {
        this.host = host;
        getStyleClass().add("review-verdict-bar");

        intentLabel.getStyleClass().add("review-verdict-intent");
        // The bar spans the code column, whose floor is RailLayout.CODE_MIN_WIDTH,
        // and at that width its own contents do not fit. What gives way is
        // decided here rather than by HBox's proportional shrinking, which
        // elided every action label equally: the actions are the point of the
        // bar, the title is context, so the actions keep their width and the
        // title yields. Its tooltip carries what the ellipsis takes.
        intentLabel.setMinWidth(0);
        for (Button action : List.of(previousButton, nextButton, approveButton,
                requestChangesButton, askAgentButton, undoButton)) {
            action.setMinWidth(Region.USE_PREF_SIZE);
        }
        navHint.getStyleClass().add("review-verdict-hint");
        HBox.setHgrow(actionSpacer, Priority.ALWAYS);

        previousButton.getStyleClass().addAll("review-verdict-nav", "review-verdict-previous");
        previousButton.setTooltip(new Tooltip("Previous intent ([)"));
        previousButton.setOnAction(e -> host.previousIntent());

        nextButton.getStyleClass().addAll("review-verdict-nav", "review-verdict-next");
        nextButton.setTooltip(new Tooltip("Next intent (])"));
        nextButton.setOnAction(e -> host.nextIntent());

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
        // Both classes: "review-verdict-refusal" for the shared visual
        // treatment, "review-verdict-submit-refusal" purely so a test can
        // find THIS label rather than the blocking-finding one that shares
        // the first class.
        submitRefusalLabel.getStyleClass().addAll("review-verdict-refusal", "review-verdict-submit-refusal");
        submitRefusalLabel.setVisible(false);
        submitRefusalLabel.setManaged(false);
        submitButton.getStyleClass().addAll("review-verdict-action", "primary");
        submitButton.setTooltip(new Tooltip("Submit the review (⏎)"));
        submitButton.setOnAction(e -> host.submit());

        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        HBox footer = new HBox(10, progressLabel, progressBar, hintLabel, submitRefusalLabel,
                footerSpacer, submitButton);
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
     * Updates what the bar says about the intent now being settled.
     *
     * @param currentDecision the section's decision, derived from its hunks;
     *                        empty while any of them is unread
     * @param blocked whether an open blocking finding refuses approval of this intent
     */
    void update(ReviewIntent currentIntent, Optional<ReviewVerdict.Decision> currentDecision,
                boolean blocked) {
        this.intent = currentIntent;
        this.decision = currentDecision;
        this.blocked = blocked;
        // Whatever changed enough to call update() again supersedes a
        // stale-diff refusal from an earlier click -- the reader has moved
        // on (a different scope, a diff that landed), so the message would
        // now be talking about a click that is no longer the most recent one.
        clearSubmitRefused();
        render();
    }

    /**
     * Progress is counted in distinct hunks, never in sections: sections
     * overlap, so the sum of their sizes exceeds the number of hunks and
     * "n/m sections settled" measures nothing (spec §5.6).
     */
    void showProgress(int settled, int total) {
        this.settledHunks = settled;
        this.totalHunks = total;
        render();
    }

    /**
     * Told by the destination that {@link Host#submit()} could not run and
     * why -- e.g. the selected scope's diff has not landed, or failed to
     * load. Shown beside the button with the same visual language {@link
     * #refusalLabel} uses for "approval refused," so a click that visibly
     * does nothing (AGENTS.md) still tells the reader why rather than
     * looking broken. Cleared by the next {@link #update}.
     */
    void showSubmitRefused(String reason) {
        submitRefusalLabel.setText("⚠ " + reason);
        submitRefusalLabel.setVisible(true);
        submitRefusalLabel.setManaged(true);
        submitButton.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("refused"), true);
    }

    private void clearSubmitRefused() {
        submitRefusalLabel.setVisible(false);
        submitRefusalLabel.setManaged(false);
        submitButton.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("refused"), false);
    }

    private void render() {
        if (intent == null) {
            intentLabel.setText("no intent");
            previousButton.setDisable(true);
            nextButton.setDisable(true);
            actionRow.getChildren().setAll(previousButton, nextButton, intentLabel);
            progressLabel.setText("");
            progressFill.setPrefWidth(0);
            submitButton.setDisable(true);
            return;
        }
        intentLabel.setText(intent.number() + " · " + intent.title());
        intentLabel.setTooltip(new Tooltip(intentLabel.getText()));
        previousButton.setDisable(false);
        nextButton.setDisable(false);

        navHint.setText(settledHunks >= totalHunks
                ? "all settled — ⏎ submits"
                : (totalHunks - settledHunks) + " hunks left · n jumps to the next");

        if (decision.isPresent()) {
            settledLabel.setText(decision.get().label());
            settledLabel.getStyleClass().removeIf(styleClass -> styleClass.startsWith("decision-"));
            settledLabel.getStyleClass().add("decision-" + decision.get().wireName());
            actionRow.getChildren().setAll(previousButton, nextButton, intentLabel,
                    settledLabel, undoButton, actionSpacer, navHint);
        } else {
            refusalLabel.setText("⚠ a blocking finding is still open");
            refusalLabel.setVisible(blocked);
            refusalLabel.setManaged(blocked);
            approveButton.pseudoClassStateChanged(
                    javafx.css.PseudoClass.getPseudoClass("refused"), blocked);
            actionRow.getChildren().setAll(previousButton, nextButton, intentLabel,
                    approveButton, requestChangesButton, askAgentButton, refusalLabel,
                    actionSpacer, navHint);
        }
        fitActionRow(actionRow.getWidth());

        progressLabel.setText(settledHunks + "/" + totalHunks + " hunks reviewed");
        progressTrack.setPrefWidth(120);
        progressFill.setPrefWidth(totalHunks == 0 ? 0 : 120.0 * settledHunks / totalHunks);
        submitButton.setDisable(false);
        submitButton.setText(settledHunks >= totalHunks
                ? "Submit review ⏎"
                : "Submit (" + (totalHunks - settledHunks) + " left)");
    }

    /**
     * The width the intent title is worth showing at. Below this it says
     * "11 · app…" and stops being context at all, so the hint goes instead --
     * it is the one thing on the bar stated nowhere else only in part: the
     * count repeats in the progress line and in the Submit button, and the
     * key it names lives in the shortcuts overlay.
     */
    private static final double INTENT_LABEL_MIN = 96;

    /**
     * Drops {@link #navHint} when the row cannot hold it, the actions and a
     * legible title at once.
     *
     * <p>The decision is made from the row's width against every other
     * child's preferred width -- deliberately including the hint's own slot
     * whether or not it is currently showing. Deciding from the laid-out
     * result instead would oscillate: hiding the hint frees the width that
     * says it should be shown.</p>
     */
    @Override
    protected void layoutChildren() {
        // Not a width listener: the first width the row is given arrives
        // before its buttons have been through CSS, so every preferred width
        // read then is a bare unstyled label's and the row looks roomy. A
        // layout pass is the earliest point the measurements are real, and
        // running here re-checks after a font or density change too.
        fitActionRow(actionRow.getWidth());
        super.layoutChildren();
    }

    private void fitActionRow(double width) {
        if (width <= 0) {
            return;
        }
        double needed = actionRow.getInsets().getLeft() + actionRow.getInsets().getRight()
                + INTENT_LABEL_MIN;
        int slots = 0;
        for (javafx.scene.Node child : actionRow.getChildren()) {
            if (!child.isManaged() && child != navHint) {
                continue;
            }
            slots++;
            if (child == actionSpacer || child == navHint || child == intentLabel) {
                continue;
            }
            needed += child.prefWidth(-1);
        }
        needed += actionRow.getSpacing() * Math.max(0, slots - 1);
        boolean room = width - needed >= navHint.prefWidth(-1);
        navHint.setVisible(room);
        navHint.setManaged(room);
    }

    /** Test-only: whether approval is currently being refused. */
    boolean diagBlocked() {
        return blocked;
    }
}
