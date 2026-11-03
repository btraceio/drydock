package app.drydock.tasks;

import app.drydock.delayedtasks.TaskStoreWatcher.WatchedSnapshot;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionActivity;
import app.drydock.intellij.tasks.TaskNode.TaskRow;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Pure delayed-task row derivation (T15a): rows come from ONE store
 * snapshot, a caller-supplied live-catalog id set, a repository-to-slug
 * mapping (P2) and an INJECTED clock -- nothing else. There is no
 * filesystem access anywhere in row construction: given the snapshot,
 * derivation succeeds even when the store root is unreadable or gone, and
 * every foreign or removed session is still derivable from the snapshot
 * alone. Presentations, blockers and live-session membership are read from
 * {@link TaskState}'s views (T07a), never recomputed; the age flag uses the
 * proposed 14-day threshold (D9) against the injected clock, never
 * {@code System.now}.
 *
 * <p>T40 adds the plan layer's derivations (P4.12): the progress
 * annotation of the target task of one staged plan -- stage n/m plus a
 * running/waiting/halted/canceled/completed phase -- and the Cancel offer
 * rule. Both are PURE over values the CALLER holds in memory (the plan's
 * published record, the task snapshot, the live-session set): no plan
 * file is ever read here, so a render path that composes only these
 * functions cannot touch the plan store's files even when they are
 * unreadable.</p>
 */
public final class DelayedTaskRows {

    /** The proposed age threshold (D9): a task created before now - 14 days is stale. */
    public static final Duration STALE_AFTER = Duration.ofDays(14);

    private DelayedTaskRows() {
    }

    // ---- T40: the plan's visible progress on its target task (P4.12) ----

    /**
     * The plan's visible phase on its target task (T40, P4.12): the plan
     * drives its current stage (RUNNING), waits on the human (WAITING: a
     * review that is all that holds a launched stage, D16, or a durably
     * paused plan awaiting its explicit Continue, P4.10), is halted by a
     * task that lost its session or was reopened (HALTED, P4.8/D16), or is
     * terminal (CANCELED, COMPLETED). The labels are the tree's display
     * contract; never rename an existing one.
     */
    public enum PlanPhase {
        RUNNING("running"), WAITING("waiting"), HALTED("halted"), CANCELED("canceled"),
        COMPLETED("completed");

        private final String label;

        PlanPhase(String label) {
            this.label = label;
        }

        /** The label the tree shows next to the stage count ("stage 2/3 running"). */
        public String label() {
            return label;
        }
    }

    /**
     * One plan's progress on its target task (T40, P4.12): the current
     * stage's number out of the plan's total, and the phase the task
     * snapshot and live sessions derive. A value type; the derivation is
     * {@link #planProgress}.
     */
    public record PlanProgress(int stage, int stages, PlanPhase phase) {

        public PlanProgress {
            if (stage < 1) {
                throw new IllegalArgumentException("Stage numbers start at 1, got: " + stage);
            }
            if (stages < 1) {
                throw new IllegalArgumentException("A plan has at least one stage");
            }
            if (stage > stages) {
                throw new IllegalArgumentException("Stage " + stage + " exceeds the plan's "
                        + stages + " stages");
            }
            Objects.requireNonNull(phase, "phase");
        }

        /** The P4.12 annotation text: "stage 2/3 running". */
        public String text() {
            return "stage " + stage + "/" + stages + " " + phase.label();
        }
    }

    /**
     * The plan whose target is {@code task} (T40, P4.12), from the PUBLISHED
     * records the caller holds in memory: at most one exists, because the
     * plan store keys every plan by its target task. Pure -- no plan file
     * is read here; the caller's list is the service's cached publication.
     */
    public static Optional<PlanStore.PlanRecord> planOf(List<PlanStore.PlanRecord> published, TaskId task) {
        Objects.requireNonNull(published, "published");
        Objects.requireNonNull(task, "task");
        for (PlanStore.PlanRecord record : published) {
            if (record.plan().launch().target().equals(task)) {
                return Optional.of(record);
            }
        }
        return Optional.empty();
    }

