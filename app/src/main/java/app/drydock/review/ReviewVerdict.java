package app.drydock.review;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The human's decision on one hunk of a diff (Review handoff §7; spec §9.2):
 * keyed by {@code (scopeId, hunkDigest)}, because an agent can regroup the
 * diff into different intents at any time and a verdict keyed on a grouping
 * would be orphaned by that regrouping. A digest over the hunk's own text
 * survives regrouping unchanged.
 *
 * <p>A digest cannot see the base commit move underneath it -- a rebase
 * leaves every hunk byte-identical while the code it sits on changed -- so
 * the {@code (baseCommit, headCommit)} this was judged against is recorded
 * alongside it, and {@link #staleAgainst} derives whether the base has since
 * moved.</p>
 */
public record ReviewVerdict(String scopeId, String hunkDigest, Decision decision,
                            Optional<String> note, Instant at,
                            String baseCommit, String headCommit) {

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
        Objects.requireNonNull(hunkDigest, "hunkDigest");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(note, "note");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(baseCommit, "baseCommit");
        Objects.requireNonNull(headCommit, "headCommit");
        if (scopeId.isBlank() || hunkDigest.isBlank()) {
            throw new IllegalArgumentException(
                    "a verdict is keyed by (scopeId, hunkDigest); neither may be blank");
        }
    }

    public Key key() {
        return new Key(scopeId, hunkDigest);
    }

    /** {@code (scopeId, hunkDigest)} -- a hunk's content is its identity (spec §9.2). */
    public record Key(String scopeId, String hunkDigest) {
        public Key {
            Objects.requireNonNull(scopeId, "scopeId");
            Objects.requireNonNull(hunkDigest, "hunkDigest");
        }
    }

    /**
     * Whether the base has moved since this was given. Only a candidate for
     * staleness: whether the move could actually matter is
     * {@link BaseMove}'s question, not this record's.
     */
    public boolean staleAgainst(String currentBase) {
        return !baseCommit.equals(currentBase);
    }

    /**
     * "Confirm still good": the same decision, re-dated, recorded against the
     * base it has now been judged against. Rewriting the base rather than
     * storing a confirmed flag keeps one source of truth for staleness --
     * a flag would have to be cleared by the next base move, and forgetting
     * to is a silently-approved-stale-code bug.
     */
    public ReviewVerdict confirmedAgainst(String currentBase, String currentHead, Instant when) {
        return new ReviewVerdict(scopeId, hunkDigest, decision, note, when, currentBase, currentHead);
    }
}
