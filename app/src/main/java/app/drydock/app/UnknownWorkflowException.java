package app.drydock.app;

import app.drydock.domain.WorkflowId;

/**
 * A {@link SessionManager} operation was asked to act on a {@link
 * WorkflowId} that is not present in the persisted {@code
 * ApplicationState}. Kept as its own specific type rather than a generic
 * {@code IllegalArgumentException} or {@code NoSuchElementException} per
 * plan section 20 ("never a generic 'something went wrong'").
 */
public final class UnknownWorkflowException extends RuntimeException {

    public UnknownWorkflowException(WorkflowId workflowId) {
        super("No workflow with id " + workflowId);
    }
}
