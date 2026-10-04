package app.drydock.ui.review;

import app.drydock.ui.TestStages;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With every rail collapsed the verdict bar is the only surface left, so it
 * has to be a complete loop on its own: say which file it is settling, and
 * move between files without the keyboard.
 */
class ReviewVerdictBarNavigationTest extends ApplicationTest {

    private final List<String> calls = new ArrayList<>();
    private ReviewVerdictBar bar;

    @Override
    public void start(Stage stage) {
        bar = new ReviewVerdictBar(new ReviewVerdictBar.Host() {
            @Override public void approve(ReviewVerdictBar.Target target) {
                calls.add("approve");
            }
            @Override public void requestChanges(ReviewVerdictBar.Target target) {
                calls.add("changes");
            }
            @Override public boolean askAgentToFix(ReviewVerdictBar.Target target) { calls.add("ask"); return true; }
            @Override public void undo(ReviewVerdictBar.Target target) { calls.add("undo"); }
            @Override public void confirmStillGood(ReviewVerdictBar.Target target) { calls.add("confirm"); }
            @Override public void nextUnsettled() { calls.add("nextUnsettled"); }
            @Override public void submit() { calls.add("submit"); }
            @Override public void previous() { calls.add("previous"); }
            @Override public void next() { calls.add("next"); }
        });
        Scene scene = new Scene(bar, 900, 200);
        scene.getStylesheets().addAll(
                getClass().getResource("/app/drydock/ui/app.css").toExternalForm(),
                getClass().getResource("/app/drydock/ui/theme-dark.css").toExternalForm());
        TestStages.show(stage, scene);
    }

    @Test
    void theBarNamesTheFileItIsSettling() {
        interact(() -> bar.update(target("2/14 · src/Parser.java"), Optional.empty(), false));

        assertEquals("2/14 · src/Parser.java",
                ((Label) lookup(".review-verdict-target").query()).getText());
    }

    @Test
    void theNavigationControlsReachTheSameActionsAsTheKeys() {
        interact(() -> bar.update(target("2/14 · src/Parser.java"), Optional.empty(), false));

        interact(() -> ((Button) lookup(".review-verdict-previous").query()).fire());
        interact(() -> ((Button) lookup(".review-verdict-next").query()).fire());

        assertEquals(List.of("previous", "next"), calls);
    }

    @Test
    void withNoFileTheBarSaysSoAndDisablesNavigation() {
        interact(() -> bar.update(null, Optional.empty(), false));

        assertEquals("no file", ((Label) lookup(".review-verdict-target").query()).getText());
        assertTrue(((Button) lookup(".review-verdict-next").query()).isDisabled());
    }

    /**
     * The hunk diff walks files, so its arrows say so; the tour's say step.
     * Read off the tooltips because the arrows are bare glyphs.
     */
    @Test
    void theArrowsNameTheFileInTheHunkDiffAndTheStepInTheTour() {
        interact(() -> bar.update(target("2/14 · src/Parser.java"), Optional.empty(), false));

        assertEquals("Previous file ([)", tooltipOf(".review-verdict-previous"));
        assertEquals("Next file (])", tooltipOf(".review-verdict-next"));

        interact(() -> bar.setTourMode(true));
        assertEquals("Previous step ([)", tooltipOf(".review-verdict-previous"));
        assertEquals("Next step (])", tooltipOf(".review-verdict-next"));

        interact(() -> bar.setTourMode(false));
        assertEquals("Previous file ([)", tooltipOf(".review-verdict-previous"));
    }

    private String tooltipOf(String selector) {
        return ((Button) lookup(selector).query()).getTooltip().getText();
    }

    private static ReviewVerdictBar.Target target(String label) {
        return new ReviewVerdictBar.Target("file:" + label, label);
    }
}
