package app.drydock.review;

import java.util.Locale;
import java.util.Optional;

/**
 * How serious a finding is, in the agent's opinion (Review MCP schema §3).
 * The human can override it, and so can the outcome of an {@code asks}
 * exchange -- both are recorded with an actor, and neither rewrites what the
 * reviewer originally said.
 *
 * <p>Ordered most to least severe, so a margin or a queue badge can colour
 * itself by the worst finding it holds.</p>
 */
public enum Severity {

    /** Blocks approval of its intent until it is resolved or discussed. */
    BLOCKING("blocking"),
    /** A question rather than a defect. */
    QUESTION("question"),
    /** The change deviates from what the human asked for. */
    DEVIATION("deviation"),
    /** A nit: worth saying, never worth blocking on. */
    NIT("nit");

    private final String wireName;

    Severity(String wireName) {
        this.wireName = wireName;
    }

    /** The name this severity travels under, over MCP and in the store. Never rename one. */
    public String wireName() {
        return wireName;
    }

    /** The {@code app.css} modifier class for this severity's pill and pin. */
    public String styleClass() {
        return "severity-" + wireName;
    }

    /** Empty rather than throwing: an unknown severity from an agent is a value, not a crash. */
    public static Optional<Severity> fromWire(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normalized = raw.strip().toLowerCase(Locale.ROOT);
        for (Severity severity : values()) {
            if (severity.wireName.equals(normalized)) {
                return Optional.of(severity);
            }
        }
        return Optional.empty();
    }

    /** Whether a finding of this severity refuses approval of its intent while open. */
    public boolean blocksApproval() {
        return this == BLOCKING;
    }
}
