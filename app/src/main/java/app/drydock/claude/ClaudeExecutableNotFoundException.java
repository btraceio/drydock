package app.drydock.claude;

/**
 * No usable {@code claude} executable could be found: neither an explicitly
 * configured path, nor anything on {@code PATH}, nor any of the common
 * fallback install locations (plan section 6.8's discovery order).
 */
public final class ClaudeExecutableNotFoundException extends ClaudeException {

    public ClaudeExecutableNotFoundException(String searchedDescription) {
        super("Claude executable not found. Searched: " + searchedDescription
                + ". Configure an explicit claude path in settings.");
    }
}
