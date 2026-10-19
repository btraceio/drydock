package app.drydock.intellij.agent;

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
        /** Remove the row, close this window, and remove its worktree. The branch stays. */
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
    public static String removeDetail(boolean windowOpen) {
        return "Removes the worktree folder" + (windowOpen ? " and closes its window" : "")
                + ". The branch stays unless you tick below.";
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
