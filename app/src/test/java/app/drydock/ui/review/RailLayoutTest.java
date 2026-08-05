package app.drydock.ui.review;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One invariant in place of four thresholds: there is always readable code
 * on screen. The rails used to collapse on independent width triggers while
 * the code column had no claim on space at all, so at ~1200px the rails were
 * the only thing left and the intent card was the only thing to click.
 */
class RailLayoutTest {

    @Test
    void aWideWindowCollapsesNothingAndStaysFullWidth() {
        // Expanded rails are 236 + 232 + 336 = 804; 1800 leaves 996 for code.
        RailLayout.Layout layout = RailLayout.solve(1800, false, false, false);

        assertFalse(layout.queueCollapsed());
        assertFalse(layout.intentsCollapsed());
        assertFalse(layout.marginCollapsed());
        assertFalse(layout.narrow());
    }

    @Test
    void narrowingTheRailsIsTriedBeforeCollapsingAnything() {
        // Expanded would leave 1300 - 804 = 496, under the floor. Narrow rails
        // are 206 + 196 + 286 = 688, leaving 612 -- so nothing has to go.
        RailLayout.Layout layout = RailLayout.solve(1300, false, false, false);

        assertTrue(layout.narrow());
        assertFalse(layout.marginCollapsed(), "narrowing bought enough width on its own");
    }

    @Test
    void theMarginIsTheFirstToGo() {
        // Narrow rails 688 leave 1200 - 688 = 512, under the floor. Collapsing
        // the margin gives 206 + 196 + 30 = 432, leaving 768.
        RailLayout.Layout layout = RailLayout.solve(1200, false, false, false);

        assertTrue(layout.marginCollapsed(), "the margin collapses first");
        assertFalse(layout.intentsCollapsed());
        assertFalse(layout.queueCollapsed());
    }

    @Test
    void thenTheIntentsAndOnlyThenTheQueue() {
        // 950 - 432 = 518, under the floor; collapsing intents gives 276.
        RailLayout.Layout intents = RailLayout.solve(950, false, false, false);
        assertTrue(intents.marginCollapsed());
        assertTrue(intents.intentsCollapsed());
        assertFalse(intents.queueCollapsed(), "the queue still has room here");

        // 800 - 276 = 524, under the floor; the queue is the last to go.
        RailLayout.Layout all = RailLayout.solve(800, false, false, false);
        assertTrue(all.queueCollapsed(), "the queue is the last to give up its width");
    }

    @Test
    void theCodeColumnClearsItsFloorWheneverArithmeticAllows() {
        for (double width = 700; width <= 2000; width += 10) {
            RailLayout.Layout layout = RailLayout.solve(width, false, false, false);
            double used = RailLayout.railsWidth(layout);
            assertTrue(width - used >= RailLayout.CODE_MIN_WIDTH || allCollapsed(layout),
                    "at " + width + "px the code column got " + (width - used));
        }
    }

    @Test
    void aManualCollapseIsHonouredEvenWhenThereIsRoom() {
        RailLayout.Layout layout = RailLayout.solve(1800, true, false, false);

        assertTrue(layout.queueCollapsed(), "the user's own collapse survives a wide window");
        assertFalse(layout.intentsCollapsed());
    }

    @Test
    void collapseIsMonotonicInWidth() {
        // A wider window may never be more collapsed than a narrower one.
        // The rule this pins down: narrowing is tried before collapsing, so
        // there is no width at which widening the window loses you a rail.
        RailLayout.Layout previous = RailLayout.solve(600, false, false, false);
        for (double width = 610; width <= 2000; width += 10) {
            RailLayout.Layout layout = RailLayout.solve(width, false, false, false);
            assertTrue(collapsedCount(layout) <= collapsedCount(previous),
                    "widening to " + width + "px collapsed something that was open");
            previous = layout;
        }
    }

    /**
     * The measured defect, as a test. These are the view widths photographed
     * through the diag harness on 2026-08-05, before this class existed; at
     * four of them the code column was under its floor -- 526, 522, 493 and
     * 524 -- because each rail decided its own collapse and none of them was
     * accountable for what was left. (View widths, not window widths: the
     * window is wider by the sidebar.)
     *
     * <p>The code column's own width is deliberately <em>not</em> asserted to
     * grow with the window. Widening from 1210 to 1270 takes it from 778 to
     * 582, because 1270 is where the margin can re-open and it takes its
     * 286px back. That is the design -- rails return in the reverse of the
     * order they went -- and the floor is what must hold across it.</p>
     */
    @Test
    void theWidthsThatWereMeasuredWrongAreRight() {
        for (double width : new double[] {1050, 1110, 1150, 1181, 1210, 1270, 1330}) {
            RailLayout.Layout layout = RailLayout.solve(width, false, false, false);
            double code = width - RailLayout.railsWidth(layout);
            assertTrue(code >= RailLayout.CODE_MIN_WIDTH,
                    "at " + width + "px the code column got " + code);
        }
    }

    private static int collapsedCount(RailLayout.Layout layout) {
        return (layout.queueCollapsed() ? 1 : 0) + (layout.intentsCollapsed() ? 1 : 0)
                + (layout.marginCollapsed() ? 1 : 0);
    }

    private static boolean allCollapsed(RailLayout.Layout layout) {
        return layout.queueCollapsed() && layout.intentsCollapsed() && layout.marginCollapsed();
    }
}
