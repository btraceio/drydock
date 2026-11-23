package app.drydock.intellij.review;

import app.drydock.domain.ManagedSessionId;
import app.drydock.git.DiffScope;
import app.drydock.git.ReviewBase;
import app.drydock.git.UnifiedDiff;
import app.drydock.intellij.review.RemoteReviewWorkbenchController.Freshness;
import app.drydock.intellij.review.RemoteReviewWorkbenchController.State;
import app.drydock.review.ReviewDataSnapshot;
import app.drydock.review.ReviewDataSource;
import app.drydock.review.ReviewDataSource.Result;
import app.drydock.review.ReviewScope;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Generation fencing and staleness against a hand-completed data source. */
class RemoteReviewWorkbenchControllerTest {

    private static final ManagedSessionId A = ManagedSessionId.newId();
    private static final ManagedSessionId B = ManagedSessionId.newId();

    static final class FakeSource implements ReviewDataSource {
        final List<CompletableFuture<Result<ReviewScope>>> resolves = new ArrayList<>();
        final List<CompletableFuture<Result<ReviewDataSnapshot>>> refreshes = new ArrayList<>();
        final List<BooleanSupplier> cancels = new ArrayList<>();

        public Backend backend() { return Backend.REMOTE; }

        public CompletableFuture<Result<ReviewScope>> resolve(ManagedSessionId s, String sel) {
            var f = new CompletableFuture<Result<ReviewScope>>();
            resolves.add(f);
            return f;
        }

        public CompletableFuture<Result<ReviewDataSnapshot>> refresh(ReviewScope scope, BooleanSupplier c) {
            var f = new CompletableFuture<Result<ReviewDataSnapshot>>();
            refreshes.add(f);
            cancels.add(c);
            return f;
        }

        public CompletableFuture<Result<ReviewDataSnapshot.Content>> contents(
                ReviewDataSnapshot s, String file, Side side) { throw new AssertionError(); }

        public CompletableFuture<Result<Excerpt>> excerpt(ManagedSessionId s, String f, int l, int c) {
            throw new AssertionError();
        }

        public CompletableFuture<Result<ReviewDataSnapshot>> validateCurrent(ReviewDataSnapshot s) {
            throw new AssertionError();
        }
    }

    private final FakeSource source = new FakeSource();
    private final List<State> seen = new ArrayList<>();
    private final List<Runnable> timers = new ArrayList<>();
    private final List<Boolean> cancelled = new ArrayList<>();
    private final RemoteReviewWorkbenchController c = new RemoteReviewWorkbenchController(source,
            (task, delayMs) -> {
                assertEquals(RemoteReviewWorkbenchController.POLL_MS, delayMs);
                int i = timers.size();
                timers.add(task);
                cancelled.add(false);
                return () -> cancelled.set(i, true);
            }, seen::add);

    private void fire() {
        timers.get(timers.size() - 1).run();
    }

