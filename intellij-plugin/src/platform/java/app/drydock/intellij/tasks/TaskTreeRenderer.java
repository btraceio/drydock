package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentRegistry;
import app.drydock.delayedtasks.DelayedTaskService;
import app.drydock.delayedtasks.TaskStoreWatcher;
import app.drydock.domain.SessionStatus;
import app.drydock.git.GhCliService;
import app.drydock.git.GhCliService.BranchPullRequest;
import app.drydock.git.GhCliService.OpenIssue;
import app.drydock.git.JiraIssue;
import app.drydock.git.PrCheckoutService;
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
import app.drydock.tasks.DelayedTaskRows;
import app.drydock.tasks.TaskDocument;
import app.drydock.tasks.TaskId;
import app.drydock.tasks.TaskMirror;
import app.drydock.tasks.TaskState;
import app.drydock.ui.AgentLabels;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.util.IconLoader;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.IconManager;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.util.ui.EmptyIcon;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;

import java.awt.Color;
import java.time.Duration;
import java.time.Instant;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.ToIntFunction;
import java.util.function.Function;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Paints one {@link TaskNode}: dot, name, and the grey where/what/age text.
 * The delayed-task layer (D9-D11) adds its rows here the same way: every
 * status, age, blocker and session hint comes CARRIED on the row the task
 * layer built (the {@code app.drydock.tasks} {@code TaskNode} values), so
 * painting reads cached row data only - no disk lookup, no clock per row
 * beyond the one paint-time read, and no hint is ever recomputed here.
 */
final class TaskTreeRenderer extends ColoredTreeCellRenderer {

    /** The tag on a row's link text ("Add", "Start ▸"): what a single click may act on. */
    static final Object LINK = new Object();

    /** The tag of a project row's "K in inbox" link: filters the inbox to that project. */
    static final Object INBOX_LINK = new Object();

    /** A project's last answered "N issues · M PRs", by main root; nothing until the panel says. */
    private Function<Path, Optional<String>> projectMeta = root -> Optional.empty();
    /** How many inbox items a project has, by main root; 0 until the panel says. */
    private ToIntFunction<Path> inboxCount = root -> 0;

    /** The tag on a conflicting pull request row's {@code Resolve Conflicts…} link. */
    static final Object RESOLVE_CONFLICTS_LINK = new Object();

    static final String RESOLVE_CONFLICTS_LINK_TEXT = "Resolve Conflicts\u2026";

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

    /** Where project rows read their "N issues · M PRs" and their inbox count. EDT. */
    void projectFacts(Function<Path, Optional<String>> meta, ToIntFunction<Path> inbox) {
        projectMeta = Objects.requireNonNull(meta, "meta");
        inboxCount = Objects.requireNonNull(inbox, "inbox");
    }

