package app.drydock.delayedtasks;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.tasks.TaskDocument;
import app.drydock.tasks.TaskFilePatch;
import app.drydock.tasks.TaskFilePatch.Updates;
import app.drydock.tasks.TaskFiles;
import app.drydock.tasks.TaskGraph;
import app.drydock.tasks.TaskId;
import app.drydock.tasks.TaskRuns;
import app.drydock.tasks.TaskSnapshot;
import app.drydock.tasks.TaskState;
import app.drydock.tasks.TaskState.Actor;
import app.drydock.tasks.TaskStatus;
import app.drydock.tasks.TaskStore;
import app.drydock.tasks.TaskStore.TaskStoreException;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The pre-spawn binding of one reserved task to the session about to launch
 * it (T28a, D6/P6). {@link #bind} runs on the launch chain, off the EDT,
 * after the session is prepared and BEFORE anything spawns: it reserves the
 * task's run receipt (a live PREPARE row, T27a) and writes the session link
 * plus the started status into the task document, so a fast harness can
 * never complete a turn before the binding exists. A bind that refuses or
 * fails throws visibly -- the launch is PREVENTED -- and leaves no partial
 * state: a failure after the reservation releases it. Every write rides
 * the store root's one single-writer lock (a fresh TaskStore and TaskRuns
 * compose it, T27a), never a parallel mutation path. The outcome callbacks
 * reconcile BY THE KNOWN SESSION ID -- a later agent done or review
 * (T07/T10a rules) is never undone by a failed or late launch -- and every
 * stale callback is a logged visible refusal, never a crash. The T27b
 * refused-release semantics stay exactly as they are.
 */
public final class TaskLaunchBinding {

    private static final Logger LOG = System.getLogger(TaskLaunchBinding.class.getName());
    private static final int REASON_CAP = 160;

    private final TaskId task;
    private final Path storeRoot;
    private final Path ownerCatalog;
    private final List<Path> consumers;
    private final int cancellationGeneration;
    private volatile String boundSession;
    private volatile Path boundWorktree;

    /** One binding: {@code ownerCatalog} keeps the receipt writable while it exists (T27a),
     * {@code consumers} are the D13 paths the receipt retains, {@code cancellationGeneration}
     * is the plan generation of this launch (P4.12; 0 for a plain single-task start). */
    public TaskLaunchBinding(TaskId task, Path storeRoot, Path ownerCatalog, List<Path> consumers,
            int cancellationGeneration) {
        this.task = Objects.requireNonNull(task, "task");
        this.storeRoot = Objects.requireNonNull(storeRoot, "storeRoot").toAbsolutePath().normalize();
        this.ownerCatalog = Objects.requireNonNull(ownerCatalog, "ownerCatalog").toAbsolutePath().normalize();
        this.consumers = List.copyOf(consumers == null ? List.of() : consumers);
        this.cancellationGeneration = cancellationGeneration;
    }

    /** The reserved task this binding launches. */
    public TaskId task() { return task; }

    /** The launch handle (T28a): complete once {@link #bind} returned, BEFORE the spawn -- early
     * enough for plan intent persistence and cancellation-generation checks. */
    public LaunchHandle handle() {
        return new LaunchHandle(task, Optional.ofNullable(boundSession), Optional.ofNullable(boundWorktree),
                cancellationGeneration, boundSession != null);
    }

    /** Binds the prepared session: the receipt reserves first (no two live plans reserve one
     * task), then the document write -- both validated on a fresh scan, exactly as every store
     * write is. A refusal or failure throws visibly; a failure after the reservation releases it,
     * so no partial state survives a prevented spawn. */
    public ManagedAgentSession bind(ManagedAgentSession session) {
        Objects.requireNonNull(session, "session");
        String sessionId = session.id().toString();
        Path worktree = session.workingDirectory().toAbsolutePath().normalize();
        try (TaskStore store = new TaskStore(storeRoot); TaskRuns runs = new TaskRuns(storeRoot)) {
            Updates write = startable(sessionId);
            runs.reserve(task, sessionId, worktree, ownerCatalog, consumers);
            try {
                store.update(task, write);
            } catch (TaskStoreException | RuntimeException e) {
                releaseReserved(runs, e);
                throw refusal("Could not write the session link of task '" + task.text() + "'", e);
            }
        } catch (TaskRuns.TaskRunsException e) { // the reserve itself refused: nothing was written
            throw refusal(e.getMessage(), e);
        } catch (IOException e) {
            throw refusal("The task store on " + storeRoot + " could not be read", e);
        }
        boundSession = sessionId;
        boundWorktree = worktree;
        return session;
    }

    /** Records the completed launch (LAUNCH receipt); a stale callback is a logged refusal. */
    public void launched() {
        receipts(boundSession, runs -> runs.launch(task, boundSession), "record the launch of " + task.text());
    }

    /** Records the failed (or late) launch and reconciles the task BY ITS KNOWN SESSION ID. */
    public void launchFailed(Throwable cause) {
        receipts(boundSession, runs -> runs.fail(task, reasonOf(cause)), "release the launch of " + task.text());
        reconcileFailed();
    }

