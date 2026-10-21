package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionWorkspace;
import app.drydock.git.GhCliService.OpenIssue;
import app.drydock.intellij.tasks.TaskNode.IdleWorktree;
import app.drydock.intellij.tasks.TaskNode.IssueRow;
import app.drydock.intellij.tasks.TaskNode.ProjectRow;
import app.drydock.intellij.tasks.TaskNode.TaskRow;
import app.drydock.projects.SharedProject;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskTreePanelTargetTest {

    private static final Path REGISTERED_ROOT = Path.of("/work/profiling-backend");
    private static final Path LINKED_WORKTREE = Path.of("/work/profiling-backend-fix");
    private static final Instant NOW = Instant.parse("2026-10-20T17:00:00Z");

    @Test
    void projectRowTargetsTheRegisteredProjectRatherThanTheWindowProject() {
        SharedProject registered = SharedProject.local(
                RepositoryId.newId(), REGISTERED_ROOT, "profiling-backend", NOW);
        ProjectRow row = new ProjectRow(registered, false, false, List.of(), 0);

        assertEquals(Optional.of(new TaskTreePanel.NewTaskTarget(REGISTERED_ROOT, Optional.empty())),
                TaskTreePanel.newTaskTarget(row, REGISTERED_ROOT));
    }

    @Test
    void idleWorktreeTargetsThatExactWorktree() {
        IdleWorktree row = new IdleWorktree(LINKED_WORKTREE, "fix-allocation-profile");
        NewTaskPlan.FixedWorktree fixed = new NewTaskPlan.FixedWorktree(
                LINKED_WORKTREE, "fix-allocation-profile");

        assertEquals(Optional.of(new TaskTreePanel.NewTaskTarget(REGISTERED_ROOT, Optional.of(fixed))),
                TaskTreePanel.newTaskTarget(row, REGISTERED_ROOT));
    }

    @Test
    void taskRowInALinkedWorktreeTargetsThatWorktreeToo() {
        ManagedAgentSession session = new ManagedAgentSession.Builder(
                ManagedSessionId.newId(), RepositoryId.newId(), "Fix allocation profile",
                AgentBinding.unlaunched(AgentKind.CLAUDE, SessionMode.WRITES),
                SessionWorkspace.inRepository(LINKED_WORKTREE), NOW)
                .build();
        TaskRow row = new TaskRow(session, Optional.empty(), false,
                Optional.of("fix-allocation-profile"), false, false);
        NewTaskPlan.FixedWorktree fixed = new NewTaskPlan.FixedWorktree(
                LINKED_WORKTREE, "fix-allocation-profile");

        assertEquals(Optional.of(new TaskTreePanel.NewTaskTarget(REGISTERED_ROOT, Optional.of(fixed))),
                TaskTreePanel.newTaskTarget(row, REGISTERED_ROOT));
    }

    @Test
    void taskInTheMainCheckoutKeepsTheNormalMainProjectChoices() {
        ManagedAgentSession session = new ManagedAgentSession.Builder(
                ManagedSessionId.newId(), RepositoryId.newId(), "Main checkout task",
                AgentBinding.unlaunched(AgentKind.CLAUDE, SessionMode.WRITES),
                SessionWorkspace.inRepository(REGISTERED_ROOT), NOW)
                .build();
        TaskRow row = new TaskRow(session, Optional.empty(), false,
                Optional.of("main"), false, false);

        assertEquals(Optional.of(new TaskTreePanel.NewTaskTarget(REGISTERED_ROOT, Optional.empty())),
                TaskTreePanel.newTaskTarget(row, REGISTERED_ROOT));
    }

    @Test
    void issueRowDoesNotOfferTheGenericNewTaskAction() {
        IssueRow row = new IssueRow(REGISTERED_ROOT, new OpenIssue(42, "Fix allocation profile",
                Optional.of("https://github.com/DataDog/profiling-backend/issues/42"), List.of(), List.of(),
                Optional.of(NOW)));

        assertEquals(Optional.empty(), TaskTreePanel.newTaskTarget(row, REGISTERED_ROOT));
    }
}
