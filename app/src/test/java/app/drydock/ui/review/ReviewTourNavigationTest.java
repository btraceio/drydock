package app.drydock.ui.review;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.tour.ReviewTour;
import app.drydock.review.tour.StepProgress;
import app.drydock.review.tour.TourAnchor;
import app.drydock.review.tour.TourFingerprint;
import app.drydock.review.tour.TourStep;
import app.drydock.ui.nav.NavigationTrail;
import app.drydock.ui.nav.SymbolPeek;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

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
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(ReviewDiagFxThread.call(view::diagPeekOpen), "the peek opened");
    }

    private List<String> trailLabels() {
        return ReviewDiagFxThread.call(view::diagTrail).stream().map(NavigationTrail.Waypoint::label).toList();
    }

    private void type(KeyCode code) {
        press(code).release(code);
        WaitForAsyncUtils.waitForFxEvents();
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
}
