package app.drydock.ui;

import app.drydock.domain.ManagedSessionId;
import app.drydock.git.BranchNotDeletedException;
import app.drydock.git.WorktreeLockedException;
import app.drydock.git.WorktreeNotCleanException;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * The one implementation of "this worktree session is finished": remove the
 * worktree, delete the branch when it is ours, close the session. Used by
 * both the Finish panel's Delete and the merge-and-finish flow -- the app's
 * most destructive sequence exists once, and reports per step instead of
 * throwing one opaque failure.
 *
 * <p>Invariant: the session is deleted only if the worktree is gone. A
 * branch that could not be deleted is a leftover the caller can report and
 * move on from, but a worktree that survived still holds files the user has
 * to look at, and the session's terminal and conversation are how they look
 * at them -- closing it would throw away the context needed to finish the
 * job.</p>
 *
 * <p>Collaborators are narrow interfaces rather than {@code WorktreeService}
 * and {@code SessionManager} so this class -- the app's most destructive
 * sequence -- is testable with fakes and no git. Runs entirely off the FX
 * thread and does no UI work; the caller renders the outcome. The returned
 * future never completes exceptionally: every failure is folded into the
 * outcome, because the caller has copy for each and a partial cleanup is not
 * an error the user can retry blindly.</p>
 */
final class WorktreeSessionCleanup {

    private static final Logger LOG = System.getLogger(WorktreeSessionCleanup.class.getName());

    /** Matches {@code WorktreeService::remove}. */
    interface WorktreeRemoval {
        CompletableFuture<Void> remove(Path repositoryRoot, Path worktree, Optional<String> branch);
    }

    /** Matches {@code SessionManager::deleteSession}. */
    interface SessionDeletion {
        CompletableFuture<Void> delete(ManagedSessionId sessionId);
    }

    private final WorktreeRemoval removal;
    private final SessionDeletion deletion;

    WorktreeSessionCleanup(WorktreeRemoval removal, SessionDeletion deletion) {
        this.removal = removal;
        this.deletion = deletion;
    }

    /**
     * Runs the sequence and reports what each step managed to do.
     *
     * @param plan what the caller has decided may happen to the branch --
     *             {@link MergeFinishDecision#forBranchDelete} for the merge
     *             flow (which refuses a branch whose tip moved since the
     *             merge it verified), {@link
     *             MergeFinishDecision#forRequestedDelete} for a delete the
     *             user asked for outright. Deliberately not a boolean: the
     *             two "keep" reasons get different copy, and one of them is
     *             the only warning the user gets that a commit is not in the
     *             base branch.
     */
    CompletableFuture<MergeFinishDecision.CleanupOutcome> run(ManagedSessionId sessionId, Path repositoryRoot,
                                                              Path worktreeRoot, String branch,
                                                              MergeFinishDecision.BranchDeletePlan plan) {
        // A blank branch name is never ours to pass to `git branch -D`
        // (WorktreeService.removeBlocking silently skips it), so treating it
        // as deletable here would report BranchResult.DELETED for a branch
        // nothing actually touched -- "branch  deleted" (double space) in
        // MergeFinishDecision.forCleanup.
        MergeFinishDecision.BranchDeletePlan effective =
                plan == MergeFinishDecision.BranchDeletePlan.DELETE && branch.isBlank()
                        ? MergeFinishDecision.BranchDeletePlan.KEEP_NOT_OURS
                        : plan;
        boolean deleteBranch = effective == MergeFinishDecision.BranchDeletePlan.DELETE;
        Optional<String> branchToDelete = deleteBranch ? Optional.of(branch) : Optional.empty();
        return attempt(() -> removal.remove(repositoryRoot, worktreeRoot, branchToDelete))
                .handle((ignored, failure) -> classify(failure, effective))
                .thenCompose(partial -> partial.worktreeRemoved()
                        ? closeSession(sessionId, partial)
                        : CompletableFuture.completedFuture(partial));
    }

