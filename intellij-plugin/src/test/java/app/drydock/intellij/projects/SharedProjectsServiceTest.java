package app.drydock.intellij.projects;

import app.drydock.domain.RepositoryId;
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
    void seedsFromAppWhenAbsent() {
        SharedProject fromApp = project(dir.resolve("app-repo"));
        SharedProjectsService service = new SharedProjectsService(file, () -> List.of(fromApp), executor);

        service.start().join();

        assertEquals(List.of(fromApp), service.projects());
        assertEquals(List.of(fromApp), file.read().projects());
    }

    @Test
    void existingFileIsNotReseeded() throws Exception {
        SharedProject existing = project(dir.resolve("existing"));
        file.update(list -> List.of(existing));
        SharedProjectsService service = new SharedProjectsService(file,
                () -> List.of(project(dir.resolve("app-repo"))), executor);

        service.start().join();

        assertEquals(List.of(existing), service.projects());
    }

    @Test
    void registerIsIdempotentByRoot() {
        SharedProjectsService service = new SharedProjectsService(file, List::of, executor);
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
        SharedProjectsService service = new SharedProjectsService(file, List::of, executor);
        service.start().join();
        SharedProject added = service.register(dir.resolve("repo"), "repo").join();

        service.remove(added.id()).join();

        assertEquals(List.of(), service.projects());
        assertEquals(List.of(), file.read().projects());
    }

    @Test
    void externalChangeFiresListenerOnce() throws Exception {
        SharedProjectsService service = new SharedProjectsService(file, List::of, executor);
        service.start().join();
        AtomicInteger fired = new AtomicInteger();
        service.addListener(fired::incrementAndGet);
        SharedProject fromApp = project(dir.resolve("from-app"));
        new SharedProjectsFile(file.file()).update(list -> List.of(fromApp));

        service.refresh();
        service.refresh();

        assertEquals(List.of(fromApp), service.projects());
        assertEquals(1, fired.get());
    }

    @Test
    void removedListenerIsNotCalled() throws Exception {
        SharedProjectsService service = new SharedProjectsService(file, List::of, executor);
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
        SharedProjectsService service = new SharedProjectsService(file, List::of, executor);

        service.start().join();

        assertTrue(service.problem().isPresent());
        assertThrows(CompletionException.class, () -> service.register(dir.resolve("r"), "r").join());
        assertFalse(Files.readString(file.file()).contains("\"r\""));
    }

    @Test
    void aDeletedFileIsReseededNotEmptied() throws Exception {
        SharedProject fromApp = project(dir.resolve("app-repo"));
        SharedProjectsService service = new SharedProjectsService(file, () -> List.of(fromApp), executor);
        service.start().join();
        Files.delete(file.file());

        service.refresh();

        assertEquals(List.of(fromApp), service.projects());
        assertEquals(List.of(fromApp), file.read().projects());
    }

    @Test
    void registeringAfterADeleteKeepsTheSeededProjects() throws Exception {
        SharedProject fromApp = project(dir.resolve("app-repo"));
        SharedProjectsService service = new SharedProjectsService(file, () -> List.of(fromApp), executor);
        service.start().join();
        Files.delete(file.file());

        SharedProject added = service.register(dir.resolve("new"), "new").join();

        assertEquals(List.of(fromApp, added), file.read().projects());
    }
}
