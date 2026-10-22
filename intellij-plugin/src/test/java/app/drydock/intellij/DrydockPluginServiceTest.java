package app.drydock.intellij;

import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.McpDelivery;
import app.drydock.app.ApplicationStateStore;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ApplicationState;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.domain.WorkspaceUiState;
import app.drydock.intellij.review.ReviewBackend;
import app.drydock.state.JsonApplicationStateRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DrydockPluginServiceTest {

    private static ManagedAgentSession session(SessionStatus status) {
        return new ManagedAgentSession.Builder(
                ManagedSessionId.newId(), RepositoryId.newId(), "Session",
                AgentBinding.unlaunched(AgentKind.CLAUDE),
                new SessionWorkspace(Path.of("/tmp/wt"), Optional.of(Path.of("/tmp/wt")), true),
                Instant.parse("2026-08-12T10:15:30Z"))
                .status(status)
                .build();
    }

    private static ApplicationState stateWith(ManagedAgentSession... sessions) {
        return new ApplicationState(List.of(), List.of(sessions), List.of(),
                WorkspaceUiState.empty(), List.of());
    }

    @Test
    void startingAndRunningSessionsNormalizeToInactive() {
        ManagedAgentSession starting = session(SessionStatus.STARTING);
        ManagedAgentSession running = session(SessionStatus.RUNNING);

        ApplicationState normalized = DrydockPluginService.normalizeOnLoad(stateWith(starting, running));

        List<ManagedAgentSession> sessions = normalized.sessions();
        assertEquals(2, sessions.size());
        assertEquals(SessionStatus.INACTIVE, sessions.get(0).status());
        assertEquals(SessionStatus.INACTIVE, sessions.get(1).status());
        // Only the status changes: identity, repository, and the rest survive.
        assertEquals(starting.id(), sessions.get(0).id());
        assertEquals(starting.repositoryId(), sessions.get(0).repositoryId());
        assertEquals(starting.binding(), sessions.get(0).binding());
        assertEquals(starting.workspace(), sessions.get(0).workspace());
        assertEquals(running.id(), sessions.get(1).id());
    }

    @Test
    void otherStatusesAreUntouched() {
        ManagedAgentSession inactive = session(SessionStatus.INACTIVE);
        ManagedAgentSession exited = session(SessionStatus.EXITED);
        ManagedAgentSession failed = session(SessionStatus.FAILED);
        ManagedAgentSession missing = session(SessionStatus.MISSING_WORKING_DIRECTORY);
        ManagedAgentSession unsupported = session(SessionStatus.UNSUPPORTED_AGENT);

        ApplicationState state = stateWith(inactive, exited, failed, missing, unsupported);
        ApplicationState normalized = DrydockPluginService.normalizeOnLoad(state);

        // Nothing to change: the same state instance comes back (so the
        // single writer's equals check skips the save entirely).
        assertSame(state, normalized);
        assertEquals(inactive, normalized.sessions().get(0));
        assertEquals(exited, normalized.sessions().get(1));
        assertEquals(failed, normalized.sessions().get(2));
        assertEquals(missing, normalized.sessions().get(3));
        assertEquals(unsupported, normalized.sessions().get(4));
    }

    @Test
    void initializationNormalizesPersistedStateOffTheTestThread(@TempDir Path base) throws Exception {
        // Seed the state file the service must find at base/state.json —
        // a leftover RUNNING session from a previous run plus one that was
        // already dead.
        new JsonApplicationStateRepository(base.resolve("state.json"))
                .save(stateWith(session(SessionStatus.RUNNING), session(SessionStatus.EXITED)));

        DrydockPluginService service = new DrydockPluginService(base);
        CountDownLatch done = new CountDownLatch(1);
        service.initialization().whenComplete((unused, failure) -> done.countDown());
        assertTrue(done.await(60, TimeUnit.SECONDS), "initialization did not complete");

        // The init work ran on the service's executor, never on this thread.
        assertNotNull(service.initializationThread());
        assertNotSame(Thread.currentThread(), service.initializationThread());

        ApplicationStateStore store = service.stateStore();
        List<ManagedAgentSession> sessions = store.state().sessions();
        assertEquals(2, sessions.size());
        // Execution-sessions migration: the persisted statuses decode as IDLE
        // at load (codec seam), so init finds nothing left to normalize — a
        // leftover RUNNING row and an EXITED one both surface as IDLE.
        assertEquals(SessionStatus.IDLE, sessions.get(0).status());
        assertEquals(SessionStatus.IDLE, sessions.get(1).status());

        // Base-dir composition: the state was found and persisted through the
        // repository rooted at the injected base dir.
        assertEquals(base, service.baseDir());
        assertTrue(Files.exists(base.resolve("state.json")));

        service.dispose();
    }

    @Test
    void disposeFlushesPendingStateAndShutsTheExecutorDown(@TempDir Path base) throws Exception {
        DrydockPluginService service = new DrydockPluginService(base);
        service.initialization().get(60, TimeUnit.SECONDS);

        ManagedAgentSession added = session(SessionStatus.INACTIVE);
        service.stateStore().update(state -> state.withSessions(
                List.of(session(SessionStatus.INACTIVE), added)));

        service.dispose();

        assertTrue(service.executor().isShutdown(), "dispose must shut the executor down");
        // flush() in dispose persisted the update added after init.
        ApplicationState onDisk = new JsonApplicationStateRepository(base.resolve("state.json")).load();
        assertEquals(2, onDisk.sessions().size());
        assertEquals(added.id(), onDisk.sessions().get(1).id());
    }

    @Test
    void testServiceKeepsTheSharedProjectsListInItsOwnDirectory(@TempDir Path base) throws Exception {
        DrydockPluginService service = new DrydockPluginService(base);
        service.initialization().get(60, TimeUnit.SECONDS);
        try {
            service.sharedProjects().register(base.resolve("repo"), "repo").get(60, TimeUnit.SECONDS);

            assertTrue(Files.exists(base.resolve("projects.json")), "the test seam must not use ~/.drydock");
            assertEquals(1, service.sharedProjects().projects().size());
        } finally {
            service.dispose();
        }
    }

    @Test
    void disposeBeforeInitializationCompletesIsSafe(@TempDir Path base) throws Exception {
        DrydockPluginService service = new DrydockPluginService(base);
        // Disposal races init: it must wait for it (bounded) rather than
        // null-flush while the normalization update is still in flight.
        service.dispose();
        service.initialization().get(5, TimeUnit.SECONDS);
        assertTrue(service.executor().isShutdown());
    }

    @Test
    void initializationStartsTheReviewBackendOffTheTestThread(@TempDir Path base) throws Exception {
        DrydockPluginService service = new DrydockPluginService(base);
        service.initialization().get(60, TimeUnit.SECONDS);
        try {
            ReviewBackend backend = service.reviewBackend().orElseThrow();
            assertTrue(backend.ready());
            assertNotSame(Thread.currentThread(), service.initializationThread());
            service.dispose();
            assertFalse(backend.ready(), "dispose must close the backend");
        } finally {
            service.dispose();
        }
    }

    @Test
    void aBackendThatCannotBeBuiltLeavesInitHealthy(@TempDir Path base) throws Exception {
        DrydockPluginService service = new DrydockPluginService(base, (dir, sessions, repositories, registry) -> {
            throw new IllegalStateException("no backend");
        });
        try {
            service.initialization().get(60, TimeUnit.SECONDS);

            assertTrue(service.reviewBackend().isEmpty());
            assertNotNull(service.stateStore());
            assertTrue(service.reviewAccess()
                    .accessFor(ManagedSessionId.newId(), McpDelivery.CONFIG_FILE).isEmpty());
        } finally {
            service.dispose();
        }
    }

    @Test
    void aBackendBuiltAfterDisposeTimedOutIsClosed(@TempDir Path base) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        AtomicReference<ReviewBackend> built = new AtomicReference<>();
        DrydockPluginService service = new DrydockPluginService(base, (dir, sessions, repositories, registry) -> {
            entered.countDown();
            try {
                proceed.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ReviewBackend backend = new ReviewBackend(dir, sessions, repositories, registry);
            built.set(backend);
            return backend;
        }, Duration.ofMillis(200));
        assertTrue(entered.await(60, TimeUnit.SECONDS));

        service.dispose();
        proceed.countDown();
        // Init may fail once the executor is shut down; that is tolerated.
        service.initialization().handle((value, error) -> null).get(60, TimeUnit.SECONDS);

        ReviewBackend backend = built.get();
        assertNotNull(backend, "the factory must have built a backend");
        assertFalse(backend.ready(), "a backend built after dispose must be closed");
        assertTrue(service.reviewBackend().isEmpty(), "a backend built after dispose must not be published");
    }

    @Test
    void treeSitterLibrariesGoToTheGivenDirectoryUnlessAlreadyConfigured(@TempDir Path dir) {
        String before = System.getProperty(DrydockPluginService.TREE_SITTER_LIB_PROPERTY);
        try {
            System.clearProperty(DrydockPluginService.TREE_SITTER_LIB_PROPERTY);
            DrydockPluginService.useOwnTreeSitterLibraries(dir.resolve("ours"));
            assertEquals(dir.resolve("ours").toString(),
                    System.getProperty(DrydockPluginService.TREE_SITTER_LIB_PROPERTY));

            DrydockPluginService.useOwnTreeSitterLibraries(dir.resolve("other"));
            assertEquals(dir.resolve("ours").toString(),
                    System.getProperty(DrydockPluginService.TREE_SITTER_LIB_PROPERTY),
                    "a directory already configured is kept");
        } finally {
            if (before == null) {
                System.clearProperty(DrydockPluginService.TREE_SITTER_LIB_PROPERTY);
            } else {
                System.setProperty(DrydockPluginService.TREE_SITTER_LIB_PROPERTY, before);
            }
        }
    }
}
