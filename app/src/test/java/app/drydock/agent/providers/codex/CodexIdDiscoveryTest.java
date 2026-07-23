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
                Instant.parse("2026-07-23T10:00:00Z"), snap, Set.of());
        assertTrue(id.isPresent());
        assertEquals("new11111-0000-0000-0000-000000000000", id.get());
    }

    @Test
    void emptyWhenNothingNew(@TempDir Path root) throws IOException {
        rollout(root, "old00000-0000-0000-0000-000000000000", "/repo/a", "2026-07-23T09:00:00Z");
        CodexRolloutStore store = new CodexRolloutStore(root);
        CodexIdDiscovery discovery = new CodexIdDiscovery(store, 1, 0);
        assertTrue(discovery.discover(Path.of("/repo/a"), Instant.parse("2026-07-23T10:00:00Z"),
                store.idsFor(Path.of("/repo/a")), Set.of()).isEmpty());
    }

    @Test
    void ambiguousTwoNewRolloutsBailToPickerWithoutBinding(@TempDir Path root) throws IOException {
        // Two same-cwd launches: snapshot is empty, then TWO new unclaimed rollouts appear.
        java.util.Set<String> claimed = java.util.concurrent.ConcurrentHashMap.newKeySet();
        CodexRolloutStore store = new CodexRolloutStore(root);
        java.util.Set<String> snap = store.idsFor(Path.of("/repo/a"));   // empty
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
        java.util.Set<String> claimed = java.util.concurrent.ConcurrentHashMap.newKeySet();
        CodexRolloutStore store = new CodexRolloutStore(root);
        java.util.Set<String> snap = store.idsFor(Path.of("/repo/a"));
        rollout(root, "ccc00000-0000-0000-0000-000000000000", "/repo/a", "2026-07-23T11:00:00Z");
        CodexIdDiscovery d = new CodexIdDiscovery(new CodexRolloutStore(root), 1, 0);
        Optional<String> first = d.discover(Path.of("/repo/a"), Instant.parse("2026-07-23T10:00:00Z"), snap, claimed);
        Optional<String> second = d.discover(Path.of("/repo/a"), Instant.parse("2026-07-23T10:00:00Z"), snap, claimed);
        assertTrue(first.isPresent());
        assertTrue(second.isEmpty());   // already claimed -> second finds no unclaimed candidate
    }
}
