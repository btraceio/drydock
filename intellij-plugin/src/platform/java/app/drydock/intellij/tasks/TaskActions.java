package app.drydock.intellij.tasks;

import app.drydock.git.WorktreeService;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.DrydockSessionsListener;
import app.drydock.intellij.agent.AgentConsoles;
import app.drydock.intellij.agent.AgentExecutionRegistry;
import app.drydock.intellij.agent.SessionCommands;
import app.drydock.intellij.agent.SessionCommandHandlers;
import app.drydock.intellij.agent.SessionRename;
import app.drydock.intellij.agent.SessionTransfer;
import app.drydock.intellij.review.ReviewRequest;
import app.drydock.intellij.tasks.TaskNode.IdleWorktree;
import app.drydock.intellij.tasks.TaskNode.ProjectRow;
import app.drydock.intellij.tasks.TaskNode.StaleWorktrees;
import app.drydock.intellij.tasks.TaskNode.TaskRow;
import app.drydock.intellij.worktree.WorktreeErrors;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/**
 * What the Drydock tool window can do to a task: switch to it, deactivate it,
 * ask its agent to review its changes, deactivate it and close its window,
 * resume it.
 *
 * <p>Deactivating asks first only when the agent is mid-task (not at its
 * prompt), and the question can always be answered "Deactivate anyway". Closing
 * a window loses nothing — IntelliJ saves open files and the worktree stays
 * on disk — so the close itself adds no question of its own; the window's
 * <em>other</em> working agents get the usual close confirmation.</p>
 */
final class TaskActions {

    enum DefaultActivation { SWITCH_TO_RUNNING, RESUME_HERE }

    private static final long REMOVE_TIMEOUT_SECONDS = 120;

    private TaskActions() {
    }

    /** How an action shows: in a context menu only when it applies; in the toolbar always, greyed when it does not. */
    record Shown(boolean visible, boolean enabled) {
    }

    /**
     * Pure: toolbar buttons stay in place so they do not jump around as the
     * selection changes; applicability gates both. A context menu hides
     * what does not apply, the toolbar only greys it; a separate enabled
     * answer (the busy guard) greys without hiding anywhere.
     */
    static Shown shown(boolean fromContextMenu, boolean applicable, boolean enabled) {
        return new Shown(applicable || !fromContextMenu, applicable && enabled);
    }

    /** {@link #shown} with no separate enabled answer: applicability alone decides. */
    static Shown shown(boolean fromContextMenu, boolean applicable) {
        return shown(fromContextMenu, applicable, true);
    }

    /** Applies {@link #shown} to {@code e}'s presentation. */
    static void present(AnActionEvent e, boolean applicable) {
        present(e, applicable, true);
    }

    /** Applies {@link #shown} with a separate enabled answer: a busy row keeps the action in place, greyed. */
    static void present(AnActionEvent e, boolean applicable, boolean enabled) {
        Shown shown = shown(e.isFromContextMenu(), applicable, enabled);
        e.getPresentation().setVisible(shown.visible());
        e.getPresentation().setEnabled(shown.enabled());
    }

    /**
     * Pure: the lifecycle label of a stopped task — "Resume" only when
     * there is a conversation to resume into, "Start" when the only way
     * forward is a fresh one; the own-window variant names where (A6, A10).
     */
    static String lifecycleText(boolean resumable, boolean ownWindow) {
        return (resumable ? "Resume" : "Start") + (ownWindow ? " in Its Own Window" : "");
    }

    /**
     * Whether no operation is already in flight for {@code row}'s session —
     * the shared busy guard for the tree's mutating actions. Memory-only
     * reads (the registry's O(1) probes, the transfer table, the row's
     * warmed resumable answer), safe on the EDT (A5, A11).
     */
    static boolean notBusy(TaskRow row) {
        return !SessionCommands.busy(SessionCommands.stateOf(row.session(), Optional.of(row.resumable())));
    }

