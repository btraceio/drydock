package app.drydock.sdlc;

import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.SessionMode;
import app.drydock.review.ReviewIntent;
import app.drydock.review.tour.ReviewTour;

import java.util.Objects;
import java.util.Optional;

/**
 * The one immutable input every materializer consumes, whatever its destination. It carries the captured
 * {@link SelectedWorkflow} (never a config or checkout path to re-read). It names the session identity but
 * grants no authority.
 */
public record WorkflowMaterializationRequest(
        String invocationId,
        long generation,
        SelectedWorkflow workflow,
        Destination destination,
        SessionMode mode,
        Optional<ReviewContext> review) {

    public WorkflowMaterializationRequest {
        Objects.requireNonNull(invocationId, "invocationId");
        if (invocationId.isBlank()) {
            throw new IllegalArgumentException("invocationId must not be blank");
        }
        Objects.requireNonNull(workflow, "workflow");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(review, "review");
    }

    /** Where the materialized references will be consumed. */
    public enum Destination { LOCAL, LOCAL_EVAL, WORKSPACE_BOX }

    /** Review-only context: explicit target, the actual session, its scope and the review kind. */
    public record ReviewContext(ReviewIntent.Target target, ManagedSessionId session, String scopeId,
            ReviewTour.Kind kind) {

        public ReviewContext {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(scopeId, "scopeId");
            Objects.requireNonNull(kind, "kind");
        }

        /** Built from the intent's own fields; the intent must already name its actual session. */
        public static ReviewContext from(ReviewIntent intent, String scopeId) {
            Objects.requireNonNull(intent, "intent");
            ManagedSessionId session = intent.session().orElseThrow(
                    () -> new IllegalArgumentException("review intent has no session"));
            return new ReviewContext(intent.target(), session, scopeId, intent.kind());
        }
    }
}
