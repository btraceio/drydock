package app.drydock.ui;

/**
 * Width rules for the sidebar's child rows, kept apart from the FX node
 * building so they can be reasoned about (and tested) on their own.
 */
final class SidebarRowMetrics {

    /** The branch tag's share of a session row. The name gets the rest. */
    private static final double BRANCH_SHARE = 0.4;

    /**
     * Below this the row is too narrow for a branch tag to say anything, so
     * the name takes the row outright rather than both ellipsizing to noise.
     */
    private static final double BRANCH_FLOOR_PX = 72.0;

    private SidebarRowMetrics() { }

    /**
     * The widest the branch tag may be on a row of {@code rowWidth}.
     *
     * <p>A cap rather than a layout priority because {@code HBox} shrinks its
     * resizable children proportionally: with the name and the branch both
     * clamped to {@code minWidth 0}, they would share the squeeze and the
     * name would keep losing characters it cannot spare. Capping the branch
     * makes the name's share the remainder.
     *
     * <p>The cap grows smoothly from zero at the floor, measuring the branch's
     * share of the width ABOVE the floor rather than of the whole row. This
     * avoids popping the branch tag in and out as the sidebar is resized.
     */
    static double branchTagMaxWidth(double rowWidth) {
        return Math.max(0.0, (rowWidth - BRANCH_FLOOR_PX) * BRANCH_SHARE);
    }
}
