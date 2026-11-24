package app.drydock.app;

import app.drydock.domain.ApplicationState;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.state.ApplicationStateRepository;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/**
 * The single authoritative in-process owner of the persisted {@link
 * ApplicationState}. {@link SessionManager} and {@link RepositoryManager}
 * each mutate different slices ({@code sessions} vs. {@code
 * repositories}/{@code ui}) of the SAME on-disk JSON file; before this
 * class existed, each held an independent cached snapshot, synchronized
 * only on itself, and did its own load-then-save cycle -- so two managers
 * saving concurrently could interleave load/save and silently clobber each
 * other's slice (the cross-manager lost-update bug documented on the old
 * {@code SessionManager.mergeSessionsOntoLatestDiskState}; the
 * merge-onto-latest-disk-state approach narrowed but never closed the
 * race).
 *
 * <p>All read-modify-write cycles now funnel through {@link #update}: the
 * transform runs under this store's single lock against the one
 * authoritative in-memory state, so no update can be lost. Persistence is
 * decoupled from mutation -- {@link #update} returns as soon as the
 * in-memory state is swapped, and a background single-thread writer saves
 * the LATEST state (coalescing bursts of updates into one disk write), so
 * UI-thread callers never block on disk I/O. Per the repository guideline
 * on services that write files from background threads, {@link #flush}
 * exposes the barrier shutdown and tests need to not race pending
 * writes.</p>
 *
 * <p>{@link #forRepository} hands out one shared store per {@link
 * ApplicationStateRepository} instance, so every manager constructed
 * against the same repository -- the managers' public constructors are
 * unchanged -- transparently shares the one lock. The registry entry (and
 * its idle writer thread, which times out after a second and is daemon
 * anyway) lives as long as the repository instance is registered; in
 * practice the application creates exactly one.</p>
 */
public final class ApplicationStateStore {

    private static final Logger LOG = System.getLogger(ApplicationStateStore.class.getName());

    private static final long FLUSH_TIMEOUT_SECONDS = 10;

    private static final Map<ApplicationStateRepository, ApplicationStateStore> STORES = new WeakHashMap<>();

    /** The shared store for {@code repository}, created (and its state loaded) on first use. */
    public static ApplicationStateStore forRepository(ApplicationStateRepository repository) {
        synchronized (STORES) {
            return STORES.computeIfAbsent(repository, ApplicationStateStore::new);
        }
    }

    private final ApplicationStateRepository repository;
    private final Object lock = new Object();
    private final ThreadPoolExecutor writer;
    /** True while a save task is queued but has not yet snapshotted the state it will write. */
    private final AtomicBoolean saveQueued = new AtomicBoolean();

    /** The authoritative current state; guarded by {@link #lock}. */
    private ApplicationState state;

