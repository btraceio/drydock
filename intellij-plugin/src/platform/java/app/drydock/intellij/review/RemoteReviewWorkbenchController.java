package app.drydock.intellij.review;

import app.drydock.domain.ManagedSessionId;
import app.drydock.review.ReviewDataSnapshot;
import app.drydock.review.ReviewDataSource;
import app.drydock.review.ReviewDataSource.Result;
import app.drydock.review.ReviewScope;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Drives the remote-session workbench over the headless {@link ReviewDataSource}
 * (INV-57: no local Path/Git branch here). Every load/select/refresh/poll
 * completion is generation-fenced (INV-60): any selection, session, scope
 * or invalidation bumps the generation, so a late completion is
 * dropped and can never repaint another session or authorize approval.
 *
 * <p>A failed refresh keeps the last snapshot of the same scope but marks it
 * {@link Freshness#STALE}; approval and requests are enabled only for a
 * {@link Freshness#FRESH} snapshot (INV-59). Listeners are called on the completing thread.</p>
 */
public final class RemoteReviewWorkbenchController {

    public enum Freshness { IDLE, LOADING, FRESH, STALE, UNAVAILABLE }

    /**
     * What the UI renders. {@code label} names the remote target and freshness;
     * {@code session}/{@code generation} let a view discard a state for another session.
     */
    public record State(Optional<ManagedSessionId> session, Optional<ReviewScope> scope,
                        Optional<ReviewDataSnapshot> snapshot, Freshness freshness, String label,
                        long generation) {
        public boolean approvalEnabled() {
            return freshness == Freshness.FRESH && snapshot.isPresent();
        }

        public boolean requestsEnabled() {
            return approvalEnabled();
        }
    }

    private final ReviewDataSource source;
    private final ReviewTabRefresher.Scheduler scheduler;
    private final Consumer<State> listener;
    private final Object lock = new Object();

    /** Poll period while the workbench is visible. */
    public static final long POLL_MS = 5_000;

    private long generation;
    /** Generation of the load currently in flight (-1: none); a poll tick skips while it is current. */
    private long loadingGen = -1;
    private boolean visible;
    private boolean disposed;
    private long pollEpoch;
    private ReviewTabRefresher.Cancelable timer;
    private State state = new State(Optional.empty(), Optional.empty(), Optional.empty(),
            Freshness.IDLE, "No remote session", 0);

    public RemoteReviewWorkbenchController(ReviewDataSource source, ReviewTabRefresher.Scheduler scheduler,
                                           Consumer<State> listener) {
        this.source = Objects.requireNonNull(source, "source");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    public State state() {
        synchronized (lock) {
            return state;
        }
    }

    /** Selects {@code selection} of {@code session}: drops the previous session's data, resolves, then loads. */
    public void select(ManagedSessionId session, String selection) {
        Objects.requireNonNull(session, "session");
        long gen;
        State loading;
        synchronized (lock) {
            if (disposed) {
                return;
            }
            gen = ++generation;
            loadingGen = gen;
            loading = state = new State(Optional.of(session), Optional.empty(), Optional.empty(),
                    Freshness.LOADING, "Remote: loading " + session, gen);
        }
        listener.accept(loading);
        source.resolve(session, selection).whenComplete((r, t) -> {
            if (isStale(gen)) {
                return; // superseded: never start a refresh for a dropped selection
            }
            if (r instanceof Result.Ok<ReviewScope>(ReviewScope scope)) {
                load(gen, session, scope, Optional.empty());
            } else {
                finish(gen, session, Optional.empty(), Optional.empty(), failure(r, t), null);
            }
        });
    }

    /** Re-reads the current scope; a failure keeps the previous snapshot as STALE. */
    public void refresh() {
        long gen;
        ManagedSessionId session;
        ReviewScope scope;
        Optional<ReviewDataSnapshot> kept;
        synchronized (lock) {
            if (disposed || state.scope().isEmpty() || state.session().isEmpty()) {
                return;
            }
            gen = ++generation;
            loadingGen = gen;
            session = state.session().get();
            scope = state.scope().get();
            kept = state.snapshot();
        }
        load(gen, session, scope, kept);
    }

    /** Transport/checkout/content change: fences in-flight work and marks retained data stale. */
    public void invalidate(String reason) {
        State next;
        synchronized (lock) {
            if (disposed) {
                return;
            }
            generation++;
            Freshness f = state.snapshot().isPresent() ? Freshness.STALE : Freshness.UNAVAILABLE;
            next = state = new State(state.session(), state.scope(), state.snapshot(), f,
                    label(state.session(), f.name(), reason), generation);
        }
        listener.accept(next);
    }

    /**
     * Starts or stops the {@link #POLL_MS} poll. The poll runs only while visible; hiding
     * cancels the timer (an already-fired stale tick is fenced by the epoch).
     */
    public void setVisible(boolean nowVisible) {
        synchronized (lock) {
            if (disposed || visible == nowVisible) {
                return;
            }
            visible = nowVisible;
            if (nowVisible) {
                scheduleLocked();
            } else {
                cancelTimerLocked();
            }
        }
    }

    /** Fences everything in flight, cancels the poll timer and silences the listener for good. */
    public void dispose() {
        synchronized (lock) {
            disposed = true;
            visible = false;
            generation++;
            cancelTimerLocked();
        }
    }

    private void scheduleLocked() {
        if (disposed || !visible || timer != null) {
            return;
        }
        long epoch = pollEpoch;
        timer = scheduler.schedule(() -> poll(epoch), POLL_MS);
    }

    private void cancelTimerLocked() {
        pollEpoch++;
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    /** One tick: refresh unless a load is already in flight (coalesced), then re-arm. */
    private void poll(long epoch) {
        boolean go;
        synchronized (lock) {
            if (epoch != pollEpoch || disposed || !visible) {
                return;
            }
            timer = null;
            go = state.scope().isPresent() && loadingGen != generation;
        }
        if (go) {
            refresh();
        }
        synchronized (lock) {
            if (epoch == pollEpoch) {
                scheduleLocked();
            }
        }
    }

    private void load(long gen, ManagedSessionId session, ReviewScope scope, Optional<ReviewDataSnapshot> kept) {
        CompletableFuture<Result<ReviewDataSnapshot>> refreshed;
        try {
            refreshed = source.refresh(scope, () -> isStale(gen));
        } catch (RuntimeException e) {
            finish(gen, session, Optional.of(scope), kept, "refresh failed: " + e.getMessage(), null);
            return;
        }
        refreshed.whenComplete((r, t) -> {
            if (r instanceof Result.Ok<ReviewDataSnapshot>(ReviewDataSnapshot snap)) {
                finish(gen, session, Optional.of(scope), Optional.of(snap), null, snap);
            } else {
                finish(gen, session, Optional.of(scope), kept, failure(r, t), null);
            }
        });
    }

    /** Publishes a completion only if its generation is still current. */
    private void finish(long gen, ManagedSessionId session, Optional<ReviewScope> scope,
                        Optional<ReviewDataSnapshot> snapshot, String failure, ReviewDataSnapshot fresh) {
        State next;
        synchronized (lock) {
            if (gen != generation) {
                return;
            }
            loadingGen = -1;
            Freshness f = fresh != null ? Freshness.FRESH
                    : snapshot.isPresent() ? Freshness.STALE : Freshness.UNAVAILABLE;
            String text = label(Optional.of(session), f == Freshness.FRESH ? "fresh" : f.name(), failure);
            next = state = new State(Optional.of(session), scope, snapshot, f, text, gen);
        }
        listener.accept(next);
    }

    private boolean isStale(long gen) {
        synchronized (lock) {
            return gen != generation;
        }
    }

    private static String label(Optional<ManagedSessionId> session, String freshness, String detail) {
        return "Remote " + session.map(ManagedSessionId::toString).orElse("?") + " — " + freshness
                + (detail == null ? "" : " (" + detail + ")");
    }

    private static String failure(Result<?> r, Throwable t) {
        if (t != null) {
            return "failed: " + t.getMessage();
        }
        return switch (r) {
            case Result.Unavailable<?> u -> "unavailable: " + u.reason();
            case Result.Stale<?> s -> "stale: " + s.reason();
            case Result.Refused<?> f -> "refused: " + f.reason();
            case Result.Ok<?> ok -> "ok";
        };
    }
}
