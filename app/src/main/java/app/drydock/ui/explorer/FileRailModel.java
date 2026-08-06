package app.drydock.ui.explorer;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The scope funnel behind the file rail (Explorer delta, part 3): which
 * files the rail shows, in what order, and -- the part that matters -- what
 * its footer says about the ones it is NOT showing.
 *
 * <p>The rule the delta sets is that the rail must always say <em>how</em>
 * the list was narrowed and must never dead-end. Every narrowing here is
 * therefore reversible from the footer: a query that matches files outside
 * the change turns the footer into the button that widens the scope, and
 * repo scope dims out-of-change files rather than hiding them.</p>
 *
 * <p>Pure model, unit-tested: the footer's wording IS the feature, so it is
 * decided here rather than assembled inline in a view.</p>
 */
final class FileRailModel {

    /** {@code d} toggles these: the current review scope, or the whole worktree. */
    enum Scope { DIFF, REPO }

    /** {@code s} cycles these. */
    enum Sort {
        CHURN("churn"), FINDINGS("findings"), NAME("a-z");

        private final String label;

        Sort(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }

        Sort next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    /** How much of a file this change rewrote; drives the heat bar and the churn sort. */
    enum Churn { HIGH, MED, LOW, NONE }

    /**
     * One rail row.
     *
     * @param changedLines how many of its lines the current diff scope touches (0 = out of change)
     * @param findings     open findings anchored in it
     * @param contentHit   whether the current query matched its CONTENT (not just its path)
     */
    record Entry(Path relative, int changedLines, int findings, boolean contentHit) {

        Entry {
            Objects.requireNonNull(relative, "relative");
        }

        boolean inDiff() {
            return changedLines > 0;
        }

        String name() {
            Path name = relative.getFileName();
            return name == null ? relative.toString() : name.toString();
        }

        /** The directory the file lives in, as the row's second line. */
        String module() {
            Path parent = relative.getParent();
            return parent == null ? "" : parent.toString();
        }

        /** The row's right-hand stat: what this change did to the file. */
        String stat() {
            return changedLines == 0 ? "untouched" : "~" + changedLines + " lines";
        }

        Churn churn() {
            if (changedLines == 0) {
                return Churn.NONE;
            }
            if (changedLines >= 40) {
                return Churn.HIGH;
            }
            return changedLines >= 10 ? Churn.MED : Churn.LOW;
        }

        /** Whether {@code query} matches this row by name or path (content is decided by the search). */
        boolean matchesPath(String query) {
            String needle = query.toLowerCase(Locale.ROOT);
            return relative.toString().toLowerCase(Locale.ROOT).contains(needle);
        }

        boolean matches(String query) {
            return query.isBlank() || contentHit || matchesPath(query);
        }
    }

    private FileRailModel() {
    }

    /**
     * The rows the rail renders.
     *
     * <p>The currently open file survives every narrowing -- scope AND
     * query. The rail must not drop the row for the file the reader is
     * looking at: that is the dead-end the delta forbids, and it is worse
     * than a hidden match because it leaves the rail's selection pointing at
     * a file that is no longer on screen.</p>
     */
    static List<Entry> visible(List<Entry> all, Scope scope, Sort sort, String query, Path openFile) {
        String needle = query == null ? "" : query.strip();
        return all.stream()
                .filter(entry -> entry.relative().equals(openFile)
                        || (entry.matches(needle) && (scope == Scope.REPO || entry.inDiff())))
                .sorted(comparator(sort))
                .toList();
    }

    /** How many entries the query actually matches — the open file is kept for orientation, not counted as a hit. */
    private static int matchCount(List<Entry> all, String needle) {
        return (int) all.stream().filter(entry -> entry.matches(needle)).count();
    }

    private static Comparator<Entry> comparator(Sort sort) {
        return switch (sort) {
            case CHURN -> Comparator.<Entry>comparingInt(entry -> entry.churn().ordinal())
                    .thenComparing(Comparator.comparingInt(Entry::changedLines).reversed())
                    .thenComparing(Entry::name);
            case FINDINGS -> Comparator.<Entry>comparingInt(entry -> -entry.findings())
                    .thenComparing(Entry::name);
            case NAME -> Comparator.comparing(Entry::name).thenComparing(entry -> entry.relative().toString());
        };
    }

    /** How many files the query matches that {@code DIFF} scope is hiding. */
    static int hiddenByDiffScope(List<Entry> all, String query, Path openFile) {
        String needle = query == null ? "" : query.strip();
        if (needle.isBlank()) {
            return 0;
        }
        return (int) all.stream()
                .filter(entry -> entry.matches(needle))
                .filter(entry -> !entry.inDiff() && !entry.relative().equals(openFile))
                .count();
    }

    /**
     * The footer: the funnel's escape hatch. With a query in {@code DIFF}
     * scope it is an ACTION -- the count of what the scope is hiding, and the
     * offer to show it -- and otherwise it reports what the reader is looking
     * at and how big the thing it was narrowed from is.
     */
    static String footer(List<Entry> all, Scope scope, Sort sort, String query, Path openFile, int repoFileCount) {
        String needle = query == null ? "" : query.strip();
        List<Entry> shown = visible(all, scope, sort, needle, openFile);
        if (scope == Scope.DIFF) {
            if (needle.isBlank()) {
                long changed = all.stream().filter(Entry::inDiff).count();
                return "this change · " + changed + (changed == 1 ? " file" : " files")
                        + " — repo has " + formatCount(repoFileCount);
            }
            int hidden = hiddenByDiffScope(all, needle, openFile);
            if (hidden == 0) {
                return "no further matches outside this change";
            }
            return "⌕ \"" + needle + "\" matches " + hidden + " more file" + (hidden == 1 ? "" : "s")
                    + " in the repo — show";
        }
        if (!needle.isBlank()) {
            int matches = matchCount(all, needle);
            return matches + (matches == 1 ? " match" : " matches")
                    + " across the repo · out-of-change dimmed";
        }
        return "whole repo · " + formatCount(repoFileCount) + " files, " + shown.size()
                + " shown · sorted by " + sort.label();
    }

    /** Whether clicking the footer does something (switches to repo scope). */
    static boolean footerIsAction(Scope scope, String query, List<Entry> all, Path openFile) {
        String needle = query == null ? "" : query.strip();
        return scope == Scope.DIFF
                && (needle.isBlank() || hiddenByDiffScope(all, needle, openFile) > 0);
    }

    private static String formatCount(int count) {
        return String.format(Locale.ROOT, "%,d", count);
    }
}
