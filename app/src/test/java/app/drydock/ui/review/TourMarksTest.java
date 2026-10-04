package app.drydock.ui.review;

import app.drydock.git.UnifiedDiff;
import app.drydock.git.UnifiedDiff.Line;
import app.drydock.review.tour.ReviewTour;
import app.drydock.review.tour.StepGrading;
import app.drydock.review.tour.TourAnchor;
import app.drydock.review.tour.TourCheck;
import app.drydock.review.tour.TourFingerprint;
import app.drydock.review.tour.TourRecord;
import app.drydock.review.tour.TourStep;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TourMarksTest {

    private static Line ctx(int n, String text) {
        return new Line(Line.Kind.CONTEXT, OptionalInt.of(n), OptionalInt.of(n), text);
    }

    private static Line add(int n, String text) {
        return new Line(Line.Kind.ADD, OptionalInt.empty(), OptionalInt.of(n), text);
    }

    /** Rows: n1 ctx, n2 add (step s1), n3 ctx, n4 add (step s2), n5 ctx. */
    private static final UnifiedDiff DIFF = new UnifiedDiff(List.of(new UnifiedDiff.FileDiff("src/A.java", "M",
            2, 0, false, false, List.of(new UnifiedDiff.Hunk("@@",
            List.of(ctx(1, "a"), add(2, "b"), ctx(3, "c"), add(4, "d"), ctx(5, "e")))))));

    private static TourCheck predict(String id) {
        TourCheck alternate = new TourCheck(id + "_alt", TourCheck.Kind.PREDICT, "p",
                List.of(new TourCheck.Choice("x", Optional.empty()), new TourCheck.Choice("y", Optional.empty())),
                OptionalInt.of(0), "e", List.of());
        return new TourCheck(id, TourCheck.Kind.PREDICT, "p",
                List.of(new TourCheck.Choice("x", Optional.empty()), new TourCheck.Choice("y", Optional.empty())),
                OptionalInt.of(0), "e", List.of(alternate));
    }

    private static TourRecord record() {
        ReviewTour tour = new ReviewTour("rs", TourFingerprint.of(DIFF), List.of(
                new TourStep("s1", "One", "n", List.of(new TourAnchor("src/A.java", "n1", "n3")), List.of(),
                        List.of(predict("c1"))),
                new TourStep("s2", "Two", "n", List.of(new TourAnchor("src/A.java", "n4", "n4")), List.of(),
                        List.of(predict("c2")))));
        return TourRecord.fresh(tour, DIFF);
    }

    @Test
    void currentStepRowsAreCurrentAndOtherStepsChangedRowsCarryTheirNumber() {
        TourMarks marks = TourMarks.of(record(), DIFF, "s1");
        assertEquals(StepMark.Strength.CURRENT, marks.markAt("src/A.java", "n1").orElseThrow().strength());
        StepMark other = marks.markAt("src/A.java", "n4").orElseThrow();
        assertEquals(StepMark.Strength.OTHER, other.strength());
        assertEquals(2, other.stepNumber());
        assertTrue(other.tagged());
        assertTrue(marks.markAt("src/A.java", "n5").isEmpty(), "unchanged and in no current anchor");
    }

    @Test
    void anUnansweredPredictHidesTheCurrentStepsAddedRowsOnly() {
        TourMarks marks = TourMarks.of(record(), DIFF, "s1");
        assertTrue(marks.markAt("src/A.java", "n2").orElseThrow().hidden());
        assertFalse(marks.markAt("src/A.java", "n1").orElseThrow().hidden(), "context stays readable");
    }

    @Test
    void answeringThePredictRevealsTheRows() {
        TourRecord record = record();
        record = record.withProgress(record.progress("s1").withCheck(
                StepGrading.answerChoice(predict("c1"), record.progress("s1").check("c1"), 0)));
        assertFalse(TourMarks.of(record, DIFF, "s1").markAt("src/A.java", "n2").orElseThrow().hidden());
    }

    @Test
    void onlyTheFirstRowOfARunIsTagged() {
        TourMarks marks = TourMarks.of(record(), DIFF, "s1");
        assertTrue(marks.markAt("src/A.java", "n1").orElseThrow().tagged());
        assertFalse(marks.markAt("src/A.java", "n2").orElseThrow().tagged());
    }

    @Test
    void theFirstHiddenRowStartsTheBand() {
        TourMarks marks = TourMarks.of(record(), DIFF, "s1");
        assertTrue(marks.markAt("src/A.java", "n2").orElseThrow().bandStart());
        assertFalse(marks.markAt("src/A.java", "n1").orElseThrow().bandStart());
    }

    @Test
    void anotherStepsUnansweredPredictHidesItsAddedRowsToo() {
        // On s1, s2's PREDICT is still open: reading n4 here would answer it.
        TourMarks marks = TourMarks.of(record(), DIFF, "s1");
        StepMark other = marks.markAt("src/A.java", "n4").orElseThrow();

        assertEquals(StepMark.Strength.OTHER, other.strength());
        assertTrue(other.hidden());
        assertTrue(other.bandStart());
        assertEquals("step 2 — hidden until you answer", other.bandLabel());
        assertEquals("hidden until you answer", marks.markAt("src/A.java", "n2").orElseThrow().bandLabel(),
                "the current step's band needs no number");
    }

    @Test
    void anotherStepsAnsweredPredictLeavesItsRowsReadable() {
        TourRecord record = record();
        record = record.withProgress(record.progress("s2").withCheck(
                StepGrading.answerChoice(predict("c2"), record.progress("s2").check("c2"), 0)));

        assertFalse(TourMarks.of(record, DIFF, "s1").markAt("src/A.java", "n4").orElseThrow().hidden());
    }

    @Test
    void aRowAlsoInAnotherStepWithAPendingPredictStaysHiddenOnTheCurrentStep() {
        // s1 (current, answered) spans n1..n3; s2 (PREDICT open) spans n2..n4.
        // n2 is s1's row, but showing it would answer s2's check.
        ReviewTour tour = new ReviewTour("rs", TourFingerprint.of(DIFF), List.of(
                new TourStep("s1", "One", "n", List.of(new TourAnchor("src/A.java", "n1", "n3")), List.of(),
                        List.of(predict("c1"))),
                new TourStep("s2", "Two", "n", List.of(new TourAnchor("src/A.java", "n2", "n4")), List.of(),
                        List.of(predict("c2")))));
        TourRecord record = TourRecord.fresh(tour, DIFF);
        record = record.withProgress(record.progress("s1").withCheck(
                StepGrading.answerChoice(predict("c1"), record.progress("s1").check("c1"), 0)));

        StepMark shared = TourMarks.of(record, DIFF, "s1").markAt("src/A.java", "n2").orElseThrow();

        assertEquals(StepMark.Strength.CURRENT, shared.strength(), "still the current step's row");
        assertTrue(shared.hidden());
        assertTrue(shared.bandStart());
        assertEquals("step 2 — hidden until you answer", shared.bandLabel(),
                "the band names the step whose answer it waits for");
    }

    /** s1 makes two claims: n1..n2 ("first") and n3 ("second"); s2 is as in {@link #record()}. */
    private static TourRecord claimsRecord(boolean answered) {
        ReviewTour tour = new ReviewTour("rs", TourFingerprint.of(DIFF), List.of(
                new TourStep("s1", "One", "n", List.of(
                        new TourAnchor("src/A.java", "n1", "n2", "first"),
                        new TourAnchor("src/A.java", "n3", "n3", "second")), List.of(),
                        List.of(predict("c1"))),
                new TourStep("s2", "Two", "n", List.of(new TourAnchor("src/A.java", "n4", "n4")), List.of(),
                        List.of(predict("c2")))));
        TourRecord record = TourRecord.fresh(tour, DIFF);
        if (answered) {
            record = record.withProgress(record.progress("s1").withCheck(
                    StepGrading.answerChoice(predict("c1"), record.progress("s1").check("c1"), 0)));
        }
        return record;
    }

    @Test
    void aStepWithNoNotesMakesNoClaims() {
        TourRecord record = record();
        record = record.withProgress(record.progress("s1").withCheck(
                StepGrading.answerChoice(predict("c1"), record.progress("s1").check("c1"), 0)));
        StepMark mark = TourMarks.of(record, DIFF, "s1").markAt("src/A.java", "n2").orElseThrow();
        assertTrue(mark.claim().isEmpty());
        assertTrue(mark.callouts().isEmpty());
    }

    @Test
    void claimsStayHiddenWhileThePredictIsOpenBecauseTheyAreTheAnswer() {
        TourMarks marks = TourMarks.of(claimsRecord(false), DIFF, "s1");
        for (String key : List.of("n1", "n2", "n3")) {
            StepMark mark = marks.markAt("src/A.java", key).orElseThrow();
            assertTrue(mark.claim().isEmpty(), key);
            assertTrue(mark.callouts().isEmpty(), key);
        }
    }

    @Test
    void anAnsweredStepNumbersItsRangesAndPutsEachClaimUnderItsLastRow() {
        TourMarks marks = TourMarks.of(claimsRecord(true), DIFF, "s1");

        StepMark n1 = marks.markAt("src/A.java", "n1").orElseThrow();
        assertEquals(new StepMark.Claim(0, 1, true, true), n1.claim().orElseThrow(), "first row: the badge row");
        assertTrue(n1.callouts().isEmpty(), "the claim is not under its first row");

        StepMark n2 = marks.markAt("src/A.java", "n2").orElseThrow();
        assertEquals(new StepMark.Claim(0, 1, true, false), n2.claim().orElseThrow());
        assertEquals(List.of(new StepMark.Callout(0, 1, true, "first")), n2.callouts());

        StepMark n3 = marks.markAt("src/A.java", "n3").orElseThrow();
        assertEquals(new StepMark.Claim(1, 2, false, true), n3.claim().orElseThrow());
        assertEquals(List.of(new StepMark.Callout(1, 2, false, "second")), n3.callouts());
    }

    @Test
    void theActiveAnchorMovesTheActiveFlagAndNothingElse() {
        TourMarks marks = TourMarks.of(claimsRecord(true), DIFF, "s1", 1);

        assertFalse(marks.markAt("src/A.java", "n2").orElseThrow().claim().orElseThrow().active());
        assertEquals(List.of(new StepMark.Callout(0, 1, false, "first")),
                marks.markAt("src/A.java", "n2").orElseThrow().callouts());
        assertTrue(marks.markAt("src/A.java", "n3").orElseThrow().claim().orElseThrow().active());
    }

    @Test
    void anotherStepsRowsNeverCarryTheCurrentStepsClaims() {
        StepMark other = TourMarks.of(claimsRecord(true), DIFF, "s1").markAt("src/A.java", "n4").orElseThrow();
        assertTrue(other.claim().isEmpty());
        assertTrue(other.callouts().isEmpty());
    }
}
