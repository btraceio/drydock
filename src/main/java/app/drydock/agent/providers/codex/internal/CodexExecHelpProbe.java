package app.drydock.agent.providers.codex.internal;

import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.process.ProcessTimeoutException;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Bounded capability check for the Codex options needed by detached babysitting. */
public final class CodexExecHelpProbe {

    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_OUTPUT_BYTES_PER_STREAM = 256 * 1024;

    public enum Outcome { SUPPORTED, UNSUPPORTED, FAILED }

    /** A failure is transient and must not be cached; supported/unsupported are definite probe results. */
    public record Result(Outcome outcome, String reason) {
        public boolean supported() {
            return outcome == Outcome.SUPPORTED;
        }

        public boolean cacheable() {
            return outcome != Outcome.FAILED;
        }
    }

    private CodexExecHelpProbe() {
    }

    /**
     * Runs {@code <executable> exec --help} with bounded output and time. This method blocks and must only be called
     * from the detached watcher/background path, never the UI thread.
     */
    public static Result probe(Path executable) {
        try {
            ProcessResult result = ProcessRunner.run(
                    List.of(executable.toString(), "exec", "--help"),
                    new ProcessRunner.Options(null, PROCESS_TIMEOUT, true, Map.of()),
                    MAX_OUTPUT_BYTES_PER_STREAM);
            if (result.exitCode() != 0) {
                return new Result(Outcome.UNSUPPORTED, "exec --help exited with status " + result.exitCode());
            }
            String output = result.stdout() + "\n" + result.stderr();
            if (!supportsDetachedWrite(output)) {
                return new Result(Outcome.UNSUPPORTED, "exec --help does not advertise both --add-dir and workspace-write");
            }
            return new Result(Outcome.SUPPORTED, "exec --help advertises --add-dir and workspace-write");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(Outcome.FAILED, "capability probe was interrupted");
        } catch (ProcessTimeoutException e) {
            return new Result(Outcome.FAILED, "exec --help timed out after " + PROCESS_TIMEOUT);
        } catch (IOException e) {
            String message = e.getMessage();
            String reason = message != null && message.contains("output exceeded")
                    ? "exec --help exceeded " + MAX_OUTPUT_BYTES_PER_STREAM + " bytes on a captured stream"
                    : "could not run exec --help: " + String.valueOf(message);
            return new Result(Outcome.FAILED, reason);
        }
    }

    /** Pure help-output parser, kept package-private for focused unit tests. */
    static boolean supportsDetachedWrite(String helpOutput) {
        return helpOutput != null && helpOutput.contains("--add-dir") && helpOutput.contains("workspace-write");
    }
}
