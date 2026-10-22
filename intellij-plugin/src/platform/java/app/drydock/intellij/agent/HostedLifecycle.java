package app.drydock.intellij.agent;

import app.drydock.domain.SessionActivity;

import java.util.Optional;

/**
 * What happens to a hosted tab when its agent's turn ends and when its
 * worktree goes away (cross-project tabs, frame F5 and the handoff's
 * "Other rules"), as pure decisions.
 */
public final class HostedLifecycle {

    private HostedLifecycle() {
    }

    /**
     * Whether a hosted agent's activity change ends a turn: from busy to
     * anything else. Its files open in any window are then refreshed — a
     * worktree no window has as a project is outside every watched root, so
     * the IDE would not notice the agent's edits on its own.
     */
    public static boolean refreshAfter(Optional<SessionActivity> before, Optional<SessionActivity> after,
                                       boolean hosted) {
        return hosted && before.equals(Optional.of(SessionActivity.BUSY))
                && !after.equals(Optional.of(SessionActivity.BUSY));
    }

    /** Whether a running agent's worktree folder is gone. It is marked, never stopped unasked. */
    public static boolean folderGone(boolean running, boolean directoryExists) {
        return running && !directoryExists;
    }
}
