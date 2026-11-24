package app.drydock.intellij.tasks;

import app.drydock.delayedtasks.TaskStoreWatcher.WatchedSnapshot;
import app.drydock.domain.PullRequestTarget;
import app.drydock.git.GhCliService.BranchPullRequest;
import app.drydock.intellij.agent.SessionTransfer;
import app.drydock.tasks.TaskDocument;
import app.drydock.tasks.TaskMirror;
import app.drydock.tasks.TaskNode.DelayedTaskRow;
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

import com.intellij.icons.AllIcons;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.DumbAwareAction;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Drydock tree's context menu, as pure layout: which actions apply to
 * the selected rows, in which sections, and in which order — navigation
 * first, work on the task next, its management after, destructive cleanup
 * last, and nothing that would only go to the destination the user is
 * already in (A10).
 *
 * <p>Only applicability is decided here, never presentation: each
 * {@link Item} is one action instance the panel owns, whose own
 * {@code update} keeps its label honest (Resume vs Start, the quick
 * review's remembered model). The one non-pure answer a menu needs —
 * transfer eligibility — is computed once per menu build by
 * {@link #factsFor} and injected, so {@link #sections} stays pure and
 * testable without the registry.</p>
 */
final class TaskMenu {

    /** The pull-request page number in a {@code /pull/<n>} address. */
    private static final Pattern PULL_REQUEST_PAGE = Pattern.compile("/pull/([1-9][0-9]*)/?$");

    /** One menu entry; the action behind it lives in the panel or {@link TaskActions}. */
    enum Item {
        /** Show the task's agent console in this window; an idle agent is never started. */
        SWITCH_TO_TASK,
        /** Raise the task's own worktree window — opening it when closed — and show its agent there. */
        SWITCH_TO_TASK_IN_OWN_WINDOW,
        DEACTIVATE,
        /** Ask the task's agent to review its changes, not the user viewing them. */
        REVIEW_CHANGES,
        TRANSFER_TO,
        RENAME,
        ASSOCIATE_PR,
        /** Set or clear the human's follow-up flag; the label follows the session's current flag. */
        FLAG,
        /** Start a babysitter for the session's open branch PR; only a local session with no engagement. */
        BABYSIT_PR,
        /** End the active babysitting engagement the row is either side of, and stop the babysitter's agent. */
        STOP_BABYSITTING,
        /** Switch to the babysitter watching this session's PR; a watched session only. */
        SHOW_BABYSITTER,
        /** Switch to the session a babysitter watches; a babysitter only. */
        SHOW_WATCHED,
        NEW_TASK,
        /** Create a worktree on the selected free branch and start a task in it, shown in this window. */
        NEW_TASK_ON_BRANCH,
        /** As NEW_TASK_ON_BRANCH, in the worktree's own window. */
        NEW_TASK_ON_BRANCH_OWN_WINDOW,
        /** Cut a new branch from the selected branch in a new worktree and start a task there. */
        START_SUB_BRANCH,
        DEACTIVATE_AND_CLOSE,
        DONE_WITH_TASK,
        /** Resume, or Start when there is no conversation to resume into. */
        RESUME,
        RESUME_IN_OWN_WINDOW,
        /** Check a pull request out into its worktree and open its review workbench, with no agent. */
        OPEN_IN_WORKBENCH,
        /** Check a conflicting pull request out and start a task that merges its base and resolves the conflicts. */
        RESOLVE_CONFLICTS,
        /** Review with the repository's remembered starting model. */
        QUICK_REVIEW,
        REVIEW,
        /** Start a review session whose first prompt is the harness's sphinx command. */
        REVIEW_IN_SPHINX,
        OPEN_IN_GITHUB,
        /** Open the Jira issue's page; only a Jira row offers it. */
        OPEN_IN_JIRA,
        /** The session's pull requests: one URL is a direct entry, several a submenu. Session rows only. */
        OPEN_PULL_REQUEST,
        /** The GitHub issues the session's tasks mirror: direct entry or submenu. Session rows only. */
        OPEN_GITHUB_TICKETS,
        /** The Jira tickets the session's tasks mirror: direct entry or submenu. Session rows only. */
        OPEN_JIRA_TICKETS,
        /** Open the Drydock task an issue row is a mirror of; only a mirrored issue offers it. */
        OPEN_MIRRORED_TASK,
        /** Reveal and select the linked row in the tree; only when the tree shows the target. */
        SHOW_TASK,
        /** Reveal and select the pull request a row works; only when the tree shows it. */
        SHOW_PULL_REQUEST,
        /** Reveal and select the session a row belongs to; only when the tree shows it. */
        SHOW_SESSION,
        IMPLEMENT,
        TRIAGE,
        /** Batch-triage every untriaged issue of the section; only an Issues node offers it. */
        TRIAGE_ALL,
        /** Pause a running batch triage of the section; the remainder stays resumable. */
        CANCEL_TRIAGE,
        /** Continue a paused batch triage of the section; only when one is stored. */
        RESUME_TRIAGE,
        /** Per-project settings: Jira, GitHub, workspace box (while opted in) and agent identity, one dialog. */
        REPOSITORY_SETTINGS,
        /** Add a user-defined Jira group (name + JQL) to the Issues section. */
        ADD_JIRA_GROUP,
        /** Rename a Jira group or change its JQL; only a group node offers it. */
        EDIT_JIRA_GROUP,
        /** Delete a Jira group, after confirmation; only a group node offers it. */
        DELETE_JIRA_GROUP,
        REFRESH,
        /** On the Sessions node: run Done on every done session of the project at once. */
        CLEANUP_DONE_SESSIONS,
        /** Show only this project in the tree, or every project again when it is focused already. */
        FOCUS_PROJECT,
        /** Raise the project's main checkout in its own IDE window, opening one when none shows it. */
        OPEN_MAIN_CHECKOUT,
        NEW_TASK_IN_MAIN,
        REMOVE_WORKTREE,
        REMOVE_STALE_WORKTREES,
        REMOVE_INACTIVE_WORKTREES,
        REMOVE_FROM_DRYDOCK,
        REMOVE_WORKTREES
    }

    /**
     * The non-pure answers of one menu build, computed once by
     * {@link #factsFor}. The session link lists name every page a session
     * row leads to — its pull requests, the GitHub issues and Jira tickets
     * its tasks mirror — each entry opening its own page, so a combined
     * "open everything" gesture is never needed; the three target rows are
     * the selection's resolved cross-row links (issues, sessions, pull
     * requests, tasks). An empty target keeps its jump item out, so the
     * menu never offers a reveal the tree cannot perform.
     *
     * <p>{@code remoteStop} says the selected task row runs nowhere in this
     * IDE but its box-bound agent is ALIVE in the box's tmux (the restart
     * scenario), so the idle menu offers the Stop next to its Resume — the
     * same lifecycle verdict every other surface's Stop follows. A row
     * without a box answers false before any read, so every local menu is
     * the pre-remote list.</p>
     */
    record MenuFacts(boolean transferAvailable, boolean jiraEnabled, boolean mirroredTask,
                     boolean remoteStop,
                     List<String> prUrls, List<String> githubUrls, List<String> jiraUrls,
                     DelayedTaskRow showTask, PullRequestRow showPullRequest, TaskRow showSession) {

        MenuFacts {
            prUrls = List.copyOf(prUrls == null ? List.of() : prUrls);
            githubUrls = List.copyOf(githubUrls == null ? List.of() : githubUrls);
            jiraUrls = List.copyOf(jiraUrls == null ? List.of() : jiraUrls);
        }

        /** The facts before any cross-row link existed: nothing resolved. */
        MenuFacts(boolean transferAvailable, boolean jiraEnabled) {
            this(transferAvailable, jiraEnabled, false);
        }

        /** The facts before the cross-row jumps existed: no mirrored task. */
        MenuFacts(boolean transferAvailable, boolean jiraEnabled, boolean mirroredTask) {
            this(transferAvailable, jiraEnabled, mirroredTask, false);
        }

        MenuFacts(boolean transferAvailable, boolean jiraEnabled, boolean mirroredTask,
                  boolean remoteStop) {
            this(transferAvailable, jiraEnabled, mirroredTask, remoteStop,
                    List.of(), List.of(), List.of(), null, null, null);
        }

        /** The facts with the session rows' link lists stated by the caller (tests). */
        MenuFacts(boolean transferAvailable, boolean jiraEnabled, boolean mirroredTask,
                  boolean remoteStop,
                  List<String> prUrls, List<String> githubUrls, List<String> jiraUrls) {
            this(transferAvailable, jiraEnabled, mirroredTask, remoteStop,
                    prUrls, githubUrls, jiraUrls, null, null, null);
        }

        /** The facts with the cross-row jump targets resolved; no session links. */
        MenuFacts(boolean transferAvailable, boolean jiraEnabled, boolean mirroredTask,
                  boolean remoteStop,
                  DelayedTaskRow showTask, PullRequestRow showPullRequest, TaskRow showSession) {
            this(transferAvailable, jiraEnabled, mirroredTask, remoteStop,
                    List.of(), List.of(), List.of(), showTask, showPullRequest, showSession);
        }
    }

    private TaskMenu() {
    }

    /**
     * Pure: the applicable menu sections for a selection, never an empty
     * section; {@code List.of()} when nothing applies, so no empty group or
     * separator can be shown. A multi-selection acts on the whole selection,
     * never on just the lead row — and a mixed selection offers nothing
     * rather than acting on its removable subset.
     */
    static List<List<Item>> sections(TaskNode lead, List<TaskNode> selection, MenuFacts facts) {
        List<TaskNode> selected = selection == null ? List.of() : selection;
        if (selected.size() > 1) {
            return TaskActions.removable(selected).isEmpty()
                    ? List.of()
                    : List.of(List.of(Item.REMOVE_WORKTREES));
        }
        TaskNode node = selected.isEmpty() ? lead : selected.get(0);
        return switch (node) {
            case null -> List.of();
            case TaskRow row -> row.running() ? running(row, facts) : idle(row, facts);
            case PullRequestRow pullRequest -> nonEmpty(List.of(
                    section(facts.showTask() != null ? Item.SHOW_TASK : null,
                            facts.showSession() != null ? Item.SHOW_SESSION : null),
                    section(Item.OPEN_IN_WORKBENCH,
                            pullRequest.pullRequest().conflicting() ? Item.RESOLVE_CONFLICTS : null),
                    // Start a task on the PR's branch: its checkout, then the
                    // New task dialog fixed to that worktree.
                    List.of(Item.NEW_TASK),
                    section(urlOf(node).isPresent() ? Item.OPEN_IN_GITHUB : null)));
            case IssueRow ignored -> nonEmpty(List.of(
                    section(facts.showTask() != null ? Item.SHOW_TASK : null,
                            facts.mirroredTask() ? Item.OPEN_MIRRORED_TASK : null),
                    List.of(Item.IMPLEMENT, Item.TRIAGE),
                    section(urlOf(node).isPresent() ? Item.OPEN_IN_GITHUB : null, Item.REPOSITORY_SETTINGS)));
            case JiraIssueRow ignored -> nonEmpty(List.of(
                    section(facts.showTask() != null ? Item.SHOW_TASK : null,
                            facts.mirroredTask() ? Item.OPEN_MIRRORED_TASK : null),
                    List.of(Item.IMPLEMENT, Item.TRIAGE),
                    List.of(Item.OPEN_IN_JIRA, Item.REPOSITORY_SETTINGS)));
            case JiraGroupRow ignored -> nonEmpty(List.of(
                    List.of(Item.EDIT_JIRA_GROUP, Item.DELETE_JIRA_GROUP),
                    List.of(Item.REFRESH)));
            case GitHubNode ignored -> List.of(
                    List.of(Item.TRIAGE_ALL, Item.CANCEL_TRIAGE, Item.RESUME_TRIAGE),
                    List.of(Item.REFRESH, Item.REPOSITORY_SETTINGS));
            // The Jira node is where its groups are added; its refresh loads
            // every group it shows.
            case JiraNode ignored -> List.of(
                    List.of(Item.TRIAGE_ALL, Item.CANCEL_TRIAGE, Item.RESUME_TRIAGE),
                    List.of(Item.REFRESH, Item.ADD_JIRA_GROUP, Item.REPOSITORY_SETTINGS));
            case Ghost ghost -> List.of(List.of(Item.REPOSITORY_SETTINGS));
            case IdleWorktree idleWorktree -> nonEmpty(List.of(
                    List.of(Item.NEW_TASK, Item.QUICK_REVIEW, Item.REVIEW, Item.REVIEW_IN_SPHINX),
                    // Locked or open worktrees cannot be removed from here.
                    section(TaskActions.removable(idleWorktree).isEmpty() ? null : Item.REMOVE_WORKTREE)));
            // A held branch's New Task is fixed to its worktree (the same
            // NEW_TASK entry a worktree row offers); a free one creates its
            // worktree first. Either way a sub-branch can be cut from it,
            // and a held branch with a shown session offers the reveal.
            case TaskNode.BranchRow branch -> nonEmpty(List.of(
                    branch.held()
                            ? List.of(Item.NEW_TASK)
                            : List.of(Item.NEW_TASK_ON_BRANCH, Item.NEW_TASK_ON_BRANCH_OWN_WINDOW),
                    List.of(Item.START_SUB_BRANCH),
                    facts.showSession() != null ? List.of(Item.SHOW_SESSION) : List.of()));
            case StaleWorktrees ignored -> List.of(List.of(Item.REMOVE_STALE_WORKTREES));
            case WorktreeGroup ignored -> List.of(List.of(Item.NEW_TASK));
            // The Sessions node is where the project's bulk cleanup lives.
            case Section section when section.kind() == SectionKind.SESSIONS ->
                    List.of(List.of(Item.CLEANUP_DONE_SESSIONS));
            case Section section when section.kind() == SectionKind.ISSUES
                    || section.kind() == SectionKind.PULL_REQUESTS ->
                    section.kind() == SectionKind.ISSUES
                            ? nonEmpty(List.of(section(Item.REFRESH,
                                    facts.jiraEnabled() ? Item.ADD_JIRA_GROUP : null,
                                    Item.REPOSITORY_SETTINGS)))
                            : List.of(List.of(Item.REFRESH, Item.REPOSITORY_SETTINGS));
            case ProjectRow project -> project(project, facts);
            default -> List.of();
        };
    }

    /**
     * The session links section's items: the project's main-checkout window
     * (its action hides itself when the project is remote or its folder is
     * gone), then the session's pull requests, GitHub issues and Jira
     * tickets, each present only when the facts name at least one page.
     */
    private static List<Item> sessionLinks(MenuFacts facts) {
        return section(Item.OPEN_MAIN_CHECKOUT,
                facts.prUrls().isEmpty() ? null : Item.OPEN_PULL_REQUEST,
                facts.githubUrls().isEmpty() ? null : Item.OPEN_GITHUB_TICKETS,
                facts.jiraUrls().isEmpty() ? null : Item.OPEN_JIRA_TICKETS);
    }

    /**
     * The babysitting jumps, in the links section: the other side of the
     * row's engagement. Pure over the carried row.
     */
    private static Item[] babysitJumps(TaskRow row) {
        return new Item[] {
                BabysitActions.showsWatched(row) ? Item.SHOW_WATCHED : null,
                BabysitActions.showsBabysitter(row) ? Item.SHOW_BABYSITTER : null};
    }

    /** The babysitting section: start one, or stop the one the row takes part in. Pure over the carried row. */
    private static List<Item> babysitting(TaskRow row) {
        return section(BabysitActions.offersStart(row) ? Item.BABYSIT_PR : null,
                BabysitActions.stoppable(row) ? Item.STOP_BABYSITTING : null);
    }

    /** The links section: the tree's reveals, then the babysitting jumps. */
    private static List<Item> links(TaskRow row, MenuFacts facts) {
        Item[] jumps = babysitJumps(row);
        return section(facts.showPullRequest() != null ? Item.SHOW_PULL_REQUEST : null,
                facts.showTask() != null ? Item.SHOW_TASK : null, jumps[0], jumps[1]);
    }

    /** A running task: its tree links, then switch — here or to its own window — work on it, manage it, clean up last. */
    private static List<List<Item>> running(TaskRow row, MenuFacts facts) {
        return nonEmpty(List.of(
                links(row, facts),
                section(Item.SWITCH_TO_TASK, Item.SWITCH_TO_TASK_IN_OWN_WINDOW),
                section(Item.DEACTIVATE,
                        TaskActions.reviewsChanges(row) ? Item.REVIEW_CHANGES : null,
                        facts.transferAvailable() ? Item.TRANSFER_TO : null),
                List.of(Item.RENAME, Item.ASSOCIATE_PR, Item.FLAG, Item.NEW_TASK),
                babysitting(row),
                sessionLinks(facts),
                section(TaskActions.closesOwnWindow(row) ? Item.DEACTIVATE_AND_CLOSE : null,
                        Item.DONE_WITH_TASK)));
    }

    /**
     * An idle task: the honest lifecycle variants — the remote Stop first
     * when the facts say the row's box-bound agent is ALIVE somewhere no
     * execution here runs, so a detached remote row never strands its
     * live agent behind a Resume-only menu — then management, cleanup
     * last.
     */
    private static List<List<Item>> idle(TaskRow row, MenuFacts facts) {
        return nonEmpty(List.of(
                links(row, facts),
                List.of(Item.SWITCH_TO_TASK, Item.SWITCH_TO_TASK_IN_OWN_WINDOW),
                section(facts.remoteStop() ? Item.DEACTIVATE : null,
                        Item.RESUME, TaskActions.resumesElsewhere(row) ? Item.RESUME_IN_OWN_WINDOW : null),
                List.of(Item.RENAME, Item.ASSOCIATE_PR, Item.FLAG, Item.NEW_TASK),
                babysitting(row),
                sessionLinks(facts),
                List.of(Item.DONE_WITH_TASK)));
    }

    /** A project: new tasks, its main checkout's window, the listings' refresh and settings, then cleanup. */
    private static List<List<Item>> project(ProjectRow row, MenuFacts facts) {
        return nonEmpty(List.of(
                List.of(Item.NEW_TASK, Item.NEW_TASK_IN_MAIN),
                List.of(Item.OPEN_MAIN_CHECKOUT, Item.FOCUS_PROJECT),
                section(Item.REFRESH, Item.REPOSITORY_SETTINGS,
                        facts.jiraEnabled() ? Item.ADD_JIRA_GROUP : null),
                section(TaskActions.removable(row).isEmpty() ? null : Item.REMOVE_INACTIVE_WORKTREES,
                        Item.REMOVE_FROM_DRYDOCK)));
    }

    /** The sections that have items; an inapplicable conditional never leaves a hole. */
    private static List<List<Item>> nonEmpty(List<List<Item>> sections) {
        return sections.stream().filter(section -> !section.isEmpty()).toList();
    }

    /** One section's present items, in order; an inapplicable conditional passes {@code null}. */
    private static List<Item> section(Item... items) {
        List<Item> present = new ArrayList<>();
        for (Item item : items) {
            if (item != null) {
                present.add(item);
            }
        }
        return List.copyOf(present);
    }

    /** The GitHub URL of an issue or pull request row, when it has one. Pure. */
    static Optional<String> urlOf(TaskNode node) {
        return switch (node) {
            case IssueRow issue -> issue.issue().url();
            // A Jira row's URL is Jira's, opened by its own action.
            case JiraIssueRow ignored -> Optional.empty();
            case PullRequestRow pullRequest -> pullRequest.pullRequest().url();
            case null, default -> Optional.empty();
        };
    }

    /**
     * Any thread: the non-pure answers a menu build needs, computed once here
     * instead of per action. Transfer eligibility reads the registry's O(1)
     * probes, the transfer table, and the provider registry — memory-only,
     * but not pure, so {@link #sections} takes the answer as
     * {@link MenuFacts} instead. {@code jiraEnabled} is the selected
     * project's connection flag as the caller read it; whether to offer
     * adding a group hinges on it (the repository settings entry is always
     * offered). {@code mirroredTask} asks the task service's cached snapshot
     * whether a task mirrors the selected issue row, memory only.
     * {@code remoteStop} asks the shared lifecycle derivation
     * ({@link TaskActions#remoteStopOffered}) whether the selected task row's
     * box-bound agent is ALIVE with no execution here running it — a
     * memory-only read of the registry and the liveness map, so the flag-off
     * and local-row builds answer false before anything is read.
     */
    static MenuFacts factsFor(TaskNode lead, boolean jiraEnabled) {
        return factsFor(lead, jiraEnabled, List.of(), TaskTreePanel.TaskLayer.EMPTY, null);
    }

    /**
     * Same, with the cross-row links resolved: {@code displayed} is the
     * tree's current node list and {@code watched} the task service's
     * cached snapshot, both memory-only. Every target resolves over the
     * DISPLAYED nodes, so a link the current view or filter hides offers
     * no jump rather than a jump that reveals nothing.
     */
    static MenuFacts factsFor(TaskNode lead, boolean jiraEnabled, List<TaskNode> displayed,
                              TaskTreePanel.TaskLayer tasks, WatchedSnapshot watched) {
        DelayedTaskRow showTask = null;
        PullRequestRow showPullRequest = null;
        TaskRow showSession = null;
        switch (lead) {
            case TaskRow row -> {
                showPullRequest = TaskLinks.pullRequestOf(row, displayed).orElse(null);
                showTask = TaskLinks.taskOfSession(row, displayed, tasks).orElse(null);
            }
            case PullRequestRow pullRequest -> {
                showTask = TaskLinks.taskOfBranch(pullRequest.pullRequest().headRefName(), watched)
                        .flatMap(document -> delayedRowOf(document, displayed, tasks)).orElse(null);
                showSession = TaskLinks.sessionOf(pullRequest, displayed).orElse(null);
            }
            case TaskNode.BranchRow branch ->
                showSession = TaskLinks.sessionOf(branch, displayed).orElse(null);
            case IssueRow issue -> showTask = TaskLinks.taskOf(issue, displayed, tasks).orElse(null);
            case JiraIssueRow jira -> showTask = TaskLinks.taskOf(jira, displayed, tasks).orElse(null);
            default -> {
            }
        }
        TaskRow taskRow = lead instanceof TaskRow row ? row : null;
        List<String> sessionMirrors = taskRow == null ? List.of()
                : TaskMirroring.mirrorsOfSession(taskRow.session().id().toString());
        return new MenuFacts(taskRow != null
                && SessionTransfer.available(taskRow.session(), taskRow.activity()), jiraEnabled,
                issueUrlOf(lead).flatMap(TaskMirroring::mirroredTask).isPresent(),
                taskRow != null && TaskActions.remoteStopOffered(taskRow),
                taskRow == null ? List.of() : prUrlsOf(taskRow),
                taskRow == null ? List.of() : githubIssuesOf(sessionMirrors),
                taskRow == null ? List.of() : jiraTicketsOf(sessionMirrors),
                showTask, showPullRequest, showSession);
    }

    /** The displayed delayed-task row of a task document, by id; empty when the tree shows none. */
    private static Optional<DelayedTaskRow> delayedRowOf(TaskDocument document,
                                                         List<TaskNode> displayed,
                                                         TaskTreePanel.TaskLayer tasks) {
        return TaskLinks.taskOfId(document.id(), displayed, tasks);
    }

    /**
     * Any thread: the GitHub page the selected issue or pull request row
     * leads to, its own address. Session rows no longer offer the combined
     * entry; their links are split per kind (see {@link #prUrlsOf}).
     */
    static List<String> githubUrlsOf(TaskNode node) {
        return switch (node) {
            case IssueRow issue -> issue.issue().url().stream().toList();
            case PullRequestRow pullRequest -> pullRequest.pullRequest().url().stream().toList();
            case null, default -> List.of();
        };
    }

    /** Any thread: the Jira page the selected Jira issue row leads to, its own address. */
    static List<String> jiraUrlsOf(TaskNode node) {
        return switch (node) {
            case JiraIssueRow jira -> List.of(jira.issue().url());
            case null, default -> List.of();
        };
    }

    /**
     * The session's pull-request pages, target first, then the working
     * branch's own PR when it is a different one — GitHub allows several
     * open pull requests from one head branch, and an explicitly associated
     * target need not be the branch's discovered PR. Deduplicated by pull
     * request number, so the same PR under two spellings is one entry.
     */
    static List<String> prUrlsOf(TaskRow task) {
        List<String> urls = new ArrayList<>();
        Set<Integer> numbers = new HashSet<>();
        task.session().targetPullRequest().map(PullRequestTarget::url)
                .ifPresent(url -> addPullRequest(urls, numbers, url));
        task.branchPullRequest().flatMap(BranchPullRequest::url)
                .ifPresent(url -> addPullRequest(urls, numbers, url));
        return List.copyOf(urls);
    }

    private static void addPullRequest(List<String> urls, Set<Integer> numbers, String url) {
        Integer number = pullRequestNumber(url).orElse(null);
        if (number != null ? !numbers.add(number) : urls.contains(url)) {
            return;
        }
        urls.add(url);
    }

    /** The pull request number in a {@code /pull/<n>} page address, empty for anything else. */
    static Optional<Integer> pullRequestNumber(String url) {
        if (url == null) {
            return Optional.empty();
        }
        Matcher number = PULL_REQUEST_PAGE.matcher(url);
        return number.find() ? Optional.of(Integer.parseInt(number.group(1))) : Optional.empty();
    }

    /** The GitHub issue pages of the session's task mirrors, in mirror order. */
    static List<String> githubIssuesOf(List<String> sessionMirrors) {
        return sessionMirrors.stream().filter(TaskMenu::isGitHub).toList();
    }

    /** The Jira ticket pages of the session's task mirrors, in mirror order. */
    static List<String> jiraTicketsOf(List<String> sessionMirrors) {
        return sessionMirrors.stream().filter(url ->
                TaskMirror.kindOf(url).filter(TaskMirror.Kind.JIRA::equals).isPresent()).toList();
    }

    private static boolean isGitHub(String url) {
        return TaskMirror.kindOf(url).filter(TaskMirror.Kind.GITHUB::equals).isPresent();
    }

    /**
     * Any thread: the popup for a selection — {@link #sections} with a separator
     * only between the sections that have actions, so a conditional absence
     * never leaves a leading, trailing, or doubled separator. The map holds
     * the panel's action instances; an {@link Item} without one is skipped
     * rather than shown broken.
     */
    static DefaultActionGroup group(TaskNode lead, List<TaskNode> selection, Map<Item, AnAction> actions,
                                    boolean jiraEnabled) {
        return group(lead, selection, actions, jiraEnabled, List.of(), TaskTreePanel.TaskLayer.EMPTY, null);
    }

    /** Same popup, with the cross-row links resolved over the tree's current nodes. */
    static DefaultActionGroup group(TaskNode lead, List<TaskNode> selection, Map<Item, AnAction> actions,
                                    boolean jiraEnabled, List<TaskNode> displayed,
                                    TaskTreePanel.TaskLayer tasks, WatchedSnapshot watched) {
        MenuFacts facts = factsFor(lead, jiraEnabled, displayed, tasks, watched);
        DefaultActionGroup menu = new DefaultActionGroup();
        boolean separated = false;
        for (List<Item> section : sections(lead, selection, facts)) {
            List<AnAction> present = new ArrayList<>();
            for (Item item : section) {
                AnAction action = entryFor(item, actions, facts);
                if (action != null) {
                    present.add(action);
                }
            }
            if (present.isEmpty()) {
                continue;
            }
            if (separated) {
                menu.addSeparator();
            }
            menu.addAll(present);
            separated = true;
        }
        return menu;
    }

    /**
     * One menu entry: the panel's action for most items, but a submenu
     * built from the facts for a session-link item naming several pages —
     * one child per page, each opening its own, where a single combined
     * action would open a tab per link at once. One page keeps the direct
     * entry, whose own update names it (PR #9, GitHub Issue #12, Jira
     * PROJ-123). Pure over the given facts; the children are throwaway
     * actions a popup's lifetime owns.
     */
    private static AnAction entryFor(Item item, Map<Item, AnAction> actions, MenuFacts facts) {
        AnAction action = actions.get(item);
        if (action == null) {
            return null;
        }
        return switch (item) {
            case OPEN_PULL_REQUEST -> submenu(facts.prUrls(), "Pull Requests",
                    TaskMenu::pullRequestLabel, AllIcons.Vcs.Vendors.Github, action);
            case OPEN_GITHUB_TICKETS -> submenu(facts.githubUrls(), "GitHub Issues",
                    TaskMenu::githubIssueLabel, AllIcons.Vcs.Vendors.Github, action);
            case OPEN_JIRA_TICKETS -> submenu(facts.jiraUrls(), "Jira Tickets",
                    TaskMenu::jiraTicketLabel, TaskTreeRenderer.JIRA_ICON, action);
            default -> action;
        };
    }

    /** The submenu for a multi-page item; {@code null} falls back to the single-page direct entry. */
    private static AnAction submenu(List<String> urls, String title,
                                    java.util.function.UnaryOperator<String> label,
                                    javax.swing.Icon icon, AnAction single) {
        if (urls.size() < 2) {
            return single;
        }
        DefaultActionGroup group = new DefaultActionGroup(title, true);
        for (String url : urls) {
            String address = url;
            group.add(new DumbAwareAction(label.apply(address), address, icon) {
                @Override
                public void actionPerformed(@NotNull AnActionEvent e) {
                    BrowserUtil.browse(address);
                }
            });
        }
        return group;
    }

    /** {@code PR #9} for a pull-request page address; the address itself when it parses no number. */
    static String pullRequestLabel(String url) {
        return pullRequestNumber(url).map(number -> "PR #" + number).orElse(url);
    }

    /** {@code GitHub Issue #12} for an issue page address; the address itself when it parses no number. */
    static String githubIssueLabel(String url) {
        return "GitHub Issue " + TaskMirror.label(url);
    }

    /** {@code Jira PROJ-123} for a ticket's browse address; the address itself when it parses no key. */
    static String jiraTicketLabel(String url) {
        return "Jira " + TaskMirror.label(url);
    }

    /** The page of a GitHub or Jira issue row, the address a task mirror records. Pure. */
    static Optional<String> issueUrlOf(TaskNode node) {
        return switch (node) {
            case IssueRow issue -> issue.issue().url();
            case JiraIssueRow jira -> Optional.of(jira.issue().url());
            case null, default -> Optional.empty();
        };
    }
}
