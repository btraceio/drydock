package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PullRequestReviewTest {

    private static Optional<AgentKind> select(Optional<AgentKind> defaultAgent, AgentKind... available) {
        return PullRequestReview.selectReviewAgent(defaultAgent, Set.of(available)::contains);
    }

    @Test
    void aNonTerminalDefaultIsTakenAsIs() {
        assertEquals(Optional.of(AgentKind.CODEX), select(Optional.of(AgentKind.CODEX), AgentKind.CODEX));
        // Available is not even consulted for a usable default.
        assertEquals(Optional.of(AgentKind.PI), select(Optional.of(AgentKind.PI)));
    }

    @Test
    void aTerminalDefaultFallsBackToTheFirstAvailableAgent() {
        assertEquals(Optional.of(AgentKind.CLAUDE),
                select(Optional.of(AgentKind.TERMINAL), AgentKind.CLAUDE, AgentKind.TERMINAL));
    }

    @Test
    void noDefaultPicksTheFirstAvailableAgentInPreferenceOrder() {
        assertEquals(Optional.of(AgentKind.CODEX), select(Optional.empty(), AgentKind.TERMINAL, AgentKind.CODEX));
        assertEquals(Optional.of(AgentKind.PI), select(Optional.empty(), AgentKind.TERMINAL, AgentKind.PI));
    }

    @Test
    void terminalIsNeverSelectedEvenWhenItIsTheOnlyAvailableKind() {
        assertEquals(Optional.empty(), select(Optional.empty(), AgentKind.TERMINAL));
        assertEquals(Optional.empty(), select(Optional.of(AgentKind.TERMINAL), AgentKind.TERMINAL));
    }

    @Test
    void nothingAvailableYieldsEmpty() {
        assertTrue(select(Optional.empty()).isEmpty());
    }
}