    /**
     * The plan's progress annotation for its target task (T40, P4.12),
     * derived from the plan's own record, the task snapshot and the
     * live-session set -- PURE: no plan-file I/O, no clock, no session
     * object, nothing recomputed from the plan's graph. The current stage
     * is the first unfinished one. A durably paused plan waits for its
     * explicit Continue (P4.10). A task of a LAUNCHED stage that reads OPEN
     * was reopened mid-plan, and a current-stage task that reads STARTED
     * with no live session was interrupted -- both halt, exactly as the
     * T34 reducer derives them (P4.8/D16). A launched stage whose only
     * unfinished work is review waits on the human's confirmation (D16).
     * Empty when the plan's target is no longer a task of the snapshot:
     * no target, no annotation.
     */
    public static Optional<PlanProgress> planProgress(PlanStore.PlanRecord record, TaskSnapshot snapshot,
            Set<String> liveSessions) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(liveSessions, "liveSessions");
        RunningPlan plan = record.plan();
        if (snapshot.find(plan.launch().target()).isEmpty()) {
            return Optional.empty();
        }
        RunningPlan.StageState current = null;
        for (RunningPlan.StageState stage : plan.stages()) {
            if (!stage.finished()) {
                current = stage;
                break;
            }
        }
        int total = plan.stages().size();
        if (current == null) { // every stage finished: the plan's own terminal word
            return Optional.of(new PlanProgress(total, total,
                    plan.status() == RunningPlan.Status.CANCELED ? PlanPhase.CANCELED
                            : PlanPhase.COMPLETED));
        }
        if (plan.status() == RunningPlan.Status.CANCELED) {
            return Optional.of(new PlanProgress(current.stage(), total, PlanPhase.CANCELED));
        }
        if (record.paused()) { // the restart pause: only an explicit Continue resumes (P4.10)
            return Optional.of(new PlanProgress(current.stage(), total, PlanPhase.WAITING));
        }
        for (RunningPlan.StageState stage : plan.stages()) {
            if (!stage.launched()) {
                continue; // unlaunched work: an open task there is simply not started yet
            }
            for (TaskId task : stage.tasks()) {
                if (snapshot.find(task).map(TaskDocument::status).orElse(null) == TaskStatus.OPEN) {
                    return Optional.of(new PlanProgress(current.stage(), total, PlanPhase.HALTED));
                }
            }
        }
        boolean reviewOnly = current.launched();
        for (TaskId task : current.tasks()) {
            TaskDocument document = snapshot.find(task).orElse(null);
            TaskStatus status = document == null ? null : document.status();
            if (status == TaskStatus.STARTED && noneLive(document, liveSessions)) {
                return Optional.of(new PlanProgress(current.stage(), total, PlanPhase.HALTED));
            }
            if (status != TaskStatus.REVIEW && status != TaskStatus.DONE
                    && status != TaskStatus.DROPPED) {
                reviewOnly = false;
            }
        }
        return Optional.of(new PlanProgress(current.stage(), total,
                reviewOnly ? PlanPhase.WAITING : PlanPhase.RUNNING));
    }

    /** Whether none of the document's stored sessions is in the live set (D10's interrupt shape). */
    private static boolean noneLive(TaskDocument document, Set<String> liveSessions) {
        for (String session : document.sessions()) {
            if (liveSessions.contains(session)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the plan still offers Cancel (T40, P4.12): a RUNNING plan
     * with unfinished work; the canceled snapshot no longer offers it,
     * and neither does a completed one. Pure over the plan's own record;
     * the tree resolves the CURRENT published record, never a captured
     * stale one.
     */
    public static boolean cancelOffered(RunningPlan plan) {
        Objects.requireNonNull(plan, "plan");
        return plan.status() == RunningPlan.Status.RUNNING
                && plan.stages().stream().anyMatch(stage -> !stage.finished());
    }

    /** Every row of the service's watched snapshot composition (T12a/T13a input). */
    public static List<TaskNode.DelayedTaskRow> rows(WatchedSnapshot watched, Set<String> liveSessionIds,
            Function<RepositoryId, String> projectSlugs, Instant now) {
        Objects.requireNonNull(watched, "watched");
        return rows(watched.snapshot(), liveSessionIds, projectSlugs, now);
    }

    /**
     * One row per document, in snapshot order: the view the snapshot's
     * {@link TaskState} derives (presentation, blockers, live sessions),
     * the document's own title/repository/stored sessions, and the age
     * flag against the injected clock. No session object, no catalog and
     * no file are consulted -- a row exists for every document, whoever
     * its sessions were.
     */
    public static List<TaskNode.DelayedTaskRow> rows(TaskSnapshot snapshot, Set<String> liveSessionIds,
            Function<RepositoryId, String> projectSlugs, Instant now) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(liveSessionIds, "liveSessionIds");
        Objects.requireNonNull(projectSlugs, "projectSlugs");
        Objects.requireNonNull(now, "now");
        TaskState state = TaskState.of(snapshot, TaskGraph.of(snapshot, projectSlugs), liveSessionIds);
        List<TaskNode.DelayedTaskRow> out = new ArrayList<>();
        for (TaskDocument document : snapshot.documents()) {
            TaskState.TaskView view = state.viewOf(document.id()).orElseThrow();
            boolean stale = document.created().map(created -> created.isBefore(now.minus(STALE_AFTER)))
                    .orElse(false);
            out.add(new TaskNode.DelayedTaskRow(view, document.title(), document.repository(),
                    document.created(), stale, document.sessions()));
        }
        return out;
    }

    /**
     * D9's ready-first order: ready, blocked, review, interrupted,
     * started, terminal; ties break by task id, so two rows with equal rank
     * always come out in the same deterministic order (and the sort itself
     * is stable, so rows never swap between derivations).
     */
    public static List<TaskNode.DelayedTaskRow> sorted(Collection<TaskNode.DelayedTaskRow> rows) {
        List<TaskNode.DelayedTaskRow> copy = new ArrayList<>(rows);
        copy.sort(Comparator.comparingInt(DelayedTaskRows::rank).thenComparing(row -> row.id().text()));
        return copy;
    }

    /** One row's rank in D9's order (ready first). */
    private static int rank(TaskNode.DelayedTaskRow row) {
        return switch (row.presentation()) {
            case READY -> 0;
            case BLOCKED -> 1;
            case REVIEW -> 2;
            case INTERRUPTED -> 3;
            case STARTED -> 4;
            case TERMINAL -> 5;
        };
    }

    /** Text search over one row: title or task id, case-insensitively; an empty filter matches every row. */
    public static boolean matches(String filter, TaskNode.DelayedTaskRow row) {
        return filter == null || filter.isBlank() || contains(row.title(), filter)
                || contains(row.id().text(), filter);
    }

    /** The active (non-terminal) rows, ready-first: the Tasks view's content (D9). */
    public static List<TaskNode.DelayedTaskRow> active(Collection<TaskNode.DelayedTaskRow> rows) {
        return sorted(rows).stream().filter(row -> !row.terminal()).toList();
    }

    /** The terminal (done/dropped) rows: the Completed view's content, hidden from All and Tasks (D9). */
    public static List<TaskNode.DelayedTaskRow> completed(Collection<TaskNode.DelayedTaskRow> rows) {
        return sorted(rows).stream().filter(TaskNode.DelayedTaskRow::terminal).toList();
    }

    /**
     * D10's membership target: the session a row attaches under -- its
     * first live stored session id for which the caller still shows a row
     * (a live catalog id whose row this window did not pass keeps the task
     * in the Tasks section; it never silently vanishes). Empty when no
     * stored id is live: the task goes back to the Tasks section, flagged
     * interrupted by {@link TaskState} when it was started, with its
     * {@link TaskNode.DelayedTaskRow#lastSession()} link to the last one.
     */
    public static Optional<String> attachedSession(TaskNode.DelayedTaskRow row, Set<String> sessionRowsShown) {
        Objects.requireNonNull(sessionRowsShown, "sessionRowsShown");
        for (String session : row.liveSessions()) {
            if (sessionRowsShown.contains(session)) {
                return Optional.of(session);
            }
        }
        return Optional.empty();
    }

    /**
     * D10/D11 Needs-you for one session row: the existing rule (a running
     * session waiting for the user's answer) OR an assigned task in
     * review -- an agent's done on a human-validated task awaits the
     * human's confirmation, which makes an IDLE or stopped parent session
     * eligible even though nothing is running.
     */
    public static boolean needsYou(TaskRow session,
            Collection<TaskNode.DelayedTaskRow> assignedChildren) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(assignedChildren, "assignedChildren");
        if (session.running() && session.activity().equals(Optional.of(SessionActivity.NEEDS_ATTENTION))) {
            return true;
        }
        return assignedChildren.stream().anyMatch(row -> row.presentation() == TaskState.Presentation.REVIEW);
    }

    private static boolean contains(String text, String filter) {
        return text.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT));
    }
}
