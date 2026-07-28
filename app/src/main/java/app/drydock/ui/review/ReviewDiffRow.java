package app.drydock.ui.review;

import app.drydock.git.UnifiedDiff;

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
     */
    record HunkHeader(String file, String range, int startLine) implements ReviewDiffRow {
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
}
