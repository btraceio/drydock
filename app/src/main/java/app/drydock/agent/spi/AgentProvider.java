package app.drydock.agent.spi;

import app.drydock.agent.api.ActivityReporter;
import app.drydock.agent.api.AgentCapabilities;
import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.ConversationSource;
import app.drydock.agent.api.CreateContext;
import app.drydock.agent.api.LaunchPlan;
import app.drydock.agent.api.McpDelivery;
import app.drydock.agent.api.ResumeContext;
import app.drydock.agent.api.SessionIdDiscovery;
import app.drydock.agent.api.SessionIdStrategy;

import java.nio.file.Path;
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

    /**
     * How this integration is handed drydock's own MCP tools -- a file it
     * points at, overrides on its command line, or not at all. A static fact
     * about the integration, like {@link #supportsRemote()}, so implementations
     * MUST make this CHEAP and non-blocking: no process spawns, no I/O. Safe on
     * the JavaFX thread.
     *
     * <p>Distinct from any probed CLI flag: this says what the integration can
     * consume at all, not that the installed binary accepts the option. The
     * binary check stays provider-internal (Claude's {@code
     * ClaudeCapabilities.supportsMcpConfig}), per this interface's rule that
     * provider-internal flag detail is not exposed here.</p>
     *
     * <p>This replaced a boolean {@code supportsMcpConfig}. The question it
     * asked -- "does this understand Claude's config file" -- stopped being
     * answerable yes/no once Codex reached the same server by a different
     * mechanism and had no use for a file.</p>
     */
    McpDelivery mcpDelivery();

    LaunchPlan buildCreateCommand(CreateContext c);

    LaunchPlan buildResumeCommand(ResumeContext r);

    SessionIdStrategy idStrategy();

    Optional<ConversationSource> conversations();

    Optional<ActivityReporter> activity();

    /** Present only for DISCOVERED-strategy providers; empty for PRESET. */
    Optional<SessionIdDiscovery> idDiscovery();

    /**
     * Whether this provider can actually route an eval session's traffic to
     * the eval account. Cheap and cached: the UI reads it synchronously to
     * enable/disable the eval checkbox, so an implementor MUST probe
     * out-of-band (e.g. at {@link #init}) and return a snapshot here, never
     * block. Default {@code true} (the provider injects eval through the
     * command or a side channel); a provider with no working injection path
     * returns {@code false} so the checkbox is disabled rather than misleading.
     */
    default boolean evalAvailable() {
        return true;
    }

    /**
     * Marks {@code sessionKey} as an eval session with whatever side channel
     * the provider uses (e.g. an HTTP call to a local proxy that injects the
     * header). Called on a background executor at eval-session launch (new
     * and resume); best-effort, never fails the launch. Default no-op: a
     * provider that injects eval purely through the launch command (env var)
     * needs do nothing. {@code sessionKey} is the agent session id the CLI
     * sends on its requests (for PRESET providers, the {@code --session-id}
     * drydock generated).
     */
    default void markEvalSession(String sessionKey) { }

    /**
     * Reverses {@link #markEvalSession}. Called on a background executor when
     * an eval session closes, exits, or is deleted. Idempotent and safe when
     * {@code markEvalSession} never ran for {@code sessionKey}.
     */
    default void unmarkEvalSession(String sessionKey) { }
}