    /**
     * Guards a collaborator call so a synchronous throw or a {@code null}
     * return becomes a failed future instead of escaping {@link #run} itself
     * or completing the returned future exceptionally further down the
     * chain -- both are real at shutdown: {@code WorktreeService::remove}
     * begins with {@code CompletableFuture.supplyAsync(..., executor)},
     * which throws {@code RejectedExecutionException} once that executor is
     * shut down, and {@code SessionManager.deleteSession} calls
     * {@code Platform.runLater} ({@code IllegalStateException} once the
     * toolkit has stopped). Without this, the class's "never completes
     * exceptionally" guarantee would not hold at exactly the moment
     * (app shutdown, mid-cleanup) it matters most.
     *
     * <p>The mechanism lives in {@link AsyncCalls} because the UI needs it in
     * three places now; what is specific to this class is the guarantee above,
     * which is why the reason it is applied here is documented here.</p>
     */
    private static CompletableFuture<Void> attempt(Supplier<CompletableFuture<Void>> call) {
        return AsyncCalls.attempt(call);
    }

    /**
     * Turns {@code WorktreeRemoval}'s result into the worktree/branch half
     * of the outcome. {@code worktreeKeptReason} is populated only when the
     * worktree survived -- {@link MergeFinishDecision.CleanupOutcome}'s
     * contract is that the field means "why the worktree survived", and
     * carrying anything else there (a branch-delete stderr excerpt, say)
     * would be a fact silently dropped by every renderer that checks
     * {@code worktreeRemoved} first, or worse, misread as the reason the
     * worktree was kept.
     */
    private static MergeFinishDecision.CleanupOutcome classify(
            Throwable failure, MergeFinishDecision.BranchDeletePlan plan) {
        if (failure == null) {
            MergeFinishDecision.BranchResult branch = switch (plan) {
                case DELETE -> MergeFinishDecision.BranchResult.DELETED;
                case KEEP_NOT_OURS -> MergeFinishDecision.BranchResult.KEPT_NOT_OURS;
                case KEEP_MOVED -> MergeFinishDecision.BranchResult.KEPT_MOVED;
            };
            return new MergeFinishDecision.CleanupOutcome(true, branch, false, Optional.empty());
        }
        Throwable cause = UiErrors.unwrap(failure);
        if (cause instanceof BranchNotDeletedException) {
            // The worktree is gone; only `git branch -D` refused. Not a
            // reason to keep the session -- see the class invariant.
            return new MergeFinishDecision.CleanupOutcome(true,
                    MergeFinishDecision.BranchResult.DELETE_FAILED, false, Optional.empty());
        }
        // Anything else means the worktree half itself did not complete:
        // NOT_ATTEMPTED is the only branch result a surviving worktree can
        // report (CleanupOutcome's compact constructor rejects any other
        // pairing with worktreeRemoved=false), and the session must stay
        // open so its terminal and conversation are still there to act on
        // whatever is keeping the worktree dirty.
        String reason;
        if (cause instanceof WorktreeNotCleanException) {
            reason = "it has uncommitted changes";
        } else if (cause instanceof WorktreeLockedException locked) {
            // Lower-case fragment, consistent with "it has uncommitted
            // changes", and names the confirmed-override escape hatch
            // (WorktreeService.removeForced, which RepositorySidebar already
            // offers) rather than the raw exception message -- a capitalised
            // sentence restating a path the panel already shows, with no
            // hint that a retry exists.
            reason = "it is locked" + locked.lockReason().map(r -> " (" + r + ")").orElse("");
        } else {
            reason = Optional.ofNullable(cause.getMessage()).orElse(cause.getClass().getSimpleName());
        }
        return new MergeFinishDecision.CleanupOutcome(false,
                MergeFinishDecision.BranchResult.NOT_ATTEMPTED, false, Optional.of(reason));
    }

    /**
     * Closes the session once the worktree is confirmed gone. A failure here
     * is logged, never swallowed the way {@code handoffDelete}'s private
     * lambda body reports "session closed" regardless of
     * {@code deleteSession}'s outcome -- {@code sessionDeleted()} must
     * reflect what actually happened.
     */
    private CompletableFuture<MergeFinishDecision.CleanupOutcome> closeSession(
            ManagedSessionId sessionId, MergeFinishDecision.CleanupOutcome partial) {
        return attempt(() -> deletion.delete(sessionId)).handle((ignored, failure) -> {
            if (failure == null) {
                return new MergeFinishDecision.CleanupOutcome(partial.worktreeRemoved(), partial.branch(),
                        true, partial.worktreeKeptReason());
            }
            LOG.log(Level.WARNING, "Could not close session " + sessionId + " after worktree cleanup",
                    UiErrors.unwrap(failure));
            return new MergeFinishDecision.CleanupOutcome(partial.worktreeRemoved(), partial.branch(),
                    false, partial.worktreeKeptReason());
        });
    }
}
