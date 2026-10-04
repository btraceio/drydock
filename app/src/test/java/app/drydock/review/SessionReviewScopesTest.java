package app.drydock.review;

import app.drydock.domain.ManagedSessionId;
import app.drydock.git.DiffScope;
import app.drydock.git.DiffService;
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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scopes one session offers, and -- the part that matters -- the exact
 * identities they are minted with. Identity is (kind, repo, worktree, PR
 * number); every finding is keyed by the id that identity produces, so a
 * kind or a PR ref that drifts here makes existing reviews vanish silently.
 *
 * <p>The checkouts below are real git repositories: {@code defaultBranch}
 * and {@code reviewBase}
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

    private static GhCliService.OpenPullRequest draftPullRequest(int number, String head) {
        return new GhCliService.OpenPullRequest(number, "Title", head, "main", true,
                Optional.empty(), Optional.empty());
    }

    // ---- which PR a checkout carries (pure; no git, no gh) ------------------

    /**
     * The regression the Critical was actually about, pinned at the seam it
     * lived in.
     *
     * <p>The earlier fix moved the draft gate into {@code forCheckout}, and a
     * test there proves the callee is right. It does NOT stop a caller from
     * re-adding {@code .filter(pr -> !pr.draft())} on the way in and
     * recreating the whole data-corruption bug with every other test green --
     * which is exactly what the original defect was. This pins the caller's
     * side of it: the answer to "which open PR does this branch carry" must
     * include drafts, because that answer becomes the local scope's identity.</p>
     */
    @Test
    void theCheckoutCarriesItsPullRequestEvenWhenItIsADraft() {
        List<GhCliService.OpenPullRequest> listing = List.of(draftPullRequest(42, "someones-branch"));

        assertEquals(42, SessionReviewScopes
                        .pullRequestCarriedBy(listing, Optional.of("pr-42")).orElseThrow().number(),
                "a draft is still the PR this pr-42 worktree holds; dropping it mints "
                        + "(WORKTREE, repo, worktree, no-PR) and orphans every finding on it");
    }

    /** The two ways a checkout is recognised as holding a PR, and the one way it is not. */
    @Test
    void aCheckoutCarriesThePullRequestOnItsHeadBranchOrItsPrAlias() {
        List<GhCliService.OpenPullRequest> listing = List.of(pullRequest(42, "someones-branch"));

        assertEquals(42, SessionReviewScopes
                        .pullRequestCarriedBy(listing, Optional.of("pr-42")).orElseThrow().number(),
                "the pr-<n> alias PrCheckoutService checks a PR out under");
        assertEquals(42, SessionReviewScopes
                        .pullRequestCarriedBy(listing, Optional.of("someones-branch")).orElseThrow().number(),
                "the PR's own head branch, for a checkout made outside drydock");
        assertTrue(SessionReviewScopes
                        .pullRequestCarriedBy(listing, Optional.of("feat/unrelated")).isEmpty(),
                "an unrelated branch carries nothing");
        assertTrue(SessionReviewScopes.pullRequestCarriedBy(listing, Optional.empty()).isEmpty(),
                "a detached checkout has no branch to match on");
        assertTrue(SessionReviewScopes.pullRequestCarriedBy(List.of(), Optional.of("pr-42")).isEmpty(),
                "an empty listing (gh missing, unauthenticated, failed) carries nothing");
    }

    /**
     * The alias wins on its number, not on a coincidence of names: {@code
     * pr-42} must not match PR #7 merely because #7 happens to be listed
     * first.
     */
    @Test
    void thePrAliasMatchesTheNumberItNames() {
        List<GhCliService.OpenPullRequest> listing = List.of(
                pullRequest(7, "other-branch"), pullRequest(42, "someones-branch"));

        assertEquals(42, SessionReviewScopes
                .pullRequestCarriedBy(listing, Optional.of("pr-42")).orElseThrow().number());
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
     * A DRAFT pull request checked out as {@code pr-<n>} must mint the very
     * same local identity a non-draft one does.
     *
     * <p>The identity for such a checkout is {@code (WORKTREE, repo,
     * worktree, <n>)} unconditionally -- {@code gh pr list --state open}
     * returns drafts, so a checkout of one is still a checkout of that PR.
     * Filtering drafts out before {@code
     * forCheckout} would mint {@code (WORKTREE, repo, worktree, ∅)} for the
     * same worktree, and every finding, verdict and thread recorded from one
     * surface would be invisible from the other. This is reachable: a
     * review-requested PR can be a draft, and drydock checks those out as
     * {@code pr-<n>}.</p>
     *
     * <p>The chip is a separate question, and IS withheld -- a draft is not
     * yet something anyone is being asked to review.</p>
     */
    @Test
    void aDraftPullRequestMintsTheSameLocalIdentityAsANonDraftOne(
            @TempDir Path dir, @TempDir Path worktreeParent)
            throws ExecutionException, InterruptedException, IOException {
        Path repo = initCommittedRepo(dir);
        Path worktree = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "pr-42").get();

        SessionReviewScopes.Scopes resolved = scopes.forCheckout(
                repo, worktree, Optional.of("pr-42"), Optional.empty(),
                Optional.of(draftPullRequest(42, "someones-branch"))).get();

        assertEquals(42, resolved.local().pr().orElseThrow().number(),
                "the local scope's ref is identity, and a draft PR is still the PR this worktree holds");
        assertTrue(resolved.pullRequest().isEmpty(),
                "a draft gets no second chip: nobody is being asked to review it yet");

        // The cross-check that makes the claim above mean something: mint the
        // scope directly, with the PR ref, through the SAME registry. A
        // matching handle means matching Identity -- (kind, repoRoot,
        // worktree, PR number) -- which is what every finding is keyed by.
        ReviewScope asTheQueueMintsIt = registry.mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.WORKTREE, repo, Optional.of(worktree),
                resolved.local().base(), "pr-42",
                Optional.of(new ReviewScope.PullRequestRef(42, Optional.empty())),
                Optional.empty()));
        assertEquals(asTheQueueMintsIt.id(), resolved.local().id(),
                "a draft pr-42 worktree must resolve to the handle its PR ref already produces");
    }

    /**
     * The rule this task got wrong the first time: a worktree on an
     * ordinary branch that happens to have an open PR does NOT carry the
     * ref locally. A {@code PullRequestRef} is attached to a worktree only
     * via {@code
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
     * The reported defect: a {@code pr-<n>} worktree resolved while the UI's
     * status cache had nothing for it yet (a worktree created a moment ago,
     * or any reveal right after a restart). The branch came back empty, the
     * PR lookup was skipped, and a second local identity with head {@code
     * "(no branch)"} and no PR ref was minted, bound to the session, and
     * never revoked. A cold cache must cost a git call, not an identity.
     */
    @Test
    void aColdCachePrCheckoutResolvesToExactlyItsPullRequestScopes(
            @TempDir Path dir, @TempDir Path worktreeParent)
            throws ExecutionException, InterruptedException, IOException {
        Path repo = initCommittedRepo(dir);
        Path worktree = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "pr-42").get();
        ManagedSessionId session = ManagedSessionId.newId();
        List<GhCliService.OpenPullRequest> listing = List.of(pullRequest(42, "someones-branch"));

        SessionReviewScopes.Scopes resolved = scopes.forSessionCheckout(repo, worktree, Optional.empty(),
                Optional.of(session),
                branch -> CompletableFuture.completedFuture(
                        SessionReviewScopes.pullRequestCarriedBy(listing, branch))).get();

        assertEquals("pr-42", resolved.local().head());
        assertEquals(42, resolved.local().pr().orElseThrow().number());
        assertEquals(42, resolved.pullRequest().orElseThrow().pr().orElseThrow().number());
        List<ReviewScope> minted = registry.scopes().stream()
                .filter(scope -> scope.sessionId().equals(Optional.of(session)))
                .toList();
        assertEquals(2, minted.size(), "exactly {PR/#42, WORKTREE/#42}; got " + minted);
        assertEquals(Set.of(ReviewScope.Kind.PR, ReviewScope.Kind.WORKTREE),
                minted.stream().map(ReviewScope::kind).collect(Collectors.toSet()));
        assertTrue(minted.stream().allMatch(scope -> scope.pr().isPresent()),
                "no PR-less (no branch) sibling identity: " + minted);
    }

    /** A cached branch is used as given: the lookup is asked about exactly that branch. */
    @Test
    void aWarmCacheBranchIsUsedAsGiven(@TempDir Path dir, @TempDir Path worktreeParent)
            throws ExecutionException, InterruptedException, IOException {
        Path repo = initCommittedRepo(dir);
        Path worktree = gitStatusService.createWorktree(repo, worktreeParent.resolve("wt"), "feature/x").get();
        List<Optional<String>> asked = new ArrayList<>();

        SessionReviewScopes.Scopes resolved = scopes.forSessionCheckout(repo, worktree,
                Optional.of("feature/x"), Optional.empty(), branch -> {
                    asked.add(branch);
                    return CompletableFuture.completedFuture(Optional.empty());
                }).get();

        assertEquals(List.of(Optional.of("feature/x")), asked);
        assertEquals("feature/x", resolved.local().head());
    }

    /**
     * A genuinely detached checkout still resolves with no branch -- and its
     * baseline head is a real commit. The {@code "(no branch)"} label is for
     * display and the MCP wire only; handed to {@code rev-parse} it failed
     * on every board render and left every verdict on the scope undatable.
     */
    @Test
    void aDetachedCheckoutsBaselineHeadIsARealCommit(@TempDir Path dir, @TempDir Path worktreeParent)
            throws ExecutionException, InterruptedException, IOException {
        Path repo = initCommittedRepo(dir);
        Path detached = worktreeParent.resolve("detached");
        runGit(repo, "worktree", "add", "--detach", detached.toString());
        List<Optional<String>> asked = new ArrayList<>();

        SessionReviewScopes.Scopes resolved = scopes.forSessionCheckout(repo, detached, Optional.empty(),
                Optional.empty(), branch -> {
                    asked.add(branch);
                    return CompletableFuture.completedFuture(Optional.empty());
                }).get();

        assertEquals(List.of(Optional.<String>empty()), asked, "a detached HEAD has no branch to look up");
        assertEquals("(no branch)", resolved.local().head(), "the wire value is unchanged");
        String revision = SessionReviewScopes.headRevision(resolved.local().head());
        assertEquals("HEAD", revision);
        Optional<String> headCommit = gitStatusService.headCommitBlocking(detached);
        assertTrue(headCommit.isPresent());
        assertEquals(headCommit, gitStatusService.commitForRefBlocking(detached, revision));
    }

    /** Every head label that is not a revision reads as {@code HEAD}; a branch is left alone. */
    @Test
    void headRevisionMapsOnlyLabelsToHead() {
        assertEquals("HEAD", SessionReviewScopes.headRevision("(no branch)"));
        assertEquals("HEAD", SessionReviewScopes.headRevision("(detached)"));
        assertEquals("HEAD", SessionReviewScopes.headRevision(""));
        assertEquals("HEAD", SessionReviewScopes.headRevision("  "));
        assertEquals("HEAD", SessionReviewScopes.headRevision(null));
        assertEquals("pr-42", SessionReviewScopes.headRevision("pr-42"));
        assertEquals("feature/x", SessionReviewScopes.headRevision("feature/x"));
    }

    /**
     * {@code defaultBranch} and {@code reviewBase} both throw {@code
     * GitExecutableNotFoundException} from inside their suppliers when no
     * git executable can be found; without a {@code handle}/{@code
     * exceptionally} step the future would complete exceptionally and the
     * Review sub-tab would get nothing. A service that cannot measure
     * something must degrade to a documented fallback instead.
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

    /** The WARNING records {@link SessionReviewScopes} logs while {@code body} runs. */
    private static List<LogRecord> warningsDuring(ThrowingRunnable body) throws Exception {
        Logger logger = Logger.getLogger(SessionReviewScopes.class.getName());
        List<LogRecord> records = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    synchronized (records) {
                        records.add(record);
                    }
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        try {
            body.run();
        } finally {
            logger.removeHandler(handler);
        }
        synchronized (records) {
            return List.copyOf(records);
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /**
     * A branch read that fails -- the session's checkout is not (or no
     * longer) a repository -- is logged and resolves as no branch: the board
     * still gets a local scope rather than nothing.
     */
    @Test
    void aBranchReadThatFailsOnANonRepositoryIsLoggedAndStillYieldsALocalScope(
            @TempDir Path dir, @TempDir Path notARepo) throws Exception {
        Path repo = initCommittedRepo(dir);
        List<SessionReviewScopes.Scopes> resolved = new ArrayList<>();

        List<LogRecord> warnings = warningsDuring(() -> resolved.add(scopes.forSessionCheckout(repo, notARepo,
                Optional.empty(), Optional.empty(),
                branch -> CompletableFuture.completedFuture(Optional.empty())).get(30, TimeUnit.SECONDS)));

        assertEquals(ReviewScope.Kind.WORKTREE, resolved.get(0).local().kind());
        assertEquals("(no branch)", resolved.get(0).local().head());
        assertTrue(warnings.stream().anyMatch(record -> record.getMessage().contains("Could not read the branch")
                && record.getMessage().contains(notARepo.toString())), "the failure is logged: " + warnings);
    }

    /** As above, when there is no git executable at all. */
    @Test
    void aBranchReadWithNoGitIsLoggedAndStillYieldsALocalScope(@TempDir Path dir) throws Exception {
        Path repo = initCommittedRepo(dir);
        GitExecutableLocator missingLocator = new GitExecutableLocator(Path.of("/nonexistent/git-does-not-exist"));
        try (GitStatusService brokenGitStatusService = new GitStatusService(missingLocator)) {
            SessionReviewScopes brokenScopes = new SessionReviewScopes(brokenGitStatusService, registry);
            List<SessionReviewScopes.Scopes> resolved = new ArrayList<>();

            List<LogRecord> warnings = warningsDuring(() -> resolved.add(brokenScopes.forSessionCheckout(repo,
                    repo, Optional.empty(), Optional.empty(),
                    branch -> CompletableFuture.completedFuture(Optional.empty())).get(30, TimeUnit.SECONDS)));

            assertEquals(ReviewScope.Kind.WORKING_TREE, resolved.get(0).local().kind());
            assertEquals("(no branch)", resolved.get(0).local().head());
            assertTrue(warnings.stream().anyMatch(record -> record.getMessage().contains("Could not read the branch")),
                    "the failure is logged: " + warnings);
        }
    }

    /**
     * A resolve racing shutdown: the git service's executor is gone. The
     * call runs on the FX thread, so it must not throw -- the future must
     * complete, one way or the other, so the board's completion handler
     * replaces "Resolving…".
     */
    @Test
    void aResolveOnAShutDownGitServiceCompletesRatherThanThrows(@TempDir Path dir) throws Exception {
        Path repo = initCommittedRepo(dir);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.shutdown();
        GitStatusService closed = new GitStatusService(new GitExecutableLocator(), executor);
        SessionReviewScopes closedScopes = new SessionReviewScopes(closed, registry);

        CompletableFuture<SessionReviewScopes.Scopes> resolve = closedScopes.forSessionCheckout(repo, repo,
                Optional.empty(), Optional.empty(), branch -> CompletableFuture.completedFuture(Optional.empty()));

        assertTrue(resolve.isDone(), "the future completes instead of hanging the board");
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

    // ---- the base has to RESOLVE, not merely be named ----------------------

    /**
     * The end-to-end regression test for the orphaned-findings bug, carried
     * over from the deleted queue's own suite because nothing else guards it.
     *
     * <p>The base used to be the main checkout's CURRENT branch and part of
     * scope identity, so switching branches in the main checkout re-derived
     * every worktree's handle and silently detached its findings and
     * verdicts. Identity drift of exactly this shape has recurred several
     * times, and it is invisible without a real {@code git switch} behind
     * it: every unit-level assertion stays green while the data goes
     * missing.</p>
     */
    @Test
    void switchingTheMainCheckoutsBranchKeepsEveryHandleAndBase(
            @TempDir Path dir, @TempDir Path worktreeParent) throws Exception {
        Path repo = initCommittedRepo(dir);
        Path worktree = gitStatusService
                .createWorktree(repo, worktreeParent.resolve("wt"), "feat/stable").get();

        ReviewScope before = scopes.forCheckout(repo, worktree, Optional.of("feat/stable"),
                Optional.empty(), Optional.empty()).get().local();

        // Exactly what a reviewer does in another terminal, mid-review.
        runGit(repo, "switch", "-c", "some-other-thing");
        ReviewScope after = scopes.forCheckout(repo, worktree, Optional.of("feat/stable"),
                Optional.empty(), Optional.empty()).get().local();

        assertEquals(before.id(), after.id(),
                "a branch switch in the main checkout must not orphan a worktree's findings");
        assertEquals("main", after.base(),
                "the base is the repository's default branch, not whatever is checked out");
        assertDiffsResolve(after);
    }

    /**
     * The base is only useful if git can resolve it. This runs the real diff
     * for every scope a session can be shown -- a dirty main checkout and two
     * worktrees -- which is what the diff column does. A base naming a branch
     * with no local ref fails with "unknown revision", and the board shows an
     * error where the diff should be.
     */
    @Test
    void everyResolvedBaseActuallyDiffs(@TempDir Path dir, @TempDir Path worktreeParent)
            throws Exception {
        Path repo = initCommittedRepo(dir);
        Path a = gitStatusService.createWorktree(repo, worktreeParent.resolve("a"), "feat/a").get();
        Path b = gitStatusService.createWorktree(repo, worktreeParent.resolve("b"), "feat/b").get();
        Files.writeString(repo.resolve("README.md"), "edited\n");

        assertDiffsResolve(
                scopes.forCheckout(repo, repo, Optional.of("main"),
                        Optional.empty(), Optional.empty()).get().local(),
                scopes.forCheckout(repo, a, Optional.of("feat/a"),
                        Optional.empty(), Optional.empty()).get().local(),
                scopes.forCheckout(repo, b, Optional.of("feat/b"),
                        Optional.empty(), Optional.empty()).get().local());
    }

    /**
     * The regression this guards: {@code origin/HEAD} names a REMOTE default,
     * and {@code git clone -b feat/x} -- or deleting a local main after a
     * merge -- leaves no local branch of that name. Handing the bare name to
     * the diff would fail every worktree scope with "unknown revision".
     *
     * <p>{@code GitStatusServiceTest} covers the PR-hinted {@code
     * origin/develop} case; this is the unhinted one, where nothing but the
     * repository's own remote-tracking refs can supply the answer.</p>
     */
    @Test
    void aDefaultBranchWithNoLocalRefStillDiffs(@TempDir Path parent, @TempDir Path worktreeParent)
            throws Exception {
        Path upstream = initCommittedRepo(parent);
        Path clone = parent.resolve("work");
        runGit(parent, "clone", "--quiet", upstream.toString(), clone.toString());
        runGit(clone, "config", "user.name", "Test");
        runGit(clone, "config", "user.email", "test@example.com");
        runGit(clone, "switch", "--quiet", "-c", "feat/x");
        Files.writeString(clone.resolve("README.md"), "changed\n");
        runGit(clone, "commit", "-qam", "change");
        runGit(clone, "branch", "-D", "main");
        Path worktree = gitStatusService
                .createWorktree(clone, worktreeParent.resolve("wt"), "feat/y").get();

        ReviewScope local = scopes.forCheckout(clone, worktree, Optional.of("feat/y"),
                Optional.empty(), Optional.empty()).get().local();

        assertEquals("origin/main", local.base(),
                "with no local main, the base must be the remote-tracking ref that does resolve");
        assertDiffsResolve(local);
    }

    /**
     * Runs the real diff for each scope and fails on the first that cannot
     * resolve its base.
     *
     * <p>Asserting the base's <em>name</em> is the wrong altitude on its own:
     * a base is only useful if git can resolve it, and a name-only assertion
     * passed happily while {@code origin/HEAD} handed the diff a branch with
     * no local ref. Every test above that asserts a base string ends here.</p>
     */
    private void assertDiffsResolve(ReviewScope... resolved) throws Exception {
        try (DiffService diffService = new DiffService()) {
            for (ReviewScope scope : resolved) {
                DiffScope diffScope = scope.kind() == ReviewScope.Kind.WORKING_TREE
                        ? DiffScope.WORKING_TREE
                        : DiffScope.BASE;
                try {
                    diffService.diff(scope.diffRoot(), diffScope, scope.base()).get();
                } catch (Exception e) {
                    throw new AssertionError("a " + scope.kind() + " scope could not diff against base '"
                            + scope.base() + "': " + e.getCause(), e);
                }
            }
        }
    }

    // ---- fixtures -----------------------------------------------------------

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
