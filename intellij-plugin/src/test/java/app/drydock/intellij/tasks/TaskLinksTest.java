package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionWorkspace;
import app.drydock.delayedtasks.TaskStoreWatcher;
import app.drydock.git.GhCliService.BranchPullRequest;
import app.drydock.git.GhCliService.OpenIssue;
import app.drydock.git.GhCliService.OpenPullRequest;
import app.drydock.git.GhCliService.PrInfo;
import app.drydock.git.JiraIssue;
import app.drydock.intellij.tasks.TaskNode.ProjectRow;
import app.drydock.intellij.tasks.TaskNode.PullRequestRow;
import app.drydock.intellij.tasks.TaskNode.Section;
import app.drydock.intellij.tasks.TaskNode.SectionKind;
import app.drydock.intellij.tasks.TaskNode.TaskRow;
import app.drydock.projects.SharedProject;
import app.drydock.tasks.StackBranches;
import app.drydock.tasks.TaskDocument;
import app.drydock.tasks.TaskId;
import app.drydock.tasks.TaskNode.DelayedTaskRow;
import app.drydock.tasks.TaskNode.TasksSection;
import app.drydock.tasks.TaskSnapshot;
import app.drydock.tasks.TaskState;
import app.drydock.tasks.TaskStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tree's cross-row links, resolved from displayed rows and a snapshot:
 * PR to its stacked-branch task and its session, task to its pull request
 * and its sessions, session to its task. Pure: no service, no plugin, no
 * I/O; the watched snapshot is built by hand. Fixtures model the shapes
 * the real tree builder produces: rows the links must see sit under a
 * {@code ProjectRow} -- a row outside any shown project is not indexed and
 * resolves strictly empty, and PR matching never crosses projects.
 */
class TaskLinksTest {

    private static final Path ROOT_A = Path.of("/work/profiling-backend");
    private static final Path ROOT_B = Path.of("/work/other-project");
    private static final RepositoryId REPO_A = RepositoryId.newId();
    private static final RepositoryId REPO_B = RepositoryId.newId();
    private static final Instant NOW = Instant.parse("2026-10-20T17:00:00Z");
    private static final TaskId TASK_ID = TaskId.of("fix-alloc-k3f9");

    private static TaskRow session(String branch) {
        return session(branch, Optional.empty());
    }

    private static TaskRow session(String branch, Optional<BranchPullRequest> pullRequest) {
        ManagedAgentSession agent = new ManagedAgentSession.Builder(
                ManagedSessionId.newId(), RepositoryId.newId(), "Fix allocation profile",
                AgentBinding.unlaunched(AgentKind.CLAUDE, SessionMode.WRITES),
                SessionWorkspace.inRepository(ROOT_A), NOW)
                .build();
        return new TaskRow(agent, Optional.empty(), false, Optional.ofNullable(branch),
                new TaskPlacement.Here(), false, false, false, pullRequest);
    }

    private static PullRequestRow pullRequest(Path root, String head, int number) {
        return new PullRequestRow(root, new OpenPullRequest(number, "Speed up the flaky suite",
                head, "main", false, Optional.of("reviewer"), Optional.of("https://github.com/x/y/pull/" + number)));
    }

    private static BranchPullRequest branchPr(int number, String head) {
        return new BranchPullRequest(number, PrInfo.PrLifecycle.OPEN, false, head, "Speed up",
                Optional.empty());
    }

    private static DelayedTaskRow task(TaskId id, RepositoryId repository, List<String> storedSessions) {
        return new DelayedTaskRow(new TaskState.TaskView(id, TaskState.Presentation.READY,
                List.of(), List.of()), "Fix allocation profile", repository,
                Optional.of(NOW), false, storedSessions);
    }

    private static DelayedTaskRow task(RepositoryId repository, List<String> storedSessions) {
        return task(TASK_ID, repository, storedSessions);
    }

    /** A shown registered project: its root, and the rows the tree nests under it. */
    private static TaskNode project(RepositoryId id, Path root, TaskNode... children) {
        return new ProjectRow(SharedProject.local(id, root, root.getFileName().toString(), NOW),
                false, false, List.of(children), List.of(), 0);
    }

    /** The project's pull-request listing section, non-empty as the model emits it. */
    private static TaskNode pullRequests(TaskNode... rows) {
        return new Section(SectionKind.PULL_REQUESTS, List.of(rows));
    }

    private static TaskDocument document(TaskId id) {
        return new TaskDocument(id, RepositoryId.newId(), "Fix allocation profile",
                TaskStatus.STARTED, app.drydock.tasks.DoneBy.AGENT, Optional.of(NOW),
                List.of(), List.of(), "", "body");
    }

