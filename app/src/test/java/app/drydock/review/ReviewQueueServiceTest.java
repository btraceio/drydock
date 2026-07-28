package app.drydock.review;

import app.drydock.domain.ManagedSessionId;
import app.drydock.git.GhCliService;
import app.drydock.git.GitStatusService;
import app.drydock.git.WorktreeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Queue assembly against real temporary Git repositories, in the style of
 * {@code WorktreeServiceTest}. The REQUESTED group is driven through the
 * {@link ReviewQueueService.ReviewRequestSource} seam so the two states that
 * matter -- {@code gh} present with results, and {@code gh} absent or
 * failing -- are both reachable without a GitHub remote.
 */
class ReviewQueueServiceTest {

    private final WorktreeService worktreeService = new WorktreeService();
    private final GitStatusService gitStatusService = new GitStatusService();
    private final ReviewScopeRegistry registry = new ReviewScopeRegistry();

    /** Stands in for a {@code gh} that is not installed: the future fails. */
    private static final ReviewQueueService.ReviewRequestSource NO_GH =
            root -> CompletableFuture.failedFuture(new IllegalStateException("gh is not installed"));

    /** Stands in for a {@code gh} that ran and found nothing. */
    private static final ReviewQueueService.ReviewRequestSource NO_REQUESTS =
            root -> CompletableFuture.completedFuture(List.of());

    @AfterEach
    void tearDown() {
        worktreeService.close();
        gitStatusService.close();
    }

    private ReviewQueueService serviceWith(ReviewQueueService.ReviewRequestSource requests) {
        return new ReviewQueueService(worktreeService, gitStatusService, requests, registry);
    }

    /**
     * Matches on the real path: {@code git worktree list} reports the
     * resolved directory, while {@code createWorktree} hands back the path
     * it was given -- under {@code @TempDir} on macOS those differ by the
     * {@code /var} → {@code /private/var} symlink. The app is unaffected
     * (sessions record the path git reported), so this normalization is the
     * fixture's job, not the service's.
     */
    private static ReviewQueueService.SessionLookup sessionsIn(Path... checkouts) {
        List<Path> bound = List.of(checkouts).stream().map(ReviewQueueServiceTest::real).toList();
        return checkout -> bound.contains(real(checkout))
                ? Optional.of(ManagedSessionId.newId())
                : Optional.empty();
    }

    @Test
    void aCleanRepositoryWithNoWorktreesProducesNoItems(@TempDir Path dir) throws Exception {
        Path repo = initCommittedRepo(dir);

        List<ReviewItem> items = serviceWith(NO_REQUESTS)
                .assemble(List.of(target(repo)), checkout -> Optional.empty()).get();

        assertTrue(items.isEmpty(), items.toString());
    }

    @Test
    void uncommittedChangesInTheMainCheckoutAreAMineWorkingTreeItem(@TempDir Path dir) throws Exception {
        Path repo = initCommittedRepo(dir);
        Files.writeString(repo.resolve("README.md"), "edited\n");

        List<ReviewItem> items = serviceWith(NO_REQUESTS)
                .assemble(List.of(target(repo)), checkout -> Optional.empty()).get();

        assertEquals(1, items.size());
        ReviewItem item = items.get(0);
        assertEquals(ReviewItem.Group.MINE, item.group());
        assertEquals(ReviewScope.Kind.WORKING_TREE, item.scope().kind());
        assertEquals("Working tree", item.title());
        assertEquals("❯_", item.icon());
    }

    @Test
    void aWorktreeWithoutASessionIsMineAndOneWithASessionIsAnAgents(
            @TempDir Path dir, @TempDir Path worktreeParent) throws Exception {
        Path repo = initCommittedRepo(dir);
        Path mine = gitStatusService.createWorktree(repo, worktreeParent.resolve("mine"), "feat/mine").get();
        Path agents = gitStatusService.createWorktree(repo, worktreeParent.resolve("agents"), "feat/agents").get();

        List<ReviewItem> items = serviceWith(NO_REQUESTS)
                .assemble(List.of(target(repo)), sessionsIn(agents)).get();

        ReviewItem mineItem = itemTitled(items, "feat/mine");
        ReviewItem agentsItem = itemTitled(items, "feat/agents");
        assertEquals(ReviewItem.Group.MINE, mineItem.group());
        assertEquals(ReviewItem.Group.AGENTS, agentsItem.group());
        assertEquals(ReviewScope.Kind.WORKTREE, agentsItem.scope().kind());
        assertEquals("main", agentsItem.scope().base());
        assertEquals(Optional.of(mine.toRealPath()), mineItem.scope().worktree().map(ReviewQueueServiceTest::real));
        assertTrue(agentsItem.scope().sessionId().isPresent());
    }

