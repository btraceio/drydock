package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentRegistry;
import app.drydock.domain.SessionStatus;
import app.drydock.git.GhCliService.OpenIssue;
import app.drydock.git.JiraIssue;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.agent.AgentStripText;
import app.drydock.intellij.tasks.TaskNode.AddCurrentProject;
import app.drydock.intellij.tasks.TaskNode.Ghost;
import app.drydock.intellij.tasks.TaskNode.GitHubNode;
import app.drydock.intellij.tasks.TaskNode.IdleWorktree;
import app.drydock.intellij.tasks.TaskNode.IssueRow;
import app.drydock.intellij.tasks.TaskNode.JiraGroupRow;
import app.drydock.intellij.tasks.TaskNode.JiraIssueRow;
import app.drydock.intellij.tasks.TaskNode.JiraNode;
import app.drydock.intellij.tasks.TaskNode.ProjectRow;
import app.drydock.intellij.tasks.TaskNode.PullRequestRow;
import app.drydock.intellij.tasks.TaskNode.Section;
import app.drydock.intellij.tasks.TaskNode.SectionKind;
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
import java.util.List;
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
                sectionCountBadge(section).ifPresent(badge ->
                        append("  " + badge, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES));
            }
            case TaskNode.Deferred deferred ->
                    append(deferred.message(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
            case TaskNode.Placeholder placeholder ->
                    append(placeholder.text(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
            case TaskNode.JiraGroupRow group -> {
                setIcon(AllIcons.Nodes.Folder);
                append(group.group().name(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                issueCountBadge(group).ifPresent(badge ->
                        append("  " + badge, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES));
                setToolTipText("Jira group · " + group.group().jql());
            }
            case GitHubNode github -> {
                setIcon(AllIcons.Vcs.Vendors.Github);
                append("GitHub", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                githubIssueCountBadge(github).ifPresent(badge ->
                        append("  " + badge, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES));
            }
            case JiraNode ignored -> {
                setIcon(JIRA_ICON);
                append("Jira", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
            }
            case Ghost ghost -> {
                setIcon(ghost.source() == TaskNode.GhostSource.GITHUB
                        ? AllIcons.Vcs.Vendors.Github : JIRA_ICON);
                append(ghost.source() == TaskNode.GhostSource.GITHUB
                        ? "GitHub not configured · " : "Jira not configured · ",
                        SimpleTextAttributes.GRAYED_ATTRIBUTES);
                append("Set up", SimpleTextAttributes.LINK_ATTRIBUTES, LINK);
            }
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
            // A group is a folder of rows, and its rows carry the source marks.
            case TaskNode.JiraGroupRow ignored -> Optional.empty();
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

    /**
     * Pure. A Jira group's issue count, shown only when the children ARE
     * the answer: the issue rows, or the single empty-result placeholder.
     * A not-loaded, loading or failed group shows none: no badge beats a
     * false "0 issues".
     */
    static Optional<String> issueCountBadge(TaskNode.JiraGroupRow group) {
        if (!answeredListing(group.children(), JiraIssueRow.class)) {
            return Optional.empty();
        }
        long issues = group.children().stream().filter(JiraIssueRow.class::isInstance).count();
        return Optional.of(issues + (issues == 1 ? " issue" : " issues"));
    }

    /** Pure. The GitHub node's issue count, under the same rule as a group's. */
    static Optional<String> githubIssueCountBadge(GitHubNode github) {
        if (!answeredListing(github.children(), IssueRow.class)) {
            return Optional.empty();
        }
        long issues = github.children().stream().filter(IssueRow.class::isInstance).count();
        return Optional.of(issues + (issues == 1 ? " issue" : " issues"));
    }

    /**
     * Whether {@code children} is a listing's final answer: all rows of the
     * given type, or exactly the {@link TaskTreeModel#NO_ISSUES} placeholder. A "Not loaded",
     * loading or failed child is not an answer.
     */
    private static boolean answeredListing(List<TaskNode> children, Class<? extends TaskNode> rowType) {
        return children.stream().allMatch(rowType::isInstance)
                || children.size() == 1 && children.get(0) instanceof TaskNode.Placeholder placeholder
                    && TaskTreeModel.NO_ISSUES.equals(placeholder.text());
    }

    /**
     * Pure. A section's item count, or none while any of it is still loading
     * (a deferred child, or an issues source still unloaded) or when nothing
     * countable is there besides a failure — a failed lookup is not "0".
     */
    static Optional<String> sectionCountBadge(Section section) {
        if (section.kind() == SectionKind.ISSUES) {
            boolean loading = section.children().stream().anyMatch(TaskTreeRenderer::issuesLoading);
            if (loading) {
                return Optional.empty();
            }
            int count = section.children().stream().mapToInt(TaskTreeRenderer::issueCount).sum();
            boolean problem = section.children().stream().anyMatch(TaskTreeRenderer::issuesFailed);
            return count == 0 && problem ? Optional.empty() : Optional.of(String.valueOf(count));
        }
        boolean loading = section.children().stream().anyMatch(child ->
                child instanceof TaskNode.Deferred
                        || child instanceof TaskNode.JiraGroupRow group
                        && group.children().stream().anyMatch(TaskNode.Deferred.class::isInstance));
        if (loading) {
            return Optional.empty();
        }
        int count = itemCount(section);
        boolean problem = section.children().stream().anyMatch(child ->
                child instanceof TaskNode.ListProblem
                        || child instanceof TaskNode.JiraGroupRow group
                        && group.children().stream().anyMatch(TaskNode.ListProblem.class::isInstance));
        return count == 0 && problem ? Optional.empty() : Optional.of(String.valueOf(count));
    }

    /** Whether an issues source node is still waiting for, or has never run, its fetch. */
    private static boolean issuesLoading(TaskNode node) {
        return switch (node) {
            case GitHubNode github -> github.children().stream().anyMatch(child ->
                    child instanceof TaskNode.Deferred
                            || notLoaded(child));
            case TaskNode.JiraGroupRow group -> group.children().stream().anyMatch(child ->
                    child instanceof TaskNode.Deferred || notLoaded(child));
            case JiraNode jira -> jira.children().stream().anyMatch(TaskTreeRenderer::issuesLoading);
            default -> false;
        };
    }

    private static boolean notLoaded(TaskNode child) {
        return child instanceof TaskNode.Placeholder placeholder
                && TaskTreeModel.NOT_LOADED.equals(placeholder.text());
    }

    /** Whether an issues source node ended in a problem row instead of a listing. */
    private static boolean issuesFailed(TaskNode node) {
        return switch (node) {
            case GitHubNode github -> github.children().stream().anyMatch(TaskNode.ListProblem.class::isInstance);
            case TaskNode.JiraGroupRow group -> group.children().stream()
                    .anyMatch(TaskNode.ListProblem.class::isInstance);
            case JiraNode jira -> jira.children().stream().anyMatch(TaskTreeRenderer::issuesFailed);
            default -> false;
        };
    }

    /** An issues source node's row count: GitHub's rows, or every group's rows. */
    private static int issueCount(TaskNode node) {
        return switch (node) {
            case GitHubNode github -> (int) github.children().stream()
                    .filter(IssueRow.class::isInstance).count();
            case JiraNode jira -> jira.children().stream()
                    .filter(TaskNode.JiraGroupRow.class::isInstance)
                    .mapToInt(group -> (int) ((TaskNode.JiraGroupRow) group).children().stream()
                            .filter(JiraIssueRow.class::isInstance).count())
                    .sum();
            case TaskNode.JiraGroupRow group -> (int) group.children().stream()
                    .filter(JiraIssueRow.class::isInstance).count();
            default -> 0;
        };
    }

    /**
     * Entity count for the non-issues sections (Sessions may contain
     * Active/Inactive folders; PR rows count one each). Loading, problem and
     * placeholder rows count nothing.
     */
    private static int itemCount(Section section) {
        if (section.children().stream().allMatch(TaskNode.Deferred.class::isInstance)) {
            return 0;
        }
        return section.children().stream().mapToInt(node -> switch (node) {
            case TaskNode.Deferred ignored -> 0;
            case TaskNode.ListProblem ignored -> 0;
            case TaskNode.Placeholder ignored -> 0;
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
