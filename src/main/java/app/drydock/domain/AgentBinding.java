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
 *
 * <p>{@code mode} joins them because it is the same kind of fact: it decides
 * which flag the CLI is launched with, so it belongs with the CLI the flag
 * goes to rather than widening {@code ManagedAgentSession} a fifteenth
 * component. It is launch input, never something the agent reports back --
 * nothing here ever rewrites it from process output.</p>
 */
public record AgentBinding(AgentKind kind, Optional<String> sessionId, Optional<String> sessionName,
                           SessionMode mode) {

    public AgentBinding {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(sessionName, "sessionName");
        Objects.requireNonNull(mode, "mode");
    }

    /**
     * As the canonical constructor with {@code mode = WRITES}; for callers
     * (and tests) that predate read-only sessions, for which writing was the
     * only mode there was.
     */
    public AgentBinding(AgentKind kind, Optional<String> sessionId, Optional<String> sessionName) {
        this(kind, sessionId, sessionName, SessionMode.WRITES);
    }

    /** A session that has not yet been launched: the agent is chosen, nothing is bound. */
    public static AgentBinding unlaunched(AgentKind kind) {
        return new AgentBinding(kind, Optional.empty(), Optional.empty(), SessionMode.WRITES);
    }

    /** An unlaunched binding in {@code mode} (the new-session dialog's two choices). */
    public static AgentBinding unlaunched(AgentKind kind, SessionMode mode) {
        return new AgentBinding(kind, Optional.empty(), Optional.empty(), mode);
    }

    public AgentBinding withSessionId(Optional<String> newSessionId) {
        return new AgentBinding(kind, newSessionId, sessionName, mode);
    }

    public AgentBinding withSessionName(Optional<String> newSessionName) {
        return new AgentBinding(kind, sessionId, newSessionName, mode);
    }

    public AgentBinding withKind(AgentKind newKind) {
        return new AgentBinding(newKind, sessionId, sessionName, mode);
    }

    public AgentBinding withMode(SessionMode newMode) {
        return new AgentBinding(kind, sessionId, sessionName, newMode);
    }
}