    @Test
    void aMissingGhLeavesTheRestOfTheQueueIntact(@TempDir Path dir, @TempDir Path worktreeParent)
            throws Exception {
        Path repo = initCommittedRepo(dir);
        gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feat/local").get();

        List<ReviewItem> items = serviceWith(NO_GH)
                .assemble(List.of(target(repo)), checkout -> Optional.empty()).get();

        assertEquals(1, items.size());
        assertEquals("feat/local", items.get(0).title());
        assertFalse(items.stream().anyMatch(item -> item.group() == ReviewItem.Group.REQUESTED));
    }

    @Test
    void reviewRequestedPullRequestsBecomeRequestedItemsWithNoWorktree(@TempDir Path dir) throws Exception {
        Path repo = initCommittedRepo(dir);
        GhCliService.ReviewRequest request = new GhCliService.ReviewRequest(412, "Rate limit the gateway",
                "feat/api-gateway", "main", Optional.of("nina"),
                Optional.of("https://github.com/o/r/pull/412"), 21, false);

        List<ReviewItem> items = serviceWith(root -> CompletableFuture.completedFuture(List.of(request)))
                .assemble(List.of(target(repo)), checkout -> Optional.empty()).get();

        assertEquals(1, items.size());
        ReviewItem item = items.get(0);
        assertEquals(ReviewItem.Group.REQUESTED, item.group());
        assertEquals(ReviewScope.Kind.PR, item.scope().kind());
        assertEquals("PR #412 feat/api-gateway", item.title());
        assertTrue(item.subtitle().contains("@nina"), item.subtitle());
        assertTrue(item.subtitle().contains("21 files"), item.subtitle());
        assertTrue(item.subtitle().endsWith("not checked out"), item.subtitle());
        assertEquals(Optional.empty(), item.scope().worktree());
        assertEquals(Optional.of(412), item.prNumber());
        // With no worktree the diff still has a directory to run git in.
        assertEquals(repo, item.scope().diffRoot());
    }

    @Test
    void aPullRequestAlreadyCheckedOutIsNotListedTwice(@TempDir Path dir, @TempDir Path worktreeParent)
            throws Exception {
        Path repo = initCommittedRepo(dir);
        gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feat/api-gateway").get();
        GhCliService.ReviewRequest request = new GhCliService.ReviewRequest(412, "Rate limit the gateway",
                "feat/api-gateway", "main", Optional.of("nina"), Optional.empty(), 21, false);

        List<ReviewItem> items = serviceWith(root -> CompletableFuture.completedFuture(List.of(request)))
                .assemble(List.of(target(repo)), checkout -> Optional.empty()).get();

        assertEquals(1, items.size());
        assertEquals(ReviewScope.Kind.WORKTREE, items.get(0).scope().kind());
    }

    /**
     * The end-to-end regression test for the orphaned-findings bug: the base
     * used to be the main checkout's CURRENT branch and part of scope
     * identity, so switching branches in the main checkout re-derived every
     * worktree's handle and silently detached its findings and verdicts.
     */
    @Test
    void switchingTheMainCheckoutsBranchKeepsEveryHandleAndBase(
            @TempDir Path dir, @TempDir Path worktreeParent) throws Exception {
        Path repo = initCommittedRepo(dir);
        gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feat/stable").get();
        ReviewQueueService service = serviceWith(NO_REQUESTS);
        ReviewItem before = service.assemble(List.of(target(repo)), checkout -> Optional.empty())
                .get().get(0);

        // Exactly what a reviewer does in another terminal, mid-review.
        runGit(repo, "switch", "-c", "some-other-thing");
        ReviewItem after = service.assemble(List.of(target(repo)), checkout -> Optional.empty())
                .get().get(0);

        assertEquals(before.scope().id(), after.scope().id(),
                "a branch switch in the main checkout must not orphan a worktree's findings");
        assertEquals("main", after.scope().base(),
                "the base is the repository's default branch, not whatever is checked out");
    }

