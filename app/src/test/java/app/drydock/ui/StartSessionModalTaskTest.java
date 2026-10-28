package app.drydock.ui;

import app.drydock.agent.api.AgentKind;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The TERMINAL task guard shared by the start-session modals: a plain shell
 * has no agent to hand a task to, and typed text would be executed by the
 * shell -- including text typed for another harness before the Terminal
 * toggle was clicked, which hiding the field does not clear.
 */
class StartSessionModalTaskTest {

    @Test
    void aTerminalSessionCarriesNoTask() {
        assertEquals(Optional.empty(), StartSessionModal.taskFor(AgentKind.TERMINAL, "rm -rf /"));
        assertEquals(Optional.empty(), StartSessionModal.taskFor(AgentKind.TERMINAL, "  "));
        assertEquals(Optional.empty(), StartSessionModal.taskFor(AgentKind.TERMINAL, null));
    }

    @Test
    void anAgentSessionCarriesTheStrippedTask() {
        assertEquals(Optional.of("review this"), StartSessionModal.taskFor(AgentKind.CLAUDE, "  review this  "));
        assertEquals(Optional.empty(), StartSessionModal.taskFor(AgentKind.CLAUDE, ""));
        assertEquals(Optional.empty(), StartSessionModal.taskFor(AgentKind.CLAUDE, null));
    }
}
