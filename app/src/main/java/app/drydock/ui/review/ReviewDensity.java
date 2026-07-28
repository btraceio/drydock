package app.drydock.ui.review;

/**
 * The three code densities {@code d} cycles through (spec §4.8): code font
 * 12.5 / 11.5 / 11px, line height 1.6 / 1.42 / 1.25, row padding 8 / 6 / 4px.
 * Dense roughly doubles the visible diff.
 *
 * <p>The measurements themselves live in {@code app.css} under the style
 * class each constant names, not here: expressing them as px literals in the
 * stylesheet is what lets {@code UiFontScale} scale them with the user's
 * interface size, so density stays a <em>relative</em> choice on top of an
 * absolute one.</p>
 */
public enum ReviewDensity {

    COZY("density-cozy", "cozy"),
    COMPACT("density-compact", "compact"),
    DENSE("density-dense", "dense");

    private final String styleClass;
    private final String label;

    ReviewDensity(String styleClass, String label) {
        this.styleClass = styleClass;
        this.label = label;
    }

    public String styleClass() {
        return styleClass;
    }

    public String label() {
        return label;
    }

    /** The next density in the cycle; wraps, because {@code d} is a cycle and not a ladder. */
    public ReviewDensity next() {
        return values()[(ordinal() + 1) % values().length];
    }
}
