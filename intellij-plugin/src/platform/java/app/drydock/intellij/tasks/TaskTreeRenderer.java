package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentRegistry;
import app.drydock.domain.SessionStatus;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.agent.AgentStripText;
import app.drydock.intellij.tasks.TaskNode.AddCurrentProject;
import app.drydock.intellij.tasks.TaskNode.IdleWorktree;
import app.drydock.intellij.tasks.TaskNode.ProjectRow;
import app.drydock.intellij.tasks.TaskNode.TaskRow;
import app.drydock.intellij.tasks.TaskNode.WorktreeGroup;
import app.drydock.intellij.ui.SessionDots;
import app.drydock.ui.AgentLabels;

import com.intellij.icons.AllIcons;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import org.jetbrains.annotations.NotNull;

import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;

import java.time.Duration;
import java.time.Instant;

/** Paints one {@link TaskNode}: dot, name, and the grey where/what/age text. */
final class TaskTreeRenderer extends ColoredTreeCellRenderer {

    /** The tag on a row's link text ("Add", "Start ▸"): what a single click may act on. */
    static final Object LINK = new Object();

    /**
     * Whether a click on a row with a link acts: a single click only on the
     * link text, a double-click anywhere on the row. Pure.
     */
    static boolean clickActs(boolean onLink, int clickCount) {
        return clickCount == 2 || (onLink && clickCount == 1);
    }

    @Override
    public void customizeCellRenderer(@NotNull JTree tree, Object value, boolean selected, boolean expanded,
                                      boolean leaf, int row, boolean hasFocus) {
        if (!(value instanceof DefaultMutableTreeNode node)) {
            return;
        }
        switch (node.getUserObject()) {
            case TaskNode.ListProblem problem -> {
                setIcon(AllIcons.General.Warning);
                append(problem.message(), SimpleTextAttributes.ERROR_ATTRIBUTES);
            }
            case AddCurrentProject add -> {
                setIcon(AllIcons.General.Add);
                append(add.repoName() + " isn't in Drydock · ", SimpleTextAttributes.GRAYED_ATTRIBUTES);
                append("Add", SimpleTextAttributes.LINK_ATTRIBUTES, LINK);
            }
            case ProjectRow project -> {
                setIcon(AllIcons.Nodes.Folder);
                append(project.project().displayName(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                if (project.folderMissing()) {
                    append("  folder missing", SimpleTextAttributes.ERROR_ATTRIBUTES);
                }
            }
            case WorktreeGroup group -> {
                append("⑂ " + group.branch(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                append("  " + group.tasks().size() + " tasks", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
            }
            case IdleWorktree idle -> {
                append("⑂ " + idle.branch(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                append("  no task · ", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                append("Start ▸", SimpleTextAttributes.LINK_ATTRIBUTES, LINK);
            }
            case TaskRow task -> paintTask(task);
            default -> append(String.valueOf(node.getUserObject()));
        }
    }

    private void paintTask(TaskRow task) {
        SessionStatus status = task.running() ? SessionStatus.RUNNING : task.session().status();
        setIcon(SessionDots.dotIcon(status, task.activity()));
        append(task.session().displayName());
        if (task.readOnly()) {
            append("  RO", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
        }
        append("   " + secondary(task), SimpleTextAttributes.GRAYED_ATTRIBUTES);
    }

    /** {@code ⑂ <branch> · <Agent> · <age>}, or {@code <branch> · this window · <Agent>}. */
    static String secondary(TaskRow task) {
        DrydockPluginService plugin = DrydockPluginService.getInstance();
        AgentRegistry registry = plugin.registry();
        String agent = AgentLabels.displayName(registry, task.session().agentKind());
        if (task.isThisWindow()) {
            return task.branch().map(branch -> branch + " · ").orElse("") + "this window · " + agent;
        }
        String age = AgentStripText.relativeAge(Duration.between(task.session().lastOpenedAt(), Instant.now()));
        return task.branch().map(branch -> "⑂ " + branch + " · ").orElse("") + agent + " · " + age;
    }
}
