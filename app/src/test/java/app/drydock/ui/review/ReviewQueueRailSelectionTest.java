package app.drydock.ui.review;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The {@code j}/{@code k} arithmetic, tested without a JavaFX toolkit --
 * this repository has no headless FX harness (docs/architecture.md), so the
 * navigation rule lives in a static that can be exercised directly.
 */
class ReviewQueueRailSelectionTest {

    @Test
    void anEmptyQueueHasNowhereToGo() {
        assertEquals(-1, ReviewQueueRail.nextIndex(-1, 0, 1));
        assertEquals(-1, ReviewQueueRail.nextIndex(-1, 0, -1));
    }

    @Test
    void withNothingSelectedTheListIsEnteredFromTheDirectionOfTravel() {
        assertEquals(0, ReviewQueueRail.nextIndex(-1, 5, 1));
        assertEquals(4, ReviewQueueRail.nextIndex(-1, 5, -1));
    }

    @Test
    void movesOneItemAtATime() {
        assertEquals(3, ReviewQueueRail.nextIndex(2, 5, 1));
        assertEquals(1, ReviewQueueRail.nextIndex(2, 5, -1));
    }

    /**
     * Clamped, not wrapped: holding {@code j} must stop at the last item
     * rather than teleporting back to the first, which would silently move
     * the review to a different repository.
     */
    @Test
    void clampsAtBothEndsInsteadOfWrapping() {
        assertEquals(4, ReviewQueueRail.nextIndex(4, 5, 1));
        assertEquals(0, ReviewQueueRail.nextIndex(0, 5, -1));
    }

    @Test
    void aSingleItemQueueStaysPut() {
        assertEquals(0, ReviewQueueRail.nextIndex(0, 1, 1));
        assertEquals(0, ReviewQueueRail.nextIndex(0, 1, -1));
    }
}
