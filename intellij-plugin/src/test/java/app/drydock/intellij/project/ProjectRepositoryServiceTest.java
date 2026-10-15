package app.drydock.intellij.project;

import app.drydock.agent.api.AgentKind;
import app.drydock.app.ApplicationStateStore;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.intellij.project.ProjectRepositoryService.Registration;
import app.drydock.state.JsonApplicationStateRepository;

import com.intellij.openapi.project.Project;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Repository identity for projects and session working directories. The
 * git fixtures are real temporary repositories in the style of {@code
 * WorktreeServiceTest}: the canonical-root key must behave against the
 * same filesystem git produces, not a mock of it (AC25/R7).
 */
class ProjectRepositoryServiceTest {

    /** Stores created by this test instance; flushed after each test. */
    private final List<ApplicationStateStore> stores = new ArrayList<>();

    private ApplicationStateStore storeAt(Path base) {
        ApplicationStateStore store =
                ApplicationStateStore.forRepository(new JsonApplicationStateRepository(base.resolve("state.json")));
        stores.add(store);
        return store;
    }

    /**
     * The store's coalescing background save otherwise races JUnit's
     * @TempDir cleanup: a save still in flight while the temp directory is
     * deleted fails the test with "Failed to delete temp directory" — a
     * flake, not a state bug (same race T9's suite fixed the same way).
     */
    @AfterEach
    void flushStores() {
        stores.forEach(ApplicationStateStore::flush);
        stores.clear();
    }

    /** The service against a ready store; the direct executor makes every future synchronous. */
    private static ProjectRepositoryService service(ApplicationStateStore store) {
        return new ProjectRepositoryService(CompletableFuture.completedFuture(null), () -> store, Runnable::run);
    }

    private static ManagedAgentSession session(RepositoryId repositoryId) {
        return new ManagedAgentSession.Builder(
                ManagedSessionId.newId(), repositoryId, "Session",
                AgentBinding.unlaunched(AgentKind.CLAUDE),
                new SessionWorkspace(Path.of("/tmp/wt"), Optional.of(Path.of("/tmp/wt")), true),
                Instant.parse("2026-08-12T10:15:30Z"))
                .status(SessionStatus.INACTIVE)
                .build();
    }

