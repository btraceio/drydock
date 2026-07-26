package app.drydock.ui;

import app.drydock.git.WorktreeService.MergeTarget;
import app.drydock.git.WorktreeService.MergeVerdict;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The merge-and-finish flow's decisions and its user-visible copy, with no
 * JavaFX in sight (see docs/superpowers/specs/2026-07-25-merge-and-finish-design.md).
 *
 * <p>The flow deletes a worktree, a branch and a session, so the rule that
 * matters is that {@link Next.CleanUp} is reachable from exactly two
 * verdicts -- {@link MergeVerdict.Merged} and {@link
 * MergeVerdict.AlreadyMerged} -- and from nothing else. Keeping that rule
 * here, in a class no test needs a toolkit to exercise, is why it is
 * separate from {@code MergeAndFinishFlow}.</p>
 */
final class MergeFinishDecision {

    private MergeFinishDecision() {
    }

    /** What the flow does next, and what it puts on screen while doing it. */
    sealed interface Next {

        /** Pre-flight passed; run the merge. */
        record Merge() implements Next { }

        /** Conflicts: send {@code prompt} to the session's Claude, then poll. */
        record HandOff(String headline, String prompt) implements Next { }

        /** The poll saw nothing conclusive; wait and probe again. */
        record KeepWaiting() implements Next { }

        /** The merge is confirmed; run the destructive cleanup. */
        record CleanUp() implements Next { }

        /** Terminal success. {@code detail} reports every cleanup step. */
        record Done(String headline, String detail) implements Next {

            /** The dismiss button's label: the work is finished, so "Done". */
            String buttonLabel() {
                return "Done";
            }
        }

        /** Terminal stop. Nothing destructive has run. */
        record Stopped(String headline, String detail) implements Next {

            /**
             * The dismiss button's label. "Close", never "Done": under
             * "✗ The merge was abandoned" a Done button reads as if the flow
             * had accomplished what it set out to do.
             */
            String buttonLabel() {
                return "Close";
            }
        }
    }

    /**
     * What the cleanup managed to do, step by step.
     *
     * @param worktreeKeptReason why the worktree survived, as a fragment meant to read after
     *                           "worktree kept — "; ignored when {@code worktreeRemoved} is true
     */
    record CleanupOutcome(boolean worktreeRemoved, BranchResult branch, boolean sessionDeleted,
                          Optional<String> worktreeKeptReason) {

        CleanupOutcome {
            // NOT_ATTEMPTED means "the worktree survived, so the branch delete was never
            // tried" -- pairing it with a removed worktree would be a contradiction Task 5
            // could otherwise construct by accident, and forCleanup would render it as a
            // bare "kept" with no reason for the user to act on.
            //
            // KEPT_MOVED deliberately gets no equivalent rule: unlike NOT_ATTEMPTED it
            // makes no claim about the worktree at all -- it is decided BEFORE the
            // destructive step, from the branch tip alone -- so it is honest with the
            // worktree removed ("worktree removed · branch feat/x kept — it moved since
            // the merge") and equally honest if the removal then failed. A rule here would
            // reject a state that is true.
            if (worktreeRemoved && branch == BranchResult.NOT_ATTEMPTED) {
                throw new IllegalArgumentException(
                        "a removed worktree cannot have NOT_ATTEMPTED as its branch result");
            }
        }
    }

    /** The fate of the branch. {@code NOT_ATTEMPTED}: the worktree survived, so nothing was tried. */
    enum BranchResult { DELETED, KEPT_NOT_OURS, KEPT_MOVED, DELETE_FAILED, NOT_ATTEMPTED }

    /**
     * What the destructive step is allowed to do with the branch, decided
     * here and passed into the cleanup rather than re-derived there.
     *
     * <p>{@code KEEP_MOVED} is the arm that prevents lost commits: see
     * {@link #forBranchDelete}.</p>
     */
    enum BranchDeletePlan { DELETE, KEEP_NOT_OURS, KEEP_MOVED }

