package app.drydock.domain;

/**
 * Lifecycle status of a {@link Workflow}.
 *
 * <p>{@link #OPEN} workflows are shown in the default rail; {@link #ARCHIVED}
 * workflows are hidden unless a "show archived" filter is on. Archiving is
 * reversible and never deletes member sessions.</p>
 */
public enum WorkflowStatus {
    OPEN,
    ARCHIVED
}
