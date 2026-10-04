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
 * <p>The board is charged for what it actually draws. The hunk diff has one
 * rail, the findings margin on the right; the tour has two, the outline on
 * the left and the step panel (at the margin's widths) on the right.</p>
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

    /**
     * Which rails are collapsed, and whether the rest are in narrow mode.
     * {@code outlineCollapsed} is the tour outline's; in the hunk diff there
     * is no left rail, and it simply carries the reader's own choice through.
     */
    record Layout(boolean outlineCollapsed, boolean marginCollapsed, boolean narrow) { }

    /**
     * Gives up rail width in escalating steps until the code column clears
     * its floor: narrow the rails first, then collapse them. A rail the user
     * collapsed by hand starts collapsed and stays that way however wide the
     * window is.
     *
     * <p>Narrowing comes before any collapse, and that ordering is what makes
     * the result monotonic in width: an earlier design took narrow mode from
     * its own fixed threshold, and widening the window could LOSE code width
     * because a rail came back at full width on the way up.</p>
     *
     * <p>In the tour the outline (a list of step titles) goes before the step
     * panel, which holds the narrative and the check -- the tour's primary
     * content. In the hunk diff the margin is the only rail to give up. The
     * last resort is every rail collapsed; below that there is nothing left
     * to trade, so the layout stops rather than pretending.</p>
     */
    static Layout solve(double width, boolean outlineForced, boolean marginForced,
                        SessionReviewView.ReviewMode mode) {
        boolean outline = outlineForced;
        boolean margin = marginForced;

        if (fits(width, new Layout(outline, margin, false), mode)) {
            return new Layout(outline, margin, false);
        }
        if (fits(width, new Layout(outline, margin, true), mode)) {
            return new Layout(outline, margin, true);
        }
        if (mode == SessionReviewView.ReviewMode.TOUR) {
            outline = true;
            if (!fits(width, new Layout(outline, margin, true), mode)) {
                margin = true;
            }
            return new Layout(outline, margin, true);
        }
        return new Layout(outline, true, true);
    }

    private static boolean fits(double width, Layout layout, SessionReviewView.ReviewMode mode) {
        return width - railsWidth(layout, mode) >= CODE_MIN_WIDTH;
    }

    /** The total width the rails occupy under {@code layout} in {@code mode}. */
    static double railsWidth(Layout layout, SessionReviewView.ReviewMode mode) {
        double margin = railWidth(layout.marginCollapsed(), layout.narrow(),
                ReviewFindingsMargin.COLLAPSED_WIDTH, ReviewFindingsMargin.NARROW_WIDTH,
                ReviewFindingsMargin.EXPANDED_WIDTH);
        if (mode != SessionReviewView.ReviewMode.TOUR) {
            return margin;
        }
        return margin + railWidth(layout.outlineCollapsed(), layout.narrow(),
                TourOutline.COLLAPSED_WIDTH, TourOutline.NARROW_WIDTH, TourOutline.EXPANDED_WIDTH);
    }

    private static double railWidth(boolean collapsed, boolean narrow, double collapsedWidth,
                                    double narrowWidth, double expandedWidth) {
        if (collapsed) {
            return collapsedWidth;
        }
        return narrow ? narrowWidth : expandedWidth;
    }
}
