package app.cpm.claude;

/**
 * Base type for the distinct Claude-integration failure modes that must
 * remain individually reportable (plan section 20: never reduce these to
 * "something went wrong"). Unchecked so it can propagate cleanly through
 * {@link java.util.concurrent.CompletableFuture} (surfacing as the cause of
 * a {@link java.util.concurrent.CompletionException}) without every caller
 * needing a checked-exception signature, mirroring {@code app.cpm.git.GitException}.
 *
 * @see ClaudeExecutableNotFoundException
 * @see ClaudeVersionCheckFailedException
 */
public sealed class ClaudeException extends RuntimeException
        permits ClaudeExecutableNotFoundException, ClaudeVersionCheckFailedException {

    ClaudeException(String message) {
        super(message);
    }

    ClaudeException(String message, Throwable cause) {
        super(message, cause);
    }
}
