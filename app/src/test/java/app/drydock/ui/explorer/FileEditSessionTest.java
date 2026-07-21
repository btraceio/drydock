package app.drydock.ui.explorer;

import app.drydock.ui.explorer.FileEditSession.PollOutcome;
import app.drydock.ui.explorer.FileEditSession.State;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    /**
     * For the "must NOT have written" tests: long enough that the setup
     * (an edit, then a poll or an abandon) is certain to complete before the
     * debounce could fire, short enough that {@link #awaitPastDebounce} is
     * quick. Those tests then wait past it deterministically instead of
     * sleeping.
     */
    private static final Duration ARMED_DEBOUNCE = Duration.ofMillis(300);
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

    /**
     * Returns once the debounce deadline has demonstrably passed, without
     * sleeping: a marker task is scheduled on the SAME single-threaded executor
     * the session arms its debounce on, at three times the debounce delay. If an
     * armed {@code writeIfDirty} were still pending it would be ordered ahead of
     * the marker and would have run by the time this returns -- so a file that
     * is still untouched afterwards proves the debounce really was disarmed.
     */
    private void awaitPastDebounce(Duration debounce) throws Exception {
        CountDownLatch marker = new CountDownLatch(1);
        executor.schedule(marker::countDown, debounce.toMillis() * 3, TimeUnit.MILLISECONDS);
        assertTrue(marker.await(5, TimeUnit.SECONDS), "marker task should have run");
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

    /**
     * A MISSING poll must disarm auto-save. Otherwise the debounce armed by the
     * user's last keystroke fires a second or two later and writes the buffer
     * back -- recreating the file Claude deleted, and answering the viewer's
     * "keep mine / close tab" banner for the user before they can read it.
     */
    @Test
    void aMissingPollDisarmsTheDebounceSoADirtyBufferIsNotWrittenBack(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session =
                new FileEditSession(file, FileContent.load(file, MAX), executor, ARMED_DEBOUNCE, MAX);

        session.edit("mine\n");
        Files.delete(file);
        var result = session.poll().get(5, TimeUnit.SECONDS);
        assertEquals(PollOutcome.MISSING, result.outcome());

        awaitPastDebounce(ARMED_DEBOUNCE);

        assertFalse(Files.exists(file), "a MISSING poll must not let the debounce recreate the file");
        assertEquals(State.DIRTY, session.state(), "the user's buffer is still theirs to keep");
    }

    /**
     * The viewer's "close tab" answer to the missing-file banner abandons the
     * session. Nothing may write that buffer afterwards -- not the explicit
     * flushes teardown fires (selection change, focus loss), nor an armed
     * debounce.
     */
    @Test
    void anAbandonedSessionWritesNeitherOnFlushNorOnTheDebounce(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session =
                new FileEditSession(file, FileContent.load(file, MAX), executor, ARMED_DEBOUNCE, MAX);

        session.edit("mine\n");
        session.abandon();

        session.flush().get(5, TimeUnit.SECONDS);
        assertEquals("one\n", Files.readString(file), "abandon must veto an explicit flush");

        awaitPastDebounce(ARMED_DEBOUNCE);
        assertEquals("one\n", Files.readString(file), "abandon must disarm the debounce");

        // And it is one-way: a late keystroke cannot re-arm it either.
        session.edit("later\n");
        session.flush().get(5, TimeUnit.SECONDS);
        awaitPastDebounce(ARMED_DEBOUNCE);
        assertEquals("one\n", Files.readString(file), "abandon must not be reversible by an edit");
    }

    /** The same veto holds when the abandoned file really is gone: it must not be recreated. */
    @Test
    void anAbandonedSessionDoesNotRecreateADeletedFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session =
                new FileEditSession(file, FileContent.load(file, MAX), executor, ARMED_DEBOUNCE, MAX);

        session.edit("mine\n");
        Files.delete(file);
        session.abandon();

        session.flush().get(5, TimeUnit.SECONDS);
        awaitPastDebounce(ARMED_DEBOUNCE);

        assertFalse(Files.exists(file), "an abandoned buffer must not recreate its file");
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

    /**
     * {@code missing()} adopts no stamp, so without remembering the identity it
     * refused, every poll tick would re-read the whole file -- a 2 MB binary
     * dropped in place would be loaded every 1.5s, forever, on the same thread
     * the saves use. Observed the only way it can be from outside: the file's
     * bytes are swapped for perfectly editable ones while its mtime and size are
     * held identical, so a second MISSING can only mean it was never re-read.
     */
    @Test
    void aRejectedNonEditableFileIsNotReReadWhileItsIdentityIsUnchanged(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session = sessionFor(file);

        Files.write(file, new byte[] { 'a', 0, 'b', '\n' });
        FileTime rejectedAt = FileTime.fromMillis(System.currentTimeMillis() + 2000);
        Files.setLastModifiedTime(file, rejectedAt);
        assertEquals(PollOutcome.MISSING, session.poll().get(5, TimeUnit.SECONDS).outcome());

        Files.write(file, "abc\n".getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(file, rejectedAt);
        assertEquals(PollOutcome.MISSING, session.poll().get(5, TimeUnit.SECONDS).outcome(),
                "an unchanged rejected file must not be loaded again");

        // A genuine identity change ends the short-circuit.
        writeExternally(file, "claude\n");
        var result = session.poll().get(5, TimeUnit.SECONDS);
        assertEquals(PollOutcome.RELOAD, result.outcome());
        assertEquals("claude\n", result.text());
    }

    /**
     * The short-circuit skips the read, not the report: a keystroke made after
     * the rejection re-arms auto-save, and every later MISSING poll must keep
     * disarming it or the buffer would be written over content this session
     * must not touch.
     */
    @Test
    void aShortCircuitedMissingPollStillDisarmsTheDebounce(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session =
                new FileEditSession(file, FileContent.load(file, MAX), executor, ARMED_DEBOUNCE, MAX);

        byte[] binary = { 'a', 0, 'b', '\n' };
        Files.write(file, binary);
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 2000));
        assertEquals(PollOutcome.MISSING, session.poll().get(5, TimeUnit.SECONDS).outcome());

        session.edit("mine\n");
        assertEquals(PollOutcome.MISSING, session.poll().get(5, TimeUnit.SECONDS).outcome());
        awaitPastDebounce(ARMED_DEBOUNCE);

        assertArrayEquals(binary, Files.readAllBytes(file),
                "a repeated MISSING must keep auto-save disarmed");
    }

    /**
     * The viewer vetoes the user's close gesture on an unresolved CONFLICT so
     * they answer the banner first, but must never hold a tab hostage over a
     * buffer they have already chosen to discard -- so it asks the session.
     */
    @Test
    void abandonedIsAOneWayLatchTheViewerCanQuery(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session = sessionFor(file);

        assertFalse(session.abandoned());
        session.edit("mine\n");
        assertFalse(session.abandoned());

        session.abandon();
        assertTrue(session.abandoned());
        session.edit("later\n");
        assertTrue(session.abandoned(), "abandon must not be reversible by an edit");
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
     * The viewer loads a file's content on one thread and constructs the
     * session later, inside a {@code Platform.runLater}. If the file changes
     * in that window, the session must not trust a stamp captured after the
     * caller's read: its first {@link FileEditSession#poll()} must still
     * detect the file no longer matches what the buffer holds.
     */
    @Test
    void fileChangedBetweenCallersLoadAndConstructionIsDetectedOnFirstPoll(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileContent stale = FileContent.load(file, MAX);
        // Simulate an external write landing after the caller's load but
        // before the session is constructed.
        writeExternally(file, "claude\n");

        FileEditSession session = new FileEditSession(file, stale, executor, IDLE_DEBOUNCE, MAX);
        var result = session.poll().get(5, TimeUnit.SECONDS);

        assertEquals(PollOutcome.RELOAD, result.outcome());
        assertEquals("claude\n", result.text());
    }

    /**
     * A stamp change alone is not proof of an external edit: something may
     * have rewritten the file with the exact bytes the buffer already holds,
     * or merely touched it. That must not raise a conflict, even for a dirty
     * buffer -- there is nothing to reconcile.
     */
    @Test
    void stampChangeWithIdenticalBytesIsNotAConflict(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session = sessionFor(file);

        session.edit("same\n");
        writeExternally(file, "same\n");
        var result = session.poll().get(5, TimeUnit.SECONDS);

        assertEquals(PollOutcome.UNCHANGED, result.outcome());
        assertEquals(State.DIRTY, session.state(),
                "identical bytes must not raise a conflict for a dirty buffer");
        // The stamp was adopted, so the file no longer looks changed either.
        var again = session.poll().get(5, TimeUnit.SECONDS);
        assertEquals(PollOutcome.UNCHANGED, again.outcome());
    }

    /**
     * The constructor seeds an UNVERIFIED stamp, so the FIRST poll always falls
     * through to loading the file and comparing it. That comparison must be
     * against the text the buffer is BASED on, never against the live buffer:
     * a dirty buffer differs from the disk by construction, so comparing the
     * two reports a conflict for a file nothing has touched. Concretely: open a
     * file from the search rail and type before the first 1.5s tick, and the
     * tick raises "changed on disk while you were editing it", disarms
     * auto-save, vetoes the tab close, and offers a "reload" that throws the
     * typing away -- with no external actor involved at all.
     */
    @Test
    void typingBeforeTheFirstPollIsNotAConflictAgainstAnUntouchedFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session = sessionFor(file);

        session.edit("mine\n");
        var result = session.poll().get(5, TimeUnit.SECONDS);

        assertEquals(PollOutcome.UNCHANGED, result.outcome(),
                "an untouched file must not look changed just because the buffer is dirty");
        assertEquals(State.DIRTY, session.state(), "the user's edits are still theirs, and still unsaved");
        // Auto-save is still armed, so the edits really do reach disk.
        session.flush().get(5, TimeUnit.SECONDS);
        assertEquals("mine\n", Files.readString(file));
        assertEquals(State.CLEAN, session.state());
    }

    /** The same must hold for a file whose buffer was never typed into but merely re-polled. */
    @Test
    void repeatedPollsOfAnUntouchedDirtyFileStayUnchanged(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session = sessionFor(file);

        session.edit("mine\n");
        assertEquals(PollOutcome.UNCHANGED, session.poll().get(5, TimeUnit.SECONDS).outcome());
        session.edit("mine again\n");
        assertEquals(PollOutcome.UNCHANGED, session.poll().get(5, TimeUnit.SECONDS).outcome());
        assertEquals(State.DIRTY, session.state());
    }

    /**
     * A MISSING poll must veto every write path, not just the debounce. The
     * viewer forces a flush on a tab switch, on the code area losing focus, on
     * the tab close and at shutdown -- so with only the debounce disarmed, a
     * user who clicks anywhere at all after the missing-file banner appears
     * silently recreates the file that is gone from disk, without ever
     * answering the banner.
     */
    @Test
    void aFlushAfterAMissingPollDoesNotRecreateTheFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session = sessionFor(file);

        session.edit("mine\n");
        Files.delete(file);
        assertEquals(PollOutcome.MISSING, session.poll().get(5, TimeUnit.SECONDS).outcome());

        session.flush().get(5, TimeUnit.SECONDS);

        assertFalse(Files.exists(file), "a forced flush must not recreate a file a MISSING poll reported");
        assertEquals(State.DIRTY, session.state(), "the buffer is still the user's to keep");
    }

    /**
     * ...and the veto survives a later keystroke, since every one of those
     * forced flushes can follow one. Only the banner's own answer re-arms it.
     */
    @Test
    void anEditAfterAMissingPollStillCannotFlushTheFileBack(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session =
                new FileEditSession(file, FileContent.load(file, MAX), executor, ARMED_DEBOUNCE, MAX);

        session.edit("mine\n");
        Files.delete(file);
        assertEquals(PollOutcome.MISSING, session.poll().get(5, TimeUnit.SECONDS).outcome());

        session.edit("later\n");
        session.flush().get(5, TimeUnit.SECONDS);
        awaitPastDebounce(ARMED_DEBOUNCE);

        assertFalse(Files.exists(file), "only keepMine may put an abandoned-on-disk file back");
    }

    /** keepMine is the documented escape hatch: it deliberately re-creates the file. */
    @Test
    void keepMineDeliberatelyRecreatesAMissingFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session = sessionFor(file);

        session.edit("mine\n");
        Files.delete(file);
        assertEquals(PollOutcome.MISSING, session.poll().get(5, TimeUnit.SECONDS).outcome());

        session.keepMine().get(5, TimeUnit.SECONDS);

        assertEquals("mine\n", Files.readString(file), "keepMine is how the user asks for exactly this");
        assertEquals(State.CLEAN, session.state());
        // And the latch is genuinely cleared: ordinary auto-save works again.
        session.edit("after\n");
        session.flush().get(5, TimeUnit.SECONDS);
        assertEquals("after\n", Files.readString(file));
    }

    /** takeDisk is the other resolution, and must clear the latch too. */
    @Test
    void takeDiskAfterAMissingPollReArmsAutoSave(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session = sessionFor(file);

        session.edit("mine\n");
        Files.write(file, new byte[] { 'a', 0, 'b', '\n' });
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 2000));
        assertEquals(PollOutcome.MISSING, session.poll().get(5, TimeUnit.SECONDS).outcome());

        writeExternally(file, "claude\n");
        assertEquals("claude\n", session.takeDisk().get(5, TimeUnit.SECONDS));

        session.edit("after\n");
        session.flush().get(5, TimeUnit.SECONDS);
        assertEquals("after\n", Files.readString(file), "takeDisk must re-arm the write path");
    }

    /** A file that comes back unchanged resolves the latch by itself: auto-save resumes. */
    @Test
    void aRestoredFileClearsTheMissingLatchOnTheNextPoll(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session = sessionFor(file);

        // Adopt a real stamp first, so "restored identically" is observable.
        assertEquals(PollOutcome.UNCHANGED, session.poll().get(5, TimeUnit.SECONDS).outcome());
        FileTime stampedAt = Files.getLastModifiedTime(file);
        session.edit("mine\n");
        Files.delete(file);
        assertEquals(PollOutcome.MISSING, session.poll().get(5, TimeUnit.SECONDS).outcome());

        Files.writeString(file, "one\n");
        Files.setLastModifiedTime(file, stampedAt);
        assertEquals(PollOutcome.UNCHANGED, session.poll().get(5, TimeUnit.SECONDS).outcome());

        session.flush().get(5, TimeUnit.SECONDS);
        assertEquals("mine\n", Files.readString(file), "the file is back, so auto-save is back");
    }

    /**
     * The 1.5s poller leaves a blind window the 2s debounce fires inside: the
     * user types, Claude writes the file 0.2s later, and the debounce lands
     * before any tick has looked. The write path must re-read the disk itself
     * rather than trust the stamp it last adopted, or it clobbers the external
     * edit with no conflict raised and no trace.
     */
    @Test
    void aDebouncedWriteThatMissedThePollEntersConflictInsteadOfOverwriting(@TempDir Path dir)
            throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session =
                new FileEditSession(file, FileContent.load(file, MAX), executor, ARMED_DEBOUNCE, MAX);
        CountDownLatch conflicted = new CountDownLatch(1);
        session.setOnStateChanged(state -> {
            if (state == State.CONFLICT) {
                conflicted.countDown();
            }
        });

        session.edit("mine\n");
        // No poll in between -- that is the whole point of the window.
        writeExternally(file, "claude\n");

        assertTrue(conflicted.await(5, TimeUnit.SECONDS), "the debounced write should have conflicted");
        assertEquals(State.CONFLICT, session.state());
        assertEquals("claude\n", Files.readString(file), "the external edit must survive untouched");
    }

    /** The same guard on an explicit flush -- Cmd+S, blur, tab switch, close. */
    @Test
    void anExplicitFlushThatMissedThePollEntersConflictInsteadOfOverwriting(@TempDir Path dir)
            throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session = sessionFor(file);

        session.edit("mine\n");
        writeExternally(file, "claude\n");
        session.flush().get(5, TimeUnit.SECONDS);

        assertEquals(State.CONFLICT, session.state());
        assertEquals("claude\n", Files.readString(file), "the external edit must survive untouched");
    }

    /**
     * ...but the guard must not fire on a file merely touched, or rewritten
     * with the bytes already there: that would turn every {@code git checkout}
     * of an identical file into a conflict the user has to answer.
     */
    @Test
    void aTouchedButUnchangedFileDoesNotBlockTheWrite(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session = sessionFor(file);

        session.edit("mine\n");
        writeExternally(file, "one\n");
        session.flush().get(5, TimeUnit.SECONDS);

        assertEquals(State.CLEAN, session.state());
        assertEquals("mine\n", Files.readString(file));
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
    /**
     * A file with no line terminator at all -- an empty file Claude just
     * created, a {@code VERSION}-style single line with no trailing newline --
     * loads as {@code Terminator.NONE}. The user types a second line and the
     * save puts LF on disk, because {@code toDiskText(NONE)} is the identity.
     * The comparator must ask what the DISK now holds, not the load-time write
     * policy: otherwise every later touch of that file, while the buffer is
     * dirty, fails the terminator arm and raises "changed on disk while you
     * were editing it" -- with no external content change involved at all, and
     * with a "reload" button that throws the user's typing away.
     */
    @Test
    void aNoTerminatorFileThatGrewALineDoesNotConflictWithItself(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("VERSION");
        Files.writeString(file, "1.2.3");
        assertEquals(FileContent.Terminator.NONE, FileContent.load(file, MAX).terminator());
        FileEditSession session = sessionFor(file);

        session.edit("1.2.3\nmore\n");
        session.flush().get(5, TimeUnit.SECONDS);
        assertEquals("1.2.3\nmore\n", Files.readString(file));

        // Dirty again, and the file is merely touched (mtime moves, bytes do not).
        session.edit("1.2.3\nmore\nyet\n");
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 2000));
        var result = session.poll().get(5, TimeUnit.SECONDS);

        assertEquals(PollOutcome.UNCHANGED, result.outcome(),
                "the LF the save itself put there is not an external change");
        assertEquals(State.DIRTY, session.state());
        // ...and auto-save is still live, so the typing really does reach disk.
        session.flush().get(5, TimeUnit.SECONDS);
        assertEquals("1.2.3\nmore\nyet\n", Files.readString(file));
        assertEquals(State.CLEAN, session.state());
    }

    /** The same, through the write path's own pre-write check rather than a poll. */
    @Test
    void aNoTerminatorFileThatGrewALineStillFlushesAfterATouch(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("VERSION");
        Files.writeString(file, "1.2.3");
        FileEditSession session = sessionFor(file);

        session.edit("1.2.3\nmore\n");
        session.flush().get(5, TimeUnit.SECONDS);

        session.edit("1.2.3\nmore\nyet\n");
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 2000));
        session.flush().get(5, TimeUnit.SECONDS);

        assertEquals(State.CLEAN, session.state(), "a touch must not block the write");
        assertEquals("1.2.3\nmore\nyet\n", Files.readString(file));
    }

    /**
     * The 1.5s poller's blind window: the file is deleted after this session
     * adopted a real stamp, and a flush (debounce, blur, tab switch, shutdown)
     * lands before the next tick can report MISSING. The write must NOT
     * recreate the file -- no path may silently put back a file the user was
     * never asked about -- it must surface the missing-file question instead.
     */
    @Test
    void aFlushIntoThePollBlindWindowDoesNotRecreateADeletedFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session = sessionFor(file);
        // Adopt a real stamp: only then does this session know the file existed.
        assertEquals(PollOutcome.UNCHANGED, session.poll().get(5, TimeUnit.SECONDS).outcome());

        session.edit("mine\n");
        Files.delete(file);
        session.flush().get(5, TimeUnit.SECONDS);

        assertFalse(Files.exists(file), "a flush must not silently recreate a deleted file");
        assertEquals(State.DIRTY, session.state(), "the buffer is still the user's to keep");
        assertTrue(session.disarmed(), "the missing-file question must be raised, not skipped");
    }

    /** ...and keepMine remains the documented escape hatch out of exactly that. */
    @Test
    void keepMineStillRecreatesAFileDeletedInsideTheBlindWindow(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session = sessionFor(file);
        assertEquals(PollOutcome.UNCHANGED, session.poll().get(5, TimeUnit.SECONDS).outcome());

        session.edit("mine\n");
        Files.delete(file);
        session.flush().get(5, TimeUnit.SECONDS);
        assertFalse(Files.exists(file));

        session.keepMine().get(5, TimeUnit.SECONDS);

        assertEquals("mine\n", Files.readString(file), "keepMine is how the user asks for this");
        assertEquals(State.CLEAN, session.state());
        assertFalse(session.disarmed());
    }

    /**
     * Only a genuinely absent file routes into the missing-file question. Any
     * other IOException from the pre-write stamp (here: a directory that has
     * momentarily lost its permissions) must fall through to the write, so a
     * transient failure is reported as the save error it is instead of raising
     * a banner about a file that is still sitting there.
     */
    @Test
    void aNonNoSuchFileStampFailureFallsThroughToTheWritesOwnError(@TempDir Path dir) throws Exception {
        Path locked = Files.createDirectory(dir.resolve("locked"));
        Path file = locked.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session = sessionFor(file);
        assertEquals(PollOutcome.UNCHANGED, session.poll().get(5, TimeUnit.SECONDS).outcome());

        session.edit("mine\n");
        assertTrue(locked.toFile().setExecutable(false), "test needs a non-traversable directory");
        assertTrue(locked.toFile().setReadable(false));
        try {
            session.flush().get(5, TimeUnit.SECONDS);

            assertEquals(State.ERROR, session.state(), "a transient failure is a save error");
            assertNotNull(session.lastError());
            assertFalse(session.lastError() instanceof NoSuchFileException,
                    "the file was never absent");
            assertFalse(session.disarmed(),
                    "a transient failure must not raise the missing-file question");
        } finally {
            assertTrue(locked.toFile().setReadable(true));
            assertTrue(locked.toFile().setExecutable(true));
        }
        assertEquals("one\n", Files.readString(file), "nothing was written");
    }

    /**
     * A conflict the user resolves by hand -- typing the buffer back into
     * agreement with the disk -- must not stay on screen. The silent stamp
     * adoption already recognised there was nothing left to reconcile; leaving
     * CONFLICT standing would keep the banner up, auto-save disarmed and the
     * tab close vetoed over a file with no disagreement in it.
     */
    @Test
    void aConflictWhoseBufferComesToMatchTheDiskClearsOnTheNextPoll(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session = sessionFor(file);

        session.edit("mine\n");
        writeExternally(file, "claude\n");
        assertEquals(PollOutcome.CONFLICT, session.poll().get(5, TimeUnit.SECONDS).outcome());
        assertEquals(State.CONFLICT, session.state());

        // The user gives in and types Claude's version themselves.
        session.edit("claude\n");
        var result = session.poll().get(5, TimeUnit.SECONDS);

        assertEquals(PollOutcome.UNCHANGED, result.outcome());
        assertEquals(State.CLEAN, session.state(), "nothing is left to reconcile");
        // Auto-save is genuinely re-armed: an ordinary edit saves again.
        session.edit("after\n");
        session.flush().get(5, TimeUnit.SECONDS);
        assertEquals("after\n", Files.readString(file));
    }

    /**
     * A file the WRITE path discovers is no longer editable is the missing
     * banner's question ("gone or no longer editable ... keep mine / close
     * tab"), not the conflict banner's -- whose "reload" can only fail, since
     * takeDisk refuses a non-editable file by contract.
     */
    @Test
    void aNonEditableFileFoundByTheWritePathIsMissingNotAConflict(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "one\n");
        FileEditSession session = sessionFor(file);

        session.edit("mine\n");
        byte[] binary = { 'a', 0, 'b', '\n' };
        Files.write(file, binary);
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 2000));
        session.flush().get(5, TimeUnit.SECONDS);

        assertNotEquals(State.CONFLICT, session.state(),
                "a file this editor cannot save is not a conflict to reload");
        assertEquals(State.DIRTY, session.state());
        assertTrue(session.disarmed());
        assertArrayEquals(binary, Files.readAllBytes(file), "the write must not have landed");
        // And the poll agrees, without contradicting the write path.
        assertEquals(PollOutcome.MISSING, session.poll().get(5, TimeUnit.SECONDS).outcome());
    }

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
