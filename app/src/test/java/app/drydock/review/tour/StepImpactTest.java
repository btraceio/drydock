package app.drydock.review.tour;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.ChangeGraph;
import app.drydock.review.OutOfDiffFanIn;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A step's measured impact (spec §6): who calls what it declares from
 * outside the change, which other steps it is wired to, what it calls that
 * the change does not declare, and whether it changed a declaration that
 * unedited call sites still use.
 */
class StepImpactTest {

    private static final String A = "src/Alpha.java";
    private static final String B = "src/Beta.java";

    private final UnifiedDiff diff = diff();
    private final ChangeGraph graph = ChangeGraph.of(diff);

    /** Step 1 anchors foo's declaration and body; step 2 is the whole of Beta, which calls foo. */
    private final TourStep fooStep = step("s1", new TourAnchor(A, "n2", "n4"));
    private final TourStep betaStep = step("s2", new TourAnchor(B, "n1", "n5"));
    private final ReviewTour tour = new ReviewTour("rs_1", "fp", List.of(fooStep, betaStep));

    @Test
    void outOfDiffCallersOfTheStepsDeclarationsAreListedByFileThenLine() {
        OutOfDiffFanIn.Result fanIn = new OutOfDiffFanIn.Result(Map.of("foo", List.of(
                new OutOfDiffFanIn.Occurrence("src/Yankee.java", 3, "y.foo();", false),
                new OutOfDiffFanIn.Occurrence("src/Xray.java", 9, "x.foo();", true),
                new OutOfDiffFanIn.Occurrence("src/Xray.java", 2, "foo();", true))), Optional.empty());

        StepImpact impact = StepImpact.of(fooStep, tour, diff, graph, fanIn);

        assertEquals(List.of(
                        new StepImpact.Caller("foo", "src/Xray.java", 2, "foo();", true),
                        new StepImpact.Caller("foo", "src/Xray.java", 9, "x.foo();", true),
                        new StepImpact.Caller("foo", "src/Yankee.java", 3, "y.foo();", false)),
                impact.calledFromOutside());
        assertEquals(Optional.empty(), impact.unavailableReason());
    }

    @Test
    void aReferencingHunkInAnotherStepIsACalledByEdgeToThatStep() {
        StepImpact impact = StepImpact.of(fooStep, tour, diff, graph, new OutOfDiffFanIn.Result(Map.of(), false));

        assertTrue(impact.inChange().contains(
                        new StepImpact.InChange("foo", StepImpact.Direction.CALLED_BY, "s2", 2)),
                "inChange was " + impact.inChange());
    }

    @Test
    void aReferenceToAnotherStepsDeclarationIsACallsEdgeToThatStep() {
        StepImpact impact = StepImpact.of(betaStep, tour, diff, graph, new OutOfDiffFanIn.Result(Map.of(), false));

        assertTrue(impact.inChange().contains(
                        new StepImpact.InChange("foo", StepImpact.Direction.CALLS, "s1", 1)),
                "inChange was " + impact.inChange());
    }

    @Test
    void aChangedDeclarationWithUneditedCallersIsFlaggedWithTheirCount() {
        OutOfDiffFanIn.Result fanIn = new OutOfDiffFanIn.Result(Map.of("foo", List.of(
                new OutOfDiffFanIn.Occurrence("src/Yankee.java", 3, "y.foo();", false),
                new OutOfDiffFanIn.Occurrence("src/Xray.java", 9, "x.foo();", true))), Optional.empty());

        StepImpact impact = StepImpact.of(fooStep, tour, diff, graph, fanIn);

        assertEquals(List.of(new StepImpact.SignatureFlag("foo", A, "n2", 2)), impact.signatureFlags());
    }

    @Test
    void aDeclarationNobodyCallsIsNotFlagged() {
        StepImpact impact = StepImpact.of(fooStep, tour, diff, graph, new OutOfDiffFanIn.Result(Map.of(), false));

        assertEquals(List.of(), impact.signatureFlags());
    }

    @Test
    void anUnavailableScanCarriesItsReasonInsteadOfAnEmptyAnswer() {
        OutOfDiffFanIn.Result fanIn = new OutOfDiffFanIn.Result(Map.of(),
                Optional.of("git grep timed out after 30 s"));

        StepImpact impact = StepImpact.of(fooStep, tour, diff, graph, fanIn);

        assertEquals(Optional.of("git grep timed out after 30 s"), impact.unavailableReason());
        assertEquals(List.of(), impact.calledFromOutside());
    }

