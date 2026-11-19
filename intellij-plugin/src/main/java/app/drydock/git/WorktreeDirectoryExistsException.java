package app.drydock.git;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A worktree was to be added at a directory that already exists, so nothing
 * was run. Distinct from {@link GitCommandFailedException} because git never
 * ran: the caller knows the directory is someone else's (a previous
 * engagement's leftover, a hand-made folder) and must not clean it up.
 */
public final class WorktreeDirectoryExistsException extends GitException {

    private final Path directory;

    public WorktreeDirectoryExistsException(Path directory) {
        super("A worktree cannot be added at " + directory + ": the directory already exists");
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    public Path directory() {
        return directory;
    }
}
