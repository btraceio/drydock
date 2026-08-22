package app.drydock.ui.review;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.ReadingPath;

/**
 * One row of the Review diff column: pure data (no scene graph), so the
 * model is built and tested headless and the {@code ListView} cells decide
 * how to render each variant.
 *
 * <p>Hunk <em>cards</em> (spec §4.4) are drawn from flat rows rather than by
 * nesting rows inside a container node, because the column has to stay
 * virtualized -- a 21-file diff is tens of thousands of lines. Each row
 * therefore carries its own {@link Edge}, and {@code app.css} turns that
 * into the card's top border and radius, its sides, and its bottom.</p>
 */
sealed interface ReviewDiffRow {

    /** Where a row sits in its hunk card, which is what gives the card its borders. */
    enum Edge {
        /** The card's header: top border, top radii. */
        TOP,
        /** Between the header and the last row: side borders only. */
        BODY,
        /** The card's last row: side + bottom borders, bottom radii. */
        BOTTOM
    }

    /** The card edge this row draws. */
    Edge edge();

    /**
     * A hunk card's header: {@code Sidebar.java  L112–124} plus the {@code ⤢}
     * jump into the Explorer. {@code startLine} is the 1-based new-file line
     * the jump targets (the old-file line for a pure deletion).
     *
     * <p>{@code hunkIndex} is the hunk's REAL index within its file's own
     * {@code UnifiedDiff.FileDiff.hunks()} -- not its position among the
     * headers a filtered render happens to show. {@link ReviewDiffColumn#revealHunk}
     * used to count rendered headers instead, which matched the wrong hunk
     * (and reported success doing it) the moment a filter hid some of a
     * file's hunks: exactly the shape {@code hunkFilter} produces for an
     * intent that names only some of a file's hunks.</p>
     *
     * <p>{@code untracked} and {@code staged} carry {@link UnifiedDiff.FileDiff}'s
     * own flags for the {@code untracked}/{@code staged} chip -- they travel
     * with the row rather than being re-derived in the renderer, because
     * {@code buildHunkHeader} only ever sees the row, never the file it came
     * from.</p>
     */
    record HunkHeader(String file, String range, int startLine, boolean untracked, boolean staged, int hunkIndex)
            implements ReviewDiffRow {
        @Override
        public Edge edge() {
            return Edge.TOP;
        }
    }

    /** One diff line. {@code lineKey} is the stable {@code o…}/{@code n…} anchor key. */
    record Line(String file, UnifiedDiff.Line line, String lineKey, Edge edge) implements ReviewDiffRow {
    }

    /**
     * A run of unchanged lines collapsed to {@code ⋯ N unchanged}. Identified
     * by ({@code file}, {@code hunkIndex}, {@code runIndex}) rather than by
     * line numbers so that expanding one survives a re-diff that shifts the
     * lines around it.
     */
    record CollapsedRun(String file, int hunkIndex, int runIndex, int count, Edge edge)
            implements ReviewDiffRow {

        /** The identity the expanded-run set stores; see the record note. */
        RunKey key() {
            return new RunKey(file, hunkIndex, runIndex);
        }
    }

    /** Identity of one collapsed run, independent of the line numbers inside it. */
    record RunKey(String file, int hunkIndex, int runIndex) {
    }

    /**
     * The open comment composer, sitting directly under the last line of the
     * range it is anchored to.
     *
     * <p>A row rather than a popover, because a comment is about a place in
     * the code and the composer has to stay attached to that place while the
     * reader scrolls. It carries no text: the draft lives in the view (see
     * {@code ReviewDiffColumn.composerNode}), because this row is recreated
     * on every rebuild and a draft stored here would be lost the first time
     * a collapsed run was expanded.</p>
     *
     * <p>{@code startKey} and {@code endKey} are equal for a single-line
     * comment -- exactly the shape {@code GitHubLineAnchor.of} expects, which
     * omits {@code start_line} from the request when the two agree.</p>
     */
    record Composer(String file, String startKey, String endKey) implements ReviewDiffRow {
        @Override
        public Edge edge() {
            return Edge.BODY;
        }
    }

    /** A status, empty or error message occupying the whole column. */
    record Message(String text) implements ReviewDiffRow {
        @Override
        public Edge edge() {
            return Edge.BODY;
        }
    }

    /** The "… diff truncated at N lines" notice after the render cap. */
    record Truncation(int limit) implements ReviewDiffRow {
        @Override
        public Edge edge() {
            return Edge.BODY;
        }
    }

    /**
     * A link to a related hunk in another file (spec §7.2), appended after
     * its source hunk's own rows so that folding, density and the
     * unchanged-run collapse apply to it with no new cases -- a parallel
     * rendering path for links would drift from this one at the first thing
     * they disagreed about. {@code edge} follows the same rule every other
     * card row does: {@link ReviewDiffRows} gives {@code BOTTOM} to whichever
     * row -- a line, a collapsed run, or the last link -- actually closes the
     * card.
     *
     * <p>{@link ReadingPath.Link#label()} already names a file and a symbol,
     * never {@link ReadingPath.Link#targetHunkId()} itself -- the id is what
     * a click acts on, not what the row shows.</p>
     */
    record LinkRow(ReadingPath.Link link, Edge edge) implements ReviewDiffRow {
    }
}
