package app.drydock.review;

import app.drydock.git.UnifiedDiff;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.process.ProcessTimeoutException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Where a changed symbol is used outside the change (spec §4.3).
 *
 * <p>The change graph is diff-scoped by design: it parses only the files the
 * diff touches, so a caller sitting in an unchanged file is invisible to it.
 * That caller is exactly the strongest "read this first" signal a review has
 * -- a public-API change whose contract other code depends on -- and this
 * class recovers it with one bounded {@code git grep} rather than by
 * building the repository-wide index this codebase has twice declined to
 * carry.</p>
 *
 * <p>One spawn for the whole scope, not one per symbol: every uniquely-named
 * changed declaration goes into a patterns file and {@code git grep -f}
 * reads them all in a single pass.</p>
 *
 * <p>The locations are kept, not just counted: a fan-in with nowhere to
 * click is a statistic rather than comprehension, and it lands exactly when
 * a reviewer wants to look. A later task feeds these into the existing
 * occurrence popover.</p>
 *
 * <p>A grep match is a lexical count, not a call count: it cannot tell a
 * real reference from an unrelated identifier spelled the same way, the
 * same trade this codebase already makes for an ungrammared file (see
 * {@link SymbolScan}). What it will NOT do is count a line that does not
 * contain the symbol at all. Attribution is therefore word-bounded ({@link
 * #mentions}), with {@code git grep -w} narrowing what comes back in the
 * first place, because a plain substring match reports
 * {@code ZetaSymHelper} as two uses of {@code ZetaSym}, and this number is
 * the reading path's FIRST rank term (see {@link ReadingPath}): an inflated
 * count does not merely read wrong, it reorders what a human reads next. A
 * repository-wide semantic index would still resolve more than this does --
 * a same-named symbol from another package is counted here -- and that is
 * exactly the cost this class is built to avoid paying. The popover says
 * "occurrences, not resolved references" for that residue; it was never a
 * licence to list lines the symbol is absent from.</p>
 *
 * <p>One line that genuinely mentions two changed declarations is counted
 * once for each. That is not double counting: it is a use of both, and the
 * popover lists it under both names.</p>
 *
 * <p><b>Path quoting.</b> Plain {@code git grep -n -F} C-quotes any path
 * with a non-ASCII byte or a special character -- {@code café.txt} comes
 * back as the literal {@code "caf\303\251.txt"}, quotes and octal escapes
 * included -- which would silently fail to match against {@code
 * changedFiles} and under-report the scan as clean. {@code -z} avoids the
 * quoting entirely, but it also changes the framing: each match becomes
 * {@code file<NUL>line<NUL>text} terminated by {@code \n} (verified against
 * a real git binary), not the colon-joined text plain {@code git grep -n}
 * prints. {@link #parse} is written against that NUL framing so a path
 * containing a colon, or a non-ASCII byte, or both, round-trips intact.</p>
 *
 * <p>Blocking; never call {@link #scan} on the FX thread.</p>
 */
public final class OutOfDiffFanIn {

    private static final Logger LOG = Logger.getLogger(OutOfDiffFanIn.class.getName());
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final char FIELD_SEPARATOR = '\0';

    /**
     * One place {@code symbol} is used, outside the change. {@code text} is
     * kept exactly as {@code git grep} reports it, leading whitespace
     * included -- deliberately, not a missed {@code .strip()}: the popover
     * this feeds is showing a source line, and its original indentation is
     * part of reading it, not noise to trim.
     */
    public record Occurrence(String file, int line, String text, boolean inChangedFile) {

        /** An occurrence in a file the change does not touch. */
        public Occurrence(String file, int line, String text) {
            this(file, line, text, false);
        }
    }

    /**
     * {@code unavailableReason} present means the scan could not run: absent,
     * not zero, and the reason says why (no checkout, timeout, git failure).
     */
    public record Result(Map<String, List<Occurrence>> bySymbol, Optional<String> unavailableReason) {

        public Result {
            Objects.requireNonNull(unavailableReason, "unavailableReason");
        }

        /** {@code unavailable} true carries the generic reason {@code "unavailable"}. */
        public Result(Map<String, List<Occurrence>> bySymbol, boolean unavailable) {
            this(bySymbol, unavailable ? Optional.of("unavailable") : Optional.empty());
        }

        public boolean unavailable() {
            return unavailableReason.isPresent();
        }

        private static Result unavailable(String reason) {
            return new Result(Map.of(), Optional.of(reason));
        }
    }

    /** Every line of a file: the "drop the whole file" mapping of the {@code Set<String>} overloads. */
    private static final class AllLines extends AbstractSet<Integer> {
        @Override
        public boolean contains(Object o) {
            return true;
        }

        @Override
        public Iterator<Integer> iterator() {
            return Collections.emptyIterator();
        }

        @Override
        public int size() {
            return Integer.MAX_VALUE;
        }
    }

    private OutOfDiffFanIn() {
    }

    /**
     * The scan for one scope's diff: the same {@link #scan} with the two
     * inputs every caller would otherwise have to derive for itself -- the
     * worktree to grep, and the diff's own files as the "inside the change"
     * set.
     *
     * <p>A scope with no worktree is {@code unavailable}, not empty: there
     * is no checkout to grep, so nothing was measured. That is the same
     * distinction {@link Result#unavailable} draws everywhere else, and the
     * one thing a surface built on this may not blur.</p>
     *
     * <p>Blocking, like {@link #scan}; never call on the FX thread.</p>
     */
    public static Result forScope(ReviewScope scope, ChangeGraph graph, UnifiedDiff diff) {
        Optional<Path> worktree = scope.worktree();
        if (worktree.isEmpty()) {
            return Result.unavailable("no checkout to search");
        }
        Map<String, Set<Integer>> changedNewLines = new HashMap<>();
        for (UnifiedDiff.FileDiff file : diff.files()) {
            Set<Integer> lines = changedNewLines.computeIfAbsent(file.path(), path -> new TreeSet<>());
            for (UnifiedDiff.Hunk hunk : file.hunks()) {
                for (UnifiedDiff.Line line : hunk.lines()) {
                    if (line.kind() == UnifiedDiff.Line.Kind.ADD && line.newLine().isPresent()) {
                        lines.add(line.newLine().getAsInt());
                    }
                }
            }
        }
        return scan(worktree.get(), graph, changedNewLines);
    }

    /**
     * {@link #scan(Path, ChangeGraph, Map)} treating every line of each of
     * {@code changedFiles} as changed, i.e. dropping those files whole.
     */
    public static Result scan(Path worktree, ChangeGraph graph, Set<String> changedFiles) {
        Map<String, Set<Integer>> everything = new HashMap<>();
        for (String file : changedFiles) {
            everything.put(file, new AllLines());
        }
        return scan(worktree, graph, everything);
    }

    /**
     * Where each of {@code graph}'s changed declarations is used other than on
     * a changed line: in files the change does not touch, and on the
     * unchanged lines of files it does (flagged
     * {@link Occurrence#inChangedFile}). Spawns one {@code git grep} over every
     * uniquely-named changed declaration at once. Blocking; never call on
     * the FX thread.
     */
    public static Result scan(Path worktree, ChangeGraph graph,
                              Map<String, Set<Integer>> changedNewLinesByFile) {
        SortedSet<String> symbols = graph.changedDeclarations();
        if (symbols.isEmpty()) {
            return new Result(Map.of(), false);
        }
        Path patterns = null;
        try {
            patterns = Files.createTempFile("drydock-fanin-", ".patterns");
            Files.writeString(patterns, String.join("\n", symbols), StandardCharsets.UTF_8);
            // -w is a PRE-FILTER, not the correctness mechanism: mentions()
            // below is, and it subsumes this. Not merely observed -- a
            // mutation dropping -w changed no result, including against a
            // repository of adversarial near-misses -- but provable: git's
            // word characters are ASCII [A-Za-z0-9_], mentions() requires
            // non-(isLetterOrDigit || '_') on both sides, and Java's set is a
            // strict SUPERSET of git's, so a boundary mentions() accepts is
            // one git also accepts. -w can therefore never drop a line
            // mentions() would have kept: it can only spare work.
            //
            // The work it spares is real: without it git streams back -- and
            // this class allocates an Occurrence for -- every line that
            // merely CONTAINS a changed name, which for a short declaration
            // like `id` is most of a repository. Do not read it as the reason
            // the count is right.
            List<String> command = List.of("git", "grep", "-z", "-n", "-F", "-w", "-f",
                    patterns.toString(), "--end-of-options");
            ProcessResult result = ProcessRunner.run(command, worktree, TIMEOUT);
            // git grep exits 1 for "no matches", a valid empty answer, not a
            // failure. Anything above 1 is.
            if (result.exitCode() > 1) {
                String excerpt = ProcessRunner.excerpt(result.stderr());
                LOG.log(Level.WARNING, "git grep for out-of-diff fan-in failed: " + excerpt);
                return Result.unavailable("git grep failed: " + excerpt);
            }
            List<Occurrence> occurrences = parse(result.stdout(), changedNewLinesByFile);
            Map<String, List<Occurrence>> bySymbol = new TreeMap<>();
            for (String symbol : symbols) {
                List<Occurrence> hits = occurrences.stream()
                        .filter(occurrence -> mentions(occurrence.text(), symbol))
                        .toList();
                if (!hits.isEmpty()) {
                    bySymbol.put(symbol, hits);
                }
            }
            return new Result(Collections.unmodifiableMap(bySymbol), false);
        } catch (ProcessTimeoutException e) {
            LOG.log(Level.WARNING, "git grep for out-of-diff fan-in timed out", e);
            return Result.unavailable("git grep timed out after " + TIMEOUT.toSeconds() + " s");
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.log(Level.WARNING, "git grep for out-of-diff fan-in could not run", e);
            return Result.unavailable("git grep could not run: " + e.getMessage());
        } finally {
            if (patterns != null) {
                try {
                    Files.deleteIfExists(patterns);
                } catch (IOException e) {
                    LOG.log(Level.FINE, "could not remove fan-in patterns file", e);
                }
            }
        }
    }

    /**
     * Whether {@code text} uses {@code symbol} as a whole word.
     *
     * <p>{@code git grep -w} decides which LINES come back; this decides
     * which of the scanned symbols each line is attributed to, and the two
     * have to agree or a line matched as a whole word for one symbol gets
     * attributed by substring to another ({@code Foo} collecting every use
     * of {@code FooBar}). Word characters are letters, digits and
     * underscore -- git's own definition, and the one {@link SymbolWords}'
     * identifiers are built from.</p>
     */
    static boolean mentions(String text, String symbol) {
        if (symbol.isEmpty()) {
            return false;
        }
        int from = 0;
        while (true) {
            int at = text.indexOf(symbol, from);
            if (at < 0) {
                return false;
            }
            boolean leftClear = at == 0 || !isWordCharacter(text.charAt(at - 1));
            int after = at + symbol.length();
            boolean rightClear = after == text.length() || !isWordCharacter(text.charAt(after));
            if (leftClear && rightClear) {
                return true;
            }
            from = at + 1;
        }
    }

    private static boolean isWordCharacter(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /**
     * Parses {@code git grep -z -n -F} output: one match per record,
     * records separated by {@code \n}, and within a record {@code
     * file<NUL>line<NUL>text}. A match on a changed new line is dropped --
     * the change wrote it, so it is not a caller of the change. A match
     * elsewhere in a changed file is kept with {@code inChangedFile} set. A record that does not
     * split into exactly the three NUL-separated fields, or whose middle
     * field is not a line number, is skipped rather than treated as fatal.
     */
    static List<Occurrence> parse(String stdout, Set<String> changedFiles) {
        Map<String, Set<Integer>> everything = new HashMap<>();
        for (String file : changedFiles) {
            everything.put(file, new AllLines());
        }
        return parse(stdout, everything);
    }

    static List<Occurrence> parse(String stdout, Map<String, Set<Integer>> changedNewLinesByFile) {
        List<Occurrence> occurrences = new ArrayList<>();
        for (String record : stdout.split("\n", -1)) {
            if (record.isEmpty()) {
                continue;
            }
            String[] fields = record.split(String.valueOf(FIELD_SEPARATOR), -1);
            if (fields.length != 3) {
                LOG.log(Level.FINE, "skipping malformed git grep row (expected file\\0line\\0text)");
                continue;
            }
            String file = fields[0];
            try {
                int line = Integer.parseInt(fields[1]);
                Set<Integer> changedLines = changedNewLinesByFile.get(file);
                if (changedLines != null && changedLines.contains(line)) {
                    continue;
                }
                occurrences.add(new Occurrence(file, line, fields[2], changedLines != null));
            } catch (NumberFormatException e) {
                LOG.log(Level.FINE, "skipping git grep row with a non-numeric line number");
            }
        }
        return List.copyOf(occurrences);
    }
}
