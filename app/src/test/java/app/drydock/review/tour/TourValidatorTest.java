package app.drydock.review.tour;

import app.drydock.git.UnifiedDiff;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static app.drydock.review.tour.TourFixtures.SCOPE;
import static app.drydock.review.tour.TourFixtures.choice;
import static app.drydock.review.tour.TourFixtures.coveringTour;
import static app.drydock.review.tour.TourFixtures.deletedFile;
import static app.drydock.review.tour.TourFixtures.predict;
import static app.drydock.review.tour.TourFixtures.risk;
import static app.drydock.review.tour.TourFixtures.step;
import static app.drydock.review.tour.TourFixtures.twoFileDiff;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TourValidatorTest {

    private final UnifiedDiff diff = twoFileDiff();

    private static ReviewTour tour(UnifiedDiff diff, TourStep... steps) {
        return new ReviewTour(SCOPE, TourFingerprint.of(diff), List.of(steps));
    }

    private static boolean anyContains(List<String> errors, String fragment) {
        return errors.stream().anyMatch(error -> error.contains(fragment));
    }

    @Test
    void aTourCoveringEveryChangedRowIsValid() {
        assertEquals(List.of(), TourValidator.validate(coveringTour(diff), diff));
    }

    @Test
    void anUncoveredHunkIsNamedWithItsRows() {
        ReviewTour partial = tour(diff,
                step("s1", List.of(new TourAnchor("src/A.java", "n1", "n4")), predict("c1")));
        List<String> errors = TourValidator.validate(partial, diff);
        assertTrue(anyContains(errors, "hunk h_src/A.java_1: rows o20..n21 are in no step"), errors.toString());
        assertTrue(anyContains(errors, "hunk h_src/B.java_0: rows o5..o6 are in no step"), errors.toString());
    }

    @Test
    void aDeletedFileIsCoveredByOldKeys() {
        UnifiedDiff deleted = new UnifiedDiff(List.of(deletedFile("src/Gone.java", "a", "b")));
        ReviewTour covering = tour(deleted,
                step("s1", List.of(new TourAnchor("src/Gone.java", "o1", "o2")), predict("c1")));
        assertEquals(List.of(), TourValidator.validate(covering, deleted));
    }

    @Test
    void anAnchorThatIsNotARowOfTheDiffIsRejected() {
        ReviewTour bad = tour(diff,
                step("s1", List.of(new TourAnchor("src/A.java", "n1", "n99")), predict("c1")),
                step("s2", List.of(new TourAnchor("src/A.java", "o20", "n21"), new TourAnchor("src/B.java", "o5", "o6")),
                        risk("c2")));
        assertTrue(anyContains(TourValidator.validate(bad, diff),
                "step s1: anchor src/A.java n1..n99 is not a range of rows of the diff"));
    }

    @Test
    void aStepWithoutChecksIsRejected() {
        ReviewTour bad = tour(diff, new TourStep("s1", "t", "n",
                List.of(new TourAnchor("src/A.java", "n1", "n22"), new TourAnchor("src/B.java", "o5", "o6")),
                List.of(), List.of()));
        assertTrue(anyContains(TourValidator.validate(bad, diff), "step s1: needs at least one check"));
    }

    @Test
    void aCheckWithoutAnAlternateIsRejected() {
        TourCheck lonely = new TourCheck("c1", TourCheck.Kind.PREDICT, "p",
                List.of(choice("a"), choice("b")), OptionalInt.of(0), "e", List.of());
        ReviewTour bad = tour(diff, step("s1",
                List.of(new TourAnchor("src/A.java", "n1", "n22"), new TourAnchor("src/B.java", "o5", "o6")), lonely));
        assertTrue(anyContains(TourValidator.validate(bad, diff), "check c1: needs at least one alternate"));
    }

    @Test
    void anAnswerOutsideTheChoicesIsRejected() {
        TourCheck alternate = new TourCheck("c1_alt", TourCheck.Kind.PREDICT, "p",
                List.of(choice("a"), choice("b")), OptionalInt.of(0), "e", List.of());
        TourCheck off = new TourCheck("c1", TourCheck.Kind.PREDICT, "p",
                List.of(choice("a"), choice("b")), OptionalInt.of(2), "e", List.of(alternate));
        ReviewTour bad = tour(diff, step("s1",
                List.of(new TourAnchor("src/A.java", "n1", "n22"), new TourAnchor("src/B.java", "o5", "o6")), off));
        assertTrue(anyContains(TourValidator.validate(bad, diff),
                "check c1: answer 2 is not one of its 2 choices (0-based)"));
    }

    @Test
    void aRiskCheckWithChoicesIsRejected() {
        TourCheck alternate = new TourCheck("c1_alt", TourCheck.Kind.RISK, "p", List.of(), OptionalInt.empty(), "e",
                List.of());
        TourCheck bad = new TourCheck("c1", TourCheck.Kind.RISK, "p", List.of(choice("a"), choice("b")),
                OptionalInt.empty(), "e", List.of(alternate));
        ReviewTour tour = tour(diff, step("s1",
                List.of(new TourAnchor("src/A.java", "n1", "n22"), new TourAnchor("src/B.java", "o5", "o6")), bad));
        assertTrue(anyContains(TourValidator.validate(tour, diff), "check c1: a risk check takes no choices"));
    }

    @Test
    void duplicateIdsAreRejected() {
        ReviewTour dup = tour(diff,
                step("s1", List.of(new TourAnchor("src/A.java", "n1", "n22")), predict("c1")),
                step("s1", List.of(new TourAnchor("src/B.java", "o5", "o6")), predict("c1")));
        List<String> errors = TourValidator.validate(dup, diff);
        assertTrue(anyContains(errors, "step id s1 is used twice"), errors.toString());
        assertTrue(anyContains(errors, "check id c1 is used twice"), errors.toString());
    }

    @Test
    void tooManyStepsIsRejected() {
        List<TourStep> steps = new ArrayList<>();
        for (int i = 0; i <= TourValidator.MAX_STEPS; i++) {
            steps.add(step("s" + i, List.of(new TourAnchor("src/A.java", "n1", "n22"),
                    new TourAnchor("src/B.java", "o5", "o6")), predict("c" + i)));
        }
        assertTrue(anyContains(TourValidator.validate(tour(diff, steps.toArray(TourStep[]::new)), diff),
                "a tour has at most 40 steps"));
    }

    @Test
    void anOverlongNarrativeIsRejected() {
        TourStep longOne = new TourStep("s1", "t", "x".repeat(TourValidator.MAX_NARRATIVE + 1),
                List.of(new TourAnchor("src/A.java", "n1", "n22"), new TourAnchor("src/B.java", "o5", "o6")),
                List.of(), List.of(predict("c1")));
        assertTrue(anyContains(TourValidator.validate(tour(diff, longOne), diff),
                "step s1: narrative is longer than 1000 characters"));
    }

    @Test
    void anImpactNoteWithoutALineIsRejected() {
        TourStep noted = new TourStep("s1", "t", "n",
                List.of(new TourAnchor("src/A.java", "n1", "n22"), new TourAnchor("src/B.java", "o5", "o6")),
                List.of(new ImpactNote("src/C.java", 0, "assumes non-null")), List.of(predict("c1")));
        assertTrue(anyContains(TourValidator.validate(tour(diff, noted), diff),
                "step s1: impact note on src/C.java needs a line of 1 or more"));
    }
}
