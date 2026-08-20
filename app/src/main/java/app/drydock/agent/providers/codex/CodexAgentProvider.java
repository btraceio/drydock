package app.drydock.agent.providers.codex;

import app.drydock.agent.api.ActivityReporter;
import app.drydock.agent.api.AgentCapabilities;
import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.ConversationSource;
import app.drydock.agent.api.CreateContext;
import app.drydock.agent.api.LaunchPlan;
import app.drydock.agent.api.McpAccess;
import app.drydock.agent.api.McpDelivery;
import app.drydock.agent.api.ResumeContext;
import app.drydock.agent.api.SessionIdDiscovery;
import app.drydock.agent.api.SessionIdStrategy;
import app.drydock.agent.api.SnapshotClaimDiscovery;
import app.drydock.agent.providers.AgentCommands;
import app.drydock.agent.providers.codex.internal.CodexExecutableLocator;
import app.drydock.agent.providers.codex.internal.CodexRolloutStore;
import app.drydock.agent.providers.codex.internal.CodexVersionProbe;
import app.drydock.agent.spi.AgentProvider;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * OpenAI Codex CLI as an {@link AgentProvider}. DISCOVERED id strategy, no
 * remote, no activity badges.
 */
public final class CodexAgentProvider implements AgentProvider {

    // Codex nested-sandbox markers (verified in the binary). Preserve CODEX_HOME.
    private static final List<String> ENV_SCRUB = List.of("CODEX_SANDBOX", "CODEX_SANDBOX_NETWORK_DISABLED");

    /** Server name under {@code mcp_servers}; matches the key McpConfigWriter uses for Claude. */
    private static final String SERVER_NAME = "drydock";

    /** Kept in step with {@code McpConfigWriter} and {@code McpServer}. */
    private static final String TOKEN_HEADER = "X-Drydock-Session-Token";

    /** Environment variable Codex reads the header value from; private to this launch. */
    private static final String TOKEN_ENV_VAR = "DRYDOCK_SESSION_TOKEN";

    private final CodexExecutableLocator locator;
    private CodexConversationSource conversationSource;
    private SessionIdDiscovery idDiscovery;

    /** Public no-arg constructor required by {@link java.util.ServiceLoader}. */
    public CodexAgentProvider() {
        this(new CodexExecutableLocator());
    }

    /** For tests: inject a locator (e.g. a nonexistent path to force conservative caps). */
    public CodexAgentProvider(CodexExecutableLocator locator) {
        this.locator = locator;
    }

    @Override
    public AgentKind kind() {
        return AgentKind.CODEX;
    }

    @Override
    public String displayName() {
        return "Codex";
    }

    @Override
    public void init(AgentContext ctx) {
        CodexRolloutStore store = new CodexRolloutStore();
        this.conversationSource = new CodexConversationSource(store);
        this.idDiscovery = new SnapshotClaimDiscovery(store);
    }

    @Override
    public Optional<Path> locateExecutable() {
        return locator.locate();
    }

    @Override
    public String describeSearched() {
        return locator.describeSearched();
    }

    /** Probes {@code codex --version} (blocking; off the FX thread per the SPI contract). */
    @Override
    public AgentCapabilities probeCapabilities() {
        return new AgentCapabilities(false, true, CodexVersionProbe.probe(locator.locate().orElse(null)));
    }

    @Override
    public boolean supportsRemote() {
        return false;
    }

    /**
     * Codex takes its MCP wiring as config overrides, not as a file: there is
     * no {@code --mcp-config} equivalent, but {@code -c} sets any config value
     * for one invocation, so nothing of the user's {@code ~/.codex/config.toml}
     * is touched.
     */
    @Override
    public McpDelivery mcpDelivery() {
        return McpDelivery.COMMAND_LINE;
    }

