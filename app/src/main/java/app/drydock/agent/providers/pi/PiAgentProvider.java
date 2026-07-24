package app.drydock.agent.providers.pi;

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
import app.drydock.agent.api.SnapshotClaimDiscovery;
import app.drydock.agent.providers.pi.internal.PiExecutableLocator;
import app.drydock.agent.providers.pi.internal.PiSessionStore;
import app.drydock.agent.providers.pi.internal.PiVersionProbe;
import app.drydock.agent.spi.AgentProvider;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Pi coding agent CLI as an {@link AgentProvider}. DISCOVERED id strategy, no
 * remote, no activity badges.
 */
public final class PiAgentProvider implements AgentProvider {

    // Pi refuses to run nested inside itself unless PI_CODING_AGENT is scrubbed.
    private static final List<String> ENV_SCRUB = List.of("PI_CODING_AGENT");

    private final PiExecutableLocator locator;
    private PiConversationSource conversationSource;
    private SessionIdDiscovery idDiscovery;

    /** Public no-arg constructor required by {@link java.util.ServiceLoader}. */
    public PiAgentProvider() {
        this(new PiExecutableLocator());
    }

    /** For tests: inject a locator (e.g. a nonexistent path to force conservative caps). */
    public PiAgentProvider(PiExecutableLocator locator) {
        this.locator = locator;
    }

    @Override
    public AgentKind kind() {
        return AgentKind.PI;
    }

    @Override
    public String displayName() {
        return "Pi";
    }

    @Override
    public void init(AgentContext ctx) {
        PiSessionStore store = new PiSessionStore();
        this.conversationSource = new PiConversationSource(store);
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

    /** Probes {@code pi --version} (blocking; off the FX thread per the SPI contract). */
    @Override
    public AgentCapabilities probeCapabilities() {
        return new AgentCapabilities(false, true, PiVersionProbe.probe(locator.locate().orElse(null)));
    }

    @Override
    public boolean supportsRemote() {
        return false;
    }

    @Override
    public LaunchPlan buildCreateCommand(CreateContext c) {
        if (c.remote().isPresent()) {
            return LaunchPlan.unsupported();   // Pi declines remote
        }
        return LaunchPlan.of(envPrefix() + "pi", false);   // DISCOVERED: no id
    }

    @Override
    public LaunchPlan buildResumeCommand(ResumeContext r) {
        if (r.remote().isPresent()) {
            return LaunchPlan.unsupported();
        }
        if (r.agentSessionId().isPresent()) {
            return LaunchPlan.of(envPrefix() + "pi --session " + shellQuote(r.agentSessionId().get()), false);
        }
        // Unknown id -> picker. NEVER --continue/--last (same-cwd ambiguity).
        return LaunchPlan.of(envPrefix() + "pi --resume", false);
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
        return Optional.empty();
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