    /**
     * A {@link Project} that answers only {@code getBasePath()} — the one
     * project API this service reads. A reflective stand-in, because there
     * is no cheap platform way to obtain a project rooted at an arbitrary
     * fixture directory.
     */
    private static Project projectWithBasePath(String basePath) {
        return (Project) Proxy.newProxyInstance(
                ProjectRepositoryServiceTest.class.getClassLoader(),
                new Class<?>[] {Project.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getBasePath" -> basePath;
                    case "toString" -> "project@" + basePath;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> args[0] == proxy;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == char.class) {
            return (char) 0;
        }
        if (type == float.class) {
            return 0f;
        }
        return 0d;
    }

    @Test
    void twoProjectsWithTheSameRootYieldOneRepository(@TempDir Path base) throws Exception {
        ApplicationStateStore store = storeAt(base);
        ProjectRepositoryService service = service(store);
        Project first = projectWithBasePath(base.toString());
        Project second = projectWithBasePath(base.toString());

        RepositoryId firstId = service.projectRepository(first).get(5, TimeUnit.SECONDS).id();
        RepositoryId secondId = service.projectRepository(second).get(5, TimeUnit.SECONDS).id();

        assertEquals(firstId, secondId);
        assertEquals(1, store.state().repositories().size(), "the second project must reuse the registration");
    }

    @Test
    void aSymlinkedProjectPathResolvesToTheSameRegistration(@TempDir Path base) throws Exception {
        ApplicationStateStore store = storeAt(base);
        ProjectRepositoryService service = service(store);
        Path real = Files.createDirectories(base.resolve("repo"));
        Path alias = Files.createSymbolicLink(base.resolve("alias"), real);

        RepositoryId viaReal = service.repositoryIdFor(real).get(5, TimeUnit.SECONDS);
        RepositoryId viaAlias = service.repositoryIdFor(alias).get(5, TimeUnit.SECONDS);

        assertEquals(viaReal, viaAlias);
        assertEquals(1, store.state().repositories().size());
        // Idempotence at the seam: an already-registered root returns the
        // SAME state instance, so the single writer's equals check skips
        // the save entirely.
        Registration reRegistered = ProjectRepositoryService.ensureRegistered(store.state(), alias);
        assertSame(store.state(), reRegistered.state());
    }

    @Test
    void aWorktreeSessionRegistersUnderTheWorktreeOwnRepository(@TempDir Path repoDir,
            @TempDir Path worktreeParent) throws Exception {
        // R7/AC25 core half: a session's repository is keyed on its own
        // working directory, so a worktree is its own repository and the
        // project later opened at the worktree root resolves the same one.
        Path repo = initCommittedRepo(repoDir);
        Path worktree = worktreeParent.resolve("wt");
        runGit(repo, "worktree", "add", worktree.toString(), "-b", "feat/wt");

        ApplicationStateStore store = storeAt(worktreeParent);
        ProjectRepositoryService service = service(store);

        RepositoryId mainId = service.repositoryIdFor(repo).get(5, TimeUnit.SECONDS);
        RepositoryId worktreeId = service.repositoryIdFor(worktree).get(5, TimeUnit.SECONDS);

        assertNotEquals(mainId, worktreeId, "a worktree is its own repository, not the main checkout's");
        assertEquals(2, store.state().repositories().size());

        // The project opened at the worktree root later resolves the id the
        // session was registered under.
        RepositoryId worktreeProjectId = service
                .projectRepository(projectWithBasePath(worktree.toString()))
                .get(5, TimeUnit.SECONDS)
                .id();
        assertEquals(worktreeId, worktreeProjectId);

        // The session bound to the worktree shows in the worktree's list,
        // never in the main checkout's.
        ManagedAgentSession worktreeSession = session(worktreeId);
        ManagedAgentSession mainSession = session(mainId);
        store.update(state -> state.withSessions(List.of(worktreeSession, mainSession)));
        assertEquals(List.of(worktreeSession), ProjectRepositoryService.sessionsFor(store.state(), worktreeId));
        assertEquals(List.of(mainSession), ProjectRepositoryService.sessionsFor(store.state(), mainId));
    }

    @Test
    void aScratchProjectWithNoBasePathResolvesToAnEmptyFuture(@TempDir Path base) {
        ProjectRepositoryService service = service(storeAt(base));

        CompletableFuture<Repository> resolved =
                service.projectRepository(projectWithBasePath(null));

        // Already completed with a null repository: "not a git project" is
        // a rendering concern for the caller, not an error or a wait.
        assertTrue(resolved.isDone(), "the scratch case must not touch the executor");
        assertNull(resolved.join());
    }

    @Test
    void repositoryResolutionWaitsForPluginInitialization(@TempDir Path base) throws Exception {
        CompletableFuture<Void> initialization = new CompletableFuture<>();
        ApplicationStateStore store = storeAt(base);
        ProjectRepositoryService service =
                new ProjectRepositoryService(initialization, () -> store, Runnable::run);

        CompletableFuture<RepositoryId> resolved = service.repositoryIdFor(base);
        assertFalse(resolved.isDone(), "the store must not be read before initialization completes");

        initialization.complete(null);
        assertEquals(resolved.get(5, TimeUnit.SECONDS), store.state().repositories().get(0).id());
    }

    private static Path initCommittedRepo(Path parent) throws IOException, InterruptedException {
        Path repo = Files.createDirectories(parent.resolve("repo"));
        runGit(repo, "init", "-b", "main");
        runGit(repo, "config", "user.name", "Test");
        runGit(repo, "config", "user.email", "test@example.com");
        Files.writeString(repo.resolve("README.md"), "hello\n");
        runGit(repo, "add", "README.md");
        runGit(repo, "commit", "-m", "initial commit");
        return repo;
    }

    private static void runGit(Path repo, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(repo.toString());
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("git " + String.join(" ", args) + " failed (exit " + exitCode + "): " + output);
        }
    }
}
