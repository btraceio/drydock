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
import app.drydock.ui.model.WorkspaceViewModel;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof that the PULL REQUESTS group's dedup against local
 * worktrees actually self-heals through the sidebar's real async plumbing
 * -- {@code refreshWorktrees}, {@code refreshPullRequests}, and the ⟳
 * button -- not just that the pure predicates behind them return the right
 * booleans in isolation.
 *
 * <p>This exists because the same defect (a PR that already has a local
 * worktree keeps its row) recurred three times across three review rounds,
 * each time inside code that had just "fixed" the previous recurrence, and
 * every prior round's tests (all pure-helper unit tests) stayed green
 * through every one of those regressions. {@link RepositoryPullRequests}
 * takes a {@link RepositoryPullRequests.Source} functional interface
 * exactly so a test can hand-drive the {@code gh} side of this without a
 * real {@code gh} -- {@link ControlledSource} below does that, and records
 * every call so the test can assert not just THAT a re-scan happened but
 * how many, and in what order relative to the worktree actually appearing.</p>
 */
class RepositorySidebarPullRequestDedupFxTest extends ApplicationTest {

    private Path repoRoot;
    private final List<Path> extraWorktreePaths = new ArrayList<>();
    private RepositoryManager repositoryManager;
    private GitStatusService gitStatusService;
    private WorktreeService worktreeService;
    private ControlledSource source;
    private WorkspaceViewModel viewModel;
    private RepositorySidebar sidebar;
    private final RecordingNavigator navigator = new RecordingNavigator();
    private Repository repository;

