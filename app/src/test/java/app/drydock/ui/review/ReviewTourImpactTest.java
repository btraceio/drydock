package app.drydock.ui.review;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.OutOfDiffFanIn;
import app.drydock.review.Provenance;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.Triage;
import app.drydock.review.UsageProvider;
import app.drydock.review.tour.AnchorIndex;
import app.drydock.review.tour.ImpactNote;
import app.drydock.review.tour.StepImpact;
import app.drydock.review.tour.StepProgress;
import app.drydock.review.tour.TourAnchor;
import app.drydock.review.tour.TourCheck;
import app.drydock.review.tour.TourStep;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The step panel's impact (spec §6, §5 "Peek"): the agent's notes pinned on
 * top, the signature flag, callers outside the change grouped by file,
 * edges to other steps, and callees outside the change -- every entry a
 * {@link Button} that peeks in place or selects the step it names.
 *
 * <p>The board's {@code src/guards.h} declares {@code foo} on n1 and {@code
 * src/guards.cpp} calls it without declaring anything, so step 1 is
 * called by step 2 and step 2 has no callers of its own to find.</p>
 */
class ReviewTourImpactTest extends ReviewTourFixture {

    @Override
    UnifiedDiff fixtureDiff() {
        return new UnifiedDiff(List.of(
                file(FILE_A, "void foo();", "void bar();"),
                file(FILE_B, "  foo();")));
    }

    private static final OutOfDiffFanIn.Result TWO_CALLERS = new OutOfDiffFanIn.Result(Map.of("foo", List.of(
            new OutOfDiffFanIn.Occurrence("src/main.cpp", 7, "  foo();", false),
            new OutOfDiffFanIn.Occurrence("src/other.cpp", 3, "return foo();", true))), Optional.empty());

    private void setFanIn(OutOfDiffFanIn.Result result) {
        interact(() -> view.diagSetFanIn(scope.id(), result));
        WaitForAsyncUtils.waitForFxEvents();
    }

    private List<String> impactTexts() {
        return ReviewDiagFxThread.call(() -> texts(view.diagStepPanel().extraSections()));
    }

