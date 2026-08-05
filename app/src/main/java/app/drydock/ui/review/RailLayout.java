package app.drydock.ui.review;

/**
 * Which rails are collapsed at a given window width.
 *
 * <p>Pure arithmetic, deliberately: this used to be four independent width
 * thresholds inside the view, and the code column -- the only thing anyone
 * opened Review to read -- had no claim on space at all. One rule replaces
 * them: rails give up their width, in a fixed order, until the code column
 * clears {@link #CODE_MIN_WIDTH}.</p>
 */
final class RailLayout {

    /**
     * The narrowest the code column may be. Wide enough for a unified diff
     * line at the default density without wrapping, which is the point below
     * which the column stops doing its job.
     */
    static final double CODE_MIN_WIDTH = 560;

    private RailLayout() {
    }

    /** Which rails are collapsed, and whether the rest are in narrow mode. */
    record Layout(boolean queueCollapsed, boolean intentsCollapsed,
                  boolean marginCollapsed, boolean narrow) { }

    /**
     * Gives up rail width in escalating steps until the code column clears
     * its floor: narrow the rails first, then collapse the margin, then the
     * intents, then the queue. A rail the user collapsed by hand starts
     * collapsed and stays that way however wide the window is.
     *
     * <p>Narrowing comes before any collapse, and that ordering is what makes
     * the result monotonic in width. The previous design took narrow mode
     * from its own fixed threshold, which produced the absurd case of
     * widening the window and <em>losing</em> width: measured at 1150px the
     * code column had 624px, and at 1210px it had 522px, because the queue
     * rail came back at full width on the way up.</p>
     *
     * <p>The last resort is every rail collapsed. Below roughly 700px even
     * that cannot clear the floor, and the view has drilled in to its Browse
     * page long before -- there is no fifth thing to give up, so the layout
     * stops rather than pretending.</p>
     */
    static Layout solve(double width, boolean queueForced, boolean intentsForced,
                        boolean marginForced) {
        boolean margin = marginForced;
        boolean intents = intentsForced;
        boolean queue = queueForced;

        if (fits(width, queue, intents, margin, false)) {
            return new Layout(queue, intents, margin, false);
        }
        if (fits(width, queue, intents, margin, true)) {
            return new Layout(queue, intents, margin, true);
        }
        margin = true;
        if (!fits(width, queue, intents, margin, true)) {
            intents = true;
        }
        if (!fits(width, queue, intents, margin, true)) {
            queue = true;
        }
        return new Layout(queue, intents, margin, true);
    }

    private static boolean fits(double width, boolean queue, boolean intents, boolean margin,
                                boolean narrow) {
        return width - railsWidth(new Layout(queue, intents, margin, narrow)) >= CODE_MIN_WIDTH;
    }

    /** The total width the rails occupy under {@code layout}. */
    static double railsWidth(Layout layout) {
        return railWidth(layout.queueCollapsed(), layout.narrow(),
                        ReviewQueueRail.COLLAPSED_WIDTH, ReviewQueueRail.NARROW_WIDTH,
                        ReviewQueueRail.EXPANDED_WIDTH)
                + railWidth(layout.intentsCollapsed(), layout.narrow(),
                        ReviewIntentRail.COLLAPSED_WIDTH, ReviewIntentRail.NARROW_WIDTH,
                        ReviewIntentRail.EXPANDED_WIDTH)
                + railWidth(layout.marginCollapsed(), layout.narrow(),
                        ReviewFindingsMargin.COLLAPSED_WIDTH, ReviewFindingsMargin.NARROW_WIDTH,
                        ReviewFindingsMargin.EXPANDED_WIDTH);
    }

    private static double railWidth(boolean collapsed, boolean narrow, double collapsedWidth,
                                    double narrowWidth, double expandedWidth) {
        if (collapsed) {
            return collapsedWidth;
        }
        return narrow ? narrowWidth : expandedWidth;
    }
}
