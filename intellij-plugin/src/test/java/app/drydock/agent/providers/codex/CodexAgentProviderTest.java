package app.drydock.agent.providers.codex;

import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.CreateContext;
import app.drydock.agent.api.LaunchPlan;
import app.drydock.agent.api.McpAccess;
import app.drydock.agent.api.McpDelivery;
import app.drydock.agent.api.ResumeContext;
import app.drydock.agent.api.SessionIdStrategy;
import app.drydock.agent.api.StdioMcpServer;
import app.drydock.agent.providers.codex.internal.CodexExecutableLocator;
import app.drydock.domain.SshRemote;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
        assertTrue(plan.command().endsWith("codex --sandbox danger-full-access"),
                plan.command());   // env-scrub prefix (if any) + the explicit sandbox; no id, no --settings
    }

    @Test
    void createCarriesThePickedStartingModel() {
        LaunchPlan plan = provider().buildCreateCommand(new CreateContext("Session 1", "ignored-uuid",
                Path.of("/repo"), Optional.empty(), Optional.empty(), Optional.of("gpt-5.2-codex"), false, false,
                Optional.empty(), List.of()));
        assertTrue(plan.command().contains(" -m 'gpt-5.2-codex'"), plan.command());
    }

    @Test
    void createWithoutAModelOmitsTheModelFlag() {
        LaunchPlan plan = provider().buildCreateCommand(new CreateContext("Session 1", "ignored-uuid",
                Path.of("/repo"), Optional.empty(), Optional.empty(), Optional.empty(), false, false,
                Optional.empty(), List.of()));
        assertFalse(plan.command().contains(" -m "), plan.command());
    }

    @Test
    void modelSuggestionsAreEmpty() {
        // codex has no model-listing command and names no aliases; the picker
        // offers only remembered models and free text.
        assertTrue(provider().modelSuggestions().isEmpty());
    }

    @Test
    void aReadOnlySessionRunsInCodexSandbox() {
        LaunchPlan plan = provider().buildCreateCommand(
                new CreateContext("Session 1", "ignored-uuid", Path.of("/repo"), Optional.empty(),
                        Optional.empty(), false, true));
        assertTrue(plan.command().endsWith("codex --sandbox read-only"), plan.command());
        assertTrue(provider().enforcesReadOnly(),
                "the sandbox is a first-class CLI option, not a probed flag");
    }

    @Test
    void aReadOnlyResumeKeepsTheSandboxAndKeepsItGlobal() {
        // Before the subcommand, like the -c overrides: the option is parsed
        // as a global one, and codex resume rejects nothing there (verified
        // against the installed CLI in both forms).
        LaunchPlan plan = provider().buildResumeCommand(
                new ResumeContext(Optional.of("019f9072-abc"), Optional.empty(), Path.of("/repo"),
                        Optional.empty(), Optional.empty(), false, true));
        assertTrue(plan.command().endsWith("codex --sandbox read-only resume '019f9072-abc'"), plan.command());
    }

    @Test
    void aWritingSessionExplicitlyRunsWithFullAccess() {
        // Codex's default sandbox (the user config's sandbox_mode) seats the
        // agent under a Seatbelt profile without an mDNSResponder mach-lookup
        // rule, which breaks getaddrinfo for every tool that resolves through
        // the system resolver -- so drydock does not leave the sandbox choice
        // to the config.
        LaunchPlan plan = provider().buildCreateCommand(
                new CreateContext("Session 1", "ignored-uuid", Path.of("/repo"), Optional.empty(),
                        Optional.empty(), false, false));
        assertTrue(plan.command().endsWith("codex --sandbox danger-full-access"), plan.command());
    }

    @Test
    void resumeByIdWhenKnown() {
        LaunchPlan plan = provider().buildResumeCommand(
                new ResumeContext(Optional.of("019f9072-abc"), Optional.empty(), Path.of("/repo"), Optional.empty(), Optional.empty()));
        assertTrue(plan.command().endsWith(
                "codex --sandbox danger-full-access resume '019f9072-abc'"), plan.command());
    }

    @Test
    void resumeUsesPickerWhenIdUnknown() {
        LaunchPlan plan = provider().buildResumeCommand(
                new ResumeContext(Optional.empty(), Optional.empty(), Path.of("/repo"), Optional.empty(), Optional.empty()));
        assertTrue(plan.command().endsWith("codex --sandbox danger-full-access resume"));   // picker; never --last
    }

    @Test
    void transferSummaryUsesExecResumeWithTheExactConversation(@TempDir Path dir) throws Exception {
        Path executable = Files.writeString(dir.resolve("codex"), "#!/bin/sh\n");
        assertTrue(executable.toFile().setExecutable(true));
        CodexAgentProvider provider = new CodexAgentProvider(new CodexExecutableLocator(executable));
        provider.init(new AgentContext(dir, dir.resolve("activity"), ForkJoinPool.commonPool()));

        assertEquals(List.of("/usr/bin/env", "-u", "CODEX_SANDBOX", "-u",
                        "CODEX_SANDBOX_NETWORK_DISABLED", executable.toString(), "exec",
                        "--sandbox", "read-only", "resume", "conversation-42", "summarize"),
                provider.buildTransferSummaryCommand(dir, "conversation-42", "summarize").orElseThrow());
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
        assertTrue(command.endsWith("codex --sandbox danger-full-access"), command);
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
        assertTrue(plan.command().endsWith("codex --sandbox danger-full-access"), plan.command());
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

    @Test
    void theInitialPromptUsesCodexDocumentedPositionalPrompt() {
        String without = provider().buildCreateCommand(new CreateContext("s", "x", Path.of("/repo"),
                Optional.empty(), Optional.empty(), false, false)).command();
        String with = provider().buildCreateCommand(new CreateContext("s", "x", Path.of("/repo"),
                Optional.empty(), Optional.empty(), false, false, Optional.of("Add a farewell method"))).command();
        assertEquals(without + " -- 'Add a farewell method'", with);
    }

    private static final StdioMcpServer IDE = new StdioMcpServer("jetbrains", "/jbr/bin/java",
            List.of("-cp", "/ide/lib/a.jar:/ide/lib/b.jar", "com.intellij.mcpserver.stdio.McpStdioRunnerKt"),
            Map.of("IJ_MCP_SERVER_PROJECT_PATH", "/wt/repo-task", "IJ_MCP_SERVER_PORT", "64342"));

    private static final String IDE_OVERRIDES =
            " -c 'mcp_servers.jetbrains.command=\"/jbr/bin/java\"'"
            + " -c 'mcp_servers.jetbrains.args=[\"-cp\", \"/ide/lib/a.jar:/ide/lib/b.jar\","
            + " \"com.intellij.mcpserver.stdio.McpStdioRunnerKt\"]'"
            + " -c 'mcp_servers.jetbrains.env={\"IJ_MCP_SERVER_PORT\"=\"64342\","
            + " \"IJ_MCP_SERVER_PROJECT_PATH\"=\"/wt/repo-task\"}'";

    @Test
    void createCarriesStdioServersAsConfigOverrides() {
        String command = provider().buildCreateCommand(
                new CreateContext("s", "x", Path.of("/repo"), Optional.empty(), Optional.empty())
                        .withStdioMcpServers(List.of(IDE))).command();

        assertTrue(command.endsWith(IDE_OVERRIDES), command);
    }

    @Test
    void resumeCarriesStdioServersBeforeTheSubcommand() {
        String command = provider().buildResumeCommand(
                new ResumeContext(Optional.of("019f9072-abc"), Optional.empty(), Path.of("/repo"),
                        Optional.empty(), Optional.empty()).withStdioMcpServers(List.of(IDE))).command();

        assertTrue(command.endsWith(IDE_OVERRIDES + " resume '019f9072-abc'"), command);
    }

    // ---- resume prompt: rendered after "--", only on a pinned id ----------

    private static ResumeContext resumeWithPrompt(Optional<String> id, boolean readOnly, Optional<McpAccess> mcp,
                                                  Optional<String> prompt) {
        return new ResumeContext(id, Optional.empty(), Path.of("/repo"), Optional.empty(), mcp,
                false, readOnly, List.of(), prompt);
    }

    @Test
    void resumeWithAPinnedIdCarriesThePromptAfterEndOfOptions() {
        String command = provider().buildResumeCommand(resumeWithPrompt(Optional.of("019f9072-abc"), false,
                Optional.empty(), Optional.of("Pick up where you left off."))).command();
        assertTrue(command.endsWith(
                "codex --sandbox danger-full-access resume '019f9072-abc' -- 'Pick up where you left off.'"),
                command);
    }

    @Test
    void aDashLeadingResumePromptIsAPromptNotAnOption() {
        String command = provider().buildResumeCommand(resumeWithPrompt(Optional.of("019f9072-abc"), false,
                Optional.empty(), Optional.of("--continue the task"))).command();
        assertTrue(command.endsWith("resume '019f9072-abc' -- '--continue the task'"), command);
        assertTrue(command.indexOf(" -- ") > command.indexOf("resume '019f9072-abc'"), command);
    }

    @Test
    void thePickerNeverCarriesTheResumePrompt() {
        String command = provider().buildResumeCommand(resumeWithPrompt(Optional.empty(), false,
                Optional.empty(), Optional.of("Pick up where you left off."))).command();
        assertTrue(command.endsWith("codex --sandbox danger-full-access resume"), command);
        assertFalse(command.contains(" -- "), command);
        assertFalse(command.contains("Pick up"), command);
        assertFalse(command.contains("--last"), command);
    }

    @Test
    void aPinnedResumeWithoutAPromptEndsAtTheId() {
        for (Optional<String> prompt : List.of(Optional.<String>empty(), Optional.of("   "))) {
            String command = provider().buildResumeCommand(resumeWithPrompt(Optional.of("019f9072-abc"), false,
                    Optional.empty(), prompt)).command();
            assertTrue(command.endsWith("resume '019f9072-abc'"), command);
            assertFalse(command.contains(" -- "), command);
        }
    }

    @Test
    void aReadOnlyResumeWithAPromptKeepsTheSandboxGlobal() {
        String command = provider().buildResumeCommand(resumeWithPrompt(Optional.of("019f9072-abc"), true,
                Optional.empty(), Optional.of("p"))).command();
        assertTrue(command.endsWith("codex --sandbox read-only resume '019f9072-abc' -- 'p'"), command);
    }

    @Test
    void theOverridesStayBeforeTheSubcommandWhenAPromptIsCarried() {
        String command = provider().buildResumeCommand(resumeWithPrompt(Optional.of("019f9072-abc"), false,
                SOME_MCP, Optional.of("p"))).command();
        assertTrue(command.indexOf("-c 'mcp_servers") < command.indexOf(" resume "), command);
        assertTrue(command.endsWith("resume '019f9072-abc' -- 'p'"), command);
    }
}
