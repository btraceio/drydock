package app.drydock.intellij.agent;

import java.nio.file.Path;

/**
 * The jump's copy (cross-project tabs, frame F3), as pure functions: what the
 * hosting tab says while the agent's own window opens, the balloon that
 * confirms the console has moved there, and what the jump found when the
 * window finished opening.
 */
public final class JumpPlan {

    private JumpPlan() {
    }

    /** {@code Opening <repo> · <branch>…}. */
    public static String opening(SessionPlace place) {
        return "Opening " + place.label() + "…";
    }

    /** {@code <title> moved}. */
    public static String movedTitle(String title) {
        return title + " moved";
    }

    /** {@code Now in the <repo> · <branch> window.} */
    public static String movedBody(SessionPlace place) {
        return "Now in the " + place.label() + " window.";
    }

    /**
     * What the jump found once its window had (or had not) opened. Pure so
     * the completion path can be tested without an IDE.
     */
    public enum Outcome {
        /** The worktree folder is gone from disk; there is nothing to open. */
        FOLDER_GONE,
        /** The folder exists but its window did not open. */
        OPEN_FAILED,
        /** An execution is live and shown there: the running move, as before. */
        MOVED_LIVE,
        /** Nothing runs: the stopped session's tab is shown there instead. */
        SHOW_STOPPED
    }

    /**
     * Pure: the jump's outcome from the three facts established on the EDT
     * after the window opened. {@code shownByRegistry} is the registry
     * re-read at completion — never the execution captured at the click —
     * so an agent that started or restarted while the window opened is
     * {@code MOVED_LIVE}, and one that ended is {@code SHOW_STOPPED}.
     */
    public static Outcome after(boolean folderExists, boolean targetOpened, boolean shownByRegistry) {
        if (!folderExists) {
            return Outcome.FOLDER_GONE;
        }
        if (!targetOpened) {
            return Outcome.OPEN_FAILED;
        }
        return shownByRegistry ? Outcome.MOVED_LIVE : Outcome.SHOW_STOPPED;
    }

    /** {@code The folder for <repo> · <branch> no longer exists on disk: <path>}. */
    public static String folderGone(String label, Path directory) {
        return "The folder for " + label + " no longer exists on disk: " + directory;
    }

    /**
     * Whether the jump's outcome carries a move worth announcing with the
     * "moved" balloon in the window the agent left. Only
     * {@link Outcome#MOVED_LIVE} does: it is the only outcome in which the
     * registry's live execution actually moved, which is what the balloon's
     * "Bring back here" (showHere, a registry move back) undoes. A stopped
     * session shown in its own window is not announced — announced, its way
     * back would call showHere with no execution in the registry, which
     * silently does nothing.
     */
    public static boolean announcesMove(Outcome outcome) {
        return outcome == Outcome.MOVED_LIVE;
    }

    /**
     * Whether the jump closes the hosting tab: only when the agent's own
     * window is a different window from the one hosting the tab now.
     */
    public static boolean closesHostTab(boolean sameWindow) {
        return !sameWindow;
    }
}