    /**
     * Whether {@code git branch -D} may run, asked again immediately before
     * the destructive step instead of being inherited from the pre-flight.
     *
     * <p>The failure this prevents is silent commit loss. The merge oracle
     * proves that a merge commit of the tip <em>recorded at pre-flight</em>
     * sits on the base branch; it says nothing about where
     * {@code refs/heads/<branch>} points now. On the conflict hand-off path
     * minutes elapse between the two, and the session's Claude runs with the
     * worktree as its cwd -- so the user, or that agent, can land a commit on
     * the branch in the meantime. {@code git branch -D} never refuses, and it
     * drops the branch's reflog, so such a commit would be recoverable only
     * through {@code git fsck --lost-found} while the modal claimed the merge
     * had taken everything.</p>
     *
     * @param branchIsOurs whether drydock created the branch (a branch that
     *                     already existed outlives its worktree either way)
     * @param recordedTip  the tip the oracle proved merged
     * @param currentTip   the tip as re-read at the destructive step, or
     *                     empty when the re-read itself failed -- which is
     *                     deliberately treated as drift rather than as "no
     *                     drift": an unanswered question about a destructive
     *                     step is a refusal, and keeping a branch costs the
     *                     user one {@code git branch -d}, while deleting the
     *                     wrong one costs them a commit
     */
    static BranchDeletePlan forBranchDelete(boolean branchIsOurs, Optional<String> recordedTip,
                                            Optional<String> currentTip) {
        if (!branchIsOurs) {
            return BranchDeletePlan.KEEP_NOT_OURS;
        }
        if (currentTip.isEmpty() || !currentTip.equals(recordedTip)) {
            return BranchDeletePlan.KEEP_MOVED;
        }
        return BranchDeletePlan.DELETE;
    }

    /**
     * The plan for the Finish panel's own Delete, which the user asked for
     * outright: there is no merge oracle to invalidate and no promise that
     * the branch's commits are anywhere else, so a moved tip is not a reason
     * to refuse what was requested.
     */
    static BranchDeletePlan forRequestedDelete(boolean branchIsOurs) {
        return branchIsOurs ? BranchDeletePlan.DELETE : BranchDeletePlan.KEEP_NOT_OURS;
    }

    /**
     * Whether to merge at all. Every refusal here happens before anything is
     * written: a detached or drifted main checkout would take the merge
     * commit somewhere the user did not ask for, an operation already in
     * progress would make our own merge's failure unreadable, and an unclean
     * worktree cannot survive the cleanup that follows a successful merge.
     */
    static Next forPreflight(MergeTarget target, String expectedBase, String branch, boolean worktreeClean) {
        if (target.baseBranch().isEmpty()) {
            return new Next.Stopped("The main checkout is not on a branch",
                    "Its HEAD is detached, so a merge committed there would be lost once " + branch
                            + " is deleted. Check out " + expectedBase
                            + " in the main checkout, then finish again. Nothing was merged.");
        }
        String actualBase = target.baseBranch().get();
        if (!actualBase.equals(expectedBase)) {
            return new Next.Stopped("The main checkout is on " + actualBase + ", not " + expectedBase,
                    "Check out " + expectedBase + " there, then finish again. Nothing was merged.");
        }
        if (target.inProgress() != MergeTarget.InProgress.NONE) {
            return new Next.Stopped("The main checkout has a " + describe(target.inProgress()) + " in progress",
                    "Finish or abort it there, then finish again. Nothing was merged.");
        }
        if (target.branchTipOid().isEmpty()) {
            return new Next.Stopped("Branch " + branch + " no longer exists",
                    "There is nothing to merge. Nothing was merged.");
        }
        if (!worktreeClean) {
            return new Next.Stopped("The worktree has uncommitted changes",
                    "Finishing removes the worktree, which would discard them. Commit or discard them first,"
                            + " then finish again. Nothing was merged.");
        }
        return new Next.Merge();
    }

    private static String describe(MergeTarget.InProgress inProgress) {
        return switch (inProgress) {
            // The caller only reaches this branch guarded by `!= NONE`; landing here is a
            // bug, not a state to word nicely for the user.
            case NONE -> throw new IllegalArgumentException("guarded by the caller");
            case MERGE -> "merge";
            case REBASE -> "rebase";
            case CHERRY_PICK -> "cherry-pick";
            case REVERT -> "revert";
            case BISECT -> "bisect";
        };
    }

