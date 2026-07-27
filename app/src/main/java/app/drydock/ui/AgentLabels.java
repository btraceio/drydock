package app.drydock.ui;

import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.agent.spi.AgentProvider;

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

    /**
     * The agent's display name, falling back to its persisted name when no
     * provider for {@code kind} is registered (a session persisted with an
     * agent this build didn't discover still has to name it).
     */
    static String displayName(AgentRegistry registry, AgentKind kind) {
        return registry.provider(kind).map(AgentProvider::displayName).orElseGet(kind::persistedName);
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
