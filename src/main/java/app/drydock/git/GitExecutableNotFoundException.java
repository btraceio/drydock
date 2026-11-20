package app.drydock.git;

/**
 * No usable {@code git} executable could be found: neither an explicitly
 * configured path, nor anything on {@code PATH}, nor any of the common
 * fallback install locations (plan section 6.8's discovery order, applied
 * here to {@code git} rather than {@code claude}).
 */
public final class GitExecutableNotFoundException extends GitException {

    public GitExecutableNotFoundException(String searchedDescription) {
        super("Git executable not found. Searched: " + searchedDescription
                + ". Configure an explicit git path in settings.");
    }
}
