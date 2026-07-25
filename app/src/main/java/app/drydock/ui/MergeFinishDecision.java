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
        record Done(String headline, String detail) implements Next { }

        /** Terminal stop. Nothing destructive has run. */
        record Stopped(String headline, String detail) implements Next { }
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
            if (worktreeRemoved && branch == BranchResult.NOT_ATTEMPTED) {
                throw new IllegalArgumentException(
                        "a removed worktree cannot have NOT_ATTEMPTED as its branch result");
            }
        }
    }

    /** The fate of the branch. {@code NOT_ATTEMPTED}: the worktree survived, so nothing was tried. */
    enum BranchResult { DELETED, KEPT_NOT_OURS, DELETE_FAILED, NOT_ATTEMPTED }

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
        String headline = "✓ Merged " + branch + " into " + base
                + (conflictsResolved ? " — conflicts resolved by Claude" : "");
        String worktree = outcome.worktreeRemoved()
                ? "worktree removed"
                : "worktree kept — " + outcome.worktreeKeptReason().orElse("git refused to remove it");
        String branchDetail = switch (outcome.branch()) {
            case DELETED -> "branch " + branch + " deleted";
            case KEPT_NOT_OURS -> "branch " + branch + " kept (already existed)";
            case DELETE_FAILED -> "branch " + branch + " kept (could not delete)";
            case NOT_ATTEMPTED -> "branch " + branch + " kept";
        };
        String session = outcome.sessionDeleted() ? "session closed" : "session left open";
        return new Next.Done(headline, String.join(" · ", worktree, branchDetail, session));
    }

    /** The poll gave up. Says where the merge might be, and that nothing was destroyed. */
    static Next.Stopped forTimeout(String branch, String base, Optional<String> lastProbeDetail) {
        return new Next.Stopped("Merge not confirmed after 5 minutes",
                "The merge of " + branch + " into " + base
                        + " may still be open in the main checkout — check the terminal. Nothing was deleted."
                        + lastProbeDetail.map(detail -> " Last check: " + detail).orElse(""));
    }
}
