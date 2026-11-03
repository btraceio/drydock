package app.drydock.mcp;

import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.tasks.TaskDocument;
import app.drydock.tasks.TaskId;
import app.drydock.tasks.TaskSnapshot;
import app.drydock.tasks.TaskState.Actor;
import app.drydock.tasks.TaskStatus;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * The platform seam behind the task MCP tools (T10a, D14/P7; implemented by
 * the application in T12). Every method returns a future, and every store
 * operation completes with either its COMMITTED document or a concrete
 * refusal reason: a domain refusal is a value in the future, never an
 * exceptional completion, so the tool hands the reason to the agent
 * verbatim. A mutation future completes only after the single writer has
 * installed and re-read the file, so the document an answer carries is
 * what the next scan sees.
 */
public interface TaskToolContext {

    /**
     * One store operation's answer: exactly one of a committed document and
     * a refusal reason. The committed document is re-read from disk after
     * the write, so its status -- review included, when an agent's done on
     * a human-validated task awaits confirmation (D16) -- is the stored
     * status, not a request.
     */
    record Outcome(TaskDocument document, String refusal) {

        public Outcome {
            if ((document == null) == (refusal == null)) {
                throw new IllegalArgumentException("exactly one of document and refusal must be set");
            }
        }

        /** The answer of a committed mutation. */
        public static Outcome committed(TaskDocument document) {
            return new Outcome(document, null);
        }

        /** The answer of a refused mutation; nothing was written. */
        public static Outcome refused(String reason) {
            return new Outcome(null, reason);
        }

        /** Whether this answer refuses the operation. */
        public boolean refused() {
            return refusal != null;
        }
    }

    /** The store's committed snapshot across EVERY registered repository (D14). */
    CompletableFuture<TaskSnapshot> snapshot();

    /** The caller's own repository, from its working root; empty when unregistered. */
    CompletableFuture<Optional<RepositoryId>> defaultRepository(ManagedSessionId caller);

    /**
     * The ids of the tasks assigned to the calling session (their sessions
     * list carries this session's id). Status writes are scoped to these
     * (clarification 2); reads never are.
     */
    CompletableFuture<Set<TaskId>> assignedTaskIds(ManagedSessionId caller);

    /** The registered repository a display slug names (D14); empty when none answers. */
    CompletableFuture<Optional<RepositoryId>> repositoryBySlug(String slug);

    /**
     * Creates one task: done_by always human (an agent cannot grant itself
     * agent-close, D16), the given prerequisite reference texts as after,
     * and no sessions yet (assignment is a launch, not a create).
     */
    CompletableFuture<Outcome> create(String title, List<String> after, RepositoryId repository);

    /**
     * Applies one status change under the state's actor rules (T07): an
     * agent never drops, an agent's done on a human-validated task stores
     * review, and done_by and relations are not requestable here at all.
     * The committed document carries the resulting status.
     */
    CompletableFuture<Outcome> updateStatus(TaskId task, Actor actor, TaskStatus status);

    /** Appends one session note under '## Session notes', touching no other body byte (P7). */
    CompletableFuture<Outcome> appendNote(TaskId task, String note);
}
