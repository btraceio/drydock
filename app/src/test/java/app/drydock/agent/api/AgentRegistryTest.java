package app.drydock.agent.api;

import app.drydock.agent.providers.claude.ClaudeAgentProvider;
import app.drydock.agent.spi.AgentProvider;
import app.drydock.claude.ClaudeExecutableLocator;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRegistryTest {

    /** A configurable fake so tests control availability/remote-capability without touching the filesystem. */
    static final class StubProvider implements AgentProvider {
        private final AgentKind kind;
        private final boolean available;
        private final boolean remoteCapable;
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
        @Override public List<String> envScrubList() { return List.of(); }
        @Override public LaunchPlan buildCreateCommand(CreateContext c) { return LaunchPlan.of("x", false); }
        @Override public LaunchPlan buildResumeCommand(ResumeContext r) { return LaunchPlan.of("x", false); }
        @Override public SessionIdStrategy idStrategy() { return SessionIdStrategy.PRESET; }
        @Override public Optional<ConversationSource> conversations() { return Optional.empty(); }
        @Override public Optional<ActivityReporter> activity() { return Optional.empty(); }
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
}