    /**
     * What a verdict means. {@code afterHandOff} is the difference between
     * "this is the answer" and "the agent is still working": once Claude has
     * been asked to resolve the conflicts, anything short of a confirmed
     * merge or an explicit abandonment is a reason to wait, never a reason
     * to delete.
     */
    static Next forVerdict(MergeVerdict verdict, String mainCheckout, String branch, String base,
                           boolean afterHandOff) {
        return switch (verdict) {
            case MergeVerdict.Merged ignored -> new Next.CleanUp();
            case MergeVerdict.AlreadyMerged ignored -> new Next.CleanUp();
            case MergeVerdict.Conflicted conflicted -> afterHandOff
                    ? new Next.KeepWaiting()
                    : new Next.HandOff(
                            "Conflicts in " + conflicted.unmergedPaths().size()
                                    + (conflicted.unmergedPaths().size() == 1 ? " file" : " files")
                                    + " — Claude is resolving them in the main checkout…",
                            handOffPrompt(mainCheckout, branch, base, conflicted.unmergedPaths()));
            case MergeVerdict.NotMerged ignored -> afterHandOff
                    ? new Next.Stopped("The merge was abandoned",
                            "The merge of " + branch + " into " + base
                                    + " was aborted in the main checkout. Nothing was deleted.")
                    : new Next.Stopped("Nothing was merged",
                            "Git left " + base + " unchanged and reported no conflicts. Nothing was deleted.");
            case MergeVerdict.Refused refused -> afterHandOff
                    ? new Next.KeepWaiting()
                    : new Next.Stopped("Could not merge " + branch + " into " + base, refused.detail());
            case MergeVerdict.Indeterminate indeterminate -> afterHandOff
                    ? new Next.KeepWaiting()
                    : new Next.Stopped("Could not confirm the merge", indeterminate.detail());
        };
    }

    /**
     * The conflict hand-off prompt. The agent's cwd is the worktree, not the
     * main checkout, so every path and every command is spelled out against
     * {@code mainCheckout} explicitly -- a relative path or a bare
     * subcommand would read as being about the worktree the agent is
     * sitting in, which is exactly the file the merge conflict is not in.
     * The forbidden shortcuts ({@code merge --abort}, {@code reset --hard},
     * {@code checkout}, {@code merge -s ours}) are each prefixed with
     * {@code git -C <mainCheckout>} for the same reason: they are exactly
     * the commands that would leave the main checkout looking merged
     * without the work actually being in it.
     */
    private static String handOffPrompt(String mainCheckout, String branch, String base,
                                        List<String> unmergedPaths) {
        String absolutePaths = unmergedPaths.stream()
                .map(path -> mainCheckout + "/" + path)
                .collect(Collectors.joining(", "));
        String addCommands = unmergedPaths.stream()
                .map(path -> "`git -C " + mainCheckout + " add " + path + "`")
                .collect(Collectors.joining(", "));
        return "The merge of '" + branch + "' into '" + base + "' stopped on conflicts in the main checkout at "
                + mainCheckout + ", not in this worktree. The conflicted files to edit are: " + absolutePaths
                + ". Edit those files in place, then run " + addCommands
                + " for each resolved file and `git -C " + mainCheckout + " commit --no-edit` to finish."
                + " Do not modify this worktree."
                + " Do not run `git -C " + mainCheckout + " merge --abort`, `git -C " + mainCheckout
                + " reset --hard`, `git -C " + mainCheckout + " checkout`, or `git -C " + mainCheckout
                + " merge -s ours` — if you cannot resolve the conflicts, say so and stop.";
    }

    /**
     * The terminal success copy, worded from what the cleanup actually did
     * rather than from what was intended -- a merge that landed is never
     * reported as a failure because the cleanup was partial, and a session
     * that is still open is never reported as closed.
     */
    static Next.Done forCleanup(CleanupOutcome outcome, String branch, String base, boolean conflictsResolved) {
        String headline = mergedHeadline(branch, base, conflictsResolved);
        String worktree = outcome.worktreeRemoved()
                ? "worktree removed"
                : "worktree kept — " + outcome.worktreeKeptReason().orElse("git refused to remove it");
        String branchDetail = switch (outcome.branch()) {
            case DELETED -> "branch " + branch + " deleted";
            case KEPT_NOT_OURS -> "branch " + branch + " kept (already existed)";
            // Not folded into KEPT_NOT_OURS: "(already existed)" would be a false
            // reason, and this line is the only place the user is told that a commit
            // they (or the agent) made during the hand-off is NOT in the base branch.
            case KEPT_MOVED -> "branch " + branch + " kept — it moved since the merge";
            case DELETE_FAILED -> "branch " + branch + " kept (could not delete)";
            case NOT_ATTEMPTED -> "branch " + branch + " kept";
        };
        String session = outcome.sessionDeleted() ? "session closed" : "session left open";
        return new Next.Done(headline, String.join(" · ", worktree, branchDetail, session));
    }

