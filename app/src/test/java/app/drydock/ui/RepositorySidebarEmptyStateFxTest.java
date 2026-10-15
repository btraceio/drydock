package app.drydock.ui;

import app.drydock.agent.api.AgentRegistry;
import app.drydock.app.RepositoryManager;
import app.drydock.app.SessionManager;
import app.drydock.domain.ApplicationState;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.Repository;
import app.drydock.git.GhCliService;
import app.drydock.git.GitStatusService;
import app.drydock.git.WorktreeService;
import app.drydock.review.RepositoryPullRequests;
import app.drydock.review.SessionReviewScopes;
import app.drydock.state.ApplicationStateRepository;
import app.drydock.testing.FxSync;
import app.drydock.testing.FxTest;
import app.drydock.ui.model.WorkspaceViewModel;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sidebar's body swaps one node in the tree's slot: the TreeView, the
 * "Nothing matches your filters" VBox ({@code emptyState}), and the
 * "Select a repository" Label ({@code selectRepoPlaceholder}). Every
 * transition between the three must land, whatever node currently occupies
 * the slot -- the released build crashed with
 * {@code IndexOutOfBoundsException: Index -1} because
 * {@code showEmptyState} called {@code getChildren().indexOf(tree)} while
 * the empty-state VBox was sitting in the tree's place, i.e. the
 * empty-state → placeholder transition had no branch at all.
 *
 * <p>These tests walk the full transition matrix. The swap happens inside
 * {@code Platform.runLater} after the 150 ms filter debounce, so a throw on
 * the FX thread would surface as an uncaught-exception log, not a test
 * failure -- each step instead asserts the expected node actually ended up
 * in the scene, which fails when the swap threw.</p>
 */
class RepositorySidebarEmptyStateFxTest extends FxTest {

    private Path repoRoot;
    private GitStatusService gitStatusService;
    private WorktreeService worktreeService;
    private RepositoryManager repositoryManager;
    private RepositorySidebar sidebar;
    private Repository repository;