    private static ReviewScope scope(String id) {
        return new ReviewScope(id, ReviewScope.Kind.BRANCH, Path.of("/r"), Optional.empty(), "main", "HEAD",
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static ReviewDataSnapshot snap(String id) {
        return new ReviewDataSnapshot(id, ReviewDataSource.Backend.REMOTE, "box", 1, DiffScope.BASE,
                new ReviewBase("main", ReviewBase.Origin.PULL_REQUEST), "b", "h", "b", "fp",
                new UnifiedDiff(List.of()), Map.of(), Instant.EPOCH);
    }

    private void load(ManagedSessionId s, String id) {
        c.select(s, "sel");
        source.resolves.get(source.resolves.size() - 1).complete(new Result.Ok<>(scope(id)));
        source.refreshes.get(source.refreshes.size() - 1).complete(new Result.Ok<>(snap(id)));
    }

    @Test
    void loadsFreshAndLabelsRemoteTarget() {
        load(A, "a");
        State s = c.state();
        assertEquals(Freshness.FRESH, s.freshness());
        assertTrue(s.approvalEnabled() && s.requestsEnabled());
        assertTrue(s.label().contains("Remote " + A) && s.label().contains("fresh"));
        assertEquals(Freshness.LOADING, seen.get(0).freshness());
    }

    @Test
    void lateCompletionOfAPreviousSessionNeverRepaintsAnother() {
        c.select(A, "x");
        var staleResolve = source.resolves.get(0);
        load(B, "b");
        staleResolve.complete(new Result.Ok<>(scope("a")));
        assertEquals(1, source.refreshes.size(), "stale resolve must not start a load for A");
        assertEquals(B, c.state().session().orElseThrow());
        assertEquals("b", c.state().snapshot().orElseThrow().scopeId());
    }

    @Test
    void outOfOrderRefreshKeepsTheNewestAndCancelsTheOlder() {
        load(A, "a");
        c.refresh();
        c.refresh();
        assertTrue(source.cancels.get(1).getAsBoolean(), "superseded refresh is cancelled");
        assertFalse(source.cancels.get(2).getAsBoolean());
        source.refreshes.get(2).complete(new Result.Ok<>(snap("newest")));
        source.refreshes.get(1).complete(new Result.Ok<>(snap("older")));
        assertEquals("newest", c.state().snapshot().orElseThrow().scopeId());
    }

    @Test
    void failedRefreshRetainsSnapshotAsStaleAndDisablesApproval() {
        load(A, "a");
        c.refresh();
        source.refreshes.get(1).complete(new Result.Unavailable<>("connection lost"));
        State s = c.state();
        assertEquals(Freshness.STALE, s.freshness());
        assertTrue(s.snapshot().isPresent());
        assertFalse(s.approvalEnabled());
        assertFalse(s.requestsEnabled());
        assertTrue(s.label().contains("STALE") && s.label().contains("connection lost"));
    }

    @Test
    void reconnectInvalidationFencesInFlightRefreshAndMarksStale() {
        load(A, "a");
        c.refresh();
        c.invalidate("reconnected");
        assertEquals(Freshness.STALE, c.state().freshness());
        assertTrue(source.cancels.get(1).getAsBoolean());
        int before = seen.size();
        source.refreshes.get(1).complete(new Result.Ok<>(snap("late")));
        assertEquals(before, seen.size());
        assertFalse(c.state().approvalEnabled());
    }

    @Test
    void invalidateWithoutSnapshotLabelsUnavailable() {
        c.invalidate("reconnected");
        assertEquals(Freshness.UNAVAILABLE, c.state().freshness());
        assertTrue(c.state().label().contains("UNAVAILABLE"));
    }

    @Test
    void pollRunsOnlyWhileVisibleAndHidingCancelsTheTimer() {
        load(A, "a");
        assertEquals(0, timers.size(), "not visible: no timer");
        c.setVisible(true);
        c.setVisible(true);
        assertEquals(1, timers.size());
        fire();
        assertEquals(2, source.refreshes.size());
        c.setVisible(false);
        assertTrue(cancelled.get(timers.size() - 1));
        int n = timers.size();
        timers.get(n - 1).run();
        assertEquals(2, source.refreshes.size(), "stale tick after hide is fenced");
        assertEquals(n, timers.size());
    }

    @Test
    void pollCoalescesWhileARefreshIsInFlight() {
        load(A, "a");
        c.setVisible(true);
        fire();
        fire();
        fire();
        assertEquals(2, source.refreshes.size(), "in-flight refresh absorbs later ticks");
        source.refreshes.get(1).complete(new Result.Ok<>(snap("p")));
        fire();
        assertEquals(3, source.refreshes.size());
    }

    @Test
    void disposeFencesInFlightWorkStopsPollAndSilencesListener() {
        load(A, "a");
        c.setVisible(true);
        fire();
        int before = seen.size();
        c.dispose();
        assertTrue(cancelled.get(timers.size() - 1));
        assertTrue(source.cancels.get(1).getAsBoolean());
        source.refreshes.get(1).complete(new Result.Ok<>(snap("late")));
        c.select(B, "x");
        c.refresh();
        c.invalidate("x");
        c.setVisible(true);
        assertEquals(before, seen.size());
        assertEquals(1, source.resolves.size());
        assertEquals(2, source.refreshes.size());
        assertEquals(timers.size(), cancelled.size());
    }

    @Test
    void pollCompletionAfterSwitchingSessionNeverRepaintsTheOldOne() {
        load(A, "a");
        c.setVisible(true);
        fire();
        load(B, "b");
        source.refreshes.get(1).complete(new Result.Ok<>(snap("a2")));
        assertEquals(B, c.state().session().orElseThrow());
        assertEquals("b", c.state().snapshot().orElseThrow().scopeId());
        assertTrue(seen.stream().noneMatch(s -> s.snapshot().map(x -> x.scopeId()).orElse("").equals("a2")));
    }

    @Test
    void cancelledReconnectKeepsPollingAndNextTickRecoversFresh() {
        load(A, "a");
        c.setVisible(true);
        fire();
        c.invalidate("cancelled");
        assertTrue(source.cancels.get(1).getAsBoolean());
        assertFalse(c.state().approvalEnabled());
        fire();
        assertEquals(3, source.refreshes.size(), "invalidate frees the in-flight slot");
        source.refreshes.get(2).complete(new Result.Ok<>(snap("again")));
        assertEquals(Freshness.FRESH, c.state().freshness());
    }
}
