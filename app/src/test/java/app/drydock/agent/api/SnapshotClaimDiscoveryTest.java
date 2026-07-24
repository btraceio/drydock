package app.drydock.agent.api;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
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

        @Override
        public Set<String> snapshotIds(Path cwd) {
            return Set.of();
        }

        @Override
        public List<String> newCandidateIds(Path cwd, Instant at, Set<String> snap) {
            return candidates;
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

    private static void awaitUninterruptibly(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
