package app.drydock.tasks;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The authoritative persisted plan store (T33a, P4.13): one
 * {@link RunningPlan} per plan id under the store root's reserved
 * {@code plans/} family, on the {@link PlanFiles} codec. Every operation
 * runs on ONE background writer thread through a bounded queue and
 * returns a future, so the caller never blocks; on that thread each
 * operation runs under the root's {@link TaskWriterLock}, acquired, run
 * and released per operation, so no lock is held between operations and
 * none across external work such as launching. CAS revisions: every
 * save carries the caller's expected revision and the file wins only if
 * its revision matches; a stale revision is a visible refusal, the file
 * untouched. A FAILED SAVE MEANS NO LAUNCH PERMISSION -- the failure is
 * visible, never silent, and the caller must not launch on this plan
 * until a save succeeds. Launching reservations go through the
 * {@link TaskRuns} receipts (T27); this store tracks plans only, never
 * task ownership. One live driver per plan: {@link #acquireClaim} takes
 * an OS lock on {@code plans/<plan-id>.claim} -- a SEPARATE lock family
 * that never touches the task mutation lock -- and a second driver is
 * refused with a concrete reason. Claim loss or restart never
 * auto-resumes: re-acquiring durably pauses a RUNNING plan (the
 * persisted {@code paused} flag) and progression resumes only through
 * an explicit Continue (a save with {@code paused=false}, composing
 * with T34a's RestartRequested refusal). {@link #close()} drains
 * bounded and silently, fails anything still parked, releases claims.
 */
public final class PlanStore implements AutoCloseable {

    private static final Logger LOG = System.getLogger(PlanStore.class.getName());

    /** One JVM claim slot per absolute claim file: a second instance of this Drydock is refused cleanly. */
    private static final ConcurrentHashMap<Path, Claim> CLAIMS = new ConcurrentHashMap<>();

    /** Bounded write queue; a full queue is a visible refusal, never a silent drop or a blocked caller. */
    private static final int QUEUE_CAPACITY = 256;

    /** One visible, checked store failure: closed writer, queue bounds, contention, CAS, torn or foreign schema. */
    public static final class PlanStoreException extends Exception {
        public PlanStoreException(String message) { super(message); }

        public PlanStoreException(String message, Throwable cause) { super(message, cause); }
    }

    /** One plan's persisted state: its revision (the CAS token), its pause flag, its content. */
    public record PlanRecord(long revision, boolean paused, RunningPlan plan) {
    }

    /** The live per-plan driver claim (P4.13): an OS lock on the plan's own {@code .claim} file,
     * held across the driver's work -- the driver identity, not a mutation lock -- until released.
     * One live claim per plan exists per JVM and per OS across processes; a lost claim makes every
     * save refuse. */
    public final class Claim implements AutoCloseable {
        private final TaskId plan;
        private final Path file;
        private final FileChannel channel;
        private final FileLock osLock;
        private boolean released;

        Claim(TaskId plan, Path file, FileChannel channel, FileLock osLock) {
            this.plan = plan; this.file = file; this.channel = channel; this.osLock = osLock;
        }

        public TaskId plan() { return plan; }

        /** The plan's own claim file, a member of the plans family -- never a task lock. */
        public Path claimFile() { return file; }

        /** False once the claim was released or its OS lock was lost: progression is paused. */
        public boolean valid() { synchronized (this) { return !released && osLock.isValid(); } }

        /** Releases the claim; never throws, idempotent. */
        @Override
        public void close() {
            synchronized (this) {
                if (released) { return; }
                released = true; CLAIMS.remove(file, this); ownedClaims.remove(this);
                try {
                    if (osLock.isValid()) { osLock.release(); }
                    channel.close();
                } catch (IOException e) {
                    LOG.log(Level.WARNING, () -> "A plan claim could not be fully released: " + e);
                }
            }
        }
    }

    private final Path root;
    private final PlanFiles files;
    private final TaskWriterLock lock;
    private final long waitMillis;
    private final ThreadPoolExecutor writer;
    private final CopyOnWriteArrayList<Claim> ownedClaims = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    public PlanStore(Path storeRoot) {
        this(storeRoot, 5_000); // as long as the task store waits
    }

    /** Bounded lock wait, package-private like TaskStore's own seam. */
    PlanStore(Path storeRoot, long lockWaitMillis) {
        this.root = Objects.requireNonNull(storeRoot, "storeRoot").toAbsolutePath().normalize();
        this.files = new PlanFiles(root);
        this.lock = new TaskWriterLock(root, lockWaitMillis);
        this.waitMillis = lockWaitMillis;
        this.writer = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY), runnable -> {
                    Thread thread = new Thread(runnable, "drydock-plan-writer");
                    thread.setDaemon(true); return thread;
                });
    }

    /** Fresh read of one plan's persisted state; read-only, no claim needed. A missing file is
     * empty; a torn, foreign-schema or oversized file is a visible failure naming the file and
     * reason -- only this plan is affected. */
    public CompletableFuture<Optional<PlanRecord>> load(TaskId plan) {
        Objects.requireNonNull(plan, "plan");
        return submit(() -> {
            PlanFiles.Loaded file = files.load(plan);
            if (file.refusal() != null) { refuse("Plan '" + plan.text() + "' cannot be read: "
                    + files.fileOf(plan) + " " + file.refusal()); }
            return file.record() == null ? Optional.empty() : Optional.of(file.record());
        });
    }

    /** Compare-and-swap save (P4.13): {@code expectedRevision} must match the file's revision (0
     * means absent, so 0 creates); a stale revision is a visible refusal, the file untouched.
     * Requires the caller's live claim for this plan and a content target equal to this plan's
     * id. A FAILED SAVE MEANS NO LAUNCH PERMISSION: the failure is visible, never silent. */
    public CompletableFuture<PlanRecord> save(Claim claim, long expectedRevision, RunningPlan next,
            boolean paused) {
        Objects.requireNonNull(claim, "claim"); Objects.requireNonNull(next, "next");
        return submit(() -> {
            TaskId plan = requireClaim(claim);
            if (!next.launch().target().equals(plan)) { refuse("Plan '" + plan.text()
                    + "' is keyed by its target task, not '" + next.launch().target().text() + "'"); }
            PlanFiles.Loaded file = files.load(plan);
            if (file.refusal() != null) { refuse("Plan '" + plan.text() + "' cannot be saved: "
                    + files.fileOf(plan) + " " + file.refusal()); }
            long actual = file.record() == null ? 0 : file.record().revision();
            if (actual != expectedRevision) { refuse("Stale revision for plan '" + plan.text()
                    + "' (0 means absent): " + files.fileOf(plan) + " is at revision " + actual
                    + ", the caller expected " + expectedRevision + "; reload and retry (P4.13)"); }
            PlanRecord saved = new PlanRecord(expectedRevision + 1, paused, next);
            files.write(plan, PlanFiles.render(saved)); return saved;
        });
    }

    /** Takes the ONE live driver claim for one plan (P4.13): an OS lock on the plan's own
     * {@code plans/<plan-id>.claim} file, separate from every task mutation lock. A second
     * driver -- this Drydock instance or another process -- is refused with a concrete reason.
     * Re-acquiring after a restart or claim loss durably pauses a RUNNING, unpaused plan: no
     * auto-resume, only an explicit Continue (a save with {@code paused=false}). If the pause
     * cannot be persisted, the claim is released and the acquisition fails visibly. */
    public CompletableFuture<Claim> acquireClaim(TaskId plan) {
        Objects.requireNonNull(plan, "plan");
        return submit(() -> {
            Path claimFile = files.claimFileOf(plan);
            Path key = claimFile.toAbsolutePath().normalize();
            if (CLAIMS.containsKey(key)) { throw conflict(plan, claimFile, "this Drydock instance"); }
            Files.createDirectories(claimFile.getParent());
            FileChannel channel = FileChannel.open(claimFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock osLock;
            try {
                osLock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                channel.close();
                throw conflict(plan, claimFile, "this Drydock (the claim file is locked)");
            }
            if (osLock == null) {
                channel.close();
                throw conflict(plan, claimFile, "another Drydock process (the claim file is OS-locked)");
            }
            Claim claim = new Claim(plan, key, channel, osLock);
            if (CLAIMS.putIfAbsent(key, claim) != null) { claim.close();
                throw conflict(plan, claimFile, "this Drydock instance"); }
            ownedClaims.add(claim);
            try {
                pauseForRestart(plan);
            } catch (IOException e) { claim.close(); refuse("The claim for plan '" + plan.text()
                    + "' was taken, but its restart pause could not be persisted: " + e); }
            return claim;
        });
    }

    /** Quarantines one plan's torn file (the driver's remedy for a torn write): the torn bytes
     * are kept in a {@code .torn} sidecar, the main file is removed, and the plan id is free
     * for a fresh create. A file with no torn tail is a visible refusal, and so is a healthy
     * file carrying a foreign schema version -- those bytes are never reinterpreted or removed. */
    public CompletableFuture<Boolean> quarantineTorn(Claim claim) {
        Objects.requireNonNull(claim, "claim");
        return submit(() -> {
            TaskId plan = requireClaim(claim);
            PlanFiles.Loaded file = files.load(plan);
            if (file.tornTail() == null) { refuse(file.refusal() == null ? "Plan '" + plan.text()
                    + "' has no torn tail to quarantine in " + files.fileOf(plan)
                    : "Plan '" + plan.text() + "' cannot be quarantined: " + files.fileOf(plan) + " "
                    + file.refusal() + "; a foreign schema is an operator decision"); }
            return files.quarantineTorn(plan, file);
        });
    }

    /** Bounded drain of the write queue; a queue that does not drain is a visible failure. */
    public void flush() throws PlanStoreException {
        if (!drain(waitMillis)) {
            throw new PlanStoreException("Timed out or was interrupted waiting for the plan writer on " + root);
        }
    }

    /** Stops accepting work, drains bounded and silently, fails anything still parked, releases
     * every claim this instance holds, and never throws; idempotent. */
    @Override
    public void close() {
        closed = true; drain(waitMillis);
        for (Runnable parked : writer.shutdownNow()) {
            if (parked instanceof Pending<?> task) { task.refuseClosed(); }
        }
        for (Claim claim : List.copyOf(ownedClaims)) {
            claim.close();
        }
    }

    // ---- internals ---------------------------------------------------------

    private static final class Refusal extends RuntimeException {
        Refusal(String message) { super(message); }
    }

    private interface Body<T> {
        T run() throws IOException;
    }

    private static void refuse(String message) { throw new Refusal(message); }

    private static Refusal conflict(TaskId plan, Path claimFile, String who) {
        return new Refusal("Plan '" + plan.text() + "' is already driven by " + who
                + " (claim " + claimFile + "); one driver per plan (P4.13)");
    }

    /** The caller's live claim for this plan, or a visible refusal: a released or lost claim
     * pauses progression at the store until re-acquired and explicitly continued. */
    private TaskId requireClaim(Claim claim) {
        if (!claim.valid() || !claim.claimFile().equals(files.claimFileOf(claim.plan()))) {
            refuse("The driver claim for plan '" + claim.plan().text() + "' is not held (released, lost"
                    + " or a different store root); progression is paused until the claim is re-acquired"
                    + " and the plan is explicitly continued (P4.13)");
        }
        return claim.plan();
    }

    /** Claim loss/restart pause (P4.13): a RUNNING, unpaused plan becomes durably paused when a
     * claim is (re-)taken, so a restarting Drydock never auto-resumes; resumption is the explicit
     * Continue save. An unreadable file is left alone: its plan is already blocked by its refusal. */
    private void pauseForRestart(TaskId plan) throws IOException {
        PlanFiles.Loaded file = files.load(plan);
        if (file.refusal() != null) {
            LOG.log(Level.WARNING, () -> "The restart pause of plan '" + plan.text() + "' was skipped: "
                    + files.fileOf(plan) + " " + file.refusal());
            return;
        }
        PlanRecord record = file.record();
        if (record != null && !record.paused() && record.plan().status() == RunningPlan.Status.RUNNING) {
            files.write(plan, PlanFiles.render(new PlanRecord(record.revision() + 1, true, record.plan())));
        }
    }

    /** Queues one operation on the single writer thread; the caller never blocks. */
    private <T> CompletableFuture<T> submit(Body<T> body) {
        Pending<T> pending = new Pending<>(body);
        if (closed) { pending.refuseClosed(); return pending.future; }
        try {
            writer.execute(pending);
        } catch (RejectedExecutionException e) {
            pending.future.completeExceptionally(new PlanStoreException("The plan writer queue on "
                    + root + " is full (" + QUEUE_CAPACITY + " pending); retry once the writer drains"));
        }
        return pending.future;
    }

    /** One queued operation: the close-window re-check, then the body under the root writer. */
    private final class Pending<T> implements Runnable {
        private final CompletableFuture<T> future = new CompletableFuture<>();
        private final Body<T> body;

        Pending(Body<T> body) { this.body = body; }

        @Override
        public void run() {
            if (closed) { refuseClosed(); return; }
            try {
                future.complete(lock.write(body::run));
            } catch (Refusal refusal) {
                future.completeExceptionally(new PlanStoreException(refusal.getMessage()));
            } catch (TaskStore.TaskStoreException e) { // contention, foreign lock, wrapped I/O
                future.completeExceptionally(new PlanStoreException(e.getMessage(), e));
            } catch (RuntimeException e) { // a surprise is visible, never a hanging future
                future.completeExceptionally(
                        new PlanStoreException("Plan store operation on " + root + " failed: " + e, e));
            }
        }

        void refuseClosed() {
            future.completeExceptionally(
                    new PlanStoreException("The plan store writer on " + root + " is closed"));
        }
    }

    /** Bounded drain: a sentinel behind every queued operation, awaited within the bound. */
    private boolean drain(long millis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() - deadline < 0) {
            CompletableFuture<Void> done = new CompletableFuture<>();
            try {
                writer.execute(() -> done.complete(null)); // ordering sentinel, no closed re-check
            } catch (RejectedExecutionException e) { continue; /* queue full: keep waiting */ }
            try {
                done.get(deadline - System.nanoTime(), TimeUnit.NANOSECONDS); return true;
            } catch (TimeoutException | ExecutionException e) {
                return false;
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return false;
    }
}
