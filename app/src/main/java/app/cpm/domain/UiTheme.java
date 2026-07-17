package app.cpm.domain;

/**
 * The two visual themes from the design handoff. Persisted in {@link
 * WorkspaceUiState} so the choice survives restarts; the actual colors
 * live entirely in {@code theme-dark.css} / {@code theme-light.css}
 * (design rule: no color literals in Java).
 */
public enum UiTheme {
    DARK("theme-dark.css"),
    LIGHT("theme-light.css");

    private final String stylesheet;

    UiTheme(String stylesheet) {
        this.stylesheet = stylesheet;
    }

    /** Stylesheet file name (relative to {@code /app/cpm/ui/}) holding this theme's color tokens. */
    public String stylesheet() {
        return stylesheet;
    }

    public UiTheme other() {
        return this == DARK ? LIGHT : DARK;
    }

    /** Lenient parse for persisted values; anything unrecognized falls back to {@link #DARK}. */
    public static UiTheme fromPersisted(String value) {
        return "LIGHT".equalsIgnoreCase(value) ? LIGHT : DARK;
    }
}
