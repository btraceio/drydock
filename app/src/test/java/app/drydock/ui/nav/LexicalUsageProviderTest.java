package app.drydock.ui.nav;

import app.drydock.review.Provenance;
import app.drydock.review.UsageProvider;
import app.drydock.review.UsageProvider.UsagesAnswer;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The name-matching {@link UsageProvider}: every answer is MEASURED, and a
 * declaration the peek only found as a first occurrence is still returned,
 * labelled as not resolved -- the peek's own honesty, not a silent gap.
 */
class LexicalUsageProviderTest {

    @Test
    void aScoredDeclarationIsReturnedAsResolved() throws Exception {
        LexicalUsageProvider provider = new LexicalUsageProvider(symbol -> peekOf(symbol, true));

        Optional<UsageProvider.Usage> declaration = provider.declaration("clamp").get();

        assertEquals(Optional.of(new UsageProvider.Usage("ui/Size.java", 12, "double clamp(double w) {",
                Provenance.MEASURED, true)), declaration);
    }

    @Test
    void aFirstOccurrenceFallbackIsReturnedAsNotResolved() throws Exception {
        LexicalUsageProvider provider = new LexicalUsageProvider(symbol -> peekOf(symbol, false));

        Optional<UsageProvider.Usage> declaration = provider.declaration("clamp").get();

        assertEquals(Optional.of(new UsageProvider.Usage("ui/Size.java", 12, "double clamp(double w) {",
                Provenance.MEASURED, false)), declaration);
    }

    @Test
    void nothingFoundIsNoDeclarationAndNoUsages() throws Exception {
        LexicalUsageProvider provider = new LexicalUsageProvider(
                symbol -> CompletableFuture.completedFuture(Optional.empty()));

        assertEquals(Optional.empty(), provider.declaration("clamp").get());
        assertEquals(List.of(), provider.usages("clamp").get());
    }

    @Test
    void anEmptyLexicalAnswerIsAnsweredNotUnavailable() throws Exception {
        LexicalUsageProvider provider = new LexicalUsageProvider(
                symbol -> CompletableFuture.completedFuture(Optional.empty()));

        UsagesAnswer answer = provider.usagesAnswer("clamp").get();

        assertEquals(UsagesAnswer.Status.ANSWERED, answer.status());
        assertEquals(List.of(), answer.usages());
    }

    @Test
    void usagesAreThePeeksOccurrences() throws Exception {
        LexicalUsageProvider provider = new LexicalUsageProvider(symbol -> peekOf(symbol, true));

        assertEquals(List.of(
                        new UsageProvider.Usage("ui/Size.java", 12, "double clamp(double w) {",
                                Provenance.MEASURED, false),
                        new UsageProvider.Usage("ui/Sidebar.java", 40, "sizing.clamp(width);",
                                Provenance.MEASURED, false)),
                provider.usages("clamp").get());
    }

    @Test
    void theLexicalAnswerCarriesTheSameRowsAsUsages() throws Exception {
        LexicalUsageProvider provider = new LexicalUsageProvider(symbol -> peekOf(symbol, true));

        UsagesAnswer answer = provider.usagesAnswer("clamp").get();

        assertEquals(UsagesAnswer.Status.ANSWERED, answer.status());
        assertEquals(provider.usages("clamp").get(), answer.usages());
    }

    @Test
    void aProviderWithoutItsOwnAnswerDefaultWrapsUsagesAsAnswered() throws Exception {
        UsageProvider bare = new UsageProvider() {
            @Override
            public CompletableFuture<Optional<Usage>> declaration(String symbol) {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public CompletableFuture<List<Usage>> usages(String symbol) {
                return CompletableFuture.completedFuture(List.of(
                        new Usage("ui/Size.java", 12, "double clamp(double w) {",
                                Provenance.MEASURED, false)));
            }
        };

        UsagesAnswer answer = bare.usagesAnswer("clamp").get();

        assertEquals(UsagesAnswer.Status.ANSWERED, answer.status());
        assertEquals(bare.usages("clamp").get(), answer.usages());
    }

    @Test
    void unavailableAndInapplicableAnswersAreConstructibleWithoutRows() {
        assertEquals(UsagesAnswer.Status.UNAVAILABLE,
                new UsagesAnswer(List.of(), UsagesAnswer.Status.UNAVAILABLE).status());
        assertEquals(UsagesAnswer.Status.INAPPLICABLE,
                new UsagesAnswer(List.of(), UsagesAnswer.Status.INAPPLICABLE).status());
    }

    @Test
    void answerRowsAreImmutable() {
        List<UsageProvider.Usage> rows = new ArrayList<>();
        rows.add(new UsageProvider.Usage("ui/Size.java", 12, "double clamp(double w) {",
                Provenance.MEASURED, false));

        UsagesAnswer answer = new UsagesAnswer(rows, UsagesAnswer.Status.ANSWERED);
        rows.clear();

        assertEquals(1, answer.usages().size());
        assertThrows(UnsupportedOperationException.class, () -> answer.usages().add(
                new UsageProvider.Usage("ui/Sidebar.java", 40, "sizing.clamp(width);",
                        Provenance.MEASURED, false)));
    }

    private static CompletableFuture<Optional<SymbolPeek>> peekOf(String symbol, boolean resolved) {
        return CompletableFuture.completedFuture(Optional.of(new SymbolPeek(symbol, symbol + " · Size.java",
                Path.of("/repo/ui/Size.java"), Path.of("ui/Size.java"), 12,
                List.of("    double clamp(double w) {", "        return w;", "    }"), Set.of(),
                List.of(new SymbolPeek.Occurrence(Path.of("ui/Size.java"), 12, "double clamp(double w) {", false),
                        new SymbolPeek.Occurrence(Path.of("ui/Sidebar.java"), 40, "sizing.clamp(width);", true)),
                resolved)));
    }
}