    @Override
    public void start(Stage stage) throws Exception {
        repoRoot = Files.createTempDirectory("drydock-empty-state");
        git(repoRoot, "init", "--initial-branch=main");
        git(repoRoot, "config", "user.email", "test@example.com");
        git(repoRoot, "config", "user.name", "Test");
        Files.writeString(repoRoot.resolve("README.md"), "hello\n");
        git(repoRoot, "add", ".");
        git(repoRoot, "commit", "-m", "initial");

        gitStatusService = new GitStatusService();
        worktreeService = new WorktreeService();
        repositoryManager = new RepositoryManager(new InMemoryStateRepository(), gitStatusService);
        AgentRegistry agentRegistry = new AgentRegistry(List.of(), null);
        SessionManager sessionManager = new SessionManager(new InMemoryStateRepository(), agentRegistry);
        // No gh in the test environment: the Absent outcome keeps the PR
        // group out of the tree, which is what these transitions assume.
        RepositoryPullRequests repositoryPullRequests =
                new RepositoryPullRequests(root ->
                        CompletableFuture.completedFuture(new GhCliService.PullRequestListing.Unsupported()));
        WorkspaceViewModel viewModel = new WorkspaceViewModel();

        sidebar = new RepositorySidebar(repositoryManager, gitStatusService, worktreeService,
                repositoryPullRequests, sessionManager, agentRegistry, new NoopNavigator(), viewModel);

        TestStages.show(stage, new Scene(new StackPane(sidebar), 360, 640));

        repository = repositoryManager.addRepository(repoRoot)
                .get(20, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() throws IOException, InterruptedException {
        gitStatusService.close();
        worktreeService.close();
        deleteTree(repoRoot);
    }

    /**
     * Deletes the throwaway repo. On Windows an in-flight child process
     * whose working directory is inside the tree (an async {@code git
     * status} outliving {@link GitStatusService#close()}, which shuts its
     * executor down without awaiting) keeps the tree busy and denies
     * deletion, so the walk retries briefly before giving up.
     */
    private static void deleteTree(Path root) throws IOException, InterruptedException {
        if (!Files.exists(root)) {
            return;
        }
        IOException lastFailure = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            try (var paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        // Git writes loose objects read-only; on Windows a
                        // read-only attribute denies deletion outright, where
                        // POSIX only needs write permission on the directory.
                        File f = p.toFile();
                        if (f.isFile() && !f.canWrite()) {
                            f.setWritable(true);
                        }
                        Files.delete(p);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
                return;
            } catch (RuntimeException locked) {
                if (locked.getCause() instanceof IOException io) {
                    lastFailure = io;
                } else {
                    throw locked;
                }
            }
            Thread.sleep(100);
        }
        throw lastFailure;
    }

    // ---- The transition matrix --------------------------------------------

    @Test
    void treeToEmptyStateWhenTheFilterMatchesNothing() {
        assertTrue(treeVisible(), "the tree shows before any filter");

        interact(() -> sidebar.diagFilter("zzz-no-match"));

        assertTrue(awaitState(this::emptyStateVisible), "a filter with no survivors swaps in the empty state");
    }

    @Test
    void emptyStateGoesToPlaceholderWhenTheFilterClearsAfterMatchingNothing() {
        interact(() -> sidebar.diagFilter("zzz-no-match"));
        assertTrue(awaitState(this::emptyStateVisible), "precondition: empty state showing");

        interact(() -> sidebar.diagFilter(""));

        // A rebuild with no survivors sets the selection to null, so clearing
        // the filter lands on the placeholder, not the tree -- the subtab
        // click (placeholderBackToTree test) is what brings the tree back.
        assertTrue(awaitState(this::placeholderVisible),
                "clearing a match-nothing filter shows the placeholder (selection was lost), without throwing");
    }

    @Test
    void treeToPlaceholderWhenTheRepoIsDeselected() {
        assertTrue(treeVisible(), "the tree shows before any filter");

        interact(() -> sidebar.diagSetRepoExpanded(repository.id(), false));

        assertTrue(awaitState(this::placeholderVisible), "a deselect with survivors shows the placeholder");
    }

    @Test
    void placeholderToEmptyStateWhenTheFilterMatchesNothing() {
        interact(() -> sidebar.diagSetRepoExpanded(repository.id(), false));
        assertTrue(awaitState(this::placeholderVisible), "precondition: placeholder showing");

        interact(() -> sidebar.diagFilter("zzz-no-match"));

        assertTrue(awaitState(this::emptyStateVisible),
                "filtering from the placeholder must swap in the empty state, not throw on indexOf(tree) == -1");
    }

    @Test
    void placeholderBackToTreeWhenTheRepoIsReselected() {
        interact(() -> sidebar.diagSetRepoExpanded(repository.id(), false));
        assertTrue(awaitState(this::placeholderVisible), "precondition: placeholder showing");

        interact(() -> sidebar.diagSetRepoExpanded(repository.id(), true));

        assertTrue(awaitState(this::treeVisible), "re-selecting the repo restores the tree");
    }

    // ---- Observation helpers ----------------------------------------------

    /** Finds a descendant by style class; {@code null} when none is in the scene. */
    private static Node findByStyleClass(Parent root, String styleClass) {
        for (Node child : root.getChildrenUnmodifiable()) {
            if (child.getStyleClass().contains(styleClass)) {
                return child;
            }
            if (child instanceof Parent parent) {
                Node found = findByStyleClass(parent, styleClass);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private boolean treeVisible() {
        return FxSync.interact(() -> findByStyleClass(sidebar, "repo-tree") != null
                && findByStyleClass(sidebar, "sidebar-empty-state") == null
                && !isPlaceholderShowing());
    }

    private boolean emptyStateVisible() {
        return FxSync.interact(() -> findByStyleClass(sidebar, "sidebar-empty-state") != null);
    }

    private boolean placeholderVisible() {
        return FxSync.interact(this::isPlaceholderShowing);
    }

    private boolean isPlaceholderShowing() {
        Node node = findByStyleClass(sidebar, "sidebar-empty-message");
        return node instanceof Label label && "Select a repository".equals(label.getText());
    }

    /**
     * Polls until {@code condition} holds. The filter debounce is 150 ms and
     * the rebuild rides {@code Platform.runLater} behind it, so a single
     * {@code waitForFxEvents()} cannot observe a swap; polling also absorbs
     * the case where the swap threw on the FX thread and never happened.
     */
    private boolean awaitState(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    // ---- Test doubles -------------------------------------------------------

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

    private static final class NoopNavigator implements WorkspaceNavigator {
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

        @Override
        public void startParallelWorktreeSession(ManagedAgentSession session) { }

        @Override
        public void showUnopenedWorktree(Repository repository, WorktreeService.Worktree worktree) { }

        @Override
        public void promptRenameSession(ManagedAgentSession session) { }

        @Override
        public Optional<ManagedSessionId> activeSessionId() {
            return Optional.empty();
        }

        @Override
        public void showReviewForSession(ManagedSessionId sessionId, SessionReviewScopes.Choice choice) { }

        @Override
        public void startReviewForWorktree(Repository repository, WorktreeService.Worktree worktree,
                                           SessionReviewScopes.Choice choice) { }

        @Override
        public void startReviewForPullRequest(Repository repository, GhCliService.OpenPullRequest pullRequest) { }
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
