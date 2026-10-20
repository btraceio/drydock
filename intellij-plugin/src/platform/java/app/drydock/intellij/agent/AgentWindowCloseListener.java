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
 * <p>Why this exists rather than relying on the execution service's own
 * {@code dispose()}: by the time a project service is disposed its message bus
 * is going away too, so the {@code processTerminated} event that normally
 * carries the idle status never reaches the session service — and the row
 * stays {@code RUNNING} with nothing running. Reopening the project then shows
 * a stopped agent described as running, and the RUNNING→INACTIVE sweep at
 * startup does not correct it because that runs once per IDE start, not per
 * project open.</p>
 *
 * <p>{@code projectClosing} fires while the bus and the services are still
 * alive, which is what makes the status write land.
 * {@link AgentExecutionService#dispose()} stays as the force-kill backstop for
 * anything this misses.</p>
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
