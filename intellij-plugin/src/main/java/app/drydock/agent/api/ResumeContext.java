package app.drydock.agent.api;

import app.drydock.domain.SshRemote;

import java.nio.file.Path;
import java.util.List;
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
 *
 * <p>{@code resumePrompt} is the fixed re-orientation prompt typed into a
 * resumed task session — the session-level counterpart of {@link
 * CreateContext#initialPrompt()}, earned by {@code
 * ManagedAgentSession.startedWithTask}. A provider whose CLI accepts a
 * positional prompt on resume (verified against the installed binary)
 * renders it after the pinned session id; the interactive picker paths
 * ({@code --resume} with no id, {@code codex resume} with no id) never
 * carry it — nobody has picked a conversation yet, so there is nothing to
 * re-orient.</p>
 */
public record ResumeContext(Optional<String> agentSessionId, Optional<String> agentSessionName,
                            Path workingDirectory, Optional<SshRemote> remote, Optional<McpAccess> mcp,
                            boolean evalMode, boolean readOnly, List<StdioMcpServer> stdioMcpServers,
                            Optional<String> resumePrompt) {
    public ResumeContext {
        Objects.requireNonNull(agentSessionId, "agentSessionId");
        Objects.requireNonNull(agentSessionName, "agentSessionName");
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(remote, "remote");
        Objects.requireNonNull(mcp, "mcp");
        stdioMcpServers = List.copyOf(Objects.requireNonNull(stdioMcpServers, "stdioMcpServers"));
        Objects.requireNonNull(resumePrompt, "resumePrompt");
        // A blank prompt is no prompt: rendering it would submit an empty
        // first message to the resumed conversation.
        resumePrompt = resumePrompt.filter(prompt -> !prompt.isBlank());
    }

    public ResumeContext(Optional<String> agentSessionId, Optional<String> agentSessionName,
                         Path workingDirectory, Optional<SshRemote> remote, Optional<McpAccess> mcp,
                         boolean evalMode, boolean readOnly, List<StdioMcpServer> stdioMcpServers) {
        this(agentSessionId, agentSessionName, workingDirectory, remote, mcp, evalMode, readOnly,
                stdioMcpServers, Optional.empty());
    }

    public ResumeContext(Optional<String> agentSessionId, Optional<String> agentSessionName,
                         Path workingDirectory, Optional<SshRemote> remote, Optional<McpAccess> mcp,
                         boolean evalMode, boolean readOnly) {
        this(agentSessionId, agentSessionName, workingDirectory, remote, mcp, evalMode, readOnly, List.of());
    }

    /** This context, with {@code servers} as the stdio MCP servers the agent starts. */
    public ResumeContext withStdioMcpServers(List<StdioMcpServer> servers) {
        return new ResumeContext(agentSessionId, agentSessionName, workingDirectory, remote, mcp, evalMode,
                readOnly, servers, resumePrompt);
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