    @Test
    void calleesAreCappedAtEightMostReferencedFirst() {
        StepImpact impact = StepImpact.of(fooStep, tour, diff, graph, new OutOfDiffFanIn.Result(Map.of(), false));

        assertEquals(8, impact.calleesToResolve().size(), "callees were " + impact.calleesToResolve());
        assertEquals("helperOne", impact.calleesToResolve().get(0), "called three times, so first");
        assertFalse(impact.calleesToResolve().contains("foo"), "foo is declared in the change");
    }

    /** One edited declaration line (DEL + ADD) is one changed declaration, so one flag. */
    @Test
    void anEditedDeclarationLineIsFlaggedOnce() {
        UnifiedDiff edited = new UnifiedDiff(List.of(new UnifiedDiff.FileDiff(A, "M", 1, 1, false, false,
                List.of(new UnifiedDiff.Hunk("@@ -1,3 +1,3 @@", List.of(
                        new UnifiedDiff.Line(UnifiedDiff.Line.Kind.CONTEXT, OptionalInt.of(1), OptionalInt.of(1),
                                "public class Alpha {"),
                        new UnifiedDiff.Line(UnifiedDiff.Line.Kind.DEL, OptionalInt.of(2), OptionalInt.empty(),
                                "    void foo(int a) { }"),
                        new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(), OptionalInt.of(2),
                                "    void foo(long a) { }"),
                        new UnifiedDiff.Line(UnifiedDiff.Line.Kind.CONTEXT, OptionalInt.of(3), OptionalInt.of(3),
                                "}")))))));
        TourStep editStep = step("e1", new TourAnchor(A, "n1", "n3"));
        OutOfDiffFanIn.Result fanIn = new OutOfDiffFanIn.Result(Map.of("foo", List.of(
                new OutOfDiffFanIn.Occurrence("src/Yankee.java", 3, "y.foo(1);", false),
                new OutOfDiffFanIn.Occurrence("src/Xray.java", 9, "x.foo(2);", false))), Optional.empty());

        StepImpact impact = StepImpact.of(editStep, new ReviewTour("rs_1", "fp", List.of(editStep)), edited,
                ChangeGraph.of(edited), fanIn);

        assertEquals(List.of(new StepImpact.SignatureFlag("foo", A, "n2", 2)), impact.signatureFlags());
    }

    /**
     * A name two changed files declare is still declared in the change: it
     * is ambiguous, not an unknown callee to go and resolve.
     */
    @Test
    void aNameDeclaredInTwoChangedFilesIsNotACalleeToResolve() {
        UnifiedDiff ambiguous = new UnifiedDiff(List.of(
                added("src/Caller.java",
                        "public class Caller {",
                        "    void go() { shared(); outsider(); }",
                        "}"),
                added("src/Gamma.java", "public class Gamma { void shared() { } }"),
                added("src/Delta.java", "public class Delta { void shared() { } }")));
        TourStep callerStep = step("c1", new TourAnchor("src/Caller.java", "n1", "n3"));

        StepImpact impact = StepImpact.of(callerStep, new ReviewTour("rs_1", "fp", List.of(callerStep)),
                ambiguous, ChangeGraph.of(ambiguous), new OutOfDiffFanIn.Result(Map.of(), false));

        assertFalse(impact.calleesToResolve().contains("shared"), "callees were " + impact.calleesToResolve());
        assertTrue(impact.calleesToResolve().contains("outsider"), "callees were " + impact.calleesToResolve());
    }

    // ---- fixtures -----------------------------------------------------------

    private static TourStep step(String id, TourAnchor anchor) {
        return new TourStep(id, "Step " + id, "why", List.of(anchor), List.of(), List.of());
    }

    /**
     * {@code Alpha.foo} calls ten helpers the change does not declare, one of
     * them three times; {@code Beta.bar} calls {@code foo}. Both files are new,
     * so every row is changed.
     */
    private static UnifiedDiff diff() {
        return new UnifiedDiff(List.of(
                added(A,
                        "public class Alpha {",
                        "    void foo() {",
                        "        helperOne(); helperOne(); helperOne(); helperTwo(); helperThree(); "
                                + "helperFour(); helperFive(); helperSix(); helperSeven(); helperEight(); "
                                + "helperNine(); helperTen();",
                        "    }",
                        "}"),
                added(B,
                        "public class Beta {",
                        "    void bar() {",
                        "        new Alpha().foo();",
                        "    }",
                        "}")));
    }

    private static UnifiedDiff.FileDiff added(String path, String... texts) {
        List<UnifiedDiff.Line> lines = new ArrayList<>();
        int n = 1;
        for (String text : texts) {
            lines.add(new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD, OptionalInt.empty(), OptionalInt.of(n++), text));
        }
        return new UnifiedDiff.FileDiff(path, "A", texts.length, 0, false, false,
                List.of(new UnifiedDiff.Hunk("@@ -0,0 +1," + texts.length + " @@", lines)));
    }
}
