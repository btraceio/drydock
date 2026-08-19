package app.drydock.review;

import app.drydock.domain.ManagedSessionId;
import app.drydock.git.GhCliService;
import app.drydock.git.GitExecutableLocator;
import app.drydock.git.GitStatusService;
import app.drydock.git.ReviewBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scopes one session offers, and -- the part that matters -- the exact
 * identities they are minted with. Identity is (kind, repo, worktree, PR
 * number); every finding is keyed by the id that identity produces, so a
 * kind or a PR ref that drifts here makes existing reviews vanish silently.
 *
 * <p>The checkouts below are real git repositories, in the style of {@code
 * ReviewQueueServiceTest}: {@code defaultBranch} and {@code reviewBase}
 * measure an actual checkout, and against a bare {@code @TempDir} they would
 * only ever exercise the unmeasured fallback, proving nothing about the base
 * these scopes are minted with.</p>
 */
class SessionReviewScopesTest {

    private final GitStatusService gitStatusService = new GitStatusService();
    private final ReviewScopeRegistry registry = new ReviewScopeRegistry();
    private final SessionReviewScopes scopes = new SessionReviewScopes(gitStatusService, registry);

    @AfterEach
    void tearDown() {
        gitStatusService.close();
    }

    private static GhCliService.OpenPullRequest pullRequest(int number, String head) {
        return new GhCliService.OpenPullRequest(number, "Title", head, "main", false,
                Optional.empty(), Optional.empty());
    }

    @Test
    void aMainCheckoutMintsAWorkingTreeScopeAndNoPullRequest(@TempDir Path dir)
            throws ExecutionException, InterruptedException, IOException {
        Path repo = initCommittedRepo(dir);

        SessionReviewScopes.Scopes resolved = scopes.forCheckout(
                repo, repo, Optional.of("main"), Optional.empty(), Optional.empty()).get();

        assertEquals(ReviewScope.Kind.WORKING_TREE, resolved.local().kind());
        assertTrue(resolved.pullRequest().isEmpty());
    }

    @Test
    void aWorktreeMintsAWorktreeScope(@TempDir Path dir, @TempDir Path worktreeParent)
            throws ExecutionException, InterruptedException, IOException {
        Path repo = initCommittedRepo(dir);
        Path worktree = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feature/x").get();

        SessionReviewScopes.Scopes resolved = scopes.forCheckout(
                repo, worktree, Optional.of("feature/x"), Optional.empty(), Optional.empty()).get();

        assertEquals(ReviewScope.Kind.WORKTREE, resolved.local().kind());
        assertEquals("feature/x", resolved.local().head());
        // Pins that the base is the MEASURED one, not the unmeasured
        // fallback -- with a bare @TempDir (no real branches to measure
        // between) this would read DEFAULT_UNMEASURED regardless of what
        // forCheckout actually does, which is exactly what the real-repo
        // fixtures are here to rule out.
        assertEquals(ReviewBase.Origin.FORKED_FROM, resolved.local().baseOrigin().orElseThrow());
    }

    /**
     * The narrow case: the checkout's branch IS the {@code pr-<n>} alias
     * {@code PrCheckoutService} checks a PR out under, so the local scope
     * and the PR scope name the very same worktree under review.
     */
    @Test
    void theLocalScopeCarriesThePullRequestRefWhenTheCheckoutIsThePrAlias(
            @TempDir Path dir, @TempDir Path worktreeParent)
            throws ExecutionException, InterruptedException, IOException {
        Path repo = initCommittedRepo(dir);
        Path worktree = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "pr-42").get();

        SessionReviewScopes.Scopes resolved = scopes.forCheckout(
                repo, worktree, Optional.of("pr-42"), Optional.empty(),
                Optional.of(pullRequest(42, "someones-branch"))).get();

