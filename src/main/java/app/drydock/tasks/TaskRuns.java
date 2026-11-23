package app.drydock.tasks;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The durable run receipt registry (T27a, D10/D13/P4) over the
 * {@link TaskRunFiles} file family under the store root's reserved
 * {@code runs/} directory. Reservations serialize through the SAME
 * writer-lock discipline as task mutations: this class composes the store
 * root's {@link TaskWriterLock} -- same lock file, same bounded queue --
 * without widening TaskStore's API, so a reservation can never interleave
 * with a task mutation in this JVM or another Drydock process.
 *
 * <p>Conservative recovery: a torn tail record blocks ONLY the affected
 * task -- its starts and by-task lookups refuse until a release
 * ({@link #forget}/{@link #fail}) quarantines the record, after which the
 * intact rows govern again. Lookups by session or worktree refuse only
 * when they would return empty while unreadable receipts exist. A row
 * whose owner catalog no longer exists is read-only history: it never
 * blocks other tasks, and only an explicit stale sweep (a later task)
 * can release it.
 */
public final class TaskRuns implements AutoCloseable {

    /** One visible, checked registry failure: contention, duplicate reservation, stale callback, torn record. */
    public static class TaskRunsException extends Exception {
        public TaskRunsException(String message) {
            super(message);
        }

        public TaskRunsException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final Path root;
    private final TaskRunFiles files;
    private final TaskWriterLock lock;
    private volatile boolean closed;

    public TaskRuns(Path storeRoot) {
        this(storeRoot, 5_000); // as long as TaskStore waits
    }

    /** Bounded lock wait, package-private like TaskStore's own seam. */
    TaskRuns(Path storeRoot, long lockWaitMillis) {
        this.root = Objects.requireNonNull(storeRoot, "storeRoot").toAbsolutePath().normalize();
        this.files = new TaskRunFiles(root);
        this.lock = new TaskWriterLock(root, lockWaitMillis);
    }

    /**
     * Reserves one task for launching work (a PREPARE row). A live
     * reservation -- latest row PREPARE or LAUNCH, P4 -- is a visible
     * refusal, never a silent overwrite; a torn tail blocks the start
     * until a release quarantines it; terminal history never blocks a
     * fresh run.
     */
    public TaskRun reserve(TaskId task, String sessionId, Path worktree, Path ownerCatalog,
            List<Path> consumers) throws TaskRunsException {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(worktree, "worktree");
        Objects.requireNonNull(ownerCatalog, "ownerCatalog");
        Objects.requireNonNull(consumers, "consumers");
        return write(() -> {
            TaskRunFiles.Loaded file = files.load(task);
            if (file.tornTail() != null) {
                refuse("Task '" + task.text() + "' cannot start: its receipt file " + files.fileOf(task)
                        + " has a torn record; release it (fail or forget) to quarantine the record first");
            }
            TaskRun holder = TaskRunFiles.latest(file);
            if (holder != null && holder.live()) {
                refuse("Task '" + task.text() + "' is already reserved (" + holder.phase()
                        + (holder.session().isEmpty() ? "" : " by session " + holder.session())
                        + ", worktree " + holder.worktree() + "); no two live plans may reserve one task");
            }
            return files.append(task, file, new TaskRun(task, TaskRun.Phase.PREPARE, Instant.now(),
                    sessionId, worktree, ownerCatalog, consumers, ""));
        });
    }

    /**
     * Records the LAUNCH completion, binding the spawn-persisted session
     * id. A completion without a live PREPARE reservation (never
     * reserved, already launched, released, foreign-owner) is a visible
     * no-op refusal, never a crash and never a write.
     */
    public TaskRun launch(TaskId task, String sessionId) throws TaskRunsException {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(sessionId, "sessionId");
        return write(() -> {
            TaskRunFiles.Loaded file = files.quarantineTorn(task, files.load(task));
            TaskRun latest = TaskRunFiles.latest(file);
            if (latest == null) {
                refuse("Launch completion for task '" + task.text() + "' is a no-op: no receipt exists");
            }
            readOnlyHistory(task, latest);
            if (latest.phase() != TaskRun.Phase.PREPARE) {
                refuse("Launch completion for task '" + task.text() + "' is a no-op: latest phase is "
                        + latest.phase() + ", not a live PREPARE");
            }
            return files.append(task, file, new TaskRun(task, TaskRun.Phase.LAUNCH, Instant.now(),
                    sessionId, latest.worktree(), latest.ownerCatalog(), latest.consumers(), latest.note()));
        });
    }

    /** Records a FAILURE, releasing the reservation; a report without a live reservation is a visible no-op. */
    public TaskRun fail(TaskId task, String reason) throws TaskRunsException {
        Objects.requireNonNull(task, "task");
        return write(() -> {
            TaskRunFiles.Loaded file = files.quarantineTorn(task, files.load(task));
            TaskRun latest = TaskRunFiles.latest(file);
            if (latest == null || !latest.live()) {
                refuse("Failure report for task '" + task.text() + "' is a no-op: no live reservation"
                        + (latest == null ? " exists" : " (latest phase " + latest.phase() + ")"));
            }
            readOnlyHistory(task, latest);
            return files.append(task, file, new TaskRun(task, TaskRun.Phase.FAILURE, Instant.now(),
                    latest.session(), latest.worktree(), latest.ownerCatalog(), latest.consumers(),
                    reason == null ? "" : reason));
        });
    }

    /**
     * Records a FORGET, releasing the reservation but keeping the history
     * rows. Releasing an already-released or absent task is an idempotent
     * no-op; this release is also the path that quarantines a torn tail.
     */
    public Optional<TaskRun> forget(TaskId task) throws TaskRunsException {
        Objects.requireNonNull(task, "task");
        return write(() -> {
            TaskRunFiles.Loaded file = files.quarantineTorn(task, files.load(task));
            TaskRun latest = TaskRunFiles.latest(file);
            if (latest == null) {
                return Optional.empty();
            }
            readOnlyHistory(task, latest);
            if (!latest.live()) {
                return Optional.of(latest);
            }
            return Optional.of(files.append(task, file, new TaskRun(task, TaskRun.Phase.FORGET, Instant.now(),
                    latest.session(), latest.worktree(), latest.ownerCatalog(), latest.consumers(), "")));
        });
    }

    /** The latest row of one task; refuses while its file has a torn tail. */
    public Optional<TaskRun> byTask(TaskId task) throws TaskRunsException {
        Objects.requireNonNull(task, "task");
        try {
            TaskRunFiles.Loaded file = files.load(task);
            if (file.tornTail() != null) {
                throw new TaskRunsException("Task '" + task.text() + "' has a torn receipt record in "
                        + files.fileOf(task) + "; its lookups refuse until the record is released and quarantined");
            }
            TaskRun latest = TaskRunFiles.latest(file);
            return latest == null ? Optional.empty() : Optional.of(latest);
        } catch (IOException e) {
            throw new TaskRunsException("Cannot read " + files.fileOf(task) + ": " + e, e);
        }
    }

    /** Every retained row bound to a session id -- provenance survives catalog deletion. */
    public List<TaskRun> bySession(String sessionId) throws TaskRunsException {
        Objects.requireNonNull(sessionId, "sessionId");
        return find(row -> sessionId.equals(row.session()), "session '" + sessionId + "'");
    }

    /** Every retained row for one worktree -- provenance survives catalog deletion. */
    public List<TaskRun> byWorktree(Path worktree) throws TaskRunsException {
        Path normalized = Objects.requireNonNull(worktree, "worktree").toAbsolutePath().normalize();
        return find(row -> normalized.equals(row.worktree()), "worktree " + normalized);
    }

    /** Bounded drain of the write queue; a queue that does not drain is a visible failure. */
    public void flush() throws TaskRunsException {
        if (!lock.drain()) {
            throw new TaskRunsException(
                    "Timed out or was interrupted waiting for the run receipt writer on " + root);
        }
    }

    /** Stops accepting work, drains bounded and silently, never throws; idempotent. */
    @Override
    public void close() {
        closed = true;
        lock.close();
    }

    // ---- internals ---------------------------------------------------------

    private static final class Refusal extends RuntimeException {
        Refusal(String message) {
            super(message);
        }
    }

    private interface Body<T> {
        T run() throws IOException;
    }

    private static void refuse(String message) {
        throw new Refusal(message);
    }

    private <T> T write(Body<T> body) throws TaskRunsException {
        try {
            return lock.write(() -> {
                if (closed) {
                    refuse("The run receipt registry on " + root + " is closed");
                }
                return body.run();
            });
        } catch (Refusal refusal) {
            throw new TaskRunsException(refusal.getMessage());
        } catch (TaskStore.TaskStoreException e) { // contention, foreign lock, wrapped I/O
            throw new TaskRunsException(e.getMessage(), e);
        }
    }

    /** A row whose owner catalog is gone is read-only history: its transitions refuse visibly. */
    private static void readOnlyHistory(TaskId task, TaskRun latest) {
        if (!Files.exists(latest.ownerCatalog())) {
            refuse("Task '" + task.text() + "' has a read-only history row: its owner catalog "
                    + latest.ownerCatalog() + " no longer exists");
        }
    }

    /** Scans task files for matching rows; empty-over-unreadable is a refusal, never proof of absence. */
    private List<TaskRun> find(Predicate<TaskRun> match, String what) throws TaskRunsException {
        List<TaskRun> found = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(files.runsDir())) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                TaskId task;
                try {
                    if (!name.endsWith(".run")) {
                        continue; // .torn sidecars are kept-aside quarantine, never receipts
                    }
                    task = TaskId.of(name.substring(0, name.length() - ".run".length()));
                } catch (IllegalArgumentException e) {
                    unreadable.add(name);
                    continue;
                }
                try {
                    TaskRunFiles.Loaded loaded = files.load(task);
                    if (loaded.tornTail() != null) {
                        unreadable.add(name);
                        continue;
                    }
                    loaded.rows().forEach(row -> {
                        if (match.test(row)) {
                            found.add(row);
                        }
                    });
                } catch (IOException e) {
                    unreadable.add(name);
                }
            }
        } catch (IOException | DirectoryIteratorException e) {
            if (Files.isDirectory(files.runsDir())) {
                throw new TaskRunsException("Cannot list " + files.runsDir() + ": " + e, e);
            }
            return List.of(); // no registry yet: nothing was ever reserved
        }
        if (found.isEmpty() && !unreadable.isEmpty()) {
            throw new TaskRunsException("No intact receipt matches " + what + ", but " + unreadable.size()
                    + " receipt file(s) cannot be read (" + String.join(", ", unreadable)
                    + "); this lookup refuses instead of guessing");
        }
        return List.copyOf(found);
    }
}
