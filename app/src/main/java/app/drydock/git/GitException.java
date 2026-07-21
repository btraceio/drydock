package app.drydock.git;

/**
 * Base type for the distinct Git-status failure modes the sidebar must be
 * able to tell apart (plan section 20: never reduce these to "something
 * went wrong"). Unchecked so it can propagate cleanly through
 * {@link java.util.concurrent.CompletableFuture} (surfacing as the cause of
 * a {@link java.util.concurrent.CompletionException}) without every caller
 * needing a checked-exception signature.
 *
 * @see GitExecutableNotFoundException
 * @see NotAGitRepositoryException
 * @see GitCommandFailedException
 */
public sealed class GitException extends RuntimeException
        permits GitExecutableNotFoundException, NotAGitRepositoryException, GitCommandFailedException {

    GitException(String message) {
        super(message);
    }

    GitException(String message, Throwable cause) {
        super(message, cause);
    }
}
