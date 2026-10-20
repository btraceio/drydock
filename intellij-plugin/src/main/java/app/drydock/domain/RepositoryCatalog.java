package app.drydock.domain;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Pure duplicate-detection logic for repositories, resolving by canonical
 * root path (plan section 10.1: "duplicate repositories resolve by
 * canonical root path").
 *
 * <p>Canonicalization uses {@link Path#toRealPath} where possible, which
 * requires the path to exist. If the path does not currently exist or is
 * otherwise unreadable (removable volume unmounted, permissions, a
 * repository whose directory was deleted after being registered), this
 * falls back to the normalized absolute path rather than throwing --
 * duplicate detection still works in the common case, and repository
 * existence itself is separately revalidated when a repository is opened
 * (plan section 10.1).</p>
 */
public final class RepositoryCatalog {

    private RepositoryCatalog() {
    }

    /**
     * Resolves {@code root} to the path used as the duplicate-detection
     * key: the real (symlink-resolved) path if it can be determined, or
     * the normalized absolute path otherwise. Never throws.
     */
    public static Path canonicalize(Path root) {
        try {
            return root.toRealPath();
        } catch (IOException e) {
            return root.toAbsolutePath().normalize();
        }
    }

    /**
     * Finds an already-registered repository whose root canonicalizes to
     * the same path as {@code root}, if any.
     */
    public static Optional<Repository> findByCanonicalRoot(List<Repository> repositories, Path root) {
        Path target = canonicalize(root);
        return repositories.stream()
                .filter(existing -> canonicalize(existing.root()).equals(target))
                .findFirst();
    }

    public static boolean isDuplicate(List<Repository> repositories, Path root) {
        return findByCanonicalRoot(repositories, root).isPresent();
    }
}
