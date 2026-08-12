package app.drydock.agent.providers;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentCommandsTest {

    @Test
    void shellQuoteEscapesEmbeddedSingleQuotes() {
        assertEquals("'a'\\''b'", AgentCommands.shellQuote("a'b"));
    }

    @Test
    void envPrefixBuildsUnsetFlagsWithTrailingSpace() {
        assertEquals("env -u A -u B ", AgentCommands.envPrefix(List.of("A", "B")));
    }

    @Test
    void envPrefixOfEmptyListIsEmptyString() {
        assertEquals("", AgentCommands.envPrefix(List.of()));
    }

    @Test
    void envPrefixCombinesUnsetsAndAssignments() {
        assertEquals("env -u A TOKEN='v' ",
                AgentCommands.envPrefix(List.of("A"), Map.of("TOKEN", "v")));
    }

    @Test
    void envPrefixWithAssignmentsOnlyStillEmitsEnv() {
        assertEquals("env TOKEN='v' ", AgentCommands.envPrefix(List.of(), Map.of("TOKEN", "v")));
    }

    @Test
    void envPrefixOfBothEmptyIsEmptyString() {
        assertEquals("", AgentCommands.envPrefix(List.of(), Map.of()));
    }

    @Test
    void envPrefixQuotesAnAssignedValueSoItCannotBecomeShellSyntax() {
        assertEquals("env TOKEN='a'\\''b; rm -rf /' ",
                AgentCommands.envPrefix(List.of(), Map.of("TOKEN", "a'b; rm -rf /")));
    }
}