    /**
     * Whether a launch action — Resume/Start in this window or in the
     * task's own — may run for {@code row}: the shared {@link
     * SessionCommands#lifecycleEnabled} policy on the row's current facts,
     * in the tree's global scope (the F1 detached rule never turns a tree
     * action into Show agent). Busy always wins; past that, a stopped
     * session whose agent this build cannot run is held off — it has
     * nothing to start and nothing to resume into — so the tree greys the
     * launch actions exactly as the tab header and strip do. Memory-only
     * reads, safe on the EDT (A5, A11).
     */
    static boolean launchable(TaskRow row) {
        return SessionCommands.lifecycleEnabled(
                SessionCommands.stateOf(row.session(), Optional.of(row.resumable())));
    }

    /**
     * The task and project actions by their {@link TaskMenu.Item} menu
     * identity — one action instance each, shared by the tree's context
     * menu and the tool window's toolbar. The removal action serves every
     * removal entry: its label follows the selection (removalActionName),
     * so the same instance sits under several keys.
     */
    static Map<TaskMenu.Item, AnAction> taskActions() {
        AnAction deactivate = task("Deactivate", "Deactivate this task's agent", AllIcons.Actions.Suspend,
                TaskRow::running, TaskActions::notBusy,
                (project, row) -> SessionCommandHandlers.deactivate(project, row.session(), false, () -> {}));
        AnAction askReview = task("Ask Agent to Review Changes", "Ask this task's agent to review its changes",
                AllIcons.Actions.Diff, TaskActions::reviewsChanges, TaskActions::reviewChanges);
        AnAction deactivateAndClose = task("Deactivate && Close Window",
                "Deactivate the agent and close its window", AllIcons.Actions.Close,
                TaskActions::closesOwnWindow, TaskActions::notBusy,
                (project, row) -> SessionCommandHandlers.deactivate(project, row.session(), true, () -> {}));
        // F5: moves an agent another window shows into this one.
        AnAction showHere = task("Show Here", "Move this agent's console into this window", AllIcons.Actions.Show,
                row -> row.placement() instanceof TaskPlacement.OwnWindow
                        || row.placement() instanceof TaskPlacement.OtherWindow,
                (project, row) -> AgentConsoles.showHere(project, row.session().id()));
        // Resume in this window, hosted when the task is another
        // worktree's — as New task does; its own window is the
        // explicit alternative.
        AnAction resume = task("Resume", "Resume the task's conversation in this window", AllIcons.Actions.Execute,
                row -> !row.running(), TaskActions::launchable, TaskActions::resumesHere,
                (project, row) -> AgentConsoles.resumeOrStart(project, row.session()));
        AnAction resumeInOwnWindow = task("Resume in Its Own Window",
                "Open the task's own window and resume its conversation there",
                AllIcons.Actions.MoveTo2, TaskActions::resumesElsewhere, TaskActions::launchable,
                TaskActions::resumesInOwnWindow,
                (project, row) -> TaskNavigator.switchTo(row.session(),
                        target -> AgentConsoles.resumeOrStart(target, row.session())));
        AnAction transfer = task("Transfer to…", "Continue this live task with another installed agent",
                AllIcons.Actions.Refresh, row -> SessionTransfer.available(row.session(), row.activity()),
                TaskActions::notBusy,
                (project, row) -> SessionTransfer.show(project, row.session()));
        AnAction rename = task("Rename…", "Rename this session", AllIcons.Actions.Edit, row -> true,
                (project, row) -> SessionRename.show(project, row.session()));
        AnAction done = task("Done with This Task…", "Forget the task, and optionally remove its worktree",
                AllIcons.Actions.Checked, row -> true, TaskActions::notBusy,
                (project, row) -> SessionCommandHandlers.done(project, row.session()));
        AnAction removeWorktrees = removeWorktrees();
        AnAction removeProject = removeProject();
        Map<TaskMenu.Item, AnAction> actions = new EnumMap<>(TaskMenu.Item.class);
        actions.put(TaskMenu.Item.DEACTIVATE, deactivate);
        actions.put(TaskMenu.Item.REVIEW_CHANGES, askReview);
        actions.put(TaskMenu.Item.DEACTIVATE_AND_CLOSE, deactivateAndClose);
        actions.put(TaskMenu.Item.SHOW_HERE, showHere);
        actions.put(TaskMenu.Item.RESUME, resume);
        actions.put(TaskMenu.Item.RESUME_IN_OWN_WINDOW, resumeInOwnWindow);
        actions.put(TaskMenu.Item.TRANSFER_TO, transfer);
        actions.put(TaskMenu.Item.RENAME, rename);
        actions.put(TaskMenu.Item.DONE_WITH_TASK, done);
        actions.put(TaskMenu.Item.REMOVE_WORKTREE, removeWorktrees);
        actions.put(TaskMenu.Item.REMOVE_STALE_WORKTREES, removeWorktrees);
        actions.put(TaskMenu.Item.REMOVE_INACTIVE_WORKTREES, removeWorktrees);
        actions.put(TaskMenu.Item.REMOVE_WORKTREES, removeWorktrees);
        actions.put(TaskMenu.Item.REMOVE_FROM_DRYDOCK, removeProject);
        return actions;
    }

