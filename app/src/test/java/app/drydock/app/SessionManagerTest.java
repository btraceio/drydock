package app.drydock.app;

import app.drydock.domain.SessionWorkspace;
import app.drydock.domain.PrLink;
import app.drydock.domain.AgentBinding;
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
import app.drydock.mcp.McpSessionContext.RenameKind;
import app.drydock.mcp.McpSessionContext.RenameOutcome;
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
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    /**
     * A minimal INACTIVE session, shared with {@link
     * SessionManagerExitReleasesMcpTest} (same package) so the MCP-lifecycle
     * tests do not restate this 16-component record.
     */
    static ManagedAgentSession newSessionFixture() {
        return sessionWith(Path.of("/tmp"), Optional.empty(), Optional.empty());
    }

    private static ManagedAgentSession sessionWith(Path workingDirectory, Optional<String> agentSessionId,
                                              Optional<String> agentSessionName) {
        Instant now = Instant.now();
        return new ManagedAgentSession(
                ManagedSessionId.newId(), RepositoryId.newId(), "example session",
                new AgentBinding(AgentKind.CLAUDE, agentSessionId, agentSessionName),
                new SessionWorkspace(workingDirectory, Optional.empty(), true),
                SessionStatus.INACTIVE, now, now, Optional.empty(),
                PrLink.of(PrState.NONE, Optional.empty()), false, Optional.empty());
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

    @Test
    void seedClaimedIdsCollectsAssignedAgentSessionIds() {
        ManagedAgentSession withId = sessionWith(Path.of("/tmp"), Optional.of("id-1"), Optional.empty())
                .withAgentKind(AgentKind.CODEX);
        ApplicationState state = ApplicationState.empty().withSessions(List.of(withId));
        assertEquals(Set.of("id-1"), SessionManager.seedClaimedIds(state));
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

        ManagedAgentSession renamed = manager.renameSession(session.id(), "new name", true);

        assertEquals("new name", renamed.displayName());
        flushState(stateRepository);
        assertEquals("new name", stateRepository.savedState().sessions().get(0).displayName());
    }

    // ---- Task 7: applyAgentRename's single-transform outcome ---------------

    /** A session seeded directly into a repository's state, bypassing launch. */
    private static ManagedAgentSession sessionIn(Repository repository, String displayName) {
        Instant now = Instant.now();
        return new ManagedAgentSession(
                ManagedSessionId.newId(), repository.id(), displayName,
                new AgentBinding(AgentKind.CLAUDE, Optional.empty(), Optional.empty()),
                new SessionWorkspace(repository.root(), Optional.empty(), true),
                SessionStatus.INACTIVE, now, now, Optional.empty(),
                PrLink.of(PrState.NONE, Optional.empty()), false, Optional.empty());
    }

    /** {@link SessionManager#sessions()} is public where {@code findSession} is not; use it to read state back. */
    private static Optional<ManagedAgentSession> currentSession(SessionManager manager, ManagedSessionId id) {
        return manager.sessions().stream().filter(session -> session.id().equals(id)).findFirst();
    }

    @Test
    void anAgentRenameAppliesAndDoesNotPin() {
        Repository repository = someRepository();
        ManagedAgentSession session = sessionIn(repository, "Session 1");
        SessionManager manager = newManager(new InMemoryStateRepository(List.of(session)));

        RenameOutcome outcome = manager.applyAgentRename(session.id(), "Fix the login flow");

        assertEquals(RenameKind.RENAMED, outcome.kind());
        assertEquals("Fix the login flow", outcome.currentName());
        assertFalse(currentSession(manager, session.id()).orElseThrow().namePinned());
        // ...so a second agent rename also succeeds
        assertEquals(RenameKind.RENAMED, manager.applyAgentRename(session.id(), "Fix the logout flow").kind());
    }

    @Test
    void aHumanRenameWithPinBlocksLaterAgentRenames() {
        Repository repository = someRepository();
        ManagedAgentSession session = sessionIn(repository, "Session 1");
        SessionManager manager = newManager(new InMemoryStateRepository(List.of(session)));
        manager.renameSession(session.id(), "Mine", true);

        RenameOutcome outcome = manager.applyAgentRename(session.id(), "Fix the login flow");

        assertEquals(RenameKind.PINNED, outcome.kind());
        assertEquals("Mine", outcome.currentName());
        assertEquals("Mine", currentSession(manager, session.id()).orElseThrow().displayName());
    }

    @Test
    void aHumanRenameWithoutPinLeavesTheAgentFree() {
        Repository repository = someRepository();
        ManagedAgentSession session = sessionIn(repository, "Session 1");
        SessionManager manager = newManager(new InMemoryStateRepository(List.of(session)));
        manager.renameSession(session.id(), "Blur wrote this", false);

        assertEquals(RenameKind.RENAMED, manager.applyAgentRename(session.id(), "Fix the login flow").kind());
    }

    @Test
    void renamingToTheCurrentNameIsUnchanged() {
        Repository repository = someRepository();
        ManagedAgentSession session = sessionIn(repository, "Session 1");
        SessionManager manager = newManager(new InMemoryStateRepository(List.of(session)));
        manager.applyAgentRename(session.id(), "Fix the login flow");

        assertEquals(RenameKind.UNCHANGED, manager.applyAgentRename(session.id(), "Fix the login flow").kind());
    }

    @Test
    void pinBeatsUnchangedWhenBothApply() {
        Repository repository = someRepository();
        ManagedAgentSession session = sessionIn(repository, "Session 1");
        SessionManager manager = newManager(new InMemoryStateRepository(List.of(session)));
        manager.renameSession(session.id(), "Mine", true);

        // Asking for the name it already has, on a pinned session: PINNED wins.
        assertEquals(RenameKind.PINNED, manager.applyAgentRename(session.id(), "Mine").kind());
    }

    @Test
    void aTitleAnotherSessionInTheSameRepositoryHoldsCollides() {
        Repository repository = someRepository();
        ManagedAgentSession first = sessionIn(repository, "Session 1");
        ManagedAgentSession second = sessionIn(repository, "Session 2");
        SessionManager manager = newManager(new InMemoryStateRepository(List.of(first, second)));
        manager.applyAgentRename(first.id(), "Fix the login flow");

        RenameOutcome outcome = manager.applyAgentRename(second.id(), "fix the LOGIN flow");

        assertEquals(RenameKind.COLLIDED, outcome.kind());
        assertEquals("Fix the login flow", outcome.currentName());
        assertEquals("Session 2", currentSession(manager, second.id()).orElseThrow().displayName());
    }

    @Test
    void theSameTitleInAnotherRepositoryIsFine() {
        Repository repository = someRepository();
        Repository otherRepository = someRepository();
        ManagedAgentSession here = sessionIn(repository, "Session 1");
        ManagedAgentSession there = sessionIn(otherRepository, "Session 1");
        SessionManager manager = newManager(new InMemoryStateRepository(List.of(here, there)));
        manager.applyAgentRename(here.id(), "Fix the login flow");

        assertEquals(RenameKind.RENAMED, manager.applyAgentRename(there.id(), "Fix the login flow").kind());
    }

    @Test
    void collisionComparesAgainstFoldedStoredNames() {
        // The human path applies no checkSessionTitle, so a stored name can carry
        // a non-breaking space that renders identically to a plain one. Folding
        // only the incoming side would let an agent take a name that looks, in
        // the sidebar and in every confirm dialog, exactly like its neighbour's.
        // U+00A0 is deliberately an escape, not a literal: an invisible character
        // in source is unreviewable.
        Repository repository = someRepository();
        ManagedAgentSession first = sessionIn(repository, "Session 1");
        ManagedAgentSession second = sessionIn(repository, "Session 2");
        SessionManager manager = newManager(new InMemoryStateRepository(List.of(first, second)));
        manager.renameSession(first.id(), "Fix\u00A0login", false);   // NBSP, stored unfolded

        assertEquals(RenameKind.COLLIDED,
                manager.applyAgentRename(second.id(), "Fix login").kind());   // plain space
    }

    @Test
    void anExitedSessionStillHoldsItsName() {
        Repository repository = someRepository();
        ManagedAgentSession first = sessionIn(repository, "Session 1").withStatus(SessionStatus.RUNNING);
        ManagedAgentSession second = sessionIn(repository, "Session 2");
        SessionManager manager = newManager(new InMemoryStateRepository(List.of(first, second)));
        manager.applyAgentRename(first.id(), "Fix the login flow");
        manager.markSessionExited(first.id());

        assertEquals(RenameKind.COLLIDED, manager.applyAgentRename(second.id(), "Fix the login flow").kind());
    }

    // ---- /new rebind: applyAgentReclaim -----------------------------------

    private static ManagedAgentSession sessionTracking(Repository repository, String displayName,
                                                        String agentSessionId) {
        return sessionIn(repository, displayName).withAgentSessionId(Optional.of(agentSessionId));
    }

    @Test
    void reclaimRebindsTheTrackedConversationId() {
        Repository repository = someRepository();
        ManagedAgentSession session = sessionTracking(repository, "Session 1", "old-conv");
        SessionManager manager = newManager(new InMemoryStateRepository(List.of(session)));

        manager.applyAgentReclaim(session.id(), "new-conv");

        ManagedAgentSession rebound = currentSession(manager, session.id()).orElseThrow();
        assertEquals(Optional.of("new-conv"), rebound.agentSessionId());
        // The new conversation is claimed, so a concurrent same-cwd launch
        // cannot discover it out from under this tab; the old one is released.
        assertTrue(manager.isClaimedAgentSessionId("new-conv"));
        assertFalse(manager.isClaimedAgentSessionId("old-conv"));
        assertEquals(Optional.of(session.id()), manager.activeSessionFor("new-conv"));
        assertTrue(manager.activeSessionFor("old-conv").isEmpty());
    }

    @Test
    void reclaimToTheIdAlreadyTrackedIsANoOpSuccess() {
        Repository repository = someRepository();
        ManagedAgentSession session = sessionTracking(repository, "Session 1", "same-conv");
        SessionManager manager = newManager(new InMemoryStateRepository(List.of(session)));

        manager.applyAgentReclaim(session.id(), "same-conv");

        // Nothing moved: the id is still tracked and still claimed. The
        // active registry is untouched on a no-op -- a surface that was
        // active stays active, and one that was not (this test has none)
        // stays inactive -- so a defensive reclaim never invents activity.
        assertEquals(Optional.of("same-conv"),
                currentSession(manager, session.id()).orElseThrow().agentSessionId());
        assertTrue(manager.isClaimedAgentSessionId("same-conv"));
        assertTrue(manager.activeSessionFor("same-conv").isEmpty());
    }

    @Test
    void reclaimPersistsTheRebind() {
        Repository repository = someRepository();
        ManagedAgentSession session = sessionTracking(repository, "Session 1", "old-conv");
        InMemoryStateRepository stateRepository = new InMemoryStateRepository(List.of(session));
        SessionManager manager = newManager(stateRepository);

        manager.applyAgentReclaim(session.id(), "new-conv");
        flushState(stateRepository);

        assertEquals(Optional.of("new-conv"),
                stateRepository.savedState().sessions().get(0).agentSessionId());
    }

    @Test
    void reclaimRefusesWhenAnotherSessionTracksTheNewId() {
        Repository repository = someRepository();
        ManagedAgentSession first = sessionTracking(repository, "Session 1", "old-conv");
        ManagedAgentSession second = sessionTracking(repository, "Session 2", "claimed-conv");
        SessionManager manager = newManager(new InMemoryStateRepository(List.of(first, second)));

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> manager.applyAgentReclaim(first.id(), "claimed-conv"));

        assertTrue(refused.getMessage().contains("claimed-conv"), refused.getMessage());
        // The first session is untouched: the persisted clash check ran inside
        // the state lock, so a refused rebind left the old id in place.
        assertEquals(Optional.of("old-conv"),
                currentSession(manager, first.id()).orElseThrow().agentSessionId());
    }

    @Test
    void reclaimRefusesWhenAnotherSessionHoldsTheNewIdOpen() {
        // The persisted check alone is not enough: a surface that lingers past
        // its persisted binding would let a second tab open the same
        // conversation. The active-registry pre-check catches that before the
        // state write, so a refused rebind never mutates persistence.
        Repository repository = someRepository();
        ManagedAgentSession first = sessionTracking(repository, "Session 1", "old-conv");
        ManagedAgentSession second = sessionTracking(repository, "Session 2", "other-old");
        SessionManager manager = newManager(new InMemoryStateRepository(List.of(first, second)));
        // Put `new-conv` in the active registry for `second` the legitimate way
        // (a reclaim), then try to reclaim the same id for `first`.
        manager.applyAgentReclaim(second.id(), "new-conv");

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> manager.applyAgentReclaim(first.id(), "new-conv"));

        assertTrue(refused.getMessage().contains("already open"), refused.getMessage());
        assertEquals(Optional.of("old-conv"),
                currentSession(manager, first.id()).orElseThrow().agentSessionId());
    }

    @Test
    void reclaimForAnUnknownSessionThrows() {
        SessionManager manager = newManager(new InMemoryStateRepository(List.of()));
        assertThrows(UnknownSessionException.class,
                () -> manager.applyAgentReclaim(ManagedSessionId.newId(), "new-conv"));
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

    @Test
    void aSuccessorInheritsTheOutgoingSessionsIdentityAndCheckout() {
        SessionManager manager = newManager(new InMemoryStateRepository(List.of()));
        Repository repository = someRepository();
        ManagedAgentSession outgoing = manager
                .prepareWorktreeSession(repository, "Rework the rail", Path.of("/repo/wt"), true,
                        AgentKind.CLAUDE)
                .withNamePinned(true);

        ManagedAgentSession successor =
                manager.prepareSuccessorSession(repository, outgoing, AgentKind.CODEX);

        assertEquals("Rework the rail", successor.displayName());
        assertTrue(successor.namePinned());
        assertEquals(Optional.of(Path.of("/repo/wt")), successor.worktreeRoot());
        assertEquals(Path.of("/repo/wt"), successor.workingDirectory());
        assertEquals(AgentKind.CODEX, successor.agentKind());
        assertTrue(successor.branchCreatedHere());
    }

    @Test
    void aSuccessorNeverClaimsToHaveCreatedTheBranch() {
        // It did not: the branch is the outgoing session's, and a successor
        // that claims otherwise would offer to delete a branch drydock does
        // not own.
        SessionManager manager = newManager(new InMemoryStateRepository(List.of()));
        Repository repository = someRepository();
        ManagedAgentSession outgoing = manager.prepareWorktreeSession(
                repository, "Rework the rail", Path.of("/repo/wt"), false, AgentKind.CLAUDE);

        ManagedAgentSession successor =
                manager.prepareSuccessorSession(repository, outgoing, AgentKind.CODEX);

        assertFalse(successor.branchCreatedHere());
    }

    @Test
    void aSuccessorOfAMainCheckoutSessionStaysOnTheMainCheckout() {
        SessionManager manager = newManager(new InMemoryStateRepository(List.of()));
        Repository repository = someRepository();
        ManagedAgentSession outgoing = manager.prepareSession(repository, AgentKind.CLAUDE);

        ManagedAgentSession successor =
                manager.prepareSuccessorSession(repository, outgoing, AgentKind.CODEX);

        assertEquals(Optional.empty(), successor.worktreeRoot());
        assertEquals(repository.root(), successor.workingDirectory());
    }

    @Test
    void aSuccessorInheritsEvalModeSoGatedWorkStaysOnTheEvalAccount() {
        SessionManager manager = newManager(new InMemoryStateRepository(List.of()));
        Repository repository = someRepository();
        ManagedAgentSession outgoing = manager
                .prepareSession(repository, AgentKind.CLAUDE).withEvalMode(true);

        assertTrue(manager.prepareSuccessorSession(repository, outgoing, AgentKind.CODEX).evalMode());
    }

    @Test
    void aSuccessorRecordsNoLineageBecauseItsParentIsAboutToBeDeleted() {
        SessionManager manager = newManager(new InMemoryStateRepository(List.of()));
        Repository repository = someRepository();
        ManagedAgentSession outgoing = manager.prepareSession(repository, AgentKind.CLAUDE);

        assertEquals(Optional.empty(),
                manager.prepareSuccessorSession(repository, outgoing, AgentKind.CODEX).forkedFrom());
    }

    @Test
    void aSuccessorGetsItsOwnId() {
        SessionManager manager = newManager(new InMemoryStateRepository(List.of()));
        Repository repository = someRepository();
        ManagedAgentSession outgoing = manager.prepareSession(repository, AgentKind.CLAUDE);

        assertNotEquals(outgoing.id(),
                manager.prepareSuccessorSession(repository, outgoing, AgentKind.CODEX).id());
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
