package app.drydock.agent.api;

import app.drydock.domain.SshRemote;

import java.nio.file.Path;
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
 */
public record CreateContext(String displayName, String sessionId, Path workingDirectory,
                            Optional<SshRemote> remote, Optional<McpAccess> mcp, boolean evalMode) {
    public CreateContext {
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(remote, "remote");
        Objects.requireNonNull(mcp, "mcp");
    }

    /** As the canonical constructor with {@code evalMode = false} (a non-eval session). */
    public CreateContext(String displayName, String sessionId, Path workingDirectory,
                         Optional<SshRemote> remote, Optional<McpAccess> mcp) {
        this(displayName, sessionId, workingDirectory, remote, mcp, false);
    }
}
