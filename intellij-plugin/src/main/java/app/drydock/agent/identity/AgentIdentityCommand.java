package app.drydock.agent.identity;

import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.process.ProcessTimeoutException;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Runs a repository's agent-identity credential command once and validates
 * its output.
 *
 * <p>The command is a user-supplied shell command (the {@code
 * git-credential-helper}-style pattern: a single shell string, not an argv),
 * so it reaches {@code /bin/sh} as one {@code -c} argument through {@link
 * ProcessRunner} -- argument list only, stdin from {@code /dev/null} so a
 * command that would prompt sees EOF instead of hanging until the timeout,
 * hard timeout, stderr captured for the failure message. Its working
 * directory is the launching session's working directory, so a command can
 * look at the repository it is minting for.</p>
 *
 * <p>The command must be safe to re-run: Drydock invokes it at every launch
 * and resume of a session, and the session's {@code gh} wrapper re-invokes
 * it again on every {@code gh} call, so a short-lived token is always
 * freshly minted, never stranded mid-session.</p>
 *
 * <p>Every failure -- non-zero exit, timeout, malformed output -- is a hard
 * {@link AgentIdentityException} carrying the stderr excerpt or the
 * offending line, never a silent degradation to the user's own identity.</p>
 */
public final class AgentIdentityCommand implements AgentIdentityPipeline.CredentialRunner {

    /** How long the command may run; an identity exchange is seconds of work, not minutes. */
    public static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** Stderr excerpt length cap embedded in a failure message. */
    private static final int STDERR_EXCERPT_LIMIT = 500;

    private final Duration timeout;

    public AgentIdentityCommand() {
        this(TIMEOUT);
    }

    /** Test seam: a shorter timeout so the timeout path is testable in seconds. */
    AgentIdentityCommand(Duration timeout) {
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    @Override
    public AgentIdentitySpec run(String command, Path workingDirectory) throws AgentIdentityException {
        ProcessResult result;
        try {
            result = ProcessRunner.run(
                    List.of("/bin/sh", "-c", command),
                    new ProcessRunner.Options(workingDirectory, timeout, true, Map.of()));
        } catch (ProcessTimeoutException e) {
            throw new AgentIdentityException(
                    "The credential command did not finish within " + timeout.getSeconds() + "s", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentIdentityException("The credential command was interrupted", e);
        } catch (IOException e) {
            throw new AgentIdentityException("The credential command could not be launched: " + e.getMessage(), e);
        }
        if (result.exitCode() != 0) {
            throw new AgentIdentityException("The credential command failed with exit code " + result.exitCode()
                    + (result.stderr().isBlank() ? "" : ": " + excerpt(result.stderr())));
        }
        return AgentIdentitySpec.parse(result.stdout());
    }

    private static String excerpt(String stderr) {
        String flattened = stderr.strip();
        if (flattened.length() <= STDERR_EXCERPT_LIMIT) {
            return flattened;
        }
        return flattened.substring(0, STDERR_EXCERPT_LIMIT) + "…";
    }
}
