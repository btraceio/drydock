package app.drydock.agent.providers;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void envPrefixReadsAnAssignedValueFromItsFileRatherThanInliningIt() {
        assertEquals("env -u A TOKEN=\"$(cat '/s/tok')\" ",
                AgentCommands.envPrefixFromFiles(List.of("A"), Map.of("TOKEN", Path.of("/s/tok"))));
    }

    @Test
    void envPrefixWithAssignmentsOnlyStillEmitsEnv() {
        assertEquals("env TOKEN=\"$(cat '/s/tok')\" ",
                AgentCommands.envPrefixFromFiles(List.of(), Map.of("TOKEN", Path.of("/s/tok"))));
    }

    @Test
    void envPrefixOfBothEmptyIsEmptyString() {
        assertEquals("", AgentCommands.envPrefixFromFiles(List.of(), Map.of()));
    }

    /**
     * The path is interpolated into a command the shell will evaluate, so a
     * quote in it must stay inside the substitution rather than closing it.
     */
    @Test
    void envPrefixQuotesThePathSoItCannotBecomeShellSyntax() {
        assertEquals("env TOKEN=\"$(cat '/s/a'\\''b; rm -rf x')\" ",
                AgentCommands.envPrefixFromFiles(List.of(), Map.of("TOKEN", Path.of("/s/a'b; rm -rf x"))));
    }

    /**
     * The whole point of the file: the rendered command must be able to carry
     * a path but never a secret, so there is deliberately no overload that
     * takes a value.
     */
    @Test
    void theRenderedCommandNamesTheFileAndNeverTheSecretItHolds() {
        String rendered = AgentCommands.envPrefixFromFiles(
                List.of("A"), Map.of("TOKEN", Path.of("/s/tok")));

        assertTrue(rendered.contains("/s/tok"), rendered);
        assertTrue(rendered.contains("$(cat "), rendered);
    }
}
