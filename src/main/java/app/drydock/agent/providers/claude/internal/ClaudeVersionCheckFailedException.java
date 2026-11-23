package app.drydock.agent.providers.claude.internal;

import java.nio.file.Path;

/**
 * A {@code claude} executable was found at {@link #executable()}, but
 * running {@code claude --version} (or {@code claude --help}, needed for
 * capability detection) failed to produce usable output -- a non-zero exit
 * code, or the process could not be launched at all (plan section 20:
 * "which executable or path was involved", "the exit code", "the relevant
 * stderr excerpt").
 */
public final class ClaudeVersionCheckFailedException extends ClaudeException {

    private final Path executable;
    private final int exitCode;
    private final String stderrExcerpt;

    public ClaudeVersionCheckFailedException(Path executable, int exitCode, String stderrExcerpt) {
        super("Claude executable found at " + executable + ", but running '" + executable
                + " --version' failed (exit " + exitCode + ")"
                + (stderrExcerpt.isBlank() ? "" : ":" + System.lineSeparator() + "stderr: " + stderrExcerpt));
        this.executable = executable;
        this.exitCode = exitCode;
        this.stderrExcerpt = stderrExcerpt;
    }

    public Path executable() {
        return executable;
    }

    public int exitCode() {
        return exitCode;
    }

    public String stderrExcerpt() {
        return stderrExcerpt;
    }
}
