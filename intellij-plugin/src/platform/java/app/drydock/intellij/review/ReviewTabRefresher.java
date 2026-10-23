package app.drydock.intellij.review;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.ReviewScope;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The Review tab's refresh policy (AC21, AC15's scheduling half): what
 * re-runs the diff, what only recomputes marks, and what never spawns git
 * at all.
 *
 * <p>Two paths come in:</p>
 * <ul>
 *   <li>{@link #marksChanged()} — the store and registry listeners. Those
 *   events cannot be filtered ({@code AnnotationStore.putVerdict} fires
 *   with a {@code null} key, so the listener cannot tell scopes or keys
 *   apart), so they coalesce to <em>one</em> recompute of
 *   {@link ReviewTabModel#snapshot} from the <em>cached</em> diff and the
 *   <em>cached</em> base commit, both taken at the last reload. They
 *   never run git: an agent writing 50 findings is 50 events, one
 *   recompute, zero spawns (AC21) — the base commit's {@code git
 *   rev-parse} belongs to the reload path, not to this one.</li>
 *   <li>{@link #reload(ReviewScope)} — file changes, the refresh action,
 *   the Changes link. A full diff re-run, debounced ({@value
 *   #RELOAD_DEBOUNCE_MS} ms); at most one diff in flight at a time, and a
 *   request arriving during a run schedules exactly one follow-up — the
 *   same mechanism AC15's "one re-run" rides on.</li>
 * </ul>
 *
 * <p>Assembled {@link ReviewTabModel.Snapshot}s go to a consumer and
 * status transitions to a status callback; both fire on whatever thread the
 * work ran on, and the provider (T13) hops them to the EDT. The first
 * user-triggered load reports {@link ReviewTabStatus#LOADING} synchronously,
 * before any background work is scheduled (the click visibly does
 * something before the result arrives, and LOADING is the placeholder
 * every terminal state replaces — nothing stays stranded).</p>
 *
 * <p>Threading: the constructor's seams are the only execution the class
 * touches. {@code background} runs the coalesced recompute; the diff
 * supplier only <em>initiates</em> a run and returns a future (production
 * {@code DiffService.diff} submits to its own executor), so the class never
 * blocks holding its lock. All mutable state is guarded by an internal
 * lock; listeners may be called from any thread (agent writes arrive on
 * MCP virtual threads).</p>
 */
public final class ReviewTabRefresher {

    private static final Logger LOG = System.getLogger(ReviewTabRefresher.class.getName());

    /** The debounce window a reload waits out, so a burst of file events is one diff. */
    public static final long RELOAD_DEBOUNCE_MS = 300;

    /** The window store/registry events coalesce over, so a burst of writes is one recompute. */
    public static final long MARKS_COALESCE_MS = 300;

    /**
     * Schedules a task after {@code delayMs}. Production supplies the
     * plugin's scheduler; tests a fake whose timers fire deterministically
     * (never real time — scheduler tests sleep nowhere).
     */
    public interface Scheduler {
        Cancelable schedule(Runnable task, long delayMs);
    }

    /** Cancels a task {@link Scheduler} scheduled, if it has not run yet. */
    public interface Cancelable {
        void cancel();
    }

    /**
     * Joins the store's current findings and verdicts onto a diff and the
     * base commit both cached at the last reload. Must spawn no git: it is
     * the marks path's only reach into state beyond this class's cache.
     */
    @FunctionalInterface
    public interface InputsJoin {
        ReviewTabModel.Inputs inputs(ReviewScope scope, UnifiedDiff diff, Optional<String> baseCommit);
    }

    /** What one reload caches: the diff and the base commit it resolved alongside. */
    private record Loaded(UnifiedDiff diff, Optional<String> baseCommit) {
    }

    private final Executor background;
    private final Scheduler scheduler;
    private final Function<ReviewScope, CompletableFuture<UnifiedDiff>> diff;
    private final Function<ReviewScope, Optional<String>> baseCommit;
    private final InputsJoin inputsFor;
    private final Function<ReviewTabModel.Inputs, ReviewTabModel.Snapshot> assembler;
    private final Consumer<ReviewTabModel.Snapshot> snapshots;
    private final BiConsumer<ReviewTabStatus, String> status;

    private final AtomicBoolean disposed = new AtomicBoolean();
    /** The listener-unregister runnables the provider hands over; run (isolated) at dispose. */
    private final List<Runnable> unregisters = new CopyOnWriteArrayList<>();

    /** Guards every mutable field below. */
    private final Object lock = new Object();
    private ReviewScope scope;
    private UnifiedDiff cachedDiff;
    /** The base commit resolved at the last reload; the marks path reads only this, never git. */
    private Optional<String> cachedBaseCommit = Optional.empty();
    private CompletableFuture<?> inFlight;
    /** True between deciding to start a run and the future being registered, so no second timer can double-start. */
    private boolean starting;
    private boolean followUpPending;
    private Cancelable marksTimer;
    private Cancelable reloadTimer;

    /**
     * @param background runs the coalesced marks recompute (the plugin
     * executor in production; a direct executor in tests)
     * @param scheduler the debounce/coalesce timer seam
     * @param diff initiates a scope's diff and returns its future (the
     * backend's {@code DiffService.diff}; must not block the caller)
     * @param baseCommit resolves the scope's base to a commit id (blocking
     * git in production); called on {@code background}, once per reload
     * after its diff completes — never from the marks path
     * @param inputsFor joins the store's current findings and verdicts onto
     * a cached diff and base commit — the only way this class reaches
     * {@link ReviewTabModel.Inputs}' store parts; must spawn no git
     * @param assembler {@code ReviewTabModel::snapshot} in production
     * (tests wrap it to count recomputes)
     * @param snapshots receives every assembled snapshot
     * @param status receives each state with its rendered text; LOADING is
     * reported synchronously from {@link #reload(ReviewScope)} before any
     * background work starts
     */
    public ReviewTabRefresher(Executor background,
                              Scheduler scheduler,
                              Function<ReviewScope, CompletableFuture<UnifiedDiff>> diff,
                              Function<ReviewScope, Optional<String>> baseCommit,
                              InputsJoin inputsFor,
                              Function<ReviewTabModel.Inputs, ReviewTabModel.Snapshot> assembler,
                              Consumer<ReviewTabModel.Snapshot> snapshots,
                              BiConsumer<ReviewTabStatus, String> status) {
        this.background = Objects.requireNonNull(background, "background");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.diff = Objects.requireNonNull(diff, "diff");
        this.baseCommit = Objects.requireNonNull(baseCommit, "baseCommit");
        this.inputsFor = Objects.requireNonNull(inputsFor, "inputsFor");
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.status = Objects.requireNonNull(status, "status");
    }

    /**
     * Hands the refresher a listener-unregister runnable (what
     * {@code AnnotationStore.addChangeListener} /
     * {@code ReviewScopeRegistry.addChangeListener} returned) to run at
     * {@link #dispose()}. Registering after dispose runs it at once, so a
     * listener registered too late still cannot leak.
     */
    public ReviewTabRefresher onDispose(Runnable unregister) {
        Objects.requireNonNull(unregister, "unregister");
        if (disposed.get()) {
            unregister.run();
            return this;
        }
        unregisters.add(unregister);
        return this;
    }

    /**
     * The store/registry listener path: coalesces to one snapshot recompute
     * from the cached diff and spawns no git. With nothing cached yet
     * (before the first load) there is nothing to recompute, so the event
     * is dropped — the load that follows reads fresh state anyway.
     */
    public void marksChanged() {
        if (disposed.get()) {
            return;
        }
        synchronized (lock) {
            if (marksTimer != null) {
                return; // one coalesced recompute is already pending
            }
            if (scope == null || cachedDiff == null) {
                return;
            }
            marksTimer = scheduler.schedule(this::recomputeMarks, MARKS_COALESCE_MS);
        }
    }

    /**
     * A full diff re-run for {@code target}: file changes, the refresh
     * action, the Changes link. Debounced — a burst collapses into the one
     * pending run — and never more than one diff in flight: a request that
     * arrives while a run is going (or just starting) schedules exactly one
     * follow-up run, which itself waits out the debounce.
     *
     * <p>Reports {@link ReviewTabStatus#LOADING} synchronously, before any
     * background work is scheduled, so the click visibly does something
     * before the result arrives.</p>
     */
    public void reload(ReviewScope target) {
        Objects.requireNonNull(target, "target");
        if (disposed.get()) {
            return;
        }
        status.accept(ReviewTabStatus.LOADING, ReviewTabStatus.LOADING.message());
        synchronized (lock) {
            if (!target.equals(scope)) {
                scope = target;
                cachedDiff = null; // another scope's diff is not this one's
                cachedBaseCommit = Optional.empty();
            }
            if (reloadTimer != null) {
                return; // one pending run already covers this burst
            }
            if (inFlight != null || starting) {
                followUpPending = true; // exactly one follow-up, not one per event
                return;
            }
            reloadTimer = scheduler.schedule(this::startReload, RELOAD_DEBOUNCE_MS);
        }
    }

    /**
     * Stops everything: cancels pending timers, runs the unregister
     * runnables (each isolated, so one failure cannot skip the rest), and
     * makes later events — and the completion of a run still in flight —
     * do nothing.
     */
    public void dispose() {
        if (!disposed.compareAndSet(false, true)) {
            return;
        }
        Cancelable marks;
        Cancelable reload;
        synchronized (lock) {
            marks = marksTimer;
            reload = reloadTimer;
            marksTimer = null;
            reloadTimer = null;
            followUpPending = false;
        }
        if (marks != null) {
            marks.cancel();
        }
        if (reload != null) {
            reload.cancel();
        }
        for (Runnable unregister : unregisters) {
            try {
                unregister.run();
            } catch (RuntimeException | Error e) {
                LOG.log(Level.WARNING, "Review tab listener unregister failed", e);
            }
        }
    }

    // ---- the two paths ------------------------------------------------------

    /** The coalesced marks recompute: one snapshot from the cached diff and base commit, zero git. */
    private void recomputeMarks() {
        synchronized (lock) {
            marksTimer = null;
        }
        if (disposed.get()) {
            return;
        }
        background.execute(() -> emitFromCache());
    }

    private void emitFromCache() {
        ReviewScope current;
        UnifiedDiff cached;
        Optional<String> base;
        synchronized (lock) {
            if (disposed.get()) {
                return;
            }
            current = scope;
            cached = cachedDiff;
            base = cachedBaseCommit;
        }
        if (current == null || cached == null) {
            return; // dropped mid-flight; the pending load reads fresh state
        }
        snapshots.accept(assembler.apply(inputsFor.inputs(current, cached, base)));
    }

    /** The debounce's end: initiates the one diff run, or defers to a follow-up if one is already going. */
    private void startReload() {
        ReviewScope target;
        synchronized (lock) {
            reloadTimer = null;
            if (disposed.get() || scope == null) {
                return;
            }
            if (inFlight != null || starting) {
                followUpPending = true;
                return;
            }
            starting = true;
            target = scope;
        }
        CompletableFuture<Loaded> future;
        try {
            // The base commit is resolved on the reload path (here, on the
            // background executor once the diff is in — not on the diff
            // service's own executor), so the marks path can reuse it.
            future = diff.apply(target).thenApplyAsync(
                    result -> new Loaded(result, baseCommit.apply(target)), background);
        } catch (RuntimeException | Error e) {
            synchronized (lock) {
                starting = false;
            }
            runDone(target, null, e);
            return;
        }
        synchronized (lock) {
            starting = false;
            if (disposed.get()) {
                return;
            }
            inFlight = future;
        }
        future.whenComplete((result, error) -> runDone(target, result, error));
    }

    /** A run's end: cache, emit, map the outcome to a state, and fire the one follow-up if one was asked for. */
    private void runDone(ReviewScope target, Loaded result, Throwable error) {
        boolean scheduleFollowUp;
        synchronized (lock) {
            inFlight = null;
            scheduleFollowUp = followUpPending && !disposed.get();
            followUpPending = false;
        }
        if (disposed.get()) {
            return;
        }
        ReviewScope current;
        synchronized (lock) {
            current = scope;
        }
        if (current == null || !current.equals(target)) {
            // A different scope was requested while this one ran: its result
            // is not this tab's, and the request that changed the scope
            // already left a pending timer or follow-up behind.
            if (scheduleFollowUp) {
                scheduleFollowUp();
            }
            return;
        }
        if (error != null) {
            // The previous cached diff stays: marks can still be recomputed
            // from it while the failure's message is shown. The diff service
            // only throws (it logs nothing), so the failure — whose message
            // carries git's stderr excerpt — is logged here (AC22).
            String failed = ReviewTabStatus.DIFF_FAILED.message(error);
            LOG.log(Level.WARNING, "Review tab: " + failed + " (scope " + target.id()
                    + " in " + target.diffRoot() + ")", error);
            status.accept(ReviewTabStatus.DIFF_FAILED, failed);
        } else {
            synchronized (lock) {
                cachedDiff = result.diff();
                cachedBaseCommit = result.baseCommit();
            }
            ReviewTabStatus state = result.diff().files().isEmpty()
                    ? ReviewTabStatus.EMPTY_DIFF : ReviewTabStatus.READY;
            status.accept(state, state.message());
            snapshots.accept(assembler.apply(inputsFor.inputs(target, result.diff(), result.baseCommit())));
        }
        if (scheduleFollowUp) {
            scheduleFollowUp();
        }
    }

    /** Schedules one follow-up run through the same debounce (AC15's "exactly one re-run"). */
    private void scheduleFollowUp() {
        synchronized (lock) {
            if (disposed.get() || reloadTimer != null) {
                return;
            }
            reloadTimer = scheduler.schedule(this::startReload, RELOAD_DEBOUNCE_MS);
        }
    }
}
