package app.drydock.agent.providers.codex.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexRolloutStoreTest {

    private static Path writeRollout(Path root, String date, String id, String cwd, String iso, String source)
            throws IOException {
        Path dir = root.resolve(date.replace("-", "/"));
        Files.createDirectories(dir);
        Path f = dir.resolve("rollout-" + iso.replace(":", "-") + "-" + id + ".jsonl");
        String meta = "{\"timestamp\":\"" + iso + "\",\"type\":\"session_meta\",\"payload\":{"
                + "\"id\":\"" + id + "\",\"session_id\":\"" + id + "\",\"timestamp\":\"" + iso + "\","
                + "\"cwd\":\"" + cwd + "\",\"source\":\"" + source + "\"}}\n";
        Files.writeString(f, meta);
        return f;
    }

    @Test
    void forWorkingDirectoryFiltersByCwdAndSourceCli(@TempDir Path root) throws IOException {
        writeRollout(root, "2026-07-23", "aaaa1111-0000-0000-0000-000000000001", "/repo/a", "2026-07-23T10:00:00Z", "cli");
        writeRollout(root, "2026-07-23", "bbbb2222-0000-0000-0000-000000000002", "/repo/b", "2026-07-23T10:01:00Z", "cli");
        writeRollout(root, "2026-07-23", "cccc3333-0000-0000-0000-000000000003", "/repo/a", "2026-07-23T10:02:00Z", "exec");
        CodexRolloutStore store = new CodexRolloutStore(root);
        var metas = store.forWorkingDirectory(Path.of("/repo/a"));
        assertEquals(1, metas.size());  // /repo/b excluded (cwd), exec excluded (source)
        assertEquals("aaaa1111-0000-0000-0000-000000000001", metas.get(0).id());
    }

    @Test
    void newCandidatesSkipsSnapshotAndOldAndSortsEarliestFirst(@TempDir Path root) throws IOException {
        String preexisting = "dddd4444-0000-0000-0000-000000000004";
        writeRollout(root, "2026-07-23", preexisting, "/repo/a", "2026-07-23T09:00:00Z", "cli");
        Set<String> snapshot = new CodexRolloutStore(root).idsFor(Path.of("/repo/a"));
        String earlier = "eeee5555-0000-0000-0000-000000000005";
        String later = "ffff6666-0000-0000-0000-000000000007";
        writeRollout(root, "2026-07-23", later, "/repo/a", "2026-07-23T11:05:00Z", "cli");
        writeRollout(root, "2026-07-23", earlier, "/repo/a", "2026-07-23T11:00:00Z", "cli");
        var found = new CodexRolloutStore(root)
                .newCandidates(Path.of("/repo/a"), Instant.parse("2026-07-23T10:30:00Z"), snapshot);
        // preexisting excluded (in snapshot); earliest-first ordering
        assertEquals(List.of(earlier, later), found.stream().map(CodexRolloutStore.RolloutMeta::id).toList());
    }

    @Test
    void existsForId(@TempDir Path root) throws IOException {
        writeRollout(root, "2026-07-23", "ffff6666-0000-0000-0000-000000000006", "/repo/a", "2026-07-23T10:00:00Z", "cli");
        CodexRolloutStore store = new CodexRolloutStore(root);
        assertTrue(store.existsForId("ffff6666-0000-0000-0000-000000000006"));
        assertFalse(store.existsForId("00000000-0000-0000-0000-000000000000"));
    }
}
