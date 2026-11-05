package app.drydock.tasks;

import app.drydock.domain.RepositoryId;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

import static app.drydock.tasks.StartPlan.Kind.*;

/**
 * Pure start planning for one target task over one snapshot and its graph (T08a, D7/D8/P3/P4): the
 * reachable UNFINISHED prerequisite closure at all levels (open, started and review prerequisites are
 * the work; done and dropped ones are satisfied, D13, and become context), grouped by repository into
 * SEGMENTS in dependency order (D7/D8) and staged by the segment DAG (stage 1 waits on no open
 * segment; the target's own segment is the last stage, P4.4). Refuses with concrete texts, nothing
 * silently skipped (P3): an unknown or already done/dropped target (it starts nothing, D12); a broken
 * reference or task-level cycle in the planned work (the graph's own texts); a planned task already
 * owned by a live catalog session, named with its session (D10/D12 - no double start); and a segment-
 * level cycle in an acyclic task graph (the project collapse, P4.3), naming the segments and the task
 * edges that close it. Every segment here is open work, so every recorded wait is a wait on an open
 * segment. Cost O(V+E); every pass is iterative; results are immutable; no I/O, no store writes, no
 * prompt text (T09), no launch (T22+). The project mapping should be the one the graph was built with;
 * it names segments only - planning groups by {@link RepositoryId} (D2).
 */
public final class StartPlanner {

    private StartPlanner() {
    }

