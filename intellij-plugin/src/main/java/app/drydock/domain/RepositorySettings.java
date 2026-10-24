package app.drydock.domain;

import app.drydock.agent.api.AgentKind;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Per-repository preferences (plan section 10.1).
 *
 * <p>Holds the last agent kind used in this repository, so the UI can default
 * a new session's agent picker to whatever was used last time, and the last
 * starting model per agent kind ({@code lastUsedModels}), so the model picker
 * pre-fills with whatever launched the previous session on that harness.
 * The model map is keyed by agent kind because the harnesses' model spaces
 * are disjoint (a claude alias means nothing to pi), so one shared "last
 * model" would silently hand a pi model id to claude.</p>
 */
public record RepositorySettings(Optional<AgentKind> lastUsedAgent, Map<AgentKind, String> lastUsedModels) {

    public static final RepositorySettings DEFAULT = new RepositorySettings(Optional.empty(), Map.of());

    public RepositorySettings {
        Objects.requireNonNull(lastUsedAgent, "lastUsedAgent");
        Objects.requireNonNull(lastUsedModels, "lastUsedModels");
        lastUsedModels = Map.copyOf(lastUsedModels);
    }

    public RepositorySettings(Optional<AgentKind> lastUsedAgent) {
        this(lastUsedAgent, Map.of());
    }

    public RepositorySettings withLastUsedAgent(AgentKind agent) {
        return new RepositorySettings(Optional.of(agent), lastUsedModels);
    }

    /** The last starting model launched with on {@code agent} in this repository, if one was ever picked. */
    public Optional<String> lastUsedModel(AgentKind agent) {
        return Optional.ofNullable(lastUsedModels.get(agent));
    }

    /**
     * Records {@code model} as {@code agent}'s last starting model; an empty
     * or blank {@code model} removes the entry (the human went back to the harness's
     * own default, and the picker should say so next time).
     */
    public RepositorySettings withLastUsedModel(AgentKind agent, Optional<String> model) {
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(model, "model");
        Optional<String> normalized = model.filter(m -> !m.isBlank());
        var updated = new java.util.HashMap<>(lastUsedModels);
        if (normalized.isPresent()) {
            updated.put(agent, normalized.get());
        } else {
            updated.remove(agent);
        }
        return new RepositorySettings(lastUsedAgent, updated);
    }
}
