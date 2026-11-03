package app.drydock.tasks;

import app.drydock.domain.RepositoryId;
import app.drydock.intellij.tasks.TaskNode.TaskRow;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The delayed-task layer of the tool window's tree (T15a): the rows the
 * {@link TaskState} presentation rules place (D9-D11), the section that
 * holds the tasks not under a session, and the wrapper that carries task
 * children under a session row. Every value is derived from one store
 * snapshot plus caller-supplied session rows and clock; nothing here
 * reads disk, recomputes a presentation, or starts a timer.
 *
 * <p>Distinct from the session rows: an {@code app.drydock.tasks.TaskNode}
 * never wraps an agent session alone. The session wrapper
 * {@link SessionTasks} carries the existing session row
 * ({@code app.drydock.intellij.tasks.TaskNode.TaskRow}) UNCHANGED and only
 * adds the delayed-task children the snapshot assigns to it -- this layer
 * adds rows, it never rewrites the session row's own behavior.</p>
 */
public sealed interface TaskNode {

    /**
     * One delayed task, as {@link TaskState.TaskView} places it. The
     * presentation (ready/blocked/review/started/interrupted/terminal), the
     * concrete blocker texts and the live session ids are CARRIED from the
     * view, never recomputed here. {@code storedSessions} is the document's
     * raw session-reference list, live or not, so a foreign, historical or
     * removed session stays linkable (D10) with no session object at hand;
     * {@code stale} is the proposed 14-day age flag (D9), computed against
     * an INJECTED clock by {@link DelayedTaskRows}, never
     * {@code System.now} deep in row code.
     */
    record DelayedTaskRow(TaskState.TaskView view, String title, RepositoryId repository,
            Optional<Instant> created, boolean stale, List<String> storedSessions) implements TaskNode {

        public DelayedTaskRow {
            Objects.requireNonNull(view, "view");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(repository, "repository");
            Objects.requireNonNull(created, "created");
            storedSessions = List.copyOf(storedSessions);
        }

        /**
         * Compatibility constructor without the age inputs: unknown
         * creation, never stale. For callers that predate the age flag;
         * the canonical constructor is the one the model uses.
         */
        public DelayedTaskRow(TaskState.TaskView view, String title, RepositoryId repository,
                List<String> storedSessions) {
            this(view, title, repository, Optional.empty(), false, storedSessions);
        }

        /** The task id, carried on the view (never recomputed). */
        public TaskId id() {
            return view.task();
        }

        /** The carried presentation: the D9-D11 hint the renderer shows. */
        public TaskState.Presentation presentation() {
            return view.presentation();
        }

        /** The carried blocker texts, one concrete line per blocker (D9, D11). */
        public List<String> blockers() {
            return view.blockers();
        }

        /** The live catalog session ids: D10 membership, carried from the view. */
        public List<String> liveSessions() {
            return view.liveSessions();
        }

        /** Done or dropped: hidden behind the filter ribbon (D9). */
        public boolean terminal() {
            return presentation() == TaskState.Presentation.TERMINAL;
        }

        /** D10's link to the last session: the last stored reference, live or not. */
        public Optional<String> lastSession() {
            return storedSessions.isEmpty()
                    ? Optional.empty()
                    : Optional.of(storedSessions.get(storedSessions.size() - 1));
        }
    }

    /**
     * One session row plus the delayed-task children the snapshot assigns
     * to it (D10: a started task shows under its session row and stays
     * there while the session exists, idle or stopped; an ASSIGNED child
     * remains under its session row even after completion). The session
     * row is carried unchanged; only children are added.
     */
    record SessionTasks(TaskRow session, List<TaskNode> children)
            implements TaskNode {

        public SessionTasks {
            Objects.requireNonNull(session, "session");
            children = List.copyOf(children);
        }

        /**
         * Compatibility constructor without children: the plain session
         * row, for callers that build session wrappers before any task
         * snapshot exists (they keep compiling and keep their meaning).
         */
        public SessionTasks(TaskRow session) {
            this(session, List.of());
        }
    }

    /**
     * The Tasks section (D9): the tasks not under a session row -- open
     * ones first (ready, then blocked, then review, then the interrupted
     * ones D10 sends back here), or, in the Completed view, the done and
     * dropped ones. Children arrive pre-sorted and stable (deterministic
     * tie-break by task id) from the model; empty sections are never
     * emitted.
     */
    record TasksSection(String label, List<TaskNode> children) implements TaskNode {
        public TasksSection {
            Objects.requireNonNull(label, "label");
            children = List.copyOf(children);
        }
    }
}
