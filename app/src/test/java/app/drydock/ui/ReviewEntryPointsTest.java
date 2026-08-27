package app.drydock.ui;

import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.app.RepositoryManager;
import app.drydock.app.SessionManager;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ApplicationState;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PrLink;
import app.drydock.domain.PrState;
import app.drydock.domain.Repository;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.git.GhCliService;
import app.drydock.git.GitStatusService;
import app.drydock.git.WorktreeService;
import app.drydock.review.RepositoryPullRequests;
import app.drydock.review.SessionReviewScopes;
import app.drydock.state.ApplicationStateRepository;
import app.drydock.ui.model.WorkspaceViewModel;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 11: the gestures that invoke review all land on one destination --
 * the session's own Review sub-tab -- and each carries the scope choice its
 * row was showing.
 *
 * <p>The assertions stop at the {@link WorkspaceNavigator} boundary on
 * purpose. What a gesture <em>means</em> ("this session, its pull request")
 * is the part that can be wrong per gesture; what the workspace then does
 * with it is one shared path, exercised by {@code
 * OpenSessionTabReviewSubTabTest}. The chip and badge are driven through
 * {@code diagClickPrChip}/{@code diagClickFindingsBadge}, which fire the
 * row's real handler rather than moving a pointer: per this project's
 * notes, synthetic pointer input in a headless run reports success without
 * reaching the app, so a click-robot test here would assert nothing.</p>
 */
class ReviewEntryPointsTest extends ApplicationTest {

    private static final int PR_NUMBER = 42;

    private Path repoRoot;
    private Path worktreeRoot;
    private RepositoryManager repositoryManager;
    private GitStatusService gitStatusService;
    private WorktreeService worktreeService;
    private WorkspaceViewModel viewModel;
    private RepositorySidebar sidebar;
    private RecordingNavigator recorder;
    private Repository repository;
    private ManagedAgentSession session;

    @Override
    public void start(Stage stage) throws Exception {
        repoRoot = Files.createTempDirectory("drydock-review-entry");
        git(repoRoot, "init", "--initial-branch=main");
        git(repoRoot, "config", "user.email", "test@example.com");
        git(repoRoot, "config", "user.name", "Test");
        Files.writeString(repoRoot.resolve("README.md"), "hello\n");
        git(repoRoot, "add", ".");
        git(repoRoot, "commit", "-m", "initial");
        worktreeRoot = Files.createTempDirectory("drydock-review-entry-wt");

        gitStatusService = new GitStatusService();
        worktreeService = new WorktreeService();
        repositoryManager = new RepositoryManager(new InMemoryStateRepository(), gitStatusService);
        AgentRegistry agentRegistry = new AgentRegistry(List.of(), null);
        SessionManager sessionManager = new SessionManager(new InMemoryStateRepository(), agentRegistry);
        RepositoryPullRequests repositoryPullRequests = new RepositoryPullRequests(
                root -> CompletableFuture.completedFuture(new GhCliService.PullRequestListing.Unsupported()));
        viewModel = new WorkspaceViewModel();
        recorder = new RecordingNavigator();

        sidebar = new RepositorySidebar(repositoryManager, gitStatusService, worktreeService, repositoryPullRequests,
                sessionManager, agentRegistry, recorder, viewModel);
        // The ◨n badge only renders when some reviewer has actually run;
        // without a count the row has no badge to click.
        sidebar.setOpenFindingsAt(path -> Optional.of(3));

        stage.setScene(new Scene(new StackPane(sidebar), 420, 640));
        stage.show();

        repository = repositoryManager.addRepository(repoRoot).get(20, TimeUnit.SECONDS);
        session = sessionOnWorktree(repository, PrLink.of(PrState.OPEN, Optional.of(PR_NUMBER)));
        interact(() -> viewModel.setSessions(List.of(session)));
    }

    @AfterEach
    void tearDown() throws IOException {
        gitStatusService.close();
        worktreeService.close();
        deleteTree(repoRoot);
        deleteTree(worktreeRoot);
    }

    // ---- the four gestures ---------------------------------------------------