    private static TaskStoreWatcher.WatchedSnapshot watched(TaskDocument... documents) {
        Map<TaskId, TaskDocument> byId = new java.util.LinkedHashMap<>();
        for (TaskDocument document : documents) {
            byId.put(document.id(), document);
        }
        return new TaskStoreWatcher.WatchedSnapshot(new TaskSnapshot(List.of(documents),
                List.of(documents[0].repository()), byId, Map.of(), List.of(), List.of()),
                List.of(), null);
    }

    /** A task layer: the Tasks section's rows, then the rows attached per session. */
    private static TaskTreePanel.TaskLayer layer(List<DelayedTaskRow> section,
                                                 Map<String, List<DelayedTaskRow>> attached) {
        List<TasksSection> sections = section.isEmpty() ? List.of()
                : List.of(new TasksSection("Tasks", List.copyOf(section)));
        return new TaskTreePanel.TaskLayer(sections, attached);
    }

    @Test
    void aPullRequestLinksToTheTaskWhoseStackedBranchIsItsHead() {
        assertEquals(TASK_ID,
                TaskLinks.taskOfBranch(StackBranches.name(TASK_ID), watched(document(TASK_ID)))
                        .orElseThrow().id());
    }

    @Test
    void aForeignDdBranchNamesNoTask() {
        assertTrue(TaskLinks.taskOfBranch("dd/no-such-task-k9x9", watched(document(TASK_ID))).isEmpty());
        assertTrue(TaskLinks.taskOfBranch("fix-allocation-profile", watched(document(TASK_ID))).isEmpty());
        assertTrue(TaskLinks.taskOfBranch(StackBranches.name(TASK_ID), null).isEmpty());
    }

    @Test
    void aPullRequestLinksToTheSessionOnItsHeadBranch() {
        TaskRow working = session("pr-40");
        TaskRow other = session("pr-41");
        TaskNode project = project(REPO_A, ROOT_A,
                pullRequests(pullRequest(ROOT_A, "pr-40", 40)), other, working);
        assertEquals(Optional.of(working),
                TaskLinks.sessionOf(pullRequest(ROOT_A, "pr-40", 40), List.of(project)));
    }

    @Test
    void aPullRequestWithNoSessionOnItsHeadLinksToNoSession() {
        TaskNode project = project(REPO_A, ROOT_A,
                pullRequests(pullRequest(ROOT_A, "pr-40", 40)), session("pr-41"));
        assertTrue(TaskLinks.sessionOf(pullRequest(ROOT_A, "pr-40", 40), List.of(project)).isEmpty());
    }

    @Test
    void aSessionRowLinksToThePullRequestOfItsBranch() {
        TaskRow row = session("pr-40", Optional.of(branchPr(40, "pr-40")));
        PullRequestRow shown = pullRequest(ROOT_A, "pr-40", 40);
        TaskNode project = project(REPO_A, ROOT_A, pullRequests(shown), row);
        assertEquals(Optional.of(shown), TaskLinks.pullRequestOf(row, List.of(project)));
    }

    @Test
    void aSessionWithoutAKnownPullRequestLinksToNone() {
        TaskNode project = project(REPO_A, ROOT_A,
                pullRequests(pullRequest(ROOT_A, "pr-40", 40)), session("pr-40"));
        assertTrue(TaskLinks.pullRequestOf(session("pr-40"), List.of(project)).isEmpty());
    }

    @Test
    void aTaskRowLinksToThePullRequestWhoseHeadIsItsStackedBranch() {
        DelayedTaskRow row = task(REPO_A, List.of());
        PullRequestRow shown = pullRequest(ROOT_A, StackBranches.name(TASK_ID), 40);
        TaskNode project = project(REPO_A, ROOT_A, pullRequests(shown));
        assertEquals(Optional.of(shown), TaskLinks.pullRequestOf(row, List.of(project)));
    }

    @Test
    void aTaskRowLinksToThePullRequestOfAShownStoredSessionWhenNoStackedPrExists() {
        TaskRow working = session("pr-40");
        DelayedTaskRow row = task(REPO_A, List.of(working.session().id().toString()));
        PullRequestRow shown = pullRequest(ROOT_A, "pr-40", 40);
        TaskNode project = project(REPO_A, ROOT_A, pullRequests(shown), working);
        assertEquals(Optional.of(shown), TaskLinks.pullRequestOf(row, List.of(project)));
    }

    @Test
    void theStackedBranchWinsOverASessionBranch() {
        TaskRow working = session("pr-40");
        DelayedTaskRow row = task(REPO_A, List.of(working.session().id().toString()));
        PullRequestRow stacked = pullRequest(ROOT_A, StackBranches.name(TASK_ID), 40);
        PullRequestRow onSession = pullRequest(ROOT_A, "pr-40", 41);
        TaskNode project = project(REPO_A, ROOT_A, pullRequests(onSession, stacked), working);
        assertEquals(Optional.of(stacked), TaskLinks.pullRequestOf(row, List.of(project)));
    }

