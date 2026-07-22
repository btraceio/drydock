package app.drydock.app;

import app.drydock.claude.ClaudeCapabilities;
import app.drydock.claude.ClaudeCapabilityService;
import app.drydock.claude.ClaudeExecutableLocator;
import app.drydock.domain.ApplicationState;
import app.drydock.domain.ManagedClaudeSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PrState;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SshRemote;
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
                Optional.empty(),
                true);
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

    /** No activity-hook settings injected, so these assertions stay about the fallback chain alone. */
    private static final Optional<Path> NO_SETTINGS = Optional.empty();

    private static ClaudeCapabilities caps(boolean name, boolean sessionId, boolean settings) {
        return new ClaudeCapabilities(name, true, false, sessionId, settings, "1.0.0");
    }

    @Test
    void resumeCommandPrefersTheClaudeSessionIdWhenKnown() {
        ManagedClaudeSession session = sessionWith(Path.of("/tmp"), Optional.of("abc-123"), Optional.of("ignored-name"));

        assertEquals(SessionManager.ENV_CLEANUP_PREFIX + "claude --resume 'abc-123'",
                SessionManager.buildResumeCommand(session, caps(true, true, false), NO_SETTINGS));
    }

    @Test
    void resumeCommandFallsBackToTheClaudeSessionNameWhenNoIdIsKnown() {
        ManagedClaudeSession session = sessionWith(Path.of("/tmp"), Optional.empty(), Optional.of("my-name"));

        assertEquals(SessionManager.ENV_CLEANUP_PREFIX + "claude --resume 'my-name'",
                SessionManager.buildResumeCommand(session, caps(true, true, false), NO_SETTINGS));
    }

    @Test
    void resumeCommandFallsBackToTheBareOfficialPickerWhenNeitherIsKnown() {
        ManagedClaudeSession session = sessionWith(Path.of("/tmp"), Optional.empty(), Optional.empty());

        assertEquals(SessionManager.ENV_CLEANUP_PREFIX + "claude --resume",
                SessionManager.buildResumeCommand(session, caps(true, true, false), NO_SETTINGS));
    }

    @Test
    void resumeCommandShellQuotesAnIdContainingASingleQuote() {
        ManagedClaudeSession session = sessionWith(Path.of("/tmp"), Optional.of("weird'id"), Optional.empty());

        assertEquals(SessionManager.ENV_CLEANUP_PREFIX + "claude --resume 'weird'\\''id'",
                SessionManager.buildResumeCommand(session, caps(true, true, false), NO_SETTINGS));
    }

    // ---- 11.1 create command -------------------------------------------------

    @Test
    void createCommandIncludesNameFlagWhenSupported() {
        assertEquals(SessionManager.ENV_CLEANUP_PREFIX + "claude -n 'my session'",
                SessionManager.buildCreateCommand(caps(true, false, false), "my session", "uuid-1", NO_SETTINGS));
    }

    @Test
    void createCommandOmitsNameFlagWhenNotSupported() {
        assertEquals(SessionManager.ENV_CLEANUP_PREFIX + "claude",
                SessionManager.buildCreateCommand(caps(false, false, false), "my session", "uuid-1", NO_SETTINGS));
    }

    @Test
    void createCommandPinsTheSessionIdWhenSupported() {
        assertEquals(SessionManager.ENV_CLEANUP_PREFIX + "claude -n 'my session' --session-id 'uuid-1'",
                SessionManager.buildCreateCommand(caps(true, true, false), "my session", "uuid-1", NO_SETTINGS));
    }

    @Test
    void createCommandOmitsTheSessionIdWhenNotSupported() {
        assertEquals(SessionManager.ENV_CLEANUP_PREFIX + "claude",
                SessionManager.buildCreateCommand(caps(false, false, false), "my session", "uuid-1", NO_SETTINGS));
    }

    // ---- activity-hook settings injection ------------------------------------

    @Test
    void createCommandInjectsTheActivitySettingsWhenSupported() {
        assertEquals(SessionManager.ENV_CLEANUP_PREFIX
                        + "claude -n 'my session' --session-id 'uuid-1' --settings '/tmp/hooks/settings.json'",
                SessionManager.buildCreateCommand(caps(true, true, true), "my session", "uuid-1",
                        Optional.of(Path.of("/tmp/hooks/settings.json"))));
    }

    @Test
    void resumeCommandInjectsTheActivitySettingsWhenSupported() {
        ManagedClaudeSession session = sessionWith(Path.of("/tmp"), Optional.of("abc-123"), Optional.empty());

        assertEquals(SessionManager.ENV_CLEANUP_PREFIX
                        + "claude --resume 'abc-123' --settings '/tmp/hooks/settings.json'",
                SessionManager.buildResumeCommand(session, caps(true, true, true),
                        Optional.of(Path.of("/tmp/hooks/settings.json"))));
    }

    /** The bare-picker fallback still reports activity: correlation comes from the hook payload, not the command line. */
    @Test
    void barePickerResumeStillInjectsTheActivitySettings() {
        ManagedClaudeSession session = sessionWith(Path.of("/tmp"), Optional.empty(), Optional.empty());

        assertEquals(SessionManager.ENV_CLEANUP_PREFIX + "claude --resume --settings '/tmp/hooks/settings.json'",
                SessionManager.buildResumeCommand(session, caps(true, true, true),
                        Optional.of(Path.of("/tmp/hooks/settings.json"))));
    }

    @Test
    void activitySettingsAreOmittedWhenTheInstalledClaudeLacksTheFlag() {
        assertEquals(SessionManager.ENV_CLEANUP_PREFIX + "claude -n 'my session' --session-id 'uuid-1'",
                SessionManager.buildCreateCommand(caps(true, true, false), "my session", "uuid-1",
                        Optional.of(Path.of("/tmp/hooks/settings.json"))));
    }

    /**
     * Regression: resume used to be a pure function of persisted metadata and
     * could not fail. Adding the activity --settings flag put uncached
     * capability detection in front of it; a probe failure must degrade to
     * "no activity reporting", never sink the resume itself. This asserts the
     * command built from the conservative all-false fallback capabilities.
     */
    @Test
    void resumeFallsBackToThePlainCommandWhenCapabilitiesAreUnknown() {
        ManagedClaudeSession session = sessionWith(Path.of("/tmp"), Optional.of("abc-123"), Optional.empty());

        assertEquals(SessionManager.ENV_CLEANUP_PREFIX + "claude --resume 'abc-123'",
                SessionManager.buildResumeCommand(session, caps(false, false, false),
                        Optional.of(Path.of("/tmp/hooks/settings.json"))));
    }

    @Test
    void activitySettingsPathWithASpaceIsShellQuoted() {
        assertEquals(SessionManager.ENV_CLEANUP_PREFIX
                        + "claude --settings '/Users/x/Application Support/hooks/settings.json'",
                SessionManager.buildCreateCommand(caps(false, false, true), "my session", "uuid-1",
                        Optional.of(Path.of("/Users/x/Application Support/hooks/settings.json"))));
    }

    // ---- degraded remote session contract (command construction) -----------

    @Test
    void remoteCreateCommandIsSshWrappedPlainClaude() {
        SshRemote remote = new SshRemote("user@h", "/srv/app");
        String command = SessionManager.buildRemoteCreateCommand(remote);
        // Pessimistic flag set: no -n, no --session-id, no --settings — the
        // remote claude's capabilities are unknown and the activity-hook
        // settings file is a LOCAL path (spec: degraded remote contract).
        assertEquals("exec ssh -t -- 'user@h' "
                + "'export TERM=xterm-256color; cd '\\''/srv/app'\\'' && exec claude'", command);
    }

    @Test
    void remoteResumeCommandTrustsStoredId() {
        SshRemote remote = new SshRemote("user@h", "/srv/app");
        ManagedClaudeSession session = sessionWith(Path.of("/tmp"), Optional.of("abc-123"), Optional.empty());
        String command = SessionManager.buildRemoteResumeCommand(remote, session);
        assertTrue(command.contains("--resume"));
        assertTrue(command.contains("abc-123"));
        assertTrue(command.startsWith("exec ssh -t -- 'user@h' '"));
    }

    @Test
    void remoteResumeCommandFallsBackToBareResume() {
        SshRemote remote = new SshRemote("user@h", "/srv/app");
        ManagedClaudeSession session = sessionWith(Path.of("/tmp"), Optional.empty(), Optional.empty());
        assertTrue(SessionManager.buildRemoteResumeCommand(remote, session).endsWith("exec claude --resume'"));
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
        // The status change must have been persisted (asynchronously; flush first).
        flushState(stateRepository);
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
        flushState(stateRepository);
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
        flushState(stateRepository);
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
        flushState(stateRepository);
        assertEquals(PrState.OPEN, stateRepository.savedState().sessions().get(0).prState());
        assertEquals(129, stateRepository.savedState().sessions().get(0).prNumber().orElseThrow());
    }

    private static final class InMemoryStateRepository implements ApplicationStateRepository {
        // volatile: saves arrive on the state store's background writer thread.
        private volatile ApplicationState state;
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
