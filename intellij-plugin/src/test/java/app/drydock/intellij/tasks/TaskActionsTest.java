package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.intellij.tasks.TaskNode.IdleWorktree;
import app.drydock.intellij.tasks.TaskNode.ListProblem;
import app.drydock.intellij.tasks.TaskNode.StaleWorktrees;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskActionsTest {

    @Test
    void removeQuestionNamesTheAgentsThatKeepRunning() {
        assertEquals("Remove drydock from Drydock? 2 agents keep running.", TaskActions.removeQuestion("drydock", 2));
        assertEquals("Remove drydock from Drydock? 1 agent keeps running.", TaskActions.removeQuestion("drydock", 1));
    }

    @Test
    void toolbarButtonsStayPutAndGreyOutContextMenusHide() {
        assertEquals(new TaskActions.Shown(true, false), TaskActions.shown(false, false), "toolbar, not applicable");
        assertEquals(new TaskActions.Shown(true, true), TaskActions.shown(false, true), "toolbar, applicable");
        assertEquals(new TaskActions.Shown(false, false), TaskActions.shown(true, false), "menu, not applicable");
        assertEquals(new TaskActions.Shown(true, true), TaskActions.shown(true, true), "menu, applicable");
    }

    @Test
    void stopAndCloseOnlyClosesTheAgentsOwnWindow() {
        assertTrue(TaskActions.closesOwnWindow(row(true, new TaskPlacement.ThisWindow())));
        assertTrue(TaskActions.closesOwnWindow(row(true, new TaskPlacement.OwnWindow())));
        // Hosted somewhere else, or nowhere: that window is not the agent's.
        assertFalse(TaskActions.closesOwnWindow(row(true, new TaskPlacement.Here())));
        assertFalse(TaskActions.closesOwnWindow(row(true, new TaskPlacement.OtherWindow("repo"))));
        assertFalse(TaskActions.closesOwnWindow(row(true, new TaskPlacement.NotShown())));
        assertFalse(TaskActions.closesOwnWindow(row(false, new TaskPlacement.Idle(true))));
    }

    @Test
    void resumingInItsOwnWindowIsOfferedOnlyForAnIdleTaskFromAnotherWorktree() {
        // Resume itself resumes in this window, like New task; its own
        // window is the explicit alternative, and only where it differs.
        assertTrue(TaskActions.resumesElsewhere(row(false, new TaskPlacement.Idle(false))));
        assertFalse(TaskActions.resumesElsewhere(row(false, new TaskPlacement.Idle(true))));
        assertFalse(TaskActions.resumesElsewhere(row(true, new TaskPlacement.NotShown())));
    }

    @Test
    void destructiveRemovalQuestionSaysWhatIsLostAndWhatIsKept() {
        assertEquals("Remove 3 stale worktrees? This permanently deletes their folders and discards any "
                        + "uncommitted and untracked changes. Branches are kept.",
                TaskActions.removalQuestion(3, true));
        assertEquals("Remove 1 inactive worktree? This permanently deletes its folder and discards any "
                        + "uncommitted and untracked changes. Branches are kept.",
                TaskActions.removalQuestion(1, false));
        assertEquals("Remove", TaskActions.removalButton(1));
        assertEquals("Remove 3", TaskActions.removalButton(3));
    }

    @Test
    void lockedAndOpenWorktreesAreNeverRemovalTargets() {
        IdleWorktree removable = new IdleWorktree(Path.of("/wt/old"), "old", Optional.empty(), false, false);
        IdleWorktree locked = new IdleWorktree(Path.of("/wt/held"), "held", Optional.empty(), true, false);
        IdleWorktree open = new IdleWorktree(Path.of("/wt/open"), "open", Optional.empty(), false, true);

        assertEquals(List.of(removable), TaskActions.removable(removable));
        assertEquals(List.of(), TaskActions.removable(locked));
        assertEquals(List.of(), TaskActions.removable(open));
    }

    @Test
    void multiSelectionRemovesEverySelectedWorktreeOnce() {
        IdleWorktree first = new IdleWorktree(Path.of("/wt/first"), "first", Optional.empty(), false, false);
        IdleWorktree second = new IdleWorktree(Path.of("/wt/second"), "second", Optional.empty(), false, false);

        assertEquals(List.of(first, second), TaskActions.removable(List.of(first, second)));
        assertEquals("Remove 2 inactive worktrees? This permanently deletes their folders and discards any "
                        + "uncommitted and untracked changes. Branches are kept.",
                TaskActions.removalQuestion(TaskActions.removable(List.of(first, second)).size(), false));
        assertEquals(List.of(first, second), TaskActions.removable(
                List.of(new StaleWorktrees(List.of(first, second)), first)),
                "selecting a group and one of its children must not remove the child twice");
    }

    @Test
    void mixedSelectionDoesNotSilentlyRemoveOnlyItsWorktreeRows() {
        IdleWorktree worktree = new IdleWorktree(Path.of("/wt/first"), "first", Optional.empty(), false, false);

        assertEquals(List.of(), TaskActions.removable(List.of(worktree, new ListProblem("broken"))));
    }

    private static TaskNode.TaskRow row(boolean running, TaskPlacement placement) {
        ManagedAgentSession session = new ManagedAgentSession.Builder(ManagedSessionId.newId(), RepositoryId.newId(),
                "Task", AgentBinding.unlaunched(AgentKind.PI, SessionMode.WRITES),
                SessionWorkspace.inRepository(Path.of("/wt/task")), Instant.EPOCH).status(SessionStatus.INACTIVE).build();
        return new TaskNode.TaskRow(session, Optional.empty(), running, Optional.empty(), placement, false, false);
    }
}
