package app.cpm.app;

import app.cpm.domain.ApplicationState;
import app.cpm.state.ApplicationStateRepository;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * FX-thread callers never block on disk I/O. Per the repository guideline
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
     * Blocks until every save queued before this call has been written
     * (the writer is a FIFO single thread, so an empty barrier task
     * suffices). For shutdown and tests; never call on the FX thread.
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
}
