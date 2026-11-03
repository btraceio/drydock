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
 */
public final class DelayedTaskRows {

    /** The proposed age threshold (D9): a task created before now - 14 days is stale. */
    public static final Duration STALE_AFTER = Duration.ofDays(14);

    private DelayedTaskRows() {
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