    /** The bind's document write: the task must exist and not be finished, the T07/T10a rules
     * must permit started, and the session link joins the task's sessions. */
    private Updates startable(String sessionId) throws IOException {
        TaskSnapshot fresh = TaskFiles.scan(storeRoot);
        TaskDocument document = fresh.byId().get(task);
        if (document == null) {
            throw refusal("Task '" + task.text() + "' is not in the store on " + storeRoot, null);
        }
        if (document.status() == TaskStatus.DONE || document.status() == TaskStatus.DROPPED) {
            throw refusal("Task '" + task.text() + "' is " + document.status().storedName()
                    + "; a finished task cannot be started", null);
        }
        TaskState.Transition started = TaskState.of(fresh, TaskGraph.of(fresh, repository -> null), Set.of())
                .validateUpdate(task, Actor.HUMAN, TaskStatus.STARTED);
        if (!started.permitted()) {
            throw refusal(started.refusalReason(), null);
        }
        List<String> sessions = new ArrayList<>(document.sessions());
        if (!sessions.contains(sessionId)) {
            sessions.add(sessionId);
        }
        return new Updates(null, started.resulting(), null, null, null, List.copyOf(sessions));
    }

    /** Undoes the bind's document write, by its known session id and ONLY while the document
     * still shows exactly that bind: a task that moved past started -- an agent's done or
     * review, a human confirm, a drop -- keeps everything it has, and a task with other
     * sessions keeps its started status under those sessions. */
    private void reconcileFailed() {
        String session = boundSession;
        if (session == null) {
            return;
        }
        try (TaskStore store = new TaskStore(storeRoot)) {
            TaskDocument document = TaskFiles.scan(storeRoot).byId().get(task);
            if (document == null || !document.sessions().contains(session)) {
                return; // vanished or already reconciled: the receipt is the durable record
            }
            if (document.status() != TaskStatus.STARTED) {
                LOG.log(Level.WARNING, "The failed launch of task '" + task.text() + "' does not undo its "
                        + document.status().storedName() + " status: a later done or review is never clobbered");
                return;
            }
            List<String> remaining = new ArrayList<>(document.sessions());
            remaining.remove(session);
            store.update(task, new Updates(null, remaining.isEmpty() ? TaskStatus.OPEN : null,
                    null, null, null, remaining));
        } catch (IOException | TaskStoreException | RuntimeException e) {
            LOG.log(Level.WARNING, "Could not reconcile task '" + task.text() + "' after its failed launch", e);
        }
    }

    /** One receipt write; an unbound (stale) callback or a registry refusal is logged, never thrown. */
    private void receipts(String session, ReceiptWrite write, String what) {
        if (session == null) {
            LOG.log(Level.WARNING, "Could not " + what + ": the launch was never bound; the callback is stale");
            return;
        }
        try (TaskRuns runs = new TaskRuns(storeRoot)) {
            write.run(runs);
        } catch (TaskRuns.TaskRunsException e) {
            LOG.log(Level.WARNING, "Could not " + what + ": " + e.getMessage(), e);
        }
    }

    /** Releases the reservation on the bind's failure path; a release the registry refuses
     * (T27b: a vanished-owner live row refuses) stays refused, logged, never worked around. */
    private void releaseReserved(TaskRuns runs, Exception cause) {
        try {
            runs.fail(task, "the pre-spawn binding failed: " + reasonOf(cause));
        } catch (TaskRuns.TaskRunsException e) {
            LOG.log(Level.WARNING, "The reservation of task '" + task.text() + "' could not be released", e);
        }
    }

    /** One bounded one-line failure reason, as the receipt's note carries it. */
    private static String reasonOf(Throwable cause) {
        String text = cause == null ? "the launch failed"
                : cause.getMessage() != null && !cause.getMessage().isBlank() ? cause.getMessage()
                        : String.valueOf(cause);
        String flat = text.replaceAll("[\\r\\n\\t]", " ").strip();
        return flat.length() > REASON_CAP ? flat.substring(0, REASON_CAP) : flat;
    }

    private static BindingRefusal refusal(String message, Throwable cause) {
        return new BindingRefusal(message, cause);
    }

    /** One receipt write, run on a fresh registry over the store root's single writer lock. */
    private interface ReceiptWrite {
        void run(TaskRuns runs) throws TaskRuns.TaskRunsException;
    }

    /** One visible bind refusal: a duplicate reservation, a finished or missing task, a failed write. */
    public static final class BindingRefusal extends RuntimeException {
        public BindingRefusal(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The exposed launch handle (T28a): ids, paths and the plan generation of this launch. A
     * plan cancel bumps the generation (RunningPlan.canceled(), P4.12); an effect still
     * carrying this handle's generation is stale once the plan's current one differs. Pure
     * exposure -- no plan logic lives here. */
    public record LaunchHandle(TaskId task, Optional<String> sessionId, Optional<Path> worktree,
            int cancellationGeneration, boolean bound) {

        /** Whether an effect at the plan's CURRENT generation is stale against this launch (P4.12). */
        public boolean staleAt(int currentGeneration) {
            return currentGeneration != cancellationGeneration;
        }
    }
}
