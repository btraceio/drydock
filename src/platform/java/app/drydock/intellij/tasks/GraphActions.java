package app.drydock.intellij.tasks;


import app.drydock.delayedtasks.DelayedTaskStart;
import app.drydock.intellij.delayedtasks.DelayedTaskActions;
import app.drydock.intellij.tasks.InboxModel.Graph;
import app.drydock.intellij.tasks.InboxModel.TaskItem;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataKey;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ui.SimpleListCellRenderer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.util.List;
import java.util.function.Supplier;

/**
 * What the tool window offers on a dependency {@link Graph}: starting it, the context-menu entry that does,
 * and the toolbar toggle between the graph view and the plain state groups. Kept out of
 * {@link TaskTreePanel}, whose size is capped.
 */
final class GraphActions {

    /** The dependency graph the inbox selection is, or a task of. */
    static final DataKey<Graph> SELECTED_GRAPH = DataKey.create("drydock.selectedGraph");

    private GraphActions() {
    }

    /**
     * Starts a graph: its one open outcome, or the one the user picks from a popup over {@code anchor}.
     * Nothing happens for a graph with no open outcome (a stuck graph has none); the start flow itself shows
     * progress and refusals. EDT.
     */
    static void start(Project project, Graph graph, JComponent anchor) {
        List<TaskItem> targets = graph.startTargets();
        if (targets.size() == 1) {
            DelayedTaskStart.start(project, targets.get(0).row());
        } else if (!targets.isEmpty()) {
            JBPopupFactory.getInstance().createPopupChooserBuilder(targets)
                    .setTitle("Start the plan for which task?")
                    .setRenderer(SimpleListCellRenderer.create("", TaskItem::title))
                    .setItemChosenCallback(target -> DelayedTaskStart.start(project, target.row()))
                    .createPopup().showInCenterOf(anchor);
        }
    }

    /**
     * The context-menu entries for a selection: {@code startGraph} alone for a graph header, ahead of
     * {@code taskActions} for one of its tasks, and null when no graph is selected or the entry is absent.
     */
    static @Nullable AnAction[] menu(AnActionEvent event, @Nullable AnAction startGraph, AnAction[] taskActions) {
        if (startGraph == null || event.getData(SELECTED_GRAPH) == null) {
            return null;
        }
        if (event.getData(DelayedTaskActions.SELECTED_TASK) != null) {
            AnAction[] withGraph = new AnAction[taskActions.length + 1];
            withGraph[0] = startGraph;
            System.arraycopy(taskActions, 0, withGraph, 1, taskActions.length);
            return withGraph;
        }
        return event.getData(TaskTreePanel.SELECTED_NODE) == null ? new AnAction[] {startGraph} : null;
    }

    /** "Start Graph" in the context menu: enabled when the selected graph has an open outcome. */
    static AnAction startAction(Project project, JComponent anchor) {
        return new DumbAwareAction("Start Graph", "Plan and start the whole graph through its outcome task",
                AllIcons.Actions.Execute) {
            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.BGT;
            }

            @Override
            public void update(@NotNull AnActionEvent event) {
                Graph graph = event.getData(SELECTED_GRAPH);
                event.getPresentation().setEnabled(graph != null && !graph.startTargets().isEmpty());
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent event) {
                Graph graph = event.getData(SELECTED_GRAPH);
                if (graph != null) {
                    start(project, graph, anchor);
                }
            }
        };
    }

    /** The toolbar toggle: dependency graphs under Ready to start, or the plain state groups. */
    static AnAction viewToggle(InboxList list, Supplier<InboxViewState> viewState) {
        return new ToggleAction("Group Tasks by Dependency Graph",
                "Show tasks that depend on each other as one graph, with Start graph; off lists them by state",
                AllIcons.Hierarchy.Subtypes) {
            @Override
            public boolean isSelected(@NotNull AnActionEvent event) {
                return list.graphView();
            }

            @Override
            public void setSelected(@NotNull AnActionEvent event, boolean state) {
                list.setGraphView(state);
                viewState.get().setByState(!state);
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        };
    }
}
