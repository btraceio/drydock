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
 * the shared {@link AgentContext}, caches availability once, and resolves the
 * default agent for a new session. Availability is computed off the FX thread
 * by the caller of {@link #create} (construction probes {@code locateExecutable}).
 */
public final class AgentRegistry {

    private static final Logger LOG = System.getLogger(AgentRegistry.class.getName());

    private final Map<AgentKind, AgentProvider> providers = new EnumMap<>(AgentKind.class);
    private final Map<AgentKind, Boolean> availability = new EnumMap<>(AgentKind.class);

    /** Discovers providers via ServiceLoader. Call off the FX thread (probes executables). */
    public static AgentRegistry create(AgentContext ctx) {
        List<AgentProvider> discovered = new ArrayList<>();
        for (AgentProvider provider : ServiceLoader.load(AgentProvider.class)) {
            discovered.add(provider);
        }
        return new AgentRegistry(discovered, ctx);
    }

    AgentRegistry(List<AgentProvider> discovered, AgentContext ctx) {
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
     * Resolves the pre-selected default: the repo's last-used agent if still
     * available, else the first available agent in preference order, else empty
     * (no agent CLI found).
     */
    public Optional<AgentKind> resolveDefault(Optional<AgentKind> repoLastUsed) {
        if (repoLastUsed.isPresent() && isAvailable(repoLastUsed.get())) {
            return repoLastUsed;
        }
        for (AgentKind kind : AgentKind.preferenceOrder()) {
            if (isAvailable(kind)) {
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
