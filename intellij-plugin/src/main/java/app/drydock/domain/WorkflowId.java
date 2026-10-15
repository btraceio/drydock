package app.drydock.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of a {@link Workflow}, mirroring {@link ManagedSessionId} and
 * {@link RepositoryId}.
 *
 * <p>Stable for the lifetime of the workflow, app-assigned, and deliberately
 * distinct from any session or repository identifier.</p>
 */
public record WorkflowId(UUID value) {

    public WorkflowId {
        Objects.requireNonNull(value, "value");
    }

    public static WorkflowId newId() {
        return new WorkflowId(UUID.randomUUID());
    }

    public static WorkflowId of(String uuidText) {
        return new WorkflowId(UUID.fromString(uuidText));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
