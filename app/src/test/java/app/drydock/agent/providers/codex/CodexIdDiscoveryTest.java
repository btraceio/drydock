package app.drydock.agent.providers.codex;

import app.drydock.agent.providers.codex.internal.CodexRolloutStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexIdDiscoveryTest {

    private static void rollout(Path root, String id, String cwd, String iso) throws IOException {
        Path dir = root.resolve("2026/07/23");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("rollout-x-" + id + ".jsonl"),
                "{\"type\":\"session_meta\",\"payload\":{\"id\":\"" + id + "\",\"cwd\":\"" + cwd
                        + "\",\"timestamp\":\"" + iso + "\",\"source\":\"cli\"}}\n");
    }

    @Test
    void discoversTheNewRolloutNotInSnapshot(@TempDir Path root) throws IOException {
        rollout(root, "old00000-0000-0000-0000-000000000000", "/repo/a", "2026-07-23T09:00:00Z");
        CodexRolloutStore store = new CodexRolloutStore(root);
        Set<String> snap = store.idsFor(Path.of("/repo/a"));
        rollout(root, "new11111-0000-0000-0000-000000000000", "/repo/a", "2026-07-23T11:00:00Z");
        CodexIdDiscovery discovery = new CodexIdDiscovery(new CodexRolloutStore(root), 1, 0);
        Optional<String> id = discovery.discover(Path.of("/repo/a"),
                Instant.parse("2026-07-23T10:00:00Z"), snap, ConcurrentHashMap.newKeySet());
        assertTrue(id.isPresent());
        assertEquals("new11111-0000-0000-0000-000000000000", id.get());
    }

    @Test
    void emptyWhenNothingNew(@TempDir Path root) throws IOException {
        rollout(root, "old00000-0000-0000-0000-000000000000", "/repo/a", "2026-07-23T09:00:00Z");
        CodexRolloutStore store = new CodexRolloutStore(root);
        CodexIdDiscovery discovery = new CodexIdDiscovery(store, 1, 0);
        assertTrue(discovery.discover(Path.of("/repo/a"), Instant.parse("2026-07-23T10:00:00Z"),
                store.idsFor(Path.of("/repo/a")), ConcurrentHashMap.newKeySet()).isEmpty());
    }

    @Test
    void ambiguousTwoNewRolloutsBailToPickerWithoutBinding(@TempDir Path root) throws IOException {
        // Two same-cwd launches: snapshot is empty, then TWO new unclaimed rollouts appear.
        Set<String> claimed = ConcurrentHashMap.newKeySet();
        CodexRolloutStore store = new CodexRolloutStore(root);
        Set<String> snap = store.idsFor(Path.of("/repo/a"));   // empty
        rollout(root, "aaa00000-0000-0000-0000-000000000000", "/repo/a", "2026-07-23T11:00:00Z");
        rollout(root, "bbb00000-0000-0000-0000-000000000000", "/repo/a", "2026-07-23T11:01:00Z");
        CodexIdDiscovery discovery = new CodexIdDiscovery(new CodexRolloutStore(root), 1, 0);
        // Ambiguous -> empty, and NOTHING claimed (no wrong bind).
        assertTrue(discovery.discover(Path.of("/repo/a"), Instant.parse("2026-07-23T10:00:00Z"), snap, claimed).isEmpty());
        assertTrue(claimed.isEmpty());
    }

    @Test
    void concurrentSingleCandidateClaimsAreDistinct(@TempDir Path root) throws IOException {
        // One new rollout, two discoveries racing the SAME claimed set: exactly one binds it.
        Set<String> claimed = ConcurrentHashMap.newKeySet();
        CodexRolloutStore store = new CodexRolloutStore(root);
        Set<String> snap = store.idsFor(Path.of("/repo/a"));
        rollout(root, "ccc00000-0000-0000-0000-000000000000", "/repo/a", "2026-07-23T11:00:00Z");
        CodexIdDiscovery d = new CodexIdDiscovery(new CodexRolloutStore(root), 1, 0);
        Optional<String> first = d.discover(Path.of("/repo/a"), Instant.parse("2026-07-23T10:00:00Z"), snap, claimed);
        Optional<String> second = d.discover(Path.of("/repo/a"), Instant.parse("2026-07-23T10:00:00Z"), snap, claimed);
        assertTrue(first.isPresent());
        assertTrue(second.isEmpty());   // already claimed -> second finds no unclaimed candidate
    }

    @Test
    void racingDiscoveriesOnSharedClaimedSetProduceExactlyOneWinner(@TempDir Path root) throws Exception {
        // Real concurrency (not the sequential test above): two threads call discover(...) on the
        // SAME claimed set for the SAME single candidate, released together via a barrier so they
        // overlap. This exercises the claimedIds.add(id) == false branch -- the loser must fall
        // through and re-poll rather than returning the id or bailing -- which a sequential test
        // can never reach because the first call always completes before the second starts.
        Set<String> claimed = ConcurrentHashMap.newKeySet();
        CodexRolloutStore store = new CodexRolloutStore(root);
        Set<String> snap = store.idsFor(Path.of("/repo/a"));
        rollout(root, "ddd00000-0000-0000-0000-000000000000", "/repo/a", "2026-07-23T11:00:00Z");

        // Small attempts / no sleep so the losing thread (which finds nothing unclaimed on its
        // remaining polls) terminates quickly and deterministically.
        CodexIdDiscovery a = new CodexIdDiscovery(new CodexRolloutStore(root), 3, 0);
        CodexIdDiscovery b = new CodexIdDiscovery(new CodexRolloutStore(root), 3, 0);
        CyclicBarrier barrier = new CyclicBarrier(2);

        CompletableFuture<Optional<String>> f1 = CompletableFuture.supplyAsync(() -> {
            awaitUninterruptibly(barrier);
            return a.discover(Path.of("/repo/a"), Instant.parse("2026-07-23T10:00:00Z"), snap, claimed);
        });
        CompletableFuture<Optional<String>> f2 = CompletableFuture.supplyAsync(() -> {
            awaitUninterruptibly(barrier);
            return b.discover(Path.of("/repo/a"), Instant.parse("2026-07-23T10:00:00Z"), snap, claimed);
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