    /** The one place the "this merge landed" headline is worded; shared by the two outcomes that report one. */
    private static String mergedHeadline(String branch, String base, boolean conflictsResolved) {
        return "✓ Merged " + branch + " into " + base
                + (conflictsResolved ? " — conflicts resolved by Claude" : "");
    }

    /**
     * A refusal for a header label that is not a branch name at all. The tab
     * header renders a detached HEAD as the literal {@code "(detached)"}, and
     * that string reaches the flow as a branch or base name -- where it would
     * come back out as "Check out (detached) in the main checkout" or "Branch
     * (detached) no longer exists". Caught before the flow starts, so nothing
     * is merged and nothing is deleted.
     *
     * <p>The real fix is an {@code Optional<String>} threaded through the
     * header instead of a display string; this is the honest refusal until
     * then (noted as a follow-up in the final-fix report).</p>
     *
     * @param worktreeDetached whether it is the worktree's own HEAD that is
     *                         detached rather than the main checkout's
     */
    static Next.Stopped forDetachedHeadLabel(boolean worktreeDetached) {
        if (worktreeDetached) {
            return new Next.Stopped("This worktree is not on a branch",
                    "Its HEAD is detached, so there is no branch to merge. Check out a branch in the worktree,"
                            + " then finish again. Nothing was merged.");
        }
        return new Next.Stopped("The main checkout is not on a branch",
                "Its HEAD is detached, so there is nowhere to merge into. Check out the base branch in the main"
                        + " checkout, then finish again. Nothing was merged.");
    }

    /**
     * A stop for a pre-flight call that could not be made at all -- an
     * executor rejecting work at shutdown, say -- rather than one git answered.
     * Nothing has been written, which is the same thing every other pre-flight
     * refusal says.
     */
    static Next.Stopped forFailedInspection(String detail) {
        return new Next.Stopped("Could not inspect the repository", detail);
    }

    /**
     * A stop for a merge call that never reached git. The headline is
     * deliberately the same sentence {@link MergeVerdict.Refused} produces:
     * from the user's side "could not merge X into Y, here is why" is one
     * statement whether git refused or the call was never made, and a second
     * copy of that sentence in the flow class would only drift from this one.
     */
    static Next.Stopped forFailedMerge(String branch, String base, String detail) {
        return new Next.Stopped("Could not merge " + branch + " into " + base, detail);
    }

    /**
     * The merge landed but the cleanup could not be invoked ({@link
     * WorktreeSessionCleanup} folds every git failure into an outcome, so a
     * failure reaching the caller means the call itself never ran, and nothing
     * was removed). Deliberately a {@link Next.Done} with the same "✓ Merged"
     * headline as a completed cleanup: a partial or absent cleanup must never
     * downgrade a merge that is already in the base branch to a ✗ failure --
     * the user would go looking for a merge commit that is right there.
     */
    static Next.Done forFailedCleanup(String branch, String base, boolean conflictsResolved, String detail) {
        return new Next.Done(mergedHeadline(branch, base, conflictsResolved),
                String.join(" · ", "cleanup did not run: " + detail,
                        "worktree, branch and session left as they are"));
    }

    /**
     * A stop for conflicts that cannot be handed off: the session's terminal
     * is closed, so there is no agent to ask and only the user can finish the
     * merge. Worded here rather than in the flow because it is the one thing
     * the user is told about a merge left open in their main checkout -- it has
     * to name the checkout, and it has to say that nothing was deleted.
     */
    static Next.Stopped forHandOffWithoutATerminal(String branch, String base, String mainCheckout) {
        return new Next.Stopped("Conflicts need resolving",
                "The merge of " + branch + " into " + base + " is open in the main checkout at " + mainCheckout
                        + ", but this session's terminal is closed. Resolve it there. Nothing was deleted.");
    }

    /** The poll gave up. Says where the merge might be, and that nothing was destroyed. */
    static Next.Stopped forTimeout(String branch, String base, Optional<String> lastProbeDetail) {
        return new Next.Stopped("Merge not confirmed after 5 minutes",
                "The merge of " + branch + " into " + base
                        + " may still be open in the main checkout — check the terminal. Nothing was deleted."
                        + lastProbeDetail.map(detail -> " Last check: " + detail).orElse(""));
    }
}
