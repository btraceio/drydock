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
     * a path but never a secret. There is an overload that takes a path
     * literal; there is deliberately none that takes a value, because a value
     * is where a credential would go.
     */
    @Test
    void theRenderedCommandNamesTheFileAndNeverTheSecretItHolds() {
        String rendered = AgentCommands.envPrefixFromFiles(
                List.of("A"), Map.of("TOKEN", Path.of("/s/tok")));

        assertTrue(rendered.contains("/s/tok"), rendered);
        assertTrue(rendered.contains("$(cat "), rendered);
    }

    @Test
    void literalPathIsQuotedSoASpaceSurvives() {
        String prefix = AgentCommands.envPrefix(
                List.of("PI_CODING_AGENT"),
                Map.of("DRYDOCK_MCP_CONFIG", Path.of("/Users/x/Library/Application Support/ClaudeProjectManager/mcp/s1.json")),
                Map.of());
        assertEquals("env -u PI_CODING_AGENT "
                + "DRYDOCK_MCP_CONFIG='/Users/x/Library/Application Support/ClaudeProjectManager/mcp/s1.json' ",
                prefix);
    }

    @Test
    void fromFilesStillReadsTheFileAtExecTime() {
        String prefix = AgentCommands.envPrefix(
                List.of(), Map.of(), Map.of("TOKEN", Path.of("/state/mcp/s1.token")));
        assertEquals("env TOKEN=\"$(cat '/state/mcp/s1.token')\" ", prefix);
    }

    @Test
    void allEmptyYieldsEmpty() {
        assertEquals("", AgentCommands.envPrefix(List.of(), Map.of(), Map.of()));
    }
}
