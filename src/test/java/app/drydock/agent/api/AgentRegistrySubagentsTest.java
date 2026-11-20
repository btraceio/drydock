package app.drydock.agent.api;

import app.drydock.agent.spi.AgentProvider;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AgentRegistry#supportsSubagents} is cached at construction from
 * {@link AgentProvider#supportsSubagents()} -- the same idiom as {@link
 * AgentRegistryTest#supportsRemoteReflectsCachedCapability}, so the UI can
 * read it on the FX thread without a process spawn.
 */
class AgentRegistrySubagentsTest {

    /** A configurable fake so tests control subagent-capability without touching the filesystem. */
    static final class StubProvider implements AgentProvider {
        private final AgentKind kind;
        private final boolean subagentCapable;
        StubProvider(AgentKind kind, boolean subagentCapable) {
            this.kind = kind;
            this.subagentCapable = subagentCapable;
        }
        @Override public AgentKind kind() { return kind; }
        @Override public String displayName() { return kind.persistedName(); }
        @Override public void init(AgentContext ctx) { }
        @Override public Optional<Path> locateExecutable() { return Optional.of(Path.of("/bin/" + kind.persistedName())); }
        @Override public String describeSearched() { return "PATH"; }
        @Override public AgentCapabilities probeCapabilities() { return new AgentCapabilities(true, true, "1"); }
        @Override public boolean supportsRemote() { return true; }
        @Override public boolean supportsSubagents() { return subagentCapable; }
        @Override public McpDelivery mcpDelivery() { return McpDelivery.NONE; }
        @Override public LaunchPlan buildCreateCommand(CreateContext c) { return LaunchPlan.of("x", false); }
        @Override public LaunchPlan buildResumeCommand(ResumeContext r) { return LaunchPlan.of("x", false); }
        @Override public SessionIdStrategy idStrategy() { return SessionIdStrategy.PRESET; }
        @Override public Optional<ConversationSource> conversations() { return Optional.empty(); }
        @Override public Optional<ActivityReporter> activity() { return Optional.empty(); }
        @Override public Optional<SessionIdDiscovery> idDiscovery() { return Optional.empty(); }
    }

    private static AgentContext ctx() {
        return new AgentContext(Path.of("/tmp"), Path.of("/tmp/activity"), ForkJoinPool.commonPool());
    }

    @Test
    void supportsSubagentsReflectsCachedCapability() {
        AgentRegistry registry = new AgentRegistry(
                List.of(new StubProvider(AgentKind.CLAUDE, true)), ctx());
        assertTrue(registry.supportsSubagents(AgentKind.CLAUDE));
    }

    @Test
    void supportsSubagentsFalseForAnUnknownKind() {
        AgentRegistry registry = new AgentRegistry(
                List.of(new StubProvider(AgentKind.CLAUDE, true)), ctx());
        assertFalse(registry.supportsSubagents(AgentKind.CODEX));
    }
}
