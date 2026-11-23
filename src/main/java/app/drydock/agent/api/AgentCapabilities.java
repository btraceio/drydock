package app.drydock.agent.api;

import java.util.Objects;

/**
 * Generic, provider-agnostic capabilities the registry and UI care about.
 * Provider-internal flag detail (e.g. Claude's {@code -n}/{@code --session-id})
 * stays inside the provider and is not exposed here.
 */
public record AgentCapabilities(boolean supportsRemote, boolean supportsResume, String version) {
    public AgentCapabilities {
        Objects.requireNonNull(version, "version");
    }
}
