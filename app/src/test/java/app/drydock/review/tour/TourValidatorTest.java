package app.drydock.review.tour;

import app.drydock.git.UnifiedDiff;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static app.drydock.review.tour.TourFixtures.SCOPE;
import static app.drydock.review.tour.TourFixtures.add;
import static app.drydock.review.tour.TourFixtures.file;
import static app.drydock.review.tour.TourFixtures.hunk;
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

    /** A diagram with its limits: readable in the panel, and a drawing that survives the font. */
    @Test
    void aWellFormedDiagramIsValid() {
        TourStep withDiagram = new TourStep("s1", "Step s1", "Why s1 exists.",
                List.of(new TourAnchor("src/A.java", "n1", "n22")), List.of(), List.of(predict("c1")),
                Optional.of(new TourDiagram("The fan-in before the fix",
                        List.of("  loadConfig()\n    ├─ parse()", "    └─ validate()"))));
        TourStep otherFile = new TourStep("s2", "Step s2", "Why s2 exists.",
                List.of(new TourAnchor("src/B.java", "o5", "o6")), List.of(), List.of(risk("c2")));
        assertEquals(List.of(), TourValidator.validate(tour(diff, withDiagram, otherFile), diff));
    }

    @Test
    void aDiagramOverItsLimitsIsRejectedWithTheStageNamed() {
        String longLine = "x".repeat(TourValidator.MAX_DIAGRAM_LINE + 1);
        TourStep over = new TourStep("s1", "Step s1", "Why s1 exists.",
                List.of(new TourAnchor("src/A.java", "n1", "n22")), List.of(), List.of(predict("c1")),
                Optional.of(new TourDiagram("caption",
                        List.of(longLine, "\tindented", " ", "s4", "s5", "s6"))));
        List<String> errors = TourValidator.validate(tour(diff, over), diff);

        assertTrue(anyContains(errors, "stage 1: has a line longer than"), errors.toString());
        assertTrue(anyContains(errors, "stage 2: uses tabs"), errors.toString());
        assertTrue(anyContains(errors, "stage 3: is blank"), errors.toString());
        assertTrue(anyContains(errors, "more than " + TourValidator.MAX_DIAGRAM_STAGES + " stages"),
                errors.toString());
        assertTrue(anyContains(errors, "s1 diagram"), "every diagram error names its step");
    }

    @Test
    void aDiagramCaptionOverItsCapIsRejected() {
        TourStep over = new TourStep("s1", "Step s1", "Why s1 exists.",
                List.of(new TourAnchor("src/A.java", "n1", "n22")), List.of(), List.of(predict("c1")),
                Optional.of(new TourDiagram("c".repeat(TourValidator.MAX_DIAGRAM_CAPTION + 1),
                        List.of("drawing"))));
        assertTrue(anyContains(TourValidator.validate(tour(diff, over), diff),
                "caption is longer than"), "caption cap is enforced");
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

    // ---- a PREDICT needs something to read ----------------------------------

    /** A file that is entirely added: rows n1..n3, no removed or unchanged row. */
    private static UnifiedDiff allAddedDiff() {
        return new UnifiedDiff(List.of(file("src/New.java", hunk(add(1, "a"), add(2, "b"), add(3, "c")))));
    }

    private static TourCheck trace(String id) {
        TourCheck alternate = new TourCheck(id + "_alt", TourCheck.Kind.TRACE, "Which line?",
                List.of(choice("one"), choice("two")), OptionalInt.of(0), "Line one.", List.of());
        return new TourCheck(id, TourCheck.Kind.TRACE, "Which line guards it?",
                List.of(choice("one"), choice("two")), OptionalInt.of(0), "Line one.", List.of(alternate));
    }

    @Test
    void aPredictOnAStepWhoseAnchorsAreAllAddedRowsIsRejectedWithTheWayOut() {
        UnifiedDiff added = allAddedDiff();
        ReviewTour tour = tour(added,
                step("s1", List.of(new TourAnchor("src/New.java", "n1", "n3")), predict("c1")));

        List<String> errors = TourValidator.validate(tour, added);

        assertTrue(anyContains(errors, "step s1: check c1 is a PREDICT"), errors.toString());
        assertTrue(anyContains(errors, "nothing to read"), errors.toString());
        assertTrue(anyContains(errors, "TRACE"), errors.toString());
    }

    @Test
    void aTraceOrARiskOnTheSameStepIsFine() {
        UnifiedDiff added = allAddedDiff();
        List<TourAnchor> anchors = List.of(new TourAnchor("src/New.java", "n1", "n3"));

        assertEquals(List.of(), TourValidator.validate(tour(added, step("s1", anchors, trace("c1"))), added));
        assertEquals(List.of(), TourValidator.validate(tour(added, step("s1", anchors, risk("c1"))), added));
    }

    @Test
    void aPredictWhoseAnchorsIncludeAnUnchangedOrRemovedRowIsFine() {
        // src/A.java hunk 1: ctx n20, DEL o20, ADD n21, ctx n22.
        assertEquals(List.of(), TourValidator.validate(coveringTour(diff), diff));
        ReviewTour deletionOnly = tour(diff,
                step("s1", List.of(new TourAnchor("src/A.java", "n1", "n22")), risk("c1")),
                step("s2", List.of(new TourAnchor("src/B.java", "o5", "o6")), predict("c2")));
        assertEquals(List.of(), TourValidator.validate(deletionOnly, diff));
    }

    @Test
    void oneAnchorWithSomethingToReadIsEnoughForTheStep() {
        UnifiedDiff mixed = new UnifiedDiff(List.of(
                file("src/New.java", hunk(add(1, "a"), add(2, "b"))),
                file("src/Old.java", hunk(TourFixtures.ctx(1, 1, "x"), add(2, "y"), TourFixtures.ctx(2, 3, "z")))));
        ReviewTour tour = tour(mixed, step("s1", List.of(
                new TourAnchor("src/New.java", "n1", "n2"), new TourAnchor("src/Old.java", "n1", "n3")),
                predict("c1")));

        assertEquals(List.of(), TourValidator.validate(tour, mixed));
    }

    @Test
    void anAlternatePredictIsHeldToTheSameRuleBecauseItHidesTheRowsAgain() {
        UnifiedDiff added = allAddedDiff();
        TourCheck predictAlternate = new TourCheck("c1_alt", TourCheck.Kind.PREDICT, "Again?",
                List.of(choice("a"), choice("b")), OptionalInt.of(0), "Because.", List.of());
        TourCheck check = new TourCheck("c1", TourCheck.Kind.TRACE, "Which line?",
                List.of(choice("one"), choice("two")), OptionalInt.of(0), "One.", List.of(predictAlternate));

        List<String> errors = TourValidator.validate(
                tour(added, step("s1", List.of(new TourAnchor("src/New.java", "n1", "n3")), check)), added);

        assertTrue(anyContains(errors, "check c1_alt is a PREDICT"), errors.toString());
        assertTrue(errors.stream().noneMatch(error -> error.contains("check c1 is a PREDICT")), errors.toString());
    }
}
