package app.drydock.agent.api;

import app.drydock.agent.spi.AgentProvider;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Discovers {@link AgentProvider}s (via {@link ServiceLoader}), inits each with
 * the shared {@link AgentContext}, caches availability and remote-capability
 * once, and resolves the default agent for a new session. Both are computed
 * off the FX thread by the caller of {@link #create} (construction probes
 * {@code locateExecutable} and {@code probeCapabilities}, either of which may
 * block); every other reader (e.g. the UI building a picker) only ever reads
 * the cached maps, so it never needs to probe -- and never blocks the FX
 * thread -- to know whether an agent is available or remote-capable.
 */
public final class AgentRegistry {

    private static final Logger LOG = System.getLogger(AgentRegistry.class.getName());

    private final Map<AgentKind, AgentProvider> providers = new EnumMap<>(AgentKind.class);
    private final Map<AgentKind, Boolean> availability = new EnumMap<>(AgentKind.class);
    private final Map<AgentKind, Boolean> remoteCapability = new EnumMap<>(AgentKind.class);

    /** Discovers providers via ServiceLoader. Call off the FX thread (probes executables). */
    public static AgentRegistry create(AgentContext ctx) {
        List<AgentProvider> discovered = new ArrayList<>();
        for (AgentProvider provider : ServiceLoader.load(AgentProvider.class)) {
            discovered.add(provider);
        }
        return new AgentRegistry(discovered, ctx);
    }

    /** For tests/callers that want to hand-pick providers instead of discovering them via {@link ServiceLoader}. */
    public AgentRegistry(List<AgentProvider> discovered, AgentContext ctx) {
        for (AgentProvider provider : discovered) {
            if (providers.containsKey(provider.kind())) {
                LOG.log(Level.WARNING, "Duplicate provider for {0}; keeping the first", provider.kind());
                continue;
            }
            provider.init(ctx);
            providers.put(provider.kind(), provider);
            boolean available;
            try {
                available = provider.locateExecutable().isPresent();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, () -> "Availability probe failed for " + provider.kind() + ": " + e);
                available = false;
            }
            availability.put(provider.kind(), available);
            boolean remote;
            try {
                remote = provider.probeCapabilities().supportsRemote();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, () -> "Capability probe failed for " + provider.kind() + ": " + e);
                remote = false;
            }
            remoteCapability.put(provider.kind(), remote);
        }
    }

    public List<Agent> agents() {
        List<Agent> agents = new ArrayList<>();
        for (AgentKind kind : AgentKind.preferenceOrder()) {
            AgentProvider provider = providers.get(kind);
            if (provider != null) {
                agents.add(new RegisteredAgent(provider, availability.getOrDefault(kind, false)));
            }
        }
        return agents;
    }

    public Optional<AgentProvider> provider(AgentKind kind) {
        return Optional.ofNullable(providers.get(kind));
    }

    public Optional<ConversationSource> conversations(AgentKind kind) {
        return provider(kind).flatMap(AgentProvider::conversations);
    }

    public Optional<ActivityReporter> activity(AgentKind kind) {
        return provider(kind).flatMap(AgentProvider::activity);
    }

    public boolean isAvailable(AgentKind kind) {
        return availability.getOrDefault(kind, false);
    }

    /**
     * Whether {@code kind}'s provider reports remote-session support, per
     * {@link app.drydock.agent.spi.AgentProvider#probeCapabilities()}. Cached
     * at construction time (alongside {@link #isAvailable}) so callers never
     * need to re-probe -- and, in particular, so the UI can read this
     * synchronously off the FX thread without triggering a process spawn.
     */
    public boolean supportsRemote(AgentKind kind) {
        return remoteCapability.getOrDefault(kind, false);
    }

    /**
     * Resolves the pre-selected default: the repo's last-used agent if still
     * available, else the first available agent in preference order, else empty
     * (no agent CLI found).
     */
    public Optional<AgentKind> resolveDefault(Optional<AgentKind> repoLastUsed) {
        return resolveDefault(repoLastUsed, false);
    }

    /**
     * As {@link #resolveDefault(Optional)}, but when {@code requireRemote} is
     * true, agents that don't report {@link #supportsRemote} are skipped
     * entirely -- used when the target repository is remote, so only
     * remote-capable agents are ever offered as the default.
     */
    public Optional<AgentKind> resolveDefault(Optional<AgentKind> repoLastUsed, boolean requireRemote) {
        if (repoLastUsed.isPresent() && isAvailable(repoLastUsed.get())
                && (!requireRemote || supportsRemote(repoLastUsed.get()))) {
            return repoLastUsed;
        }
        for (AgentKind kind : AgentKind.preferenceOrder()) {
            if (isAvailable(kind) && (!requireRemote || supportsRemote(kind))) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }

    private record RegisteredAgent(AgentProvider provider, boolean available) implements Agent {
        @Override public AgentKind kind() { return provider.kind(); }
        @Override public String displayName() { return provider.displayName(); }
        @Override public boolean isAvailable() { return available; }
        @Override public String describeSearched() { return provider.describeSearched(); }
    }
}
