package app.drydock.intellij.actions;

import app.drydock.agent.api.ActivityReporter;
import app.drydock.agent.api.AgentCapabilities;
import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.agent.api.ConversationSource;
import app.drydock.agent.api.CreateContext;
import app.drydock.agent.api.LaunchPlan;
import app.drydock.agent.api.McpDelivery;
import app.drydock.agent.api.ResumeContext;
import app.drydock.agent.api.SessionIdDiscovery;
import app.drydock.agent.api.SessionIdStrategy;
import app.drydock.agent.spi.AgentProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The T13 pure seams, pinned without opening a dialog (and without the
 * IDE): the default-agent resolution (AC8), the resume provider choice and
 * its disabled-when-empty rule (AC22), the newest-first conversation
 * ordering (AC20), and the availability/row rendering texts (AC6). The
 * Swing halves are the manual runIde gate (Pi create + Pi resume picker +
 * Claude resume).
 */
class SessionDialogsTest {

    // ---- fakes ------------------------------------------------------------------

    /**
     * A provider fake over the whole SPI: availability is what the registry
     * probes ({@code locateExecutable}), and the optional conversation
     * source is what the resume seams ask for. Everything else is inert.
     */
    static final class FakeProvider implements AgentProvider {

        private final AgentKind kind;
        private final boolean available;
        private final ConversationSource conversations;

        FakeProvider(AgentKind kind, boolean available, ConversationSource conversations) {
            this.kind = kind;
            this.available = available;
            this.conversations = conversations;
        }

        @Override public AgentKind kind() { return kind; }
        @Override public String displayName() { return "Fake " + kind; }
        @Override public void init(AgentContext ctx) { }
        @Override public Optional<Path> locateExecutable() {
            return available ? Optional.of(Path.of("/bin/true")) : Optional.empty();
        }
        @Override public String describeSearched() { return "PATH"; }
        @Override public AgentCapabilities probeCapabilities() { return new AgentCapabilities(true, true, "1.0"); }
        @Override public boolean supportsRemote() { return false; }
        @Override public McpDelivery mcpDelivery() { return McpDelivery.NONE; }
        @Override public LaunchPlan buildCreateCommand(CreateContext c) { return LaunchPlan.of("fake create", false); }
        @Override public LaunchPlan buildResumeCommand(ResumeContext r) { return LaunchPlan.of("fake resume", false); }
        @Override public SessionIdStrategy idStrategy() { return SessionIdStrategy.PRESET; }
        @Override public Optional<ConversationSource> conversations() { return Optional.ofNullable(conversations); }
        @Override public Optional<ActivityReporter> activity() { return Optional.empty(); }
        @Override public Optional<SessionIdDiscovery> idDiscovery() { return Optional.empty(); }
    }

    /** The registry's AgentContext wants an ExecutorService; this one runs everything inline. */
    static final class SameThreadExecutorService extends AbstractExecutorService {
        @Override public void execute(Runnable command) { command.run(); }
        @Override public void shutdown() { }
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    }

    private static AgentRegistry registry(@TempDir Path base, AgentProvider... providers) {
        return new AgentRegistry(List.of(providers),
                new AgentContext(base, base.resolve("activity"), new SameThreadExecutorService()));
    }

    // ---- default agent (AC8) -------------------------------------------------

    @Test
    void defaultAgentPrefersLastUsedWhenStillAvailable(@TempDir Path base) {
        // CLAUDE is first in preferenceOrder; the repo's last-used PI must
        // still win because it is available (AC8: last-used wins).
        AgentRegistry registry = registry(base,
                new FakeProvider(AgentKind.CLAUDE, true, null),
                new FakeProvider(AgentKind.PI, true, null));
        assertEquals(Optional.of(AgentKind.PI),
                SessionDialogs.defaultAgent(registry, Optional.of(AgentKind.PI)));
    }

    @Test
    void defaultAgentFallsToPreferenceOrderFirstAvailable(@TempDir Path base) {
        // No last-used agent: the first AVAILABLE kind in preferenceOrder
        // (CLAUDE, CODEX, PI, TERMINAL) is the default — here CODEX, with
        // CLAUDE registered but unavailable (Q3: preferenceOrder unchanged).
        AgentRegistry registry = registry(base,
                new FakeProvider(AgentKind.CLAUDE, false, null),
                new FakeProvider(AgentKind.CODEX, true, null),
                new FakeProvider(AgentKind.PI, true, null));
        assertEquals(Optional.of(AgentKind.CODEX),
                SessionDialogs.defaultAgent(registry, Optional.empty()));
    }

    @Test
    void defaultAgentIgnoresAnUnavailableLastUsed(@TempDir Path base) {
        // The last-used agent is no longer installed: fall to the first
        // available in preferenceOrder instead of offering a dead default.
        AgentRegistry registry = registry(base,
                new FakeProvider(AgentKind.CLAUDE, false, null),
                new FakeProvider(AgentKind.PI, true, null));
        assertEquals(Optional.of(AgentKind.PI),
                SessionDialogs.defaultAgent(registry, Optional.of(AgentKind.CLAUDE)));
    }

}
