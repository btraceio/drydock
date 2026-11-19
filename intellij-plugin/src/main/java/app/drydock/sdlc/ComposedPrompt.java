package app.drydock.sdlc;

import java.util.Objects;
import java.util.Optional;

/**
 * Result of one composition. {@code initialPrompt} is the full text to send, or
 * empty when nothing is sent. {@code generatedSeed} is what an unedited editor
 * would hold; {@code effectiveMethodology} is what the prompt actually uses.
 * They differ exactly when {@code edited} is set. {@code provenance} describes
 * the effective text, so an edit never claims to be the pristine source.
 */
public record ComposedPrompt(
        Optional<String> initialPrompt,
        String generatedSeed,
        String effectiveMethodology,
        boolean edited,
        String provenance) {

    public ComposedPrompt {
        Objects.requireNonNull(initialPrompt, "initialPrompt");
        Objects.requireNonNull(generatedSeed, "generatedSeed");
        Objects.requireNonNull(effectiveMethodology, "effectiveMethodology");
        Objects.requireNonNull(provenance, "provenance");
    }
}
