package app.drydock.agent.providers;

import org.junit.jupiter.api.Test;

import java.util.List;

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
}
