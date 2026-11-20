package app.drydock.review.tour;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The agent's take on one hunk of the review diff: what the hunk does, how
 * it aligns with the tour's intent, and the red flags it raises, each
 * optionally the id of a finding the agent posted. Keyed by the hunk's
 * digest, so a take whose hunk changed is dropped with it, never moved
 * onto other code.
 */
public record HunkTake(String hunkDigest, String does, Alignment alignment, String alignmentNote,
                       List<RedFlag> redFlags, Instant at) {

    /** How the hunk relates to the intent. */
    public enum Alignment {
        ALIGNED("aligned", "Aligned"),
        PARTIAL("partial", "Partly aligned"),
        DIVERGES("diverges", "Diverges"),
        CONTEXT("context", "Context");

        private final String wireName;
        private final String label;

        Alignment(String wireName, String label) {
            this.wireName = wireName;
            this.label = label;
        }

        public String wireName() {
            return wireName;
        }

        public String label() {
            return label;
        }

        public static Optional<Alignment> fromWire(String raw) {
            if (raw == null) {
                return Optional.empty();
            }
            String normalized = raw.strip().toLowerCase(Locale.ROOT);
            for (Alignment alignment : values()) {
                if (alignment.wireName.equals(normalized)) {
                    return Optional.of(alignment);
                }
            }
            return Optional.empty();
        }
    }

    /** One red flag; {@code findingId} names the finding it is about, when there is one. */
    public record RedFlag(String text, Optional<String> findingId) {
        public RedFlag {
            Objects.requireNonNull(text, "text");
            findingId = Objects.requireNonNull(findingId, "findingId").filter(id -> !id.isBlank());
        }
    }

    public HunkTake {
        Objects.requireNonNull(hunkDigest, "hunkDigest");
        Objects.requireNonNull(does, "does");
        Objects.requireNonNull(alignment, "alignment");
        alignmentNote = alignmentNote == null ? "" : alignmentNote.strip();
        redFlags = List.copyOf(redFlags);
        Objects.requireNonNull(at, "at");
        if (hunkDigest.isBlank()) {
            throw new IllegalArgumentException("a take is keyed by its hunk's digest, which may not be blank");
        }
    }
}
