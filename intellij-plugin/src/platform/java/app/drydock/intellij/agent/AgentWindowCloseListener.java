package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;
import app.drydock.intellij.startup.DrydockStartupActivity;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManagerListener;
import org.jetbrains.annotations.NotNull;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;

/**
 * Closing a worktree window stops the agents running in it, and leaves their
 * rows idle.
 *
 * <p>Why here rather than in the execution service's {@code dispose()}: the
 * rows go idle while the window and its services are still alive, before the
 * process has even ended, so a window reopened at once never reads a stopped
 * agent as running — the RUNNING→INACTIVE sweep runs once per IDE start, not
 * per project open. {@link AgentExecutionService#dispose()} stays as the
 * force-kill backstop for anything this misses.</p>
 *
 * <p>Not the close <em>confirmation</em> — that dialog ("Stop n working
 * agent(s)?") is a later phase and belongs in {@code canCloseProject}. This
 * only does the stopping, which has to happen either way.</p>
 */
public final class AgentWindowCloseListener implements ProjectManagerListener {

    private static final Logger LOG = System.getLogger(AgentWindowCloseListener.class.getName());

    @Override
    public void projectClosing(@NotNull Project project) {
        List<ManagedSessionId> running = AgentExecutionService.getInstance(project).runningSessions();
        if (running.isEmpty()) {
            return;
        }
        try {
            DrydockStartupActivity.sessions(project).stopSessionsForClose(running);
        } catch (RuntimeException e) {
            // Never block a project close on drydock's bookkeeping. The
            // execution service's dispose still kills the processes, and the
            // next IDE start normalizes any row left claiming to run.
            LOG.log(Level.WARNING, () -> "Could not stop Drydock agents while closing the project: " + e);
        }
    }
}
