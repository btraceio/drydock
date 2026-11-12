package app.drydock.intellij.tasks;

import app.drydock.delayedtasks.TaskStoreWatcher.WatchedSnapshot;
import app.drydock.git.GhCliService.BranchPullRequest;
import app.drydock.tasks.StackBranches;
import app.drydock.tasks.TaskDocument;
import app.drydock.tasks.TaskId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import app.drydock.tasks.TaskNode.DelayedTaskRow;
import app.drydock.intellij.tasks.TaskNode.IssueRow;
import app.drydock.intellij.tasks.TaskNode.JiraIssueRow;
import app.drydock.intellij.tasks.TaskNode.PullRequestRow;
import app.drydock.intellij.tasks.TaskNode.TaskRow;

/**
 * The tree's cross-row links (issues, sessions, pull requests, tasks),
 * resolved from what the tree already holds in memory: the built node
 * list and the task service's cached watched snapshot. No network call,
 * no file read, no git invocation -- a PR row knows its head branch, a
 * session row its branch and its branch's pull request, a task row its
 * stored sessions and stacked branch, and an issue row its mirror's
 * address; everything else is matching over those carried values.
 *
 * <p>The matching rules, one per direction:</p>
 *
 * <ul>
 * <li>PR &rarr; task: the head branch is the task's stacked branch
 * ({@code dd/<task-id>}, {@link StackBranches#taskOf}) and the snapshot
 * still holds that task -- a user-made {@code dd/} branch never names a
 * task the store does not know.</li>
 * <li>PR &rarr; session: the session row whose current branch is the PR's
 * head -- the same rule the session row's pull-request badge uses.</li>
 * <li>task &rarr; PR: the PR whose head is the task's stacked branch, or
 * the branch of one of its shown stored sessions; the stacked branch wins,
 * so a task keeps its PR when its sessions drift onto other work.</li>
 * <li>task &rarr; session, session &rarr; task: the stored-sessions list
 * -- the same membership the tree nests task rows by.</li>
 * <li>issue &rarr; task: the mirror chain ({@link TaskMirroring}), the
 * address a task records when it mirrors the issue.</li>
 * </ul>
 *
 * <p>Every answer is over the DISPLAYED nodes, so a jump action only
 * resolves when the tree can actually reveal the target: a row the current
 * view or filter hides offers no jump rather than a jump that does
 * nothing.</p>
 */
final class TaskLinks {

    private TaskLinks() {
    }

    /**
     * The task whose stacked branch a PR row's head is, from the service's
     * cached snapshot only -- memory, like the mirror badge. Empty when the
     * head is not a {@code dd/} branch of a task the snapshot knows.
     */
    static Optional<TaskDocument> taskOfBranch(String headRefName, WatchedSnapshot watched) {
        if (watched == null || headRefName == null) {
            return Optional.empty();
        }
        TaskId id = StackBranches.taskOf(headRefName);
        return id == null ? Optional.empty() : watched.snapshot().find(id);
    }

    /**
     * The first session row working a PR row's head branch, in display
     * order. Empty when no shown session is on the PR's branch.
     */
    static Optional<TaskRow> sessionOf(PullRequestRow pullRequest, List<TaskNode> nodes) {
        String head = pullRequest.pullRequest().headRefName();
        return displayedTaskRows(nodes).values().stream()
                .filter(row -> row.branch().map(head::equals).orElse(false))
                .findFirst();
    }