    @Override
    public void customizeCellRenderer(@NotNull JTree tree, Object value, boolean selected, boolean expanded,
                                      boolean leaf, int row, boolean hasFocus) {
        // One shared renderer instance paints every row: a tooltip set for a
        // Jira row would otherwise leak onto every node hovered afterwards.
        setToolTipText(null);
        if (!(value instanceof DefaultMutableTreeNode node)) {
            return;
        }
        switch (node.getUserObject()) {
            case TaskNode.ListProblem problem -> {
                setIcon(AllIcons.General.Warning);
                append(problem.message(), SimpleTextAttributes.ERROR_ATTRIBUTES);
            }
            case AddCurrentProject add -> {
                setIcon(AllIcons.Actions.NewFolder);
                append("Add open project ", SimpleTextAttributes.LINK_ATTRIBUTES, LINK);
                append(add.repoName(), new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD,
                        SimpleTextAttributes.LINK_ATTRIBUTES.getFgColor()), LINK);
                append(" — from IDE", SimpleTextAttributes.GRAYED_ATTRIBUTES);
            }
            case ProjectRow project -> {
                setIcon(AllIcons.Nodes.Folder);
                append(project.project().displayName(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                if (project.folderMissing()) {
                    append("  folder missing", SimpleTextAttributes.ERROR_ATTRIBUTES);
                }
                Path root = project.project().root();
                projectMeta.apply(root).ifPresent(meta ->
                        append("  " + meta, SimpleTextAttributes.GRAYED_ATTRIBUTES));
                int active = inboxCount.applyAsInt(root);
                if (active > 0) {
                    append("   ");
                    append(active + " in inbox", SimpleTextAttributes.LINK_ATTRIBUTES, INBOX_LINK);
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
                issue.issue().url().ifPresent(this::paintMirroredTask);
            }
            case TaskNode.JiraIssueRow jira -> {
                setIcon(JIRA_ICON);
                append(issueText(jira.issue()));
                if (!jira.issue().labels().isEmpty()) {
                    append("  " + String.join(", ", jira.issue().labels()),
                            SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                }
                setToolTipText("Jira issue " + jira.issue().key());
                paintMirroredTask(jira.issue().url());
            }
            case PullRequestRow pullRequest -> {
                setIcon(pullRequestRowIcon(pullRequest.pullRequest().conflicting()));
                append("#" + pullRequest.pullRequest().number() + " " + pullRequest.pullRequest().title());
                pullRequest.pullRequest().author().ifPresent(author ->
                        append("  @" + author, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES));
                if (pullRequest.pullRequest().conflicting()) {
                    setToolTipText("#" + pullRequest.pullRequest().number()
                            + " cannot merge into " + pullRequest.pullRequest().baseRefName() + ": it has conflicts");
                }
                paintLinkedTask(pullRequest.pullRequest().headRefName());
                append(" · ", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                append("Review ▸", SimpleTextAttributes.LINK_ATTRIBUTES, LINK);
                if (pullRequest.pullRequest().conflicting()) {
                    append(" · ", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                    append(RESOLVE_CONFLICTS_LINK_TEXT, SimpleTextAttributes.LINK_ATTRIBUTES, RESOLVE_CONFLICTS_LINK);
                }
            }
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

    /**
     * The pull request a session row belongs to (pure): the session's own PR
     * link when it carries a number, else the {@code pr-<n>} branch a PR
     * checkout gives its worktree. From the row alone, never a gh call.
     */
    static Optional<Integer> pullRequestOf(TaskRow task) {
        Optional<Integer> linked = task.session().pr().number();
        return linked.isPresent() ? linked : task.branch().flatMap(PrCheckoutService::pullRequestNumberOf);
    }

    /** Pure. The pull request's page address a session row offers as its link, when gh reported it. */
    static Optional<String> pullRequestUrl(TaskRow task) {
        return task.branchPullRequest().flatMap(BranchPullRequest::url);
    }

    /** What a branch's pull request badge reads as, which also picks its colour. */
    enum PullRequestBadgeKind { OPEN, DRAFT, MERGED, CLOSED, UNKNOWN }

    /** Pure. The badge kind of a branch's pull request: a draft is only a draft while it is open. */
    static PullRequestBadgeKind badgeKind(BranchPullRequest pullRequest) {
        return switch (pullRequest.state()) {
            case OPEN -> pullRequest.draft() ? PullRequestBadgeKind.DRAFT : PullRequestBadgeKind.OPEN;
            case MERGED -> PullRequestBadgeKind.MERGED;
            case CLOSED -> PullRequestBadgeKind.CLOSED;
            case UNKNOWN -> PullRequestBadgeKind.UNKNOWN;
        };
    }

    /** Pure. The session row's hover text when its branch has a pull request. */
    static String pullRequestTooltip(BranchPullRequest pullRequest) {
        String state = switch (badgeKind(pullRequest)) {
            case OPEN -> "open";
            case DRAFT -> "draft";
            case MERGED -> "merged";
            case CLOSED -> "closed";
            case UNKNOWN -> "state unknown";
        };
        return "Pull request #" + pullRequest.number() + " (" + state + ") from "
                + pullRequest.headRefName() + (pullRequest.title().isBlank() ? "" : ": " + pullRequest.title());
    }

    /**
     * The badge of a PR row whose head branch is a task's stacked branch,
     * looked up in the task service's cached snapshot (memory only, like
     * the mirror badge): the link mark and the task's title, the twin of
     * the task row's pull-request badge.
     */
    private void paintLinkedTask(String headRefName) {
        TaskStoreWatcher.WatchedSnapshot watched = DrydockPluginService.getInstance().taskService().watchedSnapshot();
        TaskLinks.taskOfBranch(headRefName, watched).ifPresent(document -> {
            append("  " + mirroredTaskBadge(document.title()), MIRROR_ATTRIBUTES);
            setToolTipText((getToolTipText() == null ? "" : getToolTipText() + " · ")
                    + linkedTaskTooltip(document));
        });
    }

    /** Pure. A task row's pull-request badge tooltip: the PR, its branch and its title. */
    static String pullRequestLinkTooltip(GhCliService.OpenPullRequest pullRequest) {
        return "Pull request #" + pullRequest.number() + " from " + pullRequest.headRefName()
                + (pullRequest.title().isBlank() ? "" : ": " + pullRequest.title());
    }

    /** Pure. A PR row's linked-task badge tooltip: the task working the PR's head branch. */
    static String linkedTaskTooltip(TaskDocument document) {
        return "Task '" + document.title() + "' (" + document.id() + ") works this branch";
    }

    /** A pull request row's leading icon: GitHub's mark, followed by the conflict icon while it conflicts. */
    static Icon pullRequestRowIcon(boolean conflicting) {
        if (!conflicting) {
            return AllIcons.Vcs.Vendors.Github;
        }
        return ROW_ICONS.computeIfAbsent(new IconPair(AllIcons.Vcs.Vendors.Github, PR_CONFLICT_ICON),
                pair -> IconManager.getInstance().createRowIcon(pair.first(), pair.second()));
    }

    private record IconPair(Icon first, Icon second) { }

    private static final Map<IconPair, Icon> ROW_ICONS = new ConcurrentHashMap<>();

    static final Icon PR_CONFLICT_ICON = IconLoader.getIcon("/icons/prConflict.svg", TaskTreeRenderer.class);

    /** Marks either end of a task mirror: the task's row and the mirrored issue's row. */
    static final String MIRROR_MARK = "\u21c4";

    /** Pure. An issue row's mirror badge: the mark and the title of the task it mirrors. */
    static String mirroredTaskBadge(String taskTitle) {
        return MIRROR_MARK + " task: " + taskTitle;
    }

    /**
     * The badge of an issue row some task mirrors, looked up in the task
     * service's cached snapshot (memory only, like the plan annotation).
     */
    private void paintMirroredTask(String url) {
        TaskMirroring.mirroredTask(url).ifPresent(document -> {
            append("  " + mirroredTaskBadge(document.title()), MIRROR_ATTRIBUTES);
            setToolTipText((getToolTipText() == null ? "" : getToolTipText() + " · ")
                    + "Mirror of the Drydock task '" + document.title() + "' (" + document.id() + ")");
        });
    }

    /** The mirror badges: small and in the link colour, so both ends of a mirror read as a link. */
    private static final SimpleTextAttributes MIRROR_ATTRIBUTES = new SimpleTextAttributes(
            SimpleTextAttributes.STYLE_SMALLER, JBUI.CurrentTheme.Link.Foreground.ENABLED);

    /**
     * The plan annotation of one delayed task (T40, P4.12), read from the
     * service's IN-MEMORY publications only -- the published plan records,
     * the cached watched snapshot and the live-session view; never a plan
     * file, never a fresh scan, no clock read of its own. Empty when the
     * service has published no snapshot yet or no persisted plan targets the
     * task. Pure over those cached values; the derivation itself is
     * {@link DelayedTaskRows#planProgress}.
     */
    static Optional<DelayedTaskRows.PlanProgress> planProgressOf(TaskId task) {
        DelayedTaskService service = DrydockPluginService.getInstance().taskService();
        TaskStoreWatcher.WatchedSnapshot watched = service.watchedSnapshot();
        if (watched == null) {
            return Optional.empty();
        }
        return DelayedTaskRows.planOf(service.publishedPlans(), task)
                .flatMap(record -> DelayedTaskRows.planProgress(record, watched.snapshot(),
                        service.liveSessionIds()));
    }

    /**
     * Pure. The presentation's hint word, carried from the row's view - the
     * distinct status text of D9/D10/D16. TERMINAL covers done and dropped
     * and the row carries only the presentation, so the word says both
     * rather than guessing one.
     */
    static String delayedHint(app.drydock.tasks.TaskNode.DelayedTaskRow task) {
        return switch (task.presentation()) {
            case READY -> "ready";
            case BLOCKED -> "blocked";
            case REVIEW -> "in review";
            case STARTED -> "in session";
            case INTERRUPTED -> "interrupted";
            case TERMINAL -> "done or dropped";
        };
    }

    /**
     * Pure. The row's accessible one-line summary (the tooltip of a Jira
     * row's shape): id, hint word, project when known, age and stale flag,
     * the carried blocker texts (D9/D11), and the session hint - live
     * sessions for a started task, the last session for an interrupted one (D10).
     */
    static String delayedTooltip(app.drydock.tasks.TaskNode.DelayedTaskRow task, Instant now) {
        StringBuilder tip = new StringBuilder("Task ").append(task.id().text())
                .append(" (").append(delayedHint(task)).append(')');
        if (task.project() != null) {
            tip.append(" · project ").append(task.project());
        }
        task.created().ifPresent(created -> tip.append(" · created ")
                .append(AgentStripText.relativeAge(Duration.between(created, now))).append(" ago"));
        if (task.stale()) {
            tip.append(" · stale (older than ").append(DelayedTaskRows.STALE_AFTER.toDays()).append(" days)");
        }
        for (String blocker : task.blockers()) {
            tip.append(" · ").append(blocker);
        }
        if (task.presentation() == TaskState.Presentation.INTERRUPTED) {
            task.lastSession().ifPresent(session -> tip.append(" · last session ").append(session));
        } else if (!task.liveSessions().isEmpty()) {
            tip.append(" · session ").append(String.join(", ", task.liveSessions()));
        }
        return tip.toString();
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

}
