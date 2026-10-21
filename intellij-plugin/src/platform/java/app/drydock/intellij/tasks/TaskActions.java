package app.drydock.intellij.tasks;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.SessionStatus;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.agent.AgentConsoles;
import app.drydock.intellij.agent.AgentExecutionRegistry;
import app.drydock.intellij.agent.SessionDone;
import app.drydock.intellij.agent.StopAgentsDialog;
import app.drydock.intellij.agent.StopAgentsPlan;
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
                        TaskActions::closesOwnWindow, (project, row) -> stop(project, row, true)),
                // F5: moves an agent another window shows into this one.
                task("Show Here", "Move this agent's console into this window", AllIcons.Actions.MoveTo2,
                        row -> row.placement() instanceof TaskPlacement.OwnWindow
                                || row.placement() instanceof TaskPlacement.OtherWindow,
                        (project, row) -> AgentConsoles.showHere(project, row.session().id())),
                // Resume in this window, hosted when the task is another
                // worktree's — as New task does; its own window is the
                // explicit alternative.
                task("Resume", "Resume the task's conversation in this window", AllIcons.Actions.Execute,
                        row -> !row.running(), (project, row) -> AgentConsoles.resumeOrStart(project, row.session())),
                task("Resume in Its Own Window", "Open the task's own window and resume its conversation there",
                        AllIcons.Actions.MoveTo2, TaskActions::resumesElsewhere,
                        (project, row) -> TaskNavigator.switchTo(row.session(),
                                target -> AgentConsoles.resumeOrStart(target, row.session()))),
                task("Done with This Task…", "Forget the task, and optionally remove its worktree",
                        AllIcons.Actions.Checked, row -> true, TaskActions::done),
                removeProject());
    }

    /**
     * Whether Deactivate &amp; Close Window applies: the agent runs, and the window
     * showing it is its own worktree's. A hosted agent's window belongs to
     * another project — closing it would close what the user is working in,
     * with whatever else it shows — and an agent no window shows has none to
     * close. Pure.
     */
    static boolean closesOwnWindow(TaskRow row) {
        return row.running() && (row.placement() instanceof TaskPlacement.ThisWindow
                || row.placement() instanceof TaskPlacement.OwnWindow);
    }

    /**
     * Whether "Resume in Its Own Window" applies: an idle task from another
     * worktree. For this window's own worktree its own window is this one,
     * and plain Resume already does it. Pure.
     */
    static boolean resumesElsewhere(TaskRow row) {
        return !row.running() && row.placement() instanceof TaskPlacement.Idle idle && !idle.thisWindow();
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
        AgentExecutionRegistry registry = AgentExecutionRegistry.getInstance();
        if (!registry.isRunning(id)) {
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
        // The window showing it, if any: an agent no window shows still stops.
        Optional<Project> window = registry.host(id).filter(project -> !project.isDisposed());
        registry.stop(id);
        if (closeWindow && window.isPresent()) {
            whenStopped(from, id, () -> {
                if (!window.get().isDisposed()) {
                    ProjectManager.getInstance().closeAndDispose(window.get());
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
        AgentExecutionRegistry registry = AgentExecutionRegistry.getInstance();
        if (!registry.isRunning(row.session().id())) {
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
        registry.stop(row.session().id());
        whenStopped(from, row.session().id(), () -> SessionDone.show(from, row.session()));
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

    /**
     * Runs {@code then} on the EDT once {@code id}'s agent has ended; if it
     * still runs after {@link #STOP_WAIT_MILLIS}, says so instead, in
     * {@code from}. Closing before the process has ended would make the
     * window's own close confirmation list the agent being stopped, and Done
     * would read a worktree the agent is still writing.
     */
    private static void whenStopped(Project from, ManagedSessionId id, Runnable then) {
        AgentExecutionRegistry registry = AgentExecutionRegistry.getInstance();
        DrydockPluginService.getInstance().executor().execute(() -> {
            long deadline = System.currentTimeMillis() + STOP_WAIT_MILLIS;
            boolean running;
            while ((running = registry.isRunning(id)) && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(STOP_POLL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            boolean stillRunning = running;
            ApplicationManager.getApplication().invokeLater(() -> {
                if (stillRunning) {
                    // Never carry on regardless: closing would ask about the
                    // agent again, and Done would read a tree it still writes.
                    Messages.showInfoMessage(from, StopAgentsPlan.notStoppedYet(taskName(id)), "Drydock");
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
