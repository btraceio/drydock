package app.drydock.ui.review;

import app.drydock.ui.TestStages;
import app.drydock.review.ReviewIntent;
import app.drydock.review.ReviewVerdict;
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
 * has to be a complete loop on its own: say which intent it is settling, and
 * move between them without the keyboard.
 */
class ReviewVerdictBarNavigationTest extends ApplicationTest {

    private final List<String> calls = new ArrayList<>();
    private ReviewVerdictBar bar;

    @Override
    public void start(Stage stage) {
        bar = new ReviewVerdictBar(new ReviewVerdictBar.Host() {
            @Override public void approve(ReviewIntent intent, SessionReviewView.SettleUnit unit) {
                calls.add("approve");
            }
            @Override public void requestChanges(ReviewIntent intent, SessionReviewView.SettleUnit unit) {
                calls.add("changes");
            }
            @Override public boolean askAgentToFix(ReviewIntent intent) { calls.add("ask"); return true; }
            @Override public void undo(ReviewIntent intent) { calls.add("undo"); }
            @Override public void confirmStillGood(ReviewIntent intent) { calls.add("confirm"); }
            @Override public void nextUnsettled() { calls.add("nextUnsettled"); }
            @Override public void submit() { calls.add("submit"); }
            @Override public void previousIntent() { calls.add("previous"); }
            @Override public void nextIntent() { calls.add("next"); }
        });
        Scene scene = new Scene(bar, 900, 200);
        scene.getStylesheets().addAll(
                getClass().getResource("/app/drydock/ui/app.css").toExternalForm(),
                getClass().getResource("/app/drydock/ui/theme-dark.css").toExternalForm());
        TestStages.show(stage, scene);
    }

    @Test
    void theBarNamesTheIntentItIsSettling() {
        interact(() -> bar.update(intent(2, "Rename the parser"), Optional.empty(), false));

        assertEquals("2 · Rename the parser",
                ((Label) lookup(".review-verdict-intent").query()).getText());
    }

    @Test
    void theNavigationControlsReachTheSameActionsAsTheKeys() {
        interact(() -> bar.update(intent(2, "Rename the parser"), Optional.empty(), false));

        interact(() -> ((Button) lookup(".review-verdict-previous").query()).fire());
        interact(() -> ((Button) lookup(".review-verdict-next").query()).fire());

        assertEquals(List.of("previous", "next"), calls);
    }

    @Test
    void withNoIntentTheBarSaysSoAndDisablesNavigation() {
        interact(() -> bar.update(null, Optional.empty(), false));

        assertEquals("no intent", ((Label) lookup(".review-verdict-intent").query()).getText());
        assertTrue(((Button) lookup(".review-verdict-next").query()).isDisabled());
    }

    private static ReviewIntent intent(int number, String title) {
        return new ReviewIntent("intent-" + number, number, title, ReviewIntent.Kind.CHANGE,
                ReviewIntent.Risk.LOW, "", List.of(), Optional.empty(), false);
    }
}
