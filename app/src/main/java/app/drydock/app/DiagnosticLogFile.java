package app.drydock.app;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

/**
 * A rotating log file for the packaged app.
 *
 * <p>Everything the app logs goes to stderr, which a Finder/Dock launch of
 * the {@code .app} bundle throws away. That is fine until something fails:
 * the uncaught-exception handler shows a dialog naming the error, and the
 * stack trace behind it -- the only thing that says WHERE it came from -- is
 * gone. This mirrors the same records into
 * {@code ~/Library/Logs/Drydock/} so a reproduction is always recoverable
 * after the fact.</p>
 *
 * <p>Writing is asynchronous, because a {@link FileHandler} formats and
 * {@code flush()}es on whichever thread called {@code LOG.log} -- which
 * includes the FX thread, and includes per-frame paths like {@code
 * TerminalBridge.tickAndDraw}'s failure log. Turning those into a synchronous
 * write would break "never run blocking operations on the JavaFX Application
 * Thread". Publishing here is an {@code offer} onto a bounded queue that
 * drops rather than blocks: losing a record under a log storm is a better
 * failure than stalling a frame.</p>
 *
 * <p>Best effort by construction: a log file that cannot be opened must never
 * stop the app from starting, so a failure here is logged and swallowed.</p>
 */
public final class DiagnosticLogFile {

    private static final Logger LOG = System.getLogger(DiagnosticLogFile.class.getName());

    /**
     * Small enough that the 5-generation rotation actually engages and the
     * last few sessions survive: a session writes single-digit KB, so a
     * multi-megabyte generation would never roll and the log would be one
     * ever-growing append.
     */
    private static final int MAX_BYTES = 256 * 1024;
    private static final int FILE_COUNT = 5;

    /** Deep enough to ride out a burst, shallow enough that a storm drops records instead of growing the heap. */
    private static final int QUEUE_CAPACITY = 1024;

    private static volatile boolean installed;

    private DiagnosticLogFile() {
    }

    /** {@code ~/Library/Logs/Drydock} -- where macOS expects an app's logs to be. */
    private static Path logDirectory() {
        return Path.of(System.getProperty("user.home"), "Library", "Logs", "Drydock");
    }

    /**
     * Where this run's log is being written, or empty when no file could be
     * opened -- the error dialog then says nothing rather than pointing at a
     * directory that does not exist.
     */
    public static Optional<Path> installedDirectory() {
        return installed ? Optional.of(logDirectory()) : Optional.empty();
    }

    /**
     * Attaches the rotating file handler to the root logger. Idempotent, so
     * an entry point other than {@link app.drydock.Main} can call it too
     * without stacking handlers and writing every record twice.
     */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        Path directory = logDirectory();
        try {
            Files.createDirectories(directory);
            // %g is the rotation generation; %u disambiguates concurrent JVMs
            // so two running copies cannot fight over one file.
            FileHandler handler = new FileHandler(
                    directory.resolve("drydock-%u-%g.log").toString(), MAX_BYTES, FILE_COUNT, true);
            handler.setFormatter(new SimpleFormatter());
            // No setLevel: the logger's own level gates first, so raising the
            // handler's would admit nothing while reading as "capture all".
            LogManager.getLogManager().getLogger("").addHandler(new AsyncHandler(handler));
            installed = true;
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Could not open the diagnostic log in " + directory, e);
        }
    }

    /** Hands records to a writer thread so no caller pays the format-and-flush. */
    private static final class AsyncHandler extends Handler {

        private final Handler delegate;
        private final BlockingQueue<LogRecord> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

        AsyncHandler(Handler delegate) {
            this.delegate = delegate;
            Thread writer = new Thread(this::drain, "drydock-log-writer");
            writer.setDaemon(true);
            writer.start();
            // A daemon writer dies with the JVM mid-queue, so the last records
            // -- the interesting ones after a crash -- need an explicit flush.
            Runtime.getRuntime().addShutdownHook(new Thread(this::flush, "drydock-log-flush"));
        }

        @Override
        public void publish(LogRecord record) {
            if (!isLoggable(record)) {
                return;
            }
            // Resolve the caller HERE: LogRecord infers it lazily by walking
            // the current thread's stack, which on the writer thread would be
            // the writer's own.
            record.getSourceClassName();
            queue.offer(record); // drop on full -- never block the caller
        }

        private void drain() {
            while (true) {
                try {
                    delegate.publish(queue.take());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (RuntimeException e) {
                    // A broken handler must not kill the writer and silently
                    // stop all logging; drop the record and carry on.
                    reportError(null, e, java.util.logging.ErrorManager.WRITE_FAILURE);
                }
            }
        }

        @Override
        public void flush() {
            for (LogRecord pending = queue.poll(); pending != null; pending = queue.poll()) {
                delegate.publish(pending);
            }
            delegate.flush();
        }

        @Override
        public void close() {
            flush();
            delegate.close();
        }
    }
}
