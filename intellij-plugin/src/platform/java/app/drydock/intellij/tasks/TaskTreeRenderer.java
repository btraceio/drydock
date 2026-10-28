package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentRegistry;
import app.drydock.domain.SessionStatus;
import app.drydock.git.GhCliService.OpenIssue;
import app.drydock.git.JiraIssue;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.agent.AgentStripText;
import app.drydock.intellij.tasks.TaskNode.AddCurrentProject;
import app.drydock.intellij.tasks.TaskNode.IdleWorktree;
import app.drydock.intellij.tasks.TaskNode.IssueRow;
import app.drydock.intellij.tasks.TaskNode.ProjectRow;
import app.drydock.intellij.tasks.TaskNode.PullRequestRow;
import app.drydock.intellij.tasks.TaskNode.Section;
import app.drydock.intellij.tasks.TaskNode.StaleWorktrees;
import app.drydock.intellij.tasks.TaskNode.TaskRow;
import app.drydock.intellij.tasks.TaskNode.WorktreeGroup;
import app.drydock.intellij.ui.SessionDots;
import app.drydock.ui.AgentLabels;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.util.IconLoader;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;
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
     * The neutral bundled Jira source mark (a ticket glyph, deliberately not
     * Atlassian's logo; spec Q6, risk 8). Dark variant:
     * {@code jiraIssue_dark.svg}.
     */
    static final Icon JIRA_ICON = IconLoader.getIcon("/icons/jiraIssue.svg", TaskTreeRenderer.class);

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
                if (section.children().stream().noneMatch(TaskNode.Deferred.class::isInstance)) {
                    append("  " + itemCount(section), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                }
            }
            case TaskNode.Deferred deferred ->
                    append(deferred.message(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
            case WorktreeGroup group -> {
                append("⑂ " + group.branch(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                append("  " + group.tasks().size() + " tasks", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
            }
            case StaleWorktrees stale -> {
                setIcon(AllIcons.Nodes.Folder);
                append("Stale", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                append("  " + stale.worktrees().size() + (stale.worktrees().size() == 1
                        ? " worktree" : " worktrees"), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                append(" · ", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                append("Remove all…", SimpleTextAttributes.LINK_ATTRIBUTES, LINK);
            }
            case IdleWorktree idle -> {
                append("⑂ " + idle.branch(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                append("  no task", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                idle.lastActivity().ifPresent(last -> append(" · " + AgentStripText.relativeAge(
                        Duration.between(last, Instant.now())), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES));
                if (idle.locked()) {
                    append(" · locked", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                }
                if (idle.open()) {
                    append(" · open", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                }
                append(" · ", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                append("Start ▸", SimpleTextAttributes.LINK_ATTRIBUTES, LINK);
            }
            case IssueRow issue -> {
                sourceIcon(issue).ifPresent(this::setIcon);
                append(issueText(issue.issue()));
                if (!issue.issue().labels().isEmpty()) {
                    append("  " + String.join(", ", issue.issue().labels()),
                            SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                }
            }
            case TaskNode.JiraIssueRow jira -> {
                setIcon(JIRA_ICON);
                append(issueText(jira.issue()));
                if (!jira.issue().labels().isEmpty()) {
                    append("  " + String.join(", ", jira.issue().labels()),
                            SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                }
                setToolTipText("Jira issue " + jira.issue().key());
            }
            case PullRequestRow pullRequest -> {
                setIcon(AllIcons.Vcs.Vendors.Github);
                append("#" + pullRequest.pullRequest().number() + " " + pullRequest.pullRequest().title());
                pullRequest.pullRequest().author().ifPresent(author ->
                        append("  @" + author, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES));
                append(" · ", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                append("Review ▸", SimpleTextAttributes.LINK_ATTRIBUTES, LINK);
            }
            case TaskRow task -> paintTask(task);
            default -> append(String.valueOf(node.getUserObject()));
        }
    }

    /**
     * Pure. The source mark of an issue row: GitHub's vendor icon, the
     * neutral Jira ticket, nothing for every other node (pull requests keep
     * the GitHub mark they always had).
     */
    static Optional<Icon> sourceIcon(TaskNode node) {
        return switch (node) {
            case IssueRow ignored -> Optional.of(AllIcons.Vcs.Vendors.Github);
            case TaskNode.JiraIssueRow ignored -> Optional.of(JIRA_ICON);
            default -> Optional.empty();
        };
    }

    /** Pure. A GitHub row's text: {@code #<number> <title>}. */
    static String issueText(OpenIssue issue) {
        return "#" + issue.number() + " " + issue.title();
    }

    /** Pure. A Jira row's text: {@code <KEY> <summary>}. */
    static String issueText(JiraIssue issue) {
        return issue.key() + " " + issue.summary();
    }

    /** Entity count rather than immediate child count (Sessions may contain Active/Inactive folders). */
    private static int itemCount(Section section) {
        if (section.children().stream().allMatch(TaskNode.Deferred.class::isInstance)) {
            return 0;
        }
        return section.children().stream().mapToInt(node -> switch (node) {
            case TaskNode.Deferred ignored -> 0;
            case Section nested -> itemCount(nested);
            case StaleWorktrees stale -> stale.worktrees().size();
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
        if (task.folderMissing()) {
            append("  folder missing", SimpleTextAttributes.ERROR_ATTRIBUTES);
        }
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
     * shows; for an idle task from another worktree (its own worktree's Resume
     * is the toolbar's) {@code Resume here} while it still has a conversation
     * to resume into, and {@code Start here} once it does not — the honest
     * word for what a click would actually do.
     */
    static Optional<String> link(TaskRow task) {
        return switch (task.placement()) {
            case TaskPlacement.NotShown notShown -> Optional.of("Show here");
            case TaskPlacement.Idle idle when !idle.thisWindow() ->
                    Optional.of(task.resumable() ? "Resume here" : "Start here");
            default -> Optional.empty();
        };
    }
}
