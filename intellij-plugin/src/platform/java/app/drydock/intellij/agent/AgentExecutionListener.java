package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;

import com.intellij.util.messages.Topic;

/**
 * Project-level notification that an agent execution started or ended.
 *
 * <p>Two tiers listen to this for different reasons and must not be chained
 * through each other: the session service persists the new status, and each
 * open console editor swaps its body and repaints its strip. A direct
 * callback would force one to depend on the other; the project's message bus
 * keeps them independent and keeps the execution service free of either.</p>
 *
 * <p>Project-scoped rather than application-scoped because an execution
 * belongs to exactly one window. Subscribers are called on whichever thread
 * the platform delivered the process event on — never assume the EDT.</p>
 */
public interface AgentExecutionListener {

    Topic<AgentExecutionListener> TOPIC =
            Topic.create("Drydock agent executions", AgentExecutionListener.class);

    /** A process is now live for {@code sessionId} in this window. */
    default void executionStarted(ManagedSessionId sessionId) {
    }

    /**
     * The session's process ended.
     *
     * @param stopRequested {@code true} when drydock asked for it (Stop,
     *                      window close, quit), {@code false} when the agent
     *                      exited on its own. This is the whole difference
     *                      between the two idle statuses: only a natural exit
     *                      is {@code EXITED}.
     * @param exitCode      the exit code, or {@code null} when the platform
     *                      reported none
     */
    default void executionTerminated(ManagedSessionId sessionId, boolean stopRequested, Integer exitCode) {
    }
}
