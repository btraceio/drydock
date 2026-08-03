package app.drydock.review;

import app.drydock.domain.ManagedSessionId;
import app.drydock.git.GhCliService;
import app.drydock.git.GitBranchState;
import app.drydock.git.GitStatus;
import app.drydock.git.GitStatusService;
import app.drydock.git.PrCheckoutService;
import app.drydock.git.ReviewBase;
import app.drydock.git.WorktreeService;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Assembles the Review queue (spec §4.1) from what drydock already knows:
 * {@code git worktree list}, {@code git status}, and {@code gh pr list
 * --search "review-requested:@me"}.
 *
 * <p>Every scope it produces is minted through {@link ReviewScopeRegistry},
 * so the same worktree keeps the same handle across rescans and everything
 * keyed by {@code (scopeId, …)} survives a background refresh.</p>
 *
 * <p><strong>Nothing here blocks and nothing here fails the queue.</strong>
 * A repository whose git commands fail, or a missing {@code gh}, contributes
 * no items and is logged; the rest of the queue still assembles. Review
 * degrades, never blocks (spec §6).</p>
 */
public final class ReviewQueueService {

    private static final Logger LOG = System.getLogger(ReviewQueueService.class.getName());

    /** A repository to scan: its main checkout and the name the queue shows. */
    public record RepositoryTarget(Path root, String displayName) {
        public RepositoryTarget {
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(displayName, "displayName");
        }
    }

    /**
     * Resolves the managed session running in a checkout, if any. Supplied
     * by the workspace rather than read here, so this service depends on no
     * session bookkeeping -- and so tests can drive the MINE/AGENTS split
     * without a {@code SessionManager}.
     */
    @FunctionalInterface
    public interface SessionLookup {
        Optional<ManagedSessionId> sessionAt(Path checkoutRoot);
    }

    /**
     * Where the REQUESTED group comes from -- in the app, {@code
     * GhCliService::listReviewRequests}. A narrow function rather than the
     * service itself, because "{@code gh} is absent or failing" is a state
     * the queue must survive and therefore a state tests have to be able to
     * produce, and {@code GhCliService} discovers its executable from
     * {@code PATH} with no seam to take it away.
     */
    @FunctionalInterface
    public interface ReviewRequestSource {
        CompletableFuture<List<GhCliService.ReviewRequest>> forRepository(Path repositoryRoot);
    }

    /**
     * Every open PR, keyed by each local branch name it can appear under --
     * in the app, {@code GhCliService::listOpenPullRequests}. A separate
     * seam from {@link ReviewRequestSource} for the same reason: "{@code gh}
     * is absent or failing" is a state the queue must survive, and therefore
     * one tests have to be able to produce.
     *
     * <p>Distinct from {@link ReviewRequestSource} in what it covers, too:
     * that one is the PRs asking <em>this user</em> for a review, this one
     * is all of them. A PR you opened yourself and checked out to look over
     * is in the second and not the first.</p>
     */
    @FunctionalInterface
    public interface PullRequestBaseSource {
        CompletableFuture<Map<String, GhCliService.OpenPullRequest>> forRepository(Path repositoryRoot);
    }

    /** Stands in for a workspace with no {@code gh}: no branch declares a PR. */
    public static final PullRequestBaseSource NO_PULL_REQUEST_BASES =
            root -> CompletableFuture.completedFuture(Map.of());

    private final WorktreeService worktreeService;
    private final GitStatusService gitStatusService;
    private final ReviewRequestSource reviewRequests;
    private final PullRequestBaseSource pullRequestBases;
    private final ReviewScopeRegistry scopeRegistry;

    public ReviewQueueService(WorktreeService worktreeService, GitStatusService gitStatusService,
                              ReviewRequestSource reviewRequests, ReviewScopeRegistry scopeRegistry) {
        this(worktreeService, gitStatusService, reviewRequests, NO_PULL_REQUEST_BASES, scopeRegistry);
    }

