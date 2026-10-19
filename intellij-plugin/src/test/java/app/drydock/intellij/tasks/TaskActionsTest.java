package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionWorkspace;
import app.drydock.intellij.tasks.TaskNode.IdleWorktree;
import app.drydock.intellij.tasks.TaskNode.ProjectRow;
import app.drydock.intellij.tasks.TaskNode.TaskRow;
import app.drydock.intellij.tasks.TaskNode.WorktreeGroup;
import app.drydock.projects.SharedProject;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskActionsTest {

    private static TaskRow task(boolean running) {
        ManagedAgentSession session = new ManagedAgentSession.Builder(ManagedSessionId.newId(), RepositoryId.newId(),
                "t", AgentBinding.unlaunched(AgentKind.CLAUDE, SessionMode.WRITES),
                SessionWorkspace.inRepository(Path.of("/w/x")), Instant.EPOCH).build();
        return new TaskRow(session, Optional.empty(), running, Optional.empty(), false, false);
    }

    private static ProjectRow project(List<TaskNode> children) {
        return new ProjectRow(SharedProject.local(RepositoryId.newId(), Path.of("/w/x"), "x", Instant.EPOCH),
                false, false, children);
    }

    @Test
    void countsOnlyRunningSessionsUnderThatProject() {
        ProjectRow row = project(List.of(task(true), task(false),
                new WorktreeGroup(Path.of("/wt/x"), "b", List.of(task(true), task(true))),
                new IdleWorktree(Path.of("/wt/y"), "c")));

        assertEquals(3, TaskActions.runningUnder(row));
    }

    @Test
    void zeroForAProjectWithNothingRunning() {
        assertEquals(0, TaskActions.runningUnder(project(List.of(task(false)))));
    }

    @Test
    void removeQuestionNamesTheAgentsThatKeepRunning() {
        assertEquals("Remove drydock from Drydock? 2 agents keep running.", TaskActions.removeQuestion("drydock", 2));
        assertEquals("Remove drydock from Drydock? 1 agent keeps running.", TaskActions.removeQuestion("drydock", 1));
    }
}
