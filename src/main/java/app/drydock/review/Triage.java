package app.drydock.review;

import java.util.Locale;
import java.util.Optional;

/**
 * Whether a human has looked at a finding. An agent's finding lands {@link
 * #PROPOSED}: it shows in the margin but is not posted, not sent back to the
 * agent and does not block approval until the reviewer {@link #CONFIRMED} it.
 * A {@link #DISMISSED} finding is kept (the agent can read why) but counts
 * for nothing.
 */
public enum Triage {

    PROPOSED("proposed"),
    CONFIRMED("confirmed"),
    DISMISSED("dismissed");

    private final String wireName;

    Triage(String wireName) {
        this.wireName = wireName;
    }

    /** The name this state travels under, over MCP and in the store. Never rename one. */
    public String wireName() {
        return wireName;
    }

    /** Empty rather than throwing: an unknown value from a file or an agent is a value, not a crash. */
    public static Optional<Triage> fromWire(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normalized = raw.strip().toLowerCase(Locale.ROOT);
        for (Triage triage : values()) {
            if (triage.wireName.equals(normalized)) {
                return Optional.of(triage);
            }
        }
        return Optional.empty();
    }
}