    @Test
    void aSessionRowLinksToTheFirstShownTaskThatNamesIt() {
        TaskRow row = session("pr-40");
        DelayedTaskRow assigned = task(REPO_A, List.of(row.session().id().toString()));
        DelayedTaskRow unassigned = task(REPO_A, List.of());
        assertEquals(Optional.of(assigned), TaskLinks.taskOfSession(row, List.of(row),
                layer(List.of(unassigned), Map.of(row.session().id().toString(), List.of(assigned)))));
    }

    @Test
    void aTaskRowLinksToItsFirstShownStoredSession() {
        TaskRow first = session("pr-40");
        TaskRow second = session("pr-41");
        DelayedTaskRow row = task(REPO_A, List.of(first.session().id().toString(),
                second.session().id().toString()));
        assertEquals(Optional.of(first),
                TaskLinks.sessionOf(row, List.of(second, first)));
    }

    @Test
    void aTaskWhoseSessionsAreNotShownLinksToNoSession() {
        assertTrue(TaskLinks.sessionOf(task(REPO_A, List.of("gone-session")),
                List.of(session("pr-40"))).isEmpty());
        assertTrue(TaskLinks.taskOfSession(session("pr-40"), List.of(),
                layer(List.of(task(REPO_A, List.of())), Map.of())).isEmpty());
    }

    @Test
    void linksResolveThroughNestedProjectRows() {
        TaskRow working = session("pr-40");
        TaskNode project = project(REPO_A, ROOT_A,
                pullRequests(pullRequest(ROOT_A, "pr-40", 40)), working);
        assertEquals(Optional.of(working),
                TaskLinks.sessionOf(pullRequest(ROOT_A, "pr-40", 40), List.of(project)));
    }

    // PR matching never crosses projects: two shown projects may carry the
    // same number and the same branch name, and each lead row resolves only
    // its own project's rows.

    @Test
    void aPullRequestNumberNeverLinksAcrossProjects() {
        TaskRow taskInA = session("shared-branch", Optional.of(branchPr(12, "shared-branch")));
        TaskRow taskInB = session("shared-branch", Optional.of(branchPr(12, "shared-branch")));
        PullRequestRow prA = pullRequest(ROOT_A, "shared-branch", 12);
        PullRequestRow prB = pullRequest(ROOT_B, "shared-branch", 12);
        List<TaskNode> nodes = List.of(
                project(REPO_A, ROOT_A, pullRequests(prA), taskInA),
                project(REPO_B, ROOT_B, pullRequests(prB), taskInB));
        assertEquals(Optional.of(prA), TaskLinks.pullRequestOf(taskInA, nodes));
        assertEquals(Optional.of(prB), TaskLinks.pullRequestOf(taskInB, nodes));
    }

    @Test
    void aTaskRowResolvesItsPullRequestWithinItsOwnProject() {
        TaskRow workingInB = session("shared-branch");
        DelayedTaskRow row = task(REPO_B, List.of(workingInB.session().id().toString()));
        PullRequestRow prA = pullRequest(ROOT_A, "shared-branch", 12);
        PullRequestRow prB = pullRequest(ROOT_B, "shared-branch", 13);
        List<TaskNode> nodes = List.of(
                project(REPO_A, ROOT_A, pullRequests(prA)),
                project(REPO_B, ROOT_B, pullRequests(prB), workingInB));
        assertEquals(Optional.of(prB), TaskLinks.pullRequestOf(row, nodes));
    }

    @Test
    void aPullRequestsSessionStaysWithinItsProject() {
        TaskRow sessionInA = session("shared-branch");
        TaskRow sessionInB = session("shared-branch");
        List<TaskNode> nodes = List.of(
                project(REPO_A, ROOT_A,
                        pullRequests(pullRequest(ROOT_A, "shared-branch", 12)), sessionInA),
                project(REPO_B, ROOT_B,
                        pullRequests(pullRequest(ROOT_B, "shared-branch", 12)), sessionInB));
        assertEquals(Optional.of(sessionInB),
                TaskLinks.sessionOf(pullRequest(ROOT_B, "shared-branch", 12), nodes));
        assertEquals(Optional.of(sessionInA),
                TaskLinks.sessionOf(pullRequest(ROOT_A, "shared-branch", 12), nodes));
    }

    // No global fallback: a row the tree shows outside any project is not
    // indexed and resolves strictly empty.