    public ReviewQueueService(WorktreeService worktreeService, GitStatusService gitStatusService,
                              ReviewRequestSource reviewRequests, PullRequestBaseSource pullRequestBases,
                              ReviewScopeRegistry scopeRegistry) {
        this.worktreeService = Objects.requireNonNull(worktreeService, "worktreeService");
        this.gitStatusService = Objects.requireNonNull(gitStatusService, "gitStatusService");
        this.reviewRequests = Objects.requireNonNull(reviewRequests, "reviewRequests");
        this.pullRequestBases = Objects.requireNonNull(pullRequestBases, "pullRequestBases");
        this.scopeRegistry = Objects.requireNonNull(scopeRegistry, "scopeRegistry");
    }

    /**
     * Assembles the whole queue, grouped MINE · AGENTS · REQUESTED · STACK.
     * Repositories are scanned concurrently; the future completes when all
     * of them have either produced items or failed.
     *
     * <p>Handles for scopes confirmed no longer in the queue are revoked, so
     * a worktree that was removed stops being addressable over MCP. A failed
     * scan is not evidence that an existing scope departed.</p>
     */
    public CompletableFuture<QueueAssembly> assemble(List<RepositoryTarget> repositories,
                                                     SessionLookup sessions) {
        Objects.requireNonNull(sessions, "sessions");
        List<CompletableFuture<RepositoryScan>> perRepository = repositories.stream()
                .map(repository -> assembleRepository(repository, sessions))
                .toList();
        return CompletableFuture.allOf(perRepository.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> {
                    List<RepositoryScan> scans = perRepository.stream().map(CompletableFuture::join).toList();
                    List<ReviewItem> items = scans.stream()
                            .flatMap(scan -> scan.items().stream())
                            .sorted(Comparator.comparingInt(item -> item.group().ordinal()))
                            .toList();
                    revokeDepartedScopes(scans);
                    // One repository's failed gh makes the whole queue partial:
                    // the reader cannot be told "complete" about a list that is
                    // missing another repository's pull requests.
                    return new QueueAssembly(items,
                            scans.stream().allMatch(RepositoryScan::localComplete),
                            scans.stream().allMatch(RepositoryScan::requestsComplete));
                });
    }

    /**
     * One repository's items. Worktrees, the main checkout's status and the
     * review-requested PRs are fetched concurrently, then combined -- the
     * PR list is the slow one (a network call), and it must not serialize
     * behind two local git commands.
     */
    private CompletableFuture<RepositoryScan> assembleRepository(RepositoryTarget repository,
                                                                   SessionLookup sessions) {
        CompletableFuture<Fetch<List<WorktreeService.Worktree>>> worktrees =
                worktreeService.list(repository.root()).handle((value, failure) -> {
                    if (failure == null) {
                        return new Fetch<>(value, true);
                    }
                    LOG.log(Level.DEBUG, "Could not list worktrees of " + repository.root(), failure);
                    return new Fetch<>(List.of(), false);
                });
        CompletableFuture<Fetch<Optional<GitStatus>>> status =
                gitStatusService.getStatus(repository.root())
                        .<Optional<GitStatus>>thenApply(Optional::of)
                        .handle((value, failure) -> {
                            if (failure == null) {
                                return new Fetch<>(value, true);
                            }
                            LOG.log(Level.DEBUG, "Could not read status of " + repository.root(), failure);
                            return new Fetch<>(Optional.empty(), false);
                        });
        // The base every review diffs against: the repository's own default
        // branch, NOT whatever the main checkout happens to be on. Deriving
        // it from the current branch made a `git switch` in another terminal
        // silently recompute every queue item's diff.
        CompletableFuture<Fetch<Optional<String>>> defaultBranch =
                gitStatusService.defaultBranch(repository.root()).handle((value, failure) -> {
                    if (failure == null) {
                        return new Fetch<>(value, true);
                    }
                    LOG.log(Level.DEBUG, "Could not resolve the default branch of "
                            + repository.root(), failure);
                    return new Fetch<>(Optional.<String>empty(), false);
                });
        CompletableFuture<Fetch<List<GhCliService.ReviewRequest>>> requests =
                reviewRequests.forRepository(repository.root()).handle((value, failure) -> {
                    if (failure == null) {
                        return new Fetch<>(value, true);
                    }
                    LOG.log(Level.DEBUG, "Could not list review requests for " + repository.root(), failure);
                    return new Fetch<>(List.of(), false);
                });
        // What each branch actually merges into. A failure here is no
        // information, not an error: the base then resolves locally.
        CompletableFuture<Map<String, GhCliService.OpenPullRequest>> prBases =
                pullRequestBases.forRepository(repository.root()).handle((value, failure) -> {
                    if (failure == null) {
                        return value;
                    }
                    LOG.log(Level.DEBUG, "Could not list pull requests for "
                            + repository.root(), failure);
                    return Map.<String, GhCliService.OpenPullRequest>of();
                });

        return worktrees.thenCombine(status, (trees, mainStatus) ->
                        new PartialScan(trees.value(), mainStatus.value(), Optional.<String>empty(),
                                Map.of(), trees.complete() && mainStatus.complete()))
                .thenCombine(defaultBranch, (scan, branch) ->
                        new PartialScan(scan.worktrees(), scan.status(), branch.value(), Map.of(),
                                scan.localComplete() && branch.complete()))
                .thenCombine(prBases, (scan, bases) ->
                        new PartialScan(scan.worktrees(), scan.status(), scan.defaultBranch(), bases,
                                scan.localComplete()))
                .thenCompose(scan -> resolveBases(repository, scan)
                        .thenCombine(requests, (bases, prs) -> new RepositoryScan(repository,
                                build(repository, sessions, scan, bases, prs.value()),
                                scan.localComplete(), prs.complete())));
    }

