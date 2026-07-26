package app.drydock.app;

import app.drydock.claude.ClaudeCapabilities;
import app.drydock.domain.ManagedClaudeSession;
import app.drydock.domain.SshRemote;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code --mcp-config} half of {@link SessionManager}'s command
 * construction: the flag that makes a launched {@code claude} able to call
 * back into this app (see {@code app.drydock.mcp.McpServer}).
 *
 * <p>Same package because {@code buildCreateCommand}/{@code
 * buildResumeCommand} are package-private statics; like {@link
 * SessionManagerTest} this needs no JavaFX toolkit, since nothing here
 * touches a terminal surface.</p>
 */
class SessionManagerMcpFlagTest {

    private static final Optional<Path> NO_SETTINGS = Optional.empty();
    private static final Optional<Path> NO_MCP = Optional.empty();

    /** Every flag on except {@code --mcp-config}, which the argument decides. */
    private static ClaudeCapabilities capabilities(boolean supportsMcpConfig) {
        return new ClaudeCapabilities(true, true, false, true, true, supportsMcpConfig, "1.0.0");
    }

    /**
     * A session with neither a claude id nor a name, so {@code
     * buildResumeCommand} takes its bare-{@code --resume} branch. Built
     * rather than passed as null: {@code buildResumeCommand} dereferences
     * its argument immediately.
     */
    private static ManagedClaudeSession session() {
        return SessionManagerTest.newSessionFixture();
    }

    @Test
    void createCommandCarriesTheMcpConfigFlag() {
        String command = SessionManager.buildCreateCommand(capabilities(true), "my session", "sid-1",
                Optional.of(Path.of("/base/hooks/settings.json")),
                Optional.of(Path.of("/base/mcp/abc.json")));

        assertTrue(command.contains("--mcp-config '/base/mcp/abc.json'"), command);
    }

    /** Both flags coexist: the activity badge and the tools are independent features. */
    @Test
    void createCommandKeepsTheActivitySettingsFlagAlongsideIt() {
        String command = SessionManager.buildCreateCommand(capabilities(true), "my session", "sid-1",
                Optional.of(Path.of("/base/hooks/settings.json")),
                Optional.of(Path.of("/base/mcp/abc.json")));

        assertTrue(command.contains("--settings '/base/hooks/settings.json'"), command);
    }

    @Test
    void resumeCommandCarriesTheMcpConfigFlagToo() {
        String command = SessionManager.buildResumeCommand(session(), capabilities(true),
                Optional.of(Path.of("/base/hooks/settings.json")),
                Optional.of(Path.of("/base/mcp/abc.json")));

        assertTrue(command.contains("--mcp-config '/base/mcp/abc.json'"), command);
    }

    @Test
    void anUnsupportedFlagIsOmittedRatherThanFailingTheLaunch() {
        String command = SessionManager.buildCreateCommand(capabilities(false), "my session", "sid-1",
                NO_SETTINGS, Optional.of(Path.of("/base/mcp/abc.json")));

        assertFalse(command.contains("--mcp-config"), command);
    }

    @Test
    void noConfigFileMeansNoFlag() {
        String command = SessionManager.buildCreateCommand(capabilities(true), "my session", "sid-1",
                NO_SETTINGS, NO_MCP);

        assertFalse(command.contains("--mcp-config"), command);
    }

    /**
     * No {@code --strict-mcp-config}: that would suppress the user's own MCP
     * servers, and Drydock's tools add to their setup rather than replacing it.
     */
    @Test
    void theUsersOwnMcpServersAreNotSuppressed() {
        String command = SessionManager.buildCreateCommand(capabilities(true), "my session", "sid-1",
                NO_SETTINGS, Optional.of(Path.of("/base/mcp/abc.json")));

        assertFalse(command.contains("--strict-mcp-config"), command);
    }

    @Test
    void aPathWithSpacesIsQuoted() {
        String command = SessionManager.buildCreateCommand(capabilities(true), "my session", "sid-1",
                NO_SETTINGS,
                Optional.of(Path.of("/Users/me/Application Support/drydock/mcp/abc.json")));

        assertTrue(command.contains("'/Users/me/Application Support/drydock/mcp/abc.json'"), command);
    }

    /**
     * Remote sessions get no MCP config: {@code claude} runs on the remote
     * host and cannot reach this machine's loopback address.
     */
    @Test
    void remoteCommandsCarryNoLocalConfigPath() {
        SshRemote remote = SessionManagerTest.newRemoteFixture();

        assertFalse(SessionManager.buildRemoteCreateCommand(remote).contains("--mcp-config"));
        assertFalse(SessionManager.buildRemoteResumeCommand(remote, session()).contains("--mcp-config"));
    }
}
