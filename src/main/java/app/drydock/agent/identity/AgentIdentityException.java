package app.drydock.agent.identity;

/**
 * The agent-identity credential pipeline failed. Always carries a
 * user-facing message that names what ran and what was wrong with it --
 * never collapsed to a generic "identity setup failed", because the user
 * authored the failing command and has to fix it.
 *
 * <p>Every failure here is a hard, loud launch failure: a session whose
 * identity was requested but cannot be minted must not launch under the
 * human's own identity by accident.</p>
 */
public final class AgentIdentityException extends IllegalStateException {

    public AgentIdentityException(String message) {
        super(message);
    }

    public AgentIdentityException(String message, Throwable cause) {
        super(message, cause);
    }
}
