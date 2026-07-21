package app.drydock.ui.explorer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * One open file's save state machine (spec: "Editable Explorer files with
 * auto-save"). Owns every byte this feature reads or writes; holds no
 * JavaFX types, so it is unit-testable the way {@link SyntaxHighlighter}
 * is, and the viewer stays a thin layer over it.
 *
 * <p><b>Concurrency invariant.</b> Every write, stamp capture and
 * {@link #poll()} runs on the single-threaded {@code executor} the viewer
 * owns. That serialization is what stops a session mistaking its own write
 * for an external one: the stamp capture that follows a write cannot
 * interleave with a poll. {@link #poll()} additionally returns immediately
 * while {@link State#SAVING}.</p>
 *
 * <p>Writes go directly through {@link Files#write} rather than
 * temp-file + {@code ATOMIC_MOVE}: atomic replace would reset permissions
 * and swap the inode under anything watching the file, and these are
 * in-place edits of tracked source files in a git worktree.</p>
 */
final class FileEditSession {

    /** {@link #CONFLICT} and {@link #ERROR} both disarm auto-save until the user acts. */
    enum State { CLEAN, DIRTY, SAVING, CONFLICT, ERROR }

    enum PollOutcome { UNCHANGED, RELOAD, CONFLICT, MISSING }

    /** {@code text} carries the disk content for {@link PollOutcome#RELOAD}, else null. */
    record PollResult(PollOutcome outcome, String text) { }

    /** Identity of the file as this session last saw it (spec: change detection is mtime + size). */
    private record DiskStamp(FileTime modified, long size) { }

    private final Path file;
    private final FileContent content;
    private final ScheduledExecutorService executor;
    private final Duration debounce;

    /** Guarded by the executor's single thread, except the volatile reads exposed to the FX thread. */
    private volatile State state = State.CLEAN;
    private volatile IOException lastError;
    private String pendingText;
    private DiskStamp stamp;
    private ScheduledFuture<?> armedSave;

    private Consumer<State> onStateChanged = state -> { };
    private Consumer<IOException> onSaveFailed = error -> { };

    FileEditSession(Path file, FileContent content, ScheduledExecutorService executor, Duration debounce) {
        this.file = file;
        this.content = content;
        this.executor = executor;
        this.debounce = debounce;
        this.pendingText = content.text();
        this.stamp = readStampQuietly();
    }

    /** Invoked on the executor thread; the viewer hops to the FX thread itself. */
    void setOnStateChanged(Consumer<State> handler) {
        this.onStateChanged = handler == null ? state -> { } : handler;
    }

    /** Invoked on the executor thread; the viewer hops to the FX thread itself. */
    void setOnSaveFailed(Consumer<IOException> handler) {
        this.onSaveFailed = handler == null ? error -> { } : handler;
    }

    State state() {
        return state;
    }

    /** The failure behind {@link State#ERROR}, for the viewer's banner. */
    IOException lastError() {
        return lastError;
    }

    Path file() {
        return file;
    }

    /**
     * Records an edit and (re)arms the debounce. A file in {@link
     * State#CONFLICT} stays there -- the user must resolve it first -- but
     * the newest text is still kept, so {@link #keepMine()} writes what
     * they actually have.
     */
    void edit(String text) {
        synchronized (this) {
            pendingText = text;
            if (armedSave != null) {
                armedSave.cancel(false);
            }
            if (state == State.CONFLICT) {
                return;
            }
            setState(State.DIRTY);
            armedSave = executor.schedule(this::writeIfDirty, debounce.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Writes immediately, cancelling any armed debounce. Completes normally
     * even when the write fails -- failure is reported through {@link
     * #setOnSaveFailed} and {@link State#ERROR}, so a forced flush on a
     * teardown path can never propagate an exception into it.
     */
    CompletableFuture<Void> flush() {
        synchronized (this) {
            if (armedSave != null) {
                armedSave.cancel(false);
                armedSave = null;
            }
        }
        return CompletableFuture.runAsync(this::writeIfDirty, executor);
    }

    /**
     * Awaits {@link #flush()}, bounded. The shutdown path's entry point:
     * the viewer's executor threads are daemons, so a fire-and-forget flush
     * is killed mid-write at JVM exit (the failure {@code
     * AnnotationStore.flushPendingSaves} exists to prevent).
     */
    void flushBlocking(Duration timeout) {
        try {
            flush().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (TimeoutException | ExecutionException e) {
            // Reported through onSaveFailed already; shutdown must proceed.
        }
    }

    /**
     * Compares the file's current identity against the stamp this session
     * last adopted. Clean + changed means Claude edited a file we are not
     * holding edits for, so the viewer can silently reload; dirty + changed
     * is a genuine conflict and disarms auto-save.
     */
    CompletableFuture<PollResult> poll() {
        return CompletableFuture.supplyAsync(() -> {
            if (state == State.SAVING) {
                return new PollResult(PollOutcome.UNCHANGED, null);
            }
            DiskStamp current;
            try {
                current = readStamp();
            } catch (IOException e) {
                // Deleted underneath us, or unreadable: either way the viewer
                // must ask rather than silently recreate the file.
                return new PollResult(PollOutcome.MISSING, null);
            }
            if (current.equals(stamp)) {
                return new PollResult(PollOutcome.UNCHANGED, null);
            }
            if (state == State.DIRTY || state == State.CONFLICT) {
                setState(State.CONFLICT);
                return new PollResult(PollOutcome.CONFLICT, null);
            }
            try {
                String text = FileContent.load(file, Long.MAX_VALUE).text();
                stamp = current;
                return new PollResult(PollOutcome.RELOAD, text);
            } catch (IOException e) {
                return new PollResult(PollOutcome.MISSING, null);
            }
        }, executor);
    }

    /** Conflict resolution: the user's buffer wins; write it and adopt the resulting stamp. */
    CompletableFuture<Void> keepMine() {
        return CompletableFuture.runAsync(() -> {
            setState(State.DIRTY);
            writeIfDirty();
        }, executor);
    }

    /** Conflict resolution: disk wins; discard the buffer and hand the disk text back for reload. */
    CompletableFuture<String> takeDisk() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String text = FileContent.load(file, Long.MAX_VALUE).text();
                synchronized (this) {
                    pendingText = text;
                }
                stamp = readStampQuietly();
                setState(State.CLEAN);
                return text;
            } catch (IOException e) {
                fail(e);
                return null;
            }
        }, executor);
    }

    // ---- Executor-thread internals -----------------------------------------

    private void writeIfDirty() {
        String text;
        synchronized (this) {
            armedSave = null;
            // CLEAN: nothing to write. SAVING: a write is already in flight.
            // CONFLICT: disarmed until the user resolves it. ERROR is the
            // exception -- an explicit flush after a failed write IS the
            // retry, so it must be allowed through.
            if (state != State.DIRTY && state != State.ERROR) {
                return;
            }
            text = pendingText;
        }
        setState(State.SAVING);
        try {
            Files.write(file, content.toDiskText(text).getBytes(StandardCharsets.UTF_8));
            // Same executor task as the write: no poll can observe the gap.
            stamp = readStamp();
            lastError = null;
            setState(State.CLEAN);
        } catch (IOException e) {
            fail(e);
        }
    }

    private void fail(IOException e) {
        lastError = e;
        setState(State.ERROR);
        onSaveFailed.accept(e);
    }

    private void setState(State next) {
        if (state != next) {
            state = next;
            onStateChanged.accept(next);
        }
    }

    private DiskStamp readStamp() throws IOException {
        return new DiskStamp(Files.getLastModifiedTime(file), Files.size(file));
    }

    private DiskStamp readStampQuietly() {
        try {
            return readStamp();
        } catch (IOException e) {
            return new DiskStamp(FileTime.fromMillis(0), -1);
        }
    }
}
