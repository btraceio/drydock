package app.drydock.review;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The human's decision on one intent (Review handoff §7): keyed by
 * {@code (scopeId, intentId)}, because intent ids repeat across scopes for
 * the same reason finding ids do.
 */
public record ReviewVerdict(String scopeId, String intentId, Decision decision,
                            Optional<String> note, Instant at) {

    /** What was decided. {@code AUTO_APPROVED} is the agent's own assertion, not the human's. */
    public enum Decision {
        APPROVED("approved"),
        CHANGES("changes"),
        AUTO_APPROVED("auto-approved");

        private final String wireName;

        Decision(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        /** The label the intent rail and the verdict bar show once settled. */
        public String label() {
            return switch (this) {
                case APPROVED -> "✓ approved";
                case CHANGES -> "↺ changes requested";
                case AUTO_APPROVED -> "✓ auto-approved";
            };
        }

        public static Optional<Decision> fromWire(String raw) {
            if (raw == null) {
                return Optional.empty();
            }
            String normalized = raw.strip().toLowerCase(Locale.ROOT);
            for (Decision decision : values()) {
                if (decision.wireName.equals(normalized)) {
                    return Optional.of(decision);
                }
            }
            return Optional.empty();
        }
    }

    public ReviewVerdict {
        Objects.requireNonNull(scopeId, "scopeId");
        Objects.requireNonNull(intentId, "intentId");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(note, "note");
        Objects.requireNonNull(at, "at");
        if (scopeId.isBlank() || intentId.isBlank()) {
            throw new IllegalArgumentException("a verdict is keyed by (scopeId, intentId); neither may be blank");
        }
    }

    public Key key() {
        return new Key(scopeId, intentId);
    }

    /** {@code (scopeId, intentId)} -- intent ids repeat across scopes. */
    public record Key(String scopeId, String intentId) {
        public Key {
            Objects.requireNonNull(scopeId, "scopeId");
            Objects.requireNonNull(intentId, "intentId");
        }
    }
}