    private void waitForImpactText(String text) throws TimeoutException {
        try {
            WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> impactTexts().contains(text));
        } catch (TimeoutException e) {
            throw new TimeoutException("never showed \"" + text + "\"; panel showed " + impactTexts());
        }
    }

    private static List<String> texts(Node root) {
        List<String> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(Node node, List<String> out) {
        if (node instanceof Labeled labeled && labeled.getText() != null && node.isVisible()) {
            out.add(labeled.getText());
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                if (child.isVisible()) {
                    collect(child, out);
                }
            }
        }
    }

    private static Optional<Button> button(Node root, String text) {
        return root.lookupAll(".button").stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> text.equals(button.getText()))
                .findFirst();
    }

    private Button panelButton(String text) {
        return ReviewDiagFxThread.call(() -> button(view.diagStepPanel(), text))
                .orElseThrow(() -> new AssertionError("no button \"" + text + "\" in " + impactTexts()));
    }

    @Test
    void theStepListsItsOutsideCallersItsInChangeEdgesAndItsSignatureFlag() throws TimeoutException {
        setFanIn(TWO_CALLERS);

        waitForImpactText("Called from outside the change");
        List<String> texts = impactTexts();

        assertTrue(texts.contains("occurrences, not resolved references"), "the provenance tag: " + texts);
        assertTrue(texts.contains("src/main.cpp"), "callers are grouped under their file: " + texts);
        assertTrue(texts.contains("src/other.cpp"), texts.toString());
        assertTrue(texts.contains(":7  foo();"), "the caller row: " + texts);
        assertTrue(texts.contains(":3  return foo(); · in a changed file"), texts.toString());
        assertTrue(texts.contains("← foo · step 2"), "the in-change edge: " + texts);
        assertTrue(texts.contains("declaration changed · 2 call sites were not edited"), texts.toString());
        assertTrue(texts.indexOf("declaration changed · 2 call sites were not edited")
                        < texts.indexOf("Called from outside the change"),
                "the signature flag comes before the callers: " + texts);
    }

    @Test
    void anUnavailableScanSaysWhyAndListsNoCallers() throws TimeoutException {
        setFanIn(TWO_CALLERS);
        waitForImpactText(":7  foo();");

        setFanIn(new OutOfDiffFanIn.Result(Map.of(), Optional.of("git grep timed out after 30 s")));

        waitForImpactText("callers unavailable: git grep timed out after 30 s");
        List<String> texts = impactTexts();
        assertFalse(texts.contains(":7  foo();"), "no caller rows: " + texts);
        assertFalse(texts.stream().anyMatch(text -> text.startsWith("declaration changed")),
                "no flag counted from a scan that did not run: " + texts);
        assertTrue(ReviewDiagFxThread.call(() ->
                view.diagStepPanel().lookupAll(".step-impact-caller").isEmpty()));
    }

    @Test
    void clickingAnInChangeEdgeSelectsThatStep() throws TimeoutException {
        setFanIn(TWO_CALLERS);
        waitForImpactText("← foo · step 2");

        Button edge = panelButton("← foo · step 2");
        interact(edge::fire);
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals("s2", ReviewDiagFxThread.call(view::diagCurrentStepId));
        waitForImpactText("→ foo · step 1");
    }

    @Test
    void clickingACallerOpensItsLocationInPlace() throws TimeoutException {
        setFanIn(TWO_CALLERS);
        waitForImpactText(":7  foo();");

        Button caller = panelButton(":7  foo();");
        interact(caller::fire);
        WaitForAsyncUtils.waitForFxEvents();

        // The fixture's scope has no checkout to read, so the peek says so
        // over the column -- proof the click reached openLocationPeek.
        Optional<String> notice = ReviewDiagFxThread.call(view::diagNotice);
        assertTrue(notice.isPresent() && notice.get().contains("src/main.cpp"), "notice was " + notice);
    }

    @Test
    void anUnrelatedRefreshDoesNotRebuildTheImpact() throws TimeoutException {
        setFanIn(TWO_CALLERS);
        waitForImpactText(":7  foo();");
        Button before = panelButton(":7  foo();");

        interact(view::refreshReviewState);
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(before == panelButton(":7  foo();"), "the impact section was not rebuilt");
    }

    @Test
    void theRealScanOfAScopeWithNothingToGrepSaysWhyRatherThanFindingForever() throws TimeoutException {
        // No diagSetFanIn: the fixture's worktree is not a checkout, so the
        // board's own scan lands unavailable and must still be recorded.
        try {
            WaitForAsyncUtils.waitFor(30, TimeUnit.SECONDS, () -> impactTexts().stream()
                    .anyMatch(text -> text.startsWith("callers unavailable: ")));
        } catch (TimeoutException e) {
            throw new TimeoutException("the scan never said why; panel showed " + impactTexts());
        }

        assertFalse(impactTexts().contains("Finding callers…"), impactTexts().toString());
    }

    @Test
    void aChangeThatCannotBeParsedSaysSoInsteadOfFindingCallersForever() throws TimeoutException {
        interact(() -> view.diagSetGraphBuilder(diff -> {
            throw new IllegalStateException("unbalanced braces");
        }));
        // A new diff instance with the same content: the tour stays valid,
        // and requestGraph builds again instead of treating it as graphed.
        interact(() -> view.diagShowDiff(scope, new UnifiedDiff(host.diff.files())));
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> !view.diagGraphBuildPending(scope.id()));

        waitForImpactText("callers unavailable: the change could not be parsed: unbalanced braces");
        assertFalse(impactTexts().contains("Finding callers…"), impactTexts().toString());
    }

    @Test
    void aStepThatDeclaresNothingHasNoCallersSection() throws TimeoutException {
        setFanIn(TWO_CALLERS);
        waitForImpactText("← foo · step 2");

        interact(() -> panelButton("← foo · step 2").fire());
        waitForImpactText("→ foo · step 1");

        List<String> texts = impactTexts();
        assertFalse(texts.contains("Called from outside the change"), "s2 only calls foo: " + texts);
        assertFalse(texts.contains("Finding callers…"), texts.toString());
        assertFalse(texts.stream().anyMatch(text -> text.startsWith("declaration changed")), texts.toString());
    }

    @Test
    void anAgentLocationThatIsNotAPathSaysSoOverTheColumn() {
        StepPanel.Host stepHost = ReviewDiagFxThread.call(view::diagStepHost);
        interact(() -> stepHost.openLocation("src/\0bad\u0000.h", 3));

        Optional<String> notice = ReviewDiagFxThread.call(view::diagNotice);
        assertTrue(notice.isPresent() && notice.get().endsWith(": not a file path"), "notice was " + notice);
    }

    // ---- the panel on its own ---------------------------------------------

    private static final class RecordingHost implements StepPanel.Host {
        final List<String> opened = new ArrayList<>();
        final List<String> selected = new ArrayList<>();

        @Override public void answerChoice(String checkId, int choiceIndex) { }
        @Override public void submitRisk(String checkId, String answer) { }
        @Override public void override(String reason) { }
        @Override public void goToAnchor(int anchorIndex) { }
        @Override public void retryRisk(String checkId) { }
        @Override public void triage(ReviewAnnotation finding, Triage triage, Optional<String> reason) { }
        @Override public void revealFinding(ReviewAnnotation finding) { }
        @Override public void sendBack(List<ReviewAnnotation> confirmedBlockers) { }
        @Override public void reviewAnyway() { }
        @Override public void backToStep() { }
        @Override public void requestRefresh() { }
        @Override public void postMessage(ReviewAnnotation finding, String body) { }

        @Override
        public void openLocation(String file, int line) {
            opened.add(file + ":" + line);
        }

        @Override
        public void selectStep(String stepId) {
            selected.add(stepId);
        }
    }

    /** A panel in a scene of its own, skinned, so its scroll content can be looked up. */
    private static StepPanel detachedPanel(StepPanel.Host host) {
        return ReviewDiagFxThread.call(() -> {
            StepPanel panel = new StepPanel(host);
            new Scene(panel, 336, 700);
            panel.applyCss();
            panel.layout();
            return panel;
        });
    }

    private static List<String> panelTexts(StepPanel panel) {
        return ReviewDiagFxThread.call(() -> {
            panel.applyCss();
            panel.layout();
            return texts(panel.extraSections());
        });
    }

    private static StepImpact calleesOnly(List<String> callees) {
        return new StepImpact(List.of(), List.of(), callees, List.of(), Optional.empty());
    }

    @Test
    void calleesSayHowTheyWereResolvedAndNotesArePinnedOnTop() {
        RecordingHost recording = new RecordingHost();
        StepPanel panel = detachedPanel(recording);
        Map<String, Optional<UsageProvider.Usage>> resolved = Map.of(
                "alpha", Optional.of(new UsageProvider.Usage("src/a.h", 3, "int alpha();", Provenance.MEASURED, true)),
                "beta", Optional.of(new UsageProvider.Usage("src/b.h", 9, "beta(1);", Provenance.MEASURED, false)),
                "gamma", Optional.empty());
        interact(() -> panel.showImpact(new StepPanel.ImpactView(
                List.of(new ImpactNote(FILE_A, 1, "callers must re-check the guard")),
                calleesOnly(List.of("alpha", "beta", "gamma", "delta")), resolved, false, Optional.empty(), true)));

        List<String> texts = panelTexts(panel);
        assertEquals("Agent notes", texts.getFirst(), "the claimed notes are pinned on top: " + texts);
        assertTrue(texts.contains("src/guards.h:1 — callers must re-check the guard"), texts.toString());
        assertTrue(texts.contains("claimed"), texts.toString());
        assertTrue(texts.contains("Calls outside the change"), texts.toString());
        assertTrue(texts.contains("measured"), texts.toString());
        assertTrue(texts.contains("alpha → src/a.h:3"), texts.toString());
        assertTrue(texts.contains("beta → src/b.h:9 (first occurrence)"), texts.toString());
        assertTrue(texts.contains("gamma → no declaration found"), texts.toString());
        assertTrue(texts.contains("delta → resolving…"), texts.toString());

        interact(() -> button(panel, "alpha → src/a.h:3").orElseThrow().fire());
        interact(() -> button(panel, "src/guards.h:1 — callers must re-check the guard").orElseThrow().fire());
        assertEquals(List.of("src/a.h:3", FILE_A + ":1"), recording.opened);
    }

    @Test
    void whileTheScanRunsTheCallersSayTheyAreBeingFound() {
        StepPanel panel = detachedPanel(new RecordingHost());
        interact(() -> panel.showImpact(new StepPanel.ImpactView(List.of(), calleesOnly(List.of()), Map.of(),
                true, Optional.empty(), true)));

        List<String> texts = panelTexts(panel);
        assertTrue(texts.contains("Finding callers…"), texts.toString());
        assertFalse(texts.contains("No callers outside the change"), "pending is not none: " + texts);
    }

    @Test
    void aStepThatDeclaresNothingShowsNoCallersButKeepsEdgesAndCallees() {
        StepPanel panel = detachedPanel(new RecordingHost());
        StepImpact measured = new StepImpact(List.of(),
                List.of(new StepImpact.InChange("foo", StepImpact.Direction.CALLS, "s1", 1)),
                List.of("alpha"), List.of(), Optional.empty());
        interact(() -> panel.showImpact(new StepPanel.ImpactView(List.of(), measured, Map.of(), true,
                Optional.empty(), false)));

        List<String> texts = panelTexts(panel);
        assertFalse(texts.contains("Finding callers…"), texts.toString());
        assertFalse(texts.contains("Called from outside the change"), texts.toString());
        assertTrue(texts.contains("→ foo · step 1"), texts.toString());
        assertTrue(texts.contains("alpha → resolving…"), texts.toString());
    }

    @Test
    void calleesThatCannotBeResolvedHereSaySo() {
        StepPanel panel = detachedPanel(new RecordingHost());
        interact(() -> panel.showImpact(new StepPanel.ImpactView(List.of(), calleesOnly(List.of("delta")), Map.of(),
                false, Optional.of("no checkout to search"), true)));

        List<String> texts = panelTexts(panel);
        assertTrue(texts.contains("delta → not resolved: no checkout to search"), texts.toString());
        assertFalse(texts.contains("delta → resolving…"), texts.toString());
    }

    @Test
    void aTraceChoiceWithALocationHasAPeekButton() {
        RecordingHost recording = new RecordingHost();
        StepPanel panel = detachedPanel(recording);
        TourCheck trace = new TourCheck("t1", TourCheck.Kind.TRACE, "Which caller breaks?", List.of(
                new TourCheck.Choice("main", Optional.of(new TourCheck.Location("src/main.cpp", 7))),
                new TourCheck.Choice("none", Optional.empty())), OptionalInt.of(0), "Main passes null.", List.of());
        TourStep step = new TourStep("s1", "Guards header", "Why.", List.of(new TourAnchor(FILE_A, "n1", "n1")),
                List.of(), List.of(trace));
        StepProgress progress = StepProgress.fresh(step, AnchorIndex.of(fixtureDiff()));
        interact(() -> panel.show(new StepView(step, 1, 1, progress)));

        List<Button> peeks = ReviewDiagFxThread.call(() -> {
            panel.applyCss();
            panel.layout();
            return panel.lookupAll(".step-choice-peek");
        }).stream()
                .map(Button.class::cast).toList();
        assertEquals(1, peeks.size(), "only the choice with a location gets one");
        assertEquals("peek", peeks.getFirst().getText());

        interact(peeks.getFirst()::fire);
        assertEquals(List.of("src/main.cpp:7"), recording.opened);
    }
}
