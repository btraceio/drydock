package app.drydock.sdlc;

import app.drydock.domain.SessionMode;
import app.drydock.sdlc.WorkflowMaterializationRequest.Destination;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Destination-scoped result of materializing one {@link SelectedWorkflow}: identity of the invocation and
 * snapshot, references typed for the destination, and the cleanup lease. It has no receipt, task-guidance or
 * credential field, and a remote reference is path text that cannot be coerced to a host {@link Path}.
 */
public record MaterializedWorkflow(
        String invocationId,
        long generation,
        String snapshotDigest,
        Destination destination,
        WorkflowTarget target,
        SessionMode mode,
        List<Reference> references,
        WorkflowResourceLease lease) {

    public MaterializedWorkflow {
        Objects.requireNonNull(invocationId, "invocationId");
        Objects.requireNonNull(snapshotDigest, "snapshotDigest");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(mode, "mode");
        references = List.copyOf(references);
        Objects.requireNonNull(lease, "lease");
        boolean remote = destination == Destination.WORKSPACE_BOX;
        for (Reference r : references) {
            if (remote != r instanceof RemoteReference) {
                throw new IllegalArgumentException("reference does not match destination " + destination);
            }
        }
    }

    public enum Role { RESOURCE, SOURCE, INSTRUCTION_FILE }

    public sealed interface Reference permits LocalReference, RemoteReference {
        Role role();
    }

    public record LocalReference(Role role, Path path) implements Reference {
        public LocalReference {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(path, "path");
        }
    }

    /** A path on the box, as text only. */
    public record RemoteReference(Role role, String remotePath) implements Reference {
        public RemoteReference {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(remotePath, "remotePath");
        }
    }
}
