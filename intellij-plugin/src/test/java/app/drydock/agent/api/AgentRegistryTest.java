package app.drydock.agent.api;

import app.drydock.agent.providers.claude.ClaudeAgentProvider;
import app.drydock.agent.spi.AgentProvider;
import app.drydock.agent.providers.claude.internal.ClaudeExecutableLocator;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRegistryTest {

    /** A configurable fake so tests control availability/remote-capability without touching the filesystem. */
    static final class StubProvider implements AgentProvider {
        private final AgentKind kind;
        private final boolean available;
        private final boolean remoteCapable;
        /** What {@link #modelSuggestions()} answers; counted in {@link #catalogCalls}. */
        Supplier<List<String>> catalog = List::of;
        final AtomicInteger catalogCalls = new AtomicInteger();
        StubProvider(AgentKind kind, boolean available) { this(kind, available, true); }
        StubProvider(AgentKind kind, boolean available, boolean remoteCapable) {
            this.kind = kind;
            this.available = available;
            this.remoteCapable = remoteCapable;
        }
        @Override public AgentKind kind() { return kind; }
        @Override public String displayName() { return kind.persistedName(); }
        @Override public void init(AgentContext ctx) { }
        @Override public Optional<Path> locateExecutable() {
            return available ? Optional.of(Path.of("/bin/" + kind.persistedName())) : Optional.empty();
        }
        @Override public String describeSearched() { return "PATH"; }
        @Override public AgentCapabilities probeCapabilities() { return new AgentCapabilities(remoteCapable, true, "1"); }
        @Override public boolean supportsRemote() { return remoteCapable; }
        @Override public McpDelivery mcpDelivery() { return McpDelivery.NONE; }
        @Override public LaunchPlan buildCreateCommand(CreateContext c) { return LaunchPlan.of("x", false); }
        @Override public LaunchPlan buildResumeCommand(ResumeContext r) { return LaunchPlan.of("x", false); }
        @Override public SessionIdStrategy idStrategy() { return SessionIdStrategy.PRESET; }
        @Override public Optional<ConversationSource> conversations() { return Optional.empty(); }
        @Override public Optional<ActivityReporter> activity() { return Optional.empty(); }
        @Override public Optional<SessionIdDiscovery> idDiscovery() { return Optional.empty(); }
        @Override public List<String> modelSuggestions() {
            catalogCalls.incrementAndGet();
            return catalog.get();
        }
    }

    private static AgentContext ctx() {
        return new AgentContext(Path.of("/tmp"), Path.of("/tmp/activity"), ForkJoinPool.commonPool());
    }

    @Test
    void agentsAreSortedByPreferenceOrder() {
        AgentRegistry registry = new AgentRegistry(
                List.of(new StubProvider(AgentKind.CODEX, true), new StubProvider(AgentKind.CLAUDE, true)), ctx());
        assertEquals(List.of(AgentKind.CLAUDE, AgentKind.CODEX),
                registry.agents().stream().map(Agent::kind).toList());
    }

    @Test
    void availabilityReflectsLocate() {
        AgentRegistry registry = new AgentRegistry(
                List.of(new StubProvider(AgentKind.CLAUDE, false), new StubProvider(AgentKind.CODEX, true)), ctx());
        assertFalse(registry.isAvailable(AgentKind.CLAUDE));
        assertTrue(registry.isAvailable(AgentKind.CODEX));
    }

    @Test
    void resolveDefaultPrefersAvailableRepoLastUsed() {
        AgentRegistry registry = new AgentRegistry(
                List.of(new StubProvider(AgentKind.CLAUDE, true), new StubProvider(AgentKind.CODEX, true)), ctx());
        assertEquals(Optional.of(AgentKind.CODEX), registry.resolveDefault(Optional.of(AgentKind.CODEX)));
    }

    @Test
    void resolveDefaultFallsBackToPreferenceOrderWhenLastUsedUnavailable() {
        AgentRegistry registry = new AgentRegistry(
                List.of(new StubProvider(AgentKind.CLAUDE, true), new StubProvider(AgentKind.CODEX, false)), ctx());
        // CODEX last used but unavailable → best available in [CLAUDE, CODEX, PI] → CLAUDE
        assertEquals(Optional.of(AgentKind.CLAUDE), registry.resolveDefault(Optional.of(AgentKind.CODEX)));
    }

    @Test
    void resolveDefaultEmptyWhenNothingAvailable() {
        AgentRegistry registry = new AgentRegistry(
                List.of(new StubProvider(AgentKind.CLAUDE, false)), ctx());
        assertTrue(registry.resolveDefault(Optional.empty()).isEmpty());
    }

    @Test
    void remoteCapableFilterKeepsOnlySupportingProviders() {
        AgentRegistry registry = new AgentRegistry(
                List.of(new ClaudeAgentProvider(new ClaudeExecutableLocator())), ctx());
        // Claude supports remote; the helper returns it for a remote repo.
        assertTrue(registry.agents().stream()
                .filter(a -> registry.supportsRemote(a.kind()))
                .map(Agent::kind).toList().contains(AgentKind.CLAUDE));
    }

    @Test
    void supportsRemoteReflectsCachedCapability() {
        AgentRegistry registry = new AgentRegistry(
                List.of(new StubProvider(AgentKind.CLAUDE, true, true),
                        new StubProvider(AgentKind.CODEX, true, false)), ctx());
        assertTrue(registry.supportsRemote(AgentKind.CLAUDE));
        assertFalse(registry.supportsRemote(AgentKind.CODEX));
    }

    @Test
    void resolveDefaultRequiringRemoteSkipsNonRemoteCapableAgent() {
        AgentRegistry registry = new AgentRegistry(
                List.of(new StubProvider(AgentKind.CLAUDE, true, false),
                        new StubProvider(AgentKind.CODEX, true, true)), ctx());
        // CLAUDE is preferred and available, but not remote-capable; CODEX is.
        assertEquals(Optional.of(AgentKind.CODEX), registry.resolveDefault(Optional.empty(), true));
    }

    @Test
    void resolveDefaultRequiringRemoteEmptyWhenNoneRemoteCapable() {
        AgentRegistry registry = new AgentRegistry(
                List.of(new StubProvider(AgentKind.CLAUDE, true, false)), ctx());
        assertTrue(registry.resolveDefault(Optional.empty(), true).isEmpty());
    }

    @Test
    void previewCreateCommandReturnsTheProvidersBuiltCommand() {
        AgentRegistry registry = new AgentRegistry(List.of(new StubProvider(AgentKind.CLAUDE, true)), ctx());
        CreateContext createContext = new CreateContext("name", "id", Path.of("/tmp"), Optional.empty(), Optional.empty());
        assertEquals("x", registry.previewCreateCommand(AgentKind.CLAUDE, createContext));
    }

    @Test
    void previewCreateCommandReportsNoProviderForAnUnregisteredKind() {
        AgentRegistry registry = new AgentRegistry(List.of(new StubProvider(AgentKind.CLAUDE, true)), ctx());
        CreateContext createContext = new CreateContext("name", "id", Path.of("/tmp"), Optional.empty(), Optional.empty());
        assertEquals("(no provider for " + AgentKind.CODEX.persistedName() + ")",
                registry.previewCreateCommand(AgentKind.CODEX, createContext));
    }

    @Test
    void idDiscoveryDefaultsEmptyForProvidersWithoutIt() {
        AgentRegistry registry = new AgentRegistry(
                List.of(new StubProvider(AgentKind.CLAUDE, true)), ctx());
        assertTrue(registry.idDiscovery(AgentKind.CLAUDE).isEmpty());
    }

    @Test
    void createWithExplicitLoaderDiscoversTheRegisteredProviders() {
        // The services file in the core resources registers all four providers,
        // so discovery through the core class loader yields the full preference order.
        AgentRegistry registry = AgentRegistry.create(ctx(), AgentRegistry.class.getClassLoader());
        assertEquals(AgentKind.preferenceOrder(),
                registry.agents().stream().map(Agent::kind).toList());
    }

    @Test
    void createWithExplicitLoaderDiscoversNothingUnderALoaderWithoutRegistrations() {
        // A loader whose delegation chain skips the core resources sees no services
        // registrations: the loader argument, not the TCCL, drives discovery.
        ClassLoader isolated = new URLClassLoader(new URL[0], null);
        assertTrue(AgentRegistry.create(ctx(), isolated).agents().isEmpty());
    }

    @Test
    void aFailedModelCatalogProbeIsRetriedThenTheSuccessIsCached() {
        StubProvider claude = new StubProvider(AgentKind.CLAUDE, true);
        AtomicInteger attempts = new AtomicInteger();
        claude.catalog = () -> {
            if (attempts.getAndIncrement() == 0) {
                throw new RuntimeException("transient");
            }
            return List.of("m1");
        };
        AgentRegistry registry = new AgentRegistry(List.of(claude), ctx());

        assertEquals(List.of(), registry.modelSuggestions(AgentKind.CLAUDE));
        assertEquals(List.of("m1"), registry.modelSuggestions(AgentKind.CLAUDE));
        assertEquals(List.of("m1"), registry.modelSuggestions(AgentKind.CLAUDE));
        assertEquals(2, claude.catalogCalls.get());
    }

    @Test
    void anEmptyModelCatalogProbeIsRetried() {
        StubProvider claude = new StubProvider(AgentKind.CLAUDE, true);
        AtomicInteger attempts = new AtomicInteger();
        claude.catalog = () -> attempts.getAndIncrement() == 0 ? List.of() : List.of("m1");
        AgentRegistry registry = new AgentRegistry(List.of(claude), ctx());

        assertEquals(List.of(), registry.modelSuggestions(AgentKind.CLAUDE));
        assertEquals(List.of("m1"), registry.modelSuggestions(AgentKind.CLAUDE));
        assertEquals(2, claude.catalogCalls.get());
    }

    @Test
    void aNonEmptyModelCatalogIsProbedOnce() {
        StubProvider claude = new StubProvider(AgentKind.CLAUDE, true);
        claude.catalog = () -> List.of("a");
        AgentRegistry registry = new AgentRegistry(List.of(claude), ctx());

        for (int i = 0; i < 3; i++) {
            assertEquals(List.of("a"), registry.modelSuggestions(AgentKind.CLAUDE));
        }
        assertEquals(1, claude.catalogCalls.get());
    }

    @Test
    void aKindWithoutAProviderSuggestsNoModels() {
        AgentRegistry registry = new AgentRegistry(List.of(new StubProvider(AgentKind.CLAUDE, true)), ctx());
        assertEquals(List.of(), registry.modelSuggestions(AgentKind.CODEX));
    }
}
