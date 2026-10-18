package app.drydock.intellij.agent;

import app.drydock.agent.api.LaunchPlan;
import app.drydock.domain.ManagedSessionId;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * What the session orchestrator needs from a window's execution tier, and
 * nothing more: start one agent, stop one agent, drop one agent.
 *
 * <p>The orchestrator persists statuses and builds commands; it must stay
 * testable without an IDE, and {@link AgentExecutionService} cannot be — it
 * spawns PTYs and builds Swing consoles. This interface is the seam between
 * them, which is also why it carries no console, process handler, or editor
 * type: the orchestrator has no business holding any of those.</p>
 */
public interface AgentConsoleHost {

    /**
     * Starts exactly one execution for {@code sessionId}. The future completes
     * when the console is attached and the process has been started, or fails
     * if either step did — in which case nothing is left running.
     */
    CompletableFuture<Void> startConsole(ManagedSessionId sessionId, Path workingDirectory, LaunchPlan plan);

    /** Stops the session's process, if this window has one running. */
    void stopConsole(ManagedSessionId sessionId);

    /** Stops and discards the session's execution and console entirely. */
    void forgetConsole(ManagedSessionId sessionId);
}
