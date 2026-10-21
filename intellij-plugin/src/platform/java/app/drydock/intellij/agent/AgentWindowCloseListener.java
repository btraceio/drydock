package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;
import app.drydock.intellij.startup.DrydockStartupActivity;
import app.drydock.intellij.tasks.TaskWindows;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManagerListener;
import org.jetbrains.annotations.NotNull;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Closing a window does what its close question decided
 * ({@link AgentCloseConfirmation}, frame F4): the agents it shows keep running
 * with no window showing them, or stop and leave their rows idle. An IDE quit
 * stops everything, as before.
 *
 * <p>Why here rather than in the execution service's {@code dispose()}: the
 * rows go idle while the window and its services are still alive, before the
 * process has even ended, so a window reopened at once never reads a stopped
 * agent as running — the RUNNING→INACTIVE sweep runs once per IDE start, not
 * per project open. {@link AgentExecutionService#dispose()} stays as the
 * force-kill backstop for anything this misses.</p>
 *
 * <p>A session whose console is on its way to another window (a jump) is
 * left alone either way.</p>
 */
public final class AgentWindowCloseListener implements ProjectManagerListener {

    private static final Logger LOG = System.getLogger(AgentWindowCloseListener.class.getName());

    @Override
    public void projectClosing(@NotNull Project project) {
        TaskWindows.windowClosing(project, AgentCloseConfirmation.isQuitting());
        AgentExecutionRegistry registry = AgentExecutionRegistry.getInstance();
        List<ManagedSessionId> running = registry.runningShownIn(project);
        Optional<CloseWindowPlan.Decision> decision = Optional.ofNullable(
                project.getUserData(AgentCloseConfirmation.DECISION));
        project.putUserData(AgentCloseConfirmation.DECISION, null);
        if (running.isEmpty()) {
            return;
        }
        CloseWindowPlan.Outcome outcome = AgentCloseConfirmation.isQuitting()
                ? new CloseWindowPlan.Outcome(Set.of(), Set.copyOf(running))
                : CloseWindowPlan.outcome(decision, CloseWindowDialog.remembered(), running, registry.moving());
        try {
            outcome.release().forEach(registry::release);
            if (!outcome.stop().isEmpty()) {
                DrydockStartupActivity.sessions(project).stopSessionsForClose(List.copyOf(outcome.stop()));
            }
        } catch (RuntimeException e) {
            // Never block a project close on drydock's bookkeeping. The
            // window service's dispose still stops what it shows, and the
            // next IDE start normalizes any row left claiming to run.
            LOG.log(Level.WARNING, () -> "Could not settle Drydock agents while closing the project: " + e);
        }
    }
}
