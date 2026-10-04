package app.drydock.ui.review;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One invariant in place of four thresholds: there is always readable code
 * on screen. The rails used to collapse on independent width triggers while
 * the code column had no claim on space at all.
 *
 * <p>The hunk diff has one rail (the findings margin, 336 / 286 / 30); the
 * tour has two (the outline, 232 / 196 / 40, and the step panel at the
 * margin's widths).</p>
 */
class RailLayoutTest {

    private static final SessionReviewView.ReviewMode DIFF = SessionReviewView.ReviewMode.DIFF;
    private static final SessionReviewView.ReviewMode TOUR = SessionReviewView.ReviewMode.TOUR;

    @Test
    void theOutlineKeepsTheWidthsTheHunkDiffsLeftRailHad() {
        assertEquals(232, TourOutline.EXPANDED_WIDTH);
        assertEquals(196, TourOutline.NARROW_WIDTH);
        assertEquals(40, TourOutline.COLLAPSED_WIDTH);
    }

    // ---- the hunk diff: the margin is the only rail -----------------------

    /** With no left rail, the code column takes the outline's width back. */
    @Test
    void theHunkDiffChargesOnlyTheMargin() {
        RailLayout.Layout layout = RailLayout.solve(1800, false, false, DIFF);

        assertEquals(ReviewFindingsMargin.EXPANDED_WIDTH, RailLayout.railsWidth(layout, DIFF));
    }

    @Test
    void aWideHunkDiffCollapsesNothing() {
        // 900 - 336 = 564, over the 560 floor.
        RailLayout.Layout layout = RailLayout.solve(900, false, false, DIFF);

        assertFalse(layout.marginCollapsed());
        assertFalse(layout.narrow());
    }

    @Test
    void theMarginNarrowsBeforeItCollapses() {
        // Expanded leaves 880 - 336 = 544; narrow leaves 880 - 286 = 594.
        RailLayout.Layout layout = RailLayout.solve(880, false, false, DIFF);

        assertTrue(layout.narrow());
        assertFalse(layout.marginCollapsed());
    }

    @Test
    void andCollapsesWhenNarrowingIsNotEnough() {
        // Narrow leaves 800 - 286 = 514; collapsed leaves 770.
        RailLayout.Layout layout = RailLayout.solve(800, false, false, DIFF);

        assertTrue(layout.marginCollapsed());
    }

    @Test
    void aManualMarginCollapseIsHonouredEvenWhenThereIsRoom() {
        RailLayout.Layout layout = RailLayout.solve(1800, false, true, DIFF);

        assertTrue(layout.marginCollapsed());
    }

    // ---- both modes -----------------------------------------------------------

    @Test
    void theCodeColumnClearsItsFloorWheneverArithmeticAllows() {
        for (SessionReviewView.ReviewMode mode : SessionReviewView.ReviewMode.values()) {
            for (double width = 600; width <= 2000; width += 10) {
                RailLayout.Layout layout = RailLayout.solve(width, false, false, mode);
                double used = RailLayout.railsWidth(layout, mode);
                assertTrue(width - used >= RailLayout.CODE_MIN_WIDTH || allCollapsed(layout, mode),
                        mode + " at " + width + "px the code column got " + (width - used));
            }
        }
    }

    @Test
    void collapseIsMonotonicInWidth() {
        // A wider window may never be more collapsed than a narrower one:
        // narrowing is tried before collapsing, so there is no width at
        // which widening the window loses you a rail.
        for (SessionReviewView.ReviewMode mode : SessionReviewView.ReviewMode.values()) {
            RailLayout.Layout previous = RailLayout.solve(600, false, false, mode);
            for (double width = 610; width <= 2000; width += 10) {
                RailLayout.Layout layout = RailLayout.solve(width, false, false, mode);
                assertTrue(collapsedCount(layout, mode) <= collapsedCount(previous, mode),
                        mode + ": widening to " + width + "px collapsed something that was open");
                previous = layout;
            }
        }
    }

    // ---- the tour: outline first, step panel last ---------------------------

    @Test
    void aWideTourCollapsesNothing() {
        // Expanded rails are 232 + 336 = 568; 1800 leaves 1232 for code.
        RailLayout.Layout layout = RailLayout.solve(1800, false, false, TOUR);

        assertFalse(layout.outlineCollapsed());
        assertFalse(layout.marginCollapsed());
        assertFalse(layout.narrow());
    }

    @Test
    void inATourNarrowingTheRailsIsTriedBeforeCollapsingAnything() {
        // Expanded would leave 1100 - 568 = 532, under the floor. Narrow rails
        // are 196 + 286 = 482, leaving 618 -- so nothing has to go.
        RailLayout.Layout layout = RailLayout.solve(1100, false, false, TOUR);

        assertTrue(layout.narrow());
        assertFalse(layout.marginCollapsed());
        assertFalse(layout.outlineCollapsed());
    }

    @Test
    void inATourTheOutlineGoesFirstAndTheStepPanelLast() {
        // Narrow rails 482 leave 900 - 482 = 418. Collapsing the outline
        // gives 40 + 286 = 326, leaving 574.
        RailLayout.Layout layout = RailLayout.solve(900, false, false, TOUR);

        assertTrue(layout.outlineCollapsed(), "the outline collapses first");
        assertFalse(layout.marginCollapsed(), "the step panel stays readable");
        assertTrue(layout.narrow());
    }

    @Test
    void andOnlyThenTheStepPanel() {
        // 700 - 326 = 374; collapsing the step panel too gives 70, leaving 630.
        RailLayout.Layout layout = RailLayout.solve(700, false, false, TOUR);

        assertTrue(layout.outlineCollapsed());
        assertTrue(layout.marginCollapsed());
    }

    @Test
    void aManualOutlineCollapseIsHonouredEvenWhenThereIsRoom() {
        RailLayout.Layout layout = RailLayout.solve(1800, true, false, TOUR);

        assertTrue(layout.outlineCollapsed(), "the user's own collapse survives a wide window");
        assertFalse(layout.marginCollapsed());
    }

    /**
     * The view widths photographed through the diag harness on 2026-08-05,
     * when at four of them the code column was under its floor because each
     * rail decided its own collapse. The tour still has two rails, so it is
     * the mode these widths now pin.
     */
    @Test
    void theWidthsThatWereMeasuredWrongAreRight() {
        for (double width : new double[] {1050, 1110, 1150, 1181, 1210, 1270, 1330}) {
            RailLayout.Layout layout = RailLayout.solve(width, false, false, TOUR);
            double code = width - RailLayout.railsWidth(layout, TOUR);
            assertTrue(code >= RailLayout.CODE_MIN_WIDTH,
                    "at " + width + "px the code column got " + code);
        }
    }

    private static int collapsedCount(RailLayout.Layout layout, SessionReviewView.ReviewMode mode) {
        int outline = mode == TOUR && layout.outlineCollapsed() ? 1 : 0;
        return outline + (layout.marginCollapsed() ? 1 : 0);
    }

    private static boolean allCollapsed(RailLayout.Layout layout, SessionReviewView.ReviewMode mode) {
        return layout.marginCollapsed() && (mode != TOUR || layout.outlineCollapsed());
    }
}
