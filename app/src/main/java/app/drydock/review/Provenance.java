package app.drydock.review;

/**
 * Where an ordering or a link came from (spec §6.5).
 *
 * <p>The two fail in ways a reviewer has to tell apart. A {@link #MEASURED}
 * edge fails as a false unique-name match -- two unrelated things sharing a
 * name -- and is checkable on the spot by looking; a {@link #CLAIMED} one
 * fails as a plausible fabrication and is checkable only against the code the
 * agent says it read.</p>
 */
public enum Provenance {

    /** Computed here from the diff, by the rules in §4.2 and §4.3. */
    MEASURED("measured"),

    /** Asserted by the reviewing agent, e.g. a tour step's impact notes. */
    CLAIMED("claimed"),

    /**
     * Answered by a resolving source -- a language server that has indexed
     * the code -- rather than by name matching. Reserved for that seam
     * ({@link UsageProvider}); nothing here produces it yet.
     */
    RESOLVED("resolved");

    private final String label;

    Provenance(String label) {
        this.label = label;
    }

    /** What the surface shows beside a marker carrying this warrant. */
    public String label() {
        return label;
    }

    /** The {@code app.css} modifier class, or none for the ordinary case. */
    public String styleClass() {
        return switch (this) {
            case MEASURED -> "";
            case CLAIMED -> "provenance-claimed";
            case RESOLVED -> "provenance-resolved";
        };
    }
}
