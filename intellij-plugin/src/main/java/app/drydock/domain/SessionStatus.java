package app.drydock.domain;

/**
 * Lifecycle status of a {@link ManagedAgentSession} (plan section 10.2).
 */
public enum SessionStatus {
    INACTIVE,
    STARTING,
    RUNNING,
    EXITED,
    FAILED,
    MISSING_WORKING_DIRECTORY,
    UNSUPPORTED_AGENT
}
