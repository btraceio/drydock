package app.drydock.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A first-class grouping of related {@link ManagedAgentSession}s across
 * repositories, plus a shared {@link WorkflowBrief}.
 *
 * <p>A workflow names a single piece of work -- a feature, a refactor, a bug
 * hunt, an investigation -- and lets sessions in different repos carry one
 * shared narrative instead of each relying on the operator's memory. A
 * session belongs to at most one workflow (see {@link
 * ManagedAgentSession#workflowId()}); the affiliation is set on the session,
 * not stored as a member list here, so the source of truth is one place.</p>
 *
 * <p>{@link #status()} is {@link WorkflowStatus#OPEN} by default;
 * {@link WorkflowStatus#ARCHIVED} hides the workflow from the default rail
 * without deleting it. Archiving is reversible and never touches member
 * sessions. {@link #lastOpenedAt()} is bumped when any member session is
 * opened, so the rail sorts workflows with the active one on top -- the
 * workflow-level analogue of {@link ManagedAgentSession#lastOpenedAt()}.</p>
 *
 * <p>{@link #brief()} is empty until first written. A workflow with no member
 * sessions is not auto-deleted: the human may re-add sessions.</p>
 */
public record Workflow(
        WorkflowId id,
        String title,
        WorkflowStatus status,
        Instant createdAt,
        Instant lastOpenedAt,
        Optional<WorkflowBrief> brief
) {

    public Workflow {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(lastOpenedAt, "lastOpenedAt");
        Objects.requireNonNull(brief, "brief");

        if (title.isBlank()) {
            throw new IllegalArgumentException("Workflow title must not be blank");
        }
    }

    /** A freshly created workflow: OPEN, no brief, timestamps at {@code now}. */
    public static Workflow create(WorkflowId id, String title, Instant now) {
        return new Workflow(id, title, WorkflowStatus.OPEN, now, now, Optional.empty());
    }

    public Workflow withTitle(String newTitle) {
        return new Workflow(id, newTitle, status, createdAt, lastOpenedAt, brief);
    }

    public Workflow withStatus(WorkflowStatus newStatus) {
        return new Workflow(id, title, newStatus, createdAt, lastOpenedAt, brief);
    }

    public Workflow withLastOpenedAt(Instant newLastOpenedAt) {
        return new Workflow(id, title, status, createdAt, newLastOpenedAt, brief);
    }

    public Workflow withBrief(Optional<WorkflowBrief> newBrief) {
        return new Workflow(id, title, status, createdAt, lastOpenedAt, newBrief);
    }
}
