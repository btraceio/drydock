package app.drydock.review;

import java.util.Locale;

/**
 * Lifecycle of one Review annotation thread (design handoff section C
 * "Threaded annotations"): {@link #OPEN} until the author resolves it or
 * hands it off; {@link #SENT} once posted into the session's live Claude
 * terminal (outcome unknown -- the app never claims a fix on Claude's
 * behalf; re-running the diff shows the real result); {@link #RESOLVED}
 * by hand. {@link #FIXED} is legacy: older builds auto-flipped sent
 * threads to it; it is kept so persisted files still decode.
 *
 * <p>{@link #ADDRESSED} is claimed by the agent itself through the MCP tool
 * {@code review_reply} -- distinct from {@link #RESOLVED}, which only the
 * human sets. This is the case {@link #FIXED} got wrong: that value was the
 * <em>app</em> inferring a fix from a successful hand-off, which it cannot
 * know. An agent reporting its own work can -- though the report is a claim,
 * not evidence, so the human still confirms and the thread note matters more
 * than the status.</p>
 */
public enum AnnotationStatus {
    OPEN,
    SENT,
    ADDRESSED,
    RESOLVED,
    FIXED;

    /** Lenient decode for persisted values; unknown text falls back to {@link #OPEN}. */
    public static AnnotationStatus fromPersisted(String value) {
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return OPEN;
        }
    }
}