    @Override
    public LaunchPlan buildCreateCommand(CreateContext c) {
        if (c.remote().isPresent()) {
            return LaunchPlan.unsupported();   // Codex declines remote
        }
        // Eval mode: the flag is carried on the session and shown in the UI,
        // but Codex's built-in openai provider cannot be overridden with
        // extra headers (codex rejects `model_providers.openai.*` as a
        // reserved built-in), and defining a whole custom provider would
        // require the user's base_url and auth -- too invasive and fragile.
        // So an eval Codex session is marked but its requests are NOT routed
        // to the eval account. See ManagedAgentSession.evalMode.
        return LaunchPlan.of(codexCommand(c.mcp()), false);   // DISCOVERED: no id; no --settings
    }

    @Override
    public LaunchPlan buildResumeCommand(ResumeContext r) {
        if (r.remote().isPresent()) {
            return LaunchPlan.unsupported();
        }
        String codex = codexCommand(r.mcp());
        if (r.agentSessionId().isPresent()) {
            return LaunchPlan.of(codex + " resume " + AgentCommands.shellQuote(r.agentSessionId().get()), false);
        }
        // Unknown id (or name) -> cwd-filtered picker. NEVER --last (same-cwd ambiguity).
        return LaunchPlan.of(codex + " resume", false);
    }

    /**
     * The whole {@code codex} invocation up to (but not including) any
     * subcommand: the env prefix, then the {@code -c} overrides that point
     * Codex at drydock's MCP server.
     *
     * <p>The overrides precede any subcommand because {@code -c} is a global
     * option ({@code codex [OPTIONS] <COMMAND>}); after {@code resume} they
     * would be the subcommand's arguments.</p>
     *
     * <p><strong>The token reaches Codex without ever being written down
     * here.</strong> Codex would take it as a literal {@code http_headers}
     * value, which is simpler and wrong: on macOS another user can read a
     * process's argv via {@code ps} while its environment stays private, so a
     * literal is world-readable for as long as the session runs. {@code
     * env_http_headers} names an environment variable instead -- but naming
     * one is only half of it, because setting it from a literal puts the token
     * straight back on the command line, where the session's {@code login}
     * parent keeps it for the life of the tab. So the variable is filled from
     * the owner-only file drydock minted, read by the shell at exec time; this
     * command carries the path and nothing else. See {@code
     * AgentCommands.envPrefixFromFiles}.</p>
     *
     * <p>Without that file there is no safe way to hand Codex the token, so
     * the session launches with no Drydock tools rather than with a leaked
     * credential. {@code SessionManager} always mints one when MCP is wired,
     * so this is a guard, not an expected path.</p>
     */
    private static String codexCommand(Optional<McpAccess> access) {
        Optional<Path> tokenFile = access.flatMap(McpAccess::credentialFile);
        if (access.isEmpty() || tokenFile.isEmpty()) {
            return AgentCommands.envPrefix(ENV_SCRUB) + "codex";
        }
        McpAccess mcp = access.get();
        return AgentCommands.envPrefixFromFiles(ENV_SCRUB, Map.of(TOKEN_ENV_VAR, tokenFile.get()))
                + "codex"
                + " -c " + AgentCommands.shellQuote(
                        "mcp_servers." + SERVER_NAME + ".url=" + tomlString(mcp.endpointUrl()))
                + " -c " + AgentCommands.shellQuote(
                        "mcp_servers." + SERVER_NAME + ".env_http_headers={"
                                + tomlString(TOKEN_HEADER) + "=" + tomlString(TOKEN_ENV_VAR) + "}");
    }

    /**
     * {@code value} as a TOML basic string. The token is opaque here, so a
     * quote or backslash in it must stay inside the string rather than end it.
     */
    private static String tomlString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Override
    public SessionIdStrategy idStrategy() {
        return SessionIdStrategy.DISCOVERED;
    }

    @Override
    public Optional<ConversationSource> conversations() {
        return Optional.of(conversationSource);
    }

    @Override
    public Optional<ActivityReporter> activity() {
        return Optional.empty();   // trust-gated, no notify
    }

    @Override
    public Optional<SessionIdDiscovery> idDiscovery() {
        return Optional.of(idDiscovery);
    }

    /**
     * Codex's built-in openai provider cannot carry extra headers and there
     * is no proxy-side injection path, so an eval session would be marked but
     * not rerouted. Disable the checkbox rather than offer a no-op.
     */
    @Override
    public boolean evalAvailable() {
        return false;
    }

}
