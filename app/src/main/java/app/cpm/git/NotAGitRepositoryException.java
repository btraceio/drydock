package app.cpm.git;

import java.nio.file.Path;

/**
 * {@code git} itself is present and ran, but reported that {@code repositoryRoot}
 * is not (inside) a Git working tree -- distinct from {@link GitCommandFailedException}
 * because this is an expected, user-actionable outcome (the repository was
 * removed, or a non-repository directory was added), not a tool failure
 * (plan section 20).
 */
public final class NotAGitRepositoryException extends GitException {

    public NotAGitRepositoryException(Path repositoryRoot) {
        super("Not a Git repository: " + repositoryRoot);
    }
}
