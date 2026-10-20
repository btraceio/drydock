package app.drydock.domain;

import app.drydock.agent.api.AgentKind;

import java.util.Objects;
import java.util.Optional;

/**
 * Per-repository preferences (plan section 10.1).
 *
 * <p>Currently holds only the last agent kind used in this repository, so
 * the UI can default a new session's agent picker to whatever was used
 * last time (a later milestone). Kept as a record with a stable {@code
 * settings} slot so future preferences can be added without a schema
 * migration.</p>
 */
public record RepositorySettings(Optional<AgentKind> lastUsedAgent) {

    public static final RepositorySettings DEFAULT = new RepositorySettings(Optional.empty());

    public RepositorySettings {
        Objects.requireNonNull(lastUsedAgent, "lastUsedAgent");
    }

    public RepositorySettings withLastUsedAgent(AgentKind agent) {
        return new RepositorySettings(Optional.of(agent));
    }
}
