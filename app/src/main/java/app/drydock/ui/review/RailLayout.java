package app.drydock.ui.review;

/**
 * Which rails are collapsed at a given window width.
 *
 * <p>Pure arithmetic, deliberately: this used to be four independent width
 * thresholds inside the view, and the code column -- the only thing anyone
 * opened Review to read -- had no claim on space at all. One rule replaces
 * them: rails give up their width, in a fixed order, until the code column
 * clears {@link #CODE_MIN_WIDTH}.</p>
 *
 * <p>Two rails, since review moved into the session that owns the checkout:
 * the cross-repo queue rail was the third, and it went with the destination.
 * The board that replaced it is charged for what it actually draws -- the
 * intent rail and the findings margin -- and nothing else.</p>
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
    record Layout(boolean intentsCollapsed, boolean marginCollapsed, boolean narrow) { }

    /**
     * Gives up rail width in escalating steps until the code column clears
     * its floor: narrow the rails first, then collapse the margin, then the
     * intents. A rail the user collapsed by hand starts collapsed and stays
     * that way however wide the window is.
     *
     * <p>Narrowing comes before any collapse, and that ordering is what makes
     * the result monotonic in width. The previous design took narrow mode
     * from its own fixed threshold, which produced the absurd case of
     * widening the window and <em>losing</em> width: measured at 1150px the
     * code column had 624px, and at 1210px it had 522px, because a rail came
     * back at full width on the way up.</p>
     *
     * <p>The last resort is every rail collapsed. Below roughly 630px even
     * that cannot clear the floor -- there is no third thing to give up, so
     * the layout stops rather than pretending.</p>
     */
    static Layout solve(double width, boolean intentsForced, boolean marginForced) {
        boolean margin = marginForced;
        boolean intents = intentsForced;

        if (fits(width, intents, margin, false)) {
            return new Layout(intents, margin, false);
        }
        if (fits(width, intents, margin, true)) {
            return new Layout(intents, margin, true);
        }
        margin = true;
        if (!fits(width, intents, margin, true)) {
            intents = true;
        }
        return new Layout(intents, margin, true);
    }

    private static boolean fits(double width, boolean intents, boolean margin, boolean narrow) {
        return width - railsWidth(new Layout(intents, margin, narrow)) >= CODE_MIN_WIDTH;
    }

    /** The total width the rails occupy under {@code layout}. */
    static double railsWidth(Layout layout) {
        return railWidth(layout.intentsCollapsed(), layout.narrow(),
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
