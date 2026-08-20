package app.drydock.app;

import app.drydock.domain.ApplicationState;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SshRemote;
import app.drydock.domain.UiTheme;
import app.drydock.domain.WorkspaceUiState;
import app.drydock.git.GitExecutableLocator;
import app.drydock.git.GitStatusService;
import app.drydock.git.NotAGitRepositoryException;
import app.drydock.review.SessionReviewScopes;
import app.drydock.state.ApplicationStateRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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

    /**
     * Fakes the {@code ssh} executable (mirrors {@code
     * GitStatusServiceRemoteTest}), so remote resolution never depends on an
     * actually-reachable host.
     */
    private Path fakeSsh(String script) throws IOException {
        Path fake = tempDir.resolve("fake-ssh-" + System.nanoTime());
        Files.writeString(fake, "#!/bin/sh\n" + script);
        Files.setPosixFilePermissions(fake, PosixFilePermissions.fromString("rwxr-xr-x"));
        return fake;
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
    void addRemoteRepositoryRegistersWithResolvedRootAndRejectsDuplicates() throws Exception {
        Path fake = fakeSsh("printf '/srv/app\\n'");
        try (GitStatusService remoteGitStatusService = new GitStatusService(new GitExecutableLocator(), fake.toString())) {
            RepositoryManager remoteManager = new RepositoryManager(stateRepository, remoteGitStatusService);
            SshRemote candidate = new SshRemote("user@h", "/srv/app/subdir");

            Repository added = remoteManager.addRemoteRepository(candidate).join();

            assertTrue(added.isRemote());
            assertEquals(new SshRemote("user@h", "/srv/app"), added.remote());
            assertEquals(added.remote().placeholderRoot(), added.root());
            assertEquals("app", added.displayName());

            CompletionException thrown = assertThrows(CompletionException.class,
                    () -> remoteManager.addRemoteRepository(new SshRemote("user@h", "/srv/app")).join());
            assertInstanceOf(DuplicateRepositoryException.class, thrown.getCause());
        }
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

    @Test
    void updateUiFontSizePersistsWithoutDisturbingTheTheme() {
        manager.updateTheme(UiTheme.LIGHT);

        manager.updateUiFontSize(15.0);

        assertEquals(15.0, manager.state().ui().uiFontSize());
        assertEquals(UiTheme.LIGHT, manager.state().ui().theme());
    }

    @Test
    void updateTerminalFontSizePersistsWithoutDisturbingTheInterfaceSize() {
        manager.updateUiFontSize(15.0);

        manager.updateTerminalFontSize(11.0);

        assertEquals(11.0, manager.state().ui().terminalFontSize());
        assertEquals(15.0, manager.state().ui().uiFontSize());
    }

    @Test
    void updateOpenSessionsAndSelectedSessionPersist() {
        ManagedSessionId first = ManagedSessionId.newId();
        ManagedSessionId second = ManagedSessionId.newId();

        manager.updateOpenSessions(List.of(first, second));
        manager.updateSelectedSession(Optional.of(first));
        flushState();

        assertEquals(List.of(first, second), stateRepository.savedState().ui().openSessionIds());
        assertEquals(Optional.of(first), stateRepository.savedState().ui().selectedSessionId());
    }

    @Test
    void updateOpenSessionsLeavesOtherUiFieldsAlone() {
        manager.updateTheme(UiTheme.LIGHT);
        manager.updateSidebarWidth(400.0);
        ManagedSessionId id = ManagedSessionId.newId();

        manager.updateOpenSessions(List.of(id));
        flushState();

        WorkspaceUiState ui = stateRepository.savedState().ui();
        assertEquals(UiTheme.LIGHT, ui.theme());
        assertEquals(400.0, ui.sidebarWidth());
        assertEquals(List.of(id), ui.openSessionIds());
    }

    @Test
    void updateSelectedSessionLeavesOtherUiFieldsAlone() {
        manager.updateTheme(UiTheme.LIGHT);
        ManagedSessionId id = ManagedSessionId.newId();

        manager.updateSelectedSession(Optional.of(id));
        flushState();

        assertEquals(UiTheme.LIGHT, stateRepository.savedState().ui().theme());
        assertEquals(Optional.of(id), stateRepository.savedState().ui().selectedSessionId());
    }

    // ---- Review scope choices (per session, cosmetic, single-writer) --------

    /**
     * The per-session Review chip is persisted and read back. Both halves in
     * one test because they are one contract: what {@code MainWorkspace} reads
     * when a gesture names no chip (⌘4, the sub-tab button) is exactly what an
     * earlier gesture, or the switcher itself, wrote here.
     */
    @Test
    void aSessionsReviewScopeChoiceIsPersistedAndReadBack() {
        ManagedSessionId session = ManagedSessionId.newId();

        assertEquals(SessionReviewScopes.Choice.LOCAL,
                manager.state().ui().reviewScopeChoices()
                        .getOrDefault(session, SessionReviewScopes.Choice.LOCAL),
                "a session that has never chosen reads as LOCAL");

        manager.updateReviewScopeChoice(session, SessionReviewScopes.Choice.PULL_REQUEST);

        assertEquals(SessionReviewScopes.Choice.PULL_REQUEST,
                manager.state().ui().reviewScopeChoices().get(session));
        flushState();
        assertEquals(SessionReviewScopes.Choice.PULL_REQUEST,
                stateRepository.savedState().ui().reviewScopeChoices().get(session),
                "the choice reached the state file, not just the in-memory copy");
    }

    /** One session's chip must not disturb another's -- the map is read-modify-written, not replaced. */
    @Test
    void oneSessionsChoiceLeavesTheOthersAlone() {
        ManagedSessionId first = ManagedSessionId.newId();
        ManagedSessionId second = ManagedSessionId.newId();

        manager.updateReviewScopeChoice(first, SessionReviewScopes.Choice.PULL_REQUEST);
        manager.updateReviewScopeChoice(second, SessionReviewScopes.Choice.LOCAL);

        assertEquals(SessionReviewScopes.Choice.PULL_REQUEST,
                manager.state().ui().reviewScopeChoices().get(first));
        assertEquals(SessionReviewScopes.Choice.LOCAL,
                manager.state().ui().reviewScopeChoices().get(second));
    }

    /**
     * The rest of the workspace UI state survives a chip write. The map is
     * rebuilt on every call, so a {@code withReviewScopeChoices} that dropped
     * a sibling field would show up here rather than as a lost tab strip on
     * somebody's next launch.
     */
    @Test
    void writingAChoiceKeepsTheRestOfTheUiState() {
        ManagedSessionId session = ManagedSessionId.newId();
        manager.updateTheme(UiTheme.LIGHT);
        manager.updateOpenSessions(List.of(session));

        manager.updateReviewScopeChoice(session, SessionReviewScopes.Choice.PULL_REQUEST);

        flushState();
        WorkspaceUiState ui = stateRepository.savedState().ui();
        assertEquals(UiTheme.LIGHT, ui.theme());
        assertEquals(List.of(session), ui.openSessionIds());
        assertEquals(SessionReviewScopes.Choice.PULL_REQUEST, ui.reviewScopeChoices().get(session));
    }

    /** Re-writing the choice a session already has is not a state write at all. */
    @Test
    void rewritingTheSameChoiceDoesNotSaveAgain() {
        ManagedSessionId session = ManagedSessionId.newId();
        manager.updateReviewScopeChoice(session, SessionReviewScopes.Choice.PULL_REQUEST);
        flushState();
        int savesBefore = stateRepository.saveCount();

        manager.updateReviewScopeChoice(session, SessionReviewScopes.Choice.PULL_REQUEST);
        flushState();

        assertEquals(savesBefore, stateRepository.saveCount());
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
