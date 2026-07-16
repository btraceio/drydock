package app.cpm.git;

import java.util.List;

/**
 * {@code git} ran and exited with a non-zero status for a reason other than
 * "not a repository" (permissions, a corrupt repository, an unexpected git
 * version's incompatible output, etc). Always carries the exact command,
 * exit code, and a stderr excerpt (plan section 20: "the exit code", "the
 * relevant stderr excerpt") -- never collapsed to a generic message.
 */
public final class GitCommandFailedException extends GitException {

    private final List<String> command;
    private final int exitCode;
    private final String stderrExcerpt;

    public GitCommandFailedException(List<String> command, int exitCode, String stderrExcerpt) {
        super("Git command failed (exit " + exitCode + "): " + String.join(" ", command)
                + (stderrExcerpt.isBlank() ? "" : System.lineSeparator() + "stderr: " + stderrExcerpt));
        this.command = List.copyOf(command);
        this.exitCode = exitCode;
        this.stderrExcerpt = stderrExcerpt;
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
}
