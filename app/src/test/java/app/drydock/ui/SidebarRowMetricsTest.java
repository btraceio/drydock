package app.drydock.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SidebarRowMetricsTest {

    /** The rule: the branch never takes more than a bounded share of the row. */
    @Test
    void branchTakesABoundedShareOfTheRow() {
        assertEquals(67.2, SidebarRowMetrics.branchTagMaxWidth(240.0), 0.001);
        assertEquals(99.2, SidebarRowMetrics.branchTagMaxWidth(320.0), 0.001);
    }

    /** The name is what is left, and it is always the larger share. */
    @Test
    void theNameKeepsTheMajorityOfTheRow() {
        for (double width : new double[] {120, 240, 320, 640}) {
            double branch = SidebarRowMetrics.branchTagMaxWidth(width);
            assertTrue(branch < width - branch,
                    "branch " + branch + " should be smaller than the name's share at " + width);
        }
    }

    /**
     * A collapsing sidebar reports zero and then negative widths mid-layout;
     * a negative maxWidth would be passed straight to a Label.
     */
    @Test
    void degenerateWidthsClampToZero() {
        assertEquals(0.0, SidebarRowMetrics.branchTagMaxWidth(0.0), 0.001);
        assertEquals(0.0, SidebarRowMetrics.branchTagMaxWidth(-40.0), 0.001);
    }

    /** Below this, a branch tag is unreadable and the name should have it all. */
    @Test
    void aVeryNarrowRowGivesTheBranchNothing() {
        assertEquals(0.0, SidebarRowMetrics.branchTagMaxWidth(60.0), 0.001);
    }

    /**
     * The cap grows smoothly from zero at the floor, so the branch tag does
     * not snap into or out of existence as the sidebar is resized.
     */
    @Test
    void theCapGrowsSmoothlyThroughTheFloor() {
        assertEquals(0.0, SidebarRowMetrics.branchTagMaxWidth(72.0), 0.001);
        assertEquals(0.4, SidebarRowMetrics.branchTagMaxWidth(73.0), 0.001);
    }
}
