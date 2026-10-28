package app.drydock.intellij.agent;

import app.drydock.domain.SessionActivity;

import org.junit.jupiter.api.Test;

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
