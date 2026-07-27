package app.drydock.agent.api;

import app.drydock.domain.SshRemote;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Inputs a provider needs to build a resume command.
 *
 * <p>{@code mcpConfig} is the per-session MCP config file when one was minted
 * for this launch, empty otherwise; providers that do not support it (see
 * {@code AgentProvider.supportsMcpConfig}) ignore it.</p>
 */
public record ResumeContext(Optional<String> agentSessionId, Optional<String> agentSessionName,
                            Path workingDirectory, Optional<SshRemote> remote, Optional<Path> mcpConfig) {
    public ResumeContext {
        Objects.requireNonNull(agentSessionId, "agentSessionId");
        Objects.requireNonNull(agentSessionName, "agentSessionName");
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(remote, "remote");
        Objects.requireNonNull(mcpConfig, "mcpConfig");
    }
}
