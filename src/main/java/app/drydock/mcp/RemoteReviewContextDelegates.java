package app.drydock.mcp;

import app.drydock.domain.ManagedSessionId;
import app.drydock.git.UnifiedDiff;
import app.drydock.review.ReviewDataSnapshot;
import app.drydock.review.ReviewDataSource.Result;
import app.drydock.review.ReviewDataSources;
import app.drydock.review.ReviewDiffs;
import app.drydock.review.ReviewScope;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The remote half of {@link WorkspaceMcpSessionContext}: what a remote-serving
 * context asks instead of reading a host path or running host git. Every read
 * goes through the typed {@link ReviewDataSources} dispatcher and never
 * falls back to a local source; every store write commits through the current
 * request's {@link RemoteAuthorizationPermit} (INV-35, INV-52, INV-57).
 *
 * <p>Liveness is evidence, not a status flag: {@link Liveness} reports proven
 * tmux/protocol evidence, probed at most every {@link #REFRESH_NANOS}. A session
 * counts as running only while ALIVE evidence is at most {@link #FAIL_CLOSED_NANOS}
 * old, and confirmed death fails closed immediately. A detached but enrolled
 * agent is ALIVE, so it keeps its review tools.</p>
 */
public final class RemoteReviewContextDelegates {

    public static final long REFRESH_NANOS = TimeUnit.SECONDS.toNanos(10);
    public static final long FAIL_CLOSED_NANOS = TimeUnit.SECONDS.toNanos(15);
    private static final long WAIT_SECONDS = 20;

    public enum Evidence { ALIVE, DEAD, UNKNOWN }

    /** Probes the remote agent's tmux/protocol evidence; may block (never called under a permit guard). */
    @FunctionalInterface
    public interface Liveness {
        Evidence probe(ManagedSessionId session);
    }

    private static final class State {
        Long probedAt;
        Long aliveAt;
        boolean dead;
    }

    private final ReviewDataSources sources;
    private final Liveness liveness;
    private final Predicate<ManagedSessionId> authorized;
    private final LongSupplier nanos;
    private final Map<ManagedSessionId, State> states = new HashMap<>();

    /**
     * @param authorized live enrolled catalog session with a current credential generation
     * @param nanos      monotonic clock; {@link System#nanoTime} in production
     */
    public RemoteReviewContextDelegates(ReviewDataSources sources, Liveness liveness,
                                        Predicate<ManagedSessionId> authorized, LongSupplier nanos) {
        this.sources = Objects.requireNonNull(sources, "sources");
        this.liveness = Objects.requireNonNull(liveness, "liveness");
        this.authorized = Objects.requireNonNull(authorized, "authorized");
        this.nanos = Objects.requireNonNull(nanos, "nanos");
    }

    public boolean sessionRunning(ManagedSessionId caller) {
        if (!authorized.test(caller)) {
            return false;
        }
        State state;
        synchronized (states) {
            state = states.computeIfAbsent(caller, id -> new State());
        }
        synchronized (state) {
            long now = nanos.getAsLong();
            if (state.probedAt == null || now - state.probedAt >= REFRESH_NANOS) {
                Evidence evidence;
                try {
                    evidence = liveness.probe(caller);
                } catch (RuntimeException e) {
                    evidence = Evidence.UNKNOWN;
                }
                state.probedAt = now;
                if (evidence == Evidence.ALIVE) {
                    state.aliveAt = now;
                    state.dead = false;
                } else if (evidence == Evidence.DEAD) {
                    state.dead = true;
                }
            }
            return !state.dead && state.aliveAt != null && now - state.aliveAt < FAIL_CLOSED_NANOS;
        }
    }

    public UnifiedDiff diff(ReviewScope scope) throws McpToolException {
        return ReviewDiffs.withoutIdeNoise(snapshot(scope).diff());
    }

    public Optional<String> baseCommit(ReviewScope scope) {
        return quietSnapshot(scope).map(ReviewDataSnapshot::baseCommit);
    }

    public Optional<String> baseBranch(Optional<ReviewScope> scope) {
        return scope.flatMap(this::quietSnapshot).map(snapshot -> snapshot.base().ref());
    }

    public Optional<String> excerpt(ManagedSessionId caller, String file, int line, int context) {
        try {
            if (file == null || file.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(await(sources.excerpt(caller, file, line, context)))
                    .map(found -> String.join("\n", found.lines()));
        } catch (McpToolException e) {
            return Optional.empty();
        }
    }

    /**
     * Runs one store write inside the current request's permit. Data reads and flushes
     * stay outside. No bound permit means no authority: fails closed.
     */
    public <T> T commit(Supplier<T> write) {
        return RemoteAuthorizationPermit.Bound.current()
                .orElseThrow(RemoteAuthorizationPermit.StaleAuthorizationException::new)
                .commitIfCurrent(write);
    }

    private Optional<ReviewDataSnapshot> quietSnapshot(ReviewScope scope) {
        try {
            return Optional.of(snapshot(scope));
        } catch (McpToolException e) {
            return Optional.empty();
        }
    }

    private ReviewDataSnapshot snapshot(ReviewScope scope) throws McpToolException {
        return await(sources.refresh(scope, () -> false));
    }

    /** One bounded wait; every non-Ok shape is a refusal without data, never a local retry. */
    private static <T> T await(CompletableFuture<Result<T>> future) throws McpToolException {
        Result<T> result;
        try {
            result = future.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpToolException("Interrupted while reading remote review data.");
        } catch (TimeoutException | ExecutionException e) {
            throw new McpToolException("Remote review data is unavailable.");
        }
        return switch (result) {
            case Result.Ok<T> ok -> ok.value();
            case Result.Unavailable<T> unavailable ->
                    throw new McpToolException("Remote review data is unavailable: " + unavailable.reason());
            case Result.Stale<T> stale ->
                    throw new McpToolException("Remote review data is stale: " + stale.reason());
            case Result.Refused<T> refused -> throw new McpToolException("Remote review data is unavailable.");
        };
    }
}
