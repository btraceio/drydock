package app.drydock.ui.review;

import app.drydock.git.UnifiedDiff;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Builds the Review diff column's row model from a {@link UnifiedDiff}
 * (spec §4.4): one hunk card per hunk, unchanged runs collapsed to
 * {@code ⋯ N unchanged}, and an optional context-free mode where unchanged
 * lines are dropped entirely.
 *
 * <p>Pure and headless-testable; the view owns all rendering.</p>
 */
final class ReviewDiffRows {

    /** Shorter runs of unchanged lines are cheaper to read than to collapse. */
    static final int COLLAPSE_THRESHOLD = 4;

    /**
     * Which hunks a build includes. {@link #ALL} is the whole scope; an
     * intent's own predicate is what selecting it in the rail installs.
     */
    @FunctionalInterface
    interface HunkFilter {

        /** Every hunk of every file. */
        HunkFilter ALL = (file, hunkIndex) -> true;

        boolean includes(String file, int hunkIndex);
    }

    /** What the column is currently showing. */
    record Options(boolean showContext, Set<ReviewDiffRow.RunKey> expandedRuns, int maxRows,
                   HunkFilter filter) {
        Options {
            expandedRuns = Set.copyOf(expandedRuns);
            if (maxRows <= 0) {
                throw new IllegalArgumentException("maxRows must be positive: " + maxRows);
            }
            filter = filter == null ? HunkFilter.ALL : filter;
        }

        Options(boolean showContext, Set<ReviewDiffRow.RunKey> expandedRuns, int maxRows) {
            this(showContext, expandedRuns, maxRows, HunkFilter.ALL);
        }

        static Options defaults(int maxRows) {
            return new Options(true, Set.of(), maxRows, HunkFilter.ALL);
        }
    }

    private ReviewDiffRows() {
    }

    static List<ReviewDiffRow> build(UnifiedDiff diff, Options options) {
        List<ReviewDiffRow> rows = new ArrayList<>();
        int emitted = 0;
        for (UnifiedDiff.FileDiff file : diff.files()) {
            int hunkIndex = 0;
            for (UnifiedDiff.Hunk hunk : file.hunks()) {
                // The index still advances for a filtered-out hunk: it is the
                // hunk's identity within its file, and renumbering what
                // survives the filter would make an intent's hunk ids point
                // at someone else's code.
                int index = hunkIndex++;
                if (!options.filter().includes(file.path(), index)) {
                    continue;
                }
                List<ReviewDiffRow> card = buildCard(file, hunk, index, options);
                if (card.isEmpty()) {
                    continue;
                }
                if (emitted + card.size() > options.maxRows()) {
                    rows.add(new ReviewDiffRow.Truncation(options.maxRows()));
                    return List.copyOf(rows);
                }
                rows.addAll(card);
                emitted += card.size();
            }
        }
        if (rows.isEmpty()) {
            rows.add(new ReviewDiffRow.Message(options.filter() == HunkFilter.ALL
                    ? "No changes in this scope."
                    : "This intent's hunks are not in the current diff — show the whole scope to look for them."));
        }
        return List.copyOf(rows);
    }

    /**
     * One hunk's card: a header plus its body rows, with the last body row
     * marked {@link ReviewDiffRow.Edge#BOTTOM} so the card closes. A hunk
     * whose every line is dropped (all context, with context hidden) yields
     * no card at all rather than an empty one.
     */
    private static List<ReviewDiffRow> buildCard(UnifiedDiff.FileDiff file, UnifiedDiff.Hunk hunk,
                                                 int hunkIndex, Options options) {
        List<ReviewDiffRow> body = buildBody(file, hunk, hunkIndex, options);
        if (body.isEmpty()) {
            return List.of();
        }
        List<ReviewDiffRow> card = new ArrayList<>();
        card.add(new ReviewDiffRow.HunkHeader(file.path(), rangeLabel(hunk), startLine(hunk),
                file.untracked(), file.staged()));
        card.addAll(body.subList(0, body.size() - 1));
        card.add(withBottomEdge(body.get(body.size() - 1)));
        return card;
    }

