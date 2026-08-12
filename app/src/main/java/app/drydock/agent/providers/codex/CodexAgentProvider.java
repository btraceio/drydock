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
        // DISCOVERED: no id; no --settings
        return LaunchPlan.of(AgentCommands.envPrefix(ENV_SCRUB) + "codex" + mcpOverrides(c.mcp()), false);
    }

    @Override
    public LaunchPlan buildResumeCommand(ResumeContext r) {
        if (r.remote().isPresent()) {
            return LaunchPlan.unsupported();
        }
        String codex = AgentCommands.envPrefix(ENV_SCRUB) + "codex" + mcpOverrides(r.mcp());
        if (r.agentSessionId().isPresent()) {
            return LaunchPlan.of(codex + " resume " + AgentCommands.shellQuote(r.agentSessionId().get()), false);
        }
        // Unknown id (or name) -> cwd-filtered picker. NEVER --last (same-cwd ambiguity).
        return LaunchPlan.of(codex + " resume", false);
    }

    /**
     * The {@code -c} overrides that point Codex at drydock's MCP server, or
     * {@code ""} when this launch has no access minted.
     *
     * <p>They are emitted before any subcommand because {@code -c} is a global
     * option ({@code codex [OPTIONS] <COMMAND>}); after {@code resume} they
     * would be the subcommand's arguments.</p>
     *
     * <p>The token travels as a literal {@code http_headers} value rather than
     * through {@code bearer_token_env_var}. Codex accepts custom headers with
     * literal values, so drydock's own {@code X-Drydock-Session-Token} works
     * unchanged; the env-var form would have to pass the value through this
     * same command line anyway ({@code TerminalSpec} carries no environment
     * map), so it would move nothing out of argv.</p>
     */
    private static String mcpOverrides(Optional<McpAccess> access) {
        if (access.isEmpty()) {
            return "";
        }
        McpAccess mcp = access.get();
        return " -c " + AgentCommands.shellQuote(
                        "mcp_servers." + SERVER_NAME + ".url=" + tomlString(mcp.endpointUrl()))
                + " -c " + AgentCommands.shellQuote(
                        "mcp_servers." + SERVER_NAME + ".http_headers={"
                                + tomlString(TOKEN_HEADER) + "=" + tomlString(mcp.token()) + "}");
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

}
