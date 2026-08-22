package app.drydock.review;

import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.process.ProcessTimeoutException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
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
 * real reference from an unrelated identifier that happens to contain the
 * same text, the same trade this codebase already makes for an
 * ungrammared file (see {@link SymbolScan}). Attributing an occurrence to
 * every changed declaration whose name it textually contains -- rather than
 * resolving which one, if any, it actually refers to -- can occasionally
 * over-attribute when one changed symbol's name is a substring of
 * another's; a repository-wide semantic index would not have this
 * imprecision, which is exactly the cost this class is built to avoid
 * paying.</p>
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

    /** One place {@code symbol} is used, outside the change. */
    public record Occurrence(String file, int line, String text) {
    }

    /** {@code unavailable} means the scan could not run: absent, not zero. */
    public record Result(Map<String, List<Occurrence>> bySymbol, boolean unavailable) {
    }

    private OutOfDiffFanIn() {
    }

    /**
     * Where each of {@code graph}'s changed declarations is used outside
     * {@code changedFiles}. Spawns one {@code git grep} over every
     * uniquely-named changed declaration at once. Blocking; never call on
     * the FX thread.
     */
    public static Result scan(Path worktree, ChangeGraph graph, Set<String> changedFiles) {
        SortedSet<String> symbols = graph.changedDeclarations();
        if (symbols.isEmpty()) {
            return new Result(Map.of(), false);
        }
        Path patterns = null;
        try {
            patterns = Files.createTempFile("drydock-fanin-", ".patterns");
            Files.writeString(patterns, String.join("\n", symbols), StandardCharsets.UTF_8);
            List<String> command = List.of("git", "grep", "-z", "-n", "-F", "-f",
                    patterns.toString(), "--end-of-options");
            ProcessResult result = ProcessRunner.run(command, worktree, TIMEOUT);
            // git grep exits 1 for "no matches", a valid empty answer, not a
            // failure. Anything above 1 is.
            if (result.exitCode() > 1) {
                LOG.log(Level.WARNING, "git grep for out-of-diff fan-in failed: "
                        + ProcessRunner.excerpt(result.stderr()));
                return new Result(Map.of(), true);
            }
            List<Occurrence> occurrences = parse(result.stdout(), changedFiles);
            Map<String, List<Occurrence>> bySymbol = new TreeMap<>();
            for (String symbol : symbols) {
                List<Occurrence> hits = occurrences.stream()
                        .filter(occurrence -> occurrence.text().contains(symbol))
                        .toList();
                if (!hits.isEmpty()) {
                    bySymbol.put(symbol, hits);
                }
            }
            return new Result(Collections.unmodifiableMap(bySymbol), false);
        } catch (ProcessTimeoutException e) {
            LOG.log(Level.WARNING, "git grep for out-of-diff fan-in timed out", e);
            return new Result(Map.of(), true);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.log(Level.WARNING, "git grep for out-of-diff fan-in could not run", e);
            return new Result(Map.of(), true);
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
     * Parses {@code git grep -z -n -F} output: one match per record,
     * records separated by {@code \n}, and within a record {@code
     * file<NUL>line<NUL>text}. Occurrences inside {@code changedFiles} are
     * dropped -- they are not "outside" the change. A record that does not
     * split into exactly the three NUL-separated fields, or whose middle
     * field is not a line number, is skipped rather than treated as fatal.
     */
    static List<Occurrence> parse(String stdout, Set<String> changedFiles) {
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
            if (changedFiles.contains(file)) {
                continue;
            }
            try {
                occurrences.add(new Occurrence(file, Integer.parseInt(fields[1]), fields[2]));
            } catch (NumberFormatException e) {
                LOG.log(Level.FINE, "skipping git grep row with a non-numeric line number");
            }
        }
        return List.copyOf(occurrences);
    }
}
