package app.drydock.intellij.agent;

import app.drydock.domain.SessionActivity;

import java.util.Optional;

/**
 * Every decision and string of the close/quit confirmation ("Deactivate n
 * working agents?"), as pure functions.
 *
 * <p>The decision worth pinning is which agents count as <em>working</em>.
 * The handoff asks before stopping an agent that is busy or needs the user,
 * and stops one waiting at its prompt without asking. An agent whose activity
 * cannot be read — Codex and pi ship no activity reporter, and Claude has
 * reported nothing until its first hook fires — is <em>not known</em> to be
 * at its prompt, so it counts as working: asking once too often costs a click,
 * stopping a busy agent unasked costs its work.</p>
 */
public final class StopAgentsPlan {

    private StopAgentsPlan() {
    }

    /** Whether closing a window should ask before stopping this running agent. */
    public static boolean isWorking(Optional<SessionActivity> activity) {
        return activity.orElse(SessionActivity.UNKNOWN) != SessionActivity.IDLE;
    }

    /** {@code Deactivate 1 working agent?} / {@code Deactivate 3 working agents?} */
    public static String title(int working) {
        return working == 1
                ? "Deactivate 1 working agent?"
                : "Deactivate " + working + " working agents?";
    }

    /**
     * What closing does to them. {@code place} is the window's
     * {@code <repo> · <branch>}; a quit names no window because it closes
     * every one of them.
     */
    public static String body(Optional<String> place, int working) {
        String what = place.map(name -> "Closing " + name).orElse("Quitting");
        return what + (working == 1 ? " deactivates it mid-task." : " deactivates them mid-task.");
    }

    /** One listed agent: {@code <title> · <Agent>}, plus its window on a quit. */
    public static String row(String title, String agent, Optional<String> place) {
        return title + " · " + agent + place.map(name -> " — " + name).orElse("");
    }

    /** The line under the list. */
    public static String footer() {
        return "Agents waiting at the prompt are deactivated without asking.";
    }

    /** The default button names what it does. */
    public static String primaryLabel(boolean quitting) {
        return quitting ? "Deactivate & Quit" : "Deactivate & Close";
    }

    /** Deactivating one task from the Drydock tool window: the default button. */
    public static String primaryLabelForTree(boolean closing) {
        return closing ? "Deactivate & Close" : "Deactivate anyway";
    }

    /** Deactivating one task from the Drydock tool window: what it does, and what it keeps. */
    public static String treeBody(boolean closing) {
        return closing
                ? "Deactivating ends it mid-task and closes its window. Open files are saved and the worktree stays."
                : "Deactivating ends it mid-task. Its conversation can be resumed later.";
    }

    /**
     * Where "Don't ask again" is remembered: one setting for the close and
     * quit question, another for stopping one task from the tool window, so
     * silencing one never silences the other.
     */
    public static String dontAskKey(boolean fromTree) {
        return fromTree ? "drydock.stopTaskFromTree.dontAsk" : "drydock.stopWorkingAgents.dontAsk";
    }

    /** When an agent has not deactivated in time for what was to follow (closing its window, Done). */
    public static String notStoppedYet(String taskName) {
        return "\"" + taskName + "\" has not deactivated yet. Try again in a moment.";
    }
}