    /**
     * Pure: the map's actions as the toolbar shows them — one button per
     * action instance, in the enum's {@code values()} order. The removal
     * action is one instance under several menu entries; a raw copy of the
     * map's values would add its button several times.
     */
    static List<AnAction> toolbarActions(Map<TaskMenu.Item, AnAction> actions) {
        List<AnAction> toolbar = new ArrayList<>();
        for (TaskMenu.Item item : TaskMenu.Item.values()) {
            AnAction action = actions.get(item);
            if (action != null && !toolbar.contains(action)) {
                toolbar.add(action);
            }
        }
        return List.copyOf(toolbar);
    }

    /** Whether Ask Agent to Review Changes applies: the agent runs, whatever its mode. Pure. */
    static boolean reviewsChanges(TaskRow row) {
        return row.running();
    }

    /**
     * Asks {@code row}'s agent to review its changes. EDT. The request runs
     * under a background progress that ends when it returns; any outcome but
     * a sent prompt is shown in a warning dialog on the EDT.
     */
    static void reviewChanges(Project from, TaskRow row) {
        ProgressManager.getInstance().run(new Task.Backgroundable(from, "Starting review…", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                DrydockPluginService plugin = DrydockPluginService.getInstance();
                AgentExecutionRegistry executions = AgentExecutionRegistry.getInstance();
                ReviewRequest request = ReviewRequest.forBackend(plugin.initialization(), plugin::reviewBackend,
                        executions::isRunning, kind -> plugin.registry().supportsSubagents(kind),
                        executions::submitPrompt);
                ReviewRequest.Outcome outcome = request.request(row.session());
                ReviewRequest.message(row.session().displayName(), outcome).ifPresent(message ->
                        ApplicationManager.getApplication().invokeLater(
                                () -> Messages.showWarningDialog(from, message, "Drydock"),
                                ModalityState.nonModal(), from.getDisposed()));
            }
        });
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

    /** Double-click/Enter follows the primary action: resume idle tasks here, focus running ones. */
    static DefaultActivation defaultActivation(TaskRow row) {
        return row.running() ? DefaultActivation.SWITCH_TO_RUNNING : DefaultActivation.RESUME_HERE;
    }

    /**
     * Sets the Resume action's label and icon from the row: "Resume" only
     * when the task has a conversation to resume into, "Start" otherwise
     * (A6, A10, A11).
     */
    private static void resumesHere(Presentation presentation, TaskRow row) {
        presentation.setText(lifecycleText(row.resumable(), false));
        presentation.setIcon(row.resumable() ? SessionCommands.Lifecycle.RESUME.icon()
                : SessionCommands.Lifecycle.START.icon());
    }

    /** The own-window variant: the same honest label, the move-elsewhere icon. */
    private static void resumesInOwnWindow(Presentation presentation, TaskRow row) {
        presentation.setText(lifecycleText(row.resumable(), true));
        presentation.setIcon(SessionCommands.Command.OWN_PROJECT.icon());
    }

    private interface RowAction {
        void run(Project project, TaskRow row);
    }

    /** Optional per-row presentation beyond show/hide/grey: the lifecycle actions' label and icon. */
    private interface Presenter {
        Presenter NONE = (presentation, row) -> {
        };

