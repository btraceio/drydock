package app.drydock.lsp;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The single sanctioned long-lived child process: a language server
 * speaking LSP over its stdin/stdout pipes. Every other child spawn in
 * the application is run-to-completion and goes through
 * {@code app.drydock.process.ProcessRunner}, whose
 * {@code waitFor(timeout) + destroyForcibly()} contract would kill a
 * server by construction — the spec's narrow carve-out gives this class
 * the one exception, and {@code ProcessRunner} still governs every
 * one-shot probe (e.g. {@code java -version}).
 *
 * <p>The child is launched with an argument list only — never a shell
 * string — over pipes, never a PTY: terminal semantics corrupt the
 * framed LSP transport. There is deliberately no lifetime timeout on the
 * process; protocol-level shutdown ({@code shutdown} request then
 * {@code exit} notification) is the caller's job and must happen before
 * {@link #close()}, which only handles the process-level aftermath.</p>
 *
 * <p>{@code close()} is idempotent, thread-safe, and bounded: it closes
 * the child's stdin first (a graceful EOF exit, as {@code cat} or a
 * well-behaved server performs), waits at most one second for that, and
 * only then {@link Process#destroyForcibly()} joins the child for at
 * most two seconds more. Interruption during close restores the caller's
 * interrupt flag and still releases the child. Blocking — never call on
 * the JavaFX application thread.</p>
 */
public final class LspServerProcess implements AutoCloseable {

    private static final Logger LOG = System.getLogger(LspServerProcess.class.getName());

    /** Grace period for a child that exits on stdin EOF after the caller's protocol shutdown. */
    private static final long GRACEFUL_EXIT_MILLIS = 1000;

    /** Upper bound for {@link Process#destroyForcibly()} to take effect (spec section 4: 2 s join). */
    private static final long FORCE_KILL_JOIN_MILLIS = 2000;

    /** Upper bound for the stderr drainer thread to finish once the child and its pipes are gone. */
    private static final long DRAIN_JOIN_MILLIS = 2000;

    private final List<String> command;
    private final Process process;
    private final Thread stderrDrainer;
    private final CountDownLatch stderrDrained = new CountDownLatch(1);
    private final CompletableFuture<Integer> exitFuture;
    private final AtomicBoolean closed = new AtomicBoolean();

    private LspServerProcess(List<String> command, Process process) {
        this.command = List.copyOf(command);
        this.process = process;
        this.stderrDrainer = Thread.ofVirtual()
                .name("lsp-stderr-drain")
                .start(this::drainStderr);
        // No lifetime timeout exists anywhere in this class: the exit
        // future completes when the child exits, however long that takes.
        this.exitFuture = process.onExit().thenApply(Process::exitValue);
    }

    /**
     * Launches {@code command} as the long-lived server process.
     * {@code workingDirectory} may be {@code null} to inherit this
     * process's cwd. Fails with {@link LaunchException} — carrying the
     * command — when the executable cannot be spawned; nothing is left
     * behind on that path.
     */
    public static LspServerProcess start(List<String> command, Path workingDirectory)
            throws IOException {
        Objects.requireNonNull(command, "command");
        if (command.isEmpty()) {
            throw new LaunchException("Command list must not be empty", null);
        }
        // Argument list only, pipes only: no shell string to interpret, no
        // PTY whose line discipline could mangle the framed transport.
        ProcessBuilder builder = new ProcessBuilder(List.copyOf(command)).redirectErrorStream(false);
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new LaunchException("Failed to launch " + command, e);
        }
        return new LspServerProcess(command, process);
    }

    /** The child's stdin — the LSP client's write side. */
    public OutputStream stdin() {
        return process.getOutputStream();
    }

    /** The child's stdout — the LSP client's framed read side. */
    public InputStream stdout() {
        return process.getInputStream();
    }

    /**
     * Completes with the child's exit code when it exits — crash or clean
     * exit alike — and never on a timeout, because the process has none.
     * EOF on {@link #stdout()} is the companion signal for the framed
     * reader: the pipe closes when the child dies.
     */
    public CompletableFuture<Integer> exitFuture() {
        return exitFuture;
    }

    /**
     * Idempotent, bounded release. The caller has already performed the
     * protocol shutdown ({@code shutdown} request, {@code exit}
     * notification); this closes the child's stdin for a graceful exit,
     * then force-kills within the documented bounds if the child ignores
     * EOF (a hung or wedged server must never wedge the application).
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // Graceful first: stdin EOF lets a well-behaved child exit on
        // its own (cat does; so does a server that honoured `exit`).
        closeQuietly(process.getOutputStream(), "stdin");
        try {
            if (!process.waitFor(GRACEFUL_EXIT_MILLIS, TimeUnit.MILLISECONDS)) {
                forceKill();
            }
        } catch (InterruptedException e) {
            // Restore the caller's interrupt flag, but the child is still
            // released before this method returns.
            Thread.currentThread().interrupt();
            forceKill();
        }
        // The pipes are closed regardless, so neither the LSP reader nor
        // the stderr drainer can stay blocked on a child that refused to
        // die even under destroyForcibly().
        closeQuietly(process.getInputStream(), "stdout");
        closeQuietly(process.getErrorStream(), "stderr");
        awaitStderrDrainer();
    }

    private void forceKill() {
        process.destroyForcibly();
        try {
            if (!process.waitFor(FORCE_KILL_JOIN_MILLIS, TimeUnit.MILLISECONDS)) {
                LOG.log(Level.WARNING,
                        "Language server {0} survived destroyForcibly() for {1} ms; abandoning it",
                        command, FORCE_KILL_JOIN_MILLIS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void awaitStderrDrainer() {
        try {
            if (!stderrDrained.await(DRAIN_JOIN_MILLIS, TimeUnit.MILLISECONDS)) {
                LOG.log(Level.WARNING,
                        "Language server {0} stderr drainer did not finish within {1} ms",
                        command, DRAIN_JOIN_MILLIS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Drains the child's stderr to the system logger at DEBUG so the
     * pipe's OS buffer can never block the child, without spamming the
     * log at INFO or above for a tier that is quiet by design.
     */
    private void drainStderr() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                LOG.log(Level.DEBUG, "{0} stderr: {1}", command.get(0), line);
            }
        } catch (IOException e) {
            LOG.log(Level.DEBUG, "Language server {0} stderr drain ended", command, e);
        } finally {
            stderrDrained.countDown();
        }
    }

    private static void closeQuietly(Closeable stream, String what) {
        try {
            stream.close();
        } catch (IOException e) {
            LOG.log(Level.DEBUG, "Failed closing language server {0}", what, e);
        }
    }

    /**
     * The spawn-failure mode of {@link #start}: the executable does not
     * exist or cannot be launched. Narrow by design — the tier above
     * treats it as "unavailable, retry on next query", never as a
     * terminal condition.
     */
    public static final class LaunchException extends IOException {
        LaunchException(String message, IOException cause) {
            super(message, cause);
        }
    }
}
