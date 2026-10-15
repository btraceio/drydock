package app.drydock.agent.api;

import java.util.List;
import java.util.Optional;

/**
 * The session kinds Drydock can manage. The {@link #persistedName()}
 * of each constant is a stable wire contract written into persisted session
 * state; never rename an existing one.
 *
 * <p>{@link #TERMINAL} is not an agentic CLI -- it is a plain shell in the
 * target checkout -- but it rides the same session machinery (picker,
 * persistence, resume, close) by answering every provider question with the
 * shell, so "start a session without an LLM" is a first-class kind rather
 * than a parallel flow.</p>
 */
public enum AgentKind {
    CLAUDE("claude"),
    CODEX("codex"),
    PI("pi"),
    TERMINAL("terminal");

    private final String persistedName;

    AgentKind(String persistedName) {
        this.persistedName = persistedName;
    }

    public String persistedName() {
        return persistedName;
    }

    public static Optional<AgentKind> fromPersisted(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (AgentKind kind : values()) {
            if (kind.persistedName.equals(value)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }

    /** Fixed order used for the availability-based global default and the picker. TERMINAL last: never the default while an agent CLI is available. */
    public static List<AgentKind> preferenceOrder() {
        return List.of(CLAUDE, CODEX, PI, TERMINAL);
    }
}
