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

        /**
         * "Ask the agent to fix it" -- hands the intent's open findings to
         * the bound session. False when nothing was handed over: there is no
         * session to hand them to, or the intent has no open finding to send.
         *
         * <p>A boolean for the same reason {@code openInExplorer} and {@code
         * SessionReviewView.Host#askAgentToFix} are: this button can do
         * NOTHING while looking exactly as though it worked, and a control
         * that reports nothing when it did nothing is the defect family this
         * branch has now spent three rounds on.</p>
         */
        boolean askAgentToFix(ReviewIntent intent);

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
    /**
     * Why approval is refused. Shortened to its glyph by {@link
     * #fitActionRow} when the row cannot hold the sentence; the tooltip
     * carries the whole thing either way.
     */
    private static final String BLOCKING_REFUSAL = "⚠ a blocking finding is still open";

    /**
     * Why "Ask the agent to fix it" handed nothing over. Both causes, because
     * the boolean it acts on cannot tell them apart and naming the wrong one
     * is worse than naming the pair; short, because the footer at the bar's
     * real width has room for about forty characters. A constant so a test
     * cannot hold a copy that drifts -- which is exactly how three submit
     * refusals came to be measured at a width production never gives them.
     */
    static final String NOTHING_TO_SEND = "no open findings, or no session";

    static final String NOTHING_TO_SEND_DETAIL =
            "This intent has no open finding to hand over, or this scope has no bound session to "
                    + "hand it to. Open the scope's session first.";

    private final Label refusalLabel = new Label();
    /**
     * Why an "Ask the agent to fix it" click handed nothing over -- a THIRD
     * refusal, and a third Label, for the reason {@link #submitRefusalLabel}
     * documents: the three are independently true (an intent can have a
     * blocking finding open, no session to hand it to, AND a diff that has
     * not landed). This one sits in the FOOTER rather than beside its own
     * button -- see {@link #showAskRefused} for the measurement that put it
     * there.
     *
     * <p>Transient, unlike {@link #refusalLabel}: it describes one click,
     * not a state, so {@link #update} clears it the moment anything the bar
     * renders from has changed.</p>
     */
    private final Label askRefusalLabel = new Label();
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
    /** Fields, not locals: {@link #fitFooter} has to measure this row after construction. */
    private final HBox footer = new HBox(10);
    private final Region footerSpacer = new Region();

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
        askAgentButton.setOnAction(e -> withIntent(intent -> {
            if (host.askAgentToFix(intent)) {
                clearAskRefused();
                return;
            }
            // Both causes, because the bar cannot tell them apart from a
            // boolean and must not guess at one: naming the wrong one is
            // worse than naming the pair. Short because the footer at the
            // code column's floor has room for about forty characters and
            // not one more -- see showAskRefused -- so the sentence lives in
            // the tooltip, the way intentLabel's does.
            showAskRefused(NOTHING_TO_SEND, NOTHING_TO_SEND_DETAIL);
        }));
        // Both classes, exactly as submitRefusalLabel does: the shared one
        // for the visual treatment, its own so a test can find THIS label
        // rather than the blocking-finding one beside it.
        askRefusalLabel.getStyleClass().addAll("review-verdict-refusal", "review-verdict-ask-refusal");
        askRefusalLabel.setVisible(false);
        askRefusalLabel.setManaged(false);

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
        // Never squeezed: the intent TITLE is the one thing in this row
        // allowed to give way (see intentLabel's own minWidth(0)), and
        // without this the row took its last three pixels out of the
        // refusal instead -- eliding even the bare glyph, which is the one
        // character that cannot be spared.
        refusalLabel.setMinWidth(Region.USE_PREF_SIZE);
        refusalLabel.setTooltip(new Tooltip("An open finding of this intent blocks approval. "
                + "Resolve it, or lower its severity, in the findings margin."));
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

        // Both classes, the same split submitRefusalLabel uses: the shared
        // one for the visual treatment, its own so a test can find THIS
        // label rather than navHint, which shares the first. A test that
        // could not tell them apart is why fitFooter shipped ungated on
        // width -- theHintIsBackAsSoonAsThereIsRoomForIt was matching
        // navHint's text and never looked at this label at all.
        hintLabel.getStyleClass().addAll("review-verdict-hint", "review-verdict-shortcut-hint");
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

        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        footer.getChildren().setAll(progressLabel, progressBar, hintLabel, askRefusalLabel,
                submitRefusalLabel, footerSpacer, submitButton);
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
        // Same reasoning, one row up: whatever changed enough to call
        // update() supersedes a hand-off refusal from an earlier click.
        clearAskRefused();
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
            // PATH mode: literally the row on screen, never a hunt through
            // a section -- distinct wording from HUNK on purpose, since HUNK
            // promises "the next unread one," a promise this case does not
            // make or need.
            case PATH_STEP -> "hunk";
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
        showSubmitRefused(reason, reason);
    }

    /**
     * As above, with a longer explanation on hover -- the same split {@link
     * #showAskRefused} makes, and for the same measured reason: the footer
     * has about 290px for a refusal at the code column's floor, and a
     * sentence longer than that is elided mid-word. {@code reason} is what
     * has to fit; {@code detail} is what the ellipsis would have taken.
     */
    void showSubmitRefused(String reason, String detail) {
        submitRefusalLabel.setText("⚠ " + reason);
        submitRefusalLabel.setTooltip(new Tooltip(detail));
        submitRefusalLabel.setVisible(true);
        submitRefusalLabel.setManaged(true);
        submitButton.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("refused"), true);
        // The two footer refusals are MUTUALLY EXCLUSIVE. Raised together --
        // submit refuses, the reader then clicks "Ask the agent to fix it" on
        // the same intent, and neither path calls update() -- they and the
        // Submit button share one row's width three ways, and the primary
        // action reads "Sub…". They also describe one sequence of clicks, so
        // the newer one is the one the reader is owed.
        clearAskRefused();
        fitFooter();
    }

    /** The short form a human recognises a commit by; the sha itself if it is already short. */
    private static String shortSha(String sha) {
        return sha.length() > 7 ? sha.substring(0, 7) : sha;
    }

    /**
     * Says why an "Ask the agent to fix it" click handed nothing over, in
     * the same visual language {@link #refusalLabel} uses for a refused
     * approval and {@link #showSubmitRefused} for a refused submit. Cleared
     * by the next {@link #update}.
     *
     * <p><strong>In the footer, not beside its own button</strong>, and that
     * was measured rather than chosen. At {@code RailLayout.CODE_MIN_WIDTH}
     * -- the width the bar has to be operable at, since with every rail
     * collapsed it is the only surface left -- the action row has about 25px
     * of slack once its four actions have taken their preferred widths, and
     * this label was laid out at 25 of the 319px it asked for. A refusal
     * elided to an unreadable sliver is the same defect as the silence it
     * replaces. The footer is the row immediately below, already the home of
     * {@link #submitRefusalLabel}, and {@link #askAgentButton} carries the
     * {@code :refused} pseudo-class meanwhile, so the two read as one
     * event.</p>
     */
    private void showAskRefused(String reason, String detail) {
        askRefusalLabel.setText("⚠ " + reason);
        askRefusalLabel.setTooltip(new Tooltip(detail));
        askRefusalLabel.setVisible(true);
        askRefusalLabel.setManaged(true);
        askAgentButton.pseudoClassStateChanged(
                javafx.css.PseudoClass.getPseudoClass("refused"), true);
        // See showSubmitRefused: one refusal in this footer at a time.
        clearSubmitRefused();
        fitFooter();
    }

    private void clearAskRefused() {
        askRefusalLabel.setVisible(false);
        askRefusalLabel.setManaged(false);
        askAgentButton.pseudoClassStateChanged(
                javafx.css.PseudoClass.getPseudoClass("refused"), false);
        fitFooter();
    }

    /**
     * The footer's own version of {@link #fitActionRow}'s trade: while a
     * refusal is showing AND the row is too tight to hold both, the standing
     * hint gives up its room to it.
     *
     * <p>Measured, not assumed. At the {@code CODE_MIN_WIDTH} floor the
     * footer had 264px for a refusal that asked for 319 -- and taking it
     * squeezed {@code Submit} to 39px of the 95 it wanted, which trades one
     * unreadable control for another. "press ? for shortcuts" is a standing
     * reminder; a refusal is about the click the reader just made, and it
     * outranks it for as long as it is up.</p>
     *
     * <p><strong>Gated on the WIDTH, not merely on the refusal</strong>, the
     * same way {@link #fitActionRow} gates {@code navHint}. The first version
     * dropped the hint whenever a refusal showed, at any width at all -- so a
     * 1400px bar with hundreds of pixels to spare still hid it, which is a
     * cost paid by a layout that was never short of room.</p>
     */
    private void fitFooter() {
        double width = footer.getWidth();
        boolean refusing = askRefusalLabel.isManaged() || submitRefusalLabel.isManaged();
        boolean room = !refusing || width <= 0 || width - footerWidthWithoutHint() >= hintLabel.prefWidth(-1);
        hintLabel.setVisible(room);
        hintLabel.setManaged(room);
    }

    /** What the footer needs with the hint dropped -- see {@link #fitFooter}. */
    private double footerWidthWithoutHint() {
        double needed = footer.getInsets().getLeft() + footer.getInsets().getRight();
        int slots = 0;
        for (javafx.scene.Node child : footer.getChildren()) {
            if (child == hintLabel || (!child.isManaged() && child != hintLabel)) {
                continue;
            }
            slots++;
            if (child == footerSpacer) {
                continue;
            }
            // The LARGER of pref and min. The progress bar's 120px floor is a
            // CSS -fx-min-width, and its preferred width is the fill's ~17px
            // -- so measuring pref alone under-counts this row by a hundred
            // pixels and concludes there is room for a hint there is not.
            needed += Math.max(child.prefWidth(-1), child.minWidth(-1));
        }
        // +1 slot for the hint itself, whose room is what this is deciding.
        return needed + footer.getSpacing() * Math.max(0, slots);
    }

    private void clearSubmitRefused() {
        submitRefusalLabel.setVisible(false);
        submitRefusalLabel.setManaged(false);
        submitButton.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("refused"), false);
        fitFooter();
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
            refusalLabel.setText(BLOCKING_REFUSAL);
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
     *
     * <p><strong>A reservation, not a floor.</strong> It is what {@link
     * #actionRowWidth} sets aside when deciding what else fits; the layout
     * never enforces it, because {@code intentLabel.setMinWidth(0)}
     * deliberately lets the title be the thing that yields. At {@code
     * CODE_MIN_WIDTH} with the four actions present the title measures 14px
     * against this 96 -- and that is the design working, not failing. Making
     * it a real floor would mean dropping an action button at that width,
     * which is a decision about the bar, not a bug in this constant.</p>
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
        // The footer's own fit, for the identical reason and at the identical
        // moment: showAskRefused/showSubmitRefused run outside a layout pass,
        // where footer.getWidth() is whatever the LAST pass left (0 before
        // the first), so the decision they make there is provisional. This is
        // the one that sticks.
        fitFooter();
        super.layoutChildren();
    }

    private void fitActionRow(double width) {
        if (width <= 0) {
            return;
        }
        // The blocking refusal shortens to its glyph before the nav hint is
        // dropped, because it cannot be dropped: unlike the hint it is the
        // reason a control the reader is pressing refuses to work.
        //
        // Measured: at the CODE_MIN_WIDTH floor this row has about 25px left
        // once its four actions have taken their widths, and the sentence
        // asks for 146 -- so it was elided to "⚠ a bl…", which says nothing
        // the ⚠ alone does not. The full text stays on hover either way, so
        // the short form loses no information a reader cannot reach.
        if (refusalLabel.isManaged()) {
            refusalLabel.setText(BLOCKING_REFUSAL);
            if (actionRowWidth(width, null) > width) {
                refusalLabel.setText("⚠");
            }
        }
        boolean room = width - actionRowWidth(width, navHint) >= navHint.prefWidth(-1);
        navHint.setVisible(room);
        navHint.setManaged(room);
    }

    /**
     * What the action row needs at its current contents, counting {@code
     * excluded} (when given) as taking no room of its own -- {@code navHint}
     * for the decision about whether to keep it, nothing for the decision
     * above it.
     *
     * <p>{@code navHint} is measured even while it is unmanaged so the
     * decision does not oscillate: dropping it would otherwise free the room
     * that immediately justifies bringing it back.</p>
     */
    private double actionRowWidth(double width, javafx.scene.Node excluded) {
        double needed = actionRow.getInsets().getLeft() + actionRow.getInsets().getRight()
                + INTENT_LABEL_MIN;
        int slots = 0;
        for (javafx.scene.Node child : actionRow.getChildren()) {
            if (!child.isManaged() && child != navHint) {
                continue;
            }
            slots++;
            if (child == actionSpacer || child == intentLabel || child == excluded
                    || child == navHint) {
                continue;
            }
            needed += Math.max(child.prefWidth(-1), child.minWidth(-1));
        }
        if (excluded != navHint) {
            needed += navHint.prefWidth(-1);
        }
        return needed + actionRow.getSpacing() * Math.max(0, slots - 1);
    }

    /** Test-only: whether approval is currently being refused. */
    boolean diagBlocked() {
        return blocked;
    }
}
