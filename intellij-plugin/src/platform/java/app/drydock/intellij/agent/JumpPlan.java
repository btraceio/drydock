package app.drydock.intellij.agent;

/**
 * The jump's copy (cross-project tabs, frame F3), as pure functions: what the
 * hosting tab says while the agent's own window opens, and the balloon that
 * confirms the console has moved there.
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
}
