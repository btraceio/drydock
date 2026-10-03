package app.drydock.review.tour;

import org.junit.jupiter.api.Test;

import java.util.List;

import static app.drydock.review.tour.TourFixtures.predict;
import static app.drydock.review.tour.TourFixtures.risk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepGradingTest {

    @Test
    void theRightChoicePassesTheCheck() {
        TourCheck check = predict("c1");
        CheckProgress after = StepGrading.answerChoice(check, CheckProgress.fresh("c1"), 1);
        assertEquals(CheckProgress.Status.PASSED, after.status());
        assertTrue(after.settled());
    }

    @Test
    void aWrongChoiceMovesToTheAlternateAndKeepsTheExplanation() {
        TourCheck check = predict("c1");
        CheckProgress after = StepGrading.answerChoice(check, CheckProgress.fresh("c1"), 0);
        assertEquals(CheckProgress.Status.OPEN, after.status());
        assertEquals(1, after.attempt());
        assertEquals("It returns early.", after.lastExplanation().orElseThrow());
    }

    @Test
    void aWrongAnswerOnTheLastVersionExhaustsTheCheck() {
        TourCheck check = predict("c1");
        CheckProgress onAlternate = StepGrading.answerChoice(check, CheckProgress.fresh("c1"), 0);
        CheckProgress after = StepGrading.answerChoice(check, onAlternate, 0);
        assertEquals(CheckProgress.Status.EXHAUSTED, after.status());
        assertEquals("Because.", after.lastExplanation().orElseThrow());
    }

    @Test
    void answeringASettledCheckChangesNothing() {
        TourCheck check = predict("c1");
        CheckProgress passed = StepGrading.answerChoice(check, CheckProgress.fresh("c1"), 1);
        assertEquals(passed, StepGrading.answerChoice(check, passed, 0));
    }

    @Test
    void aRiskAnswerWaitsForTheAgent() {
        CheckProgress after = StepGrading.submitRisk(CheckProgress.fresh("c2"), "an empty list");
        assertEquals(CheckProgress.Status.AWAITING_AGENT, after.status());
        assertEquals("an empty list", after.riskAnswer().orElseThrow());
    }

    @Test
    void holdsAndPartlyPassButDoesNotHoldMovesOn() {
        TourCheck check = risk("c2");
        CheckProgress waiting = StepGrading.submitRisk(CheckProgress.fresh("c2"), "x");
        assertEquals(CheckProgress.Status.PASSED,
                StepGrading.applyRiskVerdict(check, waiting, StepGrading.RiskVerdict.HOLDS, "yes").status());
        CheckProgress partly = StepGrading.applyRiskVerdict(check, waiting, StepGrading.RiskVerdict.PARTLY, "half");
        assertEquals(CheckProgress.Status.PASSED, partly.status());
        assertEquals("half", partly.agentReason().orElseThrow());
        CheckProgress wrong = StepGrading.applyRiskVerdict(check, waiting, StepGrading.RiskVerdict.DOES_NOT_HOLD, "no");
        assertEquals(CheckProgress.Status.OPEN, wrong.status());
        assertEquals(1, wrong.attempt());
    }

    @Test
    void aVoidedCheckIsSettled() {
        assertTrue(StepGrading.voided(CheckProgress.fresh("c1")).settled());
    }

    @Test
    void theGateNamesTheFirstUnsettledCheck() {
        TourStep step = TourFixtures.step("s1", List.of(new TourAnchor("src/A.java", "n1", "n22")),
                predict("c1"), risk("c2"));
        StepProgress progress = StepProgress.fresh(step, AnchorIndex.of(TourFixtures.twoFileDiff()));
        StepGate.Unmet unmet = StepGate.unmet(step, progress).orElseThrow();
        assertEquals(StepGate.Kind.CHECK, unmet.kind());
        assertEquals("c1", unmet.id());
        StepProgress oneDone = progress.withCheck(StepGrading.answerChoice(predict("c1"), progress.check("c1"), 1));
        assertEquals("c2", StepGate.unmet(step, oneDone).orElseThrow().id());
    }

    @Test
    void aStaleStepCannotPass() {
        TourStep step = TourFixtures.step("s1", List.of(new TourAnchor("src/A.java", "n1", "n22")),
                predict("c1"));
        StepProgress stale = StepProgress.fresh(step, AnchorIndex.of(TourFixtures.twoFileDiff())).withStale(true);
        assertEquals(StepGate.Kind.STALE, StepGate.unmet(step, stale).orElseThrow().kind());
    }
}
