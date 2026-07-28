package app.drydock.ui.review;

import app.drydock.git.UnifiedDiff;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The symbol lens's index (spec §4.4): where an identifier appears, and
 * whether each of those places is inside the diff or merely near it.
 *
 * <p><strong>Local, never MCP.</strong> "Where is this symbol used" is a
 * question about the code in front of the reader, and asking an agent would
 * make it slow, non-deterministic, and unavailable exactly when Review has
 * no reviewer configured -- which it must always survive.</p>
 *
 * <p>A lexical index, deliberately. A real resolver would need a compiler
 * per language; what the lens promises is "here is every place this text
 * appears", and a lexical index delivers exactly that without pretending to
 * more. Call sites are therefore <em>occurrences</em>, and the popover says
 * so.</p>
 */
final class SymbolIndex {

    /** Identifiers shorter than this are noise: {@code i}, {@code id}, {@code of}. */
    private static final int MIN_LENGTH = 3;

    /** Java-ish identifiers; the same shape the diff column's lexer treats as a word. */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * Words never worth a lens. Keywords are not symbols, and offering a
     * popover on {@code return} teaches the reader that the dotted underline
     * means nothing.
     */
    private static final Set<String> STOP_WORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof",
            "int", "interface", "long", "native", "new", "package", "private", "protected",
            "public", "record", "return", "sealed", "short", "static", "super", "switch",
            "synchronized", "this", "throw", "throws", "transient", "try", "var", "void",
            "volatile", "while", "yield", "true", "false", "null",
            "fun", "val", "let", "function", "def", "self", "from", "with", "and", "not");

    /** One place a symbol appears. */
    record Occurrence(String file, int line, String text, boolean inDiff) {
    }

    /** Everything the popover shows about one symbol. */
    record Entry(String symbol, List<Occurrence> occurrences) {
        /** Occurrences on lines the diff actually changed. */
        long inDiffCount() {
            return occurrences.stream().filter(Occurrence::inDiff).count();
        }
    }

    private final Map<String, List<Occurrence>> bySymbol;

    private SymbolIndex(Map<String, List<Occurrence>> bySymbol) {
        this.bySymbol = bySymbol;
    }

    /**
     * Indexes every identifier in {@code diff}, marking an occurrence as
     * "in diff" when it sits on a changed line and "not touched" when it sits
     * on a context line -- which is exactly the distinction the popover's
     * chips draw.
     */
    static SymbolIndex of(UnifiedDiff diff) {
        Map<String, List<Occurrence>> index = new LinkedHashMap<>();
        for (UnifiedDiff.FileDiff file : diff.files()) {
            for (UnifiedDiff.Hunk hunk : file.hunks()) {
                for (UnifiedDiff.Line line : hunk.lines()) {
                    boolean changed = line.kind() != UnifiedDiff.Line.Kind.CONTEXT;
                    int number = line.newLine().orElse(line.oldLine().orElse(0));
                    Matcher matcher = IDENTIFIER.matcher(line.text());
                    while (matcher.find()) {
                        String symbol = matcher.group();
                        if (symbol.length() < MIN_LENGTH || STOP_WORDS.contains(symbol)) {
                            continue;
                        }
                        index.computeIfAbsent(symbol, key -> new ArrayList<>())
                                .add(new Occurrence(file.path(), number, line.text().strip(), changed));
                    }
                }
            }
        }
        return new SymbolIndex(index);
    }

    /**
     * The entry for {@code symbol}, if the lens has anything to say about it.
     * A symbol that appears exactly once is not worth a popover -- the reader
     * is looking at its only occurrence.
     */
    Optional<Entry> lookup(String symbol) {
        List<Occurrence> occurrences = bySymbol.get(symbol);
        if (occurrences == null || occurrences.size() < 2) {
            return Optional.empty();
        }
        return Optional.of(new Entry(symbol, List.copyOf(occurrences)));
    }

    /** Whether {@code symbol} should carry the lens's dotted underline. */
    boolean hasEntry(String symbol) {
        return lookup(symbol).isPresent();
    }

    /** How many symbols the index holds (diagnostics and tests). */
    int size() {
        return bySymbol.size();
    }
}
