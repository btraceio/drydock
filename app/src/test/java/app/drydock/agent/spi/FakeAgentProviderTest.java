package app.drydock.agent.spi;

import app.drydock.agent.api.AgentCapabilities;
import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.ActivityReporter;
import app.drydock.agent.api.ConversationSource;
import app.drydock.agent.api.CreateContext;
import app.drydock.agent.api.LaunchPlan;
import app.drydock.agent.api.ResumeContext;
import app.drydock.agent.api.SessionIdDiscovery;
import app.drydock.agent.api.SessionIdStrategy;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FakeAgentProviderTest {

    /** A minimal provider proving the SPI surface is implementable and usable via the API types. */
    static final class FakeProvider implements AgentProvider {
        boolean initialized;

        @Override public AgentKind kind() { return AgentKind.CLAUDE; }
        @Override public String displayName() { return "Fake"; }
        @Override public void init(AgentContext ctx) { this.initialized = true; }
        @Override public Optional<Path> locateExecutable() { return Optional.of(Path.of("/bin/true")); }
        @Override public String describeSearched() { return "PATH"; }
        @Override public AgentCapabilities probeCapabilities() { return new AgentCapabilities(true, true, "1.0"); }
        @Override public boolean supportsRemote() { return true; }
        @Override public LaunchPlan buildCreateCommand(CreateContext c) { return LaunchPlan.of("fake " + c.sessionId(), true); }
        @Override public LaunchPlan buildResumeCommand(ResumeContext r) { return LaunchPlan.of("fake --resume", false); }
        @Override public SessionIdStrategy idStrategy() { return SessionIdStrategy.PRESET; }
        @Override public Optional<ConversationSource> conversations() { return Optional.empty(); }
        @Override public Optional<ActivityReporter> activity() { return Optional.empty(); }
        @Override public Optional<SessionIdDiscovery> idDiscovery() { return Optional.empty(); }
    }

    @Test
    void fakeProviderImplementsTheWholeSpi() {
        FakeProvider provider = new FakeProvider();
        // Use a simple synchronous executor for testing
        provider.init(new AgentContext(Path.of("/tmp"), Path.of("/tmp/activity"),
            java.util.concurrent.ForkJoinPool.commonPool()));
        assertTrue(provider.initialized);
        assertEquals("fake abc", provider.buildCreateCommand(
                new CreateContext("Session 1", "abc", Path.of("/tmp"), Optional.empty())).command());
        assertTrue(provider.conversations().isEmpty());
    }
}
