package app.drydock.ui.explorer;

import app.drydock.ui.explorer.FileEditSession.PollOutcome;
import app.drydock.ui.explorer.FileEditSession.State;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure save-state-machine assertions against a temp dir -- no FX toolkit needed. */
class FileEditSessionTest {

    private static final long MAX = 1024 * 1024;
    /**
     * Long enough that the debounce NEVER fires on its own during a test.
     * Every test but {@link #debounceSavesWithoutAnExplicitFlush} drives the
     * write explicitly; a short debounce here would race an auto-save
     * against the external edit the conflict tests are setting up.
     */
    private static final Duration IDLE_DEBOUNCE = Duration.ofSeconds(30);
    private static final Duration SHORT_DEBOUNCE = Duration.ofMillis(40);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "file-edit-test");
        t.setDaemon(true);
        return t;
    });

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private FileEditSession sessionFor(Path file) throws IOException {
        return new FileEditSession(file, FileContent.load(file, MAX), executor, IDLE_DEBOUNCE, MAX);
    }

    /** Bumps mtime past any filesystem granularity so a same-size edit is still observed. */
    private static void writeExternally(Path file, String text) throws IOException {
        Files.writeString(file, text);
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 2000));
    }

    @Test
    void freshlyLoadedBufferIsClean(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");

        FileEditSession session = sessionFor(file);

        assertEquals(State.CLEAN, session.state());
    }

    @Test
    void flushWritesTheExactBytes(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session = sessionFor(file);

        session.edit("two\n");
        assertEquals(State.DIRTY, session.state());
        session.flush().get(5, TimeUnit.SECONDS);

        assertEquals("two\n", Files.readString(file));
        assertEquals(State.CLEAN, session.state());
    }

    @Test
    void flushIsIdempotentWhenClean(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session = sessionFor(file);

        session.flush().get(5, TimeUnit.SECONDS);
        session.flush().get(5, TimeUnit.SECONDS);

        assertEquals("one\n", Files.readString(file));
        assertEquals(State.CLEAN, session.state());
    }

    @Test
    void debounceSavesWithoutAnExplicitFlush(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session =
                new FileEditSession(file, FileContent.load(file, MAX), executor, SHORT_DEBOUNCE, MAX);
        CountDownLatch clean = new CountDownLatch(1);
        session.setOnStateChanged(state -> {
            if (state == State.CLEAN) {
                clean.countDown();
            }
        });

        session.edit("auto\n");

        assertTrue(clean.await(5, TimeUnit.SECONDS), "debounce should have saved");
        assertEquals("auto\n", Files.readString(file));
    }

    @Test
    void crlfFileKeepsItsTerminatorOnSave(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("w.txt");
        Files.write(file, "a\r\nb\r\n".getBytes(StandardCharsets.UTF_8));
        FileEditSession session = sessionFor(file);

        session.edit("a\nX\n");
        session.flush().get(5, TimeUnit.SECONDS);

        assertEquals("a\r\nX\r\n", Files.readString(file));
    }

    @Test
    void externalChangeWhileCleanReportsReload(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session = sessionFor(file);

        writeExternally(file, "claude\n");
        var result = session.poll().get(5, TimeUnit.SECONDS);

        assertEquals(PollOutcome.RELOAD, result.outcome());
        assertEquals("claude\n", result.text());
    }

    @Test
    void externalChangeWhileDirtyReportsConflictAndDoesNotWrite(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session = sessionFor(file);

        session.edit("mine\n");
        writeExternally(file, "claude\n");
        var result = session.poll().get(5, TimeUnit.SECONDS);

        assertEquals(PollOutcome.CONFLICT, result.outcome());
        assertEquals(State.CONFLICT, session.state());
        // Auto-save must be disarmed: an explicit flush writes nothing either.
        session.flush().get(5, TimeUnit.SECONDS);
        assertEquals("claude\n", Files.readString(file), "conflict must not overwrite");
    }

    @Test
    void ownWriteIsNotMistakenForAnExternalChange(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session = sessionFor(file);

        session.edit("mine\n");
        session.flush().get(5, TimeUnit.SECONDS);
        var result = session.poll().get(5, TimeUnit.SECONDS);

        assertEquals(PollOutcome.UNCHANGED, result.outcome());
        assertEquals(State.CLEAN, session.state());
    }

    @Test
    void keepMineWritesAndClearsTheConflict(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session = sessionFor(file);

        session.edit("mine\n");
        writeExternally(file, "claude\n");
        session.poll().get(5, TimeUnit.SECONDS);
        session.keepMine().get(5, TimeUnit.SECONDS);

        assertEquals("mine\n", Files.readString(file));
        assertEquals(State.CLEAN, session.state());
    }

    @Test
    void takeDiskDiscardsTheBuffer(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session = sessionFor(file);

        session.edit("mine\n");
        writeExternally(file, "claude\n");
        session.poll().get(5, TimeUnit.SECONDS);
        String restored = session.takeDisk().get(5, TimeUnit.SECONDS);

        assertEquals("claude\n", restored);
        assertEquals(State.CLEAN, session.state());
        assertEquals("claude\n", Files.readString(file));
    }

    @Test
    void deletedFileReportsMissing(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session = sessionFor(file);

        Files.delete(file);
        var result = session.poll().get(5, TimeUnit.SECONDS);

        assertEquals(PollOutcome.MISSING, result.outcome());
    }

    @Test
    void writeFailureEntersErrorWithoutLosingTheBuffer(@TempDir Path dir) throws Exception {
        Path readOnlyDir = Files.createDirectory(dir.resolve("locked"));
        Path file = readOnlyDir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session = sessionFor(file);
        assertTrue(file.toFile().setWritable(false), "test needs a non-writable file");

        session.edit("mine\n");
        session.flush().get(5, TimeUnit.SECONDS);

        assertEquals(State.ERROR, session.state());
        assertNotNull(session.lastError());
        assertEquals("one\n", Files.readString(file), "failed write must not truncate the file");

        // The buffer survives: making the file writable and retrying saves it.
        assertTrue(file.toFile().setWritable(true));
        session.flush().get(5, TimeUnit.SECONDS);
        assertEquals("mine\n", Files.readString(file));
    }

    @Test
    void flushBlockingReturnsOnlyAfterTheBytesAreOnDisk(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session = sessionFor(file);

        session.edit("blocking\n");
        session.flushBlocking(TIMEOUT);

        assertEquals("blocking\n", Files.readString(file));
    }

    /**
     * An edit that lands while the write is in flight must not be swallowed by
     * the CLEAN transition that follows the write. The window is widened
     * deterministically by parking the executor inside the SAVING callback.
     */
    @Test
    void editDuringAnInFlightWriteIsNotLost(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session = sessionFor(file);
        CountDownLatch saving = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        session.setOnStateChanged(state -> {
            if (state == State.SAVING) {
                saving.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS), "test never released the write");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        session.edit("first\n");
        var inFlight = session.flush();
        assertTrue(saving.await(5, TimeUnit.SECONDS), "write should have reached SAVING");
        session.edit("second\n");
        release.countDown();
        inFlight.get(5, TimeUnit.SECONDS);

        assertEquals(State.DIRTY, session.state(), "a newer edit must not be reported as saved");
        session.flush().get(5, TimeUnit.SECONDS);
        assertEquals("second\n", Files.readString(file));
        assertEquals(State.CLEAN, session.state());
    }

    @Test
    void aTruncatedBufferCannotBeEdited(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("big.txt");
        Files.writeString(file, "aaaaaaaaaaaaaaaaaaaaaaaa\n");
        FileContent content = FileContent.load(file, 4);

        assertThrows(IllegalArgumentException.class,
                () -> new FileEditSession(file, content, executor, IDLE_DEBOUNCE, MAX));
    }

    @Test
    void aBinaryBufferCannotBeEdited(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("bin.dat");
        Files.write(file, new byte[] { 'a', 0, 'b', '\n' });
        FileContent content = FileContent.load(file, MAX);

        assertThrows(IllegalArgumentException.class,
                () -> new FileEditSession(file, content, executor, IDLE_DEBOUNCE, MAX));
    }

    @Test
    void aMixedTerminatorBufferCannotBeEdited(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("mixed.txt");
        Files.write(file, "a\r\nb\n".getBytes(StandardCharsets.UTF_8));
        FileContent content = FileContent.load(file, MAX);

        assertThrows(IllegalArgumentException.class,
                () -> new FileEditSession(file, content, executor, IDLE_DEBOUNCE, MAX));
    }

    /** ERROR is a dirty buffer whose write failed: an external change is a conflict, not a reload. */
    @Test
    void externalChangeWhileInErrorReportsConflict(@TempDir Path dir) throws Exception {
        Path lockedDir = Files.createDirectory(dir.resolve("locked"));
        Path file = lockedDir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session = sessionFor(file);
        assertTrue(file.toFile().setWritable(false), "test needs a non-writable file");

        session.edit("mine\n");
        session.flush().get(5, TimeUnit.SECONDS);
        assertEquals(State.ERROR, session.state());

        assertTrue(file.toFile().setWritable(true));
        writeExternally(file, "claude\n");
        var result = session.poll().get(5, TimeUnit.SECONDS);

        assertEquals(PollOutcome.CONFLICT, result.outcome());
        assertEquals(State.CONFLICT, session.state());
    }

    /** A throwable that is not an IOException must still clear SAVING. */
    @Test
    void aNonIoFailureOnTheWritePathDoesNotStrandSaving(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session = sessionFor(file);
        AtomicBoolean explode = new AtomicBoolean(true);
        session.setOnStateChanged(state -> {
            if (state == State.SAVING && explode.compareAndSet(true, false)) {
                throw new IllegalStateException("listener blew up");
            }
        });

        session.edit("mine\n");
        session.flush().get(5, TimeUnit.SECONDS);

        assertNotEquals(State.SAVING, session.state(), "SAVING must never be stranded");
        assertEquals(State.ERROR, session.state());
        assertNotNull(session.lastError());
        // Auto-save is still alive: the retry writes the buffer.
        session.flush().get(5, TimeUnit.SECONDS);
        assertEquals("mine\n", Files.readString(file));
        assertEquals(State.CLEAN, session.state());
    }

    /** A reload adopts the fresh content, so a terminator change on disk is honoured. */
    @Test
    void reloadAdoptsTheDisksNewTerminator(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("w.txt");
        Files.write(file, "a\r\nb\r\n".getBytes(StandardCharsets.UTF_8));
        FileEditSession session = sessionFor(file);

        writeExternally(file, "a\nb\n");
        var result = session.poll().get(5, TimeUnit.SECONDS);
        assertEquals(PollOutcome.RELOAD, result.outcome());

        session.edit("a\nX\n");
        session.flush().get(5, TimeUnit.SECONDS);
        assertEquals("a\nX\n", Files.readString(file), "must not re-CRLF a file that is now LF");
    }

    /** A file replaced by something unwritable must not be adopted as the buffer. */
    @Test
    void reloadOfANonEditableFileReportsMissing(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session = sessionFor(file);

        Files.write(file, new byte[] { 'a', 0, 'b', '\n' });
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 2000));
        var result = session.poll().get(5, TimeUnit.SECONDS);

        assertEquals(PollOutcome.MISSING, result.outcome());
    }

    @Test
    void takeDiskFailsExceptionallyWhenTheFileIsGone(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session = sessionFor(file);

        session.edit("mine\n");
        Files.delete(file);
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> session.takeDisk().get(5, TimeUnit.SECONDS));

        assertInstanceOf(IOException.class, failure.getCause());
    }

    @Test
    void flushBlockingReportsATimeout(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session = sessionFor(file);
        CountDownLatch saving = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<IOException> reported = new AtomicReference<>();
        session.setOnSaveFailed(reported::set);
        session.setOnStateChanged(state -> {
            if (state == State.SAVING) {
                saving.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS), "test never released the write");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        session.edit("mine\n");
        session.flush();
        assertTrue(saving.await(5, TimeUnit.SECONDS), "write should have reached SAVING");
        session.flushBlocking(Duration.ofMillis(50));

        assertNotNull(session.lastError(), "a timed-out shutdown flush must be recorded");
        assertNotNull(reported.get(), "a timed-out shutdown flush must be reported");
        release.countDown();
    }

    /**
     * {@code CompletableFuture.runAsync} calls {@code executor.execute} on the
     * calling thread and does not wrap a {@link RejectedExecutionException}
     * into the returned future, so a naive {@code flush()} would throw synchronously once the
     * executor is shut down and that exception would escape {@link
     * FileEditSession#flushBlocking}, the shutdown entry point, straight onto
     * the caller (the FX thread, in production). This needs its own executor
     * -- the shared one is reused by every other test via {@code @AfterEach}.
     */
    @Test
    void flushBlockingAfterShutdownDoesNotThrow(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");
        ScheduledExecutorService ownExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "file-edit-shutdown-test");
            t.setDaemon(true);
            return t;
        });
        try {
            FileEditSession session =
                    new FileEditSession(file, FileContent.load(file, MAX), ownExecutor, IDLE_DEBOUNCE, MAX);
            session.edit("mine\n");
            ownExecutor.shutdownNow();

            session.flushBlocking(TIMEOUT);
        } finally {
            ownExecutor.shutdownNow();
        }
    }
}