    @Test
    void thePullRequestChipAsksForThePullRequestScope() throws Exception {
        Node row = awaitSessionRow();

        recorder.reset();
        interact(() -> assertTrue(RepositorySidebar.diagClickPrChip(row), "the row has no PR chip to click"));

        assertEquals(SessionReviewScopes.Choice.PULL_REQUEST, recorder.lastChoice());
        assertEquals(session.id(), recorder.lastSessionId(),
                "the chip must review ITS row's session, not whatever tab happens to be selected");
    }

    @Test
    void theFindingsBadgeAsksForTheLocalScope() throws Exception {
        Node row = awaitSessionRow();

        recorder.reset();
        interact(() -> assertTrue(RepositorySidebar.diagClickFindingsBadge(row),
                "the row has no ◨n findings badge to click"));

        assertEquals(SessionReviewScopes.Choice.LOCAL, recorder.lastChoice());
        assertEquals(session.id(), recorder.lastSessionId());
    }

    @Test
    void theContextMenuOffersAPullRequestEntryOnlyWhenThereIsOne() {
        assertEquals(List.of("Review ▸ Local changes"),
                RepositorySidebar.reviewMenuLabels(Optional.empty()));
        assertEquals(List.of("Review ▸ Local changes", "Review ▸ PR #42"),
                RepositorySidebar.reviewMenuLabels(Optional.of(42)));
    }

    /**
     * The menu is cached per session, so its PR entry has to be re-read from
     * the live session every time it opens: a PR opened (or merged) after the
     * first right-click must not leave the cached menu offering the wrong
     * thing forever.
     */
    @Test
    void theSessionMenusReviewEntriesTrackTheLiveSession() throws Exception {
        awaitSessionRow();

        List<MenuItem> withPr = reviewMenuItems();
        assertEquals(List.of("Review ▸ Local changes", "Review ▸ PR #42"),
                withPr.stream().map(MenuItem::getText).toList());

        ManagedAgentSession noPr = session.toBuilder().pr(PrLink.none()).build();
        interact(() -> viewModel.setSessions(List.of(noPr)));

        assertEquals(List.of("Review ▸ Local changes"),
                reviewMenuItems().stream().map(MenuItem::getText).toList());
    }

    @Test
    void theSessionMenusLocalEntryReviewsThatSessionsLocalChanges() throws Exception {
        awaitSessionRow();

        recorder.reset();
        interact(() -> reviewMenuItems().get(0).fire());

        assertEquals(SessionReviewScopes.Choice.LOCAL, recorder.lastChoice());
        assertEquals(session.id(), recorder.lastSessionId());
    }

    @Test
    void theSessionMenusPullRequestEntryReviewsThePullRequest() throws Exception {
        awaitSessionRow();

        recorder.reset();
        interact(() -> reviewMenuItems().get(1).fire());

        assertEquals(SessionReviewScopes.Choice.PULL_REQUEST, recorder.lastChoice());
        assertEquals(session.id(), recorder.lastSessionId());
    }

    /**
     * A discovered worktree with no session yet cannot be sent to a session's
     * sub-tab: there is no session. It goes the other way round -- start one,
     * then land on its board -- which is a different navigator call, and the
     * one place the two must not be confused.
     */
    @Test
    void anUnopenedWorktreeStartsASessionFirst() throws Exception {
        interact(() -> viewModel.setSessions(List.of()));
        Node row = awaitRow(".worktree-unopened-row");

        recorder.reset();
        interact(() -> assertTrue(RepositorySidebar.diagClickFindingsBadge(row),
                "an unopened worktree row also carries the ◨n badge"));

        assertNull(recorder.lastSessionId(), "there is no session to show a sub-tab of");
        assertEquals(SessionReviewScopes.Choice.LOCAL, recorder.lastChoice());
        assertTrue(recorder.lastWorktree().mainCheckout(),
                "the main checkout is the only worktree this repository has");
        // Compared by name, not by path: `git worktree list` reports macOS's
        // real /private/var path for a /var temp dir, and that reported path
        // is exactly what must travel untouched into the scope's identity --
        // "fixing" it here would be the canonicalisation this flow must never
        // do.
        assertEquals(repoRoot.getFileName(), recorder.lastWorktree().path().getFileName());
    }

