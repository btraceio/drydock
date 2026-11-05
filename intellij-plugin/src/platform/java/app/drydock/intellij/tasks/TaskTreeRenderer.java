package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentRegistry;
import app.drydock.delayedtasks.DelayedTaskService;
import app.drydock.delayedtasks.TaskStoreWatcher;
import app.drydock.domain.SessionStatus;
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
import app.drydock.tasks.TaskId;
import app.drydock.tasks.TaskState;
import app.drydock.ui.AgentLabels;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.util.IconLoader;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleTextAttributes;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;

import java.awt.Color;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

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
            // Fully-qualified on purpose: the delayed-task layer reuses the
            // simple name TaskNode in app.drydock.tasks; this class's own
            // TaskNode (app.drydock.intellij.tasks) stays unqualified above.
            case app.drydock.tasks.TaskNode.DelayedTaskRow task -> paintDelayedTask(task);
            case app.drydock.tasks.TaskNode.SessionTasks session ->
                    // A session row plus task children: the session row itself
                    // renders exactly as it always did (below), unchanged.
                    paintTask(session.session());
            case app.drydock.tasks.TaskNode.TasksSection section -> paintTasksSection(section);
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
        if (task.session().flagged()) {
            append("  " + FLAG_TAG, FLAG_ATTRIBUTES);
        }
        if (task.readOnly()) {
            append("  RO", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
        }
        if (task.branchPullRequest().isPresent()) {
            BranchPullRequest pullRequest = task.branchPullRequest().get();
            append("  " + pullRequestBadge(pullRequest), badgeAttributes(pullRequest));
            setToolTipText(pullRequestTooltip(pullRequest));
        } else {
            pullRequestOf(task).ifPresent(number ->
                    append("  PR #" + number, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES));
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
     * The pull request a session row belongs to (pure): the session's own PR
     * link when it carries a number, else the {@code pr-<n>} branch a PR
     * checkout gives its worktree. From the row alone, never a gh call.
     */
    static Optional<Integer> pullRequestOf(TaskRow task) {
        Optional<Integer> linked = task.session().pr().number();
        return linked.isPresent() ? linked : task.branch().flatMap(PrCheckoutService::pullRequestNumberOf);
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

    /** Pure. The badge text: {@code PR #12}, with the state word for anything but a plain open PR. */
    static String pullRequestBadge(BranchPullRequest pullRequest) {
        String base = "PR #" + pullRequest.number();
        return switch (badgeKind(pullRequest)) {
            case OPEN, UNKNOWN -> base;
            case DRAFT -> base + " draft";
            case MERGED -> base + " merged";
            case CLOSED -> base + " closed";
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

    /** The badge colours: GitHub's own green, grey, purple and red for open, draft, merged and closed. */
    private static SimpleTextAttributes badgeAttributes(BranchPullRequest pullRequest) {
        return switch (badgeKind(pullRequest)) {
            case OPEN -> OPEN_BADGE;
            case DRAFT, UNKNOWN -> SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES;
            case MERGED -> MERGED_BADGE;
            case CLOSED -> CLOSED_BADGE;
        };
    }

    private static final SimpleTextAttributes OPEN_BADGE = new SimpleTextAttributes(
            SimpleTextAttributes.STYLE_BOLD, new JBColor(new Color(0x1A, 0x7F, 0x37), new Color(0x3F, 0xB9, 0x50)));
    private static final SimpleTextAttributes MERGED_BADGE = new SimpleTextAttributes(
            SimpleTextAttributes.STYLE_BOLD, new JBColor(new Color(0x82, 0x50, 0xDF), new Color(0xA3, 0x71, 0xF7)));
    private static final SimpleTextAttributes CLOSED_BADGE = new SimpleTextAttributes(
            SimpleTextAttributes.STYLE_BOLD, new JBColor(new Color(0xCF, 0x22, 0x2E), new Color(0xF8, 0x51, 0x49)));

    /** The follow-up flag's tag on a session row. */
    static final String FLAG_TAG = "\u2691 follow up";

    /** The follow-up tag: bold in the stale flag's orange, so it reads as the human's own mark. */
    private static final SimpleTextAttributes FLAG_ATTRIBUTES = new SimpleTextAttributes(
            SimpleTextAttributes.STYLE_BOLD, new JBColor(new Color(0xB5, 0x4A, 0x0E), new Color(0xD9, 0x8A, 0x5A)));

    /** Review's hint word: bold, so "in review" reads as waiting on YOU, not as just another state (D16). */
    private static final SimpleTextAttributes REVIEW_HINT_ATTRIBUTES =
            new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor.GRAY);

    /** Interrupted's hint word: italic, so a lost session reads different from a fresh ready one (D10). */
    private static final SimpleTextAttributes INTERRUPTED_HINT_ATTRIBUTES =
            new SimpleTextAttributes(SimpleTextAttributes.STYLE_ITALIC, JBColor.GRAY);

    /** The stale flag's colour: the review tree's open-findings orange, dark- and light-theme aware. */
    private static final SimpleTextAttributes STALE_ATTRIBUTES = new SimpleTextAttributes(
            SimpleTextAttributes.STYLE_PLAIN, new JBColor(new Color(0xB5, 0x4A, 0x0E), new Color(0xD9, 0x8A, 0x5A)));

    /**
     * Paints one delayed task (D9-D11) from the CARRIED row only: the
     * presentation's icon and hint word, the age and stale flag against one
     * clock read for this paint, and the carried blocker texts - never a
     * recomputation, a disk lookup or a per-row timer. The plan's stage/phase
     * annotation (T40, P4.12) rides the same discipline: it is read from the
     * service's CACHED publications only (published plan records, watched
     * snapshot, live-session view), so the progress stays visible wherever
     * the row sits -- in the section or under its session row (D10) -- and no
     * plan file is ever read at paint time.
     */
    private void paintDelayedTask(app.drydock.tasks.TaskNode.DelayedTaskRow task) {
        Instant now = Instant.now();
        setIcon(delayedIcon(task));
        append(task.title());
        append("  " + delayedHint(task), delayedHintAttributes(task.presentation()));
        Optional<DelayedTaskRows.PlanProgress> progress = planProgressOf(task.id());
        progress.ifPresent(annotation -> append("  " + annotation.text(),
                SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES));
        String age = delayedAge(task, now);
        if (!age.isEmpty()) {
            append(" · " + age, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
        }
        if (task.stale()) {
            append(" · stale", STALE_ATTRIBUTES);
        }
        for (String blocker : task.blockers()) {
            append(" · " + blocker, SimpleTextAttributes.GRAYED_ATTRIBUTES);
        }
        setToolTipText(delayedTooltip(task, now) + progress.map(annotation ->
                " · plan " + annotation.text()).orElse(""));
    }

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
     * Paints the task layer's section header (D9) in the existing section
     * shape: folder icon, bold label, greyed count badge.
     */
    private void paintTasksSection(app.drydock.tasks.TaskNode.TasksSection section) {
        setIcon(AllIcons.Nodes.Folder);
        append(section.label(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
        int count = section.children().size();
        if (count > 0) {
            append("  " + count + (count == 1 ? " task" : " tasks"), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
        }
        setToolTipText("Delayed tasks · " + section.label());
    }

    /**
     * Pure. The presentation's icon: every presentation reads at a glance,
     * and review (an eye) and interrupted (a warning) are visibly not the
     * ready one (D9, D16).
     */
    static Icon delayedIcon(app.drydock.tasks.TaskNode.DelayedTaskRow task) {
        return switch (task.presentation()) {
            case READY -> AllIcons.Actions.Execute;
            case BLOCKED -> AllIcons.General.Information;
            case REVIEW -> AllIcons.Actions.Preview;
            case STARTED -> AllIcons.Actions.Forward;
            case INTERRUPTED -> AllIcons.General.Warning;
            case TERMINAL -> AllIcons.Actions.Checked;
        };
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
     * Pure. The hint word's style: review and interrupted get their own look
     * (bold / italic); every other presentation keeps the greyed small
     * badge style of the rows around it.
     */
    static SimpleTextAttributes delayedHintAttributes(TaskState.Presentation presentation) {
        return switch (presentation) {
            case REVIEW -> REVIEW_HINT_ATTRIBUTES;
            case INTERRUPTED -> INTERRUPTED_HINT_ATTRIBUTES;
            default -> SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES;
        };
    }

    /**
     * Pure. D9's age text against the GIVEN clock (never read here), empty
     * when the creation time is unknown - unknown is not zero.
     */
    static String delayedAge(app.drydock.tasks.TaskNode.DelayedTaskRow task, Instant now) {
        return task.created()
                .map(created -> AgentStripText.relativeAge(Duration.between(created, now)) + " old")
                .orElse("");
    }

    /**
     * Pure. The row's accessible one-line summary (the tooltip of a Jira
     * row's shape): id, hint word, age and stale flag, the carried blocker
     * texts (D9/D11), and the session hint - live sessions for a started
     * task, the last session for an interrupted one (D10).
     */
    static String delayedTooltip(app.drydock.tasks.TaskNode.DelayedTaskRow task, Instant now) {
        StringBuilder tip = new StringBuilder("Task ").append(task.id().text())
                .append(" (").append(delayedHint(task)).append(')');
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
