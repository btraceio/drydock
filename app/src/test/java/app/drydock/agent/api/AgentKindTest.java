package app.drydock.agent.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentKindTest {

    @Test
    void persistedNamesAreStableLowercase() {
        assertEquals("claude", AgentKind.CLAUDE.persistedName());
        assertEquals("codex", AgentKind.CODEX.persistedName());
        assertEquals("pi", AgentKind.PI.persistedName());
    }

    @Test
    void fromPersistedRoundTrips() {
        for (AgentKind kind : AgentKind.values()) {
            assertEquals(Optional.of(kind), AgentKind.fromPersisted(kind.persistedName()));
        }
    }

    @Test
    void fromPersistedRejectsUnknown() {
        assertTrue(AgentKind.fromPersisted("gemini").isEmpty());
        assertTrue(AgentKind.fromPersisted(null).isEmpty());
    }

    @Test
    void preferenceOrderIsClaudeCodexPi() {
        assertEquals(List.of(AgentKind.CLAUDE, AgentKind.CODEX, AgentKind.PI), AgentKind.preferenceOrder());
    }
}
