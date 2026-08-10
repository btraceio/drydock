package app.drydock.ui.review;

import java.util.List;
import java.util.Optional;

/**
 * The gutter's selection, resolved against the diff column's row list. Pure,
 * so the rule that matters is testable headless.
 *
 * <p>A selection stops at its anchor's <em>hunk</em>, not merely its file.
 * GitHub requires both ends of a multi-line review comment to lie in the same
 * file and the same diff hunk, and rejects the whole review otherwise; since
 * {@code ReviewDiffRows} separates hunks with an inert {@link
 * ReviewDiffRow.HunkHeader} and {@link ReviewDiffRow.Line} carries no hunk
 * index, a header row is what terminates a run. Crossing a {@link
 * ReviewDiffRow.CollapsedRun} stays legal: it is keyed by {@code (file,
 * hunkIndex, runIndex)} and never leaves its hunk.</p>
 */
final class DiffLineSelection {

    private DiffLineSelection() {
    }

    /** One resolved selection, in diff order regardless of drag direction. */
    record Range(String file, String startKey, String endKey) {
    }

    static Optional<Range> resolve(List<ReviewDiffRow> rows, int anchorIndex, int focusIndex) {
        if (anchorIndex < 0 || anchorIndex >= rows.size()
                || !(rows.get(anchorIndex) instanceof ReviewDiffRow.Line anchor)) {
            return Optional.empty();
        }
        int clamped = clamp(rows, anchorIndex, focusIndex);
        ReviewDiffRow.Line other = (ReviewDiffRow.Line) rows.get(clamped);
        ReviewDiffRow.Line first = clamped < anchorIndex ? other : anchor;
        ReviewDiffRow.Line last = clamped < anchorIndex ? anchor : other;
        return Optional.of(new Range(anchor.file(), first.lineKey(), last.lineKey()));
    }

    /**
     * Walks from the anchor toward the focus and stops at the last {@code
     * Line} row before anything that ends the hunk -- a header, or the end of
     * the list. Returns the anchor itself when the very next row ends it.
     */
    private static int clamp(List<ReviewDiffRow> rows, int anchorIndex, int focusIndex) {
        int target = Math.max(0, Math.min(focusIndex, rows.size() - 1));
        int step = target < anchorIndex ? -1 : 1;
        int furthest = anchorIndex;
        for (int i = anchorIndex; i != target + step; i += step) {
            ReviewDiffRow row = rows.get(i);
            if (row instanceof ReviewDiffRow.HunkHeader) {
                break;
            }
            if (row instanceof ReviewDiffRow.Line) {
                furthest = i;
            }
        }
        return furthest;
    }
}
