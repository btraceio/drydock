package app.drydock.app;

import app.drydock.domain.Repository;

import java.nio.file.Path;

/**
 * A directory chosen through "Add repository..." canonicalizes to the same
 * root as an already-registered {@link Repository} (plan section 10.1:
 * "duplicate repositories resolve by canonical root path"). Distinct from a
 * generic validation failure so the UI can name the existing repository
 * rather than saying "something went wrong" (plan section 20).
 */
public final class DuplicateRepositoryException extends RuntimeException {

    private final transient Repository existing;

    public DuplicateRepositoryException(Path attemptedRoot, Repository existing) {
        super("\"" + attemptedRoot + "\" is already registered as \"" + existing.displayName()
                + "\" (" + existing.root() + ")");
        this.existing = existing;
    }

    /**
     * As the {@link Path}-based constructor, but for a remote repository:
     * {@code attemptedDisplay} and the existing repository's location are
     * rendered as {@code host:remotePath} rather than leaking the internal
     * placeholder root path into the message.
     */
    public DuplicateRepositoryException(String attemptedDisplay, Repository existing) {
        super("\"" + attemptedDisplay + "\" is already registered as \"" + existing.displayName()
                + "\" (" + existingLocationDisplay(existing) + ")");
        this.existing = existing;
    }

    private static String existingLocationDisplay(Repository existing) {
        return existing.isRemote()
                ? existing.remote().host() + ":" + existing.remote().remotePath()
                : existing.root().toString();
    }

    public Repository existing() {
        return existing;
    }
}