    @Override
    public void start(Stage stage) throws Exception {
        repoRoot = Files.createTempDirectory("drydock-pr-dedup");
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
        source = new ControlledSource();
        RepositoryPullRequests repositoryPullRequests = new RepositoryPullRequests(source);
        viewModel = new WorkspaceViewModel();

        sidebar = new RepositorySidebar(repositoryManager, gitStatusService, worktreeService, repositoryPullRequests,
                sessionManager, agentRegistry, navigator, viewModel);

        TestStages.show(stage, new Scene(new StackPane(sidebar), 360, 640));

        repository = repositoryManager.addRepository(repoRoot)
                .get(20, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() throws IOException {
        gitStatusService.close();
        worktreeService.close();
        deleteTree(repoRoot);
        for (Path extra : extraWorktreePaths) {
            deleteTree(extra);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
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

    /** A path unique to THIS test's {@link #repoRoot} -- a shared parent temp dir means a fixed name collides across runs. */
    private Path newWorktreePath(String suffix) {
        Path path = repoRoot.resolveSibling(repoRoot.getFileName() + "-" + suffix);
        extraWorktreePaths.add(path);
        return path;
    }

    /**
     * The full sequence the coordinator asked for: a first scan lands with
     * PR #7 having no worktree (row present), a worktree checking out PR
     * #7 appears, the ⟳ path is what notices it (the exact race B1/B2
     * fixed: the worktree rescan and a same-click PR rescan overlap), and
     * once everything settles the row is gone -- with the source having
     * been asked a second time only after the view model's own worktree
     * list already reflected the new worktree, proving the re-scan used
     * the updated list rather than the one the first scan captured.
     */
    @Test
    void aWorktreeCheckingOutAListedPrMakesItsRowDisappear() throws Exception {
        awaitCallCount(1, "the first PR scan");
        assertEquals(repository.root(), source.rootOf(0), "the scan must ask about this repository, not another");
        source.complete(0, listing(pr(7, "Fix login", "pr-7")));
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(groupRowPresent(), "PR #7 has no worktree yet: its group row must be present");

        git(repoRoot, "branch", "pr-7");
        Path worktreePath = newWorktreePath("pr-7-worktree");
        git(repoRoot, "worktree", "add", worktreePath.toString(), "pr-7");

        clickRescan();

        awaitCallCount(2, "the rescan's own PR scan, fired directly by the ⟳ click");
        assertFalse(hasPr7(worktreeListAt(1)),
                "the ⟳ click's own PR scan runs in the click handler, before its worktree "
                        + "rescan can possibly have landed: it must have been launched against "
                        + "the stale, pre-rescan worktree list");

        // Force the overlap this whole mechanism exists for, instead of
        // hoping for it: hold the ⟳'s PR scan open (call #2 is still
        // uncompleted) until the worktree rescan the SAME click started has
        // actually landed its new list. That is precisely the window in
        // which refreshPullRequests is re-entered with a scan in flight --
        // the only window pendingPullRequestRescan is ever exercised in.
        // Without this wait, a fast `git worktree list` losing a race
        // against a few FX pulses would land the worktree list AFTER call
        // #2 completed, the re-scan would come from the ordinary
        // (non-pending) path, and reverting the pending mechanism would
        // still pass.
        awaitCondition(this::viewModelSeesPr7Worktree,
                "the ⟳'s worktree rescan landing while its PR scan is still in flight");

        source.complete(1, listing(pr(7, "Fix login", "pr-7")));
        WaitForAsyncUtils.waitForFxEvents();

        // The worktree rescan's own completion must re-run the PR scan
        // once it lands (this is exactly what a reverted N1/B1/B2 fix
        // drops): a third call, made only once the view model's worktree
        // list has actually caught up.
        awaitCallCount(3, "the re-scan the worktree rescan's completion queues once the list changed");
        assertTrue(hasPr7(worktreeListAt(2)),
                "the third scan must be launched with the worktree list that already includes "
                        + "the pr-7 checkout -- captured inside the Source at the moment that "
                        + "call was made, not re-read now");
        source.complete(2, listing(pr(7, "Fix login", "pr-7")));
        WaitForAsyncUtils.waitForFxEvents();

        awaitCondition(() -> !groupRowPresent(), "the group row disappearing once the dedup sees the new worktree");
    }

    /**
     * B1, and the laziness N2 restored: a repo's worktree list changing
     * while its row is collapsed must not spawn an automatic PR scan (that
     * would be a {@code gh pr list} network spawn for a row nobody is
     * looking at), but the resulting stale outcome must not be stranded
     * either -- expanding the row has to notice and rescan. Without B1,
     * {@code needsPullRequestScan} alone cannot tell "scanned" from
     * "scanned, but a worktree appeared since" apart, so the mark from the
     * collapsed skip is the only thing that can recover it.
     */
    @Test
    void aRepoThatChangesWorktreesWhileCollapsedSelfHealsOnExpand() throws Exception {
        awaitCallCount(1, "the first PR scan");
        source.complete(0, listing(pr(7, "Fix login", "pr-7")));
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(groupRowPresent(), "PR #7 has no worktree yet: its group row must be present");

        interact(() -> sidebar.diagSetRepoExpanded(repository.id(), false)); // collapse

        git(repoRoot, "branch", "pr-7");
        Path worktreePath = newWorktreePath("pr-7-collapsed-worktree");
        git(repoRoot, "worktree", "add", worktreePath.toString(), "pr-7");

        clickRescan();
        awaitCallCount(2, "the rescan's own direct PR scan (fired by the click regardless of collapse)");
        source.complete(1, listing(pr(7, "Fix login", "pr-7")));
        WaitForAsyncUtils.waitForFxEvents();

        // N2: the worktree rescan's completion notices the list changed
        // but must NOT spawn a third scan while the repo stays collapsed.
        // Wait on the observable rather than on the clock: once the view
        // model holds the new list, the runLater that wrote it -- and
        // therefore the collapsed-vs-rescan decision it makes right
        // afterwards, in the same FX task -- has already run to completion
        // (viewModelSeesPr7Worktree drains the FX queue). A fixed sleep
        // instead passes vacuously on any machine where `git worktree list`
        // outlasts it.
        awaitCondition(this::viewModelSeesPr7Worktree,
                "the collapsed repo's worktree rescan landing its new list");
        assertEquals(2, source.callCount(),
                "a collapsed repo's worktree change must not spawn an automatic PR scan on its own (N2)");

        interact(() -> sidebar.diagSetRepoExpanded(repository.id(), true)); // expand

        // B1: expanding must notice the outcome is stale (marked so by the
        // collapsed skip above) and rescan -- otherwise PR #7 keeps a row
        // despite now having a local worktree, forever.
        awaitCallCount(3, "the rescan B1 fires on expand for a repo marked stale while collapsed");
        assertTrue(hasPr7(worktreeListAt(2)),
                "the expand-triggered rescan must use the worktree list that already includes pr-7");
        source.complete(2, listing(pr(7, "Fix login", "pr-7")));
        WaitForAsyncUtils.waitForFxEvents();

        awaitCondition(() -> !groupRowPresent(), "the group row disappearing once the dedup sees the new worktree");
    }

    /**
     * N3: the group header's count must track what a text filter actually
     * leaves shown, not the outcome's raw total -- otherwise "PULL
     * REQUESTS (2)" keeps claiming two rows while the tree narrows to one.
     */
    @Test
    void aTextFilterNarrowsTheHeaderCountToWhatItActuallyShows() throws Exception {
        awaitCallCount(1, "the first PR scan");
        source.complete(0, listing(
                pr(7, "Fix login", "pr-7"),
                pr(8, "Bump deps", "bump-deps")));
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals("PULL REQUESTS (2)", groupLabelText());

        interact(() -> sidebar.diagFilter("login"));

        awaitCondition(() -> "PULL REQUESTS (1 of 2)".equals(groupLabelTextOrNull()),
                "the header narrowing to \"1 of 2\" once the filter debounce fires");
    }

    /**
     * Task 12's double-click guard, through the real row: materializing a
     * pull request takes a whole-branch fetch, so the row must refuse a
     * second click for as long as the first is running -- a second {@code
     * git worktree add} at the same path fails with "there is already
     * something at …", which would show the human a failure for work that is
     * in fact succeeding.
     *
     * <p>The pill's handler is fired directly rather than clicked: a disabled
     * row would not accept a pointer at all, and "the row is disabled" is
     * only half the guard. The other half -- the sidebar refusing the second
     * gesture whatever fired it (⏎ on the selected row comes through {@code
     * activateNode}, not the pill) -- is what this pins.</p>
     */
    @Test
    void aSecondClickOnAPullRequestRowDoesNotStartASecondMaterialization() throws Exception {
        awaitCallCount(1, "the first PR scan");
        source.complete(0, listing(pr(7, "Fix login", "pr-7")));
        WaitForAsyncUtils.waitForFxEvents();
        expandPullRequestGroup();

        clickReviewPill();
        assertEquals(1, navigator.materializations(), "the first click starts one materialization");
        assertEquals(7, navigator.lastPullRequest().number(), "it must materialize the row's own PR");

        clickReviewPill();
        assertEquals(1, navigator.materializations(),
                "a second click while the fetch is still running must not start a second one");
        assertTrue(reviewPillIsBusy(), "the row must say what it is doing while it does it");

        // Every ending settles -- cancelled, failed or landed. Here that is
        // the workspace telling the row it is over.
        interact(navigator::settle);
        assertFalse(reviewPillIsBusy(), "the row must come back once the materialization ends");

        clickReviewPill();
        assertEquals(2, navigator.materializations(),
                "a settled row must be startable again -- a failed checkout is meant to be retried");
    }

    /**
     * Task 12's I2: after a successful materialization the PR row must be
     * GONE, not re-enabled. The dedup is computed inside the {@code gh pr
     * list} scan and cached in its outcome, so publishing a fresh worktree
     * list into the view model rebuilds the tree without re-running it -- the
     * row survived, and pressing it again reported "No worktree was created"
     * for a path that by then held a healthy checkout.
     *
     * <p>Driven through the exact seam the workspace calls ({@link
     * RepositorySidebar#refreshWorktreesFor}) with the worktree a
     * materialization would have left on disk.</p>
     */
    @Test
    void theSeamTheWorkspaceCallsRemovesTheRowOfAMaterializedPr() throws Exception {
        awaitCallCount(1, "the first PR scan");
        source.complete(0, listing(pr(7, "Fix login", "pr-7")));
        WaitForAsyncUtils.waitForFxEvents();
        expandPullRequestGroup();
        assertTrue(pullRequestRowPresent(), "PR #7 has no worktree yet: its row must be present");

        // What a successful materialization leaves behind: a worktree with
        // the PR checked out as pr-<n>.
        git(repoRoot, "branch", "pr-7");
        Path worktreePath = newWorktreePath("pr-7-materialized");
        git(repoRoot, "worktree", "add", worktreePath.toString(), "pr-7");

        interact(() -> sidebar.refreshWorktreesFor(repository));

        awaitCallCount(2, "the PR rescan the workspace's refresh seam must trigger");
        assertTrue(hasPr7(worktreeListAt(1)),
                "the rescan must run against the list that already holds the new worktree");
        source.complete(1, listing(pr(7, "Fix login", "pr-7")));
        WaitForAsyncUtils.waitForFxEvents();

        awaitCondition(() -> !pullRequestRowPresent(),
                "the materialized PR's row disappearing -- pressing it again would report that "
                        + "no worktree was created for a path that now holds a healthy checkout");
        awaitCondition(() -> !groupRowPresent(), "the now-empty PULL REQUESTS group going away");
    }

    /**
     * Expands the PULL REQUESTS group by firing its row's own click handler
     * rather than moving a pointer: a real {@code clickOn} depends on the
     * window being focused and positioned, which does not hold when the whole
     * {@code app.drydock.ui} package shares one JVM -- it passed alone and
     * timed out in the suite.
     */
    private void expandPullRequestGroup() throws InterruptedException {
        AtomicReference<Node> group = new AtomicReference<>();
        interact(() -> group.set(sidebar.lookup(".pull-request-group-row")));
        assertTrue(group.get() != null, "no PULL REQUESTS group row to expand");
        interact(() -> group.get().fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0,
                MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, true, false,
                false, null)));
        awaitCondition(this::pullRequestRowPresent, "the PR row appearing under the expanded group");
    }

    private boolean pullRequestRowPresent() {
        AtomicBoolean found = new AtomicBoolean();
        interact(() -> found.set(pullRequestRow() != null));
        return found.get();
    }

    /** The one PR row: an unopened-style row whose pill is the Review one. */
    private Node pullRequestRow() {
        for (Node node : sidebar.lookupAll(".worktree-unopened-row")) {
            Node pill = node.lookup(".start-pill");
            if (pill instanceof Label label && !label.getText().startsWith("Start")) {
                return node;
            }
        }
        return null;
    }

    private void clickReviewPill() {
        AtomicBoolean clicked = new AtomicBoolean();
        interact(() -> {
            Node row = pullRequestRow();
            clicked.set(row != null && RepositorySidebar.diagClickReviewPill(row));
        });
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(clicked.get(), "could not find the Review ▸ pill");
    }

    private boolean reviewPillIsBusy() {
        AtomicBoolean busy = new AtomicBoolean();
        interact(() -> {
            Node row = pullRequestRow();
            Node pill = row == null ? null : row.lookup(".start-pill");
            busy.set(row != null && row.isDisabled() && pill instanceof Label label
                    && "Opening…".equals(label.getText()));
        });
        return busy.get();
    }

    // ---- lookups --------------------------------------------------------

    private boolean groupRowPresent() {
        AtomicBoolean found = new AtomicBoolean();
        interact(() -> found.set(sidebar.lookup(".pull-request-group-row") != null));
        return found.get();
    }

    private String groupLabelTextOrNull() {
        AtomicReference<String> text = new AtomicReference<>();
        interact(() -> {
            Node group = sidebar.lookup(".pull-request-group-row");
            Node label = group == null ? null : group.lookup(".stale-summary");
            text.set(label instanceof Label l ? l.getText() : null);
        });
        return text.get();
    }

    private String groupLabelText() {
        String text = groupLabelTextOrNull();
        assertTrue(text != null, "no PULL REQUESTS group row found");
        return text;
    }

    private void clickRescan() {
        AtomicBoolean clicked = new AtomicBoolean();
        interact(() -> {
            for (var node : sidebar.lookupAll(".row-action-button")) {
                if (node instanceof Button button && "⟳".equals(button.getText())) {
                    button.fire();
                    clicked.set(true);
                    return;
                }
            }
        });
        assertTrue(clicked.get(), "could not find the ⟳ rescan button");
    }

    // ---- waiting ----------------------------------------------------------

    private void awaitCallCount(int expected, String what) throws InterruptedException {
        awaitCondition(() -> source.callCount() >= expected, "waiting for " + what);
        assertEquals(expected, source.callCount(), what + " -- unexpected extra/missing gh calls");
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

    private List<WorktreeService.Worktree> worktreeListAt(int callIndex) {
        return source.worktreeSnapshotAt(callIndex);
    }

    /**
     * The view model's own worktree list, read on the FX thread (it is a
     * plain HashMap the FX thread writes; {@code interact} also drains the
     * FX queue first, so a true answer here means the runLater that landed
     * the new list has already finished -- including whatever it decided to
     * do about the PR scan).
     */
    private boolean viewModelSeesPr7Worktree() {
        AtomicBoolean seen = new AtomicBoolean();
        interact(() -> seen.set(hasPr7(viewModel.worktrees(repository.id()).orElse(List.of()))));
        return seen.get();
    }

    private static boolean hasPr7(List<WorktreeService.Worktree> worktrees) {
        return worktrees.stream().anyMatch(worktree -> worktree.branch().equals(Optional.of("pr-7")));
    }

    // ---- fixtures -----------------------------------------------------------

    private static GhCliService.PullRequestListing.Listed listing(GhCliService.OpenPullRequest... prs) {
        return new GhCliService.PullRequestListing.Listed(List.of(prs));
    }

    private static GhCliService.OpenPullRequest pr(int number, String title, String headRefName) {
        return new GhCliService.OpenPullRequest(number, title, headRefName, "main", false,
                Optional.of("octocat"), Optional.empty());
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

    /**
     * A {@link RepositoryPullRequests.Source} the test drives by hand: each
     * call is recorded (never auto-answered) and completed later via
     * {@link #complete}, so the test controls exactly when each scan
     * "returns from gh" relative to the real async worktree discovery
     * happening concurrently underneath it.
     *
     * <p>Non-static on purpose: {@link #forRepository} records the worktree
     * list each scan was launched with, per call, which needs the outer
     * test's view model.</p>
     */
    private final class ControlledSource implements RepositoryPullRequests.Source {
        private final List<CompletableFuture<GhCliService.PullRequestListing>> futures = new CopyOnWriteArrayList<>();
        private final List<Path> roots = new CopyOnWriteArrayList<>();
        private final List<List<WorktreeService.Worktree>> worktreeSnapshots = new CopyOnWriteArrayList<>();
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public CompletableFuture<GhCliService.PullRequestListing> forRepository(Path repositoryRoot) {
            // RepositoryPullRequests.scan calls this synchronously, on the
            // FX thread, in the very same call in which
            // refreshPullRequests read viewModel.worktrees(id) and passed
            // it in -- so what the view model holds RIGHT NOW is exactly
            // the list this scan will dedup against. (The Source interface
            // itself does not carry that argument: scan applies the
            // worktree list after the listing comes back, not before asking
            // for it. Recording it here is what makes the assertion below a
            // real capture of THIS call's argument rather than a re-read at
            // assertion time, which any implementation would pass.)
            worktreeSnapshots.add(worktreesFor(repositoryRoot));
            roots.add(repositoryRoot);
            calls.incrementAndGet();
            CompletableFuture<GhCliService.PullRequestListing> future = new CompletableFuture<>();
            futures.add(future);
            return future;
        }

        int callCount() {
            return calls.get();
        }

        Path rootOf(int index) {
            return roots.get(index);
        }

        void complete(int index, GhCliService.PullRequestListing listing) {
            futures.get(index).complete(listing);
        }

        /** The worktree list scan {@code index} was actually launched with, captured when it was made. */
        List<WorktreeService.Worktree> worktreeSnapshotAt(int index) {
            assertTrue(index < callCount(), "no such call yet");
            return worktreeSnapshots.get(index);
        }
    }

    /** The view model's worktree list for the repository rooted at {@code root} -- FX-thread only. */
    private List<WorktreeService.Worktree> worktreesFor(Path root) {
        return repositoryManager.repositories().stream()
                .filter(candidate -> candidate.root().equals(root))
                .findFirst()
                .flatMap(candidate -> viewModel.worktrees(candidate.id()))
                .orElse(List.of());
    }

    /**
     * A no-op for everything the dedup tests drive through the tree, plus
     * the one thing the materialization test needs: a count of how many
     * materializations were actually asked for, and the settle hook the
     * workspace would run when one ends.
     */
    private static final class RecordingNavigator implements WorkspaceNavigator {
        private final AtomicInteger materializations = new AtomicInteger();
        private final List<Runnable> settles = new CopyOnWriteArrayList<>();
        private volatile GhCliService.OpenPullRequest lastPullRequest;

        int materializations() {
            return materializations.get();
        }

        GhCliService.OpenPullRequest lastPullRequest() {
            return lastPullRequest;
        }

        /** Ends every materialization asked for so far, as the workspace does on its completion paths. */
        void settle() {
            List<Runnable> pending = List.copyOf(settles);
            settles.clear();
            pending.forEach(Runnable::run);
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
        public void startReviewForPullRequest(Repository repository, GhCliService.OpenPullRequest pullRequest) {
            startReviewForPullRequest(repository, pullRequest, () -> { });
        }

        @Override
        public void startReviewForPullRequest(Repository repository, GhCliService.OpenPullRequest pullRequest,
                                              Runnable onSettled) {
            materializations.incrementAndGet();
            lastPullRequest = pullRequest;
            settles.add(onSettled);
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
