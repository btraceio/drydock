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
}
