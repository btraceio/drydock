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
        assertEquals("terminal", AgentKind.TERMINAL.persistedName());
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
    void preferenceOrderIsClaudeCodexPiTerminal() {
        assertEquals(List.of(AgentKind.CLAUDE, AgentKind.CODEX, AgentKind.PI, AgentKind.TERMINAL),
                AgentKind.preferenceOrder());
    }

    /** TERMINAL is never the resolved default while any agent CLI is available. */
    @Test
    void terminalComesLast() {
        assertEquals(AgentKind.TERMINAL, AgentKind.preferenceOrder()
                .get(AgentKind.preferenceOrder().size() - 1));
    }
}
