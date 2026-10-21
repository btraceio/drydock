package app.drydock.intellij.tasks;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.SessionStatus;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.agent.AgentConsoles;
import app.drydock.intellij.agent.AgentExecutionService;
import app.drydock.intellij.agent.SessionDone;
import app.drydock.intellij.agent.StopAgentsDialog;
import app.drydock.intellij.agent.StopAgentsPlan;
import app.drydock.intellij.startup.DrydockStartupActivity;
import app.drydock.intellij.tasks.TaskNode.ProjectRow;
import app.drydock.intellij.tasks.TaskNode.TaskRow;
import app.drydock.intellij.tasks.TaskNode.WorktreeGroup;
import app.drydock.intellij.ui.SessionDots;
import app.drydock.ui.AgentLabels;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * What the Drydock tool window can do to a task: switch to it, deactivate it,
 * deactivate it and close its window, resume it.
 *
 * <p>Deactivating asks first only when the agent is mid-task (not at its
 * prompt), and the question can always be answered "Deactivate anyway". Closing
 * a window loses nothing — IntelliJ saves open files and the worktree stays
 * on disk — so the close itself adds no question of its own; the window's
 * <em>other</em> working agents get the usual close confirmation.</p>
 */
final class TaskActions {

    /**
     * How long Deactivate &amp; Close and Done wait for the agent to end: past the
     * execution's own two-second force-kill, with room to spare.
     */
    private static final long STOP_WAIT_MILLIS = 5_000;
    private static final long STOP_POLL_MILLIS = 100;

    private TaskActions() {
    }

    /** How an action shows: in a context menu only when it applies; in the toolbar always, greyed when it does not. */
    record Shown(boolean visible, boolean enabled) {
    }

    /** Pure: toolbar buttons stay in place so they do not jump around as the selection changes. */
    static Shown shown(boolean fromContextMenu, boolean applicable) {
        return new Shown(applicable || !fromContextMenu, applicable);
    }

    /** Applies {@link #shown} to {@code e}'s presentation. */
    static void present(AnActionEvent e, boolean applicable) {
        Shown shown = shown(e.isFromContextMenu(), applicable);
        e.getPresentation().setVisible(shown.visible());
        e.getPresentation().setEnabled(shown.enabled());
    }

    /** The task and project actions, for the tree's context menu and the tool window's toolbar. */
    static List<AnAction> taskActions() {
        return List.of(
                task("Deactivate", "Deactivate this task's agent", AllIcons.Actions.Suspend, TaskRow::running,
                        (project, row) -> stop(project, row, false)),
                task("Deactivate && Close Window", "Deactivate the agent and close its window", AllIcons.Actions.Close,
                        TaskRow::running, (project, row) -> stop(project, row, true)),
                task("Resume", "Open the task and resume its conversation", AllIcons.Actions.Execute,
                        row -> !row.running(), (project, row) -> TaskNavigator.switchTo(row.session(),
                                target -> AgentConsoles.resumeOrStart(target, row.session()))),
                task("Done with This Task…", "Forget the task, and optionally remove its worktree",
                        AllIcons.Actions.Checked, row -> true, TaskActions::done),
                removeProject());
    }

    private interface RowAction {
        void run(Project project, TaskRow row);
    }

    private static AnAction task(String text, String description, Icon icon, Predicate<TaskRow> enabled,
                                 RowAction action) {
        return new DumbAwareAction(text, description, icon) {
            @Override
            public void update(@NotNull AnActionEvent e) {
                present(e, e.getData(TaskTreePanel.SELECTED_NODE) instanceof TaskRow row && enabled.test(row));
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                if (e.getProject() != null && e.getData(TaskTreePanel.SELECTED_NODE) instanceof TaskRow row) {
                    action.run(e.getProject(), row);
                }
            }
        };
    }

    /**
     * Stops {@code row}'s agent, asking first when it is mid-task; with
     * {@code closeWindow}, then closes the window it ran in. EDT.
     */
    static void stop(Project from, TaskRow row, boolean closeWindow) {
        ManagedSessionId id = row.session().id();
        Optional<Project> owner = owner(id);
        if (owner.isEmpty()) {
            return;   // ended meanwhile
        }
        if (StopAgentsPlan.isWorking(row.activity())) {
            String agent = AgentLabels.displayName(DrydockPluginService.getInstance().registry(),
                    row.session().agentKind());
            if (!StopAgentsDialog.confirmTreeStop(from,
                    SessionDots.dotIcon(SessionStatus.RUNNING, row.activity()),
                    StopAgentsPlan.row(row.session().displayName(), agent, Optional.empty()), closeWindow)) {
                return;
            }
        }
        Project window = owner.get();
        DrydockStartupActivity.sessions(window).stopSession(id);
        if (closeWindow) {
            whenStopped(window, id, () -> {
                if (!window.isDisposed()) {
                    ProjectManager.getInstance().closeAndDispose(window);
                }
            });
        }
    }

