package app.cpm.process;

import java.time.Duration;
import java.util.List;

/**
 * A child process did not exit within its {@link ProcessRunner} timeout and
 * was forcibly destroyed. Distinct from an ordinary launch failure so each
 * service can translate a hang into its own domain exception (plan section
 * 20: never collapse distinct failure modes into a generic message).
 */
public final class ProcessTimeoutException extends RuntimeException {

    private final List<String> command;
    private final Duration timeout;

    public ProcessTimeoutException(List<String> command, Duration timeout) {
        super("Process timed out after " + timeout.toSeconds() + "s and was killed: "
                + String.join(" ", command));
        this.command = List.copyOf(command);
        this.timeout = timeout;
    }

    public List<String> command() {
        return command;
    }

    public Duration timeout() {
        return timeout;
    }
}
