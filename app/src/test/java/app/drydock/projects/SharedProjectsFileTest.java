package app.drydock.projects;

import app.drydock.domain.RepositoryId;
import app.drydock.domain.SshRemote;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedProjectsFileTest {

    private static final Instant ADDED = Instant.parse("2026-10-19T10:00:00Z");

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve("projects.json");
    }

    private static SharedProject local(String root) {
        return SharedProject.local(RepositoryId.newId(), Path.of(root), Path.of(root).getFileName().toString(), ADDED);
    }

    private static List<SharedProject> append(List<SharedProject> list, SharedProject project) {
        List<SharedProject> next = new ArrayList<>(list);
        next.add(project);
        return next;
    }

    @Test
    void defaultFileHonoursTheOverrideProperty() {
        String before = System.getProperty(SharedProjectsFile.OVERRIDE_PROPERTY);
        try {
            System.setProperty(SharedProjectsFile.OVERRIDE_PROPERTY, dir.resolve("elsewhere.json").toString());

            assertEquals(dir.resolve("elsewhere.json"), SharedProjectsFile.defaultFile());
        } finally {
            if (before == null) {
                System.clearProperty(SharedProjectsFile.OVERRIDE_PROPERTY);
            } else {
                System.setProperty(SharedProjectsFile.OVERRIDE_PROPERTY, before);
            }
        }
    }

    @Test
    void missingFileReadsEmptyNotPresent() {
        SharedProjectsFile.Snapshot snapshot = new SharedProjectsFile(file()).read();

        assertEquals(List.of(), snapshot.projects());
        assertFalse(snapshot.present());
        assertFalse(snapshot.malformed());
        assertEquals(Optional.empty(), snapshot.problem());
        assertEquals(0L, snapshot.stamp());
    }

    @Test
    void updateCreatesFileAndRoundTrips() throws Exception {
        SharedProject plain = local("/w/alpha");
        SharedProject remote = new SharedProject(RepositoryId.newId(), new SshRemote("host", "/r").placeholderRoot(),
                "r", ADDED, Optional.of(new SshRemote("host", "/r")));

        new SharedProjectsFile(file()).update(list -> append(append(list, plain), remote));

        SharedProjectsFile.Snapshot snapshot = new SharedProjectsFile(file()).read();
        assertTrue(snapshot.present());
        assertEquals(List.of(plain, remote), snapshot.projects());
    }

    @Test
    void malformedFileIsNeverOverwritten() throws Exception {
        Files.writeString(file(), "{not json");
        byte[] before = Files.readAllBytes(file());
        SharedProjectsFile shared = new SharedProjectsFile(file());

        assertTrue(shared.read().malformed());
        assertEquals(List.of(), shared.read().projects());
        assertThrows(SharedProjectsException.class, () -> shared.update(list -> append(list, local("/w/x"))));
        assertArrayEquals(before, Files.readAllBytes(file()));
    }

    @Test
    void malformedEntryIsSkippedOthersKept() throws Exception {
        String id = RepositoryId.newId().toString();
        Files.writeString(file(), """
                {"schemaVersion":1,"projects":[
                  {"id":"%s","root":"/w/good","displayName":"good","addedAt":"2026-10-19T10:00:00Z"},
                  {"id":"%s","displayName":"no root","addedAt":"2026-10-19T10:00:00Z"}
                ]}""".formatted(id, RepositoryId.newId()), StandardCharsets.UTF_8);

        SharedProjectsFile.Snapshot snapshot = new SharedProjectsFile(file()).read();

        assertFalse(snapshot.malformed());
        assertEquals(1, snapshot.projects().size());
        assertEquals(Path.of("/w/good"), snapshot.projects().get(0).root());
        assertTrue(snapshot.problem().isPresent(), "a skipped entry is a problem worth showing");
    }

    @Test
    void fileWithAnUndecodableEntryIsShownButNeverRewritten() throws Exception {
        // A hand-added entry missing "addedAt": the next Add in either tool
        // used to rewrite the list without it, losing it for good.
        Files.writeString(file(), """
                {"schemaVersion":1,"projects":[
                  {"id":"%s","root":"/w/good","displayName":"good","addedAt":"2026-10-19T10:00:00Z"},
                  {"id":"%s","root":"/w/hand-added","displayName":"hand-added"}
                ]}""".formatted(RepositoryId.newId(), RepositoryId.newId()), StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(file());
        SharedProjectsFile shared = new SharedProjectsFile(file());

        SharedProjectsFile.Snapshot snapshot = shared.read();
        assertEquals(1, snapshot.projects().size());
        assertTrue(snapshot.problem().isPresent());
        assertThrows(SharedProjectsException.class, () -> shared.update(list -> append(list, local("/w/x"))));
        assertArrayEquals(before, Files.readAllBytes(file()));
    }

    @Test
    void fileFromANewerSchemaIsShownButNeverRewritten() throws Exception {
        String id = RepositoryId.newId().toString();
        Files.writeString(file(), """
                {"schemaVersion":2,"projects":[
                  {"id":"%s","root":"/w/a","displayName":"a","addedAt":"2026-10-19T10:00:00Z","pinned":true}
                ]}""".formatted(id), StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(file());
        SharedProjectsFile shared = new SharedProjectsFile(file());

        assertEquals(1, shared.read().projects().size());
        assertTrue(shared.read().problem().isPresent());
        assertThrows(SharedProjectsException.class, () -> shared.update(list -> append(list, local("/w/x"))));
        assertArrayEquals(before, Files.readAllBytes(file()));
    }

    @Test
    void concurrentUpdatesFromTwoInstancesAllSurvive() throws Exception {
        SharedProjectsFile first = new SharedProjectsFile(file());
        SharedProjectsFile second = new SharedProjectsFile(file());
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int thread = 0; thread < 16; thread++) {
                SharedProjectsFile target = thread % 2 == 0 ? first : second;
                int t = thread;
                futures.add(pool.submit(() -> {
                    go.await();
                    for (int i = 0; i < 25; i++) {
                        SharedProject project = local("/w/t" + t + "-" + i);
                        target.update(list -> append(list, project));
                    }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(400, new SharedProjectsFile(file()).read().projects().size());
    }

    @Test
    void updateOnAMissingFileStartsFromTheSeed() throws Exception {
        // The user deleted projects.json; the next Add must not leave a
        // one-entry list that empties the other tool.
        SharedProject known = local("/w/known");
        SharedProject added = local("/w/added");

        List<SharedProject> written = new SharedProjectsFile(file())
                .update(list -> append(list, added), () -> List.of(known));

        assertEquals(List.of(known, added), written);
    }

    @Test
    void seedIfAbsentWritesOnceOnly() throws Exception {
        SharedProjectsFile shared = new SharedProjectsFile(file());
        AtomicBoolean secondSupplierCalled = new AtomicBoolean();

        assertTrue(shared.seedIfAbsent(() -> List.of(local("/w/seeded"))));
        assertFalse(shared.seedIfAbsent(() -> {
            secondSupplierCalled.set(true);
            return List.of(local("/w/other"));
        }));

        assertFalse(secondSupplierCalled.get());
        assertEquals(1, shared.read().projects().size());
    }

    @Test
    void stampChangesAfterUpdate() throws Exception {
        SharedProjectsFile shared = new SharedProjectsFile(file());
        shared.update(list -> append(list, local("/w/one")));
        long before = shared.read().stamp();

        shared.update(list -> append(list, local("/w/two-with-a-longer-name")));

        assertNotEquals(before, shared.read().stamp());
    }

    @Test
    void sameRootIsKeptOnceFirstWins() throws Exception {
        SharedProjectsFile shared = new SharedProjectsFile(file());
        SharedProject first = local("/w/same");

        shared.update(list -> append(append(list, first), local("/w/same")));

        assertEquals(List.of(first), shared.read().projects());
    }
}
