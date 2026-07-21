package app.drydock.domain;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * A registered Git repository (plan section 10.1).
 *
 * <p>Invariants enforced here are the ones that are pure path-string
 * operations and never touch the filesystem: {@code root} must be
 * absolute and already normalized (no {@code .}/{@code ..}/redundant
 * separators). Invariants that require I/O -- canonical-path duplicate
 * resolution ({@link RepositoryCatalog}), and revalidating that the
 * directory still exists and is a Git repository -- are deliberately
 * <em>not</em> done here, so that constructing/deserializing a {@code
 * Repository} value never throws because a filesystem happens to be
 * unavailable at that instant (e.g. a removable volume, or restoring
 * state for a repository that has since been deleted or renamed). Those
 * checks belong at the point of adding a repository, or when a repository
 * is opened.</p>
 */
public record Repository(
        RepositoryId id,
        Path root,
        String displayName,
        Instant addedAt,
        Instant lastOpenedAt,
        RepositorySettings settings
) {

    public Repository {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(addedAt, "addedAt");
        Objects.requireNonNull(lastOpenedAt, "lastOpenedAt");
        Objects.requireNonNull(settings, "settings");

        if (!root.isAbsolute()) {
            throw new IllegalArgumentException("Repository root must be an absolute path: " + root);
        }
        Path normalized = root.normalize();
        if (!normalized.equals(root)) {
            throw new IllegalArgumentException(
                    "Repository root must already be normalized (no '.', '..', or redundant "
                            + "separators); got " + root + ", expected " + normalized);
        }
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("Repository displayName must not be blank");
        }
    }

    public Repository withLastOpenedAt(Instant newLastOpenedAt) {
        return new Repository(id, root, displayName, addedAt, newLastOpenedAt, settings);
    }

    public Repository withDisplayName(String newDisplayName) {
        return new Repository(id, root, newDisplayName, addedAt, lastOpenedAt, settings);
    }
}
