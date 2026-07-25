package app.drydock.git;

import java.util.List;

/**
 * The thread waiting on {@code git} was interrupted (future cancellation,
 * executor shutdown) -- distinct from {@link GitCommandFailedException}
 * (which also covers a timeout) because a caller that wants to keep
 * inspecting the repository after a timeout must NOT do so after an
 * interrupt: the interrupt means the caller no longer wants any more work
 * done, including read-only git queries. The child process is already
 * destroyed by the time this propagates (see {@code ProcessRunner}), and
 * the interrupt status is left set on the current thread by whoever throws
 * this.
 */
public final class GitCommandInterruptedException extends GitException {

    private final List<String> command;

    public GitCommandInterruptedException(List<String> command) {
        super("Interrupted while waiting for git: " + String.join(" ", command));
        this.command = List.copyOf(command);
    }

    public List<String> command() {
        return command;
    }
}
