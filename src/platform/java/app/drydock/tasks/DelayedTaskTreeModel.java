package app.drydock.tasks;

import app.drydock.delayedtasks.TaskStoreWatcher.WatchedSnapshot;
import app.drydock.domain.RepositoryId;
import app.drydock.intellij.tasks.TaskNode.TaskRow;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The delayed-task tree layer over the session rows (T15a): one pure
 * derivation from the service's watched snapshot composition (T12a/T13a),
 * the session rows the existing model built, and an injected clock -- the
 * Tasks/Completed sections and the task children under session rows
 * (D9-D11), narrowed by the additive quick views and the text search. The
 * existing All behavior is untouched: session rows pass through unchanged
 * (only children are added), and the session model itself is not edited.
 *
 * <p>NO rebuild per keystroke: the instance form coalesces requests on a
 * single background thread with AGENTS.md's keystroke debounce, so a burst
 * of inputs derives ONCE, from the last requested inputs, and the
 * immutable result is published off the FX thread (the subscriber hops
 * back; this class never touches the UI or the disk).</p>
 */
public final class DelayedTaskTreeModel {

    /** AGENTS.md's keystroke debounce (as SearchRail): one derivation per quiet window. */
    static final long DEBOUNCE_MILLIS = 150;

    /**
     * The additive quick views (T15a); text search applies within the
     * selected one. All keeps today's shape (task children under sessions
     * per D10, terminal tasks hidden behind the ribbon per D9); Tasks
     * shows the active tasks; Completed shows the done and dropped ones;
     * Needs you shows the sessions awaiting the user and the unassigned
     * review tasks (D10/D11).
     */
    public enum View {
        ALL("All"), TASKS("Tasks"), COMPLETED("Completed"), NEEDS_YOU("Needs you");

        private final String label;

