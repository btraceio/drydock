package app.drydock.process;

import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The one place the application runs short-lived child processes ({@code
 * git}, {@code gh}, {@code claude}): argument lists only, never a shell
 * string (plan sections 6.7/21), stdout and stderr drained concurrently so
 * neither pipe's OS buffer can deadlock the child, and a hard timeout so a
 * hung executable can never park a {@link java.util.concurrent.CompletableFuture}
 * forever.
 *
 * <p>Callers translate the failure modes into their own domain exceptions:
 * {@link IOException} (could not launch), {@link InterruptedException}
 * (caller cancelled; the child is already destroyed when it propagates),
 * and {@link ProcessTimeoutException} (child hung past the timeout and was
 * forcibly killed).</p>
 */
public final class ProcessRunner {

    private static final Logger LOG = System.getLogger(ProcessRunner.class.getName());

    /** Excerpt length cap for stderr embedded in error messages (plan section 20). */
    private static final int EXCERPT_LIMIT = 2000;

    /** How long to wait for the reader threads after a forced kill (pipes close promptly then). */
    private static final long READER_JOIN_AFTER_KILL_MILLIS = 2000;

    private ProcessRunner() {
    }

    /**
     * Runs {@code command} to completion and returns its exit code and
     * drained output. {@code workingDirectory} may be {@code null} to
     * inherit this process's cwd. On expiry of {@code timeout} the child is
     * {@link Process#destroyForcibly() forcibly destroyed} and a
     * {@link ProcessTimeoutException} is thrown; on interruption the child
     * is likewise destroyed before the {@link InterruptedException}
     * propagates. Blocking -- never call on the JavaFX application thread.
     */
    public static ProcessResult run(List<String> command, Path workingDirectory, Duration timeout)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(false);
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        Process process = builder.start();

        // Drain stdout and stderr concurrently: reading one stream fully
        // before the other risks a deadlock if the unread pipe's OS buffer
        // fills first.
        StreamReader stdoutReader = new StreamReader(process.getInputStream());
        StreamReader stderrReader = new StreamReader(process.getErrorStream());
        Thread stdoutThread = Thread.ofVirtual().start(stdoutReader);
        Thread stderrThread = Thread.ofVirtual().start(stderrReader);

        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                joinAfterKill(stdoutThread);
                joinAfterKill(stderrThread);
                throw new ProcessTimeoutException(command, timeout);
            }
            stdoutThread.join();
            stderrThread.join();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            throw e;
        }

        return new ProcessResult(process.exitValue(), stdoutReader.result(), stderrReader.result());
    }

    /**
     * Trims {@code text} and caps it for embedding in an error message
     * (plan section 20: "the relevant stderr excerpt", never the whole
     * output).
     */
    public static String excerpt(String text) {
        String trimmed = text.strip();
        return trimmed.length() <= EXCERPT_LIMIT ? trimmed : trimmed.substring(0, EXCERPT_LIMIT) + "...";
    }

    /** Bounded join after {@code destroyForcibly()}: the closed pipes end the readers promptly. */
    private static void joinAfterKill(Thread reader) throws InterruptedException {
        reader.join(READER_JOIN_AFTER_KILL_MILLIS);
    }

    private static final class StreamReader implements Runnable {
        private final InputStream stream;
        private volatile String result = "";

        StreamReader(InputStream stream) {
            this.stream = stream;
        }

        @Override
        public void run() {
            try {
                result = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                result = "";
                LOG.log(Level.DEBUG, "Failed reading child process stream", e);
            }
        }

        String result() {
            return result;
        }
    }
}
