package app.drydock.agent.providers.claude;

import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.CreateContext;
import app.drydock.agent.api.ResumeContext;
import app.drydock.agent.providers.claude.internal.ClaudeExecutableLocator;
import app.drydock.domain.SshRemote;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code --mcp-config} half of {@link ClaudeAgentProvider}'s command
 * construction: the flag that makes a launched {@code claude} able to call
 * back into this app (see {@code app.drydock.mcp.McpServer}).
 *
 * <p>Driven through the real {@code buildCreateCommand}/{@code
 * buildResumeCommand} against a hand-written stub {@code claude} shell script
 * (plan section 22.1/22.2: real subprocesses, no mocks), because whether the
 * flag is emitted depends on what the installed binary's {@code --help}
 * advertises -- a provider-internal detail deliberately not exposed on the
 * SPI.</p>
 */
class ClaudeAgentProviderMcpFlagTest {

    private static final Optional<Path> NO_MCP = Optional.empty();
    private static final Optional<Path> SOME_MCP = Optional.of(Path.of("/base/mcp/abc.json"));

    /**
     * A stub whose {@code --help} advertises {@code --mcp-config} exactly when
     * {@code advertiseMcpConfig}; everything else is held constant so only the
     * one capability under test varies.
     */
    private ClaudeAgentProvider provider(Path dir, boolean advertiseMcpConfig) throws IOException {
        Path stub = dir.resolve("claude");
        Files.writeString(stub, """
                #!/bin/sh
                if [ "$1" = "--version" ]; then
                  echo "1.2.3 (Claude Code)"
                  exit 0
                fi
                if [ "$1" = "--help" ]; then
                  echo "Usage: claude [options]"
                  echo "  --resume [sessionId]  Resume a session"
                """
                + (advertiseMcpConfig ? "  echo \"  --mcp-config <file>   Load MCP servers\"\n" : "")
                + """
                  exit 0
                fi
                exit 1
                """);
        assertTrue(stub.toFile().setExecutable(true));
        ClaudeAgentProvider provider = new ClaudeAgentProvider(new ClaudeExecutableLocator(stub));
        provider.init(new AgentContext(dir, dir.resolve("activity"),
                Executors.newVirtualThreadPerTaskExecutor()));
        return provider;
    }

    private static CreateContext create(Optional<Path> mcpConfig) {
        return new CreateContext("my session", "sid-1", Path.of("/tmp"), Optional.empty(), mcpConfig);
    }

    /** Neither id nor name, so the resume builder takes its bare {@code --resume} branch. */
    private static ResumeContext resume(Optional<Path> mcpConfig) {
        return new ResumeContext(Optional.empty(), Optional.empty(), Path.of("/tmp"), Optional.empty(), mcpConfig);
    }

    @Test
    void createCommandCarriesTheMcpConfigFlag(@TempDir Path dir) throws IOException {
        String command = provider(dir, true).buildCreateCommand(create(SOME_MCP)).command();

        assertTrue(command.contains("--mcp-config '/base/mcp/abc.json'"), command);
    }

    @Test
    void resumeCommandCarriesTheMcpConfigFlagToo(@TempDir Path dir) throws IOException {
        String command = provider(dir, true).buildResumeCommand(resume(SOME_MCP)).command();

        assertTrue(command.contains("--mcp-config '/base/mcp/abc.json'"), command);
    }

    @Test
    void anUnsupportedFlagIsOmittedRatherThanFailingTheLaunch(@TempDir Path dir) throws IOException {
        String command = provider(dir, false).buildCreateCommand(create(SOME_MCP)).command();

        assertFalse(command.contains("--mcp-config"), command);
    }

    @Test
    void noConfigFileMeansNoFlag(@TempDir Path dir) throws IOException {
        String command = provider(dir, true).buildCreateCommand(create(NO_MCP)).command();

        assertFalse(command.contains("--mcp-config"), command);
    }

    /**
     * No {@code --strict-mcp-config}: that would suppress the user's own MCP
     * servers, and Drydock's tools add to their setup rather than replacing it.
     */
    @Test
    void theUsersOwnMcpServersAreNotSuppressed(@TempDir Path dir) throws IOException {
        ClaudeAgentProvider provider = provider(dir, true);

        assertFalse(provider.buildCreateCommand(create(SOME_MCP)).command().contains("--strict-mcp-config"));
        assertFalse(provider.buildResumeCommand(resume(SOME_MCP)).command().contains("--strict-mcp-config"));
    }

    @Test
    void aPathWithSpacesIsQuoted(@TempDir Path dir) throws IOException {
        Path spaced = Path.of("/Users/me/Application Support/drydock/mcp/abc.json");
        String command = provider(dir, true).buildCreateCommand(create(Optional.of(spaced))).command();

        assertTrue(command.contains("'/Users/me/Application Support/drydock/mcp/abc.json'"), command);
    }

    /**
     * Remote sessions get no MCP config: {@code claude} runs on the remote
     * host and cannot reach this machine's loopback address. Asserted even
     * though the caller already declines to mint one for a remote launch --
     * the builder's remote early-return is the second, independent guarantee.
     */
    @Test
    void remoteCommandsCarryNoLocalConfigPath(@TempDir Path dir) throws IOException {
        ClaudeAgentProvider provider = provider(dir, true);
        Optional<SshRemote> remote = Optional.of(new SshRemote("build-box", "/srv/repo"));

        String create = provider.buildCreateCommand(
                new CreateContext("my session", "sid-1", Path.of("/tmp"), remote, SOME_MCP)).command();
        String resume = provider.buildResumeCommand(
                new ResumeContext(Optional.empty(), Optional.empty(), Path.of("/tmp"), remote, SOME_MCP)).command();

        assertFalse(create.contains("--mcp-config"), create);
        assertFalse(create.contains("/base/mcp/abc.json"), create);
        assertFalse(resume.contains("--mcp-config"), resume);
        assertFalse(resume.contains("/base/mcp/abc.json"), resume);
    }
}
