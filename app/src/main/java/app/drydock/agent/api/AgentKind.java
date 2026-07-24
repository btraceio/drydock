package app.drydock.agent.api;

import java.util.List;
import java.util.Optional;

/**
 * The agentic coding CLIs Drydock can manage. The {@link #persistedName()}
 * of each constant is a stable wire contract written into persisted session
 * state; never rename an existing one.
 */
public enum AgentKind {
    CLAUDE("claude"),
    CODEX("codex"),
    PI("pi");

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

    /** Fixed order used for the availability-based global default and the picker. */
    public static List<AgentKind> preferenceOrder() {
        return List.of(CLAUDE, CODEX, PI);
    }
}
