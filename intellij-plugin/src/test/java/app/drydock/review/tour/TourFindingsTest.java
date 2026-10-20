package app.drydock.review.tour;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.AnnotationStatus;
import app.drydock.review.Confidence;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.Severity;
import app.drydock.review.Triage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static app.drydock.review.tour.TourFixtures.SCOPE;
import static app.drydock.review.tour.TourFixtures.coveringTour;
import static app.drydock.review.tour.TourFixtures.twoFileDiff;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Findings against {@link TourFixtures#coveringTour}: step {@code s1} covers
 * {@code src/A.java} n1..n22 behind PREDICT {@code c1} (answer 1), step
 * {@code s2} covers {@code src/B.java} o5..o6 behind RISK {@code c2}.
 */
class TourFindingsTest {

    private final UnifiedDiff diff = twoFileDiff();
    private final ReviewTour tour = coveringTour(diff);
    private final AnchorIndex index = AnchorIndex.of(diff);
    private final TourRecord fresh = TourRecord.fresh(tour, diff);

    private static ReviewAnnotation finding(String id, String file, String key, Severity severity, Triage triage,
                                            Optional<String> withheldBy) {
        return new ReviewAnnotation(SCOPE, id, file, key, key, severity, Confidence.HIGH,
                Optional.of("Title " + id), "Claude", Instant.EPOCH, List.of(), Optional.empty(), Optional.empty(),
                List.of(), List.of(), Optional.empty(), AnnotationStatus.OPEN, Optional.empty(), false,
                triage, withheldBy);
    }

    private static ReviewAnnotation withheld(String id, String file, String key, String checkId) {
        return finding(id, file, key, Severity.QUESTION, Triage.PROPOSED, Optional.of(checkId));
    }

    private TourStep s1() {
        return tour.step("s1").orElseThrow();
    }

    /** {@code fresh} with c1 answered by choice {@code choiceIndex} (1 is right, 0 wrong). */
    private TourRecord answered(int choiceIndex) {
        StepProgress p = fresh.progress("s1");
        TourCheck c1 = s1().check("c1").orElseThrow();
        return fresh.withProgress(p.withCheck(StepGrading.answerChoice(c1, p.check("c1"), choiceIndex)));
    }

    @Test
    void onStepKeepsAFindingInsideTheStepAndDropsOneOutside() {
        ReviewAnnotation inside = finding("in", "src/A.java", "n3", Severity.QUESTION, Triage.PROPOSED,
                Optional.empty());
        ReviewAnnotation outside = finding("out", "src/B.java", "o5", Severity.QUESTION, Triage.PROPOSED,
                Optional.empty());

        assertEquals(List.of(inside), TourFindings.onStep(s1(), List.of(inside, outside), index));
    }

    @Test
    void aTriagedWithheldFindingIsNeverHiddenAgain() {
        // Answered, revealed, confirmed -- then the step is re-issued and its
        // check starts fresh. The reviewer's triage must not vanish with it.
        ReviewAnnotation confirmed = finding("f1", "src/A.java", "n3", Severity.QUESTION, Triage.CONFIRMED,
                Optional.of("c1"));

        assertFalse(TourFindings.hidden(confirmed, fresh));
    }

    @Test
    void aWithheldFindingIsHiddenBeforeItsCheckIsAnswered() {
        assertTrue(TourFindings.hidden(withheld("f1", "src/A.java", "n3", "c1"), fresh));
    }

    @Test
    void aWithheldFindingIsRevealedByAWrongAnswer() {
        TourRecord wrong = answered(0);
        assertEquals(1, wrong.progress("s1").check("c1").attempt());

        assertFalse(TourFindings.hidden(withheld("f1", "src/A.java", "n3", "c1"), wrong));
    }

    @Test
    void aWithheldFindingIsRevealedByARightAnswer() {
        assertFalse(TourFindings.hidden(withheld("f1", "src/A.java", "n3", "c1"), answered(1)));
    }

    @Test
    void aWithheldFindingIsRevealedWhenItsStepIsOverridden() {
        TourRecord overridden = fresh.withProgress(fresh.progress("s1")
                .withDecision(StepProgress.Decision.OVERRIDDEN, Optional.of("read it twice")));

        assertFalse(TourFindings.hidden(withheld("f1", "src/A.java", "n3", "c1"), overridden));
    }

    @Test
    void aDismissedWithheldFindingIsNotHidden() {
        ReviewAnnotation dismissed = withheld("f1", "src/A.java", "n3", "c1").withTriage(Triage.DISMISSED);

        assertFalse(TourFindings.hidden(dismissed, fresh));
    }

    @Test
    void aFindingWithheldByNothingIsNeverHidden() {
        ReviewAnnotation plain = finding("f1", "src/A.java", "n3", Severity.QUESTION, Triage.PROPOSED,
                Optional.empty());

        assertFalse(TourFindings.hidden(plain, fresh));
    }

    @Test
    void blockersIgnoreDismissedResolvedAndNonBlockingFindings() {
        ReviewAnnotation proposed = finding("b1", "src/A.java", "n3", Severity.BLOCKING, Triage.PROPOSED,
                Optional.empty());
        ReviewAnnotation confirmed = finding("b2", "src/A.java", "n21", Severity.BLOCKING, Triage.CONFIRMED,
                Optional.empty());
        ReviewAnnotation dismissed = finding("b3", "src/A.java", "n3", Severity.BLOCKING, Triage.DISMISSED,
                Optional.empty());
        ReviewAnnotation resolved = finding("b4", "src/A.java", "n3", Severity.BLOCKING, Triage.CONFIRMED,
                Optional.empty()).withStatus(AnnotationStatus.RESOLVED);
        ReviewAnnotation question = finding("q1", "src/A.java", "n3", Severity.QUESTION, Triage.CONFIRMED,
                Optional.empty());

        assertEquals(List.of(proposed, confirmed),
                TourFindings.blockers(List.of(proposed, confirmed, dismissed, resolved, question)));
    }

    @Test
    void theBannerShowsForAnOpenBlockerUntilTheReviewerReviewsAnyway() {
        List<ReviewAnnotation> findings = List.of(finding("b1", "src/A.java", "n3", Severity.BLOCKING,
                Triage.PROPOSED, Optional.empty()));

        assertTrue(TourFindings.needsBanner(fresh, findings));
        assertFalse(TourFindings.needsBanner(fresh.withReviewAnyway(true), findings));
        assertFalse(TourFindings.needsBanner(fresh, List.of()));
    }

    @Test
    void aVisibleProposalOnTheStepIsTheNextRequirementOnceChecksPass() {
        TourRecord passed = answered(1);
        ReviewAnnotation proposal = withheld("f1", "src/A.java", "n3", "c1");

        Optional<StepGate.Unmet> unmet = StepGate.unmet(s1(), passed.progress("s1"), List.of(proposal), passed);

        assertEquals(StepGate.Kind.TRIAGE, unmet.orElseThrow().kind());
        assertEquals("f1", unmet.orElseThrow().id());
    }

    @Test
    void theCheckStillComesBeforeTriage() {
        ReviewAnnotation proposal = finding("f1", "src/A.java", "n3", Severity.QUESTION, Triage.PROPOSED,
                Optional.empty());

        Optional<StepGate.Unmet> unmet = StepGate.unmet(s1(), fresh.progress("s1"), List.of(proposal), fresh);

        assertEquals(StepGate.Kind.CHECK, unmet.orElseThrow().kind());
    }

    @Test
    void aConfirmedBlockerOnTheStepStopsIt() {
        TourRecord passed = answered(1);
        ReviewAnnotation blocker = finding("b1", "src/A.java", "n3", Severity.BLOCKING, Triage.CONFIRMED,
                Optional.empty());

        Optional<StepGate.Unmet> unmet = StepGate.unmet(s1(), passed.progress("s1"), List.of(blocker), passed);

        assertEquals(StepGate.Kind.BLOCKER, unmet.orElseThrow().kind());
        assertEquals("A confirmed blocking finding is open here: request changes (r) or approve without passing.",
                unmet.orElseThrow().message());
    }

    @Test
    void nothingStandsInTheWayOnceEveryFindingIsTriaged() {
        TourRecord passed = answered(1);
        ReviewAnnotation confirmedQuestion = finding("q1", "src/A.java", "n3", Severity.QUESTION,
                Triage.CONFIRMED, Optional.empty());
        ReviewAnnotation dismissedBlocker = finding("b1", "src/A.java", "n21", Severity.BLOCKING,
                Triage.DISMISSED, Optional.empty());

        assertEquals(Optional.empty(), StepGate.unmet(s1(), passed.progress("s1"),
                List.of(confirmedQuestion, dismissedBlocker), passed));
    }

    @Test
    void theValidatorRejectsAWithheldFindingWhoseCheckIsOnAStepNotCoveringIt() {
        List<String> errors = TourValidator.validate(tour, diff,
                List.of(withheld("f1", "src/B.java", "o5", "c1")));

        assertTrue(errors.contains(
                "finding f1 is withheld by check c1, which is not on a step covering src/B.java o5"),
                errors.toString());
    }

    @Test
    void theValidatorAcceptsAWithheldFindingOnItsChecksStep() {
        assertEquals(List.of(), TourValidator.validate(tour, diff,
                List.of(withheld("f1", "src/A.java", "n3", "c1"))));
    }

    @Test
    void theValidatorRejectsAFindingWithheldByACheckTheTourDoesNotHave() {
        List<String> errors = TourValidator.validate(tour, diff,
                List.of(withheld("f1", "src/A.java", "n3", "c9")));

        assertTrue(errors.contains("finding f1 is withheld by check c9, which is not in the tour"),
                errors.toString());
    }

    @Test
    void theValidatorIgnoresADismissedWithheldFinding() {
        ReviewAnnotation dismissed = withheld("f1", "src/B.java", "o5", "c1").withTriage(Triage.DISMISSED);

        assertEquals(List.of(), TourValidator.validate(tour, diff, List.of(dismissed)));
    }

    @Test
    void theValidatorRejectsAFindingWithheldByATraceCheck() {
        TourCheck alternate = new TourCheck("t1_alt", TourCheck.Kind.PREDICT, "Alternate?",
                List.of(TourFixtures.choice("yes"), TourFixtures.choice("no")), OptionalInt.of(1), "Because.",
                List.of());
        TourCheck trace = new TourCheck("t1", TourCheck.Kind.TRACE, "Who calls this?",
                List.of(TourFixtures.choice("f"), TourFixtures.choice("g")), OptionalInt.of(0), "f does.",
                List.of(alternate));
        ReviewTour traced = new ReviewTour(SCOPE, TourFingerprint.of(diff), List.of(
                TourFixtures.step("s1", List.of(new TourAnchor("src/A.java", "n1", "n22")), trace),
                tour.step("s2").orElseThrow()));

        List<String> errors = TourValidator.validate(traced, diff, List.of(
                withheld("f1", "src/A.java", "n3", "t1"),
                withheld("f2", "src/A.java", "n3", "t1_alt")));

        assertTrue(errors.contains("finding f1 is withheld by check t1, which is a trace check; "
                + "only predict or risk checks withhold findings"), errors.toString());
        assertFalse(errors.stream().anyMatch(error -> error.startsWith("finding f2")),
                "the predict alternate's own kind counts: " + errors);
    }
}
