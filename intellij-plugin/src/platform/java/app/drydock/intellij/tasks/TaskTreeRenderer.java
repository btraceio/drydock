package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentRegistry;
import app.drydock.domain.SessionStatus;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.agent.AgentStripText;
import app.drydock.intellij.tasks.TaskNode.AddCurrentProject;
import app.drydock.intellij.tasks.TaskNode.IdleWorktree;
import app.drydock.intellij.tasks.TaskNode.IssueRow;
import app.drydock.intellij.tasks.TaskNode.ProjectRow;
import app.drydock.intellij.tasks.TaskNode.Section;
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
import java.util.Optional;

/** Paints one {@link TaskNode}: dot, name, and the grey where/what/age text. */
final class TaskTreeRenderer extends ColoredTreeCellRenderer {

    /** The tag on a row's link text ("Add", "Start ▸"): what a single click may act on. */
    static final Object LINK = new Object();

    /**
     * Whether a click on a row with a link acts: only the primary button, a
     * single click only on the link text, and a double-click anywhere on the
     * row. Pure.
     */
    static boolean clickActs(boolean primaryButton, boolean onLink, int clickCount) {
        return primaryButton && (clickCount == 2 || (onLink && clickCount == 1));
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
            case Section section -> {
                setIcon(AllIcons.Nodes.Folder);
                append(section.kind().label(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
                append("  " + itemCount(section), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
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
            case IssueRow issue -> {
                setIcon(AllIcons.Vcs.Vendors.Github);
                append("#" + issue.issue().number() + " " + issue.issue().title());
                if (!issue.issue().labels().isEmpty()) {
                    append("  " + String.join(", ", issue.issue().labels()),
                            SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                }
            }
            case TaskRow task -> paintTask(task);
            default -> append(String.valueOf(node.getUserObject()));
        }
    }

    /** Entity count rather than immediate child count (Sessions may contain Active/Inactive folders). */
    private static int itemCount(Section section) {
        return section.children().stream().mapToInt(node -> switch (node) {
            case Section nested -> itemCount(nested);
            case WorktreeGroup group -> group.tasks().size();
            default -> 1;
        }).sum();
    }

    private void paintTask(TaskRow task) {
        SessionStatus status = task.running() ? SessionStatus.RUNNING : task.session().status();
        setIcon(SessionDots.dotIcon(status, task.activity()));
        append(task.session().displayName());
        if (task.readOnly()) {
            append("  RO", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
        }
        String agent = AgentLabels.displayName(DrydockPluginService.getInstance().registry(),
                task.session().agentKind());
        append("   " + secondary(task, agent, Duration.between(task.session().lastOpenedAt(), Instant.now())),
                SimpleTextAttributes.GRAYED_ATTRIBUTES);
        link(task).ifPresent(text -> {
            append(" · ", SimpleTextAttributes.GRAYED_ATTRIBUTES);
            append(text, SimpleTextAttributes.LINK_ATTRIBUTES, LINK);
        });
    }

    /**
     * Pure. A running task says where it is shown: {@code <branch> · <where> ·
     * <Agent>} (cross-project tabs, F1/F5). An idle one keeps the MVP's text:
     * {@code <branch> · this window · <Agent>} for this window's worktree,
     * {@code ⑂ <branch> · <Agent> · <age>} otherwise.
     */
    static String secondary(TaskRow task, String agent, Duration age) {
        String meta = task.placement().meta();
        if (!meta.isEmpty()) {
            return task.branch().map(branch -> branch + " · ").orElse("") + meta + " · " + agent;
        }
        if (task.isThisWindow()) {
            return task.branch().map(branch -> branch + " · ").orElse("") + "this window · " + agent;
        }
        return task.branch().map(branch -> "⑂ " + branch + " · ").orElse("") + agent + " · "
                + AgentStripText.relativeAge(age);
    }

    /**
     * Pure: the row's inline link. {@code Show here} for an agent no window
     * shows; {@code Resume here} for an idle task from another worktree (its
     * own worktree's Resume is the toolbar's).
     */
    static Optional<String> link(TaskRow task) {
        return switch (task.placement()) {
            case TaskPlacement.NotShown notShown -> Optional.of("Show here");
            case TaskPlacement.Idle idle when !idle.thisWindow() -> Optional.of("Resume here");
            default -> Optional.empty();
        };
    }
}
