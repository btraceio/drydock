package app.drydock.domain;

import app.drydock.agent.api.AgentKind;

import java.util.Objects;
import java.util.Optional;

/**
 * Which agent CLI runs a session, and what that CLI calls it.
 *
 * <p>The three values only mean anything together: an {@code agentSessionId}
 * belongs to one tool's session store, and the same string means nothing to a
 * different one. {@code ResumeContext} already pairs the id and the name for
 * that reason -- either is a resume handle, and a provider takes whichever it
 * has.</p>
 *
 * <p>Deliberately NOT enforcing "at most one of id/name": both can legitimately
 * be set. Claude is launched with {@code -n <name>} and reports its own id
 * afterwards, so a session that has run holds both.</p>
 */
public record AgentBinding(AgentKind kind, Optional<String> sessionId, Optional<String> sessionName) {

    public AgentBinding {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(sessionName, "sessionName");
    }

    /** A session that has not yet been launched: the agent is chosen, nothing is bound. */
    public static AgentBinding unlaunched(AgentKind kind) {
        return new AgentBinding(kind, Optional.empty(), Optional.empty());
    }

    public AgentBinding withSessionId(Optional<String> newSessionId) {
        return new AgentBinding(kind, newSessionId, sessionName);
    }

    public AgentBinding withSessionName(Optional<String> newSessionName) {
        return new AgentBinding(kind, sessionId, newSessionName);
    }

    public AgentBinding withKind(AgentKind newKind) {
        return new AgentBinding(newKind, sessionId, sessionName);
    }
}
