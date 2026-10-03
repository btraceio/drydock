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

    /** The best declaration of {@code symbol}, or empty when none was found. */
    CompletableFuture<Optional<Usage>> declaration(String symbol);

    /** Every place {@code symbol} occurs. */
    CompletableFuture<List<Usage>> usages(String symbol);

    /** One located line, and the warrant it was found under. */
    record Usage(String file, int line, String text, Provenance provenance) {
        public Usage {
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(provenance, "provenance");
        }
    }
}
