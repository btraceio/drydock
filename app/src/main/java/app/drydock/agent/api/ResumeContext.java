package app.drydock.agent.api;

import app.drydock.domain.SshRemote;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Inputs a provider needs to build a resume command.
 *
 * <p>{@code mcp} is the access minted for this launch when drydock's MCP
 * server is reachable, empty otherwise. Each provider renders it the way
 * its CLI wants (see {@code AgentProvider.mcpDelivery}); a provider with
 * {@link McpDelivery#NONE} never receives one.</p>
 */
public record ResumeContext(Optional<String> agentSessionId, Optional<String> agentSessionName,
                            Path workingDirectory, Optional<SshRemote> remote, Optional<McpAccess> mcp) {
    public ResumeContext {
        Objects.requireNonNull(agentSessionId, "agentSessionId");
        Objects.requireNonNull(agentSessionName, "agentSessionName");
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(remote, "remote");
        Objects.requireNonNull(mcp, "mcp");
    }
}
