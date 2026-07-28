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
     * <p>Handles for scopes that are no longer in the queue are revoked, so
     * a worktree that was removed stops being addressable over MCP.</p>
     */
    public CompletableFuture<List<ReviewItem>> assemble(List<RepositoryTarget> repositories,
                                                        SessionLookup sessions) {
        Objects.requireNonNull(sessions, "sessions");
        List<CompletableFuture<List<ReviewItem>>> perRepository = repositories.stream()
                .map(repository -> assembleRepository(repository, sessions))
                .toList();
        return CompletableFuture.allOf(perRepository.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> {
                    List<ReviewItem> items = perRepository.stream()
                            .flatMap(future -> future.join().stream())
                            .sorted(Comparator.comparingInt(item -> item.group().ordinal()))
                            .toList();
                    revokeDepartedScopes(items);
                    return items;
                });
    }

    /**
     * One repository's items. Worktrees, the main checkout's status and the
     * review-requested PRs are fetched concurrently, then combined -- the
     * PR list is the slow one (a network call), and it must not serialize
     * behind two local git commands.
     */
    private CompletableFuture<List<ReviewItem>> assembleRepository(RepositoryTarget repository,
                                                                   SessionLookup sessions) {
        CompletableFuture<List<WorktreeService.Worktree>> worktrees =
                worktreeService.list(repository.root()).exceptionally(failure -> {
                    LOG.log(Level.DEBUG, "Could not list worktrees of " + repository.root(), failure);
                    return List.of();
                });
        CompletableFuture<Optional<GitStatus>> status =
                gitStatusService.getStatus(repository.root())
                        .<Optional<GitStatus>>thenApply(Optional::of)
                        .exceptionally(failure -> {
                            LOG.log(Level.DEBUG, "Could not read status of " + repository.root(), failure);
                            return Optional.empty();
                        });
        CompletableFuture<List<GhCliService.ReviewRequest>> requests =
                reviewRequests.forRepository(repository.root()).exceptionally(failure -> {
                    LOG.log(Level.DEBUG, "Could not list review requests for " + repository.root(), failure);
                    return List.of();
                });

        return worktrees.thenCombine(status, PartialScan::new)
                .thenCombine(requests, (scan, prs) -> build(repository, sessions, scan, prs));
    }

    private record PartialScan(List<WorktreeService.Worktree> worktrees, Optional<GitStatus> status) { }

    private List<ReviewItem> build(RepositoryTarget repository, SessionLookup sessions,
                                   PartialScan scan, List<GhCliService.ReviewRequest> requests) {
        String base = baseBranchOf(scan.status());
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
     * main checkout's current branch, which is what {@code DiffService}'s
     * {@code BASE} scope already diffs against. A detached main checkout has
     * no name to show, so {@code HEAD} stands in.
     */
    private static String baseBranchOf(Optional<GitStatus> status) {
        return status.map(GitStatus::branch)
                .filter(GitBranchState.OnBranch.class::isInstance)
                .map(branch -> ((GitBranchState.OnBranch) branch).name())
                .orElse("HEAD");
    }

    /**
     * Revokes handles for scopes that are no longer in the queue. Without
     * this a pruned worktree's handle would stay addressable over MCP for
     * the life of the process (schema §0: the handle is revoked when the
     * item leaves the queue).
     */
    private void revokeDepartedScopes(List<ReviewItem> items) {
        Set<String> live = items.stream()
                .map(item -> item.scope().id())
                .collect(Collectors.toUnmodifiableSet());
        for (ReviewScope scope : scopeRegistry.scopes()) {
            if (!live.contains(scope.id())) {
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
