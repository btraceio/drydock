package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PullRequestTarget;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionWorkspace;
import app.drydock.git.GhCliService.BranchPullRequest;
import app.drydock.git.GhCliService.PrInfo.PrLifecycle;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TargetPullRequestsTest {
    private static final PullRequestTarget TARGET = new PullRequestTarget("github.com/DataDog/java-profiler", 898);

    private static ManagedAgentSession session(Optional<PullRequestTarget> target) {
        return new ManagedAgentSession.Builder(ManagedSessionId.newId(), RepositoryId.newId(), "arbitrary title",
                AgentBinding.unlaunched(AgentKind.PI),
                new SessionWorkspace(Path.of("/missing/worktree"), Optional.empty(), false), Instant.EPOCH)
                .targetPullRequest(target).build();
    }

    private static BranchPullRequest pr(PullRequestTarget target, PrLifecycle state) {
        return new BranchPullRequest(target.number(), state, false, "unrelated-head", "PR", Optional.of(target.url()));
    }

    @Test
    void aTargetMasksTheWrongBranchWhilePendingFailedAndAfterResolution() {
        var request = new CompletableFuture<Optional<BranchPullRequest>>();
        AtomicInteger callbacks = new AtomicInteger();
        var cache = new TargetPullRequests(target -> request, System::nanoTime, callbacks::incrementAndGet);
        var about898 = session(Optional.of(TARGET));
        var about943 = session(Optional.of(new PullRequestTarget(TARGET.repository(), 943)));
        var wrongBranch = pr(new PullRequestTarget(TARGET.repository(), 943), PrLifecycle.OPEN);
        var branches = Map.of(about898.id(), wrongBranch);
        var pending = cache.resolve(List.of(about898, about943), branches);
        assertEquals(898, pending.get(about898.id()).number());
        assertEquals(PrLifecycle.UNKNOWN, pending.get(about898.id()).state());
        assertEquals(943, pending.get(about943.id()).number());
        request.completeExceptionally(new IllegalStateException("offline"));
        assertEquals(2, callbacks.get());
        assertEquals(pending, cache.resolve(List.of(about898, about943), branches));
    }

    @Test
    void aTargetIsLookedUpByRepositoryAndCachedAcrossSessionsAndTtlRefreshes() {
        Map<PullRequestTarget, CompletableFuture<Optional<BranchPullRequest>>> requests = new HashMap<>();
        AtomicInteger calls = new AtomicInteger();
        AtomicLong clock = new AtomicLong();
        var cache = new TargetPullRequests(target -> {
            calls.incrementAndGet();
            var future = new CompletableFuture<Optional<BranchPullRequest>>();
            requests.put(target, future);
            return future;
        }, clock::get, () -> {});
        var first = session(Optional.of(TARGET));
        var second = session(Optional.of(TARGET));
        var otherRepo = new PullRequestTarget("github.com/other/repo", 898);
        var third = session(Optional.of(otherRepo));
        var sessions = List.of(first, second, third);
        cache.resolve(sessions, Map.of());
        assertEquals(2, calls.get());
        requests.get(TARGET).complete(Optional.of(pr(TARGET, PrLifecycle.MERGED)));
        requests.get(otherRepo).complete(Optional.of(pr(otherRepo, PrLifecycle.CLOSED)));
        var resolved = cache.resolve(sessions, Map.of());
        assertEquals(PrLifecycle.MERGED, resolved.get(first.id()).state());
        assertEquals(resolved.get(first.id()), resolved.get(second.id()));
        assertEquals(PrLifecycle.CLOSED, resolved.get(third.id()).state());
        clock.set(TimeUnit.MINUTES.toNanos(2));
        assertEquals(resolved, cache.resolve(sessions, Map.of()), "refresh retains known state");
        assertEquals(4, calls.get());
        requests.get(TARGET).complete(Optional.empty());
        assertEquals(PrLifecycle.MERGED, cache.resolve(sessions, Map.of()).get(first.id()).state());
    }

    @Test
    void callbackRunsOutsideTheCacheMonitorWhenRegistrationCompletesInline() {
        var request = new FirstIsDoneFalseFuture();
        request.complete(Optional.of(pr(TARGET, PrLifecycle.OPEN)));
        AtomicReference<TargetPullRequests> resolverReference = new AtomicReference<>();
        AtomicInteger callbacks = new AtomicInteger();
        var resolver = new TargetPullRequests(target -> request, System::nanoTime, () -> {
            assertFalse(Thread.holdsLock(resolverReference.get()));
            callbacks.incrementAndGet();
        });
        resolverReference.set(resolver);

        var session = session(Optional.of(TARGET));
        var resolved = resolver.resolve(List.of(session), Map.of());

        assertEquals(PrLifecycle.OPEN, resolved.get(session.id()).state());
        assertEquals(1, callbacks.get());
    }

    @Test
    void cacheAndLookupOperationsRemainInsideTheCacheMonitor() {
        AtomicReference<TargetPullRequests> resolverReference = new AtomicReference<>();
        var cache = new LockCheckingCache(resolverReference);
        var resolver = new TargetPullRequests(target -> {
            assertTrue(Thread.holdsLock(resolverReference.get()));
            return CompletableFuture.completedFuture(Optional.of(pr(target, PrLifecycle.OPEN)));
        }, System::nanoTime, () -> {}, cache);
        resolverReference.set(resolver);
        var session = session(Optional.of(TARGET));

        assertEquals(PrLifecycle.OPEN, resolver.resolve(List.of(session), Map.of()).get(session.id()).state());
        resolver.clear();

        assertTrue(cache.getCalls > 0);
        assertTrue(cache.putCalls > 0);
        assertTrue(cache.keySetCalls > 0);
        assertTrue(cache.retainAllCalls > 0);
        assertEquals(1, cache.clearCalls);
    }

    @Test
    void assigningReplacingAndClearingTargetsNeverLeaksAnotherAssociationsAnswer() {
        var cache = new TargetPullRequests(target -> CompletableFuture.completedFuture(Optional.of(
                pr(target, PrLifecycle.OPEN))), System::nanoTime, () -> {});
        var unbound = session(Optional.empty());
        var branch = pr(new PullRequestTarget(TARGET.repository(), 943), PrLifecycle.OPEN);
        var branches = Map.of(unbound.id(), branch);
        assertEquals(branch, cache.resolve(List.of(unbound), branches).get(unbound.id()));
        var bound = unbound.toBuilder().targetPullRequest(Optional.of(TARGET)).build();
        assertEquals(898, cache.resolve(List.of(bound), branches).get(bound.id()).number());
        var replaced = bound.toBuilder().targetPullRequest(Optional.of(
                new PullRequestTarget(TARGET.repository(), 877))).build();
        assertEquals(877, cache.resolve(List.of(replaced), branches).get(bound.id()).number());
        assertEquals(branch, cache.resolve(List.of(unbound), branches).get(bound.id()));
        assertTrue(cache.resolve(List.of(), branches).isEmpty());
    }

    private static final class FirstIsDoneFalseFuture
            extends CompletableFuture<Optional<BranchPullRequest>> {
        private final AtomicInteger isDoneCalls = new AtomicInteger();

        @Override
        public boolean isDone() {
            return isDoneCalls.incrementAndGet() > 1 && super.isDone();
        }
    }

    private static final class LockCheckingCache extends HashMap<PullRequestTarget, TargetPullRequests.Cached> {
        private final AtomicReference<TargetPullRequests> resolver;
        private int getCalls;
        private int putCalls;
        private int keySetCalls;
        private int retainAllCalls;
        private int clearCalls;

        private LockCheckingCache(AtomicReference<TargetPullRequests> resolver) {
            this.resolver = resolver;
        }

        private void assertMonitorHeld() {
            assertTrue(Thread.holdsLock(resolver.get()));
        }

        @Override
        public TargetPullRequests.Cached get(Object key) {
            assertMonitorHeld();
            getCalls++;
            return super.get(key);
        }

        @Override
        public TargetPullRequests.Cached put(PullRequestTarget key, TargetPullRequests.Cached value) {
            assertMonitorHeld();
            putCalls++;
            return super.put(key, value);
        }

        @Override
        public Set<PullRequestTarget> keySet() {
            assertMonitorHeld();
            keySetCalls++;
            Set<PullRequestTarget> keys = super.keySet();
            return new AbstractSet<>() {
                @Override
                public Iterator<PullRequestTarget> iterator() {
                    return keys.iterator();
                }

                @Override
                public int size() {
                    return keys.size();
                }

                @Override
                public boolean retainAll(Collection<?> values) {
                    assertMonitorHeld();
                    retainAllCalls++;
                    return keys.retainAll(values);
                }
            };
        }

        @Override
        public void clear() {
            assertMonitorHeld();
            clearCalls++;
            super.clear();
        }
    }
}