        void show(Presentation presentation, TaskRow row);
    }

    private static AnAction task(String text, String description, Icon icon, Predicate<TaskRow> applies,
                                 RowAction action) {
        return task(text, description, icon, applies, row -> true, Presenter.NONE, action);
    }

    private static AnAction task(String text, String description, Icon icon, Predicate<TaskRow> applies,
                                 Predicate<TaskRow> enabled, RowAction action) {
        return task(text, description, icon, applies, enabled, Presenter.NONE, action);
    }

    /**
     * One tree action: shown where {@code applies} holds, greyed while the
     * busy guard ({@code enabled}) or applicability says no, with an optional
     * per-row {@code presenter} beyond that (the lifecycle label). While busy
     * the action is disabled, never hidden — the row is still a task.
     */
    private static AnAction task(String text, String description, Icon icon, Predicate<TaskRow> applies,
                                 Predicate<TaskRow> enabled, Presenter presenter, RowAction action) {
        return new DumbAwareAction(text, description, icon) {
            @Override
            public void update(@NotNull AnActionEvent e) {
                TaskRow row = e.getData(TaskTreePanel.SELECTED_NODE) instanceof TaskRow selected ? selected : null;
                present(e, row != null && applies.test(row), row != null && enabled.test(row));
                if (row != null) {
                    presenter.show(e.getPresentation(), row);
                }
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
     * "Remove from Drydock" on a project row: takes the project off the
     * shared list — for the standalone app too — and touches nothing else:
     * not the repository, not its worktrees, not running agents. Asks only
     * when some of its agents are running here, to say they keep running.
     */
    private static AnAction removeProject() {
        return new DumbAwareAction("Remove from Drydock", "Take this project off Drydock's list; nothing on disk "
                + "changes", AllIcons.Actions.Close) {
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

    /** Removes one idle row, a stale group, or every inactive worktree under a project. */
    private static AnAction removeWorktrees() {
        return new DumbAwareAction("Remove Inactive Worktrees…",
                "Remove inactive worktree folders; branches are kept", AllIcons.General.Remove) {
            @Override
            public void update(@NotNull AnActionEvent e) {
                List<TaskNode> selected = selectedNodes(e);
                List<IdleWorktree> targets = removable(selected);
                present(e, !targets.isEmpty() && e.getData(TaskTreePanel.SELECTED_PROJECT_ROOT) != null
                        && e.getData(TaskTreePanel.WORKTREE_REMOVAL_UI) != null);
                e.getPresentation().setText(removalActionName(selected));
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                Project project = e.getProject();
                Path root = e.getData(TaskTreePanel.SELECTED_PROJECT_ROOT);
                TaskTreePanel.WorktreeRemovalUi removalUi = e.getData(TaskTreePanel.WORKTREE_REMOVAL_UI);
                List<TaskNode> selected = selectedNodes(e);
                List<IdleWorktree> targets = removable(selected);
                if (project == null || root == null || removalUi == null || targets.isEmpty()) {
                    return;
                }
                confirmAndRemove(project, root, targets,
                        selected.size() == 1 && selected.get(0) instanceof StaleWorktrees, removalUi);
            }
        };
    }

    /** EDT entry point shared by the action and the stale row's visible link. */
    static void confirmAndRemove(Project project, Path root, List<IdleWorktree> targets, boolean staleOnly,
                                 TaskTreePanel.WorktreeRemovalUi removalUi) {
        String question = removalQuestion(targets.size(), staleOnly);
        if (Messages.showOkCancelDialog(project, question, "Remove Worktrees",
                removalButton(targets.size()), "Cancel", AllIcons.General.Warning) == Messages.OK) {
            removeInBackground(project, root, targets, removalUi);
        }
    }

    static List<IdleWorktree> removable(TaskNode node) {
        return switch (node) {
            case ProjectRow row -> row.inactiveWorktrees().stream()
                    .filter(worktree -> !worktree.locked() && !worktree.open()).toList();
            case StaleWorktrees stale -> stale.worktrees();
            case IdleWorktree idle when !idle.locked() && !idle.open() -> List.of(idle);
            case null, default -> List.of();
        };
    }

    /** All selected targets, with parent/child selection overlaps removed. */
    static List<IdleWorktree> removable(List<TaskNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }
        Map<Path, IdleWorktree> unique = new LinkedHashMap<>();
        for (TaskNode node : nodes) {
            List<IdleWorktree> targets = removable(node);
            if (targets.isEmpty()) {
                // A mixed selection must not silently act on just its
                // removable subset.
                return List.of();
            }
            targets.forEach(target -> unique.putIfAbsent(target.worktree(), target));
        }
        return List.copyOf(unique.values());
    }

    private static List<TaskNode> selectedNodes(AnActionEvent event) {
        List<TaskNode> selected = event.getData(TaskTreePanel.SELECTED_NODES);
        if (selected != null) {
            return selected;
        }
        TaskNode lead = event.getData(TaskTreePanel.SELECTED_NODE);
        return lead == null ? List.of() : List.of(lead);
    }

    static String removalActionName(TaskNode node) {
        if (node instanceof IdleWorktree) {
            return "Remove Worktree…";
        }
        if (node instanceof StaleWorktrees) {
            return "Remove Stale Worktrees…";
        }
        return "Remove Inactive Worktrees…";
    }

    static String removalActionName(List<TaskNode> nodes) {
        return nodes != null && nodes.size() == 1
                ? removalActionName(nodes.get(0))
                : "Remove Worktrees…";
    }

    static String removalQuestion(int count, boolean staleOnly) {
        String kind = staleOnly ? "stale" : "inactive";
        return "Remove " + count + " " + kind + (count == 1 ? " worktree" : " worktrees") + "? "
                + "This permanently deletes " + (count == 1 ? "its folder" : "their folders")
                + " and discards any uncommitted and untracked changes. Branches are kept.";
    }

    static String removalButton(int count) {
        return count == 1 ? "Remove" : "Remove " + count;
    }

    private static void removeInBackground(Project project, Path root, List<IdleWorktree> targets,
                                           TaskTreePanel.WorktreeRemovalUi removalUi) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Removing worktrees…", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                List<String> failures = new ArrayList<>();
                List<Path> removed = new ArrayList<>();
                try (WorktreeService worktrees = new WorktreeService()) {
                    for (int i = 0; i < targets.size(); i++) {
                        IdleWorktree target = targets.get(i);
                        indicator.setText("Removing " + target.branch());
                        indicator.setFraction((double) i / targets.size());
                        try {
                            // The confirmation explicitly accepts discarded
                            // local changes. Branches intentionally survive.
                            worktrees.removeDiscardingChanges(root, target.worktree(), Optional.empty())
                                    .get(REMOVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                            removed.add(target.worktree());
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            failures.add(target.branch() + ": interrupted");
                            break;
                        } catch (ExecutionException | TimeoutException | RuntimeException ex) {
                            failures.add(target.branch() + ": " + WorktreeErrors.visibleMessage(ex));
                        }
                    }
                } finally {
                    try {
                        if (!removed.isEmpty() && !project.isDisposed()) {
                            indicator.setText("Updating task list…");
                            indicator.setFraction(1);
                            Set<Path> removedPaths = Set.copyOf(removed);
                            // Keep the progress task alive until the rows it
                            // removed are gone from the visible tree. The
                            // authoritative rescan below can take several
                            // seconds because it also probes activity.
                            ApplicationManager.getApplication().invokeAndWait(
                                    () -> removalUi.worktreesRemoved(removedPaths), ModalityState.any());
                        }
                    } finally {
                        // Some removals may have succeeded before a later one
                        // failed; always converge the tree on the on-disk state.
                        DrydockSessionsListener.publishChanged();
                    }
                }
                if (!failures.isEmpty()) {
                    ApplicationManager.getApplication().invokeLater(() -> Messages.showErrorDialog(project,
                            "Some worktrees could not be removed:\n" + String.join("\n", failures), "Drydock"),
                            ModalityState.nonModal(), project.getDisposed());
                }
            }
        });
    }
}
