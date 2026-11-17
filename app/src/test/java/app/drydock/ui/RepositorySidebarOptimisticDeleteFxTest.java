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
import app.drydock.domain.Repository;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.git.GhCliService;
import app.drydock.git.GitStatusService;
import app.drydock.git.GitExecutableLocator;
import app.drydock.git.WorktreeConfigGuard;
import app.drydock.git.WorktreeService;
import app.drydock.projects.SharedProjectsFile;
import app.drydock.review.RepositoryPullRequests;
import app.drydock.review.SessionReviewScopes;
import app.drydock.state.ApplicationStateRepository;
import app.drydock.testing.FxTest;
import app.drydock.ui.model.WorkspaceViewModel;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The optimistic tree operations: a delete starts with the row's vanish
 * handshake in the same FX pulse as the user's OK (not after the surface
 * close's grace period), and a session whose surface close is in flight
 * renders the closing treatment instead of a RUNNING dot it knows is about
 * to flip.
 *
 * <p>The assertions stop at the {@link WorkspaceNavigator} boundary, per the
 * project's test convention: the tombstone filtering and the deferred tab
 * teardown those calls trigger live in {@code MainWorkspace}, which is too
 * heavily wired to construct headlessly. What this pins here is the
 * handshake the sidebar drives -- begin fires synchronously before the
 * background delete settles, and the settle lands exactly once as note
 * (success) or cancel (failure).</p>
 */
class RepositorySidebarOptimisticDeleteFxTest extends FxTest {

    private Path repoRoot;
    private RepositoryManager repositoryManager;
    private GitStatusService gitStatusService;
    private WorktreeService worktreeService;
    private SessionManager sessionManager;
    private WorkspaceViewModel viewModel;
    private RepositorySidebar sidebar;
    private RecordingNavigator recorder;
    private Repository repository;