    /**
     * The PR row a task row's branch pull request is, matched by number
     * within the same project (the number a PR listing's rows carry).
     * Empty when the branch has no known PR or the tree shows none.
     */
    static Optional<PullRequestRow> pullRequestOf(TaskRow task, List<TaskNode> nodes) {
        Optional<BranchPullRequest> branchPr = task.branchPullRequest();
        if (branchPr.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(pullRequestByNumber(nodes).get(branchPr.get().number()));
    }

    /**
     * The PR row whose head is a task row's stacked branch, else the head
     * branch of one of its shown stored sessions. Empty when the tree
     * shows no such PR.
     */
    static Optional<PullRequestRow> pullRequestOf(DelayedTaskRow task, List<TaskNode> nodes) {
        Map<String, PullRequestRow> byHead = pullRequestByHead(nodes);
        PullRequestRow stacked = byHead.get(StackBranches.name(task.id()));
        if (stacked != null) {
            return Optional.of(stacked);
        }
        Map<String, TaskRow> sessions = displayedTaskRows(nodes);
        for (String session : task.storedSessions()) {
            TaskRow row = sessions.get(session);
            if (row == null) {
                continue;
            }
            PullRequestRow onBranch = row.branch().map(byHead::get).orElse(null);
            if (onBranch != null) {
                return Optional.of(onBranch);
            }
        }
        return Optional.empty();
    }

    /**
     * The first task row of the tree's task layer whose stored sessions
     * name a shown session row, in display order: the layer's attached
     * rows first (they sit under their session), then the Tasks section.
     * Empty when no shown task claims the session.
     */
    static Optional<DelayedTaskRow> taskOfSession(TaskRow session, List<TaskNode> nodes,
                                                  TaskTreePanel.TaskLayer tasks) {
        String id = session.session().id().toString();
        return taskLayerRows(tasks).stream()
                .filter(row -> row.storedSessions().contains(id))
                .findFirst();
    }

    /**
     * The first shown session row a task row names in its stored sessions,
     * in display order. Empty when none of its sessions is shown.
     */
    static Optional<TaskRow> sessionOf(DelayedTaskRow task, List<TaskNode> nodes) {
        Map<String, TaskRow> sessions = displayedTaskRows(nodes);
        for (String session : task.storedSessions()) {
            TaskRow row = sessions.get(session);
            if (row != null) {
                return Optional.of(row);
            }
        }
        return Optional.empty();
    }

    /**
     * The task row an issue row's mirror names, when the tree shows it:
     * the mirror chain's tree half, the reveal counterpart of
     * {@link TaskMirroring#openMirroredTask}. Empty when no task mirrors
     * the issue or the tree shows none.
     */
    static Optional<DelayedTaskRow> taskOf(IssueRow issue, List<TaskNode> nodes,
                                           TaskTreePanel.TaskLayer tasks) {
        return taskOfUrl(issue.issue().url().orElse(null), tasks);
    }

    /** Same chain for a Jira row: its page's address is what a mirror records. */
    static Optional<DelayedTaskRow> taskOf(JiraIssueRow issue, List<TaskNode> nodes,
                                           TaskTreePanel.TaskLayer tasks) {
        return taskOfUrl(issue.issue().url(), tasks);
    }

    private static Optional<DelayedTaskRow> taskOfUrl(String url, TaskTreePanel.TaskLayer tasks) {
        if (url == null) {
            return Optional.empty();
        }
        return TaskMirroring.mirroredTask(url)
                .flatMap(document -> taskOfId(document.id(), tasks));
    }

    /** The displayed task row of {@code id}; empty when the tree shows none. */
    static Optional<DelayedTaskRow> taskOfId(TaskId id, TaskTreePanel.TaskLayer tasks) {
        return taskLayerRows(tasks).stream()
                .filter(row -> row.id().equals(id))
                .findFirst();
    }

    /**
     * Every task row the tree's delayed-task layer shows, in display
     * order: the rows attached under session rows first (the layer keeps
     * them per session), then the Tasks section's own rows.
     */
    private static List<DelayedTaskRow> taskLayerRows(TaskTreePanel.TaskLayer tasks) {
        if (tasks == null) {
            return List.of();
        }
        List<DelayedTaskRow> rows = new ArrayList<>();
        for (List<DelayedTaskRow> attached : tasks.attachedBySession().values()) {
            rows.addAll(attached);
        }
        for (app.drydock.tasks.TaskNode.TasksSection section : tasks.sections()) {
            for (app.drydock.tasks.TaskNode child : section.children()) {
                if (child instanceof DelayedTaskRow task) {
                    rows.add(task);
                }
            }
        }
        return rows;
    }

    /** Every session row the tree shows, by session id string, in display order. */
    private static Map<String, TaskRow> displayedTaskRows(List<TaskNode> nodes) {
        Map<String, TaskRow> rows = new LinkedHashMap<>();
        collect(nodes, node -> {
            if (node instanceof TaskRow task) {
                rows.putIfAbsent(task.session().id().toString(), task);
            }
        });
        return rows;
    }

    /** Every PR row the tree shows, by its number, in display order. */
    private static Map<Integer, PullRequestRow> pullRequestByNumber(List<TaskNode> nodes) {
        Map<Integer, PullRequestRow> rows = new LinkedHashMap<>();
        collect(nodes, node -> {
            if (node instanceof PullRequestRow pullRequest) {
                rows.putIfAbsent(pullRequest.pullRequest().number(), pullRequest);
            }
        });
        return rows;
    }

    /** Every PR row the tree shows, by its head branch, in display order. */
    private static Map<String, PullRequestRow> pullRequestByHead(List<TaskNode> nodes) {
        Map<String, PullRequestRow> rows = new LinkedHashMap<>();
        collect(nodes, node -> {
            if (node instanceof PullRequestRow pullRequest) {
                rows.putIfAbsent(pullRequest.pullRequest().headRefName(), pullRequest);
            }
        });
        return rows;
    }

    /** Walks the node tree in display order, visiting every row once. */
    private static void collect(List<TaskNode> nodes, Consumer<TaskNode> visit) {
        for (TaskNode node : nodes) {
            visit.accept(node);
            switch (node) {
                case TaskNode.ProjectRow project -> collect(project.children(), visit);
                case TaskNode.Section section -> collect(section.children(), visit);
                case TaskNode.WorktreeGroup group -> group.tasks().forEach(visit);
                case TaskNode.GitHubNode github -> collect(github.children(), visit);
                case TaskNode.JiraNode jira -> collect(jira.children(), visit);
                case TaskNode.JiraGroupRow group -> collect(group.children(), visit);
                default -> {
                }
            }
        }
    }
}
