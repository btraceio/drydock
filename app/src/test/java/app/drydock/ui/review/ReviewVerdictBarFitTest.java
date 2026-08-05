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
            @Override public void approve(ReviewIntent intent) { }
            @Override public void requestChanges(ReviewIntent intent) { }
            @Override public void askAgentToFix(ReviewIntent intent) { }
            @Override public void undo(ReviewIntent intent) { }
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
                Optional.of(new ReviewVerdict("rs_x", "auto:2", ReviewVerdict.Decision.APPROVED,
                        Optional.empty(), java.time.Instant.EPOCH)));

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

    // ---- helpers --------------------------------------------------------

    private boolean hintShowing() {
        boolean[] showing = new boolean[1];
        interact(() -> showing[0] = lookup(".review-verdict-hint").queryAll().stream()
                .anyMatch(node -> node.isManaged()
                        && ((Label) node).getText().contains("jumps to the next")));
        return showing[0];
    }


    private void show(ReviewIntent intent, Optional<ReviewVerdict> verdict) {
        interact(() -> bar.update(intent, verdict, false, 1, 7));
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
    private void assertNothingTruncated() {
        double[] width = new double[1];
        interact(() -> width[0] = bar.getWidth());
        assertTrue(width[0] <= RailLayout.CODE_MIN_WIDTH + 1,
                "the bar is " + Math.round(width[0]) + "px, not at the floor -- this assertion "
                        + "would pass without measuring anything");

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
        assertTrue(squeezed.isEmpty(), "at " + (int) RailLayout.CODE_MIN_WIDTH
                + "px these controls were truncated: " + squeezed);
    }

    private static ReviewIntent intent(int number, String title) {
        return new ReviewIntent("auto:" + number, number, title, ReviewIntent.Kind.CHANGE,
                ReviewIntent.Risk.LOW, "", List.of(), Optional.empty(), false);
    }
}
