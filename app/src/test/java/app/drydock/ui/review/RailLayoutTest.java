package app.drydock.ui.review;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One invariant in place of four thresholds: there is always readable code
 * on screen. The rails used to collapse on independent width triggers while
 * the code column had no claim on space at all, so at ~1200px the rails were
 * the only thing left and the intent card was the only thing to click.
 *
 * <p>Two rails now, not three: the cross-repo queue rail went with the Review
 * destination. Its collapsed width was a constant on both sides of the
 * arithmetic -- the board asked for a three-rail answer with the queue forced
 * collapsed and handed the collapsed queue's 44px back on top -- so dropping
 * it changes no breakpoint, only the terms.</p>
 */
class RailLayoutTest {

    @Test
    void aWideWindowCollapsesNothingAndStaysFullWidth() {
        // Expanded rails are 232 + 336 = 568; 1800 leaves 1232 for code.
        RailLayout.Layout layout = RailLayout.solve(1800, false, false);

        assertFalse(layout.intentsCollapsed());
        assertFalse(layout.marginCollapsed());
        assertFalse(layout.narrow());
    }

    @Test
    void narrowingTheRailsIsTriedBeforeCollapsingAnything() {
        // Expanded would leave 1100 - 568 = 532, under the floor. Narrow rails
        // are 196 + 286 = 482, leaving 618 -- so nothing has to go.
        RailLayout.Layout layout = RailLayout.solve(1100, false, false);

        assertTrue(layout.narrow());
        assertFalse(layout.marginCollapsed(), "narrowing bought enough width on its own");
    }

    @Test
    void theMarginIsTheFirstToGo() {
        // Narrow rails 482 leave 900 - 482 = 418, under the floor. Collapsing
        // the margin gives 196 + 30 = 226, leaving 674.
        RailLayout.Layout layout = RailLayout.solve(900, false, false);

        assertTrue(layout.marginCollapsed(), "the margin collapses first");
        assertFalse(layout.intentsCollapsed(), "the intent rail must still be readable");
    }

    @Test
    void andOnlyThenTheIntents() {
        // 700 - 226 = 474, under the floor; collapsing the intents gives 70,
        // leaving 630. The intent rail is the last to give up its width.
        RailLayout.Layout layout = RailLayout.solve(700, false, false);

        assertTrue(layout.marginCollapsed());
        assertTrue(layout.intentsCollapsed());
    }

    @Test
    void theCodeColumnClearsItsFloorWheneverArithmeticAllows() {
        for (double width = 700; width <= 2000; width += 10) {
            RailLayout.Layout layout = RailLayout.solve(width, false, false);
            double used = RailLayout.railsWidth(layout);
            assertTrue(width - used >= RailLayout.CODE_MIN_WIDTH || allCollapsed(layout),
                    "at " + width + "px the code column got " + (width - used));
        }
    }

    @Test
    void aManualCollapseIsHonouredEvenWhenThereIsRoom() {
        RailLayout.Layout layout = RailLayout.solve(1800, true, false);

        assertTrue(layout.intentsCollapsed(), "the user's own collapse survives a wide window");
        assertFalse(layout.marginCollapsed());
    }

    @Test
    void collapseIsMonotonicInWidth() {
        // A wider window may never be more collapsed than a narrower one.
        // The rule this pins down: narrowing is tried before collapsing, so
        // there is no width at which widening the window loses you a rail.
        RailLayout.Layout previous = RailLayout.solve(600, false, false);
        for (double width = 610; width <= 2000; width += 10) {
            RailLayout.Layout layout = RailLayout.solve(width, false, false);
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
     * grow with the window. Widening from 1110 to 1150 takes it from 628 to
     * 582, because 1150 is where the rails can re-expand and they take their
     * full width back. That is the design -- rails return in the reverse of
     * the order they went -- and the floor is what must hold across it.</p>
     */
    @Test
    void theWidthsThatWereMeasuredWrongAreRight() {
        for (double width : new double[] {1050, 1110, 1150, 1181, 1210, 1270, 1330}) {
            RailLayout.Layout layout = RailLayout.solve(width, false, false);
            double code = width - RailLayout.railsWidth(layout);
            assertTrue(code >= RailLayout.CODE_MIN_WIDTH,
                    "at " + width + "px the code column got " + code);
        }
    }

    private static int collapsedCount(RailLayout.Layout layout) {
        return (layout.intentsCollapsed() ? 1 : 0) + (layout.marginCollapsed() ? 1 : 0);
    }

    private static boolean allCollapsed(RailLayout.Layout layout) {
        return layout.intentsCollapsed() && layout.marginCollapsed();
    }
}
