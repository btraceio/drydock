package app.drydock.review;

import app.drydock.domain.ManagedSessionId;
import app.drydock.git.GhCliService;
import app.drydock.git.GitStatusService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        assertEquals(Optional.of(repo), resolved.local().worktree());
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
        assertEquals(Optional.of(worktree), resolved.local().worktree());
        assertEquals("feature/x", resolved.local().head());
    }

    @Test
    void theLocalScopeCarriesThePullRequestRefBecauseIdentityIncludesIt(
            @TempDir Path dir, @TempDir Path worktreeParent)
            throws ExecutionException, InterruptedException, IOException {
        Path repo = initCommittedRepo(dir);
        Path worktree = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "pr-42").get();

        SessionReviewScopes.Scopes resolved = scopes.forCheckout(
                repo, worktree, Optional.of("pr-42"), Optional.empty(),
                Optional.of(pullRequest(42, "someones-branch"))).get();

        // The queue minted PR-holding worktrees exactly this way; minting it
        // with an empty ref would be a different handle and would orphan
        // every finding already recorded against this worktree.
        assertEquals(ReviewScope.Kind.WORKTREE, resolved.local().kind());
        assertEquals(42, resolved.local().pr().orElseThrow().number());
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
        assertEquals(Optional.of(worktree), pr.worktree());
        assertEquals(42, pr.pr().orElseThrow().number());
        assertTrue(pr.diffable());
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
