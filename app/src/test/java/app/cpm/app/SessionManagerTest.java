package app.cpm.app;

import app.cpm.claude.ClaudeCapabilities;
import app.cpm.claude.ClaudeCapabilityService;
import app.cpm.claude.ClaudeExecutableLocator;
import app.cpm.domain.ApplicationState;
import app.cpm.domain.ManagedClaudeSession;
import app.cpm.domain.ManagedSessionId;
import app.cpm.domain.PrState;
import app.cpm.domain.RepositoryId;
import app.cpm.domain.SessionStatus;
import app.cpm.state.ApplicationStateRepository;
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
 * without a real window (see the class Javadoc there):
 *
 * <ul>
 *   <li>the plan section 11.2 resume fallback-chain -- pure argument-list
 *       (well: single-command-string, per the {@code GhosttySurface}
 *       constraint documented there) construction;</li>
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

    private SessionManager newManager(InMemoryStateRepository stateRepository) {
        backgroundExecutor = Executors.newVirtualThreadPerTaskExecutor();
        ClaudeCapabilityService capabilityService =
                new ClaudeCapabilityService(new ClaudeExecutableLocator(Path.of("/nonexistent/claude")));
        return new SessionManager(stateRepository, capabilityService, backgroundExecutor);
    }

    private ManagedClaudeSession sessionWith(Path workingDirectory, Optional<String> claudeSessionId,
                                              Optional<String> claudeSessionName) {
        Instant now = Instant.now();
        return new ManagedClaudeSession(
                ManagedSessionId.newId(),
                RepositoryId.newId(),
                "example session",
                claudeSessionId,
                claudeSessionName,
                workingDirectory,
                Optional.empty(),
                SessionStatus.INACTIVE,
                now,
                now,
                Optional.empty(),
                PrState.NONE,
                Optional.empty());
    }

    // ---- startup normalization of stale statuses ---------------------------

    @Test
    void loadNormalizesStaleRunningAndStartingSessionsToInactive() {
        ManagedClaudeSession wasRunning = sessionWith(Path.of("/tmp"), Optional.empty(), Optional.empty())
                .withStatus(SessionStatus.RUNNING);
        ManagedClaudeSession wasStarting = sessionWith(Path.of("/tmp"), Optional.empty(), Optional.empty())
                .withStatus(SessionStatus.STARTING);
        ManagedClaudeSession wasExited = sessionWith(Path.of("/tmp"), Optional.empty(), Optional.empty())
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

    // ---- 11.2 resume fallback chain (pure command construction) -----------

    @Test
    void resumeCommandPrefersTheClaudeSessionIdWhenKnown() {
        ManagedClaudeSession session = sessionWith(Path.of("/tmp"), Optional.of("abc-123"), Optional.of("ignored-name"));

        assertEquals(SessionManager.ENV_CLEANUP_PREFIX + "claude --resume 'abc-123'", SessionManager.buildResumeCommand(session));
    }

    @Test
    void resumeCommandFallsBackToTheClaudeSessionNameWhenNoIdIsKnown() {
        ManagedClaudeSession session = sessionWith(Path.of("/tmp"), Optional.empty(), Optional.of("my-name"));

        assertEquals(SessionManager.ENV_CLEANUP_PREFIX + "claude --resume 'my-name'", SessionManager.buildResumeCommand(session));
    }

    @Test
    void resumeCommandFallsBackToTheBareOfficialPickerWhenNeitherIsKnown() {
        ManagedClaudeSession session = sessionWith(Path.of("/tmp"), Optional.empty(), Optional.empty());

        assertEquals(SessionManager.ENV_CLEANUP_PREFIX + "claude --resume", SessionManager.buildResumeCommand(session));
    }

    @Test
    void resumeCommandShellQuotesAnIdContainingASingleQuote() {
        ManagedClaudeSession session = sessionWith(Path.of("/tmp"), Optional.of("weird'id"), Optional.empty());

        assertEquals(SessionManager.ENV_CLEANUP_PREFIX + "claude --resume 'weird'\\''id'", SessionManager.buildResumeCommand(session));
    }

    // ---- 11.1 create command -------------------------------------------------

    @Test
    void createCommandIncludesNameFlagWhenSupported() {
        ClaudeCapabilities capabilities = new ClaudeCapabilities(true, true, false, false, "1.0.0");

        assertEquals(SessionManager.ENV_CLEANUP_PREFIX + "claude -n 'my session'",
                SessionManager.buildCreateCommand(capabilities, "my session", "uuid-1"));
    }

    @Test
    void createCommandOmitsNameFlagWhenNotSupported() {
        ClaudeCapabilities capabilities = new ClaudeCapabilities(false, true, false, false, "0.9.0");

        assertEquals(SessionManager.ENV_CLEANUP_PREFIX + "claude", SessionManager.buildCreateCommand(capabilities, "my session", "uuid-1"));
    }

    @Test
    void createCommandPinsTheSessionIdWhenSupported() {
        ClaudeCapabilities capabilities = new ClaudeCapabilities(true, true, false, true, "1.0.0");

        assertEquals(SessionManager.ENV_CLEANUP_PREFIX + "claude -n 'my session' --session-id 'uuid-1'",
                SessionManager.buildCreateCommand(capabilities, "my session", "uuid-1"));
    }

    @Test
    void createCommandOmitsTheSessionIdWhenNotSupported() {
        ClaudeCapabilities capabilities = new ClaudeCapabilities(false, true, false, false, "0.9.0");

        assertEquals(SessionManager.ENV_CLEANUP_PREFIX + "claude", SessionManager.buildCreateCommand(capabilities, "my session", "uuid-1"));
    }

    // ---- MISSING_WORKING_DIRECTORY detection --------------------------------

    @Test
    void checkResumeBlockedDetectsAMissingWorkingDirectory(@TempDir Path tempDir) throws IOException {
        Path deleted = tempDir.resolve("gone");
        Files.createDirectory(deleted);
        Files.delete(deleted);
        assertTrue(Files.notExists(deleted));

        ManagedClaudeSession session = sessionWith(deleted, Optional.empty(), Optional.empty());
        InMemoryStateRepository stateRepository = new InMemoryStateRepository(List.of(session));
        SessionManager manager = newManager(stateRepository);

        Optional<SessionOpenResult> blocked = manager.checkResumeBlocked(session.id());

        assertTrue(blocked.isPresent());
        assertTrue(blocked.get() instanceof SessionOpenResult.MissingWorkingDirectory);
        assertEquals(SessionStatus.MISSING_WORKING_DIRECTORY, blocked.get().session().status());
        // The status change must have been persisted immediately.
        assertEquals(SessionStatus.MISSING_WORKING_DIRECTORY,
                stateRepository.savedState().sessions().get(0).status());
    }

    @Test
    void checkResumeBlockedIsEmptyWhenTheWorkingDirectoryExistsAndNoSessionIsActive(@TempDir Path tempDir) {
        ManagedClaudeSession session = sessionWith(tempDir, Optional.empty(), Optional.empty());
        SessionManager manager = newManager(new InMemoryStateRepository(List.of(session)));

        Optional<SessionOpenResult> blocked = manager.checkResumeBlocked(session.id());

        assertTrue(blocked.isEmpty());
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
        ManagedClaudeSession session = sessionWith(deleted, Optional.empty(), Optional.empty())
                .withStatus(SessionStatus.MISSING_WORKING_DIRECTORY);
        InMemoryStateRepository stateRepository = new InMemoryStateRepository(List.of(session));
        SessionManager manager = newManager(stateRepository);

        Path replacement = Files.createDirectory(tempDir.resolve("replacement"));
        ManagedClaudeSession updated = manager.reassignWorkingDirectory(session.id(), replacement);

        assertEquals(replacement.toAbsolutePath().normalize(), updated.workingDirectory());
        assertEquals(SessionStatus.INACTIVE, updated.status());
        assertEquals(replacement.toAbsolutePath().normalize(),
                stateRepository.savedState().sessions().get(0).workingDirectory());
    }

    @Test
    void renameSessionUpdatesAndPersistsTheDisplayName(@TempDir Path tempDir) {
        ManagedClaudeSession session = sessionWith(tempDir, Optional.empty(), Optional.empty());
        InMemoryStateRepository stateRepository = new InMemoryStateRepository(List.of(session));
        SessionManager manager = newManager(stateRepository);

        ManagedClaudeSession renamed = manager.renameSession(session.id(), "new name");

        assertEquals("new name", renamed.displayName());
        assertEquals("new name", stateRepository.savedState().sessions().get(0).displayName());
    }

    @Test
    void updatePrStateUpdatesAndPersistsStateAndNumber(@TempDir Path tempDir) {
        ManagedClaudeSession session = sessionWith(tempDir, Optional.empty(), Optional.empty());
        InMemoryStateRepository stateRepository = new InMemoryStateRepository(List.of(session));
        SessionManager manager = newManager(stateRepository);

        ManagedClaudeSession updated = manager.updatePrState(session.id(), PrState.OPEN, Optional.of(129));

        assertEquals(PrState.OPEN, updated.prState());
        assertEquals(129, updated.prNumber().orElseThrow());
        assertEquals(PrState.OPEN, stateRepository.savedState().sessions().get(0).prState());
        assertEquals(129, stateRepository.savedState().sessions().get(0).prNumber().orElseThrow());
    }

    private static final class InMemoryStateRepository implements ApplicationStateRepository {
        private ApplicationState state;
        private final List<ApplicationState> saves = new ArrayList<>();

        InMemoryStateRepository(List<ManagedClaudeSession> sessions) {
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
