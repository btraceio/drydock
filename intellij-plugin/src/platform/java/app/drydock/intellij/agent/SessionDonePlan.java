package app.drydock.intellij.agent;

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
     * Why the worktree cannot be removed from here, if it cannot.
     *
     * @param mainCheckout       the session runs in the repository's main checkout,
     *                           which is never removable
     * @param linkedWorktree     the session's directory is one of the repository's
     *                           linked worktrees
     * @param otherSessions      how many other sessions are recorded in the same
     *                           directory; removing it would strand them
     * @param isThisWindow       the worktree is this window's own project directory,
     *                           which is what "Closes this window" promises
     */
    public static Optional<String> removalBlocker(boolean mainCheckout, boolean linkedWorktree, int otherSessions,
                                                  boolean isThisWindow) {
        if (mainCheckout) {
            return Optional.of("This session runs in the repository's main checkout, which is never removed.");
        }
        if (!linkedWorktree) {
            return Optional.of("This session's directory is not one of the repository's worktrees.");
        }
        if (otherSessions > 0) {
            return Optional.of(otherSessions == 1
                    ? "Another session uses this worktree; forget it first."
                    : otherSessions + " other sessions use this worktree; forget them first.");
        }
        if (!isThisWindow) {
            return Optional.of("This worktree is not this window's; remove it from its own window.");
        }
        return Optional.empty();
    }

    /** The first option's secondary line. */
    public static String forgetDetail() {
        return "Removes the row. Worktree and branch stay.";
    }

    /**
     * The second option's secondary line. The data-loss clause appears only
     * when there is something to lose: a warning shown on a clean tree trains
     * the user to skip the one shown on a dirty one.
     *
     * <p>"The branch stays" is said outright because it is the opposite of
     * what a worktree removal often implies, and the branch is where any
     * committed work lives.</p>
     */
    public static String removeDetail(int uncommittedFiles) {
        String detail = "Closes this window. The branch stays.";
        if (uncommittedFiles <= 0) {
            return detail;
        }
        return detail + " " + uncommittedFiles
                + (uncommittedFiles == 1 ? " uncommitted file" : " uncommitted files")
                + " will be discarded.";
    }

    /** The footer, true of both choices. */
    public static String footer() {
        return "The agent's own conversation history on disk is kept either way.";
    }

    /** The primary button names the choice it performs. */
    public static String primaryLabel(Choice choice) {
        return choice == Choice.FORGET_AND_REMOVE ? "Forget & Remove" : "Forget";
    }
}
