package app.drydock.ui.review;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.tour.ReviewTour;
import app.drydock.review.tour.StepProgress;
import app.drydock.review.tour.TourAnchor;
import app.drydock.review.tour.TourFingerprint;
import app.drydock.review.tour.TourStep;
import app.drydock.testing.FxSync;
import app.drydock.ui.nav.NavigationTrail;
import app.drydock.ui.nav.PeekLayer;
import app.drydock.ui.nav.SymbolPeek;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Review navigates like the Explorer (spec §5 "Navigation"): a peek over the
 * diff column owns {@code ⏎}/{@code u}/{@code a} and closes on Esc, step
 * changes and promoted peeks are trail waypoints while plain peeks are not,
 * and {@code b} / {@code .} / {@code ,} move within the current step.
 *
 * <p>The host offers no navigation here (no session search root), so the
 * peek is opened through {@link SessionReviewView#diagPushPeek} and the
 * trail lives in memory only.</p>
 */
class ReviewTourNavigationTest extends ReviewTourFixture {

    /** {@code s1} anchors each hunk of {@link #FILE_A} separately, so it has two anchors to walk. */
    @Override
    ReviewTour tourFor(String scopeId, UnifiedDiff diff) {
        return new ReviewTour(scopeId, TourFingerprint.of(diff), List.of(
                new TourStep("s1", "Guards header", "Why the header changes.",
                        List.of(new TourAnchor(FILE_A, "n1", "n1"), new TourAnchor(FILE_A, "n11", "n11")),
                        List.of(), List.of(predict("c1"))),
                new TourStep("s2", "Guards source", "Why the source changes.",
                        List.of(new TourAnchor(FILE_B, "n1", "n1")), List.of(), List.of(predict("c2")))));
    }

    private static SymbolPeek peekAtBar() {
        return new SymbolPeek("bar", "bar · guards.h", Path.of("/tmp/nowhere").resolve(FILE_A), Path.of(FILE_A),
                11, List.of("void bar();"), Set.of(), List.of(), true);
    }

    private void openPeek() {
        interact(() -> view.diagPushPeek(peekAtBar()));
        FxSync.waitForFxEvents();
        assertTrue(ReviewDiagFxThread.call(view::diagPeekOpen), "the peek opened");
    }

    private List<String> trailLabels() {
        return ReviewDiagFxThread.call(view::diagTrail).stream().map(NavigationTrail.Waypoint::label).toList();
    }

    private void type(KeyCode code) {
        press(code).release(code);
        FxSync.waitForFxEvents();
    }

    @Test
    void uWithAPeekOpenTogglesUsagesInsteadOfUndoingTheStep() {
        type(KeyCode.R);
        assertEquals(StepProgress.Decision.CHANGES, progress("s1").decision());
        openPeek();

        type(KeyCode.U);

        assertEquals(StepProgress.Decision.CHANGES, progress("s1").decision(), "u did not undo the step");
        assertTrue(ReviewDiagFxThread.call(view::diagPeekOpen), "the peek is still open");
    }

    /**
     * The usages mode's two defects, fixed: the list REPLACES the code (one
     * scroll region, not the code's own scrollbar nested in a second one),
     * and each row jumps -- here to a location peek, since this scope has no
     * checkout to read, which the notice says over the column.
     */
    @Test
    void usagesReplaceTheCodeAndARowPeeksAtItsOccurrence() {
        interact(() -> view.diagPushPeek(new SymbolPeek("bar", "bar · guards.h",
                Path.of("/tmp/nowhere").resolve(FILE_A), Path.of(FILE_A), 11,
                List.of("void bar();"), Set.of(), List.of(
                        new SymbolPeek.Occurrence(Path.of(FILE_A), 11, "void bar();", true),
                        new SymbolPeek.Occurrence(Path.of("src/other.cpp"), 3, "return bar();", false)),
                true)));
        FxSync.waitForFxEvents();

        type(KeyCode.U);

        assertTrue(ReviewDiagFxThread.call(() -> !view.lookupAll(".peek-usages").isEmpty()),
                "the usages list is on show");
        assertTrue(ReviewDiagFxThread.call(() -> view.lookupAll(".peek-code").isEmpty()),
                "usages replace the code, so the card has ONE scroll region");

        Button other = ReviewDiagFxThread.call(() -> view.lookupAll(".peek-usage-row").stream()
                .map(Button.class::cast)
                .filter(row -> row.getGraphic() instanceof javafx.scene.layout.HBox box
                        && box.getChildren().stream().anyMatch(child -> child instanceof Label label
                        && label.getText().contains("other.cpp")))
                .findFirst().orElseThrow());
        interact(other::fire);
        FxSync.waitForFxEvents();

        // No checkout to read: the location peek says so over the column --
        // proof the click reached openOccurrencePeek, not nothing.
        assertTrue(ReviewDiagFxThread.call(view::diagNotice)
                        .map(text -> text.contains("src/other.cpp") || text.contains("no checkout"))
                        .orElse(false),
                "the row's click tried to peek at the occurrence");
    }

    @Test
    void enterWithAPeekOpenPromotesItInsteadOfSubmitting() {
        openPeek();

        type(KeyCode.ENTER);

        assertTrue(host.submittedScopes.isEmpty(), "Enter did not submit the review");
        assertFalse(ReviewDiagFxThread.call(view::diagPeekOpen), "promoting closed the peek stack");
        NavigationTrail.Waypoint last = ReviewDiagFxThread.call(view::diagTrail).getLast();
        assertEquals("guards.h", last.label(), "the promoted peek is a waypoint named after its file");
        assertEquals(Optional.of("n11"), last.lineKey());
        assertTrue(ReviewDiagFxThread.call(view::diagBackPillShown), "the column left the step");
    }

    @Test
    void escapeClosesOnePeek() {
        openPeek();

        assertTrue(ReviewDiagFxThread.call(view::unwindOne), "Esc closed something");

        assertFalse(ReviewDiagFxThread.call(view::diagPeekOpen));
    }

    @Test
    void aStepChangeIsAWaypointAndWalkingBackDoesNotPushOne() {
        type(KeyCode.CLOSE_BRACKET);
        assertEquals("s2", ReviewDiagFxThread.call(view::diagCurrentStepId));
        assertEquals(List.of("Step 1", "Step 2"), trailLabels());

        assertTrue(ReviewDiagFxThread.call(() -> view.navigateTrail(-1)));

        assertEquals("s1", ReviewDiagFxThread.call(view::diagCurrentStepId));
        assertEquals(List.of("Step 1", "Step 2"), trailLabels(), "walking the trail pushed nothing");
        assertFalse(ReviewDiagFxThread.call(() -> view.navigateTrail(-1)),
                "at the trail's start the key falls through to tab switching");
    }

    @Test
    void periodWalksTheStepsAnchorsAndBReturnsToTheFirst() {
        assertEquals(0, ReviewDiagFxThread.call(view::diagAnchorIndex));
        type(KeyCode.PERIOD);
        assertEquals(1, ReviewDiagFxThread.call(view::diagAnchorIndex));
        type(KeyCode.PERIOD);
        assertEquals(0, ReviewDiagFxThread.call(view::diagAnchorIndex), ". wraps to the first anchor");
        type(KeyCode.COMMA);
        assertEquals(1, ReviewDiagFxThread.call(view::diagAnchorIndex), ", wraps to the last anchor");

        type(KeyCode.B);

        assertEquals(0, ReviewDiagFxThread.call(view::diagAnchorIndex));
        assertEquals("s1", ReviewDiagFxThread.call(view::diagCurrentStepId));
    }

    @Test
    void bHidesTheBackToStepPill() {
        openPeek();
        type(KeyCode.ENTER);
        assertTrue(ReviewDiagFxThread.call(view::diagBackPillShown));

        type(KeyCode.B);

        assertFalse(ReviewDiagFxThread.call(view::diagBackPillShown));
    }

    @Test
    void aPlainPeekAddsNoWaypoint() {
        List<NavigationTrail.Waypoint> before = ReviewDiagFxThread.call(view::diagTrail);

        openPeek();
        assertTrue(ReviewDiagFxThread.call(view::unwindOne));

        assertEquals(before, ReviewDiagFxThread.call(view::diagTrail));
    }

    private void typeShifted(KeyCode code) {
        press(KeyCode.SHIFT).press(code).release(code).release(KeyCode.SHIFT);
        FxSync.waitForFxEvents();
    }

    private static NavigationTrail.Waypoint elsewhere(String label) {
        return new NavigationTrail.Waypoint(Path.of("docs/elsewhere.md"), label, 3, false, Optional.empty());
    }

    @Test
    void inTheHunkDiffAFullPeekStackSaysSoOverTheColumn() {
        type(KeyCode.V);
        assertEquals(SessionReviewView.ReviewMode.DIFF, ReviewDiagFxThread.call(view::diagMode));
        interact(() -> {
            for (int i = 0; i <= PeekLayer.MAX_DEPTH; i++) {
                view.diagPushPeek(peekAtBar());
            }
        });
        FxSync.waitForFxEvents();

        assertEquals(Optional.of("Peek stack is full — esc to unwind"), ReviewDiagFxThread.call(view::diagNotice));
    }

    @Test
    void inTheHunkDiffATrailMoveThatCannotBeShownSaysSo() {
        type(KeyCode.V);
        interact(() -> view.diagRestoreTrail(List.of(elsewhere("elsewhere.md"),
                new NavigationTrail.Waypoint(Path.of(FILE_B), "guards.cpp", 1, false, Optional.of("n1"))), 1));

        assertTrue(ReviewDiagFxThread.call(() -> view.navigateTrail(-1)));

        Optional<String> notice = ReviewDiagFxThread.call(view::diagNotice);
        assertTrue(notice.isPresent() && notice.get().contains("docs/elsewhere.md"),
                "the move that opened nothing says why: " + notice);
    }

    @Test
    void aStepWaypointWhoseFileIsNotThatStepsDoesNotSelectIt() {
        interact(() -> view.diagRestoreTrail(List.of(elsewhere("Step 2"),
                new NavigationTrail.Waypoint(Path.of(FILE_A), "Step 1", 1, false, Optional.of("n1"))), 1));

        assertTrue(ReviewDiagFxThread.call(() -> view.navigateTrail(-1)));

        assertEquals("s1", ReviewDiagFxThread.call(view::diagCurrentStepId),
                "step 2 anchors guards.cpp, not docs/elsewhere.md");
        assertTrue(ReviewDiagFxThread.call(view::diagNotice).isPresent(), "treated as a plain waypoint instead");
    }

    @Test
    void shiftUWithAPeekOpenKeepsItsBoardMeaning() {
        type(KeyCode.R);
        openPeek();

        typeShifted(KeyCode.U);

        assertEquals(StepProgress.Decision.NONE, progress("s1").decision(), "⇧U reached the board, not the peek");
        assertTrue(ReviewDiagFxThread.call(view::diagPeekOpen));
    }

    @Test
    void enterWithAPeekOpenYieldsToAFocusedButton() {
        openPeek();
        Node shortcuts = lookup((Node node) -> node instanceof Button button && "?".equals(button.getText()))
                .query();
        interact(shortcuts::requestFocus);
        FxSync.waitForFxEvents();

        type(KeyCode.ENTER);

        assertTrue(ReviewDiagFxThread.call(view::diagPeekOpen), "the peek was not promoted");
        assertTrue(host.submittedScopes.isEmpty());
    }
}
