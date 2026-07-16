package app.cpm.domain;

/**
 * Lifecycle status of a {@link ManagedClaudeSession} (plan section 10.2).
 */
public enum SessionStatus {
    INACTIVE,
    STARTING,
    RUNNING,
    EXITED,
    FAILED,
    MISSING_WORKING_DIRECTORY
}
