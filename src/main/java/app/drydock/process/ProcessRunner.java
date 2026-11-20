package app.drydock.process;

import app.drydock.app.LoginShellEnvironment;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

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
        return run(command, new Options(workingDirectory, timeout, false, Map.of()));
    }

    /**
     * How to spawn a child: {@code workingDirectory} may be {@code null} to
     * inherit this process's cwd; {@code discardInput} redirects stdin from
     * {@code /dev/null} so a child that would prompt (e.g. {@code git fetch}
     * asking for credentials) sees EOF instead of hanging until
     * {@code timeout}; {@code environment} entries are added to the child's
     * inherited environment.
     */
    public record Options(Path workingDirectory, Duration timeout, boolean discardInput,
                          Map<String, String> environment) {
        public Options {
            Objects.requireNonNull(timeout, "timeout");
            environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
        }
    }

    public static ProcessResult run(List<String> command, Options options)
            throws IOException, InterruptedException {
        return run(command, options, Integer.MAX_VALUE);
    }

    /** As {@link #run(List, Options)}, but refuses output larger than {@code maxOutputBytes}. */
    public static ProcessResult run(List<String> command, Options options, int maxOutputBytes)
            throws IOException, InterruptedException {
        if (maxOutputBytes <= 0) {
            throw new IllegalArgumentException("maxOutputBytes must be positive");
        }
        ProcessBuilder builder = builderFor(command, options);
        return runBuilder(builder, command, options.timeout(), maxOutputBytes, false);
    }

    /**
     * As {@link #run(List, Options, int)}, but output beyond {@code
     * maxOutputBytes} per stream is dropped instead of refused: the result
     * carries the exit code and the first {@code maxOutputBytes} of each
     * stream. For a long child whose output is only logged.
     */
    public static ProcessResult runTruncating(List<String> command, Options options, int maxOutputBytes)
            throws IOException, InterruptedException {
        if (maxOutputBytes <= 0) {
            throw new IllegalArgumentException("maxOutputBytes must be positive");
        }
        ProcessBuilder builder = builderFor(command, options);
        return runBuilder(builder, command, options.timeout(), maxOutputBytes, true);
    }

    private static ProcessBuilder builderFor(List<String> command, Options options) {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(false);
        if (options.workingDirectory() != null) {
            builder.directory(options.workingDirectory().toFile());
        }
        if (options.discardInput()) {
            builder.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        }
        // The login shell's PATH, not the JVM's: a Finder-launched IDE inherits launchd's bare PATH, and a
        // child's own children (git's hooks running git-lfs, say) need the user's tools. A caller's PATH wins.
        String path = LoginShellEnvironment.currentRealPath();
        if (path != null) {
            builder.environment().put("PATH", path);
        }
        builder.environment().putAll(options.environment());
        return builder;
    }

    private static ProcessResult runBuilder(ProcessBuilder builder, List<String> command, Duration timeout,
                                            int maxOutputBytes, boolean truncate)
            throws IOException, InterruptedException {
        Process process = builder.start();

        // Drain stdout and stderr concurrently: reading one stream fully
        // before the other risks a deadlock if the unread pipe's OS buffer
        // fills first.
        StreamReader stdoutReader = new StreamReader(process.getInputStream(), maxOutputBytes);
        StreamReader stderrReader = new StreamReader(process.getErrorStream(), maxOutputBytes);
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

        if (!truncate && (stdoutReader.exceeded() || stderrReader.exceeded())) {
            throw new IOException("Process output exceeded " + maxOutputBytes + " bytes");
        }
        return new ProcessResult(process.exitValue(), stdoutReader.result(), stderrReader.result());
    }

    // ---- Managed SSH forward -------------------------------------------------------------------------------
    // The one sanctioned run-without-overall-deadline spawn besides the LSP host: an independently owned
    // `ssh -N` reverse forward. Every other bound stays: bounded diagnostics, 2 s destroy-then-force, 2 s join.

    /** Per-stream diagnostic cap for a managed forward; further output is drained and discarded. */
    static final int FORWARD_DIAGNOSTIC_LIMIT = 64 * 1024;

    /** Grace after destroy() before destroyForcibly(), and again the bound for reader joins. */
    static final Duration FORWARD_CLOSE_GRACE = Duration.ofSeconds(2);

    private static final Pattern SECRET_PATTERN = Pattern.compile(
            "(?i)(bearer\\s+\\S+|(?:token|authorization|password|secret|credential)\\s*[=:]\\s*(?:bearer\\s+)?\\S+)");

    /** How to spawn a managed forward: no timeout by design; {@code environment} is added to the inherited one. */
    public record ForwardOptions(Path workingDirectory, Map<String, String> environment) {
        public ForwardOptions {
            environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
        }
    }

    /** Spawns the child; a test seam so lifecycle tests drive a fake child. */
    @FunctionalInterface
    interface ForwardStarter {
        Process start(List<String> argv, ForwardOptions options) throws IOException;
    }

    /**
     * Starts an independently owned forwarding {@code ssh -N} child and returns its lifecycle handle. The
     * child has no overall lifetime: it lives until it exits (connect refusal, keepalive loss) or the handle
     * is closed. Argument list only, pipes only, stdin from /dev/null. Rejects any argv that is not an
     * {@code ssh} no-command forward.
     */
    public static ManagedForward startSshForward(List<String> argv, ForwardOptions options) throws IOException {
        return startSshForward(argv, options, ProcessRunner::spawnForward);
    }

    static ManagedForward startSshForward(List<String> argv, ForwardOptions options, ForwardStarter starter)
            throws IOException {
        Objects.requireNonNull(options, "options");
        if (argv.isEmpty() || !"ssh".equals(Path.of(argv.get(0)).getFileName().toString())
                || !argv.contains("-N")) {
            throw new IllegalArgumentException("startSshForward only runs an ssh -N forward");
        }
        return new ManagedForward(starter.start(List.copyOf(argv), options), redactionLiterals(argv));
    }

    private static Process spawnForward(List<String> argv, ForwardOptions options) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(argv).redirectErrorStream(false)
                .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        if (options.workingDirectory() != null) {
            builder.directory(options.workingDirectory().toFile());
        }
        String path = LoginShellEnvironment.currentRealPath();
        if (path != null) {
            builder.environment().put("PATH", path);
        }
        builder.environment().putAll(options.environment());
        return builder.start();
    }

    /** Transport coordinates (the destination host and the -R spec) never appear in diagnostics. */
    private static List<String> redactionLiterals(List<String> argv) {
        List<String> literals = new ArrayList<>();
        literals.add(argv.get(argv.size() - 1));
        int r = argv.indexOf("-R");
        if (r >= 0 && r + 1 < argv.size()) {
            literals.add(argv.get(r + 1));
        }
        return literals.stream().filter(l -> !l.isBlank() && !l.startsWith("-")).toList();
    }

    /** Handle for a running managed forward. Closing is idempotent and bounded. */
    public static final class ManagedForward implements AutoCloseable {
        private final Process process;
        private final List<String> literals;
        private final ForwardDrain stdout;
        private final ForwardDrain stderr;
        private final CompletableFuture<Integer> exit = new CompletableFuture<>();
        private volatile boolean closed;

        private ManagedForward(Process process, List<String> literals) {
            this.process = process;
            this.literals = literals;
            this.stdout = new ForwardDrain(process.getInputStream());
            this.stderr = new ForwardDrain(process.getErrorStream());
            process.onExit().whenComplete((p, failure) -> {
                if (failure != null) {
                    exit.completeExceptionally(failure);
                } else {
                    exit.complete(p.exitValue());
                }
            });
        }

        /** Completes with the exit code when the child ends for any reason, including {@link #close()}. */
        public CompletableFuture<Integer> exit() {
            return exit;
        }

        /** Convenience exit notification. */
        public void onExit(Consumer<Integer> listener) {
            exit.thenAccept(listener);
        }

        public boolean isAlive() {
            return process.isAlive();
        }

        /** Redacted stdout captured so far, at most 64 KiB. */
        public String stdout() {
            return redact(stdout.text());
        }

        /** Redacted stderr captured so far, at most 64 KiB. */
        public String stderr() {
            return redact(stderr.text());
        }

        /** True while a drain thread is still running (reader accounting). */
        boolean readersAlive() {
            return stdout.alive() || stderr.alive();
        }

        private String redact(String text) {
            String out = text;
            for (String literal : literals) {
                out = out.replace(literal, "[redacted]");
            }
            return SECRET_PATTERN.matcher(out).replaceAll("[redacted]");
        }

        /** destroy(), then destroyForcibly() after 2 s; readers are joined within a further 2 s. */
        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            boolean interrupted = false;
            process.destroy();
            try {
                if (!process.waitFor(FORWARD_CLOSE_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(FORWARD_CLOSE_GRACE.toMillis(), TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException e) {
                interrupted = true;
                process.destroyForcibly();
            }
            try {
                long deadline = System.nanoTime() + FORWARD_CLOSE_GRACE.toNanos();
                stdout.join(deadline);
                stderr.join(deadline);
            } catch (InterruptedException e) {
                interrupted = true;
            }
            if (stdout.alive() || stderr.alive()) {
                LOG.log(Level.WARNING, "Managed forward readers did not finish after close");
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Keeps the first 64 KiB, then keeps draining (discarding) so the child never blocks on a full pipe. */
    private static final class ForwardDrain implements Runnable {
        private final InputStream stream;
        private final ByteArrayOutputStream kept = new ByteArrayOutputStream();
        private final Thread thread;

        ForwardDrain(InputStream stream) {
            this.stream = stream;
            this.thread = Thread.ofVirtual().start(this);
        }

        @Override
        public void run() {
            byte[] buffer = new byte[8192];
            try {
                int read;
                while ((read = stream.read(buffer)) >= 0) {
                    synchronized (kept) {
                        int room = FORWARD_DIAGNOSTIC_LIMIT - kept.size();
                        if (room > 0) {
                            kept.write(buffer, 0, Math.min(room, read));
                        }
                    }
                }
            } catch (IOException e) {
                LOG.log(Level.DEBUG, "Managed forward stream ended", e);
            }
        }

        String text() {
            synchronized (kept) {
                return kept.toString(StandardCharsets.UTF_8);
            }
        }

        boolean alive() {
            return thread.isAlive();
        }

        void join(long deadlineNanos) throws InterruptedException {
            thread.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())));
        }
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
        private final int maxBytes;
        private volatile String result = "";
        private volatile boolean exceeded;

        StreamReader(InputStream stream, int maxBytes) {
            this.stream = stream;
            this.maxBytes = maxBytes;
        }

        @Override
        public void run() {
            try {
                ByteArrayOutputStream kept = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
                byte[] buffer = new byte[8192];
                int read;
                while ((read = stream.read(buffer)) >= 0) {
                    int room = maxBytes - kept.size();
                    if (room > 0) {
                        kept.write(buffer, 0, Math.min(room, read));
                    }
                    if (read > room) {
                        exceeded = true;
                    }
                }
                result = kept.toString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                result = "";
                LOG.log(Level.DEBUG, "Failed reading child process stream", e);
            }
        }

        String result() {
            return result;
        }

        boolean exceeded() {
            return exceeded;
        }
    }
}
