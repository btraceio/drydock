package app.drydock.agent.providers.pi.internal;

import org.junit.jupiter.api.Assumptions;
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

class PiSessionStoreTest {

    private static Path writeSession(Path root, Path cwd, String id, String iso) throws IOException {
        Path dir = root.resolve(PiSessionStore.encodeCwdDir(cwd));
        Files.createDirectories(dir);
        Path f = dir.resolve(iso.replace(":", "-") + "_" + id + ".jsonl");
        String cwdString = cwd.toRealPath().toString();
        String meta = "{\"type\":\"session\",\"id\":\"" + id + "\",\"timestamp\":\"" + iso + "\","
                + "\"cwd\":\"" + cwdString.replace("\\", "\\\\") + "\"}\n";
        Files.writeString(f, meta);
        return f;
    }

    @Test
    void forWorkingDirectoryReturnsMatchingNewestFirstAndExcludesUnrelatedCwd(@TempDir Path tmp) throws IOException {
        Path cwd = tmp.resolve("proj");
        Files.createDirectories(cwd);
        Path unrelated = tmp.resolve("other");
        Files.createDirectories(unrelated);

        writeSession(tmp, cwd, "aaaa1111-0000-0000-0000-000000000001", "2026-07-23T10:00:00.000Z");
        writeSession(tmp, cwd, "bbbb2222-0000-0000-0000-000000000002", "2026-07-23T11:00:00.000Z");
        writeSession(tmp, unrelated, "cccc3333-0000-0000-0000-000000000003", "2026-07-23T12:00:00.000Z");

        PiSessionStore store = new PiSessionStore(tmp);
        List<PiSessionStore.SessionMeta> metas = store.forWorkingDirectory(cwd);

        assertEquals(List.of("bbbb2222-0000-0000-0000-000000000002", "aaaa1111-0000-0000-0000-000000000001"),
                metas.stream().map(PiSessionStore.SessionMeta::id).toList());
    }

    @Test
    void forWorkingDirectoryOnMissingDirReturnsEmpty(@TempDir Path tmp) {
        PiSessionStore store = new PiSessionStore(tmp);
        assertEquals(List.of(), store.forWorkingDirectory(tmp.resolve("nonexistent")));
    }

    @Test
    void newCandidateIdsSkipsSnapshotAndOldAndSortsEarliestFirst(@TempDir Path tmp) throws IOException {
        Path cwd = tmp.resolve("proj");
        Files.createDirectories(cwd);

        String preexisting = "dddd4444-0000-0000-0000-000000000004";
        writeSession(tmp, cwd, preexisting, "2026-07-23T09:00:00.000Z");
        PiSessionStore store = new PiSessionStore(tmp);
        Set<String> snapshot = store.snapshotIds(cwd);

        String later = "ffff6666-0000-0000-0000-000000000007";
        String earlier = "eeee5555-0000-0000-0000-000000000005";
        writeSession(tmp, cwd, later, "2026-07-23T11:05:00.000Z");
        writeSession(tmp, cwd, earlier, "2026-07-23T11:00:00.000Z");

        List<String> found = store.newCandidateIds(cwd, Instant.parse("2026-07-23T10:30:00Z"), snapshot);
        assertEquals(List.of(earlier, later), found);
    }

    @Test
    void existsForIdFindsByFilenameOnly(@TempDir Path tmp) throws IOException {
        Path cwd = tmp.resolve("proj");
        Files.createDirectories(cwd);
        String id = "ffff6666-0000-0000-0000-000000000006";
        writeSession(tmp, cwd, id, "2026-07-23T10:00:00.000Z");

        PiSessionStore store = new PiSessionStore(tmp);
        assertTrue(store.existsForId(cwd, id));
        assertFalse(store.existsForId(cwd, "00000000-0000-0000-0000-000000000000"));
    }

    @Test
    void encodeCwdDirCanonicalizesSymlinkToSameNameAsRealDir(@TempDir Path tmp) throws IOException {
        Path real = tmp.resolve("real-dir");
        Files.createDirectories(real);
        Path link = tmp.resolve("link-dir");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | IOException e) {
            // Symlinks may be unsupported/unprivileged on some platforms/CI; skip rather than fail spuriously.
            Assumptions.assumeTrue(false, "symlinks unsupported here");
        }
        assertEquals(PiSessionStore.encodeCwdDir(real), PiSessionStore.encodeCwdDir(link));
    }

    @Test
    void encodeCwdDirFallsBackToNormalizeForNonexistentPath() {
        assertEquals("--repo-a--", PiSessionStore.encodeCwdDir(Path.of("/repo/a")));
    }
}