    /**
     * The base each worktree is reviewed against, keyed by checkout path.
     *
     * <p>Resolved per worktree rather than once per repository, and off this
     * thread: the branch's own PR base when GitHub declares one, otherwise
     * the integration branch it was forked from. One repo-wide default was
     * the bug -- a repository whose {@code origin/HEAD} is {@code master}
     * while every branch is cut from {@code develop} showed each review as
     * the whole of {@code develop} plus the branch.</p>
     */
    private CompletableFuture<Map<Path, ReviewBase>> resolveBases(RepositoryTarget repository,
                                                                   PartialScan scan) {
        String fallback = baseBranchOf(scan.defaultBranch(), scan.status());
        Map<Path, CompletableFuture<ReviewBase>> pending = new LinkedHashMap<>();
        for (WorktreeService.Worktree worktree : scan.worktrees()) {
            if (skipForReview(worktree)) {
                continue;
            }
            Optional<String> declared = worktree.branch()
                    .map(scan.pullRequestBases()::get)
                    .filter(Objects::nonNull)
                    .map(GhCliService.OpenPullRequest::baseRefName);
            pending.put(worktree.path(), gitStatusService
                    .reviewBase(worktree.path(), declared, fallback)
                    .handle((value, failure) -> {
                        if (failure == null) {
                            return value;
                        }
                        LOG.log(Level.DEBUG, "Could not resolve the review base of "
                                + worktree.path(), failure);
                        return new ReviewBase(fallback, ReviewBase.Origin.DEFAULT_UNMEASURED);
                    }));
        }
        return CompletableFuture.allOf(pending.values().toArray(CompletableFuture[]::new))
                .thenApply(ignored -> {
                    Map<Path, ReviewBase> bases = new LinkedHashMap<>();
                    pending.forEach((path, future) -> bases.put(path, future.join()));
                    return Map.copyOf(bases);
                });
    }

    /**
     * Whether a worktree has no place in the review queue at all.
     *
     * <p>The main checkout is the working-tree item's job, and a prunable
     * one no longer exists. A <em>detached</em> one is the addition: it has
     * no branch, so there is nothing to say it merges into, no PR to look
     * its base up by, and nothing a reviewer can approve. It also arrives
     * by the back door -- {@link app.drydock.git.PrCheckoutService} makes
     * its worktree with {@code git worktree add --detach} before {@code gh}
     * puts a branch on it, so an interrupted checkout leaves one behind and
     * every reassembly listed it as a row reading "(detached)".</p>
     */
    private static boolean skipForReview(WorktreeService.Worktree worktree) {
        return worktree.mainCheckout() || worktree.prunable() || worktree.detached();
    }

    private record Fetch<T>(T value, boolean complete) { }

    private record PartialScan(List<WorktreeService.Worktree> worktrees, Optional<GitStatus> status,
                               Optional<String> defaultBranch,
                               Map<String, GhCliService.OpenPullRequest> pullRequestBases,
                               boolean localComplete) { }

