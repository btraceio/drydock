package app.cpm.github;

/** A GitHub search or clone failure with a user-presentable message. */
public final class GitHubSearchException extends RuntimeException {

    public GitHubSearchException(String message, Throwable cause) {
        super(message, cause);
    }
}
