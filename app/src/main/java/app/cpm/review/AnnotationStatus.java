package app.cpm.review;

import java.util.Locale;

/**
 * Lifecycle of one Review annotation thread (design handoff section C
 * "Threaded annotations"): {@link #OPEN} until the author resolves it or
 * Claude addresses it; {@link #RESOLVED} by hand; {@link #FIXED} when the
 * send-to-Claude validation loop reports it addressed.
 */
public enum AnnotationStatus {
    OPEN,
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
