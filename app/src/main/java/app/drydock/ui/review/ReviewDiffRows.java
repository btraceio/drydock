package app.drydock.ui.review;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.HunkIds;
import app.drydock.review.ReadingPath;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
     * What the column is currently showing. {@code linksByHunk} carries each
     * hunk's {@link ReadingPath.Link}s, keyed by {@link HunkIds#hunkId};
     * a hunk absent from the map gets no footer row at all, rather than an
     * empty one -- the same "no card for nothing to say" rule {@link #build}
     * already applies to a hunk with no rows to show.
     */
    record Options(boolean showContext, Set<ReviewDiffRow.RunKey> expandedRuns, int maxRows,
                   Map<String, List<ReadingPath.Link>> linksByHunk,
                   boolean expandRunsByDefault) {
        Options {
            expandedRuns = Set.copyOf(expandedRuns);
            if (maxRows <= 0) {
                throw new IllegalArgumentException("maxRows must be positive: " + maxRows);
            }
            linksByHunk = linksByHunk == null ? Map.of() : Map.copyOf(linksByHunk);
        }

        Options(boolean showContext, Set<ReviewDiffRow.RunKey> expandedRuns, int maxRows) {
            this(showContext, expandedRuns, maxRows, Map.of(), false);
        }

        Options(boolean showContext, Set<ReviewDiffRow.RunKey> expandedRuns, int maxRows,
                Map<String, List<ReadingPath.Link>> linksByHunk) {
            this(showContext, expandedRuns, maxRows, linksByHunk, false);
        }

        static Options defaults(int maxRows) {
            return new Options(true, Set.of(), maxRows, Map.of(), false);
        }
    }

    private ReviewDiffRows() {
    }

    static List<ReviewDiffRow> build(UnifiedDiff diff, Options options) {
        List<ReviewDiffRow> rows = new ArrayList<>();
        Set<ReviewDiffRow.RunKey> folds = budgetFolds(diff, options);
        int emitted = 0;
        for (UnifiedDiff.FileDiff file : diff.files()) {
            int hunkIndex = 0;
            for (UnifiedDiff.Hunk hunk : file.hunks()) {
                int index = hunkIndex++;
                List<ReviewDiffRow> card = buildCard(file, hunk, index, options, folds);
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
            rows.add(new ReviewDiffRow.Message("No changes in this scope."));
        }
        return List.copyOf(rows);
    }

    /**
     * In whole-file mode, the runs to fold so the rendered rows fit
     * {@code options.maxRows()}: longest first, because the longest unchanged
     * runs are the ones farthest from any change. Runs the user expanded are
     * never folded. Empty outside whole-file mode.
     */
    static Set<ReviewDiffRow.RunKey> budgetFolds(UnifiedDiff diff, Options options) {
        if (!options.expandRunsByDefault()) {
            return Set.of();
        }
        record Run(ReviewDiffRow.RunKey key, int length) { }
        List<Run> runs = new ArrayList<>();
        int total = 0;
        for (UnifiedDiff.FileDiff file : diff.files()) {
            int hunkIndex = 0;
            for (UnifiedDiff.Hunk hunk : file.hunks()) {
                total += 1 + hunk.lines().size();
                int runIndex = 0;
                int i = 0;
                List<UnifiedDiff.Line> lines = hunk.lines();
                while (i < lines.size()) {
                    if (lines.get(i).kind() != UnifiedDiff.Line.Kind.CONTEXT) {
                        i++;
                        continue;
                    }
                    int end = i;
                    while (end < lines.size() && lines.get(end).kind() == UnifiedDiff.Line.Kind.CONTEXT) {
                        end++;
                    }
                    ReviewDiffRow.RunKey key = new ReviewDiffRow.RunKey(file.path(), hunkIndex, runIndex++);
                    if (end - i > COLLAPSE_THRESHOLD && !options.expandedRuns().contains(key)) {
                        runs.add(new Run(key, end - i));
                    }
                    i = end;
                }
                hunkIndex++;
            }
        }
        runs.sort(Comparator.comparingInt(Run::length).reversed());
        Set<ReviewDiffRow.RunKey> folds = new HashSet<>();
        for (Run run : runs) {
            if (total <= options.maxRows()) {
                break;
            }
            folds.add(run.key());
            total -= run.length() - 1;
        }
        return folds;
    }

    /**
     * One hunk's card: a header plus its body rows, plus a footer row for
     * each of the hunk's {@link ReadingPath.Link}s (spec §7.2) -- last, so a
     * reader reaches "what this hunk has to do with the rest of the diff"
     * only after having read the hunk itself. Whichever row ends up last,
     * body or link, is marked {@link ReviewDiffRow.Edge#BOTTOM} so the card
     * closes on it. A hunk whose every line is dropped (all context, with
     * context hidden) yields no card at all rather than an empty one --
     * links belong to a hunk, not to a card with nothing else in it.
     */
    private static List<ReviewDiffRow> buildCard(UnifiedDiff.FileDiff file, UnifiedDiff.Hunk hunk,
                                                 int hunkIndex, Options options,
                                                 Set<ReviewDiffRow.RunKey> folds) {
        List<ReviewDiffRow> body = buildBody(file, hunk, hunkIndex, options, folds);
        if (body.isEmpty()) {
            return List.of();
        }
        List<ReviewDiffRow> card = new ArrayList<>();
        card.add(new ReviewDiffRow.HunkHeader(file.path(), rangeLabel(hunk), startLine(hunk),
                file.untracked(), file.staged(), hunkIndex));
        card.addAll(body);
        String hunkId = HunkIds.hunkId(file.path(), hunkIndex);
        for (ReadingPath.Link link : options.linksByHunk().getOrDefault(hunkId, List.of())) {
            card.add(new ReviewDiffRow.LinkRow(link, ReviewDiffRow.Edge.BODY));
        }
        int last = card.size() - 1;
        card.set(last, withBottomEdge(card.get(last)));
        return card;
    }

    private static List<ReviewDiffRow> buildBody(UnifiedDiff.FileDiff file, UnifiedDiff.Hunk hunk,
                                                 int hunkIndex, Options options,
                                                 Set<ReviewDiffRow.RunKey> folds) {
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
            boolean fold = runLength > COLLAPSE_THRESHOLD && (options.expandRunsByDefault()
                    ? folds.contains(key)
                    : !options.expandedRuns().contains(key));
            if (fold) {
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
            case ReviewDiffRow.LinkRow link -> new ReviewDiffRow.LinkRow(link.link(), ReviewDiffRow.Edge.BOTTOM);
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
