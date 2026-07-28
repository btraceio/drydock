package app.drydock.ui;

import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.agent.spi.AgentProvider;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.SessionStatus;

/**
 * Human-readable agent names for the session chrome. A tab must name the
 * agent it actually runs -- a Codex session labelled "Claude" is simply
 * wrong -- so every such label is derived from the session's {@link
 * AgentKind} here instead of being written out at a call site.
 */
final class AgentLabels {

    /** Sub-tab mark for the agent surface, matching the ❯_/▤/◨ of its neighbours. */
    private static final String AGENT_GLYPH = "✳";

    private AgentLabels() { }

    /** What an agent nobody can identify is called; see {@link #displayName(AgentRegistry, ManagedAgentSession)}. */
    private static final String UNKNOWN_AGENT = "Unknown agent";

    /**
     * The agent's display name, falling back to its (title-cased) persisted
     * name when no provider for {@code kind} is registered -- a session
     * persisted with an agent this build didn't discover still has to name it.
     */
    static String displayName(AgentRegistry registry, AgentKind kind) {
        return registry.provider(kind)
                .map(AgentProvider::displayName)
                .orElseGet(() -> titleCase(kind.persistedName()));
    }

    /**
     * As {@link #displayName(AgentRegistry, AgentKind)}, but honest about a
     * session whose persisted agent name this build does not recognize:
     * {@code agentKind()} is then only a placeholder ({@link AgentKind#CLAUDE}
     * -- see the state decoder), and rendering it would put the one name we
     * know to be wrong on the session that has no name at all.
     */
    static String displayName(AgentRegistry registry, ManagedAgentSession session) {
        return session.status() == SessionStatus.UNSUPPORTED_AGENT
                ? UNKNOWN_AGENT
                : displayName(registry, session.agentKind());
    }

    private static String titleCase(String persistedName) {
        return persistedName.isEmpty()
                ? persistedName
                : Character.toUpperCase(persistedName.charAt(0)) + persistedName.substring(1);
    }

    /** Text of the agent sub-tab button, e.g. {@code ✳  Codex}. */
    static String subTabLabel(AgentRegistry registry, AgentKind kind) {
        return subTabLabel(displayName(registry, kind));
    }

    /** As {@link #subTabLabel(AgentRegistry, AgentKind)}, for a name already resolved. */
    static String subTabLabel(String agentName) {
        return AGENT_GLYPH + "  " + agentName;
    }

    /** Tooltip of the agent sub-tab button, e.g. {@code Codex (⌘1)}. */
    static String subTabTooltip(AgentRegistry registry, AgentKind kind) {
        return subTabTooltip(displayName(registry, kind));
    }

    /** As {@link #subTabTooltip(AgentRegistry, AgentKind)}, for a name already resolved. */
    static String subTabTooltip(String agentName) {
        return agentName + " (⌘1)";
    }
}
