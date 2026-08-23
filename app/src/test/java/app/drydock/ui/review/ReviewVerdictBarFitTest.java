package app.drydock.ui.review;

import app.drydock.review.ReviewIntent;
import app.drydock.review.ReviewVerdict;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The verdict bar spans the code column, and the code column's floor is
 * {@link RailLayout#CODE_MIN_WIDTH}. So the bar has to be operable at that
 * width -- with every rail collapsed it is the only surface left, and a
 * review is read, settled and advanced entirely from it.
 *
 * <p>It was not: at the floor the actions truncated to "Approv…",
 * "Request c…" and "Ask the agent …". A button whose own label is elided
 * cannot be the standing action; it is a control the reader has to guess
 * at.</p>
 */
class ReviewVerdictBarFitTest extends ApplicationTest {

    private ReviewVerdictBar bar;

    @Override
    public void start(Stage stage) {
        bar = new ReviewVerdictBar(new ReviewVerdictBar.Host() {
            @Override public void approve(ReviewIntent intent, SessionReviewView.SettleUnit unit) { }
            @Override public void requestChanges(ReviewIntent intent, SessionReviewView.SettleUnit unit) { }
            @Override public boolean askAgentToFix(ReviewIntent intent) { return askSucceeds; }
            @Override public void undo(ReviewIntent intent) { }
            @Override public void confirmStillGood(ReviewIntent intent) { }
            @Override public void nextUnsettled() { }
            @Override public void submit() { }
            @Override public void previousIntent() { }
            @Override public void nextIntent() { }
        });
        Scene scene = new Scene(bar, RailLayout.CODE_MIN_WIDTH, 200);
        scene.getStylesheets().addAll(
                getClass().getResource("/app/drydock/ui/app.css").toExternalForm(),
                getClass().getResource("/app/drydock/ui/theme-dark.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
        // The stage outlives the test class, so a scene built at the floor
        // width still comes up as wide as whatever ran before it left it --
        // under which every assertion here passes without measuring anything.
        this.stage = stage;
        sharedStage = stage;
        atTheFloor();
    }

    private Stage stage;

    /**
     * The SHARED primary stage, so the class can hand it back at a normal
     * size -- see {@link #unpoisonTheSharedStage}.
     */
    private static Stage sharedStage;

    /** Whether the stub host's hand-off succeeds; false drives the refusal. */
    private boolean askSucceeds = true;

    @AfterEach
    void restoreTheFloor() {
        askSucceeds = true;
        atTheFloor();
    }

    /**
     * Every test here deliberately leaves the stage at the code column's
     * floor, and TestFX's primary stage outlives the CLASS -- so the next
     * class in this JVM built its 1400x900 scene inside a 560x200 window,
     * its diff column rendered no rows, and its clicks reported "no
     * clickable gutter". Nothing in that failure names a width, which is why
     * it reads as flakiness rather than as a leak.
     *
     * <p>Handing the stage back is this class's job, not the next class's:
     * it is the one that took it.</p>
     */
    @AfterAll
    static void unpoisonTheSharedStage() {
        if (sharedStage == null) {
            return;
        }
        WaitForAsyncUtils.waitForAsyncFx(5000, () -> {
            sharedStage.setWidth(1400);
            sharedStage.setHeight(900);
        });
    }

    private void atTheFloor() {
        interact(() -> {
            stage.setWidth(RailLayout.CODE_MIN_WIDTH);
            stage.setHeight(200);
        });
        WaitForAsyncUtils.waitForFxEvents();
    }

    @Test
    void everyActionIsFullyLegibleAtTheCodeColumnFloor() {
        show(intent(2, "drydock/review · 4 files"), Optional.empty());

        assertNothingTruncated();
    }

    /** A settled intent swaps the actions for a verdict and an undo; it must fit too. */
    @Test
    void aSettledIntentFitsAsWell() {
        show(intent(2, "drydock/review · 4 files"),
                Optional.of(ReviewVerdict.Decision.APPROVED));

        assertNothingTruncated();
    }

    /**
     * The intent title is the one thing allowed to give way -- it is context,
     * and the actions are the point. A long one must not push the buttons
     * back into truncation.
     */
    @Test
    void aLongIntentTitleYieldsInsteadOfTheButtons() {
        show(intent(11, "app/src/main/java/app/drydock/ui/review · 23 files, +1782 −455"),
                Optional.empty());

        assertNothingTruncated();
    }

    /**
     * The hint is dropped for want of room, not deleted. Hiding it
     * unconditionally would satisfy every assertion above, and would quietly
     * cost the wide layout a line it has always had.
     */
    @Test
    void theHintIsBackAsSoonAsThereIsRoomForIt() {
        show(intent(2, "drydock/review · 4 files"), Optional.empty());
        assertFalse(navHintShowing(), "at the floor the nav hint has to go");

        interact(() -> bar.getScene().getWindow().setWidth(1400));
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(navHintShowing(), "a wide bar shows the nav hint again");
    }

    /**
     * The stale banner (spec §9.2) swaps in a label plus two more buttons,
     * "Confirm still good" and "Re-review"; the Phase 1 gate named it as new
     * UI with no fit coverage, and the coordinator's review found the gap
     * was real TWICE over: {@code assertNothingTruncated} only ever looked
     * at {@code .button}, so {@code staleLabel} -- a {@code wrapText} label
     * with no {@code minWidth} -- could reflow silently underneath it, and
     * nothing asserted the BAR's own height at the floor either. Both are
     * folded into {@link #assertNothingTruncated} now, so every caller gets
     * them, not just this test.
     *
     * <p>Measured, not designed around: at the {@code CODE_MIN_WIDTH} floor
     * the banner does NOT read as one line -- {@code "⚠ approved against
     * base a1b2c3d · base is now d4e5f6a"} wraps to exactly two, 17px each.
     * Nothing is truncated (wrap, not ellipsis -- no character is lost), but
     * the floor is real and the verdict bar's row genuinely gets one line
     * taller whenever the current section is stale at that width.</p>
     */
    @Test
    void theStaleBannerFitsAtTheCodeColumnFloor() {
        show(intent(2, "drydock/review · 4 files"), Optional.of(ReviewVerdict.Decision.APPROVED));
        interact(() -> bar.showStale(Optional.of(
                new ReviewVerdictBar.StaleInfo("a1b2c3d4e5f6789", "d4e5f6a1b2c3789"))));
        WaitForAsyncUtils.waitForFxEvents();
        interact(() -> bar.getScene().getRoot().layout());
        WaitForAsyncUtils.waitForFxEvents();

        assertNothingTruncated();
    }

    /**
     * The unit (spec §9.6) is named on the button itself now, not a separate
     * droppable label: "Approve intent" (the pre-Task-7 text) contradicted
     * whatever {@link SessionReviewView#settleUnit()} actually hit, and at
     * the floor the acting-unit label the first attempt added was hidden by
     * design -- so the ONLY unit statement visible there was the wrong one.
     * Naming it on the button is always-visible, which is what makes this
     * the fit-relevant surface rather than the (now deleted) label.
     */
    @Test
    void theApproveButtonNamesTheUnitAndFitsForEveryUnitAtTheFloor() {
        for (SessionReviewView.SettleUnit unit : SessionReviewView.SettleUnit.values()) {
            show(intent(2, "drydock/review · 4 files"), Optional.empty());
            interact(() -> bar.showActingUnit(unit));
            WaitForAsyncUtils.waitForFxEvents();
            interact(() -> bar.getScene().getRoot().layout());
            WaitForAsyncUtils.waitForFxEvents();

            assertTrue(approveButtonText().contains(unitWord(unit)),
                    "the button must name " + unit + ", got: " + approveButtonText());
            assertNothingTruncated();
        }
    }

    /**
     * Fix round 2's refusal is a FOURTH thing competing for the action row
     * at the floor, and this file exists because that row has truncated
     * before ("Approv…", "Request c…"). A refusal the reader cannot read is
     * no better than the silence it replaced.
     */
    @Test
    void theAskRefusalFitsAtTheCodeColumnFloor() {
        askSucceeds = false;
        show(intent(2, "drydock/review · 4 files"), Optional.empty());

        interact(() -> askButton().fire());
        WaitForAsyncUtils.waitForFxEvents();
        interact(() -> bar.getScene().getRoot().layout());
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(lookup(".review-verdict-ask-refusal").queryAll().stream().anyMatch(Node::isVisible),
                "the refusal must be showing, or this measures nothing");
        assertNothingTruncated();
    }

    /**
     * Round 3, item 3. The blocking refusal is a STATE, not a click, so it
     * sits in the action row -- which at the floor has about 25px of slack
     * once the four actions have taken their widths. It asked for 146.
     * Nothing rendered it in a fit test before, which is the only reason it
     * survived the round that added the elision check.
     */
    @Test
    void theBlockingRefusalFitsAtTheCodeColumnFloor() {
        interact(() -> {
            bar.update(intent(2, "drydock/review · 4 files"), Optional.empty(), true);
            bar.showProgress(1, 7);
        });
        WaitForAsyncUtils.waitForFxEvents();
        interact(() -> bar.getScene().getRoot().layout());
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(lookup(".review-verdict-refusal").queryAll().stream()
                        .filter(node -> !node.getStyleClass().contains("review-verdict-ask-refusal"))
                        .filter(node -> !node.getStyleClass().contains("review-verdict-submit-refusal"))
                        .anyMatch(Node::isVisible),
                "the blocking refusal must be showing, or this measures nothing");
        assertNothingTruncated();
        // AND the title still exists. "Nothing is truncated" is otherwise
        // satisfiable by letting the refusal run to its full 146px and
        // taking every one of them from the title: the title is allowed to
        // YIELD (intentLabel.setMinWidth(0)) and at this floor it yields
        // almost everything, but the row is not allowed to spend it down to
        // nothing for a sentence whose glyph says as much. Measured: 14px
        // with the short form, 0 with the long one.
        double[] title = new double[1];
        interact(() -> title[0] = lookup(".review-verdict-intent").query().getBoundsInLocal().getWidth());
        assertTrue(title[0] > 0, "the intent title was squeezed out of existence; the refusal "
                + "must shorten to its glyph before taking the last of it");
    }

    /**
     * Round 3, item 2. {@code update()} clears both footer refusals, but
     * nothing cleared one when the OTHER was raised -- and neither failure
     * path calls {@code update()}. Submit refuses, the reader then asks the
     * agent on that same intent, and both labels plus {@code Submit} shared
     * one row three ways: the primary action read "Sub…".
     */
    @Test
    void raisingOneFooterRefusalRetiresTheOther() {
        show(intent(2, "drydock/review · 4 files"), Optional.empty());
        interact(() -> bar.showSubmitRefused("an intent needs a verdict; jumped to it"));
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(refusalShowing("review-verdict-submit-refusal"));

        askSucceeds = false;
        interact(() -> askButton().fire());
        WaitForAsyncUtils.waitForFxEvents();
        interact(() -> bar.getScene().getRoot().layout());
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(refusalShowing("review-verdict-ask-refusal"), "the newer refusal is the one shown");
        assertFalse(refusalShowing("review-verdict-submit-refusal"),
                "two refusals in one footer squeeze Submit to an ellipsis");
        assertNothingTruncated();
    }

    /**
     * The other direction, which the test above cannot see and a mutation
     * proved it could not: {@code showAskRefused} clearing the submit
     * refusal and {@code showSubmitRefused} clearing the ask one are two
     * separate lines, and either can be lost on its own. A reader reaches
     * this one by asking the agent, being told there is nothing to send, and
     * then pressing Submit.
     */
    @Test
    void raisingTheSubmitRefusalRetiresTheAskRefusalToo() {
        show(intent(2, "drydock/review · 4 files"), Optional.empty());
        askSucceeds = false;
        interact(() -> askButton().fire());
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(refusalShowing("review-verdict-ask-refusal"));

        interact(() -> bar.showSubmitRefused("a verdict is missing; jumped to it"));
        WaitForAsyncUtils.waitForFxEvents();
        interact(() -> bar.getScene().getRoot().layout());
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(refusalShowing("review-verdict-submit-refusal"), "the newer refusal is the one shown");
        assertFalse(refusalShowing("review-verdict-ask-refusal"),
                "two refusals in one footer squeeze Submit to an ellipsis");
        assertNothingTruncated();
    }

    /**
     * Round 3, item 3. {@code fitFooter} traded the shortcuts hint away for
     * a refusal at ANY width -- it never consulted the room it had, unlike
     * {@code fitActionRow}. A 1400px bar hid it with hundreds of pixels to
     * spare, and no test could see that: both hints carry
     * {@code .review-verdict-hint} and the only assertion about "the hint"
     * matched navHint's text.
     */
    @Test
    void aWideBarKeepsTheShortcutHintWhileRefusing() {
        show(intent(2, "drydock/review · 4 files"), Optional.empty());
        interact(() -> bar.getScene().getWindow().setWidth(1400));
        WaitForAsyncUtils.waitForFxEvents();

        interact(() -> bar.showSubmitRefused("an intent needs a verdict; jumped to it"));
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(refusalShowing("review-verdict-submit-refusal"), "the refusal must be up");
        assertTrue(shortcutHintShowing(),
                "a 1400px bar has room for both; the hint is dropped for want of room, not on principle");
    }

    /** And at the floor it still yields, which is what made the trade worth making. */
    @Test
    void atTheFloorTheShortcutHintStillYieldsToARefusal() {
        show(intent(2, "drydock/review · 4 files"), Optional.empty());
        interact(() -> bar.showSubmitRefused("an intent needs a verdict; jumped to it"));
        WaitForAsyncUtils.waitForFxEvents();
        interact(() -> bar.getScene().getRoot().layout());
        WaitForAsyncUtils.waitForFxEvents();

        assertFalse(shortcutHintShowing(), "at the floor the refusal takes the hint's room");
        assertNothingTruncated();
    }

    private boolean refusalShowing(String styleClass) {
        boolean[] showing = new boolean[1];
        interact(() -> showing[0] = lookup("." + styleClass).queryAll().stream()
                .anyMatch(Node::isVisible));
        return showing[0];
    }

    private Button askButton() {
        return lookup(".button").queryAll().stream()
                .map(Button.class::cast)
                .filter(button -> "Ask the agent to fix it".equals(button.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no Ask-the-agent button"));
    }

    private static String unitWord(SessionReviewView.SettleUnit unit) {
        return switch (unit) {
            case HUNK -> "next unread hunk";
            case SECTION -> "section";
            case FILE -> "file";
            case PATH_STEP -> "hunk";
        };
    }

    private String approveButtonText() {
        String[] text = new String[1];
        interact(() -> text[0] = lookup(".button").queryAll().stream()
                .map(Button.class::cast)
                .map(Button::getText)
                .filter(t -> t.startsWith("Approve ("))
                .findFirst()
                .orElse("<no approve button found>"));
        return text[0];
    }

    // ---- helpers --------------------------------------------------------

    /**
     * {@code navHint} -- "3 left · n jumps to the next", the action row's own
     * droppable hint. NOT the footer's "press ? for shortcuts": both carry
     * {@code .review-verdict-hint}, and this method used to match on text to
     * pick one, which meant every assertion about "the hint" was silently
     * about the action row only.
     */
    private boolean navHintShowing() {
        boolean[] showing = new boolean[1];
        interact(() -> showing[0] = lookup(".review-verdict-hint").queryAll().stream()
                .anyMatch(node -> node.isManaged()
                        && ((Label) node).getText().contains("jumps to the next")));
        return showing[0];
    }

    /** The FOOTER's "press ? for shortcuts", found by its own class. */
    private boolean shortcutHintShowing() {
        boolean[] showing = new boolean[1];
        interact(() -> showing[0] = lookup(".review-verdict-shortcut-hint").queryAll().stream()
                .anyMatch(Node::isManaged));
        return showing[0];
    }


    private void show(ReviewIntent intent, Optional<ReviewVerdict.Decision> decision) {
        interact(() -> {
            bar.update(intent, decision, false);
            bar.showProgress(1, 7);
        });
        WaitForAsyncUtils.waitForFxEvents();
        interact(() -> bar.getScene().getRoot().layout());
        WaitForAsyncUtils.waitForFxEvents();
    }

    /**
     * A control is truncated when it is laid out narrower than the width it
     * asked for. Comparing against {@code prefWidth} rather than reading the
     * skin's elided string keeps this independent of the font the CI machine
     * happens to have.
     */
    /**
     * Generous: a normal one-line row at the floor is under 40px; the
     * stale banner's own two-line wrap (see the class's history) adds one
     * more line. What this actually guards against is the OTHER failure
     * mode this codebase has shipped -- a wrapped label collapsing to a
     * column of single characters (see {@code ReviewIntentRailCardHeightTest}) --
     * not the two-line wrap itself, which is real and reported, not hidden.
     */
    private static final double SANE_BAR_HEIGHT = 160;

    private void assertNothingTruncated() {
        double[] width = new double[1];
        double[] barPrefHeight = new double[1];
        interact(() -> {
            width[0] = bar.getWidth();
            // prefHeight, not getHeight(): the bar is the Scene's ROOT, and
            // a Scene resizes its root to fill its own fixed dimensions
            // (200px here) regardless of content -- getHeight() would
            // therefore always read 200 and this assertion would pass
            // without measuring anything, the same trap the width check
            // above already guards against.
            barPrefHeight[0] = bar.prefHeight(RailLayout.CODE_MIN_WIDTH);
        });
        assertTrue(width[0] <= RailLayout.CODE_MIN_WIDTH + 1,
                "the bar is " + Math.round(width[0]) + "px, not at the floor -- this assertion "
                        + "would pass without measuring anything");
        assertTrue(barPrefHeight[0] > 0 && barPrefHeight[0] < SANE_BAR_HEIGHT,
                "the bar wants " + Math.round(barPrefHeight[0]) + "px tall at the "
                        + (int) RailLayout.CODE_MIN_WIDTH + "px floor; a wrapped label collapsed to "
                        + "a column of single characters looks exactly like this");

        List<String> squeezed = new ArrayList<>();
        interact(() -> lookup(".button").queryAll().stream()
                .map(Button.class::cast)
                .filter(Button::isVisible)
                .forEach(button -> {
                    double wanted = button.prefWidth(-1);
                    if (button.getWidth() + 0.5 < wanted) {
                        squeezed.add("'" + button.getText() + "' got "
                                + Math.round(button.getWidth()) + " of " + Math.round(wanted));
                    }
                }));
        // Folded in per the coordinator's review: a wrapText label with no
        // minWidth (the stale banner) can reflow silently underneath a
        // button-only check. Not "one line" -- it measurably is not, at
        // this floor (see theStaleBannerFitsAtTheCodeColumnFloor's javadoc)
        // -- but it must not wrap past two lines either.
        interact(() -> lookup(".review-verdict-stale").queryAll().stream()
                .map(Label.class::cast)
                .filter(Label::isVisible)
                .forEach(label -> {
                    double oneLine = label.prefHeight(-1);
                    double actual = label.getHeight();
                    if (actual > oneLine * 2 + 1) {
                        squeezed.add("'" + label.getText() + "' wrapped to roughly "
                                + Math.round(actual / oneLine) + " lines ("
                                + Math.round(actual) + "px)");
                    }
                }));
        // A refusal label is NOT wrapText, so it elides rather than reflows --
        // invisible to both checks above. Measured the same way the buttons
        // are (laid-out width against asked-for width), which keeps it
        // independent of the CI machine's font.
        interact(() -> lookup(".review-verdict-refusal").queryAll().stream()
                .map(Label.class::cast)
                .filter(Label::isVisible)
                .forEach(label -> {
                    double wanted = label.prefWidth(-1);
                    if (label.getWidth() + 0.5 < wanted) {
                        squeezed.add("'" + label.getText() + "' got "
                                + Math.round(label.getWidth()) + " of " + Math.round(wanted));
                    }
                }));
        assertTrue(squeezed.isEmpty(), "at " + (int) RailLayout.CODE_MIN_WIDTH
                + "px these controls were truncated or mis-wrapped: " + squeezed);
    }

    private static ReviewIntent intent(int number, String title) {
        return new ReviewIntent("auto:" + number, number, title, ReviewIntent.Kind.CHANGE,
                ReviewIntent.Risk.LOW, "", List.of(), Optional.empty(), false);
    }
}
