package app.drydock.ui.review;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.SymbolWords;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    /**
     * What counts as a symbol lives in {@link app.drydock.review.SymbolWords},
     * shared with the Explorer's peek underline -- the delta requires the two
     * lenses to agree, and two copies of this list drifted the moment they
     * existed.
     */
    private static final Pattern IDENTIFIER = SymbolWords.IDENTIFIER;

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
                        if (!SymbolWords.isSymbol(symbol)) {
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
