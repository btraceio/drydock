package app.drydock.ui.explorer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
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
 * <p><b>Concurrency.</b> Every write, stamp capture and {@link #poll()} runs
 * on the single-threaded {@code executor} the viewer owns; that serialization
 * is what stops a session mistaking its own write for an external one, since
 * the stamp capture that follows a write cannot interleave with a poll.
 * {@link #edit(String)} is the one exception: it runs on the caller's thread
 * (the FX thread) so that {@link #state()} reflects a keystroke immediately
 * for the status chip. All mutable state -- {@code state}, {@code pendingText},
 * {@code stamp}, {@code content}, the armed debounce -- is therefore guarded by
 * {@link #lock}, and every decision that spans a slow I/O call re-checks that
 * state under the lock afterwards rather than trusting the pre-I/O reading.</p>
 *
 * <p>The bridge between the two threads is {@code editSeq}, bumped by every
 * edit. A write captures it alongside the text; when the write lands, the
 * session returns to {@link State#CLEAN} only if the counter is unchanged.
 * An edit that arrives mid-write therefore leaves the session {@link
 * State#DIRTY} with the debounce re-armed instead of being silently lost.</p>
 *
 * <p>Listeners are never invoked while {@link #lock} is held, and every
 * completion path -- success, failure and early return -- clears {@link
 * State#SAVING}, so a throwable from the write path (or from a listener)
 * cannot strand the session and kill auto-save.</p>
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
    private final ScheduledExecutorService executor;
    private final Duration debounce;
    private final long maxBytes;

    /** Guards every mutable field below; never held while a listener runs. */
    private final Object lock = new Object();

    /** Volatile only so {@link #state()} can be read off-lock by the FX thread. */
    private volatile State state = State.CLEAN;
    private volatile IOException lastError;
    /** Replaced when a reload adopts fresh disk content, so the terminator stays accurate. */
    private volatile FileContent content;
    private String pendingText;
    private DiskStamp stamp;
    private ScheduledFuture<?> armedSave;
    private long editSeq;
    private boolean writeInFlight;

    private volatile Consumer<State> onStateChanged = state -> { };
    private volatile Consumer<IOException> onSaveFailed = error -> { };

    /**
     * @param maxBytes the truncation limit reloads are read under -- the same
     *     limit the initial {@link FileContent#load} used, so a file Claude
     *     replaces with a huge one cannot be slurped whole into memory
     * @throws IllegalArgumentException if {@code content} is not {@link
     *     FileContent#editable()}. A truncated buffer's text carries the
     *     truncation notice and would overwrite the file's tail; an
     *     undecodable one carries U+FFFD replacements; a mixed-terminator one
     *     would be renormalised line by line. The viewer gates session
     *     creation on the same predicate, but this class owns the bytes and
     *     enforces its own precondition.
     */
    FileEditSession(Path file, FileContent content, ScheduledExecutorService executor,
                    Duration debounce, long maxBytes) {
        if (!content.editable()) {
            throw new IllegalArgumentException("refusing to edit a non-editable buffer: " + file);
        }
        this.file = file;
        this.content = content;
        this.executor = executor;
        this.debounce = debounce;
        this.maxBytes = maxBytes;
        this.pendingText = content.text();
        this.stamp = readStampQuietly();
    }

    /** Invoked on the executor thread, never while an internal lock is held. */
    void setOnStateChanged(Consumer<State> handler) {
        this.onStateChanged = handler == null ? state -> { } : handler;
    }

    /** Invoked on the executor thread, never while an internal lock is held. */
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
     * Records an edit and (re)arms the debounce. Runs on the caller's thread
     * so {@link #state()} is {@link State#DIRTY} the instant it returns; the
     * state-change callback is still delivered on the executor thread. A file
     * in {@link State#CONFLICT} stays there -- the user must resolve it first
     * -- but the newest text is still kept, so {@link #keepMine()} writes what
     * they actually have.
     */
    void edit(String text) {
        State changed;
        synchronized (lock) {
            pendingText = text;
            editSeq++;
            cancelArmed();
            if (state == State.CONFLICT) {
                return;
            }
            changed = transition(State.DIRTY);
            arm();
        }
        if (changed != null) {
            Consumer<State> handler = onStateChanged;
            submit(() -> handler.accept(changed));
        }
    }

    /**
     * Writes immediately, cancelling any armed debounce. Completes normally
     * even when the write fails -- failure is reported through {@link
     * #setOnSaveFailed} and {@link State#ERROR}, so a forced flush on a
     * teardown path can never propagate an exception into it.
     */
    CompletableFuture<Void> flush() {
        synchronized (lock) {
            cancelArmed();
        }
        return CompletableFuture.runAsync(this::writeIfDirty, executor);
    }

    /**
     * Awaits {@link #flush()}, bounded. The shutdown path's entry point:
     * the viewer's executor threads are daemons, so a fire-and-forget flush
     * is killed mid-write at JVM exit (the failure {@code
     * AnnotationStore.flushPendingSaves} exists to prevent). A flush that does
     * not finish in time is a genuine loss of the user's edits, so it is
     * recorded in {@link #lastError} and reported to {@link #setOnSaveFailed}
     * here -- nothing else has reported it -- before shutdown proceeds.
     */
    void flushBlocking(Duration timeout) {
        try {
            flush().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            report(new IOException("interrupted while saving " + file, e));
        } catch (TimeoutException e) {
            report(new IOException("timed out after " + timeout + " saving " + file, e));
        } catch (ExecutionException e) {
            // writeIfDirty converts everything it can into ERROR itself, so
            // reaching here means the task died in a way nothing reported.
            report(new IOException("failed to save " + file, e.getCause()));
        }
    }

    /**
     * Compares the file's current identity against the stamp this session
     * last adopted. Clean + changed means Claude edited a file we are not
     * holding edits for, so the viewer can silently reload; dirty + changed
     * is a genuine conflict and disarms auto-save. {@link State#ERROR} counts
     * as dirty: it is a buffer whose write failed, and its edits are still
     * unsaved.
     */
    CompletableFuture<PollResult> poll() {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (lock) {
                if (state == State.SAVING) {
                    return new PollResult(PollOutcome.UNCHANGED, null);
                }
            }
            DiskStamp current;
            try {
                current = readStamp();
            } catch (IOException e) {
                // Deleted underneath us, or unreadable: either way the viewer
                // must ask rather than silently recreate the file.
                return new PollResult(PollOutcome.MISSING, null);
            }
            boolean conflicted;
            State changed;
            synchronized (lock) {
                if (current.equals(stamp)) {
                    return new PollResult(PollOutcome.UNCHANGED, null);
                }
                conflicted = hasUnsavedEdits();
                changed = conflicted ? enterConflict() : null;
            }
            if (conflicted) {
                notifyState(changed);
                return new PollResult(PollOutcome.CONFLICT, null);
            }
            FileContent fresh;
            try {
                fresh = FileContent.load(file, maxBytes);
            } catch (IOException e) {
                return new PollResult(PollOutcome.MISSING, null);
            }
            if (!fresh.editable()) {
                // Claude replaced the file with something we must not write
                // back (binary, over the size limit, mixed terminators). Adopt
                // nothing -- keeping the old stamp means every later poll keeps
                // saying so -- and let the viewer decide, exactly as for a
                // deleted file.
                return new PollResult(PollOutcome.MISSING, null);
            }
            synchronized (lock) {
                // An edit may have landed while we were reading. Adopting the
                // stamp now would hide Claude's version from the next write,
                // which would then overwrite it with no conflict raised.
                conflicted = hasUnsavedEdits();
                if (conflicted) {
                    changed = enterConflict();
                } else {
                    content = fresh;
                    pendingText = fresh.text();
                    stamp = current;
                }
            }
            notifyState(changed);
            return conflicted
                    ? new PollResult(PollOutcome.CONFLICT, null)
                    : new PollResult(PollOutcome.RELOAD, fresh.text());
        }, executor);
    }

    /** Conflict resolution: the user's buffer wins; write it and adopt the resulting stamp. */
    CompletableFuture<Void> keepMine() {
        return CompletableFuture.runAsync(() -> {
            State changed;
            synchronized (lock) {
                changed = transition(State.DIRTY);
            }
            notifyState(changed);
            writeIfDirty();
        }, executor);
    }

    /**
     * Conflict resolution: disk wins; discard the buffer and hand the disk
     * text back for reload. Completes exceptionally when the file cannot be
     * read, or when it is no longer an editable buffer -- the session's state
     * is left alone in that case, so an unresolved conflict stays unresolved
     * rather than silently looking clean.
     */
    CompletableFuture<String> takeDisk() {
        return CompletableFuture.supplyAsync(() -> {
            FileContent fresh;
            try {
                fresh = FileContent.load(file, maxBytes);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
            if (!fresh.editable()) {
                throw new CompletionException(
                        new IOException("file on disk is no longer editable: " + file));
            }
            DiskStamp current = readStampQuietly();
            State changed;
            synchronized (lock) {
                content = fresh;
                pendingText = fresh.text();
                stamp = current;
                editSeq++;
                cancelArmed();
                lastError = null;
                changed = transition(State.CLEAN);
            }
            notifyState(changed);
            return fresh.text();
        }, executor);
    }

    // ---- Executor-thread internals -----------------------------------------

    private void writeIfDirty() {
        String text;
        long seq;
        FileContent snapshot;
        State changed;
        synchronized (lock) {
            armedSave = null;
            // CLEAN: nothing to write. SAVING: a write is already in flight.
            // CONFLICT: disarmed until the user resolves it. ERROR is the
            // exception -- an explicit flush after a failed write IS the
            // retry, so it must be allowed through.
            if (writeInFlight || (state != State.DIRTY && state != State.ERROR)) {
                return;
            }
            text = pendingText;
            seq = editSeq;
            snapshot = content;
            writeInFlight = true;
            changed = transition(State.SAVING);
        }
        try {
            notifyState(changed);
            Files.write(file, snapshot.toDiskText(text).getBytes(StandardCharsets.UTF_8));
            // Same executor task as the write: no poll can observe the gap.
            DiskStamp written = readStamp();
            State next;
            synchronized (lock) {
                stamp = written;
                lastError = null;
                if (editSeq == seq) {
                    next = transition(State.CLEAN);
                } else {
                    // An edit landed while we were writing. Its debounce was
                    // cancelled by nothing, but re-arm from here so the newer
                    // text cannot be stranded behind a CLEAN state.
                    next = transition(State.DIRTY);
                    cancelArmed();
                    arm();
                }
            }
            notifyState(next);
        } catch (IOException e) {
            fail(e);
        } catch (RuntimeException | Error e) {
            // A listener or the filesystem layer threw something we do not
            // model. Swallowing the state would be worse than swallowing the
            // throwable: a stranded SAVING kills auto-save AND conflict
            // detection for the rest of the session's life.
            fail(new IOException("unexpected failure while saving " + file, e));
        } finally {
            synchronized (lock) {
                writeInFlight = false;
            }
        }
    }

    /** Records the failure, leaves the buffer intact and clears {@link State#SAVING}. */
    private void fail(IOException e) {
        State changed;
        synchronized (lock) {
            lastError = e;
            changed = transition(State.ERROR);
        }
        notifyState(changed);
        onSaveFailed.accept(e);
    }

    /** Records a failure nothing else will report, without touching the state machine. */
    private void report(IOException e) {
        lastError = e;
        onSaveFailed.accept(e);
    }

    /** Must hold {@link #lock}; returns the new state to announce, or null if unchanged. */
    private State transition(State next) {
        if (state == next) {
            return null;
        }
        state = next;
        return next;
    }

    private void notifyState(State changed) {
        if (changed != null) {
            onStateChanged.accept(changed);
        }
    }

    /** Must hold {@link #lock}: a buffer whose edits are not on disk. */
    private boolean hasUnsavedEdits() {
        return state == State.DIRTY || state == State.CONFLICT || state == State.ERROR;
    }

    /** Must hold {@link #lock}: enter conflict and disarm auto-save. */
    private State enterConflict() {
        cancelArmed();
        return transition(State.CONFLICT);
    }

    /** Must hold {@link #lock}. */
    private void cancelArmed() {
        if (armedSave != null) {
            armedSave.cancel(false);
            armedSave = null;
        }
    }

    /** Must hold {@link #lock}. */
    private void arm() {
        try {
            armedSave = executor.schedule(this::writeIfDirty, debounce.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            armedSave = null;
        }
    }

    private void submit(Runnable task) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            // Shutting down; the state itself is already correct.
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