    private static List<ReviewDiffRow> buildBody(UnifiedDiff.FileDiff file, UnifiedDiff.Hunk hunk,
                                                 int hunkIndex, Options options) {
        List<ReviewDiffRow> body = new ArrayList<>();
        int runIndex = 0;
        int i = 0;
        List<UnifiedDiff.Line> lines = hunk.lines();
        while (i < lines.size()) {
            UnifiedDiff.Line line = lines.get(i);
            if (line.kind() != UnifiedDiff.Line.Kind.CONTEXT) {
                body.add(new ReviewDiffRow.Line(file.path(), line, line.lineKey(), ReviewDiffRow.Edge.BODY));
                i++;
                continue;
            }
            int end = i;
            while (end < lines.size() && lines.get(end).kind() == UnifiedDiff.Line.Kind.CONTEXT) {
                end++;
            }
            int runLength = end - i;
            ReviewDiffRow.RunKey key = new ReviewDiffRow.RunKey(file.path(), hunkIndex, runIndex++);
            if (!options.showContext()) {
                // `c`: unchanged lines are dropped entirely, not collapsed --
                // a collapsed-run row would still be a row about context.
                i = end;
                continue;
            }
            if (runLength > COLLAPSE_THRESHOLD && !options.expandedRuns().contains(key)) {
                body.add(new ReviewDiffRow.CollapsedRun(file.path(), hunkIndex, key.runIndex(), runLength,
                        ReviewDiffRow.Edge.BODY));
            } else {
                for (int j = i; j < end; j++) {
                    body.add(new ReviewDiffRow.Line(file.path(), lines.get(j), lines.get(j).lineKey(),
                            ReviewDiffRow.Edge.BODY));
                }
            }
            i = end;
        }
        return body;
    }

    private static ReviewDiffRow withBottomEdge(ReviewDiffRow row) {
        return switch (row) {
            case ReviewDiffRow.Line line ->
                    new ReviewDiffRow.Line(line.file(), line.line(), line.lineKey(), ReviewDiffRow.Edge.BOTTOM);
            case ReviewDiffRow.CollapsedRun run ->
                    new ReviewDiffRow.CollapsedRun(run.file(), run.hunkIndex(), run.runIndex(), run.count(),
                            ReviewDiffRow.Edge.BOTTOM);
            default -> row;
        };
    }

    /** {@code L112–124} over the new-file line range, falling back to the old file for a deletion. */
    static String rangeLabel(UnifiedDiff.Hunk hunk) {
        int first = 0;
        int last = 0;
        for (UnifiedDiff.Line line : hunk.lines()) {
            int number = line.newLine().orElse(line.oldLine().orElse(0));
            if (number == 0) {
                continue;
            }
            if (first == 0) {
                first = number;
            }
            last = number;
        }
        if (first == 0) {
            return hunk.header();
        }
        return first == last ? "L" + first : "L" + first + "–" + last;
    }

    /**
     * The line the card's {@code ⤢} jumps to: the hunk's first CHANGED line
     * in the new file, not its first line.
     *
     * <p>A hunk opens with {@code REVIEW_CONTEXT_LINES} of unchanged code, so
     * jumping to its first line landed the Explorer several lines above
     * anything the reader clicked ⤢ to see -- "the top of the hunk" rather
     * than the change. A pure deletion has no added line to aim at; the
     * following context line is where the deleted code used to be, which is
     * the closest thing to it that still exists in the file. Package-private
     * so the rule is testable without a toolkit.</p>
     */
    static int startLine(UnifiedDiff.Hunk hunk) {
        boolean sawChange = false;
        for (UnifiedDiff.Line line : hunk.lines()) {
            if (line.kind() != UnifiedDiff.Line.Kind.CONTEXT) {
                sawChange = true;
            }
            if (sawChange && line.newLine().isPresent()) {
                return line.newLine().getAsInt();
            }
        }
        // No change with a new-file line at all (a deletion at end of file):
        // fall back to the hunk's first line, which is what this used to do.
        for (UnifiedDiff.Line line : hunk.lines()) {
            if (line.newLine().isPresent()) {
                return line.newLine().getAsInt();
            }
        }
        return 1;
    }
}
