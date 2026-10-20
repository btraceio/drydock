package app.drydock.intellij.agent;

import app.drydock.intellij.agent.AgentConsoles.ShowDecision;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void aJumpTheExecutorRefusesIsUndone() {
        AtomicBoolean undone = new AtomicBoolean();
        Executor shutDown = task -> {
            throw new RejectedExecutionException("shut down");
        };

        AgentConsoles.runOrUndo(shutDown, () -> { }, () -> undone.set(true));

        assertTrue(undone.get());
    }

    @Test
    void aJumpTheExecutorTakesIsNotUndone() {
        AtomicBoolean undone = new AtomicBoolean();
        AtomicBoolean ran = new AtomicBoolean();

        AgentConsoles.runOrUndo(Runnable::run, () -> ran.set(true), () -> undone.set(true));

        assertTrue(ran.get());
        assertFalse(undone.get());
    }
}
