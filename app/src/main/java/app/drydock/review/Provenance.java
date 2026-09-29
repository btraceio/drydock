package app.drydock.review;

/**
 * Where an ordering or a link came from (spec §6.5).
 *
 * <p>The two fail in ways a reviewer has to tell apart. A {@link #MEASURED}
 * edge fails as a false unique-name match -- two unrelated things sharing a
 * name -- and is checkable on the spot by looking; a {@link #CLAIMED} one
 * fails as a plausible fabrication and is checkable only against the code the
 * agent says it read.</p>
 *
 * <p>One rendering path, two visibly different warrants -- the treatment
 * {@code ReviewIntent.Collapse} already gets, applied consistently.</p>
 */
public enum Provenance {

    /** Computed here from the diff, by the rules in §4.2 and §4.3. */
    MEASURED("measured"),

    /** Asserted by the reviewing agent, through {@code review_intents} and its {@code reads}. */
    CLAIMED("claimed");

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
        return this == CLAIMED ? "provenance-claimed" : "";
    }
}
