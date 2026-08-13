package app.drydock.domain;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Where a session's work lives on disk, and whether drydock owns the branch
 * there.
 *
 * <p>{@code branchCreatedHere} sits with the paths rather than off on its own
 * because it is a fact <em>about this worktree's branch</em>: {@link
 * BranchOwnership} already reads it together with {@code worktreeRoot}, and the
 * delete paths consult both before {@code git branch -D}. A branch drydock did
 * not create is never force-deleted.</p>
 *
 * <p>Both paths must be absolute and already normalized. That is a pure
 * path-string check, so it belongs here; whether the directory still <em>exists</em>
 * is revalidated when a session is opened, never at construction -- restoring
 * persisted state for a directory that has since disappeared must not throw.</p>
 */
public record SessionWorkspace(Path workingDirectory, Optional<Path> worktreeRoot, boolean branchCreatedHere) {

    public SessionWorkspace {
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(worktreeRoot, "worktreeRoot");
        requireAbsoluteNormalized(workingDirectory, "workingDirectory");
        if (worktreeRoot.isPresent()) {
            requireAbsoluteNormalized(worktreeRoot.get(), "worktreeRoot");
        }
    }

    /** A session working directly in a repository checkout, on a branch drydock did not create. */
    public static SessionWorkspace inRepository(Path workingDirectory) {
        return new SessionWorkspace(workingDirectory, Optional.empty(), false);
    }

    public SessionWorkspace withWorkingDirectory(Path newWorkingDirectory) {
        return new SessionWorkspace(newWorkingDirectory, worktreeRoot, branchCreatedHere);
    }

    public SessionWorkspace withWorktreeRoot(Optional<Path> newWorktreeRoot) {
        return new SessionWorkspace(workingDirectory, newWorktreeRoot, branchCreatedHere);
    }

    private static void requireAbsoluteNormalized(Path path, String fieldName) {
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException(
                    "SessionWorkspace " + fieldName + " must be an absolute path: " + path);
        }
        Path normalized = path.normalize();
        if (!normalized.equals(path)) {
            throw new IllegalArgumentException(
                    "SessionWorkspace " + fieldName + " must already be normalized (no '.', '..', or "
                            + "redundant separators); got " + path + ", expected " + normalized);
        }
    }
}
