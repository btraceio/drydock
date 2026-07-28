package app.drydock.review;

import app.drydock.domain.ManagedSessionId;
import app.drydock.git.GhCliService;
import app.drydock.git.GitBranchState;
import app.drydock.git.GitStatus;
import app.drydock.git.GitStatusService;
import app.drydock.git.WorktreeService;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

    private final WorktreeService worktreeService;
    private final GitStatusService gitStatusService;
    private final ReviewRequestSource reviewRequests;
    private final ReviewScopeRegistry scopeRegistry;

    public ReviewQueueService(WorktreeService worktreeService, GitStatusService gitStatusService,
                              ReviewRequestSource reviewRequests, ReviewScopeRegistry scopeRegistry) {
        this.worktreeService = Objects.requireNonNull(worktreeService, "worktreeService");
        this.gitStatusService = Objects.requireNonNull(gitStatusService, "gitStatusService");
        this.reviewRequests = Objects.requireNonNull(reviewRequests, "reviewRequests");
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
    public CompletableFuture<List<ReviewItem>> assemble(List<RepositoryTarget> repositories,
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
                    return items;
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

        return worktrees.thenCombine(status, (trees, mainStatus) ->
                        new PartialScan(trees.value(), mainStatus.value(), Optional.<String>empty(),
                                trees.complete() && mainStatus.complete()))
                .thenCombine(defaultBranch, (scan, branch) ->
                        new PartialScan(scan.worktrees(), scan.status(), branch.value(),
                                scan.localComplete() && branch.complete()))
                .thenCombine(requests, (scan, prs) -> new RepositoryScan(repository,
                        build(repository, sessions, scan, prs.value()), scan.localComplete(), prs.complete()));
    }

    private record Fetch<T>(T value, boolean complete) { }

    private record PartialScan(List<WorktreeService.Worktree> worktrees, Optional<GitStatus> status,
                               Optional<String> defaultBranch, boolean localComplete) { }

    private record RepositoryScan(RepositoryTarget repository, List<ReviewItem> items,
                                  boolean localComplete, boolean requestsComplete) { }

    private List<ReviewItem> build(RepositoryTarget repository, SessionLookup sessions,
                                   PartialScan scan, List<GhCliService.ReviewRequest> requests) {
        String base = baseBranchOf(scan.defaultBranch(), scan.status());
        List<ReviewItem> items = new ArrayList<>();

        // MINE -- the main checkout's uncommitted work, when there is any.
        if (scan.status().map(GitStatus::dirty).orElse(false)) {
            ReviewScope scope = scopeRegistry.mint(ReviewScopeRegistry.spec(
                    ReviewScope.Kind.WORKING_TREE, repository.root(), Optional.of(repository.root()),
                    base, base, Optional.empty(), sessions.sessionAt(repository.root())));
            items.add(new ReviewItem(scope, ReviewItem.Group.MINE, "Working tree",
                    repository.displayName() + " · uncommitted changes"));
        }

        // MINE / AGENTS -- one item per non-main worktree. A worktree with a
        // bound session is an agent's; one without is the human's own.
        for (WorktreeService.Worktree worktree : scan.worktrees()) {
            if (worktree.mainCheckout() || worktree.prunable()) {
                continue;
            }
            String head = worktree.branch().orElse(worktree.detached() ? "(detached)" : "(no branch)");
            Optional<ManagedSessionId> session = sessions.sessionAt(worktree.path());
            ReviewScope scope = scopeRegistry.mint(ReviewScopeRegistry.spec(
                    ReviewScope.Kind.WORKTREE, repository.root(), Optional.of(worktree.path()),
                    base, head, Optional.empty(), session));
            items.add(new ReviewItem(scope,
                    session.isPresent() ? ReviewItem.Group.AGENTS : ReviewItem.Group.MINE,
                    head, repository.displayName() + " · vs " + base));
        }

        // REQUESTED -- PRs asking this user for a review. A PR whose head
        // branch is already checked out in a worktree is that worktree's
        // item; listing it twice would split its findings across two scopes.
        Set<String> checkedOutBranches = scan.worktrees().stream()
                .map(WorktreeService.Worktree::branch)
                .flatMap(Optional::stream)
                .collect(Collectors.toUnmodifiableSet());
        for (GhCliService.ReviewRequest request : requests) {
            if (checkedOutBranches.contains(request.headRefName())) {
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
