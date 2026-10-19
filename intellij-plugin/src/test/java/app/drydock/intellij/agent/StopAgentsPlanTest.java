package app.drydock.intellij.agent;

import app.drydock.domain.SessionActivity;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void theCopyCountsAndNamesTheWindow() {
        assertEquals("Stop 1 working agent?", StopAgentsPlan.title(1));
        assertEquals("Stop 2 working agents?", StopAgentsPlan.title(2));
        assertEquals("Closing dd-scratch · add-a-shout stops it mid-task.",
                StopAgentsPlan.body(Optional.of("dd-scratch · add-a-shout"), 1));
        assertEquals("Quitting stops them mid-task.", StopAgentsPlan.body(Optional.empty(), 3));
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
        assertEquals("Stop & Close", StopAgentsPlan.primaryLabel(false));
        assertEquals("Stop & Quit", StopAgentsPlan.primaryLabel(true));
    }
}
