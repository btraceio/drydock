package app.drydock.app;

import app.drydock.domain.ApplicationState;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.git.GitStatusService;
import app.drydock.git.NotAGitRepositoryException;
import app.drydock.state.ApplicationStateRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositoryManagerTest {

    @TempDir
    Path tempDir;

    private GitStatusService gitStatusService;
    private InMemoryStateRepository stateRepository;
    private RepositoryManager manager;

    @BeforeEach
    void setUp() {
        gitStatusService = new GitStatusService();
        stateRepository = new InMemoryStateRepository();
        manager = new RepositoryManager(stateRepository, gitStatusService);
    }

    @AfterEach
    void tearDown() {
        gitStatusService.close();
    }

    /**
     * State persistence is asynchronous now (see {@link
     * ApplicationStateStore}): mutators return once the in-memory state is
     * swapped and a background writer saves later. Tests asserting on what
     * reached the repository must flush first.
     */
    private void flushState() {
        ApplicationStateStore.forRepository(stateRepository).flush();
    }

    private Path initGitRepo(String name) throws IOException, InterruptedException {
        Path dir = Files.createDirectory(tempDir.resolve(name));
        run(dir, "git", "init", "-q");
        return dir;
    }

    private static void run(Path dir, String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).directory(dir.toFile()).inheritIO().start();
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("Command failed: " + String.join(" ", command));
        }
    }

    @Test
    void addRepositoryRegistersAndPersistsAValidGitRepository() throws Exception {
        Path repo = initGitRepo("repo-a");

        Repository added = manager.addRepository(repo).get();

        assertEquals("repo-a", added.displayName());
        assertEquals(1, manager.repositories().size());
        flushState();
        assertEquals(1, stateRepository.savedState().repositories().size());
    }

    @Test
    void addRepositoryRejectsANonGitDirectory() throws Exception {
        Path plainDir = Files.createDirectory(tempDir.resolve("not-a-repo"));

        CompletionException thrown = assertThrows(CompletionException.class, () -> manager.addRepository(plainDir).join());

        assertTrue(thrown.getCause() instanceof NotAGitRepositoryException);
        assertTrue(manager.repositories().isEmpty());
    }

    @Test
    void addRepositoryRejectsADuplicateByCanonicalRoot() throws Exception {
        Path repo = initGitRepo("repo-b");
        manager.addRepository(repo).get();

        ExecutionException thrown = assertThrows(ExecutionException.class, () -> manager.addRepository(repo).get());

        assertTrue(thrown.getCause() instanceof DuplicateRepositoryException);
        assertEquals(1, manager.repositories().size());
    }

    @Test
    void removeRepositoryDeletesOnlyMetadataNeverTheDirectory() throws Exception {
        Path repo = initGitRepo("repo-c");
        Repository added = manager.addRepository(repo).get();

        manager.removeRepository(added.id());

        assertTrue(manager.repositories().isEmpty());
        assertTrue(Files.exists(repo), "removing a repository must never delete it from disk");
        flushState();
        assertTrue(stateRepository.savedState().repositories().isEmpty());
    }

    @Test
    void removeRepositoryIsANoOpForAnUnknownId() throws Exception {
        Path repo = initGitRepo("repo-d");
        manager.addRepository(repo).get();
        flushState();
        int savesBefore = stateRepository.saveCount();

        manager.removeRepository(RepositoryId.newId());

        flushState();
        assertEquals(1, manager.repositories().size());
        assertEquals(savesBefore, stateRepository.saveCount(), "an unknown id must not trigger a redundant save");
    }

    private static final class InMemoryStateRepository implements ApplicationStateRepository {
        // volatile: saves arrive on the state store's background writer thread.
        private volatile ApplicationState state = ApplicationState.empty();
        private final List<ApplicationState> saves = new ArrayList<>();

        @Override
        public ApplicationState load() {
            return state;
        }

        @Override
        public void save(ApplicationState newState) {
            state = newState;
            saves.add(newState);
        }

        ApplicationState savedState() {
            return state;
        }

        int saveCount() {
            return saves.size();
        }
    }
}