        View(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Everything one derivation needs: all of it snapshot- or catalog-derived, plus the injected clock. */
    public record Inputs(WatchedSnapshot tasks, List<TaskRow> sessionRows, Set<String> liveSessionIds,
            Function<RepositoryId, String> projectSlugs, View view, String filter, Instant now) {

        public Inputs {
            Objects.requireNonNull(tasks, "tasks");
            sessionRows = List.copyOf(sessionRows);
            liveSessionIds = Set.copyOf(liveSessionIds);
            Objects.requireNonNull(projectSlugs, "projectSlugs");
            Objects.requireNonNull(view, "view");
            filter = filter == null ? "" : filter.strip();
            Objects.requireNonNull(now, "now");
        }
    }

    /** One immutable derivation: the section first, then the session rows in the given order. */
    public record Derivation(List<TaskNode> nodes) {
        public Derivation {
            nodes = List.copyOf(nodes);
        }
    }

    private final Consumer<Derivation> publisher;
    private final long debounceMillis;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "drydock-task-tree-model");
        thread.setDaemon(true);
        return thread;
    });
    private final Object lock = new Object();
    private Inputs pending;
    private boolean scheduled;
    private volatile boolean disposed;

    /** A model with AGENTS.md's debounce; the publisher runs on the coalescing thread. */
    public static DelayedTaskTreeModel create(Consumer<Derivation> publisher) {
        return new DelayedTaskTreeModel(publisher, DEBOUNCE_MILLIS);
    }

    /** A model with an explicit debounce (tests and sandboxes); derivation never runs on the calling thread. */
    public DelayedTaskTreeModel(Consumer<Derivation> publisher, long debounceMillis) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.debounceMillis = debounceMillis;
    }

    /**
     * Requests one re-derivation. Requests inside the debounce window
     * coalesce: only the LAST requested inputs are derived when the
     * window closes, so a burst of keystrokes cannot cause a burst of
     * rebuilds. A request after dispose is dropped.
     */
    public void request(Inputs inputs) {
        Objects.requireNonNull(inputs, "inputs");
        synchronized (lock) {
            if (disposed) {
                return;
            }
            pending = inputs;
            if (scheduled) {
                return;
            }
            scheduled = true;
        }
        try {
            scheduler.schedule(this::cycle, debounceMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException disposedWhileScheduling) {
        }
    }

    /** One coalesced cycle: derives the last pending inputs and publishes the immutable result. */
    private void cycle() {
        Inputs inputs;
        synchronized (lock) {
            scheduled = false;
            inputs = pending;
            pending = null;
            if (inputs == null || disposed) {
                return;
            }
        }
        publisher.accept(derive(inputs));
    }

    /** Stops the coalescing thread and drops pending work; idempotent, never throws. */
    public void dispose() {
        synchronized (lock) {
            disposed = true;
            pending = null;
        }
        scheduler.shutdownNow();
    }

    /**
     * The pure derivation: rows from the watched snapshot (no filesystem
     * access), each attached under the first live session row its
     * document references (D10) or placed in the section (D9, D10's
     * interrupted send-back); terminal tasks stay hidden behind the
     * ribbon except in the Completed view, where assigned ones remain
     * under their session row (D9, D10). Session rows pass through in the
     * given order, unchanged, wrapped only to carry children.
     */
    public static Derivation derive(Inputs in) {
        List<TaskNode.DelayedTaskRow> rows =
                DelayedTaskRows.rows(in.tasks(), in.liveSessionIds(), in.projectSlugs(), in.now());
        Map<String, TaskRow> rowBySession = new LinkedHashMap<>();
        for (TaskRow session : in.sessionRows()) {
            rowBySession.putIfAbsent(session.session().id().toString(), session);
        }
        Map<String, List<TaskNode.DelayedTaskRow>> attached = new LinkedHashMap<>();
        List<TaskNode.DelayedTaskRow> section = new ArrayList<>();
        for (TaskNode.DelayedTaskRow row : DelayedTaskRows.sorted(rows)) {
            DelayedTaskRows.attachedSession(row, rowBySession.keySet()).ifPresentOrElse(
                    session -> attached.computeIfAbsent(session, any -> new ArrayList<>()).add(row),
                    () -> section.add(row));
        }
        List<TaskNode> nodes = new ArrayList<>();
        List<TaskNode> sectionChildren = sectionChildren(in, section);
        if (!sectionChildren.isEmpty()) {
            nodes.add(new TaskNode.TasksSection(
                    in.view() == View.COMPLETED ? "Completed" : "Tasks", sectionChildren));
        }
        for (TaskRow session : rowBySession.values()) {
            List<TaskNode.DelayedTaskRow> assigned =
                    attached.getOrDefault(session.session().id().toString(), List.of());
            List<TaskNode.DelayedTaskRow> children = in.view() == View.COMPLETED
                    ? DelayedTaskRows.completed(assigned)
                    : DelayedTaskRows.active(assigned);
            boolean nameMatches = !in.filter().isEmpty() && contains(session.session().displayName(), in.filter());
            List<TaskNode.DelayedTaskRow> kept = nameMatches ? children
                    : children.stream().filter(row -> DelayedTaskRows.matches(in.filter(), row)).toList();
            boolean retain = switch (in.view()) {
                case ALL -> in.filter().isEmpty() || nameMatches || !kept.isEmpty();
                case TASKS, COMPLETED -> !kept.isEmpty();
                case NEEDS_YOU -> DelayedTaskRows.needsYou(session, DelayedTaskRows.active(assigned))
                        && (in.filter().isEmpty() || nameMatches || !kept.isEmpty());
            };
            if (retain) {
                nodes.add(new TaskNode.SessionTasks(session,
                        kept.stream().map(row -> (TaskNode) row).toList()));
            }
        }
        return new Derivation(nodes);
    }

    /** The section's filtered content for the selected view: active tasks, terminal ones, or unassigned reviews. */
    private static List<TaskNode> sectionChildren(Inputs in, List<TaskNode.DelayedTaskRow> section) {
        List<TaskNode.DelayedTaskRow> content = switch (in.view()) {
            case COMPLETED -> DelayedTaskRows.completed(section);
            case NEEDS_YOU -> DelayedTaskRows.sorted(section).stream()
                    .filter(row -> row.presentation() == TaskState.Presentation.REVIEW).toList();
            default -> DelayedTaskRows.active(section);
        };
        return content.stream()
                .filter(row -> DelayedTaskRows.matches(in.filter(), row))
                .map(row -> (TaskNode) row)
                .toList();
    }

    private static boolean contains(String text, String filter) {
        return text.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT));
    }
}
