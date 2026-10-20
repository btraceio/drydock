package app.drydock.projects;

import app.drydock.domain.RepositoryId;
import app.drydock.domain.SshRemote;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One entry of the registered-projects list that the standalone app and the
 * IntelliJ plugin share ({@link SharedProjectsFile}).
 *
 * <p>Membership only: what a tool remembers <em>about</em> a project (its
 * last-used agent, its sessions) stays in that tool's own state, keyed by
 * {@link #id()}. The id is therefore the contract between the two tools and
 * never changes once written.</p>
 *
 * <p>{@code root} is the project's main checkout, absolute and normalized.
 * A remote (SSH) project, which only the app can open, carries its
 * {@link SshRemote} and the remote's placeholder root.</p>
 */
public record SharedProject(RepositoryId id, Path root, String displayName, Instant addedAt,
                            Optional<SshRemote> remote) {

    public SharedProject {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(addedAt, "addedAt");
        Objects.requireNonNull(remote, "remote");
        if (!root.isAbsolute()) {
            throw new IllegalArgumentException("root must be absolute: " + root);
        }
        root = root.normalize();
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
    }

    /** A local project: one on this machine's disk. */
    public static SharedProject local(RepositoryId id, Path root, String displayName, Instant addedAt) {
        return new SharedProject(id, root, displayName, addedAt, Optional.empty());
    }

    public boolean isRemote() {
        return remote.isPresent();
    }
}
