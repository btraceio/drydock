package app.drydock.mcp;

import app.drydock.domain.ManagedSessionId;
import app.drydock.git.ReviewBase;
import app.drydock.git.UnifiedDiff;
import app.drydock.mcp.RemoteReviewContextDelegates.Evidence;
import app.drydock.review.ReviewDataSnapshot;
import app.drydock.review.ReviewDataSource;
import app.drydock.review.ReviewDataSource.Backend;
import app.drydock.review.ReviewDataSource.Result;
import app.drydock.review.ReviewDataSources;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Liveness freshness, the permit commit guard and the typed (never local) data reads. */
class RemoteReviewContextDelegatesTest {

    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);

    private final ManagedSessionId session = ManagedSessionId.newId();
    private final AtomicLong clock = new AtomicLong(1_000 * SECOND);
    private final AtomicInteger probes = new AtomicInteger();
    private Evidence evidence = Evidence.ALIVE;
    private boolean authorized = true;
    private Backend verified = Backend.REMOTE;
    private final List<String> reads = new ArrayList<>();
    private Result<ReviewDataSnapshot> refreshResult;
    private final ReviewScope scope = new ReviewScopeRegistry().mint(ReviewScopeRegistry.spec(
            ReviewScope.Kind.WORKTREE, Path.of("/no/such/host/repo"), Optional.empty(), "main", "feat",
            Optional.empty(), Optional.of(session)));

    private final ReviewDataSource remoteSource = new ReviewDataSource() {
        public Backend backend() {
            return Backend.REMOTE;
        }

        public CompletableFuture<Result<ReviewScope>> resolve(ManagedSessionId s, String selection) {
            return CompletableFuture.completedFuture(new Result.Refused<>("unused"));
        }

        public CompletableFuture<Result<ReviewDataSnapshot>> refresh(ReviewScope s, BooleanSupplier cancelled) {
            reads.add("refresh");
            return CompletableFuture.completedFuture(refreshResult);
        }

        public CompletableFuture<Result<ReviewDataSnapshot.Content>> contents(
                ReviewDataSnapshot snapshot, String file, Side side) {
            return CompletableFuture.completedFuture(new Result.Unavailable<>("unused"));
        }

        public CompletableFuture<Result<Excerpt>> excerpt(ManagedSessionId s, String file, int line, int context) {
            reads.add("excerpt");
            return CompletableFuture.completedFuture(new Result.Ok<>(new Excerpt(file, line, List.of("a", "b"))));
        }

        public CompletableFuture<Result<ReviewDataSnapshot>> validateCurrent(ReviewDataSnapshot snapshot) {
            return CompletableFuture.completedFuture(new Result.Ok<>(snapshot));
        }
    };

    private RemoteReviewContextDelegates delegates() {
        ReviewDataSources.Verifier verifier = new ReviewDataSources.Verifier() {
            public Optional<Backend> backendOfSession(ManagedSessionId id) {
                return Optional.of(verified);
            }

            public Optional<Backend> backendOfScope(ReviewScope s) {
                return Optional.of(verified);
            }

            public Optional<Backend> backendOfScopeId(String id) {
                return Optional.of(verified);
            }
        };
        return new RemoteReviewContextDelegates(new ReviewDataSources(verifier, Map.of(Backend.REMOTE, remoteSource)),
                id -> {
                    probes.incrementAndGet();
                    return evidence;
                }, id -> authorized, clock::get);
    }

    private ReviewDataSnapshot snapshot() {
        return new ReviewDataSnapshot(scope.id(), Backend.REMOTE, "box:checkout", 1, scope.diffScope(),
                new ReviewBase("develop", ReviewBase.Origin.FORKED_FROM), "c".repeat(40), "d".repeat(40),
                "c".repeat(40), "fp", new UnifiedDiff(List.of()), Map.of(), Instant.EPOCH);
    }

    private static final class Fence implements RemoteAuthorizationPermit.Fence {
        private final Object lock = new Object();
        boolean revoked;

        public Object lock() {
            return lock;
        }

        public boolean revoked() {
            return revoked;
        }
    }

    @Test
    void livenessProbesAtMostEveryTenSecondsAndFailsClosedFifteenSecondsAfterTheLastProof() {
        RemoteReviewContextDelegates delegates = delegates();

        assertTrue(delegates.sessionRunning(session));
        clock.addAndGet(9 * SECOND);
        assertTrue(delegates.sessionRunning(session));
        assertEquals(1, probes.get(), "no second probe inside the 10 s refresh window");

        evidence = Evidence.UNKNOWN;
        clock.addAndGet(2 * SECOND);
        assertTrue(delegates.sessionRunning(session), "11 s old proof still stands while evidence is unverifiable");
        assertEquals(2, probes.get());
        clock.addAndGet(5 * SECOND);
        assertFalse(delegates.sessionRunning(session), "16 s without proof fails closed");
    }

    @Test
    void confirmedDeathAndMissingAuthorizationFailClosedImmediately() {
        RemoteReviewContextDelegates delegates = delegates();
        assertTrue(delegates.sessionRunning(session));

        evidence = Evidence.DEAD;
        clock.addAndGet(10 * SECOND);
        assertFalse(delegates.sessionRunning(session));

        evidence = Evidence.ALIVE;
        authorized = false;
        clock.addAndGet(10 * SECOND);
        assertFalse(delegates().sessionRunning(session), "an unauthorized session is never running");
    }

    @Test
    void aCommitRunsOnceInsideTheBoundCurrentPermitAndNeverWithoutOne() {
        RemoteReviewContextDelegates delegates = delegates();
        Fence fence = new Fence();
        RemoteAuthorizationPermit permit = new RemoteAuthorizationPermit(session, 3, fence);
        AtomicInteger writes = new AtomicInteger();

        assertThrows(RemoteAuthorizationPermit.StaleAuthorizationException.class,
                () -> delegates.commit(writes::incrementAndGet));
        try (RemoteAuthorizationPermit.Bound ignored = RemoteAuthorizationPermit.Bound.bind(permit)) {
            assertEquals(1, delegates.commit(writes::incrementAndGet));
            fence.revoked = true;
            assertThrows(RemoteAuthorizationPermit.StaleAuthorizationException.class,
                    () -> delegates.commit(writes::incrementAndGet));
        }
        assertEquals(1, writes.get());
    }

    @Test
    void dataIsReadThroughTheTypedSourceAndAnUnverifiedBackendNeverFallsToALocalPath() throws Exception {
        refreshResult = new Result.Ok<>(snapshot());
        RemoteReviewContextDelegates delegates = delegates();

        assertEquals(Optional.of("c".repeat(40)), delegates.baseCommit(scope));
        assertEquals(Optional.of("develop"), delegates.baseBranch(Optional.of(scope)));
        assertEquals(Optional.of("a\nb"), delegates.excerpt(session, "src/A.java", 2, 1));
        assertEquals(0, delegates.diff(scope).files().size());

        reads.clear();
        verified = Backend.LOCAL;
        assertEquals(Optional.empty(), delegates.baseCommit(scope));
        assertEquals(Optional.empty(), delegates.excerpt(session, "src/A.java", 2, 1));
        assertThrows(McpToolException.class, () -> delegates.diff(scope));
        assertEquals(List.of(), reads, "a scope not verified remote reaches no source at all");
    }

    @Test
    void aStaleOrUnavailableSnapshotIsARefusalNotEmptyData() {
        RemoteReviewContextDelegates delegates = delegates();
        refreshResult = new Result.Stale<>("checkout moved");
        assertTrue(assertThrows(McpToolException.class, () -> delegates.diff(scope)).getMessage()
                .contains("stale"));
        refreshResult = new Result.Unavailable<>("connection lost");
        assertEquals(Optional.empty(), delegates.baseCommit(scope));
    }
}
