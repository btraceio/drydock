package app.drydock.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * The three subagent model tiers a launch can carry: STRONG, MID and LOW.
 *
 * <p>Tiers are a launch-time input only, like the starting model they
 * accompany: they reach the agent exclusively through the launch's initial
 * prompt (the per-subagent model rules), never through the session record,
 * and a resume never carries them. The picked values are remembered per
 * repository and harness (see {@code RepositorySettings#lastUsedTiers}) so
 * the next dialog on that harness pre-fills them.</p>
 *
 * <p>An empty tier means "the session's own model": the prompt rules are
 * built with the launch's starting model substituted for it, so the rules
 * stay concrete wherever a starting model was picked.</p>
 */
public record ModelTiers(Optional<String> strong, Optional<String> mid, Optional<String> low) {

    public static final ModelTiers EMPTY = new ModelTiers(Optional.empty(), Optional.empty(), Optional.empty());

    public ModelTiers {
        strong = normalized(strong, "strong");
        mid = normalized(mid, "mid");
        low = normalized(low, "low");
    }

    /**
     * A blank value, or one carrying a line break (it would split the
     * single-line prompt the tiers are spent in), counts as not set.
     */
    private static Optional<String> normalized(Optional<String> value, String tier) {
        Objects.requireNonNull(value, tier);
        return value.filter(m -> !m.isBlank() && m.indexOf('\n') < 0 && m.indexOf('\r') < 0);
    }

    /** Whether no tier carries a model (the prompt rules are then omitted entirely). */
    public boolean isEmpty() {
        return strong.isEmpty() && mid.isEmpty() && low.isEmpty();
    }

    /**
     * This tier's model, or {@code fallback} (the launch's starting model)
     * when the tier was left empty -- the "empty tier means the session's
     * own model" rule.
     */
    public Optional<String> resolved(Tier tier, Optional<String> fallback) {
        return switch (tier) {
            case STRONG -> strong.or(() -> fallback);
            case MID -> mid.or(() -> fallback);
            case LOW -> low.or(() -> fallback);
        };
    }

    /** The three tiers, in prompt-rule order. */
    public enum Tier { STRONG, MID, LOW }
}
