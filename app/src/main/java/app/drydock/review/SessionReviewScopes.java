package app.drydock.review;

import app.drydock.domain.ManagedSessionId;
import app.drydock.git.GhCliService;
import app.drydock.git.GitStatusService;
import app.drydock.git.PrCheckoutService;
import app.drydock.git.ReviewBase;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The scopes one checkout offers its session's Review sub-tab (spec §3.2):
 * a local scope always, and a pull-request scope when the checkout's branch
 * carries an open non-draft PR.
 *
 * <p><strong>The kinds are not a free choice.</strong> They are the ones the
 * deleted queue and {@code openCheckedOutPr} already minted for the same
 * places -- {@code WORKING_TREE} for a main checkout, {@code WORKTREE} for a
 * worktree (carrying its PR ref when it has one), {@code PR} for the pull
 * request -- because scope identity is what every finding is keyed by.</p>
 */
public final class SessionReviewScopes {

    /** What a session's Review sub-tab shows: one scope, or two to switch between. */
    public record Scopes(ReviewScope local, Optional<ReviewScope> pullRequest) {
        public Scopes {
            Objects.requireNonNull(local, "local");
            Objects.requireNonNull(pullRequest, "pullRequest");
        }

        /** The scope {@code choice} names, falling back to local when there is no PR. */
        public ReviewScope forChoice(Choice choice) {
            return choice == Choice.PULL_REQUEST ? pullRequest.orElse(local) : local;
        }
    }

    /** The switcher's two chips. Persisted per session (see {@code WorkspaceUiState}). */
    public enum Choice {
        LOCAL,
        PULL_REQUEST;

        /** Lenient parse for persisted values; anything unrecognized is {@link #LOCAL}. */
        public static Choice fromPersisted(String value) {
            if (value == null) {
                return LOCAL;
            }
            for (Choice choice : values()) {
                if (choice.name().equalsIgnoreCase(value)) {
                    return choice;
                }
            }
            return LOCAL;
        }
    }

    private static final String NO_BRANCH = "(no branch)";

    private final GitStatusService gitStatusService;
    private final ReviewScopeRegistry registry;

    public SessionReviewScopes(GitStatusService gitStatusService, ReviewScopeRegistry registry) {
        this.gitStatusService = Objects.requireNonNull(gitStatusService, "gitStatusService");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * Resolves {@code checkoutRoot}'s scopes. Never blocks the caller: the
     * base measurement runs on the git service's own executor, and a git
     * failure (no executable, unreadable checkout) degrades to {@link
     * ReviewBase.Origin#DEFAULT_UNMEASURED} rather than failing the future
     * -- the same degrade {@code ReviewQueueService} applies, because a
     * missing base measurement is no reason to show the Review sub-tab
     * nothing at all.
     *
     * @param pullRequest the open non-draft PR this checkout's branch carries,
     *                    if any -- resolved by the caller, which already holds
     *                    the repository's listing
     */
    public CompletableFuture<Scopes> forCheckout(Path repositoryRoot, Path checkoutRoot,
                                                 Optional<String> branch,
                                                 Optional<ManagedSessionId> session,
                                                 Optional<GhCliService.OpenPullRequest> pullRequest) {
        String head = branch.filter(name -> !name.isBlank()).orElse(NO_BRANCH);
        Optional<ReviewScope.PullRequestRef> ref = pullRequest
                .map(pr -> new ReviewScope.PullRequestRef(pr.number(), pr.url()));
        boolean mainCheckout = checkoutRoot.toAbsolutePath().normalize()
                .equals(repositoryRoot.toAbsolutePath().normalize());
        ReviewScope.Kind localKind = mainCheckout
                ? ReviewScope.Kind.WORKING_TREE : ReviewScope.Kind.WORKTREE;
        // The local scope carries the PR ref only when the checkout's own
        // branch IS the pr-<n> alias PrCheckoutService checks a PR out
        // under -- never for an ordinary branch, and never for the main
        // checkout. The wider rule flips this scope's identity the moment a
        // PR is opened or merged on an ordinary branch, silently detaching
        // every finding already recorded against the worktree from events
        // that have nothing to do with it. The PR scope itself is
        // unaffected: it always carries its ref.
        Optional<ReviewScope.PullRequestRef> localRef = !mainCheckout
                && PrCheckoutService.pullRequestNumberOf(head).isPresent()
                ? ref : Optional.empty();

        return gitStatusService.defaultBranch(repositoryRoot)
                .handle((defaultBranch, failure) -> failure == null ? defaultBranch : Optional.<String>empty())
                .thenCompose(defaultBranch -> {
                    String fallback = defaultBranch.orElse("main");
                    return gitStatusService.reviewBase(checkoutRoot,
                                    pullRequest.map(GhCliService.OpenPullRequest::baseRefName), fallback)
                            .handle((base, failure) -> failure == null ? base
                                    : new ReviewBase(fallback, ReviewBase.Origin.DEFAULT_UNMEASURED));
                })
                .thenApply(base -> {
                    ReviewScope local = registry.mint(ReviewScopeRegistry.spec(
                            localKind, repositoryRoot, Optional.of(checkoutRoot),
                            base.ref(), head, localRef, session, Optional.of(base.origin())));
                    // The PR scope diffs against the SAME resolved base as the
                    // local scope -- reviewBase already privileges the PR's
                    // declared base when one is supplied, and that resolution
                    // (falling back to origin/<name> when there is no local
                    // ref) is what makes the base a revision git can actually
                    // name. Using the raw, unresolved baseRefName here would
                    // let the PR chip's diff fail while the local chip's works.
                    Optional<ReviewScope> pr = pullRequest.map(open -> registry.mint(
                            ReviewScopeRegistry.spec(ReviewScope.Kind.PR, repositoryRoot,
                                    Optional.of(checkoutRoot), base.ref(), head, ref,
                                    session, Optional.of(ReviewBase.Origin.PULL_REQUEST))));
                    return new Scopes(local, pr);
                });
    }
}