    private record RepositoryScan(RepositoryTarget repository, List<ReviewItem> items,
                                  boolean localComplete, boolean requestsComplete) { }

    private List<ReviewItem> build(RepositoryTarget repository, SessionLookup sessions,
                                   PartialScan scan, Map<Path, ReviewBase> worktreeBases,
                                   List<GhCliService.ReviewRequest> requests) {
        String base = baseBranchOf(scan.defaultBranch(), scan.status());
        List<ReviewItem> items = new ArrayList<>();

        // MINE -- the main checkout's uncommitted work, when there is any.
        // Its diff is `git diff HEAD`, so the base is a label here, not a
        // revision the diff resolves.
        if (scan.status().map(GitStatus::dirty).orElse(false)) {
            ReviewScope scope = scopeRegistry.mint(ReviewScopeRegistry.spec(
                    ReviewScope.Kind.WORKING_TREE, repository.root(), Optional.of(repository.root()),
                    base, base, Optional.empty(), sessions.sessionAt(repository.root())));
            items.add(new ReviewItem(scope, ReviewItem.Group.MINE, "Working tree",
                    repository.displayName() + " · uncommitted changes"));
        }

        // MINE / AGENTS / REQUESTED -- one item per non-main worktree. A
        // worktree holding a PR this user was asked to review stays in
        // REQUESTED; of the rest, one with a bound session is an agent's and
        // one without is the human's own.
        Map<Integer, GhCliService.ReviewRequest> requestsByNumber = new LinkedHashMap<>();
        for (GhCliService.ReviewRequest request : requests) {
            requestsByNumber.putIfAbsent(request.number(), request);
        }
        Set<Integer> checkedOutPrs = new LinkedHashSet<>();
        for (WorktreeService.Worktree worktree : scan.worktrees()) {
            if (skipForReview(worktree)) {
                continue;
            }
            String head = worktree.branch().orElse("(no branch)");
            ReviewBase worktreeBase = worktreeBases.getOrDefault(worktree.path(),
                    new ReviewBase(base, ReviewBase.Origin.DEFAULT_UNMEASURED));
            Optional<ManagedSessionId> session = sessions.sessionAt(worktree.path());
            // Reviewing a PR from inside Drydock checks it out as pr-<n>, so
            // the worktree IS that PR. Resolved against every open PR rather
            // than the review-requested ones alone: a PR you opened yourself
            // and checked out to look over before merging is still a pull
            // request, and a row reading "pr-40" tells nobody which.
            Optional<GhCliService.OpenPullRequest> pullRequest = PrCheckoutService
                    .pullRequestNumberOf(head)
                    .map(number -> scan.pullRequestBases().get(PrCheckoutService.localBranchFor(number)))
                    .filter(Objects::nonNull);
            pullRequest.ifPresent(pr -> checkedOutPrs.add(pr.number()));
            // ...but REQUESTED means "asking you for a review", so only a PR
            // that actually does belongs there. Your own goes where any
            // branch of yours goes.
            Optional<GhCliService.ReviewRequest> asked =
                    pullRequest.map(pr -> requestsByNumber.get(pr.number())).filter(Objects::nonNull);
            ReviewScope scope = scopeRegistry.mint(ReviewScopeRegistry.spec(
                    ReviewScope.Kind.WORKTREE, repository.root(), Optional.of(worktree.path()),
                    worktreeBase.ref(), head,
                    pullRequest.map(pr -> new ReviewScope.PullRequestRef(pr.number(),
                            asked.flatMap(GhCliService.ReviewRequest::url))),
                    session, Optional.of(worktreeBase.origin())));
            if (pullRequest.isPresent()) {
                GhCliService.OpenPullRequest pr = pullRequest.get();
                items.add(new ReviewItem(scope,
                        asked.isPresent() ? ReviewItem.Group.REQUESTED
                                : session.isPresent() ? ReviewItem.Group.AGENTS : ReviewItem.Group.MINE,
                        "PR #" + pr.number() + " " + pr.headRefName(),
                        repository.displayName() + " · vs " + worktreeBase.ref()));
            } else {
                items.add(new ReviewItem(scope,
                        session.isPresent() ? ReviewItem.Group.AGENTS : ReviewItem.Group.MINE,
                        head, repository.displayName() + " · vs " + worktreeBase.ref()));
            }
        }

        // REQUESTED -- PRs asking this user for a review that are not checked
        // out anywhere. One already checked out is the worktree's item above;
        // listing it twice would split its findings across two scopes.
        Set<String> checkedOutBranches = scan.worktrees().stream()
                .filter(worktree -> !skipForReview(worktree))
                .map(WorktreeService.Worktree::branch)
                .flatMap(Optional::stream)
                .collect(Collectors.toUnmodifiableSet());
        for (GhCliService.ReviewRequest request : requests) {
            if (checkedOutBranches.contains(request.headRefName())
                    || checkedOutPrs.contains(request.number())) {
                continue;
            }
            ReviewScope scope = scopeRegistry.mint(ReviewScopeRegistry.spec(
                    ReviewScope.Kind.PR, repository.root(), Optional.empty(),
                    request.baseRefName(), request.headRefName(),
                    Optional.of(new ReviewScope.PullRequestRef(request.number(), request.url())),
                    Optional.empty()));
            items.add(new ReviewItem(scope, ReviewItem.Group.REQUESTED,
                    "PR #" + request.number() + " " + request.headRefName(),
                    prSubtitle(repository, request)));
        }

        return List.copyOf(items);
    }