    @Test
    void aRowOutsideAnyShownProjectLinksToNothing() {
        TaskRow floating = session("pr-40", Optional.of(branchPr(40, "pr-40")));
        TaskNode project = project(REPO_A, ROOT_A, pullRequests(pullRequest(ROOT_A, "pr-40", 40)));
        assertTrue(TaskLinks.pullRequestOf(floating, List.of(project)).isEmpty());
        assertTrue(TaskLinks.pullRequestOf(task(REPO_B, List.of()), List.of(project)).isEmpty());
    }

    // Guard pins: a mutation of these guards is what the assertions kill.

    @Test
    void anIssueWithNoUrlLinksToNoTask() {
        TaskNode.IssueRow issue = new TaskNode.IssueRow(ROOT_A, new OpenIssue(7, "Flaky suite",
                Optional.empty(), List.of(), List.of(), Optional.of(NOW)));
        assertTrue(TaskLinks.taskOf(issue, List.of(),
                layer(List.of(task(REPO_A, List.of())), Map.of())).isEmpty());
    }

    @Test
    void aJiraIssueWithNoUrlLinksToNoTask() {
        // JiraIssue.url is a required String (never null); blank is the no-address shape.
        TaskNode.JiraIssueRow jira = new TaskNode.JiraIssueRow(ROOT_A,
                new JiraIssue("PROJ-7", "Flaky suite", "", List.of(), Optional.of(NOW)));
        assertTrue(TaskLinks.taskOf(jira, List.of(),
                layer(List.of(task(REPO_A, List.of())), Map.of())).isEmpty());
    }

    @Test
    void aNullTaskLayerResolvesToNoTask() {
        assertTrue(TaskLinks.taskOfId(TASK_ID, List.of(), null).isEmpty());
        assertTrue(TaskLinks.taskOfSession(session("pr-40"), List.of(), null).isEmpty());
    }

    @Test
    void aTaskResolvesByIdOnlyWhenShown() {
        DelayedTaskRow row = task(REPO_A, List.of());
        assertEquals(TASK_ID, TaskLinks.taskOfId(TASK_ID, List.of(),
                layer(List.of(row), Map.of())).orElseThrow().id());
        assertTrue(TaskLinks.taskOfId(TaskId.of("other-k4k2"), List.of(),
                layer(List.of(row), Map.of())).isEmpty());
        // The attached-rows half of the layer surfaces the row too.
        TaskRow shownSession = session("pr-40");
        assertEquals(TASK_ID, TaskLinks.taskOfId(TASK_ID, List.of(shownSession),
                layer(List.of(), Map.of(shownSession.session().id().toString(), List.of(row))))
                .orElseThrow().id());
    }

    // Display order matches the tree's mount: Tasks-section rows first
    // (the panel mounts the section at the root before the session rows),
    // then per displayed session row in display order.

    @Test
    void taskOfSessionPicksRowsInTheTreesDisplayOrder() {
        TaskRow sessionOne = session("pr-40");
        TaskRow sessionTwo = session("pr-41");
        String one = sessionOne.session().id().toString();
        String two = sessionTwo.session().id().toString();
        DelayedTaskRow sectionRow = task(TaskId.of("section-row-k1a1"), REPO_A, List.of(one));
        DelayedTaskRow attachedUnderOne = task(TaskId.of("attached-one-k2b2"), REPO_A, List.of(one));
        DelayedTaskRow attachedUnderTwo = task(TaskId.of("attached-two-k3c3"), REPO_A, List.of(two));
        // The layer map's own iteration order is unspecified (Map.copyOf):
        // the answer must come from the tree's display order, never from
        // iterating the map.
        TaskTreePanel.TaskLayer tasks = layer(List.of(sectionRow),
                Map.of(one, List.of(attachedUnderOne), two, List.of(attachedUnderTwo)));
        // The tree shows sessionTwo before sessionOne.
        List<TaskNode> nodes = List.of(sessionTwo, sessionOne);
        // The Tasks section precedes every attached row: it wins a session
        // both a section row and an attached row claim.
        assertEquals(Optional.of(sectionRow), TaskLinks.taskOfSession(sessionOne, nodes, tasks));
        // sessionTwo is claimed only by the row attached under sessionTwo --
        // picked by the displayed session rows' order, whatever the layer
        // map iterates first.
        assertEquals(Optional.of(attachedUnderTwo), TaskLinks.taskOfSession(sessionTwo, nodes, tasks));
    }

    @Test
    void anAttachedTaskWhoseSessionIsNotShownLinksToNoTask() {
        TaskRow shown = session("pr-40");
        DelayedTaskRow attachedToHidden = task(REPO_A, List.of("hidden-session"));
        assertTrue(TaskLinks.taskOfSession(shown, List.of(shown),
                layer(List.of(), Map.of("hidden-session", List.of(attachedToHidden)))).isEmpty());
    }
}
