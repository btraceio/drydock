package app.drydock.agent.spi;

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

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The one interface each agentic CLI implements. Discovered via
 * {@link java.util.ServiceLoader}, so implementations need a public no-arg
 * constructor and receive collaborators via {@link #init(AgentContext)}.
 *
 * <p>{@link #buildCreateCommand}/{@link #buildResumeCommand},
 * {@link #locateExecutable}, and {@link #probeCapabilities} may perform
 * blocking work (process spawns, filesystem probes) and MUST be called off the
 * JavaFX Application Thread.</p>
 */
public interface AgentProvider {

    AgentKind kind();

    String displayName();

    void init(AgentContext ctx);

    Optional<Path> locateExecutable();

    String describeSearched();

    AgentCapabilities probeCapabilities();

    /**
     * Whether this integration supports SSH-remote sessions. This is a static
     * fact about the integration (not something detected from the CLI), so
     * implementations MUST make this CHEAP and non-blocking: no process
     * spawns, no filesystem or network I/O. Safe to call on the JavaFX
     * Application Thread.
     */
    boolean supportsRemote();

    List<String> envScrubList();

    LaunchPlan buildCreateCommand(CreateContext c);

    LaunchPlan buildResumeCommand(ResumeContext r);

    SessionIdStrategy idStrategy();

    Optional<ConversationSource> conversations();

    Optional<ActivityReporter> activity();

    /** Present only for DISCOVERED-strategy providers; empty for PRESET. */
    Optional<SessionIdDiscovery> idDiscovery();
}
