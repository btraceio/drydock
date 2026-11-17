package app.drydock.intellij.workspace;

import app.drydock.domain.RepositoryId;
import app.drydock.workspace.WorkspaceBoxNames;

import com.intellij.ide.util.PropertiesComponent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
 *   <li>{@code drydock.workspace.<RepositoryId>.usedBoxes} — every box name
 *       the repository's mapping has pointed at, the never-reuse history
 *       (INV-25); a re-created generation must never take back an earlier
 *       name, even after the old boxes have left {@code workspaces list};
 *   <li>{@code drydock.workspace.<RepositoryId>.clone} — the discovered
 *       box-side clone path for the repository's project, trusted only
 *       while the mapping points at the box it was recorded for
 *       ({@code .cloneBox} holds that box's name) — a fresh box generation
 *       discovers its own clone instead of inheriting the old box's path;
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

    /**
     * Sets the repository's box name; a null or blank value clears it. Every
     * name the mapping points at is also appended to the used-names history
     * (INV-25), so an earlier generation is never reused for a new box even
     * after the mapping moves on or the override is cleared — the history is
     * never cleared by clearing the override.
     */
    public static void setBox(RepositoryId id, String boxName) {
        store(key(id, ".box"), boxName);
        String name = boxName == null ? null : boxName.strip();
        if (name == null || name.isEmpty()) {
            return;
        }
        List<String> history = new ArrayList<>(usedBoxes(id));
        if (!history.contains(name)) {
            history.add(name);
            PropertiesComponent.getInstance().setValue(key(id, ".usedBoxes"), String.join(",", history));
        }
    }

    /**
     * Every box name this repository's mapping has ever pointed at, in the
     * order first recorded — the never-reuse history (INV-25): current
     * mapping, earlier generations, and cleared overrides alike. Decoding is
     * lenient like the box key: an absent, malformed, or invalid history
     * entry is skipped, never a reason to fail a launch.
     */
    public static List<String> usedBoxes(RepositoryId id) {
        return value(key(id, ".usedBoxes")).stream()
                .flatMap(stored -> Arrays.stream(stored.split(",")))
                .map(String::strip)
                .filter(WorkspaceBoxNames::validate)
                .distinct()
                .toList();
    }

    /** The discovered box-side clone path for the repository's project, or empty. */
    public static Optional<String> clonePath(RepositoryId id) {
        return value(key(id, ".clone"));
    }

    /** Sets the repository's box clone path; a null or blank value clears it. */
    public static void setClonePath(RepositoryId id, String path) {
        store(key(id, ".clone"), path);
        // The Workspace box dialog's override applies to the box the mapping
        // points at when it is set: a path picked on one box generation is
        // never trusted on another (a fresh generation discovers its own).
        // No path stored — no box stamped.
        String stored = path == null ? null : path.strip();
        String stamp = stored == null || stored.isEmpty() ? null : box(id).orElse(null);
        store(key(id, ".cloneBox"), stamp);
    }

    /**
     * The clone path recorded for {@code boxName}, empty when the cached
     * path belongs to a different box generation — the caller (a fresh box)
     * then discovers its own clone instead of trusting the old box's path,
     * so a box-local manual override never carries into a new box.
     */
    public static Optional<String> clonePathFor(RepositoryId id, String boxName) {
        return cloneBox(id).filter(boxName::equals).flatMap(ignored -> clonePath(id));
    }

    /**
     * Records a clone path discovered on {@code boxName} — the only box the
     * path is then trusted for; a null or blank path clears both the path
     * and its box stamp.
     */
    public static void recordDiscoveredClone(RepositoryId id, String boxName, String clonePath) {
        store(key(id, ".clone"), clonePath);
        String stamp = clonePath == null || clonePath.isBlank() ? null : boxName;
        store(key(id, ".cloneBox"), stamp);
    }

    /**
     * The box name the cached clone path was recorded for, or empty when the
     * path applies to no known box; decoding is lenient (an invalid stamp is
     * skipped, and the cached path is then trusted for no box).
     */
    public static Optional<String> cloneBox(RepositoryId id) {
        return value(key(id, ".cloneBox")).filter(WorkspaceBoxNames::validate);
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
