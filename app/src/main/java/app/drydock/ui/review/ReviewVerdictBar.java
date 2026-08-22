package app.drydock.ui.review;

import app.drydock.review.ReviewIntent;
import app.drydock.review.ReviewVerdict;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
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
        /**
         * {@code unit} is the acting unit CAPTURED at the moment the reader
         * pressed the button (or the live one, for a keyboard/programmatic
         * fire with no press to capture) -- never re-read at release time.
         * A real mouse press on this button moves Scene focus off the diff
         * column before the button's own action fires (JavaFX requests focus
         * on press for a focusable control), which would otherwise flip
         * {@link SessionReviewView#settleUnit()} to {@code SECTION}
         * mid-press and settle the wrong thing on release.
         */
        void approve(ReviewIntent intent, SessionReviewView.SettleUnit unit);

        void requestChanges(ReviewIntent intent, SessionReviewView.SettleUnit unit);

        /** "Ask the agent to fix it" -- hands the intent's findings to the bound session. */
        void askAgentToFix(ReviewIntent intent);

        /** {@code u} -- undoes this intent's verdict; also "Re-review" on the stale banner. */
        void undo(ReviewIntent intent);

        /**
         * "Confirm still good" on the stale banner (spec §9.2): rewrites
         * the section's stale verdicts against the current base rather than
         * clearing them.
         */
        void confirmStillGood(ReviewIntent intent);

        /** {@code n} -- moves to the next unsettled intent. */
        void nextUnsettled();

        /** {@code ⏎} -- submits the review, once everything is settled. */
        void submit();

        /** {@code [} -- the intent before this one. */
        void previousIntent();

        /** {@code ]} -- the intent after this one. */
        void nextIntent();
    }

    /**
     * A section's stale verdict (spec §9.2): the base it was approved
     * against, and the scope's base now. Not a {@link ReviewVerdict} --
     * a section owns no verdict of its own, only what its hunks merge to.
     */
    record StaleInfo(String oldBase, String newBase) {
    }

    private final Host host;

    private final Label intentLabel = new Label();
    private final Button previousButton = new Button("‹");
    private final Button nextButton = new Button("›");
    // Text and tooltip are both rewritten by render() to name the acting
    // unit ("Approve (hunk)"); the constructor's construction argument is
    // only ever visible for the single frame before the first render().
    private final Button approveButton = new Button();
    private final Button requestChangesButton = new Button();
    private final Button askAgentButton = new Button("Ask the agent to fix it");
    private final Button undoButton = new Button("change");
    private final Label settledLabel = new Label();
    private final Label refusalLabel = new Label();
    /** The stale-verdict banner (spec §9.2): text plus its two answers. */
    private final Label staleLabel = new Label();
    private final Button confirmStillGoodButton = new Button("Confirm still good");
    private final Button reReviewButton = new Button("Re-review");
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
    private Optional<StaleInfo> stale = Optional.empty();
    /**
     * What {@code a}/{@code r}/{@code u} act on right now (spec §9.6),
     * stated on the Approve/Request-changes buttons themselves ("Approve
     * (hunk)") rather than in a separate label: a droppable label is not on
     * screen at the code column's floor, and a button whose own text
     * contradicts what it does ("Approve intent" acting on one hunk) is
     * worse than no unit statement at all.
     */
    private SessionReviewView.SettleUnit actingUnit = SessionReviewView.SettleUnit.SECTION;
    /**
     * The acting unit captured at the moment a real mouse press landed on
     * {@link #approveButton}/{@link #requestChangesButton} -- empty between
     * presses, and for a keyboard or programmatic {@code fire()} that never
     * pressed at all. A press moves Scene focus (JavaFX requests it on
     * press for any focusable control -- see {@code app.css}'s {@code
     * .review-verdict-action:focused}), which can flip {@link #actingUnit}
     * mid-press if the reader had the diff column focused; the button must
     * still act on what it READ when pressed, not what focus became by the
     * time the reader let go.
     */
    private Optional<SessionReviewView.SettleUnit> pressedUnit = Optional.empty();

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
                requestChangesButton, askAgentButton, undoButton, confirmStillGoodButton,
                reReviewButton)) {
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
        approveButton.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> pressedUnit = Optional.of(actingUnit));
        approveButton.setOnAction(e -> {
            SessionReviewView.SettleUnit unit = consumePressedUnit();
            withIntent(intent -> host.approve(intent, unit));
        });

        requestChangesButton.getStyleClass().add("review-verdict-action");
        requestChangesButton.addEventFilter(MouseEvent.MOUSE_PRESSED,
                e -> pressedUnit = Optional.of(actingUnit));
        requestChangesButton.setOnAction(e -> {
            SessionReviewView.SettleUnit unit = consumePressedUnit();
            withIntent(intent -> host.requestChanges(intent, unit));
        });

        askAgentButton.getStyleClass().add("review-verdict-action");
        askAgentButton.setTooltip(new Tooltip("Hand this intent's open findings to the bound session"));
        askAgentButton.setOnAction(e -> withIntent(host::askAgentToFix));

        undoButton.getStyleClass().add("review-verdict-action");
        undoButton.setTooltip(new Tooltip("Undo this intent's verdict (u)"));
        undoButton.setOnAction(e -> withIntent(host::undo));

        // Each also carries a class of its own: the stale banner is the one
        // place two "review-verdict-action" buttons show at once with no
        // decision-dependent branch to tell them apart by position alone.
        confirmStillGoodButton.getStyleClass().addAll("review-verdict-action", "primary",
                "review-verdict-confirm-stale");
        confirmStillGoodButton.setTooltip(
                new Tooltip("Keep this verdict, recorded against the base as it is now"));
        confirmStillGoodButton.setOnAction(e -> withIntent(host::confirmStillGood));

        reReviewButton.getStyleClass().addAll("review-verdict-action", "review-verdict-re-review");
        reReviewButton.setTooltip(new Tooltip("Clear this verdict so the section can be re-read"));
        reReviewButton.setOnAction(e -> withIntent(host::undo));

        staleLabel.getStyleClass().add("review-verdict-stale");
        staleLabel.setWrapText(true);

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
     * The unit an Approve/Request-changes press just captured, or the LIVE
     * one when nothing was captured -- a keyboard activation or a test's
     * {@code Button.fire()} never presses at all, so those correctly read
     * whatever is current right now rather than a stale snapshot from
     * whenever this button was last physically pressed.
     */
    private SessionReviewView.SettleUnit consumePressedUnit() {
        SessionReviewView.SettleUnit unit = pressedUnit.orElse(actingUnit);
        pressedUnit = Optional.empty();
        return unit;
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
     * Told whether the section now showing has a stale verdict (spec §9.2):
     * present swaps the normal actions for the banner and its two answers,
     * "Confirm still good" and "Re-review". Empty renders nothing extra --
     * {@link SectionStates.Staleness#UNKNOWN} must say nothing, never warn,
     * so this is only ever called with a value once {@code MOVED} is
     * actually established.
     */
    void showStale(Optional<StaleInfo> info) {
        this.stale = info;
        render();
    }

    /**
     * Told what {@code a}/{@code r}/{@code u} act on right now (spec §9.6),
     * so the Approve/Request-changes buttons can say so: a key whose target
     * depends on focus has to state what it is about to do, or the reader
     * is guessing.
     */
    void showActingUnit(SessionReviewView.SettleUnit unit) {
        this.actingUnit = unit;
        render();
    }

    /**
     * The word the unit reads as on a button: "Approve (section)",
     * "Request changes (file)". HUNK reads as "next unread hunk," not
     * "hunk" alone (reversed ruling): a completed gutter click opens the
     * comment composer and steals real keyboard focus into its text field,
     * which the existing {@code TextInputControl} guard then makes a/r
     * type into rather than trigger, and closing that composer clears the
     * gutter selection along with it -- so on every real reader path, HUNK
     * mode settles the section's first UNSETTLED hunk, never literally the
     * one under the pointer. The label has to promise what the code
     * actually does.
     */
    private static String unitWord(SessionReviewView.SettleUnit unit) {
        return switch (unit) {
            case HUNK -> "next unread hunk";
            case SECTION -> "section";
            case FILE -> "file";
        };
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

    /** The short form a human recognises a commit by; the sha itself if it is already short. */
    private static String shortSha(String sha) {
        return sha.length() > 7 ? sha.substring(0, 7) : sha;
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

        if (stale.isPresent()) {
            // Takes priority over the settled branch below: a stale section
            // DOES have a decision recorded, but it was given against a base
            // that has since moved, so the plain "settled, here is undo" row
            // would understate what is actually being asked of the reader.
            staleLabel.setText("⚠ approved against base " + shortSha(stale.get().oldBase())
                    + " · base is now " + shortSha(stale.get().newBase()));
            actionRow.getChildren().setAll(previousButton, nextButton, intentLabel,
                    staleLabel, confirmStillGoodButton, reReviewButton, actionSpacer, navHint);
        } else if (decision.isPresent()) {
            settledLabel.setText(decision.get().label());
            settledLabel.getStyleClass().removeIf(styleClass -> styleClass.startsWith("decision-"));
            settledLabel.getStyleClass().add("decision-" + decision.get().wireName());
            actionRow.getChildren().setAll(previousButton, nextButton, intentLabel,
                    settledLabel, undoButton, actionSpacer, navHint);
        } else {
            // Named after the acting unit, not "intent": a button whose own
            // label contradicts what it is about to do (spec §9.6) is worse
            // than no unit statement, and this is the one surface that is
            // never dropped for width, unlike a separate label would be.
            String unit = unitWord(actingUnit);
            approveButton.setText("Approve (" + unit + ")");
            requestChangesButton.setText("Request changes (" + unit + ")");
            // HUNK gets its own plain-language tooltip: "this hunk" would
            // still read as "the one under the pointer," which is exactly
            // the promise the reversed ruling says the code cannot keep.
            if (actingUnit == SessionReviewView.SettleUnit.HUNK) {
                approveButton.setTooltip(new Tooltip(
                        "Approves the next unread hunk in this section (a)"));
                requestChangesButton.setTooltip(new Tooltip(
                        "Requests changes on the next unread hunk in this section (r)"));
            } else {
                approveButton.setTooltip(new Tooltip("Approve this " + unit + " (a)"));
                requestChangesButton.setTooltip(
                        new Tooltip("Request changes on this " + unit + " (r)"));
            }
            refusalLabel.setText("⚠ a blocking finding is still open");
            refusalLabel.setVisible(blocked);
            refusalLabel.setManaged(blocked);
            approveButton.pseudoClassStateChanged(
                    javafx.css.PseudoClass.getPseudoClass("refused"), blocked);
            actionRow.getChildren().setAll(previousButton, nextButton, intentLabel,
                    approveButton, requestChangesButton, askAgentButton,
                    refusalLabel, actionSpacer, navHint);
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
