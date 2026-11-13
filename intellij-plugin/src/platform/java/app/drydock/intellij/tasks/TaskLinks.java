package app.drydock.intellij.tasks;

import app.drydock.delayedtasks.TaskStoreWatcher.WatchedSnapshot;
import app.drydock.domain.RepositoryId;
import app.drydock.tasks.StackBranches;
import app.drydock.tasks.TaskDocument;
import app.drydock.tasks.TaskId;

import java.nio.file.Path;
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
 * <li>PR &rarr; session: the session row of the PR's own project whose
 * current branch is the PR's head -- the same rule the session row's
 * pull-request badge uses.</li>
 * <li>task &rarr; PR: the PR of the task's own project whose head is the
 * task's stacked branch, or the branch of one of its shown stored
 * sessions; the stacked branch wins, so a task keeps its PR when its
 * sessions drift onto other work.</li>
 * <li>task &rarr; session, session &rarr; task: the stored-sessions list
 * -- the same membership the tree nests task rows by.</li>
 * <li>issue &rarr; task: the mirror chain ({@link TaskMirroring}), the
 * address a task records when it mirrors the issue.</li>
 * </ul>
 *
 * <p>Every answer is over the DISPLAYED nodes, so a jump action only
 * resolves when the tree can actually reveal the target: a row the current
 * view or filter hides offers no jump rather than a jump that does
 * nothing. PR matching never crosses projects: indexes are keyed by the
 * enclosing project's main root, so a number or branch name in one project
 * never links to another project's row.</p>
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
     * The first session row working a PR row's head branch, in the display
     * order of the PR row's own project -- the answer never crosses a
     * project boundary, even when another shown project has a session on a
     * same-named branch. Empty when no shown session of the PR's project is
     * on the PR's branch.
     */
    static Optional<TaskRow> sessionOf(PullRequestRow pullRequest, List<TaskNode> nodes) {
        String head = pullRequest.pullRequest().headRefName();
        return PrIndex.of(nodes).sessionsByRoot()
                .getOrDefault(pullRequest.mainRoot(), Map.of())
                .values().stream()
                .filter(row -> row.branch().map(head::equals).orElse(false))
                .findFirst();
    }

    /**
     * The PR row a task row's branch pull request is, matched by number
     * within the task row's own project (the number a PR listing's rows
     * carry). Empty when the branch has no known PR, the tree shows none in
     * that project, or the row sits outside any shown project.
     */
    static Optional<PullRequestRow> pullRequestOf(TaskRow task, List<TaskNode> nodes) {
        return pullRequestOf(task, PrIndex.of(nodes));
    }

    /** The number-matched PR of {@code task}'s own project, over a built index. */
    static Optional<PullRequestRow> pullRequestOf(TaskRow task, PrIndex index) {
        Path root = index.rootOfTaskRow().get(task);
        if (root == null || task.branchPullRequest().isEmpty()) {
            return Optional.empty();
        }
        Map<Integer, PullRequestRow> byNumber = index.prByNumber().getOrDefault(root, Map.of());
        return Optional.ofNullable(byNumber.get(task.branchPullRequest().get().number()));
    }

    /**
     * The PR row whose head is a task row's stacked branch, else the head
     * branch of one of its shown stored sessions -- both resolved within
     * the task's own project, never a PR row of another project that
     * happens to carry the same branch name or number. Empty when that
     * project shows no such PR, or the task's project is not shown.
     */
    static Optional<PullRequestRow> pullRequestOf(DelayedTaskRow task, List<TaskNode> nodes) {
        return pullRequestOf(task, PrIndex.of(nodes));
    }

    /** The stacked-branch-or-session-branch PR of {@code task}'s own project, over a built index. */
    static Optional<PullRequestRow> pullRequestOf(DelayedTaskRow task, PrIndex index) {
        Path root = index.rootOfRepo().get(task.repository());
        if (root == null) {
            return Optional.empty();
        }
        Map<String, PullRequestRow> byHead = index.prByHead().getOrDefault(root, Map.of());
        PullRequestRow stacked = byHead.get(StackBranches.name(task.id()));
        if (stacked != null) {
            return Optional.of(stacked);
        }
        Map<String, TaskRow> sessions = index.sessionsByRoot().getOrDefault(root, Map.of());
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
     * name a shown session row, in the tree's display order: the Tasks
     * section's rows first (the panel mounts the section at the root
     * before the session rows, in {@code TaskTreePanel.apply}), then the
     * rows attached under each displayed session row, in session display
     * order. Empty when no shown task claims the session.
     */
    static Optional<DelayedTaskRow> taskOfSession(TaskRow session, List<TaskNode> nodes,
                                                  TaskTreePanel.TaskLayer tasks) {
        String id = session.session().id().toString();
        return taskLayerRows(nodes, tasks).stream()
                .filter(row -> row.storedSessions().contains(id))
                .findFirst();
    }

    /**
     * The first shown session row a task row names in its stored sessions,
     * in display order. Empty when none of its sessions is shown. Session
     * ids are UUIDs, unique across projects, so this lookup stays global
     * over the displayed node list.
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
        return taskOfUrl(issue.issue().url().orElse(null), nodes, tasks);
    }

    /** Same chain for a Jira row: its page's address is what a mirror records. */
    static Optional<DelayedTaskRow> taskOf(JiraIssueRow issue, List<TaskNode> nodes,
                                           TaskTreePanel.TaskLayer tasks) {
        return taskOfUrl(issue.issue().url(), nodes, tasks);
    }

    private static Optional<DelayedTaskRow> taskOfUrl(String url, List<TaskNode> nodes,
                                                      TaskTreePanel.TaskLayer tasks) {
        // A blank address is no address: a Jira row's url is a required String,
        // and the mirror lookup reads the plugin service, so blank must short-circuit.
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        return TaskMirroring.mirroredTask(url)
                .flatMap(document -> taskOfId(document.id(), nodes, tasks));
    }

    /** The displayed task row of {@code id}; empty when the tree shows none. */
    static Optional<DelayedTaskRow> taskOfId(TaskId id, List<TaskNode> nodes,
                                             TaskTreePanel.TaskLayer tasks) {
        return taskLayerRows(nodes, tasks).stream()
                .filter(row -> row.id().equals(id))
                .findFirst();
    }

    /**
     * Every task row the tree's delayed-task layer shows, in the tree's
     * display order: the Tasks section's rows first -- the panel mounts the
     * section at the root before the session rows, in
     * {@code TaskTreePanel.apply} -- then, for each displayed session row in
     * display order, the rows the layer attaches under it. The attached map
     * is read per session id only: it is a {@code Map.copyOf}, whose
     * iteration order is unspecified, so it is never iterated for order.
     * A session row the tree does not display contributes none of its
     * attached rows -- every answer stays over the DISPLAYED nodes.
     */
    private static List<DelayedTaskRow> taskLayerRows(List<TaskNode> nodes,
                                                      TaskTreePanel.TaskLayer tasks) {
        if (tasks == null) {
            return List.of();
        }
        List<DelayedTaskRow> rows = new ArrayList<>();
        for (app.drydock.tasks.TaskNode.TasksSection section : tasks.sections()) {
            for (app.drydock.tasks.TaskNode child : section.children()) {
                if (child instanceof DelayedTaskRow task) {
                    rows.add(task);
                }
            }
        }
        for (TaskRow session : displayedTaskRows(nodes).values()) {
            rows.addAll(tasks.attachedBySession()
                    .getOrDefault(session.session().id().toString(), List.of()));
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

    /**
     * The tree's PR and session indexes, keyed by the enclosing project's
     * main root: a PR number or head branch in one project never links to
     * another project's row, and every lookup resolves within the lead
     * row's own project. Rows outside any shown {@code ProjectRow} are not
     * indexed and resolve strictly empty -- never a global fallback.
     *
     * <p>Built by one walk of the displayed node list; the first shown row
     * wins each slot, the same first-in-display-order rule the tree's own
     * rebuild applies. {@code rootOfRepo} bridges a delayed task row's
     * repository id to its enclosing project's main root, and
     * {@code rootOfTaskRow} carries each session row's enclosing
     * project.</p>
     */
    record PrIndex(Map<Path, Map<Integer, PullRequestRow>> prByNumber,
                   Map<Path, Map<String, PullRequestRow>> prByHead,
                   Map<Path, Map<String, TaskRow>> sessionsByRoot,
                   Map<TaskRow, Path> rootOfTaskRow,
                   Map<RepositoryId, Path> rootOfRepo) {

        /** The index of the displayed node list: one walk, first shown row wins. */
        static PrIndex of(List<TaskNode> nodes) {
            Indexer indexer = new Indexer();
            indexer.walk(nodes, null);
            return new PrIndex(indexer.prByNumber, indexer.prByHead, indexer.sessionsByRoot,
                    indexer.rootOfTaskRow, indexer.rootOfRepo);
        }

        /** The one-pass builder behind {@link #of}. */
        private static final class Indexer {
            private final Map<Path, Map<Integer, PullRequestRow>> prByNumber = new LinkedHashMap<>();
            private final Map<Path, Map<String, PullRequestRow>> prByHead = new LinkedHashMap<>();
            private final Map<Path, Map<String, TaskRow>> sessionsByRoot = new LinkedHashMap<>();
            private final Map<TaskRow, Path> rootOfTaskRow = new LinkedHashMap<>();
            private final Map<RepositoryId, Path> rootOfRepo = new LinkedHashMap<>();

            private void walk(List<TaskNode> nodes, Path root) {
                for (TaskNode node : nodes) {
                    switch (node) {
                        case TaskNode.ProjectRow project -> {
                            Path projectRoot = project.project().root();
                            rootOfRepo.putIfAbsent(project.project().id(), projectRoot);
                            walk(project.children(), projectRoot);
                        }
                        case PullRequestRow pullRequest when root != null ->
                                pullRequestRow(pullRequest, root);
                        case TaskRow task when root != null -> sessionRow(task, root);
                        case TaskNode.Section section -> walk(section.children(), root);
                        case TaskNode.WorktreeGroup group ->
                                group.tasks().forEach(task -> sessionRow(task, root));
                        case TaskNode.GitHubNode github -> walk(github.children(), root);
                        case TaskNode.JiraNode jira -> walk(jira.children(), root);
                        case TaskNode.JiraGroupRow group -> walk(group.children(), root);
                        default -> {
                        }
                    }
                }
            }

            private void pullRequestRow(PullRequestRow pullRequest, Path root) {
                prByNumber.computeIfAbsent(root, any -> new LinkedHashMap<>())
                        .putIfAbsent(pullRequest.pullRequest().number(), pullRequest);
                prByHead.computeIfAbsent(root, any -> new LinkedHashMap<>())
                        .putIfAbsent(pullRequest.pullRequest().headRefName(), pullRequest);
            }

            private void sessionRow(TaskRow task, Path root) {
                rootOfTaskRow.putIfAbsent(task, root);
                sessionsByRoot.computeIfAbsent(root, any -> new LinkedHashMap<>())
                        .putIfAbsent(task.session().id().toString(), task);
            }
        }
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
