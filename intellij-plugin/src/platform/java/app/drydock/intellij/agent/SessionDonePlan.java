package app.drydock.intellij.agent;

import app.drydock.git.WorktreeService;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Every decision and string of "Done with this session…", as pure functions.
 *
 * <p>The dialog's copy carries the data-loss warning, which is the part worth
 * pinning: it must appear exactly when the tree is dirty, count the files
 * right, and never offer to remove a worktree that cannot or must not be
 * removed. The dialog places these results; it derives none of them.</p>
 */
public final class SessionDonePlan {

    /** What the user chose. */
    public enum Choice {
        /** Remove the row only; the worktree and its branch stay. */
        FORGET,
        /** Remove the row and the worktree. The branch stays. */
        FORGET_AND_REMOVE
    }

    private SessionDonePlan() {
    }

    /**
     * What removing the worktree would lose: uncommitted and untracked
     * files, and commits not on the default branch. {@code merged} is true
     * when the branch is merged by ancestry or its PR is recorded as merged
     * (a squash or rebase merge leaves commits "ahead" that are merged all
     * the same); then {@code unmergedCommits} does not count.
     */
    public record Risks(int uncommitted, int untracked, int unmergedCommits, boolean merged) {
    }

    /**
     * Why "Forget + remove worktree" is unavailable, if it is: the main
     * checkout, a directory that is not a linked worktree, or other tasks
     * still using it (named, so the user knows which to forget).
     */
    public static Optional<String> removalBlocker(boolean mainCheckout, boolean linkedWorktree,
                                                  List<String> otherTasks) {
        if (mainCheckout) {
            return Optional.of("This task runs in the repository's main checkout, which is never removed.");
        }
        if (!linkedWorktree) {
            return Optional.of("This task's directory is not one of the repository's worktrees.");
        }
        if (!otherTasks.isEmpty()) {
            return Optional.of("Also used by: " + String.join(", ", otherTasks) + ". Forget "
                    + (otherTasks.size() == 1 ? "it" : "them") + " first.");
        }
        return Optional.empty();
    }

    /** Under "Forget task only". */
    public static String forgetDetail() {
        return "Removes the task from Drydock. Worktree and branch stay.";
    }

    /** Under "Forget + remove worktree". */
    public static String removeDetail() {
        return "Removes the worktree folder. The branch stays unless you tick below.";
    }

    /** {@code 3 uncommitted files, 1 untracked file, 2 commits not merged}; empty when nothing would be lost. */
    public static Optional<String> riskLine(Risks risks) {
        List<String> parts = new ArrayList<>();
        if (risks.uncommitted() > 0) {
            parts.add(count(risks.uncommitted(), "uncommitted file"));
        }
        if (risks.untracked() > 0) {
            parts.add(count(risks.untracked(), "untracked file"));
        }
        if (!risks.merged() && risks.unmergedCommits() > 0) {
            parts.add(count(risks.unmergedCommits(), "commit") + " not merged");
        }
        return parts.isEmpty() ? Optional.empty() : Optional.of(String.join(", ", parts));
    }

    /**
     * Whether a {@code git status --porcelain} line is an untracked IDE
     * project file at the worktree's top: {@code .idea/} or a {@code .iml}.
     * Opening the worktree as a window creates them, so counting them would
     * make every task's removal warn about files nobody wrote.
     */
    public static boolean isIdeProjectFile(String porcelainLine) {
        if (!porcelainLine.startsWith("?? ")) {
            return false;
        }
        String path = porcelainLine.substring(3);
        return path.equals(".idea/") || (path.endsWith(".iml") && !path.contains("/"));
    }

    /**
     * Pure: whether a session is done -- its worktree is a non-main,
     * non-locked linked worktree whose work has already landed: the branch
     * merged (ancestry, or its pull request recorded as merged), or the
     * worktree prunable (its directory already gone) or detached. The same
     * test the standalone app's stale bucket applies to session-less
     * worktrees, applied to the worktree a session sits on. A merely idle
     * session on an unmerged branch is not done and is never touched by the
     * bulk cleanup; a main-checkout or locked worktree never counts either.
     */
    public static boolean doneSession(WorktreeService.Worktree worktree) {
        return !worktree.mainCheckout() && !worktree.locked()
                && (worktree.merged() || worktree.prunable() || worktree.detached());
    }

    /** Under the bulk dialog's "Forget task only". */
    public static String bulkForgetDetail() {
        return "Removes the tasks from Drydock. Worktrees and branches stay.";
    }

    /** Under the bulk dialog's "Forget + remove worktrees": the per-session guards stay on. */
    public static String bulkRemoveDetail() {
        return "Also removes each clean worktree. A worktree with uncommitted or untracked files,"
                + " or another session still using it, is kept.";
    }

    /** Under the bulk dialog's branch checkbox: merged only, and never a pruned worktree's. */
    public static String bulkDeleteBranchDetail() {
        return "Branches of removed worktrees are deleted only when their work is merged;"
                + " a pruned worktree keeps its branch.";
    }

    /**
     * Pure: the bulk dialog's summary line. {@code running} says how many
     * of the done sessions' agents still run and will be stopped.
     */
    public static String bulkSummary(int count, int running) {
        String sessions = count + (count == 1 ? " session" : " sessions");
        if (running == 0) {
            return "Forget " + sessions + " whose work has landed (merged, pruned, or detached)?";
        }
        return "Forget " + sessions + " whose work has landed (merged, pruned, or detached)? "
                + running + (running == 1 ? " agent still runs and will be stopped."
                        : " agents still run and will be stopped.");
    }

    /**
     * Pure: why the bulk removal kept a worktree -- the count of what
     * removing it would have lost, the same risk line the per-session
     * dialog shows as a warning; the fallback names the unreadable state.
     */
    public static String bulkKeptReason(Risks risks) {
        return riskLine(risks).orElse("its state could not be read");
    }

    /** Whether removing asks first: anything in {@link #riskLine}. */
    public static boolean needsConfirmation(Risks risks) {
        return riskLine(risks).isPresent();
    }

    /** "Also delete branch" starts ticked only for a merged branch; it can always be ticked. */
    public static boolean deleteBranchDefault(Risks risks) {
        return risks.merged();
    }

    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    /** The footer, true of both choices. */
    public static String footer() {
        return "The agent's own conversation history on disk is kept either way.";
    }

    /** The primary button names the choice it performs. */
    public static String primaryLabel(Choice choice, boolean risky) {
        if (choice != Choice.FORGET_AND_REMOVE) {
            return "Forget";
        }
        return risky ? "Remove anyway" : "Forget & Remove";
    }
}
