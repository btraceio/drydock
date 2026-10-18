package app.drydock.domain;

import java.util.Optional;

/**
 * Whether a session's agent is allowed to change the working tree.
 *
 * <p>A worktree holds at most one {@link #WRITES} session by default; the
 * rest are {@link #READ_ONLY} companions reading the same tree. Two writers
 * in one tree can overwrite each other, which is why starting a second one
 * takes an explicit confirmation rather than being silently allowed.</p>
 *
 * <p>Enforcement is the agent CLI's, not drydock's: the mode travels into
 * {@code CreateContext}/{@code ResumeContext} and each provider renders it
 * with its own flag (Claude plan mode, the Codex read-only sandbox). A
 * provider whose CLI has no read-only mode runs the agent unrestricted and
 * says so in the UI — {@code READ_ONLY} is then a statement of intent, not
 * a guarantee, and must never be presented as one.</p>
 *
 * <p>A read-only session cannot switch mode in place: the agent is already
 * running under the flag. Continuing the same conversation with writes means
 * resuming it as a new, writing session.</p>
 */
public enum SessionMode {

    /** The agent may edit files. The default for a session in its own worktree. */
    WRITES,

    /** The agent is asked not to edit files, as far as its CLI can be told. */
    READ_ONLY;

    /**
     * The persisted name — a wire contract, like {@code AgentKind}'s: never
     * rename an existing constant.
     */
    public String persistedName() {
        return name();
    }

    /**
     * The mode persisted under {@code value}, or empty when the name is not
     * one this build knows. Callers decode leniently: an unrecognized mode
     * must not cost the session row.
     */
    public static Optional<SessionMode> fromPersisted(String value) {
        for (SessionMode mode : values()) {
            if (mode.persistedName().equals(value)) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }
}
