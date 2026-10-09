package app.drydock.ui.nav;

import app.drydock.review.Provenance;
import app.drydock.review.UsageProvider;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * The name-matching {@link UsageProvider}: answers from {@link
 * SymbolPeekService}'s lexical index, so every result is {@link
 * Provenance#MEASURED} -- an occurrence of the text, not a resolved
 * reference.
 *
 * <p>{@code changedLines} is the diff scope the peek marks occurrences
 * against, captured once by the caller.</p>
 *
 * <p>It inherits {@link UsageProvider#usagesAnswer(String)}'s default, which
 * is always ANSWERED: the lexical tier is the floor every higher tier
 * composes over (spec §7), so an empty list means "no occurrences found",
 * never "could not look".</p>
 */
public final class LexicalUsageProvider implements UsageProvider {

    private final Function<String, CompletableFuture<Optional<SymbolPeek>>> peek;

    /** Peeks through {@code peeks}, marking occurrences against {@code changedLines}. */
    public LexicalUsageProvider(SymbolPeekService peeks, Map<Path, Set<Integer>> changedLines) {
        this(symbolPeek(peeks, Map.copyOf(changedLines)));
    }

    /** Peeks through {@code peek}; the seam a test hands a canned peek through. */
    public LexicalUsageProvider(Function<String, CompletableFuture<Optional<SymbolPeek>>> peek) {
        this.peek = peek;
    }

    private static Function<String, CompletableFuture<Optional<SymbolPeek>>> symbolPeek(
            SymbolPeekService peeks, Map<Path, Set<Integer>> changedLines) {
        return symbol -> peeks.peek(symbol, changedLines);
    }

    /**
     * The peek's best-scoring candidate. When it did not score as a
     * declaration it is the first occurrence, returned with {@code
     * resolvedDeclaration} false -- the same honesty label the peek card
     * shows ("first occurrence") rather than a silent nothing.
     */
    @Override
    public CompletableFuture<Optional<Usage>> declaration(String symbol) {
        return peek.apply(symbol).thenApply(found -> found
                .map(best -> new Usage(best.relativePath().toString(), best.startLine(),
                        best.lines().isEmpty() ? "" : best.lines().get(0).strip(),
                        // A scope-bound declaration was confirmed by the
                        // parse tree, not the scoring heuristic -- the tier
                        // the provenance says it is.
                        best.declarationScopeBound() ? Provenance.SCOPED : Provenance.MEASURED,
                        best.resolvedDeclaration())));
    }

    @Override
    public CompletableFuture<List<Usage>> usages(String symbol) {
        return peek.apply(symbol).thenApply(found -> found
                .map(best -> best.occurrences().stream()
                        .map(occurrence -> new Usage(occurrence.relativePath().toString(),
                                occurrence.line(), occurrence.text(),
                                occurrence.bound() ? Provenance.SCOPED : Provenance.MEASURED,
                                false))
                        .toList())
                .orElse(List.of()));
    }
}