    // ---- lookups -------------------------------------------------------------

    private Node awaitSessionRow() throws InterruptedException {
        return awaitRow(".session-row");
    }

    private Node awaitRow(String selector) throws InterruptedException {
        AtomicReference<Node> found = new AtomicReference<>();
        awaitCondition(() -> {
            interact(() -> found.set(sidebar.lookup(selector)));
            return found.get() != null;
        }, "waiting for a " + selector + " to render");
        Node row = found.get();
        assertNotNull(row);
        return row;
    }

    /** The {@code Review ▸} entries of the cached session menu, read after firing its onShowing. */
    private List<MenuItem> reviewMenuItems() {
        AtomicReference<List<MenuItem>> items = new AtomicReference<>();
        interact(() -> items.set(sidebar.diagReviewMenuItems(session.id())));
        return items.get();
    }

    private void awaitCondition(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("timed out " + what);
    }

    // ---- fixtures ------------------------------------------------------------

    private ManagedAgentSession sessionOnWorktree(Repository repo, PrLink pr) {
        Instant now = Instant.now();
        return new ManagedAgentSession(ManagedSessionId.newId(), repo.id(), "review-me",
                AgentBinding.unlaunched(AgentKind.CLAUDE),
                new SessionWorkspace(worktreeRoot, Optional.of(worktreeRoot), false),
                SessionStatus.RUNNING, now, now, Optional.empty(), pr, false, Optional.empty());
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A temp tree that outlives the run is not a failure.
                }
            });
        }
    }

    private static void git(Path workingDirectory, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", arguments) + " failed: " + output);
        }
    }

    /** Records only what this task decides: which session, and which scope. */
    private static final class RecordingNavigator implements WorkspaceNavigator {
        private ManagedSessionId lastSessionId;
        private SessionReviewScopes.Choice lastChoice;
        private WorktreeService.Worktree lastWorktree;

        void reset() {
            lastSessionId = null;
            lastChoice = null;
            lastWorktree = null;
        }

        ManagedSessionId lastSessionId() {
            return lastSessionId;
        }

        SessionReviewScopes.Choice lastChoice() {
            return lastChoice;
        }

        WorktreeService.Worktree lastWorktree() {
            return lastWorktree;
        }

        @Override
        public void showReviewForSession(ManagedSessionId sessionId, SessionReviewScopes.Choice choice) {
            lastSessionId = sessionId;
            lastChoice = choice;
        }

        @Override
        public void startReviewForWorktree(Repository repository, WorktreeService.Worktree worktree,
                                           SessionReviewScopes.Choice choice) {
            lastWorktree = worktree;
            lastChoice = choice;
        }

        @Override
        public void startReviewForPullRequest(Repository repository, GhCliService.OpenPullRequest pullRequest) {
        }

        @Override
        public void resumeSession(ManagedAgentSession session) { }

        @Override
        public CompletableFuture<Void> closeSession(ManagedSessionId sessionId) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void noteSessionDeleted(ManagedSessionId sessionId) { }

        @Override
        public void openNewSession(Repository repository) { }

        @Override
        public void promptStartWorktreeSession(Repository repository, WorktreeService.Worktree worktree) { }

        @Override
        public void startParallelSession(ManagedAgentSession session) { }
        public void startParallelWorktreeSession(ManagedAgentSession session) { }

        @Override
        public void showUnopenedWorktree(Repository repository, WorktreeService.Worktree worktree) { }

        @Override
        public void promptRenameSession(ManagedAgentSession session) { }

        @Override
        public Optional<ManagedSessionId> activeSessionId() {
            return Optional.empty();
        }
    }

    private static final class InMemoryStateRepository implements ApplicationStateRepository {
        private volatile ApplicationState state = ApplicationState.empty();

        @Override
        public ApplicationState load() {
            return state;
        }

        @Override
        public void save(ApplicationState newState) {
            state = newState;
        }
    }
}
