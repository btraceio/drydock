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
import java.util.regex.Pattern;
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
 * <p><b>Self-declaring files are dropped.</b> A word-bounded match still
 * cannot tell the change's {@code text} from another file's own
 * {@code text} -- a declaration named like a common field swamps the
 * caller list with files that merely have a member of the same name. So a
 * file outside the change whose own occurrence lines look like a
 * declaration of the symbol (its own field, method, type or local of that
 * name -- {@link #looksLikeDeclaration}, a line-lexical probe, no parse)
 * is dropped whole for that symbol: its occurrences are uses of its own
 * symbol, not callers of the change's. Occurrences <em>inside</em> changed
 * files are never dropped this way -- an unedited call site in an edited
 * file is exactly where a signature change breaks, and the change graph,
 * not a heuristic, says what those files declare.</p>
 *
 * <p><b>Too-common symbols are counted, not listed.</b> A symbol whose
 * occurrences survive the drop but still number more than {@link
 * #MAX_ATTRIBUTABLE} is noise as a list and signal as a count, so its
 * occurrences are removed from {@link Result#bySymbol} and reported in
 * {@link Result#suppressedCounts} instead: consumers show "N occurrences,
 * too common to attribute" rather than N rows of links to chase. The
 * signature-changed signal survives through {@link Result#occurrences},
 * which counts listed and suppressed alike.</p>
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
     * A symbol with more attributable occurrences than this is a count, not
     * a list: its rows are removed from {@link Result#bySymbol} and its
     * occurrences reported through {@link Result#suppressedCounts}. Fifty
     * rows of "occurrences, not resolved references" is already more than
     * a reviewer reads; past that the list only reorders noise.
     */
    public static final int MAX_ATTRIBUTABLE = 50;

    /**
     * One place {@code symbol} is used, outside the change. {@code text} is
     * kept exactly as {@code git grep} reports it, leading whitespace
     * included -- deliberately, not a missed {@code .strip()}: the popover
     * this feeds is showing a source line, and its original indentation is
     * part of reading it, not noise to trim.
     *
     * <p>{@code bound} is the scope tier: the parse tree tied this
     * occurrence to the symbol's own declaration -- a real reference, not a
     * shared name. False is the default and the honest fallback: it means
     * "not proven", which covers a name-only match AND an occurrence the
     * classification budget did not reach.</p>
     */
    public record Occurrence(String file, int line, String text, boolean inChangedFile, boolean bound) {

        /** An occurrence in a file the change does not touch, unclassified. */
        public Occurrence(String file, int line, String text) {
            this(file, line, text, false, false);
        }

        /** An occurrence in a changed file, unclassified. */
        public Occurrence(String file, int line, String text, boolean inChangedFile) {
            this(file, line, text, inChangedFile, false);
        }
    }

    /**
     * {@code unavailableReason} present means the scan could not run: absent,
     * not zero, and the reason says why (no checkout, timeout, git failure).
     *
     * @param bySymbol          each symbol's attributable occurrences:
     *                          self-declaring files already dropped, and
     *                          symbols too common to list already moved to
     *                          {@code suppressedCounts}
     * @param suppressedCounts  symbols whose attributable occurrences
     *                          exceeded {@link #MAX_ATTRIBUTABLE}, and how
     *                          many there were: absent from {@code bySymbol},
     *                          counted by {@link #occurrences}
     */
    public record Result(Map<String, List<Occurrence>> bySymbol, Map<String, Integer> suppressedCounts,
                         Optional<String> unavailableReason) {

        public Result {
            Objects.requireNonNull(unavailableReason, "unavailableReason");
            bySymbol = Map.copyOf(bySymbol);
            suppressedCounts = Map.copyOf(suppressedCounts);
        }

        /** No symbol was suppressed. */
        public Result(Map<String, List<Occurrence>> bySymbol, Optional<String> unavailableReason) {
            this(bySymbol, Map.of(), unavailableReason);
        }

        /** {@code unavailable} true carries the generic reason {@code "unavailable"}. */
        public Result(Map<String, List<Occurrence>> bySymbol, boolean unavailable) {
            this(bySymbol, Map.of(), unavailable ? Optional.of("unavailable") : Optional.empty());
        }

        public boolean unavailable() {
            return unavailableReason.isPresent();
        }

        /**
         * How many unedited lines outside the change still spell {@code symbol}:
         * the listed occurrences plus the ones suppressed as too common. The
         * signature-changed signal draws on this, so suppressing a list never
         * silences the flag.
         */
        public int occurrences(String symbol) {
            return bySymbol.getOrDefault(symbol, List.of()).size()
                    + suppressedCounts.getOrDefault(symbol, 0);
        }

        private static Result unavailable(String reason) {
            return new Result(Map.of(), Map.of(), Optional.of(reason));
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
            Map<String, List<Occurrence>> raw = new TreeMap<>();
            for (String symbol : symbols) {
                List<Occurrence> hits = occurrences.stream()
                        .filter(occurrence -> mentions(occurrence.text(), symbol))
                        .toList();
                if (!hits.isEmpty()) {
                    raw.put(symbol, hits);
                }
            }
            return classified(bindScope(worktree, graph, raw));
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
     * Files whose occurrences the scope classification may parse. A cap, not
     * a target: past it the rest stay unclassified -- {@code bound} false,
     * the honest "not proven" -- because a monster repository must not turn
     * the fan-in scan into a whole-repo parse. The parse results are cached
     * per binder, so the cap bounds TOTAL files, not per symbol.
     */
    static final int MAX_BIND_FILES = 200;

    /** Bindable languages today, matching the scope binder's walkers: Java and Kotlin. */
    static boolean bindable(String fileName) {
        return fileName.endsWith(".java") || fileName.endsWith(".kt") || fileName.endsWith(".kts");
    }

    /**
     * Classifies the fan-in's occurrences through the scope binder: an
     * occurrence whose receiver the parse tree ties to the symbol's own
     * declaration carries {@code bound} true, and a same-named member on an
     * unrelated class stays a name match. The classification runs only for
     * Java files under the {@link #MAX_BIND_FILES} budget; everything else,
     * and every failure, leaves the occurrence exactly as the grep found it.
     *
     * <p>Ambiguity refuses, same as the binder itself: a symbol several
     * changed files declare has no single declaration to bind to, so all its
     * occurrences stay name-tier.</p>
     */
    static Map<String, List<Occurrence>> bindScope(Path worktree, ChangeGraph graph,
                                                    Map<String, List<Occurrence>> raw) {
        List<Path> javaFiles = raw.values().stream()
                .flatMap(List::stream)
                .map(Occurrence::file)
                .distinct()
                .filter(file -> bindable(file))
                .map(Path::of)
                .toList();
        if (javaFiles.isEmpty() || javaFiles.size() > MAX_BIND_FILES) {
            return raw;
        }
        ScopeBinder binder = new ScopeBinder(worktree);
        Map<String, List<Occurrence>> bound = new TreeMap<>();
        for (Map.Entry<String, List<Occurrence>> entry : raw.entrySet()) {
            String symbol = entry.getKey();
            Optional<ScopeBinder.Declaration> queried = graph.declarationSite(symbol)
                    .filter(site -> site.lineKey().startsWith("n"))
                    .map(site -> new ScopeBinder.Declaration(Path.of(site.file()),
                            Integer.parseInt(site.lineKey().substring(1))));
            Map<Path, Set<Integer>> boundLinesByFile = new HashMap<>();
            if (queried.isPresent()) {
                for (Path file : javaFiles) {
                    ScopeBinder.Binding binding = binder.bind(symbol, queried, file);
                    if (!binding.isEmpty()) {
                        boundLinesByFile.put(file, Set.copyOf(binding.boundLines().keySet()));
                    }
                }
            }
            List<Occurrence> classified = new ArrayList<>();
            for (Occurrence occurrence : entry.getValue()) {
                if (bindable(occurrence.file())) {
                    Set<Integer> lines = boundLinesByFile.get(Path.of(occurrence.file()));
                    if (lines != null && lines.contains(occurrence.line())) {
                        classified.add(new Occurrence(occurrence.file(), occurrence.line(),
                                occurrence.text(), occurrence.inChangedFile(), true));
                        continue;
                    }
                }
                classified.add(occurrence);
            }
            bound.put(symbol, List.copyOf(classified));
        }
        return bound;
    }

    /**
     * Turns the raw per-symbol occurrence lists of one scan into a {@link
     * Result}: self-declaring files dropped per symbol, and symbols too
     * common to list moved to {@link Result#suppressedCounts}. Separated
     * from {@link #scan} so the filtering is testable without a git spawn,
     * exactly like {@link #parse}.
     */
    static Result classified(Map<String, List<Occurrence>> rawBySymbol) {
        Map<String, List<Occurrence>> attributable = new TreeMap<>();
        Map<String, Integer> suppressed = new TreeMap<>();
        rawBySymbol.forEach((symbol, occurrences) -> {
            Set<String> selfDeclaring = new TreeSet<>();
            DeclarationLine probe = DeclarationLine.forSymbol(symbol);
            for (Occurrence occurrence : occurrences) {
                if (!occurrence.inChangedFile() && probe.matches(occurrence.text())) {
                    selfDeclaring.add(occurrence.file());
                }
            }
            List<Occurrence> kept = new ArrayList<>();
            for (Occurrence occurrence : occurrences) {
                if (occurrence.inChangedFile() || !selfDeclaring.contains(occurrence.file())) {
                    kept.add(occurrence);
                }
            }
            if (kept.size() > MAX_ATTRIBUTABLE) {
                suppressed.put(symbol, kept.size());
            } else if (!kept.isEmpty()) {
                attributable.put(symbol, List.copyOf(kept));
            }
        });
        return new Result(Collections.unmodifiableMap(attributable),
                Collections.unmodifiableMap(suppressed), Optional.empty());
    }

    /**
     * Whether {@code rawLine} looks like {@code symbol}'s own declaration --
     * the change's {@code text} versus this file's own field, method, type
     * or local named {@code text}.
     *
     * <p>Line-lexical, deliberately: a parse of every hit file would cost a
     * tree-sitter run per file of a list that exists because a common name
     * matches hundreds of them. The probe trades recall for that -- a
     * declaration whose signature spills onto the next line, a record
     * component ({@code record X(int text)}), a Go field -- reads as a use
     * and the file is kept. The cost of a miss is one more occurrence row
     * in a list already labelled "occurrences, not resolved references";
     * the cost of the opposite error (a plain call read as a declaration)
     * would be a genuine caller silently dropped, so every pattern is built
     * to fail toward "use": a receiver before the name disqualifies, a
     * {@code new} before the name disqualifies, an assignment needs a
     * modifier keyword on the line, and comments, imports and {@code
     * return} never count.</p>
     */
    static boolean looksLikeDeclaration(String rawLine, String symbol) {
        return DeclarationLine.forSymbol(symbol).matches(rawLine);
    }

    /** One symbol's declaration patterns, in the shape of the peek card's own. */
    private record DeclarationLine(Pattern type, Pattern function, Pattern method, Pattern field, String symbol) {

        static DeclarationLine forSymbol(String symbol) {
            String quoted = Pattern.quote(symbol);
            return new DeclarationLine(
                    // class/interface/enum/record/trait/struct/type X -- a type of that name.
                    Pattern.compile("\\b(class|interface|enum|record|trait|struct|type|fn)\\s+" + quoted + "\\b"),
                    // fun/func/def X( -- a function of that name (Go methods keep their
                    // receiver in the parentheses, so they miss; that is the documented
                    // recall trade).
                    Pattern.compile("\\b(fun|func|function|def|sub)\\s+" + quoted + "\\s*\\("),
                    // A method signature: only type-ish tokens, modifiers and
                    // annotations before the name (a receiver's '.' is not among
                    // them), a parameter list, and an opening brace after it -- so
                    // a call statement, which ends in ';' or continues into an
                    // expression, does not match.
                    Pattern.compile("^[\\w@<>\\[\\],:?&\\s]*\\b" + quoted
                            + "\\s*\\([^;]*\\)\\s*(throws\\s+[\\w,.\\s]+)?\\s*\\{.*$"),
                    // A field or local: a modifier keyword earlier on the line and
                    // the name immediately before a ';' or '='. An unmodified
                    // assignment (foo.text = x) is a use of somebody's member and
                    // never counts.
                    Pattern.compile("\\b(private|public|protected|internal|static|final|const|let|var|val)\\b"
                            + "[^=;]*\\b" + Pattern.quote(symbol) + "\\s*[;=]"),
                    symbol);
        }

        boolean matches(String rawLine) {
            String line = rawLine.strip();
            if (line.isEmpty()
                    || line.startsWith("import ") || line.startsWith("package ")
                    || line.startsWith("#include")
                    || line.startsWith("//") || line.startsWith("/*") || line.startsWith("*")
                    || line.startsWith("#")) {
                return false;
            }
            if (!(type().matcher(line).find() || function().matcher(line).find()
                    || method().matcher(line).find() || field().matcher(line).find())) {
                return false;
            }
            // new X(...) is a use of X even when it opens an anonymous
            // class body, which the method pattern otherwise matches.
            return !Pattern.compile("\\bnew\\s+" + Pattern.quote(symbol()) + "\\b")
                    .matcher(line).find();
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
