package app.drydock.ui.model;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionStatusFacet;
import java.util.Set;

/**
 * The sidebar's status/harness filter: which session rows survive, expressed
 * over domain types only so it can be reasoned about (and tested) without a
 * JavaFX toolkit.
 *
 * <p>An empty set is <em>no constraint</em>, not "match nothing" -- an
 * untouched filter shows every session. Facets OR within an axis and AND
 * across axes, and a fully selected axis is treated as unconstrained.
 */
public record SessionFilter(Set<SessionStatusFacet> statuses, Set<AgentKind> agents) {

    public SessionFilter {
        statuses = Set.copyOf(statuses);
        agents = Set.copyOf(agents);
    }

    public static SessionFilter none() {
        return new SessionFilter(Set.of(), Set.of());
    }

    /** Whether either axis constrains anything. */
    public boolean isActive() {
        return constrains(statuses, SessionStatusFacet.values().length)
                || constrains(agents, AgentKind.values().length);
    }

    public boolean matches(ManagedAgentSession session) {
        return matchesStatus(session) && matchesAgent(session);
    }

    private boolean matchesStatus(ManagedAgentSession session) {
        return !constrains(statuses, SessionStatusFacet.values().length)
                || statuses.contains(SessionStatusFacet.of(session.status()));
    }

    private boolean matchesAgent(ManagedAgentSession session) {
        if (!constrains(agents, AgentKind.values().length)) {
            return true;
        }
        // A session this build cannot identify has only a placeholder kind
        // (see the state decoder), so it belongs to no harness chip. `error`
        // is how it stays reachable.
        return session.status() != SessionStatus.UNSUPPORTED_AGENT
                && agents.contains(session.agentKind());
    }

    /** A set constrains unless it is empty or holds every value of its axis. */
    private static boolean constrains(Set<?> selected, int axisSize) {
        return !selected.isEmpty() && selected.size() < axisSize;
    }
}
