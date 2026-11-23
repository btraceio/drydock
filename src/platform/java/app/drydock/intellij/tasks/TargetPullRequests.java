package app.drydock.intellij.tasks;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PullRequestTarget;
import app.drydock.git.GhCliService.BranchPullRequest;
import app.drydock.git.GhCliService.PrInfo.PrLifecycle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** Nonblocking, repository-scoped target lookups; one cached request per distinct target per minute. */
final class TargetPullRequests {
    private static final long TTL = TimeUnit.MINUTES.toNanos(1);
    private final Function<PullRequestTarget, CompletableFuture<Optional<BranchPullRequest>>> lookup;
    private final LongSupplier clock;
    private final Runnable changed;
    private final Map<PullRequestTarget, Cached> cache;

    record Cached(long expires, CompletableFuture<Optional<BranchPullRequest>> request,
                  BranchPullRequest previous) {
        BranchPullRequest current() {
            return request.isDone() && !request.isCompletedExceptionally()
                    ? request.join().orElse(previous) : previous;
        }
    }

    TargetPullRequests(Function<PullRequestTarget, CompletableFuture<Optional<BranchPullRequest>>> lookup,
                       LongSupplier clock, Runnable changed) {
        this(lookup, clock, changed, new HashMap<>());
    }

    TargetPullRequests(Function<PullRequestTarget, CompletableFuture<Optional<BranchPullRequest>>> lookup,
                       LongSupplier clock, Runnable changed, Map<PullRequestTarget, Cached> cache) {
        this.lookup = lookup;
        this.clock = clock;
        this.changed = changed;
        this.cache = cache;
    }

    Map<ManagedSessionId, BranchPullRequest> resolve(List<ManagedAgentSession> sessions,
                                                     Map<ManagedSessionId, BranchPullRequest> branches) {
        Map<ManagedSessionId, BranchPullRequest> result = new HashMap<>();
        List<CompletableFuture<Optional<BranchPullRequest>>> pendingCallbacks = new ArrayList<>();
        Set<PullRequestTarget> active = new HashSet<>();
        synchronized (this) {
            for (ManagedAgentSession session : sessions) {
                if (session.targetPullRequest().isEmpty()) {
                    Optional.ofNullable(branches.get(session.id())).ifPresent(pr -> result.put(session.id(), pr));
                    continue;
                }
                PullRequestTarget target = session.targetPullRequest().get();
                active.add(target);
                Cached cached = cache.get(target);
                if (cached == null || cached.expires() <= clock.getAsLong()) {
                    // A target is an identity even while GitHub is unavailable.
                    // Never fall back to the working branch's unrelated PR here.
                    BranchPullRequest previous = cached == null
                            ? new BranchPullRequest(target.number(), PrLifecycle.UNKNOWN, false, "", "",
                                    Optional.of(target.url())) : cached.current();
                    CompletableFuture<Optional<BranchPullRequest>> request = lookup.apply(target);
                    cached = new Cached(clock.getAsLong() + TTL, request, previous);
                    cache.put(target, cached);
                    if (!request.isDone()) {
                        pendingCallbacks.add(request);
                    }
                }
                result.put(session.id(), cached.current());
            }
            cache.keySet().retainAll(active);
        }
        pendingCallbacks.forEach(request -> request.whenComplete((answer, failure) -> changed.run()));
        return Map.copyOf(result);
    }

    synchronized void clear() {
        cache.clear();
    }
}
