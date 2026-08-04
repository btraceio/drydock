package app.drydock.ui.explorer;

import app.drydock.review.SymbolWords;
import app.drydock.search.SessionSearchService;
import app.drydock.search.SessionSearchService.FileMatches;
import app.drydock.search.SessionSearchService.TextMatch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a clicked identifier to the {@link SymbolPeek} a peek card shows
 * (Explorer delta, part 1).
 *
 * <p><strong>Local, never MCP</strong> -- the same rule the Review tab's
 * symbol lens follows. "Where does this live" is a question about the code
 * in front of the reader; routing it through an agent would make peeking
 * slow, non-deterministic, and unavailable exactly when no reviewer is
 * configured, which the Explorer must always survive.</p>
 *
 * <p><strong>Lexical, and honest about it.</strong> Occurrences come from
 * the session's own text search; the line the card opens on is the
 * best-scoring <em>candidate</em> declaration ({@link
 * #scoreDeclaration}), not a compiled answer. When nothing scores as a
 * declaration the card still opens -- on the first occurrence -- and says
 * so, because "I could not find the definition" is more useful shown at a
 * real call site than as an error.</p>
 */
public final class SymbolPeekService {

    /** Identifiers this short are noise; the Review lens's own threshold. */
    static final int MIN_SYMBOL_LENGTH = SymbolWords.MIN_LENGTH;

    /** Excerpt cap: enough to see a small method whole, short enough to stay a card. */
    static final int MAX_EXCERPT_LINES = 14;

    /** Guard against peeking into a generated monster; the viewer's own cap. */
    private static final long MAX_FILE_BYTES = 2 * 1024 * 1024;

    private final Path searchRoot;
    private final SessionSearchService searchService;

    /**
     * Where {@link #resolve} runs. Load-bearing, not decorative: the search
     * future can already be complete when {@code peek()} chains onto it (a
     * warm cache, an empty result), and a plain {@code thenApply} would then
     * run the file read and the scoring inline on the FX thread.
     */
    private final java.util.concurrent.Executor resolveExecutor =
            runnable -> Thread.ofVirtual().start(runnable);

    public SymbolPeekService(Path searchRoot, SessionSearchService searchService) {
        this.searchRoot = searchRoot;
        this.searchService = searchService;
    }

    /**
     * Resolves {@code symbol}, marking occurrences against {@code
     * changedLines} (the diff overlay's current scope, captured by the
     * caller on the FX thread).
     *
     * <p>Empty when the symbol is too short or occurs nowhere -- the caller
     * simply does not open a card, which is why the underline is only
     * offered for symbols the index knows.</p>
     */
    public CompletableFuture<Optional<SymbolPeek>> peek(String symbol, Map<Path, Set<Integer>> changedLines) {
        if (symbol == null || symbol.length() < MIN_SYMBOL_LENGTH) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        Map<Path, Set<Integer>> changed = Map.copyOf(changedLines);
        return searchService.searchText(searchRoot, symbol)
                .thenApplyAsync(matches -> resolve(symbol, matches, changed), resolveExecutor);
    }

    private Optional<SymbolPeek> resolve(String symbol, List<FileMatches> files, Map<Path, Set<Integer>> changed) {
        List<Candidate> candidates = new ArrayList<>();
        // Compiled once per peek, not once per matching line: a common
        // identifier comes back with up to MAX_MATCHES of them.
        DeclarationPatterns patterns = DeclarationPatterns.forSymbol(symbol);
        for (FileMatches file : files) {
            for (TextMatch match : file.matches()) {
                if (!isWholeWord(match.lineText(), symbol)) {
                    continue;
                }
                candidates.add(new Candidate(file.file(), file.relativePath(), match.lineNumber(),
                        match.lineText().strip(),
                        patterns.score(file.relativePath(), match.lineText(), symbol)));
            }
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        List<SymbolPeek.Occurrence> occurrences = candidates.stream()
                .map(c -> new SymbolPeek.Occurrence(c.relativePath, c.line, c.text,
                        changed.getOrDefault(c.relativePath, Set.of()).contains(c.line)))
                .toList();

        Candidate best = candidates.stream()
                .max(Comparator.comparingInt((Candidate c) -> c.score)
                        .thenComparing(c -> -c.line))
                .orElseThrow();
        boolean declaration = best.score > 0;

        List<String> excerpt = readExcerpt(best.file, best.line);
        if (excerpt.isEmpty()) {
            excerpt = List.of(best.text);
        }
        Set<Integer> changedInExcerpt = new java.util.LinkedHashSet<>();
        Set<Integer> fileChanged = changed.getOrDefault(best.relativePath, Set.of());
        for (int i = 0; i < excerpt.size(); i++) {
            if (fileChanged.contains(best.line + i)) {
                changedInExcerpt.add(best.line + i);
            }
        }
        String title = symbol + " · " + best.relativePath.getFileName()
                + (declaration ? "" : " · first occurrence");
        return Optional.of(new SymbolPeek(symbol, title, best.file, best.relativePath, best.line,
                excerpt, changedInExcerpt, occurrences, declaration));
    }

    private record Candidate(Path file, Path relativePath, int line, String text, int score) {
    }

    /**
     * Reads {@code MAX_EXCERPT_LINES} of {@code file} from {@code startLine},
     * stopping early once the block opened on the first line closes -- so a
     * three-line method is a three-line card rather than fourteen lines of
     * whatever follows it.
     */
    static List<String> readExcerpt(Path file, int startLine) {
        List<String> lines = new ArrayList<>();
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                return List.of();
            }
            List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
            int from = Math.max(0, startLine - 1);
            int depth = 0;
            boolean opened = false;
            for (int i = from; i < all.size() && lines.size() < MAX_EXCERPT_LINES; i++) {
                String line = all.get(i);
                lines.add(line);
                for (int c = 0; c < line.length(); c++) {
                    if (line.charAt(c) == '{') {
                        depth++;
                        opened = true;
                    } else if (line.charAt(c) == '}') {
                        depth--;
                    }
                }
                if (opened && depth <= 0) {
                    break;
                }
            }
        } catch (IOException | java.io.UncheckedIOException e) {
            // A peek that cannot read the file is not an error worth a banner:
            // the caller falls back to the matched line itself.
            return List.of();
        }
        return lines;
    }

    /** True when {@code symbol} appears in {@code line} on identifier boundaries. */
    static boolean isWholeWord(String line, String symbol) {
        int from = 0;
        int at;
        while ((at = line.indexOf(symbol, from)) >= 0) {
            boolean leftOk = at == 0 || !isIdentifierChar(line.charAt(at - 1));
            int end = at + symbol.length();
            boolean rightOk = end >= line.length() || !isIdentifierChar(line.charAt(end));
            if (leftOk && rightOk) {
                return true;
            }
            from = at + 1;
        }
        return false;
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /**
     * How much this line looks like {@code symbol}'s declaration. Zero means
     * "just an occurrence"; the card says so rather than claiming a
     * definition it did not find.
     */
    static int scoreDeclaration(Path relativePath, String rawLine, String symbol) {
        return DeclarationPatterns.forSymbol(symbol).score(relativePath, rawLine, symbol);
    }

    /** One symbol's declaration patterns, compiled once and reused across every candidate line. */
    private record DeclarationPatterns(Pattern type, Pattern function, Pattern signature,
                                       Pattern assignment, Pattern modifier) {

        static DeclarationPatterns forSymbol(String symbol) {
            String quoted = Pattern.quote(symbol);
            return new DeclarationPatterns(
                    Pattern.compile("\\b(class|interface|record|enum|trait|struct|type)\\s+" + quoted + "\\b"),
                    Pattern.compile("\\b(fun|def|func|function|sub)\\s+" + quoted + "\\s*\\("),
                    Pattern.compile("^[\\w@<>\\[\\],.?&\\s]*\\b" + quoted + "\\s*\\([^;]*\\)\\s*"
                            + "(throws [\\w,.\\s]+)?\\{?$"),
                    Pattern.compile("\\b" + quoted + "\\s*=[^=]"),
                    Pattern.compile("^(private|public|protected|static|final|const|let|var|val)\\b"));
        }

        int score(Path relativePath, String rawLine, String symbol) {
            return scoreWith(this, relativePath, rawLine, symbol);
        }
    }

    private static int scoreWith(DeclarationPatterns patterns, Path relativePath, String rawLine, String symbol) {
        String line = rawLine.strip();
        if (line.startsWith("import ") || line.startsWith("//") || line.startsWith("*")
                || line.startsWith("#include")) {
            return 0;
        }
        int score = 0;
        String base = relativePath.getFileName().toString();
        int dot = base.lastIndexOf('.');
        if ((dot > 0 ? base.substring(0, dot) : base).equals(symbol)) {
            // Foo lives in Foo.java far more often than it does not.
            score += 6;
        }
        if (patterns.type().matcher(line).find()) {
            score += 6;
        }
        if (patterns.function().matcher(line).find()) {
            score += 5;
        }
        if (patterns.signature().matcher(line).find()
                && !line.startsWith("return ") && !line.contains("=")) {
            // A method signature: the symbol applied to a parameter list, at
            // the head of the line, not inside an expression.
            score += 4;
        }
        if (patterns.assignment().matcher(line).find() && patterns.modifier().matcher(line).find()) {
            score += 3;
        }
        return score;
    }

    /**
     * The identifier at {@code caret} in {@code line}, if there is one worth
     * peeking at. Shared by the viewer's click handler and its tests.
     */
    static Optional<String> identifierAt(String line, int caret) {
        if (line == null || caret < 0 || caret > line.length()) {
            return Optional.empty();
        }
        Matcher matcher = SymbolWords.IDENTIFIER.matcher(line);
        while (matcher.find()) {
            if (caret >= matcher.start() && caret <= matcher.end()) {
                String word = matcher.group();
                return SymbolWords.isSymbol(word) ? Optional.of(word) : Optional.empty();
            }
        }
        return Optional.empty();
    }

}
