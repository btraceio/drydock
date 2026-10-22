package app.drydock.intellij.review;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.SessionStatusFacet;
import app.drydock.git.GitStatusService;
import app.drydock.git.NotAGitRepositoryException;
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
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * Resolves the {@link ReviewScope} of a session's own checkout.
 *
 * <p>The repository root handed to {@link SessionReviewScopes#forCheckout} is
 * the main checkout git lists for the session's directory, not the repository
 * root recorded in the session catalog. {@code forCheckout} picks {@code
 * WORKING_TREE} only when the checkout equals that root, so passing the
 * catalog's root would mint {@code WORKING_TREE} for every linked worktree.</p>
 *
 * <p>The pull request is left empty. A {@code pr-<n>} branch still carries
 * its PR ref, because {@code forCheckout} derives it from the branch name.
 * Never blocks the caller: git runs on the executors of the services passed
 * to the constructor.</p>
 */
public final class SessionReviewScopeResolver {

    private final WorktreeService worktrees;
    private final SessionReviewScopes scopes;
    private final ReviewScopeRegistry registry;
    private final Supplier<List<ManagedAgentSession>> sessions;

    public SessionReviewScopeResolver(WorktreeService worktrees, GitStatusService git,
                                      ReviewScopeRegistry registry,
                                      Supplier<List<ManagedAgentSession>> sessions) {
        this.worktrees = Objects.requireNonNull(worktrees, "worktrees");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.scopes = new SessionReviewScopes(git, registry);
    }

    /**
     * The scope of {@code session}'s working directory, bound to the session.
     * Resolving rebinds the scope from a session that is not running. The future
     * completes exceptionally with a {@link CompletionException} whose cause is
     * {@link IllegalStateException} when a different running session holds the
     * checkout's scope. The check and the bind are not atomic, so two
     * requests that resolve at the same moment can still rebind.
     * Completes exceptionally with a {@link CompletionException} whose cause is
     * {@link NotAGitRepositoryException} when the directory is outside a git
     * repository, or {@link IllegalStateException} when git does not list it as
     * a worktree. Listed entries whose directory is gone (prunable worktrees)
     * are skipped.
     */
    public CompletableFuture<ReviewScope> resolve(ManagedAgentSession session) {
        Path checkout = session.workingDirectory();
        return worktrees.list(checkout).thenCompose(listed -> {
            Worktree main = listed.stream().filter(Worktree::mainCheckout).findFirst()
                    .orElseThrow(() -> new IllegalStateException(checkout + " is not in a git repository"));
            Worktree own = ownEntry(listed, checkout);
            refuseIfHeldByRunningSession(session, main, own);
            return scopes.forCheckout(main.path(), own.path(), own.branch(),
                    Optional.of(session.id()), Optional.empty());
        }).thenApply(SessionReviewScopes.Scopes::local);
    }

    /**
     * Throws when the scope of this checkout is bound to a different session
     * that is running. The kind rule is the one {@code forCheckout} applies;
     * the pull request ref is not compared, so a scope of the same checkout
     * with another ref also counts.
     */
    private void refuseIfHeldByRunningSession(ManagedAgentSession session, Worktree main, Worktree own) {
        ReviewScope.Kind kind = main.path().toAbsolutePath().normalize()
                .equals(own.path().toAbsolutePath().normalize())
                ? ReviewScope.Kind.WORKING_TREE : ReviewScope.Kind.WORKTREE;
        for (ReviewScope held : registry.scopes()) {
            if (held.kind() != kind || !held.repoRoot().equals(main.path())
                    || !held.worktree().equals(Optional.of(own.path()))
                    || held.sessionId().isEmpty() || held.sessionId().get().equals(session.id())) {
                continue;
            }
            for (ManagedAgentSession other : sessions.get()) {
                if (other.id().equals(held.sessionId().get())
                        && SessionStatusFacet.of(other.status()) == SessionStatusFacet.RUNNING) {
                    throw new IllegalStateException("the running task " + other.displayName()
                            + " holds this checkout's review; deactivate it first");
                }
            }
        }
    }

    /**
     * The entry whose real path equals {@code checkout}'s. Real paths are
     * compared because git reports {@code /private/var/...} where a macOS
     * temporary directory is {@code /var/...}. An entry whose path cannot be
     * resolved is skipped; only {@code checkout} failing to resolve is an error.
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
