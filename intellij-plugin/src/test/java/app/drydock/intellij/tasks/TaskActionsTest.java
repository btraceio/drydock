package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
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

    private static TaskNode.TaskRow row(boolean running, TaskPlacement placement) {
        ManagedAgentSession session = new ManagedAgentSession.Builder(ManagedSessionId.newId(), RepositoryId.newId(),
                "Task", AgentBinding.unlaunched(AgentKind.PI, SessionMode.WRITES),
                SessionWorkspace.inRepository(Path.of("/wt/task")), Instant.EPOCH).status(SessionStatus.INACTIVE).build();
        return new TaskNode.TaskRow(session, Optional.empty(), running, Optional.empty(), placement, false, false);
    }
}
