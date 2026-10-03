package app.drydock.ui.nav;

import app.drydock.review.Provenance;
import app.drydock.review.UsageProvider;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * The name-matching {@link UsageProvider}: answers from {@link
 * SymbolPeekService}'s lexical index, so every result is {@link
 * Provenance#MEASURED} -- an occurrence of the text, not a resolved
 * reference.
 *
 * <p>{@code changedLines} is the diff scope the peek marks occurrences
 * against, captured once by the caller.</p>
 */
public final class LexicalUsageProvider implements UsageProvider {

    private final SymbolPeekService peeks;
    private final Map<Path, Set<Integer>> changedLines;

    public LexicalUsageProvider(SymbolPeekService peeks, Map<Path, Set<Integer>> changedLines) {
        this.peeks = peeks;
        this.changedLines = Map.copyOf(changedLines);
    }

    /**
     * The peek's best-scoring candidate, when it scored as a declaration. A
     * peek that only found a first occurrence is not a declaration, and is
     * not reported as one.
     */
    @Override
    public CompletableFuture<Optional<Usage>> declaration(String symbol) {
        return peeks.peek(symbol, changedLines).thenApply(peek -> peek
                .filter(SymbolPeek::resolvedDeclaration)
                .map(found -> new Usage(found.relativePath().toString(), found.startLine(),
                        found.lines().isEmpty() ? "" : found.lines().get(0).strip(),
                        Provenance.MEASURED)));
    }

    @Override
    public CompletableFuture<List<Usage>> usages(String symbol) {
        return peeks.peek(symbol, changedLines).thenApply(peek -> peek
                .map(found -> found.occurrences().stream()
                        .map(occurrence -> new Usage(occurrence.relativePath().toString(),
                                occurrence.line(), occurrence.text(), Provenance.MEASURED))
                        .toList())
                .orElse(List.of()));
    }
}
