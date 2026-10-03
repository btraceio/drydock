package app.drydock.ui.review;

import app.drydock.review.tour.CheckProgress;
import app.drydock.review.tour.StepProgress;
import app.drydock.review.tour.TourAnchor;
import app.drydock.review.tour.TourCheck;
import app.drydock.review.tour.TourStep;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepPanelTest extends ApplicationTest {

    private final List<String> calls = new ArrayList<>();
    private StepPanel panel;

    private static TourCheck predict() {
        TourCheck alternate = new TourCheck("c1_alt", TourCheck.Kind.PREDICT, "Alt?",
                List.of(new TourCheck.Choice("p", Optional.empty()), new TourCheck.Choice("q", Optional.empty())),
                OptionalInt.of(0), "Alt because.", List.of());
        return new TourCheck("c1", TourCheck.Kind.PREDICT, "What happens?",
                List.of(new TourCheck.Choice("throws", Optional.empty()), new TourCheck.Choice("returns", Optional.empty())),
                OptionalInt.of(1), "It returns.", List.of(alternate));
    }

    private static final TourStep STEP = new TourStep("s1", "Guard", "Why the guard exists.",
            List.of(new TourAnchor("src/A.java", "n3", "n9")), List.of(), List.of(predict()));

    private static StepView view(CheckProgress check) {
        return new StepView(STEP, 1, 3, new StepProgress("s1", List.of(), Map.of("c1", check),
                StepProgress.Decision.NONE, Optional.empty(), false));
    }

    @Override
    public void start(Stage stage) {
        panel = new StepPanel(new StepPanel.Host() {
            @Override public void answerChoice(String checkId, int choiceIndex) { calls.add("answer " + checkId + " " + choiceIndex); }
            @Override public void submitRisk(String checkId, String answer) { calls.add("risk " + checkId + " " + answer); }
            @Override public void override(String reason) { calls.add("override " + reason); }
            @Override public void askAgent(String checkId) { calls.add("ask " + checkId); }
            @Override public void goToAnchor(int anchorIndex) { calls.add("anchor " + anchorIndex); }
            @Override public void retryRisk(String checkId) { calls.add("retry " + checkId); }
        });
        stage.setScene(new Scene(panel, 336, 700));
        stage.show();
    }

    @Test
    void choicesAreNumberedButtonsThatAnswerTheCheck() {
        interact(() -> panel.show(view(CheckProgress.fresh("c1"))));
        clickOn("2  returns");
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(List.of("answer c1 1"), calls);
    }

    @Test
    void aDigitKeyAnswersTheActiveChoiceCheck() {
        interact(() -> panel.show(view(CheckProgress.fresh("c1"))));
        boolean[] handled = new boolean[1];
        interact(() -> handled[0] = panel.answerByKey(1));
        assertTrue(handled[0]);
        assertEquals(List.of("answer c1 0"), calls);
    }

    @Test
    void afterAWrongAnswerTheExplanationAndTheAlternateShow() {
        CheckProgress wrong = new CheckProgress("c1", 1, CheckProgress.Status.OPEN, Optional.empty(),
                Optional.empty(), Optional.of("It returns."));
        interact(() -> panel.show(view(wrong)));
        assertTrue(lookup("Not quite: It returns.").tryQuery().isPresent());
        assertTrue(lookup("Alt?").tryQuery().isPresent());
    }

    @Test
    void anExhaustedCheckOffersAnOverrideThatNeedsAReason() {
        CheckProgress exhausted = new CheckProgress("c1", 1, CheckProgress.Status.EXHAUSTED, Optional.empty(),
                Optional.empty(), Optional.of("Alt because."));
        interact(() -> panel.show(view(exhausted)));
        Button override = lookup("Approve without passing").queryAs(Button.class);
        assertTrue(override.isDisabled());
        clickOn(".step-override-reason").write("trivial rename");
        WaitForAsyncUtils.waitForFxEvents();
        clickOn("Approve without passing");
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(List.of("override trivial rename"), calls);
    }

    @Test
    void anchorChipsGoToTheirAnchor() {
        interact(() -> panel.show(view(CheckProgress.fresh("c1"))));
        clickOn("src/A.java:3");
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(List.of("anchor 0"), calls);
    }

    @Test
    void aTransientNoticeShowsAboveTheContentUntilTheNextShow() {
        interact(() -> {
            panel.show(view(CheckProgress.fresh("c1")));
            panel.showTransient("Could not save.");
        });
        assertTrue(lookup("Could not save.").tryQuery().isPresent());
        assertTrue(lookup("What happens?").tryQuery().isPresent());
        interact(() -> panel.show(view(CheckProgress.fresh("c1"))));
        assertTrue(lookup("Could not save.").tryQuery().isEmpty());
    }
}
