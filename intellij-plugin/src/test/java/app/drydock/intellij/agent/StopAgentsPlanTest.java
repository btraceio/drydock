package app.drydock.intellij.agent;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PrLink;
import app.drydock.domain.PrState;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionActivity;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.domain.WorkspaceBoxBinding;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The close/quit confirmation's rules and copy, as the handoff states them. */
class StopAgentsPlanTest {

    @Test
    void onlyAnAgentKnownToBeAtItsPromptIsStoppedWithoutAsking() {
        assertFalse(StopAgentsPlan.isWorking(Optional.of(SessionActivity.IDLE)));
        assertTrue(StopAgentsPlan.isWorking(Optional.of(SessionActivity.BUSY)));
        assertTrue(StopAgentsPlan.isWorking(Optional.of(SessionActivity.NEEDS_ATTENTION)));
        // Unreadable activity is not known to be idle: stopping a busy agent
        // unasked costs its work, asking once too often costs a click.
        assertTrue(StopAgentsPlan.isWorking(Optional.of(SessionActivity.UNKNOWN)));
        assertTrue(StopAgentsPlan.isWorking(Optional.empty()));
    }

    @Test
    void stoppingOneSessionAsksOnlyForAnAgentThatMightBeMidTask() {
        // One session stopped from the tree, ribbon, tab header or popup:
        // an agent the user knows is waiting for them is not mid-task.
        assertTrue(StopAgentsPlan.confirmsSingleStop(Optional.of(SessionActivity.BUSY)));
        assertTrue(StopAgentsPlan.confirmsSingleStop(Optional.of(SessionActivity.UNKNOWN)));
        // Unreadable activity is not known to be at its prompt.
        assertTrue(StopAgentsPlan.confirmsSingleStop(Optional.empty()));
        assertFalse(StopAgentsPlan.confirmsSingleStop(Optional.of(SessionActivity.IDLE)));
        assertFalse(StopAgentsPlan.confirmsSingleStop(Optional.of(SessionActivity.NEEDS_ATTENTION)));
    }

    @Test
    void aWorkspaceBoxSessionIsAbsentFromEveryCount() {
        // Closing a window (either answer) and quitting the IDE end only a
        // workspace-box session's ssh client; its agent keeps running in the
        // box-side tmux. No question asks the user to agree to deactivate an
        // agent that is not deactivated, so it is absent from every count.
        ManagedAgentSession local = session(ManagedSessionId.newId());
        ManagedAgentSession onBox = session(ManagedSessionId.newId())
                .toBuilder()
                .workspaceBox(Optional.of(new WorkspaceBoxBinding(
                        "drydock-profiling-rt-ai-plugins", "/home/user/profiling-rt-ai-plugins")))
                .build();
        assertEquals(List.of(local.id()),
                StopAgentsPlan.countedOnClose(List.of(local.id(), onBox.id()), List.of(local, onBox)));
        // Also absent when only the box session runs: the question is not
        // asked at all.
        assertTrue(StopAgentsPlan.countedOnClose(List.of(onBox.id()), List.of(local, onBox)).isEmpty());
    }

    @Test
    void localRowsAreCountedExactlyAsBefore() {
        ManagedAgentSession first = session(ManagedSessionId.newId());
        ManagedAgentSession second = session(ManagedSessionId.newId());
        assertEquals(List.of(second.id(), first.id()),
                StopAgentsPlan.countedOnClose(List.of(second.id(), first.id()), List.of(first, second)));
        // A session not running is not counted, as before; a running id
        // without a row passes through, as the caller's row lookup drops it.
        ManagedSessionId unknown = ManagedSessionId.newId();
        assertEquals(List.of(first.id(), unknown),
                StopAgentsPlan.countedOnClose(List.of(first.id(), unknown), List.of(first, second)));
    }

    /** A running session row, local by construction. */
    private static ManagedAgentSession session(ManagedSessionId id) {
        return new ManagedAgentSession(id, RepositoryId.of("11111111-2222-3333-4444-555555555555"),
                "session", new AgentBinding(AgentKind.CLAUDE, Optional.empty(), Optional.empty()),
                new SessionWorkspace(Path.of("/tmp/repo/wd"), Optional.empty(), true),
                SessionStatus.RUNNING, Instant.EPOCH, Instant.EPOCH, Optional.empty(),
                PrLink.of(PrState.NONE, Optional.empty()), false, Optional.empty());
    }

    @Test
    void theQuitCopyCountsTheAgents() {
        assertEquals("Deactivate 1 working agent?", StopAgentsPlan.title(1));
        assertEquals("Deactivate 2 working agents?", StopAgentsPlan.title(2));
        assertEquals("Quitting deactivates it mid-task.", StopAgentsPlan.body(1));
        assertEquals("Quitting deactivates them mid-task.", StopAgentsPlan.body(3));
    }

    @Test
    void aQuitListsEachAgentWithItsWindow() {
        assertEquals("Add a shout method · Claude", StopAgentsPlan.row("Add a shout method", "Claude",
                Optional.empty()));
        assertEquals("Add a shout method · Claude — dd-scratch · add-a-shout", StopAgentsPlan.row(
                "Add a shout method", "Claude", Optional.of("dd-scratch · add-a-shout")));
    }

    @Test
    void theDefaultButtonNamesWhatItDoes() {
        assertEquals("Deactivate & Quit", StopAgentsPlan.primaryLabel());
    }

    @Test
    void primaryLabelForTreeStop() {
        assertEquals("Deactivate anyway", StopAgentsPlan.primaryLabelForTree(false));
    }

    @Test
    void primaryLabelForTreeClose() {
        assertEquals("Deactivate & Close", StopAgentsPlan.primaryLabelForTree(true));
    }

    @Test
    void treeBodySaysWhatStoppingKeeps() {
        assertEquals("Deactivating ends it mid-task. Its conversation can be resumed later.",
                StopAgentsPlan.treeBody(false));
        assertEquals("Deactivating ends it mid-task and closes its window. Open files are saved and the worktree "
                + "stays.", StopAgentsPlan.treeBody(true));
    }

    @Test
    void stoppingFromTheTreeRemembersItsOwnDontAsk() {
        // Ticking "Don't ask again" on one task's Stop must not silently
        // turn off the question a window close or a quit asks.
        assertNotEquals(StopAgentsPlan.dontAskKey(true), StopAgentsPlan.dontAskKey(false));
        assertEquals("drydock.stopWorkingAgents.dontAsk", StopAgentsPlan.dontAskKey(false),
                "the close/quit key keeps its stored name");
    }

    @Test
    void anAgentThatDoesNotStopIsReportedNotIgnored() {
        assertEquals("\"Add farewell\" has not deactivated yet. Try again in a moment.",
                StopAgentsPlan.notStoppedYet("Add farewell"));
    }
}