    private ApplicationStateStore(ApplicationStateRepository repository) {
        this.repository = repository;
        this.writer = new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
                task -> {
                    Thread thread = new Thread(task, "application-state-writer");
                    thread.setDaemon(true);
                    return thread;
                });
        // Let the writer thread die when idle so a store leaked by a
        // short-lived repository (tests) costs nothing after a second.
        this.writer.allowCoreThreadTimeOut(true);
        this.state = repository.load();
    }

    /** The current authoritative state (never reloads from disk; this store is the only in-process writer). */
    public ApplicationState state() {
        synchronized (lock) {
            return state;
        }
    }

    /**
     * Atomically applies {@code transform} to the current state and
     * schedules an asynchronous save of the result. The transform runs
     * under the store's lock on the calling thread -- it must be pure and
     * quick (no I/O). If it throws, the state is left unchanged and the
     * exception propagates to the caller. If it returns a state equal to
     * the current one, nothing is written.
     *
     * @return the state after the transform (the new authoritative state)
     */
    public ApplicationState update(UnaryOperator<ApplicationState> transform) {
        ApplicationState updated;
        synchronized (lock) {
            updated = transform.apply(state);
            if (updated.equals(state)) {
                return state;
            }
            state = updated;
        }
        scheduleSave();
        return updated;
    }

    private void scheduleSave() {
        if (!saveQueued.compareAndSet(false, true)) {
            return; // an already-queued save task will pick up this update's state.
        }
        try {
            writer.execute(this::writeLatest);
        } catch (RejectedExecutionException e) {
            saveQueued.set(false);
            LOG.log(Level.WARNING, "State writer rejected a save; the latest state was not persisted", e);
        }
    }

    private void writeLatest() {
        ApplicationState toSave;
        synchronized (lock) {
            // Cleared before snapshotting, so an update that lands after
            // this point queues a fresh save rather than being lost.
            saveQueued.set(false);
            toSave = state;
        }
        try {
            repository.save(toSave);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Failed to persist application state", e);
        }
    }

    /**
     * The one-stop reservation of a checkout's marked (editor Q&A) row: an
     * atomic compare-and-insert keyed by the canonical working directory.
     * If this catalog already holds exactly one marked row for {@code
     * candidate}'s working directory, that row is returned and nothing is
     * written — a crash after a row-only reservation must leave a reusable
     * row, never a reason to mint another. Otherwise {@code candidate} is
     * inserted.
     *
     * <p>The whole comparison and insert runs inside a single {@link
     * #update}, under the store's lock: two IDE windows whose first asks
     * for the same checkout race can never both insert — one finds the
     * other's row. There is deliberately no check-then-insert across
     * separate calls, which would reintroduce the lost-update shape this
     * store exists to kill.
     *
     * <p>The key is the session's working directory, normalized here
     * without I/O ({@code toAbsolutePath().normalize()}). Resolving
     * symlinks — the part that makes two spellings of one checkout the
     * same key — is real filesystem I/O, so it belongs to the caller, off
     * the EDT, before the candidate is built. An ordinary row at the same
     * directory is never reused: purposes differ, and a Q&A row must not
     * take over a human's session.
     *
     * <p>Marked rows for one directory are unique by construction; if the
     * catalog already holds two or more (a damaged or externally edited
     * state file), the ambiguity is a recovery error — {@link
     * DuplicateMarkedSessionException} — never a guessed binding.
     *
     * @return the effective row: the pre-existing marked row for the
     *     directory, or the inserted {@code candidate}
     * @throws DuplicateMarkedSessionException when the catalog already
     *     holds more than one marked row for the directory
     */
    public ManagedAgentSession ensureMarkedSession(ManagedAgentSession candidate) {
        if (candidate.purpose() == ManagedAgentSession.SessionPurpose.ORDINARY) {
            throw new IllegalArgumentException(
                    "ensureMarkedSession requires a marked (non-ORDINARY) session: " + candidate.id());
        }
        Path key = canonicalKey(candidate.workingDirectory());
        AtomicReference<ManagedAgentSession> effective = new AtomicReference<>();
        update(state -> {
            ManagedAgentSession existing = markedSession(state, key);
            if (existing != null) {
                effective.set(existing);
                return state;   // row-only reservation already durable: reusable, never re-minted
            }
            List<ManagedAgentSession> sessions = new ArrayList<>(state.sessions());
            sessions.add(candidate);
            effective.set(candidate);
            return state.withSessions(List.copyOf(sessions));
        });
        return effective.get();
    }

    /**
     * The marked row for {@code canonicalWorkingDirectory}, or empty when
     * the checkout has none. A read-side companion to {@link
     * #ensureMarkedSession} for callers resolving the index without
     * reserving (restart recovery, Forget protection). Like it, this
     * refuses to guess when the index is inconsistent: two or more marked
     * rows for one directory raise {@link DuplicateMarkedSessionException}.
     * The caller passes a canonical (symlink-resolved) path; it is
     * normalized here without I/O.
     */
    public Optional<ManagedAgentSession> markedSession(Path canonicalWorkingDirectory) {
        return Optional.ofNullable(markedSession(state(), canonicalKey(canonicalWorkingDirectory)));
    }

    /**
     * The marked row keyed by {@code key} inside {@code state}, or null.
     * Shared by the insert path and the read path so both see the same
     * uniqueness rule. Pure: no I/O, called only under the store's lock (or
     * on an immutable snapshot).
     */
    private static ManagedAgentSession markedSession(ApplicationState state, Path key) {
        List<ManagedAgentSession> matches = new ArrayList<>();
        for (ManagedAgentSession session : state.sessions()) {
            if (session.purpose() != ManagedAgentSession.SessionPurpose.ORDINARY
                    && canonicalKey(session.workingDirectory()).equals(key)) {
                matches.add(session);
            }
        }
        if (matches.size() > 1) {
            throw new DuplicateMarkedSessionException(key, matches);
        }
        return matches.isEmpty() ? null : matches.getFirst();
    }

    /** Normalizes a directory key without I/O; symlink resolution is the caller's, off the EDT. */
    private static Path canonicalKey(Path directory) {
        return directory.toAbsolutePath().normalize();
    }

    /**
     * Recovery error, not a silent choice: the catalog holds more than one
     * marked row for one canonical checkout directory, so no row can be
     * trusted as the checkout's Q&A session. Carrying the row ids keeps the
     * error triageable (which rows to inspect or prune) without guessing a
     * binding the state file no longer supports.
     */
    public static final class DuplicateMarkedSessionException extends IllegalStateException {

        public DuplicateMarkedSessionException(Path directory, List<ManagedAgentSession> duplicates) {
            super("Multiple marked sessions for canonical directory " + directory + ": "
                    + duplicates.stream().map(s -> s.id().value().toString()).sorted()
                            .reduce((a, b) -> a + ", " + b).orElse(""));
        }
    }

    /**
     * Blocks until every queued save of every live store is written. For
     * tests: a test that ends without {@link #flush} leaves the daemon
     * writer free to write into its temp directory after the test returns,
     * racing JUnit's cleanup ("Failed to close extension context", a temp
     * directory that no longer deletes). Called after each test by the
     * {@code app.drydock.testing} extension; never on the UI thread.
     */
    public static void flushAll() {
        List<ApplicationStateStore> live;
        synchronized (STORES) {
            live = List.copyOf(STORES.values());
        }
        for (ApplicationStateStore store : live) {
            // Nothing queued and nothing running: flushing would only wake
            // the idle-timed-out writer thread to do nothing.
            if (store.saveQueued.get() || store.writer.getActiveCount() > 0
                    || !store.writer.getQueue().isEmpty()) {
                store.flush();
            }
        }
    }

    /**
     * Blocks until every save queued before this call has been written
     * (the writer is a FIFO single thread, so an empty barrier task
     * suffices). For shutdown and tests; never call on the UI thread.
     */
    public void flush() {
        try {
            writer.submit(() -> { }).get(FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | RejectedExecutionException e) {
            LOG.log(Level.WARNING, "Could not flush pending application-state saves", e);
        }
    }

    /**
     * Writes the current state on the writer thread and answers whether
     * that write succeeded: true only once the state as it stands at this
     * call (or later) is on disk. False -- logged -- when the save failed,
     * the writer refused the task, or it did not finish within {@value
     * #FLUSH_TIMEOUT_SECONDS} s. For a caller that may delete another copy
     * of what it just put into the state only once the state has it
     * durably; {@link #flush} cannot tell, since a failed save is swallowed.
     * Blocking; never on the UI thread or the EDT.
     */
    public boolean flushDurably() {
        try {
            return writer.submit(() -> {
                ApplicationState toSave;
                synchronized (lock) {
                    toSave = state;
                }
                try {
                    repository.save(toSave);
                    return true;
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "Failed to persist application state", e);
                    return false;
                }
            }).get(FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException | RejectedExecutionException e) {
            LOG.log(Level.WARNING, "Could not persist the application state durably", e);
            return false;
        }
    }
}