    @Test
    void theBaseIsTheDefaultBranchNotTheCurrentOne(@TempDir Path dir, @TempDir Path worktreeParent)
            throws Exception {
        Path repo = initCommittedRepo(dir);
        gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feat/x").get();
        runGit(repo, "switch", "-c", "scratch");

        List<ReviewItem> items = serviceWith(NO_REQUESTS)
                .assemble(List.of(target(repo)), checkout -> Optional.empty()).get();

        assertEquals("main", itemTitled(items, "feat/x").scope().base());
    }

    /**
     * The base is only useful if git can resolve it. This runs the real diff
     * for EVERY queue item, which is what the column does -- a base that
     * names a branch with no local ref fails with "unknown revision" and the
     * item shows an error instead of a diff.
     */
    @Test
    void everyQueueItemsBaseActuallyDiffs(@TempDir Path dir, @TempDir Path worktreeParent)
            throws Exception {
        Path repo = initCommittedRepo(dir);
        gitStatusService.createWorktree(repo, worktreeParent.resolve("a"), "feat/a").get();
        gitStatusService.createWorktree(repo, worktreeParent.resolve("b"), "feat/b").get();
        Files.writeString(repo.resolve("README.md"), "edited\n");

        List<ReviewItem> items = serviceWith(NO_REQUESTS)
                .assemble(List.of(target(repo)), checkout -> Optional.empty()).get();

        assertDiffsResolve(items);
    }

    /**
     * The regression this guards: {@code origin/HEAD} names a REMOTE default,
     * and {@code git clone -b feat/x} -- or deleting a local main after a
     * merge -- leaves no local branch of that name. Handing the bare name to
     * the diff would fail every worktree item with "unknown revision".
     */
    @Test
    void aDefaultBranchWithNoLocalRefStillDiffs(@TempDir Path parent, @TempDir Path worktreeParent)
            throws Exception {
        Path upstream = initCommittedRepo(parent);
        Path clone = parent.resolve("work");
        runGitIn(parent, "clone", "--quiet", upstream.toString(), clone.toString());
        runGit(clone, "config", "user.name", "Test");
        runGit(clone, "config", "user.email", "test@example.com");
        runGit(clone, "switch", "--quiet", "-c", "feat/x");
        Files.writeString(clone.resolve("README.md"), "changed\n");
        runGit(clone, "commit", "-qam", "change");
        runGit(clone, "branch", "-D", "main");
        gitStatusService.createWorktree(clone, worktreeParent.resolve("wt"), "feat/y").get();

        List<ReviewItem> items = serviceWith(NO_REQUESTS)
                .assemble(List.of(target(clone)), checkout -> Optional.empty()).get();

        assertFalse(items.isEmpty(), "expected the worktree item");
        assertEquals("origin/main", itemTitled(items, "feat/y").scope().base(),
                "with no local main, the base must be the remote-tracking ref that does resolve");
        assertDiffsResolve(items);
    }

    /** Runs the real diff for each item and fails on the first that cannot resolve its base. */
    private void assertDiffsResolve(List<ReviewItem> items) throws Exception {
        try (app.drydock.git.DiffService diffService = new app.drydock.git.DiffService()) {
            for (ReviewItem item : items) {
                ReviewScope scope = item.scope();
                app.drydock.git.DiffScope diffScope =
                        scope.kind() == ReviewScope.Kind.WORKING_TREE
                                ? app.drydock.git.DiffScope.WORKING_TREE
                                : app.drydock.git.DiffScope.BASE;
                try {
                    diffService.diff(scope.diffRoot(), diffScope, scope.base()).get();
                } catch (Exception e) {
                    throw new AssertionError("item '" + item.title() + "' could not diff against base '"
                            + scope.base() + "': " + e.getCause(), e);
                }
            }
        }
    }

