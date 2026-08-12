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

    private static final Optional<McpAccess> SOME_MCP = Optional.of(
            new McpAccess("http://127.0.0.1:51234/mcp", "tok-abc", Optional.empty()));

    @Test
    void takesItsMcpAccessOnTheCommandLine() {
        assertEquals(McpDelivery.COMMAND_LINE, provider().mcpDelivery());
    }

    @Test
    void createCarriesTheEndpointAndTokenAsConfigOverrides() {
        LaunchPlan plan = provider().buildCreateCommand(
                new CreateContext("Session 1", "ignored-uuid", Path.of("/repo"), Optional.empty(), SOME_MCP));

        assertTrue(plan.command().contains(
                "-c 'mcp_servers.drydock.url=\"http://127.0.0.1:51234/mcp\"'"), plan.command());
        assertTrue(plan.command().contains(
                "-c 'mcp_servers.drydock.http_headers={\"X-Drydock-Session-Token\"=\"tok-abc\"}'"),
                plan.command());
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
    void aTokenWithTomlMetacharactersIsEscapedNotInjected() {
        // The token is opaque to this provider; a quote or backslash in it must
        // stay inside the TOML string rather than ending it.
        Optional<McpAccess> nasty = Optional.of(
                new McpAccess("http://127.0.0.1:1/mcp", "a\"b\\c", Optional.empty()));

        String command = provider().buildCreateCommand(
                new CreateContext("s", "x", Path.of("/repo"), Optional.empty(), nasty)).command();

        assertTrue(command.contains("\\\"b\\\\c"), command);
    }

    @Test
    void aRemoteContextStaysUnsupportedEvenWithMcpAccess() {
        LaunchPlan plan = provider().buildCreateCommand(new CreateContext("s", "x", Path.of("/repo"),
                Optional.of(new SshRemote("host", "/remote/path")), SOME_MCP));

        assertFalse(plan.supported());
    }
}
