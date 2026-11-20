package app.drydock.review;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Where a symbol is declared and where it is used (spec §6, the {@code
 * UsageProvider} seam).
 *
 * <p>One shape for every source of the answer. The lexical implementation
 * matches names and labels its results {@link Provenance#MEASURED}; a
 * later language-server implementation would label its own {@link
 * Provenance#RESOLVED}, and is used only when a server for the language is
 * running and indexed. Every answer is a future because both kinds search:
 * a caller must never wait for one on the FX thread.</p>
 */
public interface UsageProvider {

    /**
     * The best declaration of {@code symbol}, or empty when it occurs
     * nowhere. A provider that found only a likely candidate returns it with
     * {@link Usage#resolvedDeclaration} false rather than nothing.
     */
    CompletableFuture<Optional<Usage>> declaration(String symbol);

    /** Every place {@code symbol} occurs. */
    CompletableFuture<List<Usage>> usages(String symbol);

    /**
     * The usages answer with the tier's status attached (spec §7).
     * The default stands behind every row {@link #usages(String)}
     * returns: a provider that does not distinguish was built to
     * answer, so its answer is {@link UsagesAnswer.Status#ANSWERED},
     * empty list included. Existing implementations and callers are
     * unchanged by it.
     */
    default CompletableFuture<UsagesAnswer> usagesAnswer(String symbol) {
        return usages(symbol).thenApply(rows -> new UsagesAnswer(rows, UsagesAnswer.Status.ANSWERED));
    }

    /**
     * One located line, and the warrant it was found under.
     * {@code resolvedDeclaration} is true only for a declaration the
     * provider is confident of; a fallback candidate, and every plain usage,
     * carry false.
     */
    record Usage(String file, int line, String text, Provenance provenance, boolean resolvedDeclaration) {
        public Usage {
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(provenance, "provenance");
        }
    }

    /**
     * One tier's usages rows and whether they are an answer at all
     * (spec §7). A composer over this seam composes upgrade-only:
     * {@link Status#ANSWERED} rows may confirm lower-tier rows as
     * {@link Provenance#RESOLVED}, never erase or downgrade what the
     * lower tiers produced -- an empty list is an answer, not a
     * failure; {@link Status#UNAVAILABLE} and {@link Status#INAPPLICABLE}
     * carry no rows of their own and leave every lower-tier row in
     * place.
     */
    record UsagesAnswer(List<Usage> usages, Status status) {
        public UsagesAnswer {
            Objects.requireNonNull(usages, "usages");
            Objects.requireNonNull(status, "status");
            usages = List.copyOf(usages);
        }

        /**
         * What kind of answer the rows are (spec §7). The distinction
         * that matters to a composer: a valid answer may add warrant,
         * a tier that could not or does not apply may not.
         */
        public enum Status {
            /**
             * A valid answer from this tier. An empty list is an
             * answer -- a server's null or empty references result
             * is a successful query (spec §7), not a failure.
             */
            ANSWERED,

            /**
             * This tier could not answer: no server, not yet
             * indexed, timed out, or failed. No rows are carried.
             */
            UNAVAILABLE,

            /**
             * The tier does not apply to the symbol, e.g. a Java-only
             * server asked about a non-Java file. No rows are
             * carried.
             */
            INAPPLICABLE
        }
    }
}