        assertEquals(ReviewScope.Kind.WORKTREE, resolved.local().kind());
        assertEquals(42, resolved.local().pr().orElseThrow().number());
    }

    /**
     * The rule this task got wrong the first time: a worktree on an
     * ordinary branch that happens to have an open PR does NOT carry the
     * ref locally. {@code ReviewQueueService.build} only ever attaches a
     * {@code PullRequestRef} to a worktree via {@code
     * PrCheckoutService.pullRequestNumberOf(head)}, which requires the
     * literal {@code pr-<n>} alias -- an ordinary branch like this one is
     * never recognised as "holding" a PR that way. Attaching the ref
     * whenever one was merely supplied would flip this scope's identity the
     * moment somebody opens (or merges) a PR on the branch, silently
     * detaching every finding already recorded against the worktree.
     */
    @Test
    void aWorktreeOnAnOrdinaryBranchDoesNotCarryAnOpenPullRequestsRefLocally(
            @TempDir Path dir, @TempDir Path worktreeParent)
            throws ExecutionException, InterruptedException, IOException {
        Path repo = initCommittedRepo(dir);
        Path worktree = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feat/x").get();

        SessionReviewScopes.Scopes resolved = scopes.forCheckout(
                repo, worktree, Optional.of("feat/x"), Optional.empty(),
                Optional.of(pullRequest(42, "feat/x"))).get();

        assertTrue(resolved.local().pr().isEmpty(),
                "an ordinary branch's local scope must not carry the PR ref");
        assertEquals(42, resolved.pullRequest().orElseThrow().pr().orElseThrow().number(),
                "the PR scope itself is unaffected and always carries its ref");
    }

    /** As above, for the main checkout: never a {@code pr-<n>} alias, so never a ref. */
    @Test
    void aMainCheckoutDoesNotCarryAnOpenPullRequestsRefLocally(@TempDir Path dir)
            throws ExecutionException, InterruptedException, IOException {
        Path repo = initCommittedRepo(dir);

        SessionReviewScopes.Scopes resolved = scopes.forCheckout(
                repo, repo, Optional.of("main"), Optional.empty(),
                Optional.of(pullRequest(42, "main"))).get();

        assertEquals(ReviewScope.Kind.WORKING_TREE, resolved.local().kind());
        assertTrue(resolved.local().pr().isEmpty());
    }

    @Test
    void thePullRequestScopeIsItsOwnKindAndKeepsTheWorktree(
            @TempDir Path dir, @TempDir Path worktreeParent)
            throws ExecutionException, InterruptedException, IOException {
        Path repo = initCommittedRepo(dir);
        Path worktree = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "pr-42").get();

        SessionReviewScopes.Scopes resolved = scopes.forCheckout(
                repo, worktree, Optional.of("pr-42"), Optional.empty(),
                Optional.of(pullRequest(42, "someones-branch"))).get();

        ReviewScope pr = resolved.pullRequest().orElseThrow();
        assertEquals(ReviewScope.Kind.PR, pr.kind());
        assertEquals(42, pr.pr().orElseThrow().number());
        assertTrue(pr.diffable(), "diffable() is true exactly when a worktree is present");
    }

    @Test
    void theTwoScopesAreDistinctHandles(@TempDir Path dir, @TempDir Path worktreeParent)
            throws ExecutionException, InterruptedException, IOException {
        Path repo = initCommittedRepo(dir);
        Path worktree = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "pr-42").get();

        SessionReviewScopes.Scopes resolved = scopes.forCheckout(
                repo, worktree, Optional.of("pr-42"), Optional.empty(),
                Optional.of(pullRequest(42, "someones-branch"))).get();

        assertFalse(resolved.local().id().equals(resolved.pullRequest().orElseThrow().id()));
    }

    @Test
    void resolvingTwiceReturnsTheSameHandles(@TempDir Path dir, @TempDir Path worktreeParent)
            throws ExecutionException, InterruptedException, IOException {
        Path repo = initCommittedRepo(dir);
        Path worktree = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feature/x").get();
        ManagedSessionId session = ManagedSessionId.newId();

        String first = scopes.forCheckout(repo, worktree, Optional.of("feature/x"),
                Optional.of(session), Optional.empty()).get().local().id();
        String second = scopes.forCheckout(repo, worktree, Optional.of("feature/x"),
                Optional.of(session), Optional.empty()).get().local().id();

        assertEquals(first, second);
    }

    @Test
    void aDetachedCheckoutStillResolvesALocalScope(@TempDir Path dir, @TempDir Path worktreeParent)
            throws ExecutionException, InterruptedException, IOException {
        Path repo = initCommittedRepo(dir);
        Path detached = worktreeParent.resolve("detached");
        runGit(repo, "worktree", "add", "--detach", detached.toString());

        SessionReviewScopes.Scopes resolved = scopes.forCheckout(
                repo, detached, Optional.empty(), Optional.empty(), Optional.empty()).get();

        assertEquals("(no branch)", resolved.local().head());
    }

    /**
     * {@code defaultBranch} and {@code reviewBase} both throw {@code
     * GitExecutableNotFoundException} from inside their suppliers when no
     * git executable can be found; without a {@code handle}/{@code
     * exceptionally} step the future would complete exceptionally and the
     * Review sub-tab would get nothing. A service that cannot measure
     * something must degrade to a documented fallback instead -- the same
     * one {@code ReviewQueueService} uses.
     */
    @Test
    void aFailingGitServiceStillYieldsALocalScope(@TempDir Path dir)
            throws ExecutionException, InterruptedException, IOException {
        Path repo = initCommittedRepo(dir);
        GitExecutableLocator missingLocator = new GitExecutableLocator(Path.of("/nonexistent/git-does-not-exist"));
        try (GitStatusService brokenGitStatusService = new GitStatusService(missingLocator)) {
            SessionReviewScopes brokenScopes = new SessionReviewScopes(brokenGitStatusService, registry);

            SessionReviewScopes.Scopes resolved = brokenScopes.forCheckout(
                    repo, repo, Optional.of("main"), Optional.empty(), Optional.empty()).get();

            assertEquals(ReviewScope.Kind.WORKING_TREE, resolved.local().kind());
            assertEquals(ReviewBase.Origin.DEFAULT_UNMEASURED, resolved.local().baseOrigin().orElseThrow());
        }
    }

    @Test
    void forChoiceFallsBackToLocalWhenThePullRequestIsAbsent() {
        ReviewScope local = ReviewScopeRegistry.spec(ReviewScope.Kind.WORKING_TREE, Path.of("/repo"),
                Optional.of(Path.of("/repo")), "main", "main", Optional.empty(), Optional.empty());
        SessionReviewScopes.Scopes noPullRequest = new SessionReviewScopes.Scopes(local, Optional.empty());

        assertEquals(local, noPullRequest.forChoice(SessionReviewScopes.Choice.PULL_REQUEST));
    }

    @Test
    void choiceFromPersistedIsLenient() {
        assertEquals(SessionReviewScopes.Choice.LOCAL, SessionReviewScopes.Choice.fromPersisted(null));
        assertEquals(SessionReviewScopes.Choice.LOCAL, SessionReviewScopes.Choice.fromPersisted("not-a-choice"));
        assertEquals(SessionReviewScopes.Choice.PULL_REQUEST,
                SessionReviewScopes.Choice.fromPersisted("pull_request"));
        assertEquals(SessionReviewScopes.Choice.PULL_REQUEST,
                SessionReviewScopes.Choice.fromPersisted("Pull_Request"));
    }

    // ---- fixtures, in the style of ReviewQueueServiceTest -------------------

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

    private static void runGit(Path repo, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git"));
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
