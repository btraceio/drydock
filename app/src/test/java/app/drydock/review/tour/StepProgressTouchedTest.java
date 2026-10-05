package app.drydock.review.tour;

import app.drydock.git.UnifiedDiff;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static app.drydock.review.tour.TourFixtures.coveringTour;
import static app.drydock.review.tour.TourFixtures.predict;
import static app.drydock.review.tour.TourFixtures.twoFileDiff;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What counts as the reviewer's progress -- what a whole new tour would discard. */
class StepProgressTouchedTest {

    private final UnifiedDiff diff = twoFileDiff();
    private final TourRecord fresh = TourRecord.fresh(coveringTour(diff), diff);

    @Test
    void aFreshTourHasNothingToDiscard() {
        assertFalse(fresh.progress("s1").touched());
        assertEquals(0, fresh.touchedSteps());
    }

    @Test
    void aDecisionIsProgressWhateverItIs() {
        for (StepProgress.Decision decision : List.of(StepProgress.Decision.PASSED,
                StepProgress.Decision.CHANGES, StepProgress.Decision.OVERRIDDEN)) {
            assertTrue(fresh.progress("s1").withDecision(decision, Optional.empty()).touched(), decision.name());
        }
    }

    @Test
    void anAnsweredCheckIsProgressEvenWithoutADecision() {
        StepProgress answered = fresh.progress("s1").withCheck(
                StepGrading.answerChoice(predict("c1"), fresh.progress("s1").check("c1"), 1));

        assertTrue(answered.touched());
    }

    @Test
    void aWrongAnswerIsProgressToo() {
        StepProgress wrong = fresh.progress("s1").withCheck(
                StepGrading.answerChoice(predict("c1"), fresh.progress("s1").check("c1"), 0));

        assertTrue(wrong.touched(), "an attempt was made, and the explanation seen");
    }

    @Test
    void aRiskAnswerTypedAndSentIsProgress() {
        CheckProgress sent = StepGrading.submitRisk(fresh.progress("s2").check("c2"), "an empty list");

        assertTrue(fresh.progress("s2").withCheck(sent).touched());
    }

    @Test
    void theRecordCountsTouchedStepsNotChecks() {
        TourRecord record = fresh.withProgress(fresh.progress("s1")
                .withDecision(StepProgress.Decision.PASSED, Optional.empty()));

        assertEquals(1, record.touchedSteps());
        assertEquals(2, record.withProgress(record.progress("s2")
                .withDecision(StepProgress.Decision.CHANGES, Optional.empty())).touchedSteps());
    }
}
