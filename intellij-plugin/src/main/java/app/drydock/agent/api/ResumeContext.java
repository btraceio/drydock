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
 *
 * <p>{@code evalMode} is as documented on {@link CreateContext#evalMode()}.
 * For a resume it comes from the persisted session, so an eval session
 * reopened stays on the eval account.</p>
 *
 * <p>{@code readOnly} is as documented on {@link CreateContext#readOnly()}.
 * It comes from the persisted session's mode, so resuming a read-only
 * session re-applies the same restriction: a session cannot gain write
 * rights by being resumed.</p>
 */
public record ResumeContext(Optional<String> agentSessionId, Optional<String> agentSessionName,
                            Path workingDirectory, Optional<SshRemote> remote, Optional<McpAccess> mcp,
                            boolean evalMode, boolean readOnly) {
    public ResumeContext {
        Objects.requireNonNull(agentSessionId, "agentSessionId");
        Objects.requireNonNull(agentSessionName, "agentSessionName");
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(remote, "remote");
        Objects.requireNonNull(mcp, "mcp");
    }

    /** As the canonical constructor with {@code readOnly = false} (a writing resume). */
    public ResumeContext(Optional<String> agentSessionId, Optional<String> agentSessionName,
                         Path workingDirectory, Optional<SshRemote> remote, Optional<McpAccess> mcp,
                         boolean evalMode) {
        this(agentSessionId, agentSessionName, workingDirectory, remote, mcp, evalMode, false);
    }

    /** As the canonical constructor with {@code evalMode = false} (a non-eval resume). */
    public ResumeContext(Optional<String> agentSessionId, Optional<String> agentSessionName,
                         Path workingDirectory, Optional<SshRemote> remote, Optional<McpAccess> mcp) {
        this(agentSessionId, agentSessionName, workingDirectory, remote, mcp, false, false);
    }
}