    @Test
    void reassemblingKeepsEveryScopeHandle(@TempDir Path dir, @TempDir Path worktreeParent) throws Exception {
        Path repo = initCommittedRepo(dir);
        gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feat/stable").get();
        ReviewQueueService service = serviceWith(NO_REQUESTS);

        String first = service.assemble(List.of(target(repo)), checkout -> Optional.empty())
                .get().get(0).scope().id();
        String again = service.assemble(List.of(target(repo)), checkout -> Optional.empty())
                .get().get(0).scope().id();

        assertEquals(first, again);
    }

    @Test
    void anItemThatLeavesTheQueueHasItsHandleRevoked(@TempDir Path dir, @TempDir Path worktreeParent)
            throws Exception {
        Path repo = initCommittedRepo(dir);
        Path worktree = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feat/gone").get();
        ReviewQueueService service = serviceWith(NO_REQUESTS);
        String scopeId = service.assemble(List.of(target(repo)), checkout -> Optional.empty())
                .get().get(0).scope().id();

        worktreeService.remove(repo, worktree, Optional.of("feat/gone")).get();
        List<ReviewItem> after = service.assemble(List.of(target(repo)), checkout -> Optional.empty()).get();

        assertTrue(after.isEmpty(), after.toString());
        assertEquals(Optional.empty(), registry.byId(scopeId));
    }

    @Test
    void aRepositoryThatIsNotAGitCheckoutIsSkippedRatherThanFailingTheQueue(
            @TempDir Path dir, @TempDir Path notARepo) throws Exception {
        Path repo = initCommittedRepo(dir);
        Files.writeString(repo.resolve("README.md"), "edited\n");

        List<ReviewItem> items = serviceWith(NO_REQUESTS).assemble(
                List.of(new ReviewQueueService.RepositoryTarget(notARepo, "broken"), target(repo)),
                checkout -> Optional.empty()).get();

        assertEquals(1, items.size());
        assertEquals("Working tree", items.get(0).title());
    }

    @Test
    void itemsAreOrderedByGroup(@TempDir Path dir, @TempDir Path worktreeParent) throws Exception {
        Path repo = initCommittedRepo(dir);
        Files.writeString(repo.resolve("README.md"), "edited\n");
        Path agents = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feat/agent").get();
        GhCliService.ReviewRequest request = new GhCliService.ReviewRequest(9, "t", "feat/remote", "main",
                Optional.empty(), Optional.empty(), 0, false);

        List<ReviewItem> items = serviceWith(root -> CompletableFuture.completedFuture(List.of(request)))
                .assemble(List.of(target(repo)), sessionsIn(agents)).get();

        assertEquals(List.of(ReviewItem.Group.MINE, ReviewItem.Group.AGENTS, ReviewItem.Group.REQUESTED),
                items.stream().map(ReviewItem::group).toList());
    }

    // ---- fixtures -----------------------------------------------------------

    private static ReviewQueueService.RepositoryTarget target(Path repo) {
        return new ReviewQueueService.RepositoryTarget(repo, "drydock");
    }

    private static ReviewItem itemTitled(List<ReviewItem> items, String title) {
        return items.stream().filter(item -> item.title().equals(title)).findFirst()
                .orElseThrow(() -> new AssertionError("no item titled " + title + " in " + items));
    }

    private static Path real(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path;
        }
    }

    private static Path initCommittedRepo(Path parent) throws IOException, InterruptedException {
        Path repo = Files.createDirectories(parent.resolve("repo"));
        runGit(repo, "init", "-b", "main");
        runGit(repo, "config", "user.name", "Test");
        runGit(repo, "config", "user.email", "test@example.com");
        Files.writeString(repo.resolve("README.md"), "hello\n");
        runGit(repo, "add", "README.md");
        runGit(repo, "commit", "-m", "initial commit");
        return repo;
    }

    /** As {@link #runGit}, for commands run in a directory that is not itself a repository. */
    private static void runGitIn(Path directory, String... args) throws IOException, InterruptedException {
        runGit(directory, args);
    }

    private static void runGit(Path repo, String... args) throws IOException, InterruptedException {
        List<String> command = new java.util.ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command)
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " exited " + exit + ": " + output);
        }
    }
}
