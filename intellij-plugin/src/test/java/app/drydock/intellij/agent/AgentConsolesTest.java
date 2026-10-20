package app.drydock.intellij.agent;

import app.drydock.intellij.agent.AgentConsoles.ShowDecision;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Where Resume, Start and Show land, given where the agent is shown now. */
class AgentConsolesTest {

    @Test
    void aSessionRunningInAnotherWindowRaisesThatWindow() {
        assertEquals(ShowDecision.RAISE_HOST, AgentConsoles.decide(true, Optional.of("B"), "A"));
    }

    @Test
    void aRunningSessionNoWindowShowsIsShownHere() {
        assertEquals(ShowDecision.SHOW_HERE, AgentConsoles.decide(true, Optional.empty(), "A"));
    }

    @Test
    void aSessionAlreadyHereStaysHere() {
        assertEquals(ShowDecision.SHOW_HERE, AgentConsoles.decide(true, Optional.of("A"), "A"));
    }

    @Test
    void aStoppedSessionStarts() {
        assertEquals(ShowDecision.START, AgentConsoles.decide(false, Optional.of("B"), "A"));
    }
}