    private static String prSubtitle(RepositoryTarget repository, GhCliService.ReviewRequest request) {
        StringBuilder subtitle = new StringBuilder(repository.displayName());
        request.author().ifPresent(author -> subtitle.append(" · @").append(author));
        if (request.changedFiles() > 0) {
            subtitle.append(" · ").append(request.changedFiles())
                    .append(request.changedFiles() == 1 ? " file" : " files");
        }
        if (request.draft()) {
            subtitle.append(" · draft");
        }
        return subtitle.append(" · not checked out").toString();
    }

    /**
     * The branch every worktree in this repository is reviewed against: the
     * repository's <em>default</em> branch.
     *
     * <p>It used to be the main checkout's current branch. That made the base
     * -- and so every queue item's diff -- follow whatever the user happened
     * to have checked out, so switching branches in another terminal silently
     * recomputed every review against a different thing, leaving findings
     * anchored by line key pointing at unrelated code.</p>
     *
     * <p>The current branch is still the last resort, for a repository with
     * no {@code origin/HEAD} and none of the conventional default names; a
     * detached checkout with neither leaves {@code HEAD}.</p>
     */
    private static String baseBranchOf(Optional<String> defaultBranch, Optional<GitStatus> status) {
        return defaultBranch
                .or(() -> status.map(GitStatus::branch)
                        .filter(GitBranchState.OnBranch.class::isInstance)
                        .map(branch -> ((GitBranchState.OnBranch) branch).name()))
                .orElse("HEAD");
    }

    /**
     * Revokes handles only when the source authoritative for their kind
     * completed successfully and no longer lists them. Without this a pruned
     * worktree's handle would stay addressable over MCP; treating a failed
     * command as an empty result would instead revoke live scopes.
     */
    private void revokeDepartedScopes(List<RepositoryScan> scans) {
        for (ReviewScope scope : scopeRegistry.scopes()) {
            Optional<RepositoryScan> scan = scans.stream()
                    .filter(candidate -> candidate.repository().root().toAbsolutePath().normalize()
                            .equals(scope.repoRoot().toAbsolutePath().normalize()))
                    .findFirst();
            if (scan.isEmpty()) {
                continue;
            }
            boolean sourceCompleted = scope.kind() == ReviewScope.Kind.PR
                    ? scan.get().requestsComplete()
                    : scan.get().localComplete();
            boolean stillLive = scan.get().items().stream()
                    .anyMatch(item -> item.scope().id().equals(scope.id()));
            if (sourceCompleted && !stillLive) {
                scopeRegistry.revoke(scope.id());
            }
        }
    }

    /** Exposed for the sidebar's per-worktree {@code ◨n} badge lookup. */
    public Function<Path, Optional<ReviewScope>> scopeByWorktree() {
        return path -> scopeRegistry.scopes().stream()
                .filter(scope -> scope.worktree().filter(path::equals).isPresent())
                .findFirst();
    }
}
