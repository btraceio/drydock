package app.drydock.agent.api;

import app.drydock.domain.SshRemote;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Inputs a provider needs to build a create command. {@code sessionId} is the
 * app-generated id for {@code PRESET} providers; {@code DISCOVERED} providers
 * ignore it. {@code remote}, when present, means launch over SSH.
 *
 * <p>{@code mcp} is the access minted for this launch when drydock's MCP
 * server is reachable, empty otherwise. Each provider renders it the way
 * its CLI wants (see {@code AgentProvider.mcpDelivery}); a provider with
 * {@link McpDelivery#NONE} never receives one.</p>
 *
 * <p>{@code evalMode} is true when this session runs on the "eval" account:
 * the provider arranges for its CLI to send an {@code x-target-account: eval}
 * request header. How that is done is provider-specific (env var or
 * extension); the flag itself only travels through here. See {@code
 * ManagedAgentSession.evalMode}. The preview path ({@code
 * AgentRegistry.previewCreateCommand}) also sets it to false, so a provider
 * must never write files or mint state as a side effect of reading it inside
 * {@code buildCreateCommand}. Injection is implemented for Pi; Claude and
 * Codex are marked but not rerouted (see their providers for why).</p>
 *
 * <p>{@code readOnly} asks the CLI not to edit files: it comes from the
 * session's {@code SessionMode} and each provider renders it with its own
 * flag. A provider whose CLI has no read-only mode <em>ignores</em> it rather
 * than refusing the launch — the restriction is best-effort by construction,
 * and the UI says so for that agent instead of pretending the flag exists.
 * Nothing here enforces it on drydock's side.</p>
 *
 * <p>{@code initialPrompt} is the task the user gave when creating the
 * session — the first message the agent should act on. A provider whose CLI
 * accepts a prompt on its command line renders it there; one that does not
 * (or whose contract has not been verified against the installed binary)
 * ignores it, and the user types the task into the console instead. It is a
 * create-only input: resuming a conversation never re-sends it.</p>
 */
public record CreateContext(String displayName, String sessionId, Path workingDirectory,
                            Optional<SshRemote> remote, Optional<McpAccess> mcp, boolean evalMode,
                            boolean readOnly, Optional<String> initialPrompt,
                            List<StdioMcpServer> stdioMcpServers) {
    public CreateContext {
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(remote, "remote");
        Objects.requireNonNull(mcp, "mcp");
        Objects.requireNonNull(initialPrompt, "initialPrompt");
        stdioMcpServers = List.copyOf(Objects.requireNonNull(stdioMcpServers, "stdioMcpServers"));
        // A blank task is no task: rendering it would launch the agent with
        // an empty first message.
        initialPrompt = initialPrompt.filter(prompt -> !prompt.isBlank());
    }

    public CreateContext(String displayName, String sessionId, Path workingDirectory,
                         Optional<SshRemote> remote, Optional<McpAccess> mcp, boolean evalMode,
                         boolean readOnly, Optional<String> initialPrompt) {
        this(displayName, sessionId, workingDirectory, remote, mcp, evalMode, readOnly, initialPrompt, List.of());
    }

    /** This context, with {@code servers} as the stdio MCP servers the agent starts. */
    public CreateContext withStdioMcpServers(List<StdioMcpServer> servers) {
        return new CreateContext(displayName, sessionId, workingDirectory, remote, mcp, evalMode, readOnly,
                initialPrompt, servers);
    }

    /** As the canonical constructor with no initial prompt. */
    public CreateContext(String displayName, String sessionId, Path workingDirectory,
                         Optional<SshRemote> remote, Optional<McpAccess> mcp, boolean evalMode,
                         boolean readOnly) {
        this(displayName, sessionId, workingDirectory, remote, mcp, evalMode, readOnly, Optional.empty());
    }

    /** As the canonical constructor with {@code readOnly = false} (a writing session). */
    public CreateContext(String displayName, String sessionId, Path workingDirectory,
                         Optional<SshRemote> remote, Optional<McpAccess> mcp, boolean evalMode) {
        this(displayName, sessionId, workingDirectory, remote, mcp, evalMode, false);
    }

    /** As the canonical constructor with {@code evalMode = false} (a non-eval session). */
    public CreateContext(String displayName, String sessionId, Path workingDirectory,
                         Optional<SshRemote> remote, Optional<McpAccess> mcp) {
        this(displayName, sessionId, workingDirectory, remote, mcp, false, false);
    }
}
