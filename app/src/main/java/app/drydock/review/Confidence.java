package app.drydock.review;

import java.util.Locale;
import java.util.Optional;

/**
 * How sure the reviewer is of a finding (Review MCP schema §3). Rendered
 * beside the anchor so a reader can weigh a confident blocking finding
 * differently from an unsure one.
 */
public enum Confidence {

    HIGH("high"),
    MEDIUM("medium"),
    UNSURE("unsure");

    private final String wireName;

    Confidence(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    /** Empty rather than throwing: an unknown value from an agent is a value, not a crash. */
    public static Optional<Confidence> fromWire(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normalized = raw.strip().toLowerCase(Locale.ROOT);
        for (Confidence confidence : values()) {
            if (confidence.wireName.equals(normalized)) {
                return Optional.of(confidence);
            }
        }
        return Optional.empty();
    }
}