    /**
     * "Done with this task…" from the tree. A running agent is stopped first
     * — asking, as Stop does, when it is mid-task — so the dialog's check of
     * the worktree sees what the agent left, not what it is still writing.
     */
    static void done(Project from, TaskRow row) {
        Optional<Project> owner = owner(row.session().id());
        if (owner.isEmpty()) {
            SessionDone.show(from, row.session());
            return;
        }
        if (StopAgentsPlan.isWorking(row.activity())) {
            String agent = AgentLabels.displayName(DrydockPluginService.getInstance().registry(),
                    row.session().agentKind());
            if (!StopAgentsDialog.confirmTreeStop(from,
                    SessionDots.dotIcon(SessionStatus.RUNNING, row.activity()),
                    StopAgentsPlan.row(row.session().displayName(), agent, Optional.empty()), false)) {
                return;
            }
        }
        DrydockStartupActivity.sessions(owner.get()).stopSession(row.session().id());
        whenStopped(owner.get(), row.session().id(), () -> SessionDone.show(from, row.session()));
    }

    /**
     * "Remove from Drydock" on a project row: takes the project off the
     * shared list — for the standalone app too — and touches nothing else:
     * not the repository, not its worktrees, not running agents. Asks only
     * when some of its agents are running here, to say they keep running.
     */
    private static AnAction removeProject() {
        return new DumbAwareAction("Remove from Drydock", "Take this project off Drydock's list; nothing on disk "
                + "changes", AllIcons.General.Remove) {
            @Override
            public void update(@NotNull AnActionEvent e) {
                present(e, e.getData(TaskTreePanel.SELECTED_NODE) instanceof ProjectRow);
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                if (!(e.getData(TaskTreePanel.SELECTED_NODE) instanceof ProjectRow row) || e.getProject() == null) {
                    return;
                }
                Project project = e.getProject();
                int running = row.running();
                if (running > 0 && Messages.showOkCancelDialog(project,
                        removeQuestion(row.project().displayName(), running), "Drydock", "Remove", "Cancel",
                        null) != Messages.OK) {
                    return;
                }
                DrydockPluginService.getInstance().sharedProjects().remove(row.project().id())
                        .whenComplete((ignored, failure) -> {
                            if (failure != null) {
                                ApplicationManager.getApplication().invokeLater(() -> Messages.showErrorDialog(
                                        project, "Could not remove " + row.project().displayName() + ": "
                                                + failure.getMessage(), "Drydock"),
                                        ModalityState.nonModal(), project.getDisposed());
                            }
                        });
            }
        };
    }

    /** {@code Remove <repo> from Drydock? <n> agents keep running.} */
    static String removeQuestion(String repo, int running) {
        return "Remove " + repo + " from Drydock? " + running + (running == 1 ? " agent keeps" : " agents keep")
                + " running.";
    }

    /** The open window running {@code id}. In-memory, EDT-safe. */
    private static Optional<Project> owner(ManagedSessionId id) {
        return Arrays.stream(ProjectManager.getInstance().getOpenProjects())
                .filter(project -> !project.isDisposed())
                .filter(project -> AgentExecutionService.getInstance(project).isRunning(id))
                .findFirst();
    }

    /**
     * Runs {@code then} on the EDT once {@code id} no longer runs in
     * {@code window}; if it still runs after {@link #STOP_WAIT_MILLIS}, says
     * so instead. Closing before the process has ended would make the
     * window's own close confirmation list the agent being stopped, and Done
     * would read a worktree the agent is still writing.
     */
    private static void whenStopped(Project window, ManagedSessionId id, Runnable then) {
        DrydockPluginService.getInstance().executor().execute(() -> {
            long deadline = System.currentTimeMillis() + STOP_WAIT_MILLIS;
            boolean running = true;
            while (!window.isDisposed() && (running = AgentExecutionService.getInstance(window).isRunning(id))
                    && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(STOP_POLL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            boolean stillRunning = running && !window.isDisposed();
            ApplicationManager.getApplication().invokeLater(() -> {
                if (stillRunning) {
                    // Never carry on regardless: closing would ask about the
                    // agent again, and Done would read a tree it still writes.
                    Messages.showInfoMessage(window, StopAgentsPlan.notStoppedYet(taskName(id)), "Drydock");
                    return;
                }
                then.run();
            }, ModalityState.nonModal());
        });
    }

    private static String taskName(ManagedSessionId id) {
        return DrydockPluginService.getInstance().stateStore().state().sessions().stream()
                .filter(session -> session.id().equals(id))
                .map(ManagedAgentSession::displayName)
                .findFirst()
                .orElse("The agent");
    }
}
