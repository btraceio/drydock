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
import app.drydock.git.GhCliService.OpenPullRequest;
import app.drydock.intellij.tasks.TaskNode.PullRequestRow;
import app.drydock.intellij.tasks.TaskNode.TaskRow;
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
 * I/O; the watched snapshot is built by hand.
 */
class TaskLinksTest {

    private static final Path ROOT = Path.of("/work/profiling-backend");
    private static final Instant NOW = Instant.parse("2026-10-20T17:00:00Z");
    private static final TaskId TASK_ID = TaskId.of("fix-alloc-k3f9");

    private static TaskRow session(String branch) {
        return session(branch, Optional.empty());
    }

    private static TaskRow session(String branch, Optional<BranchPullRequest> pullRequest) {
        ManagedAgentSession agent = new ManagedAgentSession.Builder(
                ManagedSessionId.newId(), RepositoryId.newId(), "Fix allocation profile",
                AgentBinding.unlaunched(AgentKind.CLAUDE, SessionMode.WRITES),
                SessionWorkspace.inRepository(ROOT), NOW)
                .build();
        return new TaskRow(agent, Optional.empty(), false, Optional.ofNullable(branch),
                new TaskPlacement.Here(), false, false, false, pullRequest);
    }

    private static PullRequestRow pullRequest(String head, int number) {
        return new PullRequestRow(ROOT, new OpenPullRequest(number, "Speed up the flaky suite",
                head, "main", false, Optional.of("reviewer"), Optional.of("https://github.com/x/y/pull/" + number)));
    }

    private static DelayedTaskRow task(List<String> storedSessions) {
        return new DelayedTaskRow(new TaskState.TaskView(TASK_ID, TaskState.Presentation.READY,
                List.of(), List.of()), "Fix allocation profile", RepositoryId.newId(),
                Optional.of(NOW), false, storedSessions);
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
        assertEquals(Optional.of(working),
                TaskLinks.sessionOf(pullRequest("pr-40", 40), List.of(other, working)));
    }

    @Test
    void aPullRequestWithNoSessionOnItsHeadLinksToNoSession() {
        assertTrue(TaskLinks.sessionOf(pullRequest("pr-40", 40),
                List.of(session("pr-41"))).isEmpty());
    }

    @Test
    void aSessionRowLinksToThePullRequestOfItsBranch() {
        TaskRow row = session("pr-40", Optional.of(
                new BranchPullRequest(40, app.drydock.git.GhCliService.PrInfo.PrLifecycle.OPEN, false, "pr-40", "Speed up", Optional.empty())));
        PullRequestRow shown = pullRequest("pr-40", 40);
        assertEquals(Optional.of(shown), TaskLinks.pullRequestOf(row, List.of(shown)));
    }

    @Test
    void aSessionWithoutAKnownPullRequestLinksToNone() {
        assertTrue(TaskLinks.pullRequestOf(session("pr-40"), List.of(pullRequest("pr-40", 40))).isEmpty());
    }

    @Test
    void aTaskRowLinksToThePullRequestWhoseHeadIsItsStackedBranch() {
        DelayedTaskRow row = task(List.of());
        PullRequestRow shown = pullRequest(StackBranches.name(TASK_ID), 40);
        assertEquals(Optional.of(shown), TaskLinks.pullRequestOf(row, List.of(shown)));
    }

    @Test
    void aTaskRowLinksToThePullRequestOfAShownStoredSessionWhenNoStackedPrExists() {
        TaskRow working = session("pr-40");
        DelayedTaskRow row = task(List.of(working.session().id().toString()));
        PullRequestRow shown = pullRequest("pr-40", 40);
        assertEquals(Optional.of(shown),
                TaskLinks.pullRequestOf(row, List.of(shown, working)));
    }

    @Test
    void theStackedBranchWinsOverASessionBranch() {
        TaskRow working = session("pr-40");
        DelayedTaskRow row = task(List.of(working.session().id().toString()));
        PullRequestRow stacked = pullRequest(StackBranches.name(TASK_ID), 40);
        PullRequestRow onSession = pullRequest("pr-40", 41);
        assertEquals(Optional.of(stacked),
                TaskLinks.pullRequestOf(row, List.of(onSession, working, stacked)));
    }

    @Test
    void aSessionRowLinksToTheFirstShownTaskThatNamesIt() {
        TaskRow row = session("pr-40");
        DelayedTaskRow assigned = task(List.of(row.session().id().toString()));
        DelayedTaskRow unassigned = task(List.of());
        assertEquals(Optional.of(assigned), TaskLinks.taskOfSession(row, List.of(),
                layer(List.of(unassigned), Map.of(row.session().id().toString(), List.of(assigned)))));
    }

    /** A task layer: the Tasks section's rows, then the rows attached per session. */
    private static TaskTreePanel.TaskLayer layer(List<DelayedTaskRow> section,
                                                 Map<String, List<DelayedTaskRow>> attached) {
        List<TasksSection> sections = section.isEmpty() ? List.of()
                : List.of(new TasksSection("Tasks", List.copyOf(section)));
        return new TaskTreePanel.TaskLayer(sections, attached);
    }

    @Test
    void aTaskRowLinksToItsFirstShownStoredSession() {
        TaskRow first = session("pr-40");
        TaskRow second = session("pr-41");
        DelayedTaskRow row = task(List.of(first.session().id().toString(),
                second.session().id().toString()));
        assertEquals(Optional.of(first),
                TaskLinks.sessionOf(row, List.of(second, first)));
    }

    @Test
    void aTaskWhoseSessionsAreNotShownLinksToNoSession() {
        assertTrue(TaskLinks.sessionOf(task(List.of("gone-session")), List.of(session("pr-40"))).isEmpty());
        assertTrue(TaskLinks.taskOfSession(session("pr-40"), List.of(),
                layer(List.of(task(List.of())), Map.of())).isEmpty());
    }

    @Test
    void linksResolveThroughNestedProjectRows() {
        TaskRow working = session("pr-40");
        TaskNode project = new TaskNode.ProjectRow(
                app.drydock.projects.SharedProject.local(RepositoryId.newId(), ROOT, "profiling-backend", NOW),
                false, false,
                List.of(new TaskNode.Section(TaskNode.SectionKind.PULL_REQUESTS,
                        List.of(pullRequest("pr-40", 40)))),
                List.of(), 0);
        assertEquals(Optional.of(working),
                TaskLinks.sessionOf(pullRequest("pr-40", 40), List.of(project, working)));
    }
}
