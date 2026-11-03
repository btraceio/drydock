package app.drydock.delayedtasks;

import app.drydock.tasks.DoneBy;
import app.drydock.tasks.TaskDocument;
import app.drydock.tasks.TaskFilePatch;
import app.drydock.tasks.TaskFiles;
import app.drydock.tasks.TaskGraph;
import app.drydock.tasks.TaskId;
import app.drydock.tasks.TaskRuns;
import app.drydock.tasks.TaskSnapshot;
import app.drydock.tasks.TaskState;
import app.drydock.tasks.TaskState.Actor;
import app.drydock.tasks.TaskStatus;
import app.drydock.tasks.TaskStore;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Session Done completion engine (T30a, D6/D10/D16/P8). One instance is
 * captured BEFORE the Session Done dialog opens and, when the user accepts,
 * commits that session's task completions and run receipts always BEFORE the
 * session may be deleted (the plan's hard ordering: persist task
 * completion/history first).
 *
 * <p>SNAPSHOT ISOLATION: {@link #capture} reads the store once and keeps the
 * session's tasks -- statuses, done_by, review state, and whether this
 * session is each task's last live catalog session -- so later changes
 * cannot race what the dialog shows or which units a commit plans. A commit
 * still validates every selected done against a FRESH scan through
 * {@link TaskState#validateUpdate} with the HUMAN actor: the dialog's
 * confirm is the human applying done (D16), so review-staged tasks and
 * agent-done tasks sitting in review confirm to done.
 *
 * <p>MEMBERSHIP: an unselected task never gets a status write -- "interrupted"
 * stays derived (T07). When this session is a task's last live session, the
 * deletion makes a started task present interrupted, and the run-receipt
 * FORGET row the engine writes records that transition and releases the
 * reservation so the task can run again; a task with another live session
 * keeps its reservation and stays under that session's row. A task already
 * in review is never auto-resolved: unselected, it keeps awaiting
 * confirmation.
 *
 * <p>FAILED SAVES BLOCK DELETION: a unit is its status write, then its
 * receipt write; any failure is collected visibly and the commit's
 * {@link Result} refuses deletion. Committed units are remembered, so a
 * retry -- {@link #commit} on this instance again, or a fresh capture whose
 * done statuses and released receipts are no-ops -- re-commits nothing that
 * already succeeded and retries only the rest.
 *
 * <p>T41 BOUNDARY: {@link #reservePendingWorktreeRemoval} marks this
 * session's worktree as pending removal so stage auto-proceed cannot race
 * the deletion; consuming the marker, worktree-consumer rechecks and every
 * removal guard are T41's, not this engine's. Cancelling the dialog writes
 * nothing: capture only reads, and a commit that never runs writes
 * nothing.</p>
 */
public final class SessionTaskCompletion {

    private static final Logger LOG = System.getLogger(SessionTaskCompletion.class.getName());

    /**
     * One captured task of the session (the dialog's immutable view): its
     * identity and stored state at capture, whether it awaits the human's
     * confirmation (D16 review), and whether this session is its last live
     * catalog session (D10 membership at capture).
     */
    public record SessionTask(TaskId id, String title, TaskStatus status, DoneBy doneBy,
            boolean awaitingReview, boolean lastLiveSession) {
    }

    /** One unit's visible failure; {@code task} is null for a whole-store failure. */
    public record Failure(TaskId task, String reason) {
    }

    /** One commit attempt: what committed, and every failure that blocks deletion. */
    public static final class Result {

        private final List<TaskId> committed;
        private final List<Failure> failures;

        private Result(List<TaskId> committed, List<Failure> failures) {
            this.committed = List.copyOf(committed);
            this.failures = List.copyOf(failures);
        }

        /** Whether the session may now be deleted: every captured unit committed. */
        public boolean deletionAllowed() {
            return failures.isEmpty();
        }

        /** The units this attempt fully committed, including ones needing no write. */
        public List<TaskId> committed() {
            return committed;
        }

        /** Every failure that blocked deletion, in unit order. */
        public List<Failure> failures() {
            return failures;
        }
    }

    /** Pending worktree removals by canonical worktree, holding the session id (T41 consumes). */
    private static final ConcurrentHashMap<Path, String> PENDING_WORKTREE_REMOVALS = new ConcurrentHashMap<>();

    private final Path root;
    private final String sessionId;
    private final Path worktree;
    private final Set<String> liveSessions;
    private final List<SessionTask> tasks;
    private final String captureProblem;
    /** Units a commit of this instance already fully committed; a retry never redoes them. */
    private final Set<TaskId> committedUnits = new LinkedHashSet<>();

    private SessionTaskCompletion(Path root, String sessionId, Path worktree, Set<String> liveSessions,
            List<SessionTask> tasks, String captureProblem) {
        this.root = root;
        this.sessionId = sessionId;
        this.worktree = worktree;
        this.liveSessions = liveSessions;
        this.tasks = tasks;
        this.captureProblem = captureProblem;
    }

    /**
     * Captures the session's tasks once, before the dialog. {@code liveSessions}
     * is the caller-supplied live catalog set (T07: a session id is live exactly
     * while the catalog holds its row, idle or stopped). A store that cannot be
     * read degrades to no tasks plus a visible {@link #captureProblem()} -- the
     * plugin keeps working without tasks -- never an exception.
     */
    public static SessionTaskCompletion capture(Path storeRoot, Set<String> liveSessions, String sessionId,
            Path worktree) {
        Objects.requireNonNull(storeRoot, "storeRoot");
        Objects.requireNonNull(liveSessions, "liveSessions");
        Objects.requireNonNull(sessionId, "sessionId");
        Set<String> live = Set.copyOf(liveSessions);
        List<SessionTask> captured = List.of();
        String problem = null;
        try {
            captured = TaskFiles.scan(storeRoot).documents().stream()
                    .filter(document -> document.sessions().contains(sessionId))
                    .map(document -> new SessionTask(document.id(), document.title(), document.status(),
                            document.doneBy(), document.status() == TaskStatus.REVIEW,
                            lastLiveSession(document, sessionId, live)))
                    .toList();
        } catch (IOException | RuntimeException e) {
            problem = "the task store on " + storeRoot + " could not be read: " + e;
            LOG.log(Level.WARNING, "Could not capture the tasks of session " + sessionId, e);
        }
        return new SessionTaskCompletion(storeRoot.toAbsolutePath().normalize(), sessionId, worktree, live,
                captured, problem);
    }

    /** An engine for a store the caller could not even approach: no tasks, the problem shown. */
    public static SessionTaskCompletion captureFailed(String sessionId, Path worktree, String problem) {
        return new SessionTaskCompletion(Path.of(""), Objects.requireNonNull(sessionId, "sessionId"), worktree,
                Set.of(), List.of(), Objects.requireNonNull(problem, "problem"));
    }

    /** The captured tasks, in snapshot order; empty when none link this session. */
    public List<SessionTask> tasks() {
        return tasks;
    }

    /** Why no tasks could be captured, or empty when the snapshot was read. */
    public String captureProblem() {
        return captureProblem;
    }

    /**
     * Commits the user's per-task selection. Selected tasks become done through
     * the HUMAN actor (freshly validated, D16); unselected tasks get no status
     * write. Receipts: every selected task, and every unselected task whose
     * last live session is this one, is released with a FORGET row. Never
     * throws: every failure is a visible {@link Failure} that blocks deletion.
     */
    public synchronized Result commit(Set<TaskId> selection) {
        Objects.requireNonNull(selection, "selection");
        if (tasks.isEmpty()) {
            return new Result(List.of(), List.of()); // nothing captured: no writes, nothing blocks
        }
        List<TaskId> committedNow = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();
        try (TaskStore store = new TaskStore(root); TaskRuns runs = new TaskRuns(root)) {
            TaskSnapshot fresh = TaskFiles.scan(root);
            TaskState state = TaskState.of(fresh, TaskGraph.of(fresh, repository -> null), liveSessions);
            for (SessionTask task : tasks) {
                if (committedUnits.contains(task.id())) {
                    continue; // a partial retry never re-commits a committed unit
                }
                if (commitUnit(store, runs, state, fresh, task, selection.contains(task.id()), committedNow,
                        failures)) {
                    committedUnits.add(task.id());
                }
            }
        } catch (IOException | RuntimeException e) { // the whole commit's own reads failed
            failures.add(new Failure(null, "the task store on " + root + " failed: " + e));
        }
        return new Result(committedNow, failures);
    }

    /** Commits one unit: the done write when selected, then its receipt release. */
    private boolean commitUnit(TaskStore store, TaskRuns runs, TaskState state, TaskSnapshot fresh,
            SessionTask task, boolean selected, List<TaskId> committedNow, List<Failure> failures) {
        try {
            if (selected && statusOf(fresh, task.id()) != TaskStatus.DONE) {
                TaskState.Transition done = state.validateUpdate(task.id(), Actor.HUMAN, TaskStatus.DONE);
                if (!done.permitted()) {
                    failures.add(new Failure(task.id(), done.refusalReason()));
                    return false;
                }
                store.update(task.id(), new TaskFilePatch.Updates(null, TaskStatus.DONE, null, null, null, null));
            }
            if (selected || task.lastLiveSession()) {
                runs.forget(task.id());
            }
            committedNow.add(task.id());
            return true;
        } catch (TaskStore.TaskStoreException | TaskRuns.TaskRunsException | RuntimeException e) {
            failures.add(new Failure(task.id(), e.getMessage() != null ? e.getMessage() : e.toString()));
            return false;
        }
    }

    /** The status of one task on the commit's fresh scan; null when it left the store. */
    private static TaskStatus statusOf(TaskSnapshot fresh, TaskId task) {
        TaskDocument document = fresh.byId().get(task);
        return document == null ? null : document.status();
    }

    /** Whether a task keeps a live session besides this one (captured, D10 membership). */
    private static boolean lastLiveSession(TaskDocument document, String sessionId, Set<String> live) {
        return document.sessions().stream()
                .filter(session -> !session.equals(sessionId))
                .noneMatch(live::contains);
    }

    // ---- worktree-removal reservation (T41 boundary) -----------------------

    /** Reserves this session's worktree as pending removal; consumed by T41's removal path. */
    public void reservePendingWorktreeRemoval() {
        if (worktree != null) {
            PENDING_WORKTREE_REMOVALS.put(canonical(worktree), sessionId);
        }
    }

    /** Releases the reservation this engine holds; also the path a failed commit takes. */
    public void releaseWorktreeRemovalReservation() {
        if (worktree != null) {
            PENDING_WORKTREE_REMOVALS.remove(canonical(worktree), sessionId);
        }
    }

    /** Whether a worktree's removal is reserved; T41's auto-proceed consults this. */
    public static boolean worktreeRemovalPending(Path worktree) {
        return worktree != null && PENDING_WORKTREE_REMOVALS.containsKey(canonical(worktree));
    }

    private static Path canonical(Path worktree) {
        return worktree.toAbsolutePath().normalize();
    }
}
