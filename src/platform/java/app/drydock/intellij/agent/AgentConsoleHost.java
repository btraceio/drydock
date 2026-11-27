package app.drydock.intellij.agent;

import app.drydock.agent.api.LaunchPlan;
import app.drydock.domain.ManagedSessionId;
import app.drydock.editorqa.EditorAskLane;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

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

    /**
     * Makes the session's terminal display-only for free-form input (the
     * editor Q&amp;A row); call before {@link #startConsole}. The guard lives in
     * the app-level registry where the connector is bound, so every host
     * gets it.
     */
    default void markDisplayOnly(ManagedSessionId sessionId) {
        AgentExecutionRegistry.getInstance().markDisplayOnly(sessionId);
    }

    /**
     * The one programmatic write into a display-only terminal: bound to the
     * lane's reservation and the specific execution, off the EDT; see
     * {@link AgentExecutionRegistry#submitAskPrompt}.
     */
    default PromptOutcome submitAskPrompt(ManagedSessionId sessionId, String prompt,
                                          EditorAskLane.Reservation reservation,
                                          Predicate<EditorAskLane.Reservation> current) {
        return AgentExecutionRegistry.getInstance().submitAskPrompt(sessionId, prompt, reservation, current);
    }
}
