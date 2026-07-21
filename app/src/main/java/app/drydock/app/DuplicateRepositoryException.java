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

    public Repository existing() {
        return existing;
    }
}
