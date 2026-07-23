package app.drydock.app;

import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.agent.providers.claude.ClaudeAgentProvider;
import app.drydock.agent.providers.claude.internal.ClaudeExecutableLocator;
import app.drydock.domain.ApplicationState;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PrState;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.RepositorySettings;
import app.drydock.domain.SessionStatus;
import app.drydock.state.ApplicationStateRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers exactly the parts of {@link SessionManager} that are testable
 * without a real window (see the class Javadoc there). Note: command-string
 * construction for the create/resume fallback chain now lives in {@code
 * ClaudeAgentProviderTest} -- {@link SessionManager} only routes through
 * {@code AgentRegistry}/{@code AgentProvider} and no longer builds commands
 * itself.
 *
 * <ul>
 *   <li>plan section 11.2's MISSING_WORKING_DIRECTORY detection;</li>
 *   <li>metadata mutations ({@link SessionManager#reassignWorkingDirectory},
 *       {@link SessionManager#renameSession}) that only touch persistence,
 *       never a terminal object.</li>
 * </ul>
 *
 * <p>NOT covered here (needs a live {@code GhosttySurface}/AppKit window,
 * per the task's Gate0c/0d/0e-style split of headless vs. interactive
 * verification):</p>
 * <ul>
 *   <li>{@link SessionManager#createSession} / {@link
 *       SessionManager#resumeSession} actually spawning a {@code claude}
 *       process and marking a session RUNNING;</li>
 *   <li>the plan section 11.3 "AlreadyOpen" outcome, which requires a real
 *       active {@code GhosttySurface} to be registered first (only
 *       reachable via a successful {@code resumeSession} call);</li>
 *   <li>{@link SessionManager#closeSession} actually driving {@code
 *       GhosttySurface.closeGracefully}.</li>
 * </ul>
 */
class SessionManagerTest {

    private ExecutorService backgroundExecutor;

    @AfterEach
    void tearDown() {
        if (backgroundExecutor != null) {
            backgroundExecutor.shutdown();
        }
    }

    /**
     * State persistence is asynchronous now (see {@link
     * ApplicationStateStore}): mutators return once the in-memory state is
     * swapped and a background writer saves later. Tests asserting on what
     * reached the repository must flush first.
     */
    private static void flushState(InMemoryStateRepository stateRepository) {
        ApplicationStateStore.forRepository(stateRepository).flush();
    }

    private SessionManager newManager(InMemoryStateRepository stateRepository) {
        backgroundExecutor = Executors.newVirtualThreadPerTaskExecutor();
        AgentContext ctx = new AgentContext(Path.of("/tmp/drydock-test"), Path.of("/tmp/drydock-test/activity"),
                backgroundExecutor);
        AgentRegistry registry = new AgentRegistry(
                List.of(new ClaudeAgentProvider(new ClaudeExecutableLocator(Path.of("/nonexistent/claude")))), ctx);
        return new SessionManager(stateRepository, registry, backgroundExecutor);
    }

    private ManagedAgentSession sessionWith(Path workingDirectory, Optional<String> agentSessionId,
                                              Optional<String> agentSessionName) {
        Instant now = Instant.now();
        return new ManagedAgentSession(
                ManagedSessionId.newId(),
                RepositoryId.newId(),
                AgentKind.CLAUDE,
                "example session",
                agentSessionId,
                agentSessionName,
                workingDirectory,
                Optional.empty(),
                SessionStatus.INACTIVE,
                now,
                now,
                Optional.empty(),
                PrState.NONE,
                Optional.empty(),
                true);
    }

    private Repository someRepository() {
        Instant now = Instant.now();
        return new Repository(RepositoryId.newId(), Path.of("/tmp/drydock-test-repo"), "example repo", now, now,
                RepositorySettings.DEFAULT);
    }

    // ---- Task 11: agent picker / lastUsedAgent persistence -----------------

    @Test
    void prepareSessionRecordsChosenAgentKind() {
        InMemoryStateRepository stateRepository = new InMemoryStateRepository(List.of());
        SessionManager manager = newManager(stateRepository);

        ManagedAgentSession prepared = manager.prepareSession(someRepository(), AgentKind.CLAUDE);

        assertEquals(AgentKind.CLAUDE, prepared.agentKind());
    }

    @Test
    void lastUsedAgentTransformUpdatesTheRepo() {
        Repository repo = someRepository();
        ApplicationState state = ApplicationState.empty().withRepositories(List.of(repo));

        ApplicationState updated = SessionManager.repoWithLastUsedAgent(state, repo.id(), AgentKind.CODEX);

        assertEquals(Optional.of(AgentKind.CODEX), updated.repositories().get(0).settings().lastUsedAgent());
    }

    // ---- startup normalization of stale statuses ---------------------------

    @Test
    void loadNormalizesStaleRunningAndStartingSessionsToInactive() {
        ManagedAgentSession wasRunning = sessionWith(Path.of("/tmp"), Optional.empty(), Optional.empty())
                .withStatus(SessionStatus.RUNNING);
        ManagedAgentSession wasStarting = sessionWith(Path.of("/tmp"), Optional.empty(), Optional.empty())
                .withStatus(SessionStatus.STARTING);
        ManagedAgentSession wasExited = sessionWith(Path.of("/tmp"), Optional.empty(), Optional.empty())
                .withStatus(SessionStatus.EXITED);

        SessionManager manager = newManager(new InMemoryStateRepository(List.of(wasRunning, wasStarting, wasExited)));

        assertEquals(SessionStatus.INACTIVE, statusOf(manager, wasRunning.id()),
                "a session persisted as RUNNING by a previous app run has no surviving process");
        assertEquals(SessionStatus.INACTIVE, statusOf(manager, wasStarting.id()));
        assertEquals(SessionStatus.EXITED, statusOf(manager, wasExited.id()),
                "terminal statuses must pass through unchanged");
    }

    private static SessionStatus statusOf(SessionManager manager, ManagedSessionId id) {
        return manager.sessions().stream()
                .filter(s -> s.id().equals(id))
                .findFirst()
                .orElseThrow()
                .status();
    }

    // ---- MISSING_WORKING_DIRECTORY detection --------------------------------

    @Test
    void checkResumeBlockedDetectsAMissingWorkingDirectory(@TempDir Path tempDir) throws IOException {
        Path deleted = tempDir.resolve("gone");
        Files.createDirectory(deleted);
        Files.delete(deleted);
        assertTrue(Files.notExists(deleted));

        ManagedAgentSession session = sessionWith(deleted, Optional.empty(), Optional.empty());
        InMemoryStateRepository stateRepository = new InMemoryStateRepository(List.of(session));
        SessionManager manager = newManager(stateRepository);

        Optional<SessionOpenResult> blocked = manager.checkResumeBlocked(session.id());

        assertTrue(blocked.isPresent());
        assertTrue(blocked.get() instanceof SessionOpenResult.MissingWorkingDirectory);
        assertEquals(SessionStatus.MISSING_WORKING_DIRECTORY, blocked.get().session().status());
        // The status change must have been persisted (asynchronously; flush first).
        flushState(stateRepository);
        assertEquals(SessionStatus.MISSING_WORKING_DIRECTORY,
                stateRepository.savedState().sessions().get(0).status());
    }

    @Test
    void checkResumeBlockedIsEmptyWhenTheWorkingDirectoryExistsAndNoSessionIsActive(@TempDir Path tempDir) {
        ManagedAgentSession session = sessionWith(tempDir, Optional.empty(), Optional.empty());
        SessionManager manager = newManager(new InMemoryStateRepository(List.of(session)));

        Optional<SessionOpenResult> blocked = manager.checkResumeBlocked(session.id());

        assertTrue(blocked.isEmpty());
    }

    @Test
    void checkResumeBlockedBlocksAnUnsupportedAgentSession(@TempDir Path tempDir) {
        ManagedAgentSession session = sessionWith(tempDir, Optional.empty(), Optional.empty())
                .withStatus(SessionStatus.UNSUPPORTED_AGENT);
        SessionManager manager = newManager(new InMemoryStateRepository(List.of(session)));

        Optional<SessionOpenResult> blocked = manager.checkResumeBlocked(session.id());

        assertTrue(blocked.isPresent(), "an UNSUPPORTED_AGENT session must never resume/launch");
        assertTrue(blocked.get() instanceof SessionOpenResult.UnsupportedAgent);
        assertEquals(session.id(), blocked.get().session().id());
    }

    @Test
    void checkResumeBlockedThrowsForAnUnknownSessionId() {
        SessionManager manager = newManager(new InMemoryStateRepository(List.of()));

        assertThrows(UnknownSessionException.class, () -> manager.checkResumeBlocked(ManagedSessionId.newId()));
    }

    // ---- Metadata-only mutations --------------------------------------------

    @Test
    void reassignWorkingDirectoryUpdatesAndPersistsAndClearsMissingStatus(@TempDir Path tempDir) throws IOException {
        Path deleted = tempDir.resolve("gone2");
        Files.createDirectory(deleted);
        Files.delete(deleted);
        ManagedAgentSession session = sessionWith(deleted, Optional.empty(), Optional.empty())
                .withStatus(SessionStatus.MISSING_WORKING_DIRECTORY);
        InMemoryStateRepository stateRepository = new InMemoryStateRepository(List.of(session));
        SessionManager manager = newManager(stateRepository);

        Path replacement = Files.createDirectory(tempDir.resolve("replacement"));
        ManagedAgentSession updated = manager.reassignWorkingDirectory(session.id(), replacement);

        assertEquals(replacement.toAbsolutePath().normalize(), updated.workingDirectory());
        assertEquals(SessionStatus.INACTIVE, updated.status());
        flushState(stateRepository);
        assertEquals(replacement.toAbsolutePath().normalize(),
                stateRepository.savedState().sessions().get(0).workingDirectory());
    }

    @Test
    void renameSessionUpdatesAndPersistsTheDisplayName(@TempDir Path tempDir) {
        ManagedAgentSession session = sessionWith(tempDir, Optional.empty(), Optional.empty());
        InMemoryStateRepository stateRepository = new InMemoryStateRepository(List.of(session));
        SessionManager manager = newManager(stateRepository);

        ManagedAgentSession renamed = manager.renameSession(session.id(), "new name");

        assertEquals("new name", renamed.displayName());
        flushState(stateRepository);
        assertEquals("new name", stateRepository.savedState().sessions().get(0).displayName());
    }

    @Test
    void updatePrStateUpdatesAndPersistsStateAndNumber(@TempDir Path tempDir) {
        ManagedAgentSession session = sessionWith(tempDir, Optional.empty(), Optional.empty());
        InMemoryStateRepository stateRepository = new InMemoryStateRepository(List.of(session));
        SessionManager manager = newManager(stateRepository);

        ManagedAgentSession updated = manager.updatePrState(session.id(), PrState.OPEN, Optional.of(129));

        assertEquals(PrState.OPEN, updated.prState());
        assertEquals(129, updated.prNumber().orElseThrow());
        flushState(stateRepository);
        assertEquals(PrState.OPEN, stateRepository.savedState().sessions().get(0).prState());
        assertEquals(129, stateRepository.savedState().sessions().get(0).prNumber().orElseThrow());
    }

    private static final class InMemoryStateRepository implements ApplicationStateRepository {
        // volatile: saves arrive on the state store's background writer thread.
        private volatile ApplicationState state;
        private final List<ApplicationState> saves = new ArrayList<>();

        InMemoryStateRepository(List<ManagedAgentSession> sessions) {
            state = ApplicationState.empty().withSessions(sessions);
        }

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
    }
}