    /** The plan for starting {@code target}. The graph must be the one built from
      * this same snapshot (a mismatch fails the first lookup, never a wrong answer);
      * the live set is copied, null and blank ids ignored. */
    public static StartPlan plan(TaskId target, TaskSnapshot snapshot, TaskGraph graph,
            Function<RepositoryId, String> projectSlugs, Set<String> liveSessionIds) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(projectSlugs, "projectSlugs");
        Objects.requireNonNull(liveSessionIds, "liveSessionIds");
        Set<String> live = new LinkedHashSet<>();
        liveSessionIds.forEach(id -> { if (id != null && !id.isBlank()) live.add(id); });
        TaskDocument targetDocument = snapshot.byId().get(target);
        if (targetDocument == null) {
            return StartPlan.refused(target, List.of(new StartPlan.Refusal(TARGET_UNKNOWN, "no task with id '"
                    + target.text() + "' in the snapshot (never stored, or excluded after an id collision)")));
        }
        if (targetDocument.status() == TaskStatus.DONE || targetDocument.status() == TaskStatus.DROPPED) {
            return StartPlan.refused(target, List.of(new StartPlan.Refusal(TARGET_FINISHED, "target task "
                    + graph.titleOf(target) + " is already " + targetDocument.status().storedName()
                    + ": it starts nothing")));
        }
        Walk walk = closure(target, snapshot, graph, live);
        if (!walk.refusals.isEmpty()) {
            return StartPlan.refused(target, walk.refusals);
        }
        Map<RepositoryId, List<TaskDocument>> byRepository = new LinkedHashMap<>();
        for (TaskId id : walk.order) {
            TaskDocument document = snapshot.byId().get(id);
            byRepository.computeIfAbsent(document.repository(), any -> new ArrayList<>()).add(document);
        }
        SegmentWaits waits = waitGraph(walk, snapshot, projectSlugs);
        List<StartPlan.Segment> segments = new ArrayList<>();
        byRepository.forEach((repository, tasks) -> segments.add(new StartPlan.Segment(repository,
                nameOf(projectSlugs, repository), topoOrder(tasks, walk.prerequisites, snapshot.byId()))));
        Map<RepositoryId, Integer> depth = new LinkedHashMap<>();
        List<RepositoryId> cycle = segmentWalk(waits.waits(), segments, depth);
        if (cycle != null) {
            return StartPlan.refused(target, List.of(segmentCycle(cycle, waits, projectSlugs)));
        }
        TreeMap<Integer, List<StartPlan.Segment>> byStage = new TreeMap<>();
        segments.forEach(segment -> byStage.computeIfAbsent(depth.get(segment.repository()) + 1,
                any -> new ArrayList<>()).add(segment));
        List<StartPlan.Stage> stages = new ArrayList<>();
        byStage.forEach((number, group) -> stages.add(new StartPlan.Stage(number, group)));
        StartPlan.Stage last = stages.get(stages.size() - 1);
        if (last.segments().size() != 1 || !last.segments().get(0).repository().equals(targetDocument.repository())) {
            throw new IllegalStateException("the target's segment is not the last stage (P4.4)");
        }
        List<StartPlan.Context> context = walk.context.values().stream()
                .map(d -> new StartPlan.Context(d.id(), d.repository(), d.title())).toList();
        List<StartPlan.FollowUp> followUps = graph.dependents().getOrDefault(target, List.of()).stream()
                .map(d -> new StartPlan.FollowUp(d.id(), d.repository(), d.title())).toList();
        return new StartPlan(target, segments, stages, context, followUps, List.of());
    }

    /** One walk's outcome, all in encounter order (the target first). */
    private static final class Walk {
        final List<TaskId> order = new ArrayList<>();
        final Map<TaskId, List<TaskId>> prerequisites = new LinkedHashMap<>();
        final Map<TaskId, TaskDocument> context = new LinkedHashMap<>();
        final List<StartPlan.Refusal> refusals = new ArrayList<>();
    }

    /** Iterative depth-first walk from the target over RESOLVED graph edges only:
      * every visited task's broken references, cycle memberships and live sessions
      * become refusals; unfinished prerequisites are walked further and remembered
      * as this task's edges, done and dropped ones are recorded as context and
      * never walked (their history is done). */
    private static Walk closure(TaskId target, TaskSnapshot snapshot, TaskGraph graph, Set<String> live) {
        Walk walk = new Walk();
        Set<TaskId> visited = new LinkedHashSet<>();
        Deque<TaskId> work = new ArrayDeque<>();
        visited.add(target);
        work.push(target);
        while (!work.isEmpty()) {
            TaskId id = work.pop();
            TaskDocument document = snapshot.byId().get(id);
            walk.order.add(id);
            TaskGraph.TaskDiagnostics diagnostics = graph.diagnosticsOf(id).orElseThrow(
                    () -> new IllegalArgumentException("the graph holds no diagnostics for task '" + id.text()
                            + "'; build it from this snapshot"));
            for (TaskGraph.BrokenReference broken : diagnostics.broken()) {
                walk.refusals.add(new StartPlan.Refusal(BROKEN_REFERENCE, "task " + graph.titleOf(id)
                        + ": broken prerequisite reference '" + broken.reference() + "': " + broken.reason()));
            }
            for (TaskGraph.CycleDiagnostic cycle : diagnostics.cycles()) {
                walk.refusals.add(new StartPlan.Refusal(TASK_CYCLE, "task " + graph.titleOf(id) + " is in a"
                        + " dependency cycle: " + graph.titledPath(cycle)));
            }
            document.sessions().stream().filter(live::contains).findFirst().ifPresent(session ->
                    walk.refusals.add(new StartPlan.Refusal(OWNED_TASK, "task " + graph.titleOf(id)
                            + " is already owned by live session '" + session + "'")));
            List<TaskId> unfinished = new ArrayList<>();
            for (TaskGraph.ResolvedReference reference : diagnostics.resolved()) {
                TaskDocument prerequisite = reference.target();
                if (prerequisite.status() == TaskStatus.DONE || prerequisite.status() == TaskStatus.DROPPED) {
                    walk.context.putIfAbsent(prerequisite.id(), prerequisite);
                } else {
                    unfinished.add(prerequisite.id());
                    if (visited.add(prerequisite.id())) work.push(prerequisite.id());
                }
            }
            walk.prerequisites.put(id, unfinished);
        }
        return walk;
    }

    /** Kahn's algorithm over one segment's own edges, prerequisites first; iterative, stable on encounter order. */
    private static List<TaskDocument> topoOrder(List<TaskDocument> tasks,
            Map<TaskId, List<TaskId>> prerequisites, Map<TaskId, TaskDocument> byId) {
        Set<TaskId> members = new LinkedHashSet<>();
        tasks.forEach(task -> members.add(task.id()));
        Map<TaskId, List<TaskId>> dependents = new LinkedHashMap<>();
        Map<TaskId, Integer> indegree = new LinkedHashMap<>();
        for (TaskDocument task : tasks) {
            indegree.put(task.id(), 0);
            for (TaskId prerequisite : prerequisites.getOrDefault(task.id(), List.of())) {
                if (members.contains(prerequisite)) {
                    dependents.computeIfAbsent(prerequisite, any -> new ArrayList<>()).add(task.id());
                    indegree.merge(task.id(), 1, Integer::sum);
                }
            }
        }
        Deque<TaskId> ready = new ArrayDeque<>();
        tasks.forEach(task -> { if (indegree.get(task.id()) == 0) ready.add(task.id()); });
        List<TaskDocument> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            TaskId id = ready.poll();
            order.add(byId.get(id));
            for (TaskId dependent : dependents.getOrDefault(id, List.of())) {
                if (indegree.merge(dependent, -1, Integer::sum) == 0) ready.add(dependent);
            }
        }
        if (order.size() != tasks.size()) throw new IllegalStateException("dependency order inside a segment"
                + " stalled although the graph reported no cycle: " + order.size() + " of " + tasks.size());
        return order;
    }

    /** The segment wait graph (P4.3) plus one concrete task edge per wait, for refusal texts. */
    private record SegmentWaits(Map<RepositoryId, LinkedHashSet<RepositoryId>> waits,
            Map<RepositoryId, Map<RepositoryId, String>> edges) { }

    /** Segment S waits on segment R when a task in S has an UNFINISHED resolved
      * prerequisite in R (P4.3); a same-repository edge stays inside the segment,
      * and a done or dropped prerequisite satisfies (D13) - neither records a wait. */
    private static SegmentWaits waitGraph(Walk walk, TaskSnapshot snapshot,
            Function<RepositoryId, String> projectSlugs) {
        Map<RepositoryId, LinkedHashSet<RepositoryId>> waits = new LinkedHashMap<>();
        Map<RepositoryId, Map<RepositoryId, String>> edges = new LinkedHashMap<>();
        for (TaskId id : walk.order) {
            RepositoryId segment = snapshot.byId().get(id).repository();
            for (TaskId prerequisite : walk.prerequisites.get(id)) {
                RepositoryId other = snapshot.byId().get(prerequisite).repository();
                if (segment.equals(other)) continue;
                waits.computeIfAbsent(segment, any -> new LinkedHashSet<>()).add(other);
                edges.computeIfAbsent(segment, any -> new LinkedHashMap<>()).putIfAbsent(other,
                        "task '" + snapshot.byId().get(id).title() + "' in '" + nameOf(projectSlugs, segment)
                                + "' waits for '" + snapshot.byId().get(prerequisite).title() + "' in '"
                                + nameOf(projectSlugs, other) + "'");
            }
        }
        return new SegmentWaits(waits, edges);
    }

    /** One iterative colored DFS over segment waits does both graph jobs (P4.4):
      * in post order it records each segment's stage depth (stage 1 waits on
      * nothing), and a gray hit is a segment cycle, returned as a closed path
      * with its first member repeated at the end. Reached only when the task
      * graph reported no cycle, so a hit is the project collapse. */
    private static List<RepositoryId> segmentWalk(Map<RepositoryId, LinkedHashSet<RepositoryId>> waits,
            List<StartPlan.Segment> segments, Map<RepositoryId, Integer> depth) {
        Map<RepositoryId, Integer> color = new LinkedHashMap<>();
        for (StartPlan.Segment root : segments) {
            if (color.containsKey(root.repository())) continue;
            Deque<RepositoryId> stack = new ArrayDeque<>();
            Deque<Iterator<RepositoryId>> frames = new ArrayDeque<>();
            color.put(root.repository(), 1);
            stack.push(root.repository());
            frames.push(waits.getOrDefault(root.repository(), new LinkedHashSet<>()).iterator());
            while (!stack.isEmpty()) {
                Iterator<RepositoryId> frame = frames.peek();
                if (!frame.hasNext()) {
                    frames.pop();
                    RepositoryId done = stack.pop();
                    color.put(done, 2);
                    int deepest = 0;
                    for (RepositoryId wait : waits.getOrDefault(done, new LinkedHashSet<>())) {
                        deepest = Math.max(deepest, depth.get(wait) + 1);
                    }
                    depth.put(done, deepest);
                } else {
                    RepositoryId next = frame.next();
                    Integer state = color.get(next);
                    if (state == null) {
                        color.put(next, 1);
                        stack.push(next);
                        frames.push(waits.getOrDefault(next, new LinkedHashSet<>()).iterator());
                    } else if (state == 1) {
                        List<RepositoryId> path = new ArrayList<>();
                        for (RepositoryId node : stack) { path.add(node); if (node.equals(next)) break; }
                        Collections.reverse(path);
                        path.add(next);
                        return path;
                    }
                }
            }
        }
        return null;
    }

    /** The project-collapse refusal (P4.3): the closed segment path and the task edges that close it. */
    private static StartPlan.Refusal segmentCycle(List<RepositoryId> cycle, SegmentWaits waits,
            Function<RepositoryId, String> projectSlugs) {
        String path = String.join(" -> ",
                cycle.stream().map(r -> "'" + nameOf(projectSlugs, r) + "'").toList());
        List<String> closing = new ArrayList<>();
        for (int i = 0; i < cycle.size() - 1; i++) closing.add(waits.edges().get(cycle.get(i)).get(cycle.get(i + 1)));
        return new StartPlan.Refusal(SEGMENT_CYCLE, "project segments form a cycle although the task graph is"
                + " acyclic: " + path + " (the closing edges: " + String.join("; ", closing) + ")");
    }

    /** The repository's project name, or its uuid text when the mapping supplies none. */
    private static String nameOf(Function<RepositoryId, String> projectSlugs, RepositoryId repository) {
        String slug = projectSlugs.apply(repository);
        return slug != null && !slug.isBlank() ? slug : repository.value().toString();
    }
}
