package app.drydock.ui.review;

import app.drydock.review.ReviewIntent;
import app.drydock.review.ReviewVerdict;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.stage.Stage;
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
            @Override public void askAgentToFix(ReviewIntent intent) { }
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
        atTheFloor();
    }

    private Stage stage;

    @AfterEach
    void restoreTheFloor() {
        atTheFloor();
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
        assertFalse(hintShowing(), "at the floor the hint has to go");

        interact(() -> bar.getScene().getWindow().setWidth(1400));
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(hintShowing(), "a wide bar shows the hint again");
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

    private static String unitWord(SessionReviewView.SettleUnit unit) {
        return switch (unit) {
            case HUNK -> "next unread hunk";
            case SECTION -> "section";
            case FILE -> "file";
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

    private boolean hintShowing() {
        boolean[] showing = new boolean[1];
        interact(() -> showing[0] = lookup(".review-verdict-hint").queryAll().stream()
                .anyMatch(node -> node.isManaged()
                        && ((Label) node).getText().contains("jumps to the next")));
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
        assertTrue(squeezed.isEmpty(), "at " + (int) RailLayout.CODE_MIN_WIDTH
                + "px these controls were truncated or mis-wrapped: " + squeezed);
    }

    private static ReviewIntent intent(int number, String title) {
        return new ReviewIntent("auto:" + number, number, title, ReviewIntent.Kind.CHANGE,
                ReviewIntent.Risk.LOW, "", List.of(), Optional.empty(), false);
    }
}
