package app.drydock.tasks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pure, immutable graph projection of one "mirror this task" request: the
 * selected root's resolved prerequisite closure (every status), its unique
 * direct edges and a deterministic prerequisite-first order. No I/O and no
 * service, settings, writer or UI calls; per-system dispositions are
 * prepared data supplied by the caller through {@link #withDispositions}.
 *
 * <p>Reference and cycle authority is only
 * {@link TaskGraph#diagnosticsOf(TaskId)} ({@code resolved}, {@code broken},
 * {@code cycles}); dependents are never consulted. The closure is collected
 * with iterative worklists and keeps going through reused, ineligible and
 * cyclic prerequisites. A missing/collided root or a root with a cycle
 * diagnostic yields a whole-run {@link Refusal}; otherwise exactly the
 * reachable tasks with a cycle diagnostic are excluded, broken and
 * excluded-incident edges are dropped with reasons, and the rest is ordered
 * with Kahn's algorithm, ties broken by snapshot document ordinal.</p>
 */
public final class TaskMirrorPlan {

    /** Why the whole run is refused, with the concrete closed path (ids and titles) when cyclic. */
    public record Refusal(String message, List<TaskId> path, List<String> titles) {
        public Refusal {
            path = List.copyOf(path);
            titles = List.copyOf(titles);
        }
    }

    /** One unique direct prerequisite edge; {@code reference} is the first stored text naming it. */
    public record Edge(TaskDocument prerequisite, TaskDocument dependent, String reference) {
    }

    /** An edge that is not part of the ordered graph; {@code target} is null for a broken reference. */
    public record DroppedEdge(String reference, TaskDocument source, TaskDocument target, String reason) {
    }

    /** A reachable cycle member left out of the plan, with its concrete closed path. */
    public record Exclusion(TaskDocument task, List<TaskId> path, List<String> titles) {
        public Exclusion {
            path = List.copyOf(path);
            titles = List.copyOf(titles);
        }
    }

    /** A reachable task's cycle path kept as a warning (members are excluded, so it repeats them). */
    public record CycleWarning(TaskId task, List<TaskId> path, List<String> titles) {
        public CycleWarning {
            path = List.copyOf(path);
            titles = List.copyOf(titles);
        }
    }

    /** What to do for one task in the requested system. */
    public sealed interface Disposition {
        /** Reuse the exact stored address. */
        record Reuse(String address) implements Disposition {
        }

        /** Create a new issue. */
        record Create() implements Disposition {
        }

        /** Do not create, with a concrete reason. */
        record Skip(String reason) implements Disposition {
        }

        /**
         * Reuse of the first recognized mirror of {@code kind} in stored order (other-system
         * mirrors are not counted), else create.
         */
        static Disposition forMirrors(List<String> mirrors, TaskMirror.Kind kind) {
            for (String address : mirrors) {
                if (TaskMirror.kindOf(address).filter(kind::equals).isPresent()) {
                    return new Reuse(address);
                }
            }
            return new Create();
        }
    }

    private final TaskDocument root;
    private final Refusal refusal;
    private final List<TaskDocument> order;
    private final List<Edge> edges;
    private final Map<TaskId, List<TaskDocument>> prerequisites;
    private final List<Exclusion> excluded;
    private final List<DroppedEdge> dropped;
    private final List<TaskGraph.BrokenReference> brokenWarnings;
    private final List<CycleWarning> cycleWarnings;
    private final Map<TaskId, Disposition> dispositions;

    private TaskMirrorPlan(TaskDocument root, Refusal refusal, List<TaskDocument> order, List<Edge> edges,
            Map<TaskId, List<TaskDocument>> prerequisites, List<Exclusion> excluded, List<DroppedEdge> dropped,
            List<TaskGraph.BrokenReference> brokenWarnings, List<CycleWarning> cycleWarnings,
            Map<TaskId, Disposition> dispositions) {
        this.root = root;
        this.refusal = refusal;
        this.order = List.copyOf(order);
        this.edges = List.copyOf(edges);
        Map<TaskId, List<TaskDocument>> copy = new LinkedHashMap<>();
        prerequisites.forEach((id, list) -> copy.put(id, List.copyOf(list)));
        this.prerequisites = Collections.unmodifiableMap(copy);
        this.excluded = List.copyOf(excluded);
        this.dropped = List.copyOf(dropped);
        this.brokenWarnings = List.copyOf(brokenWarnings);
        this.cycleWarnings = List.copyOf(cycleWarnings);
        this.dispositions = Collections.unmodifiableMap(new LinkedHashMap<>(dispositions));
    }

    private static TaskMirrorPlan refused(TaskDocument root, Refusal refusal) {
        return new TaskMirrorPlan(root, refusal, List.of(), List.of(), Map.of(), List.of(), List.of(), List.of(),
                List.of(), Map.of());
    }

    /** Projects the closure of {@code rootId}; never throws for a missing/cyclic root, it refuses. */
    public static TaskMirrorPlan of(TaskSnapshot snapshot, TaskGraph graph, TaskId rootId) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(rootId, "rootId");
        TaskDocument root = snapshot.byId().get(rootId);
        Optional<TaskGraph.TaskDiagnostics> rootDiagnostics = graph.diagnosticsOf(rootId);
        if (root == null || rootDiagnostics.isEmpty()) {
            boolean collided = snapshot.idCollisions().stream().anyMatch(c -> c.id().equals(rootId));
            return refused(null, new Refusal(collided
                    ? "task id '" + rootId.text() + "' is claimed by more than one file"
                    : "no task with id '" + rootId.text() + "'", List.of(), List.of()));
        }
        if (!rootDiagnostics.get().cycles().isEmpty()) {
            TaskGraph.CycleDiagnostic cycle = rootDiagnostics.get().cycles().get(0);
            List<String> titles = titles(snapshot, cycle.path());
            return refused(root, new Refusal("'" + root.title() + "' is part of a prerequisite cycle: "
                    + String.join(" -> ", titles) + " (" + cycle.text() + ")", cycle.path(), titles));
        }

        Map<TaskId, Integer> ordinal = new HashMap<>();
        List<TaskDocument> documents = snapshot.documents();
        for (int i = 0; i < documents.size(); i++) {
            ordinal.putIfAbsent(documents.get(i).id(), i);
        }

        // Closure: iterative worklist over resolved references only.
        Map<TaskId, TaskDocument> reachable = new LinkedHashMap<>();
        Deque<TaskDocument> work = new ArrayDeque<>();
        reachable.put(rootId, root);
        work.add(root);
        while (!work.isEmpty()) {
            TaskDocument node = work.poll();
            for (TaskGraph.ResolvedReference ref : diagnostics(graph, node).resolved()) {
                if (reachable.putIfAbsent(ref.target().id(), ref.target()) == null) {
                    work.add(ref.target());
                }
            }
        }

        Set<TaskId> excludedIds = new LinkedHashSet<>();
        List<Exclusion> exclusions = new ArrayList<>();
        List<CycleWarning> cycleWarnings = new ArrayList<>();
        List<TaskGraph.BrokenReference> broken = new ArrayList<>();
        for (TaskDocument node : reachable.values()) {
            TaskGraph.TaskDiagnostics d = diagnostics(graph, node);
            broken.addAll(d.broken());
            if (!d.cycles().isEmpty()) {
                excludedIds.add(node.id());
                TaskGraph.CycleDiagnostic cycle = d.cycles().get(0);
                List<String> titles = titles(snapshot, cycle.path());
                exclusions.add(new Exclusion(node, cycle.path(), titles));
                d.cycles().forEach(c -> cycleWarnings.add(new CycleWarning(node.id(), c.path(),
                        titles(snapshot, c.path()))));
            }
        }

        List<Edge> edges = new ArrayList<>();
        List<DroppedEdge> dropped = new ArrayList<>();
        Set<String> seenPairs = new HashSet<>();
        for (TaskDocument node : reachable.values()) {
            TaskGraph.TaskDiagnostics d = diagnostics(graph, node);
            for (TaskGraph.BrokenReference b : d.broken()) {
                dropped.add(new DroppedEdge(b.reference(), node, null, "broken reference: " + b.reason()));
            }
            for (TaskGraph.ResolvedReference ref : d.resolved()) {
                TaskDocument target = ref.target();
                if (!seenPairs.add(target.id().text() + "\n" + node.id().text())) continue;
                if (excludedIds.contains(node.id()) || excludedIds.contains(target.id())) {
                    TaskDocument member = excludedIds.contains(target.id()) ? target : node;
                    dropped.add(new DroppedEdge(ref.reference(), node, target,
                            "'" + member.title() + "' (" + member.id().text() + ") is in a prerequisite cycle"));
                } else {
                    edges.add(new Edge(target, node, ref.reference()));
                }
            }
        }

        // Kahn on prerequisite -> dependent adjacency; ties by snapshot ordinal.
        Map<TaskId, TaskDocument> members = new LinkedHashMap<>();
        reachable.forEach((id, doc) -> { if (!excludedIds.contains(id)) members.put(id, doc); });
        Map<TaskId, Integer> indegree = new HashMap<>();
        Map<TaskId, List<TaskId>> out = new HashMap<>();
        members.keySet().forEach(id -> indegree.put(id, 0));
        for (Edge e : edges) {
            indegree.merge(e.dependent().id(), 1, Integer::sum);
            out.computeIfAbsent(e.prerequisite().id(), any -> new ArrayList<>()).add(e.dependent().id());
        }
        PriorityQueue<TaskDocument> ready = new PriorityQueue<>(
                (a, b) -> Integer.compare(ordinal.get(a.id()), ordinal.get(b.id())));
        members.forEach((id, doc) -> { if (indegree.get(id) == 0) ready.add(doc); });
        List<TaskDocument> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            TaskDocument next = ready.poll();
            order.add(next);
            for (TaskId dependent : out.getOrDefault(next.id(), List.of())) {
                if (indegree.merge(dependent, -1, Integer::sum) == 0) ready.add(members.get(dependent));
            }
        }

        Map<TaskId, Integer> position = new HashMap<>();
        for (int i = 0; i < order.size(); i++) position.put(order.get(i).id(), i);
        Map<TaskId, List<TaskDocument>> direct = new LinkedHashMap<>();
        order.forEach(doc -> direct.put(doc.id(), new ArrayList<>()));
        for (Edge e : edges) direct.get(e.dependent().id()).add(e.prerequisite());
        direct.values().forEach(list -> list.sort(
                (a, b) -> Integer.compare(position.get(a.id()), position.get(b.id()))));

        return new TaskMirrorPlan(root, null, order, edges, direct, exclusions, dropped, broken, cycleWarnings,
                Map.of());
    }

    private static TaskGraph.TaskDiagnostics diagnostics(TaskGraph graph, TaskDocument node) {
        return graph.diagnosticsOf(node.id()).orElseThrow(
                () -> new IllegalStateException("no diagnostics for task " + node.id().text()));
    }

    private static List<String> titles(TaskSnapshot snapshot, List<TaskId> path) {
        return path.stream().map(id -> {
            TaskDocument doc = snapshot.byId().get(id);
            return doc != null ? doc.title() : id.text();
        }).collect(Collectors.toList());
    }

    /** A copy carrying the caller-prepared dispositions (by task id) for the requested system. */
    public TaskMirrorPlan withDispositions(Map<TaskId, Disposition> prepared) {
        return new TaskMirrorPlan(root, refusal, order, edges, prerequisites, excluded, dropped, brokenWarnings,
                cycleWarnings, prepared);
    }

    /** The whole-run refusal, or empty when the plan is usable. */
    public Optional<Refusal> refusal() { return Optional.ofNullable(refusal); }

    /** The selected root document, or null for a missing/collided root. */
    public TaskDocument root() { return root; }

    /** Participants, prerequisites first. */
    public List<TaskDocument> order() { return order; }

    /** Unique direct edges between participants. */
    public List<Edge> edges() { return edges; }

    /** The participant's direct prerequisites in plan order; empty for a non-participant. */
    public List<TaskDocument> prerequisitesOf(TaskId task) { return prerequisites.getOrDefault(task, List.of()); }

    /** Reachable cycle members left out of the plan. */
    public List<Exclusion> excluded() { return excluded; }

    /** Edges removed from the plan, with reasons. */
    public List<DroppedEdge> droppedEdges() { return dropped; }

    /** Reachable broken-reference warnings. */
    public List<TaskGraph.BrokenReference> brokenReferences() { return brokenWarnings; }

    /** Reachable cycle paths. */
    public List<CycleWarning> cycles() { return cycleWarnings; }

    /** The prepared disposition of a task, or empty when none was supplied. */
    public Optional<Disposition> dispositionOf(TaskId task) { return Optional.ofNullable(dispositions.get(task)); }
}
