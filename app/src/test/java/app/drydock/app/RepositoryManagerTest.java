package app.drydock.app;

import app.drydock.domain.ApplicationState;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.SessionWorkspace;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.RepositorySettings;
import app.drydock.domain.SshRemote;
import app.drydock.domain.UiTheme;
import app.drydock.domain.WorkspaceUiState;
import app.drydock.git.GitExecutableLocator;
import app.drydock.git.GitStatusService;
import app.drydock.git.NotAGitRepositoryException;
import app.drydock.git.WorktreeService;
import app.drydock.projects.SharedProject;
import app.drydock.projects.SharedProjectsFile;
import app.drydock.projects.SharedProjectsSeed;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositoryManagerTest {

    @TempDir
    Path tempDir;

    private GitStatusService gitStatusService;
    private WorktreeService worktreeService;
    private InMemoryStateRepository stateRepository;
    private SharedProjectsFile sharedFile;
    private RepositoryManager manager;

    @BeforeEach
    void setUp() {
        gitStatusService = new GitStatusService();
        worktreeService = new WorktreeService();
        stateRepository = new InMemoryStateRepository();
        sharedFile = new SharedProjectsFile(tempDir.resolve("shared").resolve("projects.json"));
        manager = newManager(stateRepository, gitStatusService);
    }

    private RepositoryManager newManager(ApplicationStateRepository state, GitStatusService git) {
        return new RepositoryManager(state, git, sharedFile, SharedProjectsSeed.mainCheckoutVia(worktreeService));
    }

    @AfterEach
    void tearDown() {
        manager.stop();
        worktreeService.close();
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
            RepositoryManager remoteManager = newManager(stateRepository, remoteGitStatusService);
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

    // ---- the shared registered-projects list ---------------------------------

    private Path initGitRepoWithCommit(String name) throws IOException, InterruptedException {
        Path dir = initGitRepo(name);
        run(dir, "git", "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "--allow-empty", "-m", "init");
        return dir;
    }

    private static Repository localRepository(Path root) {
        Instant now = Instant.now();
        return new Repository(RepositoryId.newId(), root, root.getFileName().toString(), now, now,
                RepositorySettings.DEFAULT);
    }

    private List<RepositoryId> sharedIds() {
        return sharedFile.read().projects().stream().map(SharedProject::id).toList();
    }

    @Test
    void startSeedsSharedFileFromLocalListWhenAbsent() throws Exception {
        Repository existing = localRepository(initGitRepo("seeded").toRealPath());
        ApplicationStateStore.forRepository(stateRepository)
                .update(state -> state.withRepositories(List.of(existing)));

        manager.start().join();

        assertEquals(List.of(existing.id()), sharedIds());
        assertEquals(List.of(existing.id()), manager.repositories().stream().map(Repository::id).toList());
    }

    @Test
    void startDoesNotSeedWhenSharedFilePresent() throws Exception {
        Repository local = localRepository(initGitRepo("local-only").toRealPath());
        ApplicationStateStore.forRepository(stateRepository)
                .update(state -> state.withRepositories(List.of(local)));
        SharedProject other = SharedProject.local(RepositoryId.newId(), initGitRepo("other").toRealPath(), "other",
                Instant.now());
        sharedFile.update(list -> List.of(other));

        manager.start().join();

        assertEquals(List.of(other.id()), sharedIds());
        assertEquals(List.of(other.id()), manager.repositories().stream().map(Repository::id).toList());
    }

    @Test
    void addWritesSharedAndLocalWithSameId() throws Exception {
        Path repo = initGitRepo("added").toRealPath();

        Repository added = manager.addRepository(repo).join();

        assertEquals(List.of(added.id()), sharedIds());
        assertEquals(List.of(added), manager.repositories());
    }

    @Test
    void addingWorktreeFolderRegistersItsMainCheckout() throws Exception {
        Path main = initGitRepoWithCommit("main").toRealPath();
        Path worktree = tempDir.resolve("wt");
        run(main, "git", "worktree", "add", "-q", "-b", "feature", worktree.toString());

        Repository added = manager.addRepository(worktree).join();

        assertEquals(main, added.root());
        assertEquals("main", added.displayName());
    }

    @Test
    void reAddReusesOrphanedSessionIdSoSessionsReattach() throws Exception {
        Path repo = initGitRepo("again").toRealPath();
        Repository first = manager.addRepository(repo).join();
        ManagedAgentSession session = SessionManagerTest.newSessionFixture().toBuilder()
                .repositoryId(first.id())
                .workspace(new SessionWorkspace(repo, Optional.empty(), true))
                .build();
        ApplicationStateStore.forRepository(stateRepository)
                .update(state -> state.withSessions(List.of(session)));

        manager.removeRepository(first.id()).join();
        Repository second = manager.addRepository(repo).join();

        assertEquals(first.id(), second.id());
    }

    @Test
    void removeDropsFromSharedAndVisibleList() throws Exception {
        Repository added = manager.addRepository(initGitRepo("gone").toRealPath()).join();

        manager.removeRepository(added.id()).join();

        assertEquals(List.of(), sharedIds());
        assertEquals(List.of(), manager.repositories());
    }

    @Test
    void externalAddAppearsAndNotifiesOnce() throws Exception {
        manager.start().join();
        AtomicInteger notified = new AtomicInteger();
        manager.addChangeListener(notified::incrementAndGet);
        SharedProject fromIde = SharedProject.local(RepositoryId.newId(), initGitRepo("from-ide").toRealPath(),
                "from-ide", Instant.now());
        new SharedProjectsFile(sharedFile.file()).update(list -> List.of(fromIde));

        manager.reconcile();
        manager.reconcile();

        assertEquals(List.of(fromIde.id()), manager.repositories().stream().map(Repository::id).toList());
        assertEquals(1, notified.get());
    }

    @Test
    void externalRemoveHidesAndNotifies() throws Exception {
        Repository added = manager.addRepository(initGitRepo("ext-removed").toRealPath()).join();
        AtomicInteger notified = new AtomicInteger();
        manager.addChangeListener(notified::incrementAndGet);
        new SharedProjectsFile(sharedFile.file()).update(list -> List.of());

        manager.reconcile();

        assertFalse(manager.repositories().stream().anyMatch(repository -> repository.id().equals(added.id())));
        assertEquals(1, notified.get());
    }

    @Test
    void malformedSharedFileKeepsLastGoodListAndRefusesAdd() throws Exception {
        Repository added = manager.addRepository(initGitRepo("kept").toRealPath()).join();
        Files.writeString(sharedFile.file(), "{broken");

        manager.reconcile();
        CompletionException thrown = assertThrows(CompletionException.class,
                () -> manager.addRepository(initGitRepo("refused")).join());

        assertEquals(List.of(added), manager.repositories());
        assertTrue(thrown.getMessage().contains("projects.json"), thrown.getMessage());
    }

    @Test
    void aSharedFileItCannotFullyReadIsNotAppliedAndIsReported() throws Exception {
        Repository kept = manager.addRepository(initGitRepo("kept-too").toRealPath()).join();
        AtomicInteger notified = new AtomicInteger();
        manager.addChangeListener(notified::incrementAndGet);
        Files.writeString(sharedFile.file(), """
                {"schemaVersion":1,"projects":[{"id":"%s","root":"/w/no-added-at","displayName":"x"}]}"""
                .formatted(RepositoryId.newId()));

        manager.reconcile();

        assertEquals(List.of(kept), manager.repositories(), "an unreadable entry must not empty the list");
        assertTrue(manager.sharedListProblem().isPresent());
        assertEquals(1, notified.get(), "the sidebar learns there is a problem to show");
    }

    @Test
    void aDeletedSharedFileIsReseededFromThisAppsList() throws Exception {
        Repository first = manager.addRepository(initGitRepo("survives-1").toRealPath()).join();
        Repository second = manager.addRepository(initGitRepo("survives-2").toRealPath()).join();
        Files.delete(sharedFile.file());

        manager.reconcile();

        assertEquals(List.of(first.id(), second.id()), sharedIds());
        assertEquals(List.of(first, second), manager.repositories());
    }

    private ManagedAgentSession sessionIn(RepositoryId repositoryId, Path workingDirectory) {
        return SessionManagerTest.newSessionFixture().toBuilder()
                .repositoryId(repositoryId)
                .workspace(new SessionWorkspace(workingDirectory, Optional.empty(), true))
                .build();
    }

    private RepositoryId repositoryOf(ManagedAgentSession session) {
        return ApplicationStateStore.forRepository(stateRepository).state().sessions().stream()
                .filter(s -> s.id().equals(session.id())).findFirst().orElseThrow().repositoryId();
    }

    @Test
    void seedingFoldsAWorktreeEntryAndItsSessionsFollowTheMainCheckout() throws Exception {
        Path main = initGitRepoWithCommit("seed-main").toRealPath();
        Path worktree = tempDir.resolve("seed-wt");
        run(main, "git", "worktree", "add", "-q", "-b", "feature", worktree.toString());
        Repository mainRepo = localRepository(main);
        Repository worktreeRepo = localRepository(worktree.toRealPath());
        ManagedAgentSession inWorktree = sessionIn(worktreeRepo.id(), worktree.toRealPath());
        ApplicationStateStore.forRepository(stateRepository).update(state -> state
                .withRepositories(List.of(mainRepo, worktreeRepo))
                .withSessions(List.of(inWorktree)));

        manager.start().join();

        assertEquals(List.of(mainRepo.id()), sharedIds());
        assertEquals(mainRepo.id(), repositoryOf(inWorktree), "the session must not be orphaned by the fold");
    }

    @Test
    void aProjectReAddedByTheIdeBringsTheAppsSessionsBack() throws Exception {
        Path repo = initGitRepo("re-added").toRealPath();
        Repository added = manager.addRepository(repo).join();
        ManagedAgentSession session = sessionIn(added.id(), repo);
        ApplicationStateStore.forRepository(stateRepository).update(state -> state.withSessions(List.of(session)));
        manager.removeRepository(added.id()).join();
        SharedProject fromIde = SharedProject.local(RepositoryId.newId(), repo, "re-added", Instant.now());
        new SharedProjectsFile(sharedFile.file()).update(list -> List.of(fromIde));

        manager.reconcile();

        assertEquals(fromIde.id(), repositoryOf(session));
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
