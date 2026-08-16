package app.drydock.git;

import java.util.List;

/**
 * {@code git} ran and exited with a non-zero status for a reason other than
 * "not a repository" (permissions, a corrupt repository, an unexpected git
 * version's incompatible output, etc). Always carries the exact command,
 * exit code, and a stderr excerpt (plan section 20: "the exit code", "the
 * relevant stderr excerpt") -- never collapsed to a generic message.
 */
public final class GitCommandFailedException extends GitException {

    /**
     * Whether the command can have changed anything before it failed.
     *
     * <p>The exit code cannot answer this: a launch failure, a kill after
     * the timeout, an interrupt and a pre-spawn {@code mkdir} failure all
     * report {@code -1}. The difference matters to anyone deciding whether
     * a failed operation left something behind -- {@code git worktree add}
     * creates the directory and the admin entry well before it finishes, so
     * a killed add may have produced a worktree that a "nothing happened"
     * reading would tell the caller to recreate.</p>
     */
    public enum Outcome {

        /** git ran to a verdict, or never started at all; nothing half-done. */
        KNOWN_FAILED,

        /** The child was running when the wait ended; it may have got somewhere. */
        UNKNOWN
    }

    private final List<String> command;
    private final int exitCode;
    private final String stderrExcerpt;
    private final Outcome outcome;

    /** As {@link #GitCommandFailedException(List, int, String, Outcome)}, {@link Outcome#KNOWN_FAILED}. */
    public GitCommandFailedException(List<String> command, int exitCode, String stderrExcerpt) {
        this(command, exitCode, stderrExcerpt, Outcome.KNOWN_FAILED);
    }

    public GitCommandFailedException(List<String> command, int exitCode, String stderrExcerpt, Outcome outcome) {
        super("Git command failed (exit " + exitCode + "): " + String.join(" ", command)
                + (stderrExcerpt.isBlank() ? "" : System.lineSeparator() + "stderr: " + stderrExcerpt));
        this.command = List.copyOf(command);
        this.exitCode = exitCode;
        this.stderrExcerpt = stderrExcerpt;
        this.outcome = outcome;
    }

    public List<String> command() {
        return command;
    }

    public int exitCode() {
        return exitCode;
    }

    public String stderrExcerpt() {
        return stderrExcerpt;
    }

    public Outcome outcome() {
        return outcome;
    }
}
