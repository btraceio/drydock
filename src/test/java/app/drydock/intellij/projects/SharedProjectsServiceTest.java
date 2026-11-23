package app.drydock.intellij.projects;

import app.drydock.domain.RepositoryId;
import app.drydock.domain.SshRemote;
import app.drydock.projects.SharedProject;
import app.drydock.projects.SharedProjectsFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedProjectsServiceTest {

    @TempDir
    Path dir;

    private ScheduledExecutorService executor;
    private SharedProjectsFile file;

    @BeforeEach
    void setUp() {
        executor = Executors.newSingleThreadScheduledExecutor();
        file = new SharedProjectsFile(dir.resolve("projects.json"));
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private static SharedProject project(Path root) {
        return SharedProject.local(RepositoryId.newId(), root, root.getFileName().toString(), Instant.now());
    }

    @Test
    void aMissingFileStartsEmpty() {
        SharedProjectsService service = new SharedProjectsService(file, executor);

        service.start().join();

        assertEquals(List.of(), service.projects());
        assertTrue(Files.exists(file.file()), "the list file is created on start");
    }

    @Test
    void existingFileIsNotReseeded() throws Exception {
        SharedProject existing = project(dir.resolve("existing"));
        file.update(list -> List.of(existing));
        SharedProjectsService service = new SharedProjectsService(file, executor);

        service.start().join();

        assertEquals(List.of(existing), service.projects());
    }

    @Test
    void registerIsIdempotentByRoot() {
        SharedProjectsService service = new SharedProjectsService(file, executor);
        service.start().join();
        Path root = dir.resolve("repo");

        SharedProject first = service.register(root, "repo").join();
        SharedProject second = service.register(root, "repo again").join();

        assertEquals(first, second);
        assertEquals(List.of(first), service.projects());
        assertEquals(first, service.byRoot(root).orElseThrow());
    }

    @Test
    void removeDropsIt() {
        SharedProjectsService service = new SharedProjectsService(file, executor);
        service.start().join();
        SharedProject added = service.register(dir.resolve("repo"), "repo").join();

        service.remove(added.id()).join();

        assertEquals(List.of(), service.projects());
        assertEquals(List.of(), file.read().projects());
    }

    @Test
    void byIdNowReadsTheFileFreshNotTheLaggedCache() throws Exception {
        SharedProjectsService service = new SharedProjectsService(file, executor);
        service.start().join();
        SharedProject added = project(dir.resolve("external"));
        // Another tool's write the 2-second poll has not picked up yet.
        new SharedProjectsFile(file.file()).update(list -> List.of(added));

        assertEquals(Optional.of(added), service.byIdNow(added.id()));
        assertEquals(Optional.empty(), service.byIdNow(RepositoryId.newId()));
    }

    @Test
    void externalChangeFiresListenerOnce() throws Exception {
        SharedProjectsService service = new SharedProjectsService(file, executor);
        service.start().join();
        AtomicInteger fired = new AtomicInteger();
        service.addListener(fired::incrementAndGet);
        SharedProject external = project(dir.resolve("external"));
        new SharedProjectsFile(file.file()).update(list -> List.of(external));

        service.refresh();
        service.refresh();

        assertEquals(List.of(external), service.projects());
        assertEquals(1, fired.get());
    }

    @Test
    void removedListenerIsNotCalled() throws Exception {
        SharedProjectsService service = new SharedProjectsService(file, executor);
        service.start().join();
        AtomicInteger fired = new AtomicInteger();
        Runnable unsubscribe = service.addListener(fired::incrementAndGet);
        unsubscribe.run();
        new SharedProjectsFile(file.file()).update(list -> List.of(project(dir.resolve("x"))));

        service.refresh();

        assertEquals(0, fired.get());
    }

    @Test
    void malformedFileExposesFlagAndRegisterFails() throws Exception {
        Files.writeString(file.file(), "{broken");
        SharedProjectsService service = new SharedProjectsService(file, executor);

        service.start().join();

        assertTrue(service.problem().isPresent());
        assertThrows(CompletionException.class, () -> service.register(dir.resolve("r"), "r").join());
        assertFalse(Files.readString(file.file()).contains("\"r\""));
    }

    @Test
    void aDeletedFileIsReseededNotLeftAbsent() throws Exception {
        SharedProjectsService service = new SharedProjectsService(file, executor);
        service.start().join();
        Files.delete(file.file());

        service.refresh();

        assertEquals(List.of(), service.projects());
        assertTrue(Files.exists(file.file()), "the deleted file is recreated, not left absent");
    }

    @Test
    void registeringAfterADeleteWritesOntoTheRecreatedFile() throws Exception {
        SharedProjectsService service = new SharedProjectsService(file, executor);
        service.start().join();
        Files.delete(file.file());

        SharedProject added = service.register(dir.resolve("new"), "new").join();

        assertEquals(List.of(added), file.read().projects());
    }

    @Test
    void aServiceStoppedBeforeItStartedNeverWrites() throws Exception {
        // The IDE can dispose the plugin service before its initialization
        // reaches start(): the late start must not seed files afterwards.
        SharedProjectsService service = new SharedProjectsService(file, executor);

        service.stop();
        service.start().join();
        service.refresh();

        assertFalse(Files.exists(file.file()));
    }

    @Test
    void registerRemoteStoresThePlaceholderRootAndTheRemote() {
        SharedProjectsService service = new SharedProjectsService(file, executor);
        service.start().join();
        SshRemote remote = new SshRemote("build-box", "/srv/work/engine");

        SharedProject registered = service.registerRemote(remote).join();

        assertTrue(registered.isRemote());
        assertEquals(Optional.of(remote), registered.remote());
        assertEquals(remote.placeholderRoot(), registered.root());
        assertEquals("engine", registered.displayName());
        // A remote entry must round-trip through the same codec as every
        // other entry so all writers agree on the row.
        List<SharedProject> stored = file.read().projects();
        assertEquals(1, stored.size());
        assertTrue(stored.get(0).isRemote());
        assertEquals(Optional.of(remote), stored.get(0).remote());
    }

    @Test
    void registerRemoteIsIdempotentOnTheSameHostAndPath() {
        SharedProjectsService service = new SharedProjectsService(file, executor);
        service.start().join();
        SshRemote remote = new SshRemote("build-box", "/srv/work/engine");

        SharedProject first = service.registerRemote(remote).join();
        SharedProject second = service.registerRemote(
                new SshRemote("build-box", "/srv/work/engine")).join();

        assertEquals(first, second);
        assertEquals(1, file.read().projects().size(), "the duplicate must not add a second entry");
    }

    @Test
    void aDifferentHostIsNotADuplicateOfTheSamePath() {
        SharedProjectsService service = new SharedProjectsService(file, executor);
        service.start().join();

        service.registerRemote(new SshRemote("build-box", "/srv/work/engine")).join();
        service.registerRemote(new SshRemote("other-box", "/srv/work/engine")).join();

        assertEquals(2, file.read().projects().size(), "the placeholder root is unique per (host, path)");
    }
}
