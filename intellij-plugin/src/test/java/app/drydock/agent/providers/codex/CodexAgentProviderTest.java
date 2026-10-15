package app.drydock.agent.providers.codex;

import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.CreateContext;
import app.drydock.agent.api.LaunchPlan;
import app.drydock.agent.api.McpAccess;
import app.drydock.agent.api.McpDelivery;
import app.drydock.agent.api.ResumeContext;
import app.drydock.agent.api.SessionIdStrategy;
import app.drydock.agent.providers.codex.internal.CodexExecutableLocator;
import app.drydock.domain.SshRemote;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexAgentProviderTest {

    private CodexAgentProvider provider() {
        CodexAgentProvider p = new CodexAgentProvider(new CodexExecutableLocator(Path.of("/nonexistent/codex")));
        p.init(new AgentContext(Path.of("/tmp"), Path.of("/tmp/activity"), ForkJoinPool.commonPool()));
        return p;
    }

    @Test
    void identity() {
        CodexAgentProvider p = provider();
        assertEquals(AgentKind.CODEX, p.kind());
        assertEquals("Codex", p.displayName());
        assertEquals(SessionIdStrategy.DISCOVERED, p.idStrategy());
    }

    @Test
    void createCarriesNoIdAndNoSettings() {
        LaunchPlan plan = provider().buildCreateCommand(
                new CreateContext("Session 1", "ignored-uuid", Path.of("/repo"), Optional.empty(), Optional.empty()));
        assertTrue(plan.supported());
        assertFalse(plan.sessionIdUsed());
        assertTrue(plan.command().endsWith("codex"));   // env-scrub prefix (if any) + "codex"; no id, no --settings
    }

    @Test
    void resumeByIdWhenKnown() {
        LaunchPlan plan = provider().buildResumeCommand(
                new ResumeContext(Optional.of("019f9072-abc"), Optional.empty(), Path.of("/repo"), Optional.empty(), Optional.empty()));
        assertTrue(plan.command().endsWith("codex resume '019f9072-abc'"));
    }

    @Test
    void resumeUsesPickerWhenIdUnknown() {
        LaunchPlan plan = provider().buildResumeCommand(
                new ResumeContext(Optional.empty(), Optional.empty(), Path.of("/repo"), Optional.empty(), Optional.empty()));
        assertTrue(plan.command().endsWith("codex resume"));   // picker; never --last
    }

    @Test
    void remoteIsUnsupported() {
        // A remote CreateContext yields an unsupported plan (Codex declines remote).
        LaunchPlan plan = provider().buildCreateCommand(new CreateContext("s", "x", Path.of("/repo"),
                Optional.of(new SshRemote("host", "/remote/path")), Optional.empty()));
        assertFalse(plan.supported());
        assertFalse(provider().probeCapabilities().supportsRemote());
    }

    @Test
    void activityAndRemoteDeclinedButConversationsAndDiscoveryPresent() {
        CodexAgentProvider p = provider();
        assertTrue(p.activity().isEmpty());
        assertTrue(p.conversations().isPresent());
        assertTrue(p.idDiscovery().isPresent());
    }

    // ---- drydock's MCP tools, delivered on the command line ------------------

    private static final Path TOKEN_FILE = Path.of("/state/mcp/s.token");

    private static final Optional<McpAccess> SOME_MCP = Optional.of(
            new McpAccess("http://127.0.0.1:51234/mcp", "tok-abc", Optional.of(TOKEN_FILE)));

    @Test
    void takesItsMcpAccessOnTheCommandLine() {
        assertEquals(McpDelivery.COMMAND_LINE, provider().mcpDelivery());
    }

    @Test
    void createCarriesTheEndpointAsAConfigOverride() {
        LaunchPlan plan = provider().buildCreateCommand(
                new CreateContext("Session 1", "ignored-uuid", Path.of("/repo"), Optional.empty(), SOME_MCP));

        assertTrue(plan.command().contains(
                "-c 'mcp_servers.drydock.url=\"http://127.0.0.1:51234/mcp\"'"), plan.command());
        assertTrue(plan.command().contains(
                "-c 'mcp_servers.drydock.env_http_headers={\"X-Drydock-Session-Token\"=\"DRYDOCK_SESSION_TOKEN\"}'"),
                plan.command());
    }

    /**
     * The token must never appear in the command, in any form. On macOS
     * another user can read a process's argv via {@code ps} but not its
     * environment -- and the command is not just the agent's argv: libghostty
     * runs it under a long-lived {@code login} parent whose own argv keeps the
     * whole string for the life of the session. A live fork run found the
     * token still readable there 21 seconds after launch. So the command names
     * the file and the shell reads it at exec time.
     */
    @Test
    void theTokenNeverAppearsInTheCommandInAnyForm() {
        String command = provider().buildCreateCommand(
                new CreateContext("s", "x", Path.of("/repo"), Optional.empty(), SOME_MCP)).command();

        assertFalse(command.contains("tok-abc"), command);
        assertTrue(command.startsWith("env "), command);
        assertTrue(command.contains("DRYDOCK_SESSION_TOKEN=\"$(cat '/state/mcp/s.token')\""), command);
        assertFalse(command.contains("-c 'mcp_servers.drydock.http_headers"), command);
    }

    /**
     * The guard rather than an expected path: SessionManager always mints the
     * file when MCP is wired. If it ever did not, the session must launch
     * without drydock's tools rather than with the token inlined.
     */
    @Test
    void mcpAccessWithNoCredentialFileYieldsNoOverridesRatherThanAnInlinedToken() {
        Optional<McpAccess> fileless = Optional.of(
                new McpAccess("http://127.0.0.1:51234/mcp", "tok-abc", Optional.empty()));

        String command = provider().buildCreateCommand(
                new CreateContext("s", "x", Path.of("/repo"), Optional.empty(), fileless)).command();

        assertFalse(command.contains("tok-abc"), command);
        assertFalse(command.contains("mcp_servers"), command);
        assertTrue(command.endsWith("codex"), command);
    }

    @Test
    void theOverridesPrecedeTheSubcommandSoCodexParsesThemAsGlobalOptions() {
        LaunchPlan plan = provider().buildResumeCommand(
                new ResumeContext(Optional.of("019f9072-abc"), Optional.empty(), Path.of("/repo"),
                        Optional.empty(), SOME_MCP));

        assertTrue(plan.command().indexOf("-c 'mcp_servers") < plan.command().indexOf("resume"),
                plan.command());
        assertTrue(plan.command().endsWith("resume '019f9072-abc'"), plan.command());
    }

    @Test
    void resumeWithoutAnIdStillCarriesTheOverrides() {
        LaunchPlan plan = provider().buildResumeCommand(
                new ResumeContext(Optional.empty(), Optional.empty(), Path.of("/repo"),
                        Optional.empty(), SOME_MCP));

        assertTrue(plan.command().contains("mcp_servers.drydock.url"), plan.command());
        // Still the cwd-filtered picker, never --last -- the overrides sit
        // between "codex" and "resume", so this no longer ends "codex resume".
        assertTrue(plan.command().endsWith(" resume"), plan.command());
        assertFalse(plan.command().contains("--last"), plan.command());
    }

    @Test
    void noMcpAccessMeansNoOverridesAtAll() {
        LaunchPlan plan = provider().buildCreateCommand(
                new CreateContext("s", "x", Path.of("/repo"), Optional.empty(), Optional.empty()));

        assertFalse(plan.command().contains("mcp_servers"), plan.command());
        assertTrue(plan.command().endsWith("codex"), plan.command());
    }

    @Test
    void aTokenWithShellMetacharactersCannotInjectBecauseItIsNeverRendered() {
        Optional<McpAccess> nasty = Optional.of(
                new McpAccess("http://127.0.0.1:1/mcp", "a'b;rm -rf /", Optional.of(TOKEN_FILE)));

        String command = provider().buildCreateCommand(
                new CreateContext("s", "x", Path.of("/repo"), Optional.empty(), nasty)).command();

        assertFalse(command.contains("rm -rf"), command);
    }

    @Test
    void anEndpointWithTomlMetacharactersIsEscapedNotInjected() {
        // The URL still goes through TOML, so a quote in it must stay inside
        // the string rather than ending it.
        Optional<McpAccess> nasty = Optional.of(
                new McpAccess("http://h/\"x\\y", "tok", Optional.of(TOKEN_FILE)));

        String command = provider().buildCreateCommand(
                new CreateContext("s", "x", Path.of("/repo"), Optional.empty(), nasty)).command();

        assertTrue(command.contains("\\\"x\\\\y"), command);
    }

    @Test
    void aRemoteContextStaysUnsupportedEvenWithMcpAccess() {
        LaunchPlan plan = provider().buildCreateCommand(new CreateContext("s", "x", Path.of("/repo"),
                Optional.of(new SshRemote("host", "/remote/path")), SOME_MCP));

        assertFalse(plan.supported());
    }
}
