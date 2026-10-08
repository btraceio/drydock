package app.drydock.ui.review;

import app.drydock.review.AnnotationStatus;
import app.drydock.review.Confidence;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.Severity;
import app.drydock.review.Triage;
import app.drydock.review.tour.CheckProgress;
import app.drydock.review.tour.StepGate;
import app.drydock.review.tour.StepProgress;
import app.drydock.review.tour.TourAnchor;
import app.drydock.review.tour.TourCheck;
import app.drydock.review.tour.TourStep;
import app.drydock.testing.FxSync;
import app.drydock.testing.FxTest;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.text.Text;
import app.drydock.review.tour.ImpactNote;
import app.drydock.review.tour.StepImpact;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepPanelTest extends FxTest {

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
            @Override public void goToAnchor(int anchorIndex) { calls.add("anchor " + anchorIndex); }
            @Override public void retryRisk(String checkId) { calls.add("retry " + checkId); }
            @Override public void triage(ReviewAnnotation finding, Triage triage, Optional<String> reason) {
                calls.add("triage " + finding.id() + " " + triage);
            }
            @Override public void revealFinding(ReviewAnnotation finding) { calls.add("reveal " + finding.id()); }
            @Override public void sendBack(List<ReviewAnnotation> blockers) { calls.add("send " + blockers.size()); }
            @Override public void reviewAnyway() { calls.add("review anyway"); }
            @Override public void backToStep() { calls.add("back to step"); }
            @Override public void openLocation(String file, int line) { calls.add("open " + file + ":" + line); }
            @Override public void selectStep(String stepId) { calls.add("select " + stepId); }
            @Override public void requestRefresh() { calls.add("refresh"); }
            @Override public void postMessage(ReviewAnnotation finding, String body) {
                calls.add("message " + finding.id() + " " + body);
            }
        });
        stage.setScene(new Scene(panel, 336, 700));
        stage.show();
    }

    @Test
    void choicesAreNumberedButtonsThatAnswerTheCheck() {
        interact(() -> panel.show(view(CheckProgress.fresh("c1"))));
        clickOn("2  returns");
        FxSync.waitForFxEvents();
        assertEquals(List.of("answer c1 1"), calls);
    }

    private static String displayed(Button button) {
        button.applyCss();
        button.layout();
        return ((Text) button.lookup(".text")).getText();
    }

    @Test
    void aChoiceWithAnUnderscoreIsShownVerbatim() {
        TourCheck check = new TourCheck("c1", TourCheck.Kind.PREDICT, "What does it return?",
                List.of(new TourCheck.Choice("Return Integer.MAX_VALUE", Optional.empty()),
                        new TourCheck.Choice("Return zero", Optional.empty())),
                OptionalInt.of(0), "Because.", List.of());
        TourStep step = new TourStep("s1", "Guard", "Why.", List.of(new TourAnchor("src/A.java", "n3", "n9")),
                List.of(), List.of(check));
        interact(() -> panel.show(new StepView(step, 1, 3, new StepProgress("s1", List.of(),
                Map.of("c1", CheckProgress.fresh("c1")), StepProgress.Decision.NONE, Optional.empty(), false))));
        Button choice = lookup("1  Return Integer.MAX_VALUE").queryAs(Button.class);
        assertFalse(choice.isMnemonicParsing());
        assertEquals("1  Return Integer.MAX_VALUE", displayed(choice));
    }

    @Test
    void anImpactEntryWithAnUnderscoreIsShownVerbatim() {
        interact(() -> panel.showImpact(new StepPanel.ImpactView(
                List.of(new ImpactNote("src/snake_case.h", 1, "keep max_value in range")),
                new StepImpact(List.of(), List.of(), List.of(), List.of(), List.of(), Optional.empty()),
                Map.of(), false, Optional.empty(), true)));
        Button entry = lookup("src/snake_case.h:1 — keep max_value in range").queryAs(Button.class);
        assertFalse(entry.isMnemonicParsing());
        assertEquals("src/snake_case.h:1 — keep max_value in range", displayed(entry));
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
        FxSync.waitForFxEvents();
        clickOn("Approve without passing");
        FxSync.waitForFxEvents();
        assertEquals(List.of("override trivial rename"), calls);
    }

    @Test
    void anOpenPredictSaysReadTheCodeFirstAndLinksItBeforeAskingTheQuestion() {
        interact(() -> panel.show(view(CheckProgress.fresh("c1"))));
        assertTrue(lookup("Why the guard exists.").tryQuery().isEmpty(),
                "the narrative says what the added lines do, which is what the PREDICT asks");
        double notice = top(lookup(StepPanel.WITHHELD_NARRATIVE).query());
        double link = top(lookup("src/A.java:3").query());
        double question = top(lookup("What happens?").query());
        assertTrue(notice < link, "the notice, then the links to the code it points at");
        assertTrue(link < question, "and only then the question about that code");
    }

    @Test
    void theNoticeSaysWhatIsHiddenAndWhatIsNot() {
        assertTrue(StepPanel.WITHHELD_NARRATIVE.contains("surrounding code"));
        assertTrue(StepPanel.WITHHELD_NARRATIVE.contains("added lines are hidden until you answer"));
        assertFalse(StepPanel.WITHHELD_NARRATIVE.contains("Read the code first"),
                "it must not send the reader to read the very lines the diff column hides");
    }

    private static double top(Node node) {
        return node.localToScene(node.getBoundsInLocal()).getMinY();
    }

    @Test
    void theExpandedWidthIsWhatTheReaderChoseClampedToWhatAPanelCanBe() {
        interact(() -> panel.setExpandedWidth(480));
        assertEquals(480, panel.getPrefWidth());
        assertEquals(480, panel.getMinWidth());
        assertEquals(480, panel.getMaxWidth());

        interact(() -> panel.setExpandedWidth(50));
        assertEquals(StepPanel.MIN_WIDTH, panel.getPrefWidth(), "narrower than the narrow width is not readable");
        interact(() -> panel.setExpandedWidth(5000));
        assertEquals(StepPanel.MAX_WIDTH, panel.getPrefWidth());
    }

    @Test
    void narrowAndCollapsedStillWinOverAChosenWidth() {
        interact(() -> panel.setExpandedWidth(600));

        interact(() -> panel.setNarrow(true));
        assertEquals(StepPanel.NARROW_WIDTH, panel.getPrefWidth(), "narrow takes a wide panel back down");

        interact(() -> panel.setCollapsed(true));
        assertEquals(StepPanel.COLLAPSED_WIDTH, panel.getPrefWidth());

        interact(() -> {
            panel.setCollapsed(false);
            panel.setNarrow(false);
        });
        assertEquals(600, panel.getPrefWidth(), "and the chosen width comes back with the room");
    }

    @Test
    void anAnsweredPredictShowsTheNarrativeAndDropsTheWithheldNotice() {
        CheckProgress passed = new CheckProgress("c1", 0, CheckProgress.Status.PASSED, Optional.empty(),
                Optional.empty(), Optional.empty());
        interact(() -> panel.show(view(passed)));
        assertTrue(lookup("Why the guard exists.").tryQuery().isPresent());
        assertTrue(lookup(StepPanel.WITHHELD_NARRATIVE).tryQuery().isEmpty());
    }

    @Test
    void aRiskCheckNeverWithholdsTheNarrative() {
        TourCheck risk = new TourCheck("r1", TourCheck.Kind.RISK, "What could break?", List.of(),
                OptionalInt.empty(), "", List.of());
        TourStep step = new TourStep("s1", "Guard", "Why the guard exists.", List.of(), List.of(), List.of(risk));
        interact(() -> panel.show(new StepView(step, 1, 1, new StepProgress("s1", List.of(),
                Map.of("r1", CheckProgress.fresh("r1")), StepProgress.Decision.NONE, Optional.empty(), false))));
        assertTrue(lookup("Why the guard exists.").tryQuery().isPresent());
    }

    @Test
    void anchorChipsGoToTheirAnchor() {
        interact(() -> panel.show(view(CheckProgress.fresh("c1"))));
        clickOn("src/A.java:3");
        FxSync.waitForFxEvents();
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

    @Test
    void aWhitespaceOnlyOverrideReasonKeepsTheOverrideDisabled() {
        CheckProgress exhausted = new CheckProgress("c1", 1, CheckProgress.Status.EXHAUSTED, Optional.empty(),
                Optional.empty(), Optional.empty());
        interact(() -> panel.show(view(exhausted)));
        clickOn(".step-override-reason").write("   ");
        FxSync.waitForFxEvents();
        assertTrue(lookup("Approve without passing").queryAs(Button.class).isDisabled());
    }

    @Test
    void aWhitespaceOnlyRiskAnswerKeepsSendDisabled() {
        TourCheck risk = new TourCheck("r1", TourCheck.Kind.RISK, "What could break?", List.of(),
                OptionalInt.empty(), "", List.of());
        TourStep step = new TourStep("s1", "Guard", "Why.", List.of(), List.of(), List.of(risk));
        StepView riskView = new StepView(step, 1, 1, new StepProgress("s1", List.of(),
                Map.of("r1", CheckProgress.fresh("r1")), StepProgress.Decision.NONE, Optional.empty(), false));
        interact(() -> panel.show(riskView));
        clickOn(".text-area").write("   ");
        FxSync.waitForFxEvents();
        assertTrue(lookup("Send answer").queryAs(Button.class).isDisabled());
    }

    @Test
    void aStaleStepWhoseRefreshWasSentSaysTheAgentIsRewritingIt() {
        StepView stale = new StepView(STEP, 1, 3, new StepProgress("s1", List.of(), Map.of(),
                StepProgress.Decision.PASSED, Optional.empty(), true), Optional.of(STALE), true);
        interact(() -> panel.show(stale));
        assertTrue(lookup("This step's code changed; the agent is re-writing it.").tryQuery().isPresent());
        assertTrue(lookup("Ask the agent to refresh").tryQuery().isEmpty(), "already asked");
        interact(() -> panel.show(view(CheckProgress.fresh("c1"))));
        assertTrue(lookup("This step's code changed; the agent is re-writing it.").tryQuery().isEmpty());
    }

    private static final StepGate.Unmet STALE = new StepGate.Unmet(StepGate.Kind.STALE, "s1",
            "This step's code changed since the tour was written; it is waiting for the agent.");

    private static final StepGate.Unmet BLOCKER = new StepGate.Unmet(StepGate.Kind.BLOCKER, "f1",
            "A confirmed blocking finding is open here: request changes (r) or approve without passing.");

    @Test
    void aStaleStepNobodyAskedAboutOffersToAskTheAgent() {
        StepView stale = new StepView(STEP, 1, 3, new StepProgress("s1", List.of(), Map.of(),
                StepProgress.Decision.NONE, Optional.empty(), true), Optional.of(STALE), false);
        interact(() -> panel.show(stale));

        assertTrue(lookup("This step's code changed; waiting for the agent.").tryQuery().isPresent());
        assertTrue(lookup("This step's code changed; the agent is re-writing it.").tryQuery().isEmpty(),
                "nothing was sent, so nothing is being re-written");
        clickOn("Ask the agent to refresh");
        FxSync.waitForFxEvents();
        assertEquals(List.of("refresh"), calls);
    }

    @Test
    void aStaleStepCanBeApprovedWithoutPassing() {
        StepView stale = new StepView(STEP, 1, 3, new StepProgress("s1", List.of(), Map.of(),
                StepProgress.Decision.NONE, Optional.empty(), true), Optional.of(STALE), false);
        interact(() -> panel.show(stale));

        clickOn(".step-override-reason").write("the change is a rename");
        FxSync.waitForFxEvents();
        clickOn("Approve without passing");
        FxSync.waitForFxEvents();
        assertEquals(List.of("override the change is a rename"), calls);
    }

    @Test
    void anAgentThatDidNotAnswerLeavesRetryAndTheOverride() {
        CheckProgress unavailable = new CheckProgress("c1", 0, CheckProgress.Status.AGENT_UNAVAILABLE,
                Optional.of("an empty list"), Optional.empty(), Optional.empty());
        interact(() -> panel.show(view(unavailable)));

        assertTrue(lookup("Retry").tryQuery().isPresent());
        clickOn(".step-override-reason").write("agent is down");
        FxSync.waitForFxEvents();
        clickOn("Approve without passing");
        FxSync.waitForFxEvents();
        assertEquals(List.of("override agent is down"), calls);
    }

    @Test
    void aConfirmedBlockerOnThePassedStepOffersTheOverrideNotAnApproval() {
        StepView blocked = new StepView(STEP, 1, 3, new StepProgress("s1", List.of(),
                Map.of("c1", new CheckProgress("c1", 0, CheckProgress.Status.PASSED, Optional.empty(),
                        Optional.empty(), Optional.empty())),
                StepProgress.Decision.NONE, Optional.empty(), false), Optional.of(BLOCKER), false);
        interact(() -> panel.show(blocked));

        assertTrue(lookup(BLOCKER.message()).tryQuery().isPresent());
        assertTrue(lookup("All checks passed — press a to approve this step.").tryQuery().isEmpty(),
                "a press of a cannot approve it");
        clickOn(".step-override-reason").write("fixed in a follow-up");
        FxSync.waitForFxEvents();
        clickOn("Approve without passing");
        FxSync.waitForFxEvents();
        assertEquals(List.of("override fixed in a follow-up"), calls);
    }

    @Test
    void aOnAStaleStepShowsWhyItCannotPass() {
        StepView stale = new StepView(STEP, 1, 3, new StepProgress("s1", List.of(), Map.of(),
                StepProgress.Decision.NONE, Optional.empty(), true), Optional.of(STALE), false);
        interact(() -> {
            panel.show(stale);
            panel.focusUnmet(STALE);
        });

        assertTrue(lookup(STALE.message()).tryQuery().isPresent());
    }

    @Test
    void anExhaustedCheckHasNoAskTheAgentButtonThatDoesNothing() {
        CheckProgress exhausted = new CheckProgress("c1", 1, CheckProgress.Status.EXHAUSTED, Optional.empty(),
                Optional.empty(), Optional.of("Alt because."));
        interact(() -> panel.show(view(exhausted)));

        assertTrue(lookup("Ask the agent").tryQuery().isEmpty());
        assertTrue(lookup("Approve without passing").tryQuery().isPresent());
    }

    /** Visible along with every ancestor -- what a reader could actually see. */
    private static boolean treeVisible(Node node) {
        for (Node at = node; at != null; at = at.getParent()) {
            if (!at.isVisible()) {
                return false;
            }
        }
        return true;
    }

    private static ReviewAnnotation proposal(String id) {
        return new ReviewAnnotation("rs", id, "src/A.java", "n4", "n4", Severity.QUESTION,
                Confidence.HIGH, Optional.of("Title " + id), "Claude", Instant.EPOCH, List.of(), Optional.empty(),
                Optional.empty(), List.of(), List.of(new ReviewAnnotation.Message("Claude", Instant.EPOCH, "why")),
                Optional.empty(), AnnotationStatus.OPEN, Optional.empty(), false, Triage.PROPOSED, Optional.empty());
    }

    @Test
    void notSureOpensAReplyToTheFindingsThreadAndLeavesItProposed() {
        interact(() -> {
            panel.show(view(CheckProgress.fresh("c1")));
            panel.showTriage(List.of(proposal("f1")));
        });
        assertFalse(treeVisible(lookup(".step-finding-reply").query()), "the reply field waits for Not sure");

        clickOn("Not sure");
        FxSync.waitForFxEvents();
        clickOn(".step-finding-reply").write("Is this reachable from the CLI?");
        FxSync.waitForFxEvents();
        clickOn("Send");
        FxSync.waitForFxEvents();

        assertEquals(List.of("message f1 Is this reachable from the CLI?"), calls,
                "posted to the thread, and no triage recorded");
    }
}