    @Override
    public void start(Stage stage) throws Exception {
        repoRoot = Files.createTempDirectory("drydock-optimistic-delete");
        git(repoRoot, "init", "--initial-branch=main");
        git(repoRoot, "config", "user.email", "test@example.com");
        git(repoRoot, "config", "user.name", "Test");
        Files.writeString(repoRoot.resolve("README.md"), "hello\n");
        git(repoRoot, "add", ".");
        git(repoRoot, "commit", "-m", "initial");

        gitStatusService = new GitStatusService();
        worktreeService = new WorktreeService();
        repositoryManager = new RepositoryManager(new InMemoryStateRepository(), gitStatusService,
                new SharedProjectsFile(Files.createTempDirectory("drydock-shared").resolve("projects.json")),
                root -> Optional.of(root));
        AgentRegistry agentRegistry = new AgentRegistry(List.of(), null);
        sessionManager = new SessionManager(new InMemoryStateRepository(), agentRegistry);
        RepositoryPullRequests repositoryPullRequests = new RepositoryPullRequests(
                root -> CompletableFuture.completedFuture(new GhCliService.PullRequestListing.Unsupported()));
        viewModel = new WorkspaceViewModel();
        recorder = new RecordingNavigator();

        sidebar = new RepositorySidebar(repositoryManager, gitStatusService, worktreeService,
                new WorktreeConfigGuard(new GitExecutableLocator()), repositoryPullRequests,
                sessionManager, agentRegistry, recorder, viewModel);

        TestStages.show(stage, new Scene(new StackPane(sidebar), 420, 640));

        repository = repositoryManager.addRepository(repoRoot).get(20, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() throws IOException {
        sessionManager.close();
        gitStatusService.close();
        worktreeService.close();
        deleteTree(repoRoot);
    }

    @Test
    void deleteBeginsSynchronouslyAndSettlesExactlyOnceAsNote() throws Exception {
        ManagedAgentSession session = idleSession("to-delete");
        interact(() -> viewModel.setSessions(List.of(session)));
        awaitRow(".session-row");

        recorder.reset();
        interact(() -> {
            sidebar.diagDeleteSession(session.id());
            // The begin handshake is synchronous: it has fired before the
            // interact call returns, i.e. in the same pulse as the user's OK.
            assertEquals(List.of("begin:" + session.id().value()), recorder.events,
                    "begin must not wait for the background delete");
        });

        awaitCondition(() -> recorder.events.size() >= 2, "the delete to settle");
        // An idle session has no surface, so the settle is a fast note; the
        // exact-timing assertion lives on the async completion, not here.
        assertEquals("begin:" + session.id().value(), recorder.events.get(0));
        assertTrue(recorder.events.get(recorder.events.size() - 1).startsWith("note:"),
                "a successful delete settles as note, got " + recorder.events);
        assertEquals(2, recorder.events.size(), "exactly one settle: begin, then note");
    }

    @Test
    void aClosingSessionRendersTheClosingTreatmentUntilTheOverlayClears() throws Exception {
        ManagedAgentSession session = runningSession("closing");
        interact(() -> viewModel.setSessions(List.of(session)));
        awaitRow(".session-row");
        assertEquals(List.of(), rowsWithClass("closing"), "a live row shows no closing treatment");

        interact(() -> viewModel.setSessionClosing(session.id(), true));
        awaitCondition(() -> !rowsWithClass("closing").isEmpty(), "the closing treatment to appear");

        interact(() -> viewModel.setSessionClosing(session.id(), false));
        awaitCondition(() -> rowsWithClass("closing").isEmpty(), "the closing treatment to clear");
    }

    // ---- lookups -------------------------------------------------------------

    private List<Node> rowsWithClass(String styleClass) {
        AtomicReference<List<Node>> found = new AtomicReference<>();
        interact(() -> found.set(sidebar.lookupAll(".session-row." + styleClass).stream().toList()));
        return found.get();
    }

    private void awaitRow(String selector) throws InterruptedException {
        awaitCondition(() -> {
            AtomicReference<Node> found = new AtomicReference<>();
            interact(() -> found.set(sidebar.lookup(selector)));
            return found.get() != null;
        }, "waiting for a " + selector + " to render");
    }

    private void awaitCondition(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("timed out waiting for " + what);
    }

    // ---- fixtures ------------------------------------------------------------

    private ManagedAgentSession idleSession(String name) {
        return session(name, SessionStatus.INACTIVE);
    }

    private ManagedAgentSession runningSession(String name) {
        return session(name, SessionStatus.RUNNING);
    }

    private ManagedAgentSession session(String name, SessionStatus status) {
        Instant now = Instant.now();
        return new ManagedAgentSession(ManagedSessionId.newId(), repository.id(), name,
                AgentBinding.unlaunched(AgentKind.CLAUDE),
                new SessionWorkspace(repoRoot, Optional.empty(), true),
                status, now, now, Optional.empty(), PrLink.none(), false, Optional.empty());
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

    /** Records the delete handshake as ordered events. */
    private static final class RecordingNavigator implements WorkspaceNavigator {
        final List<String> events = new ArrayList<>();

        void reset() {
            events.clear();
        }

        @Override
        public void beginSessionDelete(ManagedSessionId sessionId) {
            events.add("begin:" + sessionId.value());
        }

        @Override
        public void cancelSessionDelete(ManagedSessionId sessionId, Throwable failure) {
            events.add("cancel:" + sessionId.value());
        }

        @Override
        public void noteSessionDeleted(ManagedSessionId sessionId) {
            events.add("note:" + sessionId.value());
        }

        @Override
        public void resumeSession(ManagedAgentSession session) { }

        @Override
        public CompletableFuture<Void> closeSession(ManagedSessionId sessionId) {
            return CompletableFuture.completedFuture(null);
        }

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
        public void startSphinxReviewForWorktree(Repository repository, WorktreeService.Worktree worktree) { }

        @Override
        public void startSphinxReviewInSession(ManagedSessionId sessionId) { }

        @Override
        public void startSphinxReviewForPullRequest(Repository repository, GhCliService.OpenPullRequest pullRequest) { }

        @Override
        public void startReviewForPullRequest(Repository repository, GhCliService.OpenPullRequest pullRequest) { }
    }
}
