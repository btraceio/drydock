package app.drydock.ui;

import app.drydock.agent.api.AgentCapabilities;
import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.agent.api.ActivityReporter;
import app.drydock.agent.api.ConversationSource;
import app.drydock.agent.api.CreateContext;
import app.drydock.agent.api.LaunchPlan;
import app.drydock.agent.api.ResumeContext;
import app.drydock.agent.api.SessionIdDiscovery;
import app.drydock.agent.api.SessionIdStrategy;
import app.drydock.agent.spi.AgentProvider;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PrState;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentLabelsTest {

    /** Minimal provider stub: only kind/displayName matter here. */
    private record StubProvider(AgentKind kind, String label) implements AgentProvider {
        @Override public String displayName() { return label; }
        @Override public void init(AgentContext ctx) { }
        @Override public Optional<Path> locateExecutable() { return Optional.of(Path.of("/bin/" + kind.persistedName())); }
        @Override public String describeSearched() { return "PATH"; }
        @Override public AgentCapabilities probeCapabilities() { return new AgentCapabilities(true, true, "1"); }
        @Override public boolean supportsRemote() { return true; }
        @Override public boolean supportsMcpConfig() { return false; }
        @Override public LaunchPlan buildCreateCommand(CreateContext c) { return LaunchPlan.of("x", false); }
        @Override public LaunchPlan buildResumeCommand(ResumeContext r) { return LaunchPlan.of("x", false); }
        @Override public SessionIdStrategy idStrategy() { return SessionIdStrategy.PRESET; }
        @Override public Optional<ConversationSource> conversations() { return Optional.empty(); }
        @Override public Optional<ActivityReporter> activity() { return Optional.empty(); }
        @Override public Optional<SessionIdDiscovery> idDiscovery() { return Optional.empty(); }
    }

    private static AgentRegistry registry() {
        return new AgentRegistry(
                List.of(new StubProvider(AgentKind.CLAUDE, "Claude"), new StubProvider(AgentKind.CODEX, "Codex")),
                new AgentContext(Path.of("/tmp"), Path.of("/tmp/activity"), ForkJoinPool.commonPool()));
    }

    @Test
    void subTabLabelNamesTheSessionsOwnAgent() {
        assertEquals("✳  Codex", AgentLabels.subTabLabel(registry(), AgentKind.CODEX));
        assertEquals("✳  Claude", AgentLabels.subTabLabel(registry(), AgentKind.CLAUDE));
    }

    @Test
    void tooltipNamesTheSessionsOwnAgent() {
        assertEquals("Codex (⌘1)", AgentLabels.subTabTooltip(registry(), AgentKind.CODEX));
    }

    @Test
    void fallsBackToThePersistedNameWhenNoProviderIsRegistered() {
        // A session persisted with an agent this build didn't discover must
        // still name it -- never the previous hard-coded "Claude".
        assertEquals("✳  Pi", AgentLabels.subTabLabel(registry(), AgentKind.PI));
    }

    @Test
    void anUnsupportedAgentIsNamedUnknownRatherThanItsPlaceholderKind() {
        // The state decoder gives a session whose persisted agent name this
        // build doesn't know a placeholder agentKind() of CLAUDE. Rendering
        // that placeholder is the one thing this class must never do.
        assertEquals("Unknown agent",
                AgentLabels.displayName(registry(), session(AgentKind.CLAUDE, SessionStatus.UNSUPPORTED_AGENT)));
        assertEquals("Codex",
                AgentLabels.displayName(registry(), session(AgentKind.CODEX, SessionStatus.INACTIVE)));
    }

    private static ManagedAgentSession session(AgentKind kind, SessionStatus status) {
        return new ManagedAgentSession(ManagedSessionId.newId(), RepositoryId.newId(), kind, "work",
                Optional.empty(), Optional.empty(), Path.of("/tmp"), Optional.empty(), status,
                Instant.EPOCH, Instant.EPOCH, Optional.empty(), PrState.NONE, Optional.empty(), false);
    }
}
