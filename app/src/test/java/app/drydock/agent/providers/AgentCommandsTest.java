package app.drydock.agent.providers;

import app.drydock.testing.TestPaths;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCommandsTest {

    private static final Path TOK = TestPaths.abs("/s/tok");
    private static final Path MCP_CONFIG = TestPaths.abs(
            "/Users/x/Library/Application Support/ClaudeProjectManager/mcp/s1.json");
    private static final Path TOKEN_FILE = TestPaths.abs("/state/mcp/s1.token");

    /** Mirrors the single-quote escaping the product must apply. */
    private static String shellQuote(Path path) {
        return path.toString().replace("'", "'\\''");
    }

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
        assertEquals("env -u A TOKEN=\"$(cat '" + TOK + "')\" ",
                AgentCommands.envPrefixFromFiles(List.of("A"), Map.of("TOKEN", TOK)));
    }

    @Test
    void envPrefixWithAssignmentsOnlyStillEmitsEnv() {
        assertEquals("env TOKEN=\"$(cat '" + TOK + "')\" ",
                AgentCommands.envPrefixFromFiles(List.of(), Map.of("TOKEN", TOK)));
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
        Path quoted = TestPaths.abs("/s/a'b; rm -rf x");
        assertEquals("env TOKEN=\"$(cat '" + shellQuote(quoted) + "')\" ",
                AgentCommands.envPrefixFromFiles(List.of(), Map.of("TOKEN", quoted)));
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
                List.of("A"), Map.of("TOKEN", TOK));

        assertTrue(rendered.contains(TOK.toString()), rendered);
        assertTrue(rendered.contains("$(cat "), rendered);
    }

    @Test
    void literalPathIsQuotedSoASpaceSurvives() {
        String prefix = AgentCommands.envPrefix(
                List.of("PI_CODING_AGENT"),
                Map.of("DRYDOCK_MCP_CONFIG", MCP_CONFIG),
                Map.of());
        assertEquals("env -u PI_CODING_AGENT "
                + "DRYDOCK_MCP_CONFIG='" + MCP_CONFIG + "' ",
                prefix);
    }

    @Test
    void fromFilesStillReadsTheFileAtExecTime() {
        String prefix = AgentCommands.envPrefix(
                List.of(), Map.of(), Map.of("TOKEN", TOKEN_FILE));
        assertEquals("env TOKEN=\"$(cat '" + TOKEN_FILE + "')\" ", prefix);
    }

    @Test
    void allEmptyYieldsEmpty() {
        assertEquals("", AgentCommands.envPrefix(List.of(), Map.of(), Map.of()));
    }
}
