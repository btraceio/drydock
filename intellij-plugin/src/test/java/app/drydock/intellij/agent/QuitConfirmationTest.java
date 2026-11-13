package app.drydock.intellij.agent;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PrLink;
import app.drydock.domain.PrState;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.domain.WorkspaceBoxBinding;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The quit veto: the user's answer decides, and Drydock failing never keeps the IDE from quitting. */
class QuitConfirmationTest {

    @Test
    void theUsersAnswerDecides() {
        assertTrue(new QuitConfirmation(() -> true).canExitApplication());
        assertFalse(new QuitConfirmation(() -> false).canExitApplication());
    }

    @Test
    void aFailingQuestionNeverBlocksTheQuit() {
        assertTrue(new QuitConfirmation(() -> {
            throw new IllegalStateException("catalog not loaded");
        }).canExitApplication());
    }

    @Test
    void theQuitQuestionCountsOnlyAgentsQuittingDeactivates() {
        // Quitting ends only a workspace-box session's ssh client; its agent
        // keeps running in the box-side tmux. The quit question — built in
        // AgentCloseConfirmation.confirmQuit through StopAgentsPlan.countedOnClose
        // — counts the agents quitting would deactivate: the local ones,
        // exactly as before.
        ManagedAgentSession local = session(ManagedSessionId.newId());
        ManagedAgentSession onBox = session(ManagedSessionId.newId())
                .toBuilder()
                .workspaceBox(Optional.of(new WorkspaceBoxBinding(
                        "drydock-profiling-rt-ai-plugins", "/home/user/profiling-rt-ai-plugins")))
                .build();
        assertEquals(List.of(local.id()),
                StopAgentsPlan.countedOnClose(List.of(onBox.id(), local.id()), List.of(onBox, local)));
        // A quit whose every working agent is on a box asks nothing.
        assertEquals(List.of(), StopAgentsPlan.countedOnClose(List.of(onBox.id()), List.of(onBox, local)));
    }

    /** A running session row, local by construction. */
    private static ManagedAgentSession session(ManagedSessionId id) {
        return new ManagedAgentSession(id, RepositoryId.of("11111111-2222-3333-4444-555555555555"),
                "session", new AgentBinding(AgentKind.CLAUDE, Optional.empty(), Optional.empty()),
                new SessionWorkspace(Path.of("/tmp/repo/wd"), Optional.empty(), true),
                SessionStatus.RUNNING, Instant.EPOCH, Instant.EPOCH, Optional.empty(),
                PrLink.of(PrState.NONE, Optional.empty()), false, Optional.empty());
    }
}
