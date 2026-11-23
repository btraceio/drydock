package app.drydock.sdlc;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Boundary between the immutable selection and a destination's own resources. Local and workspace-box
 * implementations consume the identical {@link WorkflowMaterializationRequest}. An implementation owns any
 * partial artifact until a success hands its {@link WorkflowResourceLease} to the caller.
 */
public interface WorkflowMaterializer {

    /** Never completes exceptionally for expected outcomes; failure and cancellation are distinct results. */
    CompletableFuture<Result> materialize(WorkflowMaterializationRequest request, Cancellation cancellation);

    sealed interface Result permits Success, Failure, Cancelled {
    }

    record Success(MaterializedWorkflow workflow) implements Result {
        public Success {
            Objects.requireNonNull(workflow, "workflow");
        }
    }

    record Failure(Reason reason, String message) implements Result {
        public Failure {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(message, "message");
        }
    }

    /** The cancel flag was observed; the implementation has cleaned its partial artifacts. */
    enum Cancelled implements Result {
        INSTANCE
    }

    enum Reason { UNSUPPORTED_DESTINATION, CAPABILITY_UNVERIFIED, IO_FAILURE, LIMIT_EXCEEDED }
}
