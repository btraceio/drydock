package app.drydock.tasks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure stacked-branch decomposition of ONE repository segment's ordered task
 * set (S1 of docs/StackedPrExecution.md): the tasks of one segment become
 * LAUNCH UNITS, each running as one session that lands every member task on
 * its own stacked branch. A unit is a maximal linear chain: its members
 * follow each other in the segment's dependency order, and every member
 * after the first has that predecessor as its ONLY in-segment prerequisite
 -- where the graph forks (a task with two or more dependents), the first
 * unassigned dependent in dependency order continues the chain and the
 * others start their own units. A task with several in-segment
 * prerequisites (a merge) cannot continue any chain, so it opens its own
 * unit and stacks on its FIRST prerequisite in dependency order; the other
 * prerequisites are launch waits only.
 *
 * <p>Every unit records which branches it needs before its session may
 * start (S2): the branches of its first task's in-segment prerequisite
 * tasks -- the stack base is the FIRST one, whose branch the unit's first
 * task's branch is created on. A unit with no in-segment prerequisite is a
 * root: it starts immediately and its first branch comes off the
 * repository's default branch. Units are returned in dependency order, and
 * every unit a unit waits for appears earlier, because the input order
 * (the segment's {@code topoOrder}) lists prerequisites first.</p>
 *
 * <p>Branch names are the caller's convention; this class carries task ids
 * only and never mints a name. Pure: no I/O, no store, O(V^2) worst case
 * over the segment's own task count -- segments are small (a plan's share
 * of one repository), well inside the repo's small-set scan cutoff.</p>
 */
public final class StackUnits {

    private StackUnits() {
    }

    /** One launch unit: its member tasks in branch order, the branches it
      * waits for (the first task's in-segment prerequisites, in input
      * order), and the branch it stacks on (the first wait, null for a
      * root). The waits are TASK ids; the branch of a task is the caller's
      * naming convention applied to that id. */
    public record Unit(List<TaskId> tasks, List<TaskId> waits, TaskId stackBase) {
        public Unit {
            Objects.requireNonNull(tasks, "tasks");
            Objects.requireNonNull(waits, "waits");
            if (tasks.isEmpty()) {
                throw new IllegalArgumentException("A launch unit carries at least one task");
            }
            if (stackBase == null != waits.isEmpty()) {
                throw new IllegalArgumentException("A unit stacks on a branch exactly when it waits for one:"
                        + " a root unit waits for nothing, a stacked unit waits for at least one branch");
            }
            tasks = List.copyOf(tasks);
            waits = List.copyOf(waits);
        }

        /** Whether this unit's session may start immediately: no prerequisite branch needed. */
        public boolean isRoot() {
            return stackBase == null;
        }
    }

    /**
     * Decomposes {@code orderedTasks} (one segment's tasks, prerequisites
     * first -- the StartPlanner's {@code topoOrder}) into launch units.
     * {@code inSegmentPrerequisites} maps a task id to its prerequisites
     * that belong to the SAME segment, in input order; an id absent from
     * the map has none. Every id in the prerequisite map must be a member
     * of the segment -- cross-segment waits are the staged plan's business
     * (D8), never a unit's.
     */
    public static List<Unit> decompose(List<TaskId> orderedTasks,
            Map<TaskId, List<TaskId>> inSegmentPrerequisites) {
        Objects.requireNonNull(orderedTasks, "orderedTasks");
        Objects.requireNonNull(inSegmentPrerequisites, "inSegmentPrerequisites");
        Set<TaskId> members = new LinkedHashSet<>(orderedTasks);
        if (members.size() != orderedTasks.size()) {
            throw new IllegalArgumentException("The segment's task order repeats a task");
        }
        for (Map.Entry<TaskId, List<TaskId>> entry : inSegmentPrerequisites.entrySet()) {
            if (!members.contains(entry.getKey())) {
                throw new IllegalArgumentException("A prerequisite is given for task '" + entry.getKey().text()
                        + "', which is not a member of this segment");
            }
            for (TaskId prerequisite : entry.getValue()) {
                if (!members.contains(prerequisite)) {
                    throw new IllegalArgumentException("Task '" + entry.getKey().text() + "' waits for '"
                            + prerequisite.text() + "', which is not a member of this segment");
                }
            }
        }
        Set<TaskId> assigned = new LinkedHashSet<>();
        List<Unit> units = new ArrayList<>();
        for (TaskId task : orderedTasks) {
            if (assigned.contains(task)) {
                continue;
            }
            units.add(unit(task, orderedTasks, inSegmentPrerequisites, members, assigned));
        }
        return List.copyOf(units);
    }

    /** One unit grown from {@code first} along the chain rule, marking its members assigned. */
    private static Unit unit(TaskId first, List<TaskId> orderedTasks,
            Map<TaskId, List<TaskId>> prerequisites, Set<TaskId> members, Set<TaskId> assigned) {
        List<TaskId> tasks = new ArrayList<>();
        tasks.add(first);
        assigned.add(first);
        List<TaskId> waits = new ArrayList<>(prerequisites.getOrDefault(first, List.of()));
        while (true) {
            TaskId last = tasks.get(tasks.size() - 1);
            TaskId next = null;
            for (TaskId candidate : orderedTasks) { // dependency order: prerequisites first
                if (assigned.contains(candidate)) {
                    continue;
                }
                List<TaskId> required = prerequisites.getOrDefault(candidate, List.of());
                if (required.size() == 1 && required.get(0).equals(last)) {
                    next = candidate;
                    break;
                }
            }
            if (next == null) {
                break;
            }
            tasks.add(next);
            assigned.add(next);
        }
        return new Unit(tasks, waits, waits.isEmpty() ? null : waits.get(0));
    }

    /** The in-segment prerequisite edges of one segment's ordered tasks,
      * resolved through {@code graph} (the {@code after} field holds
      * reference TEXTS; only the graph resolves them). A resolved
      * prerequisite outside the segment (another repository's share) or a
      * satisfied one (done or dropped, D13) is dropped here:
      * cross-repository waits belong to the stages (D8), and a satisfied
      * prerequisite starts nothing. The graph must be the one built from
      * the snapshot the documents came from. */
    public static Map<TaskId, List<TaskId>> inSegmentEdges(List<TaskDocument> orderedTasks, TaskGraph graph) {
        Objects.requireNonNull(orderedTasks, "orderedTasks");
        Objects.requireNonNull(graph, "graph");
        Set<TaskId> members = new LinkedHashSet<>();
        for (TaskDocument document : orderedTasks) {
            members.add(document.id());
        }
        Map<TaskId, List<TaskId>> edges = new LinkedHashMap<>();
        for (TaskDocument document : orderedTasks) {
            List<TaskId> inside = new ArrayList<>();
            for (TaskGraph.ResolvedReference reference : graph.diagnosticsOf(document.id())
                    .orElseThrow(() -> new IllegalArgumentException("the graph holds no diagnostics for task '"
                            + document.id().text() + "'; build it from this snapshot")).resolved()) {
                TaskDocument target = reference.target();
                if (members.contains(target.id()) && target.status() != TaskStatus.DONE
                        && target.status() != TaskStatus.DROPPED) {
                    inside.add(target.id());
                }
            }
            edges.put(document.id(), inside);
        }
        return edges;
    }
}
