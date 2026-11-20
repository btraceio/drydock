package app.drydock.intellij.session;

import app.drydock.agent.api.CreateContext;
import app.drydock.agent.api.LaunchPlan;
import app.drydock.agent.api.LaunchResources;
import app.drydock.agent.api.LocalWorkflowAccess;
import app.drydock.agent.api.McpAccess;
import app.drydock.agent.api.ResumeContext;
import app.drydock.agent.api.StdioMcpServer;
import app.drydock.agent.providers.AgentCommands;
import app.drydock.agent.spi.AgentProvider;
import app.drydock.delayedtasks.TaskLaunchResources;
import app.drydock.domain.ManagedSessionId;
import app.drydock.intellij.review.SessionMcpAccess;
import app.drydock.sdlc.SessionWorkflowResourceOwner;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * The local launch's resource and context assembly, split out of
 * {@link DrydockSessionService} unchanged: the Drydock MCP access for a
 * launch, the task resources it carries, the IDE's stdio MCP server routed
 * to the launch's worktree, and the revocation of access a plan does not
 * use. Blocking: callers run it on the service's executor, never the EDT.
 */
final class LocalLaunchPreparation {

    private static final Logger LOG = System.getLogger(LocalLaunchPreparation.class.getName());

    private final TaskLaunchResources taskLaunchResources;
    private final SessionMcpAccess mcpAccess;
    private final Function<Path, List<StdioMcpServer>> stdioMcpServers;
    private volatile SessionWorkflowResourceOwner workflowOwner;
    private volatile Function<ManagedSessionId, Optional<LocalWorkflowAccess>> workflowAccess = id -> Optional.empty();

    /** A null resolver is every pre-T29 caller's path: nothing resolves. */
    LocalLaunchPreparation(TaskLaunchResources taskLaunchResources, SessionMcpAccess mcpAccess,
            Function<Path, List<StdioMcpServer>> stdioMcpServers) {
        this.taskLaunchResources = taskLaunchResources;
        this.mcpAccess = Objects.requireNonNull(mcpAccess, "mcpAccess");
        this.stdioMcpServers = Objects.requireNonNull(stdioMcpServers, "stdioMcpServers");
    }

    /**
     * The resources a launch carries (T29a): the session's retained receipt
     * directories plus the installed guidance when this launch's MCP access
     * reaches a task-serving surface. An unresolvable receipt directory is a
     * visible refusal, never a silent empty launch.
     */
    Optional<LaunchResources> resourcesFor(ManagedSessionId id, Optional<McpAccess> access) {
        TaskLaunchResources resolver = taskLaunchResources;
        return resolver == null ? Optional.empty() : resolver.resolve(id, access);
    }

    /**
     * The Drydock MCP access for a launch of {@code id}, or empty when the
     * access service declines or throws. A failure is logged without the
     * credentials and never fails the launch.
     */
    Optional<McpAccess> mcpAccessFor(ManagedSessionId id, AgentProvider provider) {
        try {
            return mcpAccess.accessFor(id, provider.mcpDelivery());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, () -> "Launching " + id + " without Drydock MCP access: " + e);
            return Optional.empty();
        }
    }

    /**
     * Revokes {@code id}'s MCP access when {@code plan} does not carry its
     * credential file (every provider puts it into the command as
     * {@link AgentCommands#shellQuote}, so its absence means the agent never
     * reaches the server).
     */
    void releaseIfUnused(ManagedSessionId id, Optional<McpAccess> access, LaunchPlan plan) {
        Optional<Path> file = access.flatMap(McpAccess::credentialFile);
        if (file.isPresent() && !plan.command().contains(AgentCommands.shellQuote(file.get().toString()))) {
            LOG.log(Level.INFO, () -> "Launch of " + id + " does not use Drydock MCP access; releasing it");
            release(id);
        }
    }

    /** Revokes {@code id}'s MCP access; a failure is logged and swallowed. */
    void release(ManagedSessionId id) {
        try {
            mcpAccess.release(id);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, () -> "Releasing Drydock MCP access for " + id + " failed: " + e);
        }
    }

    List<StdioMcpServer> stdioMcpServersFor(Path workingDirectory) {
        try {
            return stdioMcpServers.apply(workingDirectory);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, () -> "Launching without the IDE's MCP server for " + workingDirectory
                    + ": " + e);
            return List.of();
        }
    }

    /**
     * Installs the local workflow-resource seam (T35): the one retained-resource owner and the resolver of a
     * session's typed access. Unset, every launch is exactly as before.
     */
    void workflowResources(SessionWorkflowResourceOwner owner,
            Function<ManagedSessionId, Optional<LocalWorkflowAccess>> access) {
        this.workflowAccess = Objects.requireNonNull(access, "access");
        this.workflowOwner = Objects.requireNonNull(owner, "owner");
    }

    /** {@code ctx} carrying the session's typed workflow access, kept apart from receipts and guidance. */
    CreateContext withWorkflow(CreateContext ctx, ManagedSessionId id) {
        return access(id).map(ctx::withWorkflowAccess).orElse(ctx);
    }

    /**
     * {@code ctx} carrying the session's typed workflow access. An access naming staged bundles needs those
     * bundles retained: a missing one throws, so the resume is refused visibly instead of launching the agent
     * against a guessed path. An eval-namespace-only access (an empty in-session namespace) needs none.
     */
    ResumeContext withWorkflow(ResumeContext ctx, ManagedSessionId id) {
        Optional<LocalWorkflowAccess> access = access(id);
        if (access.isPresent() && !access.get().readRoots().isEmpty()) {
            workflowOwner.requireRetained(id.value().toString());
        }
        return access.map(ctx::withWorkflowAccess).orElse(ctx);
    }

    private Optional<LocalWorkflowAccess> access(ManagedSessionId id) {
        return workflowAccess.apply(id);
    }
}
