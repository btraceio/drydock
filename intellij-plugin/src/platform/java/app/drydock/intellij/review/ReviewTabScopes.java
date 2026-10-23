package app.drydock.intellij.review;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.git.GitStatusService;
import app.drydock.git.PrCheckoutService;
import app.drydock.git.WorktreeService;
import app.drydock.git.WorktreeService.Worktree;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import app.drydock.review.SessionReviewScopes;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * The Review tab's source of the scope it shows: the local scope of the
 * project's own checkout, resolved so that an existing scope is never
 * rebound (AC19).
 *
 * <p>Unlike {@link SessionReviewScopeResolver} -- which rebinds a checkout's
 * scope to the session that asks -- this class is read-mostly: when the
 * registry already holds the checkout's identity, that scope is handed out
 * exactly as it is. {@link ReviewScopeRegistry#mint} <em>updates an existing
 * handle in place</em>, session binding and base/head included, so minting
 * over a live identity here would take the scope away from a running agent
 * mid-review ({@code isAddressableBy} would then refuse its {@code review_*}
 * calls). A new scope is minted only when the registry holds none, through
 * the same {@link SessionReviewScopes#forCheckout} path the resolver uses,
 * so the agent and the tab diff the same base.</p>
 */
public final class ReviewTabScopes {

    private final WorktreeService worktrees;
    private final GitStatusService git;
    private final ReviewScopeRegistry registry;

    /**
     * @param sessions the workspace's live sessions. Accepted (and required
     *                 non-null) so T13 can wire this beside {@link
     *                 SessionReviewScopeResolver} from the same supplier, but
     *                 never read: this class neither refuses a holder nor
     *                 rebinds, so it needs no liveness check.
     */
    public ReviewTabScopes(WorktreeService worktrees, GitStatusService git,
                           ReviewScopeRegistry registry,
                           Supplier<List<ManagedAgentSession>> sessions) {
        this.worktrees = Objects.requireNonNull(worktrees, "worktrees");
        this.git = Objects.requireNonNull(git, "git");
        this.registry = Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(sessions, "sessions");
    }

    /**
     * The local review scope of the tab's checkout, never rebinding an
     * existing scope.
     *
     * <p>The checkout is located in git's worktree list by <em>real</em>
     * path (macOS lists {@code /private/var/...} where a temporary
     * directory is {@code /var/...}); listed entries whose directory is
     * gone (prunable) are skipped, because resolving their path fails.
     * Never blocks the caller: git runs on the services' executors.
     * Completes exceptionally with a {@link java.util.concurrent.CompletionException}
     * whose cause is {@link app.drydock.git.NotAGitRepositoryException} when
     * the directory is outside a git repository, or {@link IllegalStateException}
     * when git does not list it as a worktree.</p>
     *
     * <p>When the registry already holds this checkout's identity -- kind,
     * repository root, worktree, PR number, the fields {@code
     * ReviewScopeRegistry.Identity} hashes -- that scope is returned
     * unchanged, whoever holds it and whatever the Changes link named. Only
     * an absent scope is minted, bound to {@code linkSession} (the session
     * whose Changes link opened the tab; empty when the tab was opened
     * directly, Q7) or to nobody.</p>
     *
     * <p><strong>Accepted race.</strong> Between the registry scan and the
     * mint, a concurrent mint of the same identity could rebind the scope;
     * this load would then hand out a value minted after it, not the
     * winner's. In P2 the Changes-link path is the only minter, so the
     * window is two tab loads racing on one checkout, and the damage is one
     * display-side handle -- never a silently re-bound running agent.</p>
     */
    public CompletableFuture<ReviewScope> resolve(Path checkout, Optional<ManagedAgentSession> linkSession) {
        return worktrees.list(checkout).thenCompose(listed -> {
            Worktree main = listed.stream().filter(Worktree::mainCheckout).findFirst()
                    .orElseThrow(() -> new IllegalStateException(checkout + " is not in a git repository"));
            Worktree own = ownEntry(listed, checkout);
            ReviewScope.Kind kind = kindOf(main, own);
            ReviewScope existing = registered(main, own, kind);
            if (existing != null) {
                return CompletableFuture.completedFuture(existing);
            }
            return new SessionReviewScopes(git, registry)
                    .forCheckout(main.path(), own.path(), own.branch(),
                            linkSession.map(ManagedAgentSession::id), Optional.empty())
                    .thenApply(SessionReviewScopes.Scopes::local);
        });
    }

    /** The kind {@code forCheckout} mints: the main checkout is a working tree, a linked checkout a worktree. */
    private static ReviewScope.Kind kindOf(Worktree main, Worktree own) {
        return main.path().toAbsolutePath().normalize().equals(own.path().toAbsolutePath().normalize())
                ? ReviewScope.Kind.WORKING_TREE
                : ReviewScope.Kind.WORKTREE;
    }

    /**
     * The registered scope of this checkout's identity, or {@code null} when
     * the registry holds none. Compared on exactly {@code
     * ReviewScopeRegistry.Identity}'s fields: the kind, the normalized
     * repository root (the main checkout), the normalized worktree, and the
     * PR number -- derived the way {@link SessionReviewScopes#forCheckout}
     * derives a local scope's ref: only a linked worktree on a {@code
     * pr-<n>} branch carries one, never the main checkout.
     */
    private ReviewScope registered(Worktree main, Worktree own, ReviewScope.Kind kind) {
        Path repoRoot = main.path().toAbsolutePath().normalize();
        Optional<Path> worktree = Optional.of(own.path().toAbsolutePath().normalize());
        Optional<Integer> pr = kind == ReviewScope.Kind.WORKING_TREE
                ? Optional.empty()
                : PrCheckoutService.pullRequestNumberOf(own.branch().orElse(null));
        for (ReviewScope scope : registry.scopes()) {
            if (scope.kind() == kind
                    && scope.repoRoot().toAbsolutePath().normalize().equals(repoRoot)
                    && scope.worktree().map(path -> path.toAbsolutePath().normalize()).equals(worktree)
                    && scope.pr().map(ReviewScope.PullRequestRef::number).equals(pr)) {
                return scope;
            }
        }
        return null;
    }

    /**
     * The entry whose real path equals {@code checkout}'s. Real paths are
     * compared because git reports {@code /private/var/...} where a macOS
     * temporary directory is {@code /var/...}. An entry whose path cannot be
     * resolved is skipped (a prunable entry, whose directory is already
     * gone -- resolving it would fail every session's review in the repo);
     * only {@code checkout} failing to resolve is an error. Ported from
     * {@link SessionReviewScopeResolver}; {@code resolve(session)} itself is
     * deliberately not called, because it rebinds.
     */
    private static Worktree ownEntry(List<Worktree> listed, Path checkout) {
        Path real;
        try {
            real = checkout.toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (Worktree entry : listed) {
            try {
                if (entry.path().toRealPath().equals(real)) {
                    return entry;
                }
            } catch (IOException e) {
                continue;
            }
        }
        throw new IllegalStateException("git does not list " + checkout + " as a worktree");
    }
}
