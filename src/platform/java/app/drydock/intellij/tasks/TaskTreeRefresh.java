package app.drydock.intellij.tasks;

import app.drydock.intellij.tasks.TaskNode.AllPullRequestsRow;
import app.drydock.intellij.tasks.TaskNode.GitHubNode;
import app.drydock.intellij.tasks.TaskNode.JiraGroupRow;
import app.drydock.intellij.tasks.TaskNode.JiraNode;
import app.drydock.intellij.tasks.TaskNode.PrGroupRow;
import app.drydock.intellij.tasks.TaskNode.ProjectRow;
import app.drydock.intellij.tasks.TaskNode.Section;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure refresh dispatch for {@link TaskTreePanel}: decides which caches to
 * drop and which lazy listing intents to record from the event origin and
 * selected tree node.
 */
final class TaskTreeRefresh {

    private TaskTreeRefresh() {
    }

    /**
     * One refresh gesture, two scopes. From the toolbar — or the project
     * row's context menu, which names a whole project — it is the user
     * saying "show me now", so the cached GitHub listings (issues, pull
     * requests) are dropped too, and the delayed-task store is asked for a
     * fresh re-read (its refusal is its own balloon), not just the worktree
     * scan. From an issues or pull-requests node's own context menu it
     * re-reads only that project's listing(s): the GitHub node its GitHub
     * listing, the Jira node every group it shows, a Jira group or PR
     * subgroup its own query, the All PR folder its unfiltered listing.
     * The refresh records the fetch intent it needs, so a refresh of a
     * not-yet-loaded node loads it instead of staying unloaded. A node with
     * no root above it — or no listing of its own — fetches nothing rather
     * than silently broadening to a global fetch. Pure dispatch over
     * {@code target}'s effects; EDT.
     */
    static void dispatch(TaskTreePanel.RefreshTarget target, boolean fromContextMenu, TaskNode node, Path root) {
        if (!fromContextMenu) {
            target.invalidateAll();
            target.refreshTasks();
            target.requestRebuild(true);
            return;
        }
        // A node with no project above it has no listing to scope to; fetch
        // nothing rather than silently broadening to a global fetch.
        if (root == null) {
            return;
        }
        if (node instanceof ProjectRow) {
            // The project row's menu entry names a whole project: the
            // toolbar's scope.
            target.invalidateAll();
            target.refreshTasks();
            target.requestRebuild(true);
            return;
        }
        if (node instanceof JiraGroupRow group) {
            // A group's refresh re-queries its project's Jira groups and
            // records the group's fetch intent, so a group that is visible
            // but not yet fetched actually loads instead of staying unloaded.
            target.invalidateJiraIssues(root);
            target.requestJiraGroup(root, group.group().id());
            target.requestRebuild(false);
            return;
        }
        if (node instanceof GitHubNode) {
            target.invalidateIssues(root);
            target.requestGitHubIssues(root);
            target.requestRebuild(false);
            return;
        }
        if (node instanceof JiraNode) {
            target.invalidateJiraIssues(root);
            for (String groupId : jiraGroupIdsUnder(node)) {
                target.requestJiraGroup(root, groupId);
            }
            target.requestRebuild(false);
            return;
        }
        if (node instanceof PrGroupRow group) {
            target.invalidatePullRequests(root);
            target.requestPrGroup(root, group.group().id());
            target.requestRebuild(false);
            return;
        }
        if (node instanceof AllPullRequestsRow) {
            target.invalidatePullRequests(root);
            target.requestAllPullRequests(root);
            target.requestRebuild(false);
            return;
        }
        if (!(node instanceof Section section)) {
            // Ghosts and every non-listing row have nothing to re-fetch; a
            // stray dispatch must not turn into a global fetch.
            return;
        }
        switch (section.kind()) {
            case ISSUES -> {
                target.invalidateIssues(root);
                target.invalidateJiraIssues(root);
                // The section's GitHub node and every group it shows are
                // what "refresh" means here: fetch them, even if none of
                // them was ever opened.
                target.requestGitHubIssues(root);
                for (String groupId : jiraGroupIdsUnder(node)) {
                    target.requestJiraGroup(root, groupId);
                }
                target.requestRebuild(false);
            }
            case PULL_REQUESTS -> {
                target.invalidatePullRequests(root);
                target.requestPullRequests(root);
                List<String> prGroupIds = prGroupIdsUnder(node);
                if (!prGroupIds.isEmpty()) {
                    target.requestAllPullRequests(root);
                    for (String groupId : prGroupIds) {
                        target.requestPrGroup(root, groupId);
                    }
                }
                target.requestRebuild(false);
            }
            // Sessions/worktrees sections have no listing to scope to; the
            // menu never offers Refresh on them, and a stray dispatch must
            // not turn into a global fetch.
            default -> {
            }
        }
    }

    /** The ids of the Jira groups displayed under {@code node} (a section or the Jira node). */
    private static List<String> jiraGroupIdsUnder(TaskNode node) {
        List<String> ids = new ArrayList<>();
        collectJiraGroupIds(node, ids);
        return ids;
    }

    private static void collectJiraGroupIds(TaskNode node, List<String> ids) {
        switch (node) {
            case JiraGroupRow group -> ids.add(group.group().id());
            case Section section -> section.children().forEach(child -> collectJiraGroupIds(child, ids));
            case JiraNode jira -> jira.children().forEach(child -> collectJiraGroupIds(child, ids));
            default -> {
            }
        }
    }

    /** The ids of the PR subgroups displayed under {@code node} (the Pull Requests section). */
    private static List<String> prGroupIdsUnder(TaskNode node) {
        List<String> ids = new ArrayList<>();
        if (node instanceof Section section) {
            for (TaskNode child : section.children()) {
                if (child instanceof PrGroupRow group) {
                    ids.add(group.group().id());
                }
            }
        }
        return ids;
    }
}
