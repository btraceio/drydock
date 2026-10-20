package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;

import com.intellij.openapi.project.Project;
import com.intellij.util.messages.Topic;

/**
 * Application-level notification that an agent execution started, ended, or
 * moved between windows.
 *
 * <p>Two tiers listen to this for different reasons and must not be chained
 * through each other: the session service persists the new status, and each
 * open console editor swaps its body and repaints its strip. A direct
 * callback would force one to depend on the other; the project's message bus
 * keeps them independent and keeps the execution service free of either.</p>
 *
 * <p>Application-scoped because an execution belongs to the IDE, not to a
 * window: it can be shown in any window, or in none (see
 * {@link AgentExecutionRegistry}). No broadcast to project buses — a
 * subscriber listens on the application bus and filters by session. The
 * persistence of an end does <em>not</em> come through here: the registry
 * tells the session service that started the agent directly, once.
 * Subscribers are called on whichever thread published — never assume the
 * EDT.</p>
 */
public interface AgentExecutionListener {

    Topic<AgentExecutionListener> TOPIC =
            Topic.create("Drydock agent executions", AgentExecutionListener.class, Topic.BroadcastDirection.NONE);

    /** A process is now live for {@code sessionId}. */
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

    /** {@code sessionId} is now shown in {@code to}, and in no other window. EDT. */
    default void executionShown(ManagedSessionId sessionId, Project to) {
    }

    /** No window shows {@code sessionId} any more; its agent may still run. EDT. */
    default void executionReleased(ManagedSessionId sessionId) {
    }
}
