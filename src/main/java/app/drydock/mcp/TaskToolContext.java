package app.drydock.mcp;

import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.tasks.TaskDocument;
import app.drydock.tasks.TaskId;
import app.drydock.tasks.TaskPriority;
import app.drydock.tasks.TaskSnapshot;
import app.drydock.tasks.TaskState.Actor;
import app.drydock.tasks.TaskStatus;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.nio.file.Path;
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
     * The display slugs that resolve to exactly one registered repository,
     * in registration order, for refusals that name what an explicit
     * project can be. A slug two projects share is omitted: it would
     * refuse in {@link #repositoryBySlug} anyway (never picks a winner),
     * so advertising it would only offer a dead-end retry.
     */
    CompletableFuture<List<String>> registeredProjectSlugs();

    /**
     * The ids of the tasks assigned to the calling session (their sessions
     * list carries this session's id). Status writes are scoped to these
     * (clarification 2); reads never are.
     */
    CompletableFuture<Set<TaskId>> assignedTaskIds(ManagedSessionId caller);

    /** The registered repository a display slug names (D14); empty when none answers. */
    CompletableFuture<Optional<RepositoryId>> repositoryBySlug(String slug);

    /**
     * The registered project a task's repository id names, as the platform resolves it:
     * the dual-registry resolution ({@code SharedProjectResolver}) a task's id needs,
     * because the task tools stamp created tasks with the shared registry's id while an
     * IDE-dialog task may carry the install catalog's. The slug is the display-name slug
     * the graph and the tree show (null when the name has no slug characters); the root
     * is the checkout's main directory. Remote projects answer empty -- there is no
     * local checkout to name. Memory-only; never I/O.
     */
    CompletableFuture<Optional<ProjectInfo>> projectOf(RepositoryId repository);

    /** The project a task is filed under: its display slug (nullable when unnameable)
     *  and the checkout root a start launches in. */
    record ProjectInfo(String slug, Path root) {

        public ProjectInfo {
            Objects.requireNonNull(root, "root");
        }
    }

    /**
     * Creates one task: done_by always human (an agent cannot grant itself
     * agent-close, D16), the given prerequisite reference texts as after,
     * the given priority in the new file's front matter, and no sessions
     * yet (assignment is a launch, not a create). The explicit
     * {@code Unprioritized} stores the same as unset.
     */
    CompletableFuture<Outcome> create(String title, List<String> after, RepositoryId repository,
            Optional<TaskPriority> priority);

    /**
     * Applies one status change under the state's actor rules (T07): an
     * agent never drops, an agent's done on a human-validated task stores
     * review, and done_by and relations are not requestable here at all.
     * The committed document carries the resulting status.
     */
    CompletableFuture<Outcome> updateStatus(TaskId task, Actor actor, TaskStatus status);

    /**
     * Applies a status change and optional priority together. Implementations with a
     * single-writer store should persist both fields in one mutation. This default
     * preserves compatibility for contexts that only implement the original status
     * operation; production contexts override it to avoid a second file write.
     */
    default CompletableFuture<Outcome> updateStatus(TaskId task, Actor actor, TaskStatus status,
            Optional<TaskPriority> priority) {
        Objects.requireNonNull(priority, "priority");
        CompletableFuture<Outcome> updated = updateStatus(task, actor, status);
        if (priority.isEmpty()) {
            return updated;
        }
        return updated.thenCompose(statusOutcome -> {
            if (statusOutcome.refused()) {
                return CompletableFuture.completedFuture(statusOutcome);
            }
            return setPriority(task, priority.get()).thenApply(priorityOutcome -> {
                if (priorityOutcome.refused()) {
                    return Outcome.refused("The status was committed as '"
                            + statusOutcome.document().status().storedName()
                            + "' but the priority could not be stored: " + priorityOutcome.refusal());
                }
                return priorityOutcome;
            });
        });
    }

    /** Appends one session note under '## Session notes', touching no other body byte (P7). */
    CompletableFuture<Outcome> appendNote(TaskId task, String note);

    /**
     * Sets one task's priority, replacing whatever the front matter stored
     * (a named value, an unknown one, or nothing). Unlike a status change
     * this needs no state rule -- priority touches no status, no actor
     * policy -- so the committed document carries the stored priority.
     */
    CompletableFuture<Outcome> setPriority(TaskId task, TaskPriority priority);

    /**
     * Claims one claimable follow-up (S4 of docs/StackedPrExecution.md) for
     * the calling session: Drydock validates the claim (a follow-up with an
     * in-repository prerequisite branch, not owned by a live session, the
     * graph sound) and launches the task as its OWN stacked session on a
     * worktree at the prerequisite's {@code dd/<id>} branch tip -- the
     * confirmed O3 policy: a claim launches immediately, with no human
     * confirmation step. The committed document is the task as it stood at
     * claim time; the launch binding adds the new session's id to its
     * sessions list afterwards. For a tool claim the service records one
     * audit log line naming the calling session and the claimed task
     * immediately before the launch, and a refused tool-path claim logs one
     * audit line (calling session, claimed task, reason). The launch itself
     * is asynchronous: this future answers once validation passed and the
     * launch attempt started; a preparation refusal or launch failure
     * afterwards is logged and launches nothing -- the task stays open and
     * claimable, and the claiming agent verifies the outcome with task_get
     * (the new session's id appears in the sessions list on success).
     */
    CompletableFuture<Outcome> claim(ManagedSessionId caller, TaskId task);
}
