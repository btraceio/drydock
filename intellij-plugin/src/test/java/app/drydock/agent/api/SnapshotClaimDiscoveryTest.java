package app.drydock.agent.api;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotClaimDiscoveryTest {

    /** Controllable CandidateSource: snapshot is empty; newCandidateIds returns the configured list. */
    static final class FakeSource implements CandidateSource {
        volatile List<String> candidates = List.of();
        volatile List<SessionRecord> records = List.of();

        @Override
        public Set<String> snapshotIds(Path cwd) {
            return Set.of();
        }

        @Override
        public List<String> newCandidateIds(Path cwd, Instant at, Set<String> snap) {
            return candidates;
        }

        @Override
        public List<SessionRecord> sessionsWithTimestamps(Path cwd) {
            return records;
        }
    }

    private static final Path CWD = Path.of("/repo");

    @Test
    void discoversSingleNewId() {
        FakeSource s = new FakeSource();
        s.candidates = List.of("id-1");
        Optional<String> id = new SnapshotClaimDiscovery(s, 1, 0)
                .discover(CWD, Instant.EPOCH, Set.of(), ConcurrentHashMap.newKeySet());
        assertEquals(Optional.of("id-1"), id);
    }

    @Test
    void emptyWhenNothingNew() {
        assertTrue(new SnapshotClaimDiscovery(new FakeSource(), 1, 0)
                .discover(CWD, Instant.EPOCH, Set.of(), ConcurrentHashMap.newKeySet()).isEmpty());
    }

    @Test
    void ambiguousTwoCandidatesBailWithoutClaiming() {
        FakeSource s = new FakeSource();
        s.candidates = List.of("a", "b");
        Set<String> claimed = ConcurrentHashMap.newKeySet();
        assertTrue(new SnapshotClaimDiscovery(s, 1, 0).discover(CWD, Instant.EPOCH, Set.of(), claimed).isEmpty());
        assertTrue(claimed.isEmpty());
    }

    @Test
    void sequentialClaimsAreDistinct() {
        FakeSource s = new FakeSource();
        s.candidates = List.of("only");
        Set<String> claimed = ConcurrentHashMap.newKeySet();
        SnapshotClaimDiscovery d = new SnapshotClaimDiscovery(s, 1, 0);
        assertEquals(Optional.of("only"), d.discover(CWD, Instant.EPOCH, Set.of(), claimed));
        assertTrue(d.discover(CWD, Instant.EPOCH, Set.of(), claimed).isEmpty());
    }

    @Test
    void racingDiscoveriesOnSharedClaimedSetProduceExactlyOneWinner() throws Exception {
        // Real concurrency (not the sequential test above): two threads call discover(...) on the
        // SAME claimed set for the SAME single candidate, released together via a barrier so they
        // overlap. This exercises the claimedIds.add(id) == false branch -- the loser must fall
        // through and re-poll rather than returning the id or bailing -- which a sequential test
        // can never reach because the first call always completes before the second starts.
        FakeSource s = new FakeSource();
        s.candidates = List.of("ddd00000-0000-0000-0000-000000000000");
        Set<String> claimed = ConcurrentHashMap.newKeySet();

        // Small attempts / no sleep so the losing thread (which finds nothing unclaimed on its
        // remaining polls) terminates quickly and deterministically.
        SnapshotClaimDiscovery a = new SnapshotClaimDiscovery(s, 3, 0);
        SnapshotClaimDiscovery b = new SnapshotClaimDiscovery(s, 3, 0);
        CyclicBarrier barrier = new CyclicBarrier(2);

        CompletableFuture<Optional<String>> f1 = CompletableFuture.supplyAsync(() -> {
            awaitUninterruptibly(barrier);
            return a.discover(CWD, Instant.EPOCH, Set.of(), claimed);
        });
        CompletableFuture<Optional<String>> f2 = CompletableFuture.supplyAsync(() -> {
            awaitUninterruptibly(barrier);
            return b.discover(CWD, Instant.EPOCH, Set.of(), claimed);
        });

        Optional<String> r1 = f1.join();
        Optional<String> r2 = f2.join();

        long winners = Stream.of(r1, r2).filter(Optional::isPresent).count();
        assertEquals(1, winners, "exactly one racer should claim the sole candidate");
        assertEquals(Set.of("ddd00000-0000-0000-0000-000000000000"), claimed);
        String won = r1.isPresent() ? r1.get() : r2.get();
        assertEquals("ddd00000-0000-0000-0000-000000000000", won);
    }

    // ---- Late-binding resolve ----

    @Test
    void resolvePicksClosestUnclaimedToCreatedAt() {
        FakeSource s = new FakeSource();
        Instant created = Instant.parse("2026-08-28T10:00:00Z");
        // Old sessions (well before createdAt) + one session 3s after createdAt.
        s.records = List.of(
                new CandidateSource.SessionRecord("old-1", created.minusSeconds(600)),
                new CandidateSource.SessionRecord("old-2", created.minusSeconds(300)),
                new CandidateSource.SessionRecord("target", created.plusSeconds(3)));
        Optional<String> id = new SnapshotClaimDiscovery(s, 1, 0)
                .resolve(CWD, created, Set.of());
        assertEquals(Optional.of("target"), id);
    }

    @Test
    void resolveSkipsClaimedIds() {
        FakeSource s = new FakeSource();
        Instant created = Instant.parse("2026-08-28T10:00:00Z");
        s.records = List.of(
                new CandidateSource.SessionRecord("claimed", created.plusSeconds(2)),
                new CandidateSource.SessionRecord("free", created.plusSeconds(5)));
        Optional<String> id = new SnapshotClaimDiscovery(s, 1, 0)
                .resolve(CWD, created, Set.of("claimed"));
        assertEquals(Optional.of("free"), id);
    }

    @Test
    void resolveEmptyWhenAllClaimed() {
        FakeSource s = new FakeSource();
        Instant created = Instant.parse("2026-08-28T10:00:00Z");
        s.records = List.of(
                new CandidateSource.SessionRecord("a", created.plusSeconds(2)),
                new CandidateSource.SessionRecord("b", created.plusSeconds(5)));
        assertTrue(new SnapshotClaimDiscovery(s, 1, 0)
                .resolve(CWD, created, Set.of("a", "b")).isEmpty());
    }

    @Test
    void resolveEmptyWhenNothingNearCreatedAt() {
        FakeSource s = new FakeSource();
        Instant created = Instant.parse("2026-08-28T10:00:00Z");
        // All sessions are old (before createdAt minus the 5s skew allowance).
        s.records = List.of(
                new CandidateSource.SessionRecord("old-1", created.minusSeconds(600)));
        assertTrue(new SnapshotClaimDiscovery(s, 1, 0)
                .resolve(CWD, created, Set.of()).isEmpty());
    }

    @Test
    void resolveAmbiguousWhenTwoUnclaimedNearCreatedAt() {
        FakeSource s = new FakeSource();
        Instant created = Instant.parse("2026-08-28T10:00:00Z");
        // Two unclaimed sessions, both within the tolerance and within the
        // ambiguity gap of each other (3s apart).
        s.records = List.of(
                new CandidateSource.SessionRecord("a", created.plusSeconds(2)),
                new CandidateSource.SessionRecord("b", created.plusSeconds(5)));
        assertTrue(new SnapshotClaimDiscovery(s, 1, 0)
                .resolve(CWD, created, Set.of()).isEmpty());
    }

    @Test
    void resolvePicksCloserWhenRunnerUpIsFar() {
        FakeSource s = new FakeSource();
        Instant created = Instant.parse("2026-08-28T10:00:00Z");
        // Two unclaimed sessions within the 120s tolerance, but 20s apart —
        // the closer one wins (the gap exceeds the 10s ambiguity threshold).
        s.records = List.of(
                new CandidateSource.SessionRecord("near", created.plusSeconds(3)),
                new CandidateSource.SessionRecord("far", created.plusSeconds(23)));
        Optional<String> id = new SnapshotClaimDiscovery(s, 1, 0)
                .resolve(CWD, created, Set.of());
        assertEquals(Optional.of("near"), id);
    }

    @Test
    void resolveAllowsSmallBackwardSkew() {
        FakeSource s = new FakeSource();
        Instant created = Instant.parse("2026-08-28T10:00:00Z");
        // The agent record's clock lagged 2s behind the Java clock.
        s.records = List.of(
                new CandidateSource.SessionRecord("target", created.minusSeconds(2)));
        Optional<String> id = new SnapshotClaimDiscovery(s, 1, 0)
                .resolve(CWD, created, Set.of());
        assertEquals(Optional.of("target"), id);
    }

    private static void awaitUninterruptibly(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
