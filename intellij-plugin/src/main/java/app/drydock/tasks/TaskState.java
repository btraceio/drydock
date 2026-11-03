package app.drydock.tasks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Pure presentation-and-rules layer over one {@link TaskSnapshot}, its
 * {@link TaskGraph} and a CALLER-SUPPLIED set of live catalog session ids
 * (T07a). It computes each task's {@link Presentation} (D9-D11), session
 * membership (D10) and the actor-aware status-update policy (D6/D16) with
 * no I/O, no store writes, no MCP and no UI; wiring a ManagedSessionId to
 * an {@link Actor} is the MCP layer's job (T10/T14), and the session-scope
 * check ("only tasks of the calling session") lives there too.
 *
 * <p>MEMBERSHIP, NOT PROCESS ACTIVITY: a session id is live exactly when
 * the CALLER's catalog still holds that row, idle or stopped. The caller
 * decides liveness - {@code sessions:} ids recorded under another IDE
 * configuration are not silently declared dead here; if that other
 * catalog's row still exists, the caller includes its id in the supplied
 * set. A {@code started} task none of whose stored session ids are in the
 * set is presented {@link Presentation#INTERRUPTED}; process exit is never
 * consulted and never completion (P1/D10).</p>
 *
 * <p>Ready means: status open, every resolved prerequisite done or
 * dropped, no broken reference and no cycle membership - a broken or
 * cyclic reference blocks even when every named prerequisite is finished.
 * Blocked carries one concrete text per blocker (D9's "blocked by" hint,
 * D11's "in session" link). Cost O(V+E): the graph is built once per
 * snapshot by its owner, and every view reads each document's references
 * once.</p>
 */
public final class TaskState {

    /** Who performs a status update; the MCP layer maps its caller to an actor. */
    public enum Actor {
        HUMAN, AGENT
    }

    /** One task's derived presentation (D9-D11); exactly one value per task. */
    public enum Presentation {
        /** Open, every prerequisite done or dropped, no broken or cyclic reference. */
        READY,
        /** Open with at least one concrete blocker text (unfinished prerequisite, broken reference, cycle). */
        BLOCKED,
        /** Stored status review - an agent's done on a human-validated task awaits confirmation (D16). */
        REVIEW,
        /** Stored status started and at least one live session id: under its session's row (D10). */
        STARTED,
        /** Derived, not stored: started, and no stored session id is in the live catalog set (D10). */
        INTERRUPTED,
        /** Done or dropped; done stays done even when no session row remains. */
        TERMINAL
    }

    /** One task's presentation, blocker texts and live sessions; immutable. */
    public record TaskView(TaskId task, Presentation presentation, List<String> blockers,
            List<String> liveSessions) {

        public TaskView {
            blockers = List.copyOf(blockers);
            liveSessions = List.copyOf(liveSessions);
        }

        /** D10 membership: at least one stored session id still has a catalog row. */
        public boolean inSession() {
            return !liveSessions.isEmpty();
        }
    }

    /**
     * The outcome of validating one status-update request. {@code resulting}
     * is the status that must be stored when permitted - the request itself,
     * or {@code review} for an agent's done on a human-validated task (D16).
     * On refusal it is the status that stays stored (null when the task is
     * not in the snapshot), and {@code refusalReason} is never null: no
     * silent no-op. Idempotence: an update to the status the task already
     * has is permitted with {@code resulting} equal to that status.
     */
    public record Transition(TaskId task, Actor actor, TaskStatus requested, boolean permitted,
            TaskStatus resulting, String refusalReason) {

        public Transition {
            if (permitted == (refusalReason != null)) {
                throw new IllegalArgumentException("exactly one of permitted and refusalReason must be set");
            }
        }

        static Transition noOp(TaskId task, Actor actor, TaskStatus current) {
            return new Transition(task, actor, current, true, current, null);
        }

        static Transition permitted(TaskId task, Actor actor, TaskStatus requested, TaskStatus resulting) {
            return new Transition(task, actor, requested, true, resulting, null);
        }

        static Transition refused(TaskId task, Actor actor, TaskStatus requested, TaskStatus current,
                String refusalReason) {
            return new Transition(task, actor, requested, false, current,
                    Objects.requireNonNull(refusalReason, "refusalReason"));
        }
    }

    private final TaskSnapshot snapshot;
    private final TaskGraph graph;
    private final Set<String> liveSessions;
    private final Map<TaskId, TaskView> views;

    /** Builds every view once; call via {@link #of}. */
    private TaskState(TaskSnapshot snapshot, TaskGraph graph, Set<String> liveSessionIds) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.graph = Objects.requireNonNull(graph, "graph");
        Set<String> live = new LinkedHashSet<>();
        for (String id : liveSessionIds) {
            if (id != null && !id.isBlank()) {
                live.add(id);
            }
        }
        this.liveSessions = Collections.unmodifiableSet(live);
        Map<TaskId, TaskView> built = new LinkedHashMap<>();
        for (TaskDocument document : snapshot.documents()) {
            built.put(document.id(), viewOf(document));
        }
        this.views = Collections.unmodifiableMap(built);
    }

    /**
     * The state of one snapshot. The graph must be the one built from that
     * same snapshot (a mismatch fails the first view lookup, never a wrong
     * answer); the live set is copied, and null or blank ids are ignored.
     */
    public static TaskState of(TaskSnapshot snapshot, TaskGraph graph, Set<String> liveSessionIds) {
        Objects.requireNonNull(liveSessionIds, "liveSessionIds");
        return new TaskState(snapshot, graph, liveSessionIds);
    }

    /** Every task's view, in snapshot order. */
    public List<TaskView> views() {
        return List.copyOf(views.values());
    }

    /** One task's view, or empty when the snapshot holds no such document. */
    public Optional<TaskView> viewOf(TaskId task) {
        Objects.requireNonNull(task, "task");
        return Optional.ofNullable(views.get(task));
    }

    /** D10 membership of one task: false when the task is not in the snapshot. */
    public boolean inSession(TaskId task) {
        return viewOf(task).map(TaskView::inSession).orElse(false);
    }

    /**
     * Validates one status update. A HUMAN actor (the Session Done dialog,
     * Confirm/Reopen, a manual edit) may set any stored status, including
     * done and dropped. An AGENT actor may request open, started, review
     * or done - done stores {@code review} when the task's done_by is human
     * and {@code done} when it is agent (D16) - but NEVER dropped (Drop is
     * human-only, so an agent cannot bypass human validation by dropping);
     * done_by and relations cannot be requested at all: no method here
     * accepts them. A request for the status the task already has is a
     * no-op success for either actor.
     */
    public Transition validateUpdate(TaskId task, Actor actor, TaskStatus requested) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(requested, "requested");
        TaskDocument document = snapshot.byId().get(task);
        if (document == null) {
            return Transition.refused(task, actor, requested, null, "no task with id '" + task.text()
                    + "' in the snapshot (never stored, or excluded after an id collision)");
        }
        if (requested == document.status()) {
            return Transition.noOp(task, actor, document.status());
        }
        if (actor == Actor.HUMAN) {
            return Transition.permitted(task, actor, requested, requested);
        }
        if (requested == TaskStatus.DROPPED) {
            return Transition.refused(task, actor, requested, document.status(), "Drop is human-only: an agent"
                    + " may not set task '" + task.text() + "' to dropped");
        }
        if (requested == TaskStatus.DONE) {
            TaskStatus resulting = document.doneBy() == DoneBy.AGENT ? TaskStatus.DONE : TaskStatus.REVIEW;
            return Transition.permitted(task, actor, requested, resulting);
        }
        return Transition.permitted(task, actor, requested, requested);
    }

    /** One document's view: presentation, blocker texts and live sessions. */
    private TaskView viewOf(TaskDocument document) {
        List<String> live = new ArrayList<>();
        for (String session : document.sessions()) {
            if (liveSessions.contains(session)) {
                live.add(session);
            }
        }
        Presentation presentation;
        List<String> blockers = List.of();
        switch (document.status()) {
            case DONE, DROPPED -> presentation = Presentation.TERMINAL;
            case REVIEW -> presentation = Presentation.REVIEW;
            case STARTED -> presentation = live.isEmpty() ? Presentation.INTERRUPTED : Presentation.STARTED;
            case OPEN -> {
                blockers = blockersOf(document);
                presentation = blockers.isEmpty() ? Presentation.READY : Presentation.BLOCKED;
            }
            default -> throw new IllegalStateException("Unknown task status: " + document.status());
        }
        return new TaskView(document.id(), presentation, blockers, live);
    }

    /**
     * Every concrete blocker of one open task, in fixed order: broken
     * references, cycle memberships, then unfinished prerequisites. All
     * three come from the graph (its diagnostics hold the whole snapshot,
     * including targets outside this document).
     */
    private List<String> blockersOf(TaskDocument document) {
        TaskGraph.TaskDiagnostics diagnostics = graph.diagnosticsOf(document.id())
                .orElseThrow(() -> new IllegalArgumentException("the graph holds no diagnostics for task '"
                        + document.id().text() + "'; build it from this snapshot"));
        List<String> blockers = new ArrayList<>();
        for (TaskGraph.BrokenReference broken : diagnostics.broken()) {
            blockers.add("broken prerequisite reference '" + broken.reference() + "': " + broken.reason());
        }
        for (TaskGraph.CycleDiagnostic cycle : diagnostics.cycles()) {
            blockers.add("in a dependency cycle: " + cycle.text());
        }
        for (TaskGraph.ResolvedReference reference : diagnostics.resolved()) {
            TaskDocument target = reference.target();
            if (target.status() != TaskStatus.DONE && target.status() != TaskStatus.DROPPED) {
                blockers.add(blockedText(target));
            }
        }
        return blockers;
    }

    /** The concrete text for one unfinished prerequisite (D9, D11). */
    private String blockedText(TaskDocument target) {
        for (String session : target.sessions()) {
            if (liveSessions.contains(session)) {
                return "blocked by '" + target.title() + "' (in session '" + session + "')";
            }
        }
        return "blocked by '" + target.title() + "' (" + target.status().storedName() + ")";
    }
}
