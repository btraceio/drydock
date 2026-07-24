package app.drydock.agent.providers.codex;

import app.drydock.agent.api.ActivityReporter;
import app.drydock.agent.api.AgentCapabilities;
import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.ConversationSource;
import app.drydock.agent.api.CreateContext;
import app.drydock.agent.api.LaunchPlan;
import app.drydock.agent.api.ResumeContext;
import app.drydock.agent.api.SessionIdDiscovery;
import app.drydock.agent.api.SessionIdStrategy;
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

    private final CodexExecutableLocator locator;
    private CodexConversationSource conversationSource;
    private CodexIdDiscovery idDiscovery;

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
        this.idDiscovery = new CodexIdDiscovery(store);
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

    @Override
    public LaunchPlan buildCreateCommand(CreateContext c) {
        if (c.remote().isPresent()) {
            return LaunchPlan.unsupported();   // Codex declines remote
        }
        return LaunchPlan.of(envPrefix() + "codex", false);   // DISCOVERED: no id; no --settings
    }

    @Override
    public LaunchPlan buildResumeCommand(ResumeContext r) {
        if (r.remote().isPresent()) {
            return LaunchPlan.unsupported();
        }
        if (r.agentSessionId().isPresent()) {
            return LaunchPlan.of(envPrefix() + "codex resume " + shellQuote(r.agentSessionId().get()), false);
        }
        // Unknown id (or name) -> cwd-filtered picker. NEVER --last (same-cwd ambiguity).
        return LaunchPlan.of(envPrefix() + "codex resume", false);
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

    private static String envPrefix() {
        if (ENV_SCRUB.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("env");
        for (String v : ENV_SCRUB) {
            sb.append(" -u ").append(v);
        }
        return sb.append(' ').toString();
    }

    static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
