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
import java.util.concurrent.Executor;
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
 * {@link #edit(String)} is one exception: it runs on the caller's thread
 * (the FX thread) so that {@link #state()} reflects a keystroke immediately
 * for the status chip. The constructor does no I/O of its own -- it seeds an
 * unverified sentinel stamp rather than reading the disk, because the
 * {@code content} it is handed was already read by the caller, on a
 * different thread, possibly one {@code Platform.runLater} hop earlier; a
 * stamp captured fresh here could describe bytes newer than what
 * {@code content} holds. The sentinel guarantees the first {@link #poll()}
 * always verifies the file against the buffer instead of trusting a stamp
 * that might be lying. All mutable state --
 * {@code state}, {@code pendingText},
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

    /**
     * {@link #CONFLICT} disarms auto-save until the user resolves it. {@link
     * #ERROR} does not: the next {@link #edit(String)} re-arms the debounce
     * and an explicit {@link #flush()} retries too, so a transient failure
     * (a momentarily read-only file, a full disk that frees up) clears on
     * its own without the user having to do anything special.
     */
    enum State { CLEAN, DIRTY, SAVING, CONFLICT, ERROR }

    enum PollOutcome { UNCHANGED, RELOAD, CONFLICT, MISSING }

    /** {@code text} carries the disk content for {@link PollOutcome#RELOAD}, else null. */
    record PollResult(PollOutcome outcome, String text) { }

    /** Identity of the file as this session last saw it (spec: change detection is mtime + size). */
    private record DiskStamp(FileTime modified, long size) { }

    /**
     * Seeded by the constructor in place of a real stamp. No file on disk can
     * report a negative size, so this can never equal a {@link #readStamp}
     * result -- the first {@link #poll()} therefore always falls through to
     * load and compare the file rather than trusting a stamp that might
     * describe content this session was never handed.
     */
    private static final DiskStamp UNVERIFIED = new DiskStamp(FileTime.fromMillis(0), -1);

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
    /**
     * The identity of a file this session has already loaded and refused to
     * adopt (binary, oversized, mixed terminators -- see {@link #missing}).
     * {@code missing()} deliberately adopts no stamp, so without remembering
     * the rejection every poll tick would re-read the whole file for as long as
     * the tab stays open: a 2 MB binary Claude dropped in place would be loaded
     * every {@code POLL_INTERVAL} forever, on the same single thread the saves
     * use. Cleared whenever a stamp IS adopted, so a file that becomes editable
     * again is picked up normally.
     */
    private DiskStamp rejectedStamp;
    private ScheduledFuture<?> armedSave;
    private long editSeq;
    private boolean writeInFlight;
    /**
     * Set by {@link #abandon()} and never cleared: a one-way latch that vetoes
     * every write path this session owns. The tab is going away and the user
     * has explicitly chosen to discard the buffer, so a listener firing during
     * teardown (the selection listener's flush, the code area's focus-lost
     * flush) must not be able to put those bytes back on disk.
     */
    private boolean abandoned;

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
        this.stamp = UNVERIFIED;
    }

    /** Invoked on the executor thread, never while an internal lock is held. */
    void setOnStateChanged(Consumer<State> handler) {
        this.onStateChanged = handler == null ? state -> { } : handler;
    }

    /**
     * Invoked on the executor thread, never while an internal lock is held --
     * except for the shutdown path: {@link #flushBlocking} reports a timeout,
     * an interruption, or an executor task that died without reporting itself
     * synchronously, on whatever thread called {@link #flushBlocking}.
     */
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
            if (abandoned) {
                return;
            }
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
     * teardown path cannot have a write failure propagate an exception into
     * it. That guarantee covers the write path only: {@link #fail} invokes
     * {@link #setOnSaveFailed}'s handler outside every catch this class owns,
     * so a listener that itself throws still escapes into the returned
     * future. ({@link #flushBlocking} catches the resulting {@link
     * ExecutionException}, so the shutdown path stays safe.)
     */
    CompletableFuture<Void> flush() {
        synchronized (lock) {
            cancelArmed();
        }
        return runOnExecutor(this::writeIfDirty);
    }

    /**
     * Permanently disarms this session: cancels the armed debounce and vetoes
     * every future write, including an explicit {@link #flush()} and any later
     * {@link #edit(String)}. There is no way back, deliberately -- the only
     * caller is the viewer's "the file is gone from disk, close the tab"
     * action, where the user has chosen to discard the buffer and the tab is
     * about to be torn down. Teardown itself fires several flushes (the
     * selection listener's, the code area's focus-lost one), so vetoing at the
     * session is the only place that can guarantee none of them recreates the
     * very file the user just abandoned.
     */
    void abandon() {
        synchronized (lock) {
            abandoned = true;
            cancelArmed();
        }
    }

    /**
     * Whether {@link #abandon()} has latched. The viewer needs this to tell an
     * unresolved conflict (where a close gesture must be vetoed so the user
     * answers the banner first) from a buffer the user has already deliberately
     * discarded (where the tab must be allowed to go).
     */
    boolean abandoned() {
        synchronized (lock) {
            return abandoned;
        }
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
     * last adopted. A changed stamp is not by itself proof of an external
     * edit -- something may have rewritten the file with the exact bytes the
     * buffer already holds, or merely touched it -- so a stamp change is
     * always followed by loading the file and comparing its text and
     * terminator before this reports anything: an identical match adopts the
     * new stamp and reports {@link PollOutcome#UNCHANGED}, dirty or not,
     * since there is nothing to reconcile. Only once the file genuinely
     * differs does dirtiness matter:
     * clean + changed means Claude edited a file we are not holding edits
     * for, so the viewer can silently reload; dirty + changed is a genuine
     * conflict and disarms auto-save. {@link State#ERROR} counts as dirty: it
     * is a buffer whose write failed, and its edits are still unsaved.
     */
    CompletableFuture<PollResult> poll() {
        try {
            return CompletableFuture.supplyAsync(() -> pollNow(), executor);
        } catch (RejectedExecutionException e) {
            // Shutting down: nothing to reload or conflict against, and the
            // caller (a viewer timer) must not see an exception for it.
            return CompletableFuture.completedFuture(new PollResult(PollOutcome.UNCHANGED, null));
        }
    }

    private PollResult pollNow() {
        synchronized (lock) {
            if (writeInFlight || state == State.SAVING) {
                return new PollResult(PollOutcome.UNCHANGED, null);
            }
        }
        DiskStamp current;
        boolean sameAsRejected;
        try {
            current = readStamp();
        } catch (IOException e) {
            // Deleted underneath us, or unreadable: either way the viewer
            // must ask rather than silently recreate the file. No stamp to
            // remember -- there is nothing here to re-read cheaply anyway.
            return missing(null);
        }
        synchronized (lock) {
            if (current.equals(stamp)) {
                rejectedStamp = null;
                return new PollResult(PollOutcome.UNCHANGED, null);
            }
            sameAsRejected = current.equals(rejectedStamp);
        }
        if (sameAsRejected) {
            // Byte-for-byte the same file we already loaded and refused once.
            // Loading it again would change nothing except the cost -- but the
            // report (and the disarm it carries) still has to be repeated every
            // tick, or an edit made after the rejection would re-arm the
            // debounce and write the buffer over content this session must not
            // touch.
            return missing(current);
        }
        // The stamp differs, but that alone does not mean the bytes did --
        // load and compare text before deciding anything, rather than
        // declaring a conflict (or a reload) against a stamp change alone.
        FileContent fresh;
        try {
            fresh = FileContent.load(file, maxBytes);
        } catch (IOException e) {
            return missing(null);
        }
        if (!fresh.editable()) {
            // Claude replaced the file with something we must not write
            // back (binary, over the size limit, mixed terminators). Adopt
            // nothing -- keeping the old stamp means every later poll keeps
            // saying so -- and let the viewer decide, exactly as for a
            // deleted file. The rejected identity IS remembered, so the next
            // tick can say so again without re-reading the whole file.
            return missing(current);
        }
        boolean conflicted;
        State changed;
        synchronized (lock) {
            if (fresh.text().equals(pendingText) && fresh.terminator() == content.terminator()) {
                // Same bytes under a new stamp -- a rewrite that changed
                // nothing, or a touch. (Terminator must match too: a file
                // rewritten CRLF-to-LF has identical LF-normalised text but
                // different bytes on disk, which is a real change.) Adopt
                // the stamp silently, regardless of dirtiness: there is
                // nothing here to reconcile, so raising a conflict against
                // content identical to what the user already has would be
                // wrong.
                content = fresh;
                stamp = current;
                rejectedStamp = null;
                return new PollResult(PollOutcome.UNCHANGED, null);
            }
            conflicted = hasUnsavedEdits();
            changed = conflicted ? enterConflict() : null;
        }
        if (conflicted) {
            notifyState(changed);
            return new PollResult(PollOutcome.CONFLICT, null);
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
                rejectedStamp = null;
            }
        }
        notifyState(changed);
        return conflicted
                ? new PollResult(PollOutcome.CONFLICT, null)
                : new PollResult(PollOutcome.RELOAD, fresh.text());
    }

    /**
     * Reports {@link PollOutcome#MISSING} and disarms auto-save on the way out.
     * Disarming is the point: the file the buffer belongs to is gone (deleted,
     * unreadable, or replaced by content this session must not write back), so
     * an armed debounce would fire a second later and recreate it -- making the
     * banner's "keep mine / close tab" choice for the user before they can even
     * read it. The state machine is left alone: the buffer is still the user's,
     * and {@link #keepMine()} is exactly how they re-arm it deliberately.
     *
     * @param rejected the identity of the file that was loaded and refused, so
     *     later polls can short-circuit while it is unchanged; {@code null}
     *     when no usable stamp was obtained (deleted / unreadable), which also
     *     clears any previously remembered one.
     */
    private PollResult missing(DiskStamp rejected) {
        synchronized (lock) {
            rejectedStamp = rejected;
            cancelArmed();
        }
        return new PollResult(PollOutcome.MISSING, null);
    }

    /** Conflict resolution: the user's buffer wins; write it and adopt the resulting stamp. */
    CompletableFuture<Void> keepMine() {
        return runOnExecutor(() -> {
            State changed;
            synchronized (lock) {
                changed = transition(State.DIRTY);
            }
            notifyState(changed);
            writeIfDirty();
        });
    }

    /**
     * Conflict resolution: disk wins; discard the buffer and hand the disk
     * text back for reload. Completes exceptionally when the file cannot be
     * read, or when it is no longer an editable buffer -- the session's state
     * is left alone in that case, so an unresolved conflict stays unresolved
     * rather than silently looking clean. Also completes exceptionally,
     * wrapping the executor's {@link RejectedExecutionException} in an
     * {@link IOException} so callers that cast the cause to {@link
     * IOException} per this method's documented contract do not hit a {@link
     * ClassCastException}, if the executor is already shut down -- unlike
     * {@link #flush()}, {@link #poll()} and {@link #keepMine()}, this
     * method's contract is already "may complete exceptionally", so a
     * shutdown is reported the same way any other failure to read the disk
     * would be.
     */
    CompletableFuture<String> takeDisk() {
        try {
            return CompletableFuture.supplyAsync(this::takeDiskNow, executor);
        } catch (RejectedExecutionException e) {
            CompletableFuture<String> failed = new CompletableFuture<>();
            failed.completeExceptionally(
                    new IOException("executor shut down while reading " + file, e));
            return failed;
        }
    }

    private String takeDiskNow() {
        // Stamp captured before the content read, same as pollNow: an
        // external write landing in between must not make the session adopt
        // a stamp for content it does not actually hold.
        DiskStamp current = readStampQuietly();
        FileContent fresh;
        try {
            fresh = FileContent.load(file, maxBytes);
        } catch (IOException e) {
            throw new CompletionException(e);
        }
        if (!fresh.editable()) {
            // Not adopted below, so the premature stamp capture above is
            // simply discarded along with everything else on this path.
            throw new CompletionException(
                    new IOException("file on disk is no longer editable: " + file));
        }
        State changed;
        synchronized (lock) {
            content = fresh;
            pendingText = fresh.text();
            stamp = current;
            rejectedStamp = null;
            editSeq++;
            cancelArmed();
            lastError = null;
            changed = transition(State.CLEAN);
        }
        notifyState(changed);
        return fresh.text();
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
            // exception -- both an explicit flush and the next edit's
            // debounce retry a failed write, so it must be allowed through.
            // ABANDONED is absolute: no path may write this buffer again.
            if (abandoned || writeInFlight || (state != State.DIRTY && state != State.ERROR)) {
                return;
            }
            text = pendingText;
            seq = editSeq;
            snapshot = content;
            writeInFlight = true;
            changed = transition(State.SAVING);
        }
        // Set only by the success path below; a write/read failure (or the
        // pre-write notifyState throwing) leaves it null so the post-write
        // notification after the try/finally is skipped entirely.
        State next = null;
        try {
            notifyState(changed);
            Files.write(file, snapshot.toDiskText(text).getBytes(StandardCharsets.UTF_8));
            // Same executor task as the write: no poll can observe the gap.
            DiskStamp written = readStamp();
            synchronized (lock) {
                stamp = written;
                rejectedStamp = null;
                lastError = null;
                if (editSeq == seq) {
                    next = transition(State.CLEAN);
                } else {
                    // A newer edit arrived while this write was in flight, so
                    // the buffer stays DIRTY for the newer text; re-arm the
                    // debounce so that text is not stranded behind a save
                    // that only covers what we wrote, not what is now pending.
                    next = transition(State.DIRTY);
                    cancelArmed();
                    arm();
                }
            }
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
        // Outside the try: the write already succeeded and the state machine
        // already reflects it, so a listener fault here must not relabel a
        // successful save as a failure the way the pre-write notification
        // above still can.
        try {
            notifyState(next);
        } catch (RuntimeException | Error e) {
            // Nothing to do -- the save already succeeded; see above.
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

    /**
     * Records a failure nothing else will report, without touching the state
     * machine. Takes {@link #lock} for the {@code lastError} write, same as
     * the write path, even though {@code lastError} is volatile and would be
     * visible either way -- the lock keeps this write from interleaving with
     * one from {@link #fail} on another thread. The listener call itself is
     * deliberately outside the lock and, per {@link #setOnSaveFailed}'s
     * contract, runs on whatever thread called {@link #flushBlocking}, not
     * the executor thread.
     */
    private void report(IOException e) {
        synchronized (lock) {
            lastError = e;
        }
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

    /**
     * {@link CompletableFuture#runAsync(Runnable, Executor)}
     * calls {@code executor.execute} on the calling thread and does not catch
     * {@link RejectedExecutionException} for us, so a shut-down executor would
     * otherwise make this throw synchronously instead of failing the future --
     * exactly the exception {@link #flushBlocking} exists to keep off the
     * shutdown path. Used by callers whose contract is "completes normally".
     */
    private CompletableFuture<Void> runOnExecutor(Runnable task) {
        try {
            return CompletableFuture.runAsync(task, executor);
        } catch (RejectedExecutionException e) {
            return CompletableFuture.completedFuture(null);
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
