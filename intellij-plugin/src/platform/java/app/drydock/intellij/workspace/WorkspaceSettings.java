package app.drydock.intellij.workspace;

import app.drydock.domain.RepositoryId;

import com.intellij.ide.util.PropertiesComponent;

import java.util.Optional;

/**
 * Opt-in workspace-box session settings, stored in the IDE's application-level
 * {@link PropertiesComponent} exactly the way {@code GitHubProjectSettings}
 * stores its per-project presentation settings. Three key families:
 *
 * <ul>
 *   <li>{@code drydock.workspaceSessions.enabled} — the opt-in flag, default
 *       false, so a fresh install stores nothing and behaves byte-identically
 *       to a build without the feature;
 *   <li>{@code drydock.workspace.<RepositoryId>.box} — the repository's
 *       current box name, a user override or a re-created generation;
 *   <li>{@code drydock.workspace.<RepositoryId>.clone} — the discovered
 *       box-side clone path for the repository's project.
 * </ul>
 *
 * <p>This is plugin-only IDE state, deliberately not shared with the desktop
 * app's config, and it holds no secrets. Values are stored verbatim after
 * whitespace stripping; a null or blank writer argument clears the key, so a
 * cleared override leaves no trace behind.</p>
 */
public final class WorkspaceSettings {

    private static final String ENABLED_KEY = "drydock.workspaceSessions.enabled";
    private static final String PREFIX = "drydock.workspace.";

    private WorkspaceSettings() {
    }

    /** Whether agent sessions may run on workspace boxes; off until the user opts in. */
    public static boolean enabled() {
        return PropertiesComponent.getInstance().getBoolean(ENABLED_KEY, false);
    }

    /** Sets the opt-in flag. Writing the default false unsets the key, keeping a fresh install clean. */
    public static void setEnabled(boolean enabled) {
        PropertiesComponent.getInstance().setValue(ENABLED_KEY, enabled, false);
    }

    /** The repository's current box name — a user override or a re-created generation — or empty. */
    public static Optional<String> box(RepositoryId id) {
        return value(key(id, ".box"));
    }

    /** Sets the repository's box name; a null or blank value clears it. */
    public static void setBox(RepositoryId id, String boxName) {
        store(key(id, ".box"), boxName);
    }

    /** The discovered box-side clone path for the repository's project, or empty. */
    public static Optional<String> clonePath(RepositoryId id) {
        return value(key(id, ".clone"));
    }

    /** Sets the repository's box clone path; a null or blank value clears it. */
    public static void setClonePath(RepositoryId id, String path) {
        store(key(id, ".clone"), path);
    }

    private static String key(RepositoryId id, String suffix) {
        return PREFIX + id + suffix;
    }

    private static Optional<String> value(String key) {
        return Optional.ofNullable(PropertiesComponent.getInstance().getValue(key))
                .map(String::strip)
                .filter(stored -> !stored.isEmpty());
    }

    private static void store(String key, String value) {
        String stored = value == null ? null : value.strip();
        if (stored != null && stored.isEmpty()) {
            stored = null;
        }
        // A null value removes the key: clearing an override leaves the
        // properties exactly as a fresh install.
        PropertiesComponent.getInstance().setValue(key, stored);
    }
}
