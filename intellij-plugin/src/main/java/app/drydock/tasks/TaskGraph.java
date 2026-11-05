package app.drydock.tasks;

import app.drydock.domain.RepositoryId;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Pure, immutable diagnostics over ONE {@link TaskSnapshot} (T06a): P2
 * reference resolution against a CALLER-SUPPLIED repository-to-slug
 * mapping (the scan carries only UUID identities, D2; a {@code Map}
 * adapts as {@code map::get}), the computed inverse follow-up map (D3),
 * and iterative cycle diagnostics (P3). No I/O, no store writes, no
 * readiness computation (that is T07).
 *
 * <p>Reference texts are bare ids or {@code <slug>:<id>} (id after the
 * last colon). A bare id resolves to the unique document with that id
 * across ALL repositories (D4), or is broken: claimed by N files when an
 * {@link TaskSnapshot.IdCollision} lists it, or no task with that id. A
 * qualified ref must name one repository and find the id among that
 * repository's own documents - a globally unique id elsewhere never
 * resolves it (the qualifier scopes it), and the reason names where the
 * id lives. A repository mapped to null or blank never qualifies a
 * reference; two repositories sharing a slug break every reference
 * qualified by it, with the count, never silently picked.</p>
 *
 * <p>Cycles are found ITERATIVELY (explicit stacks, never recursion):
 * every multi-node strongly connected component and every
 * self-referencing task is reported with one concrete closed path
 * ({@code a -> b -> c -> a}); a broken reference is no edge and never
 * part of a cycle. Cost O(V+E); every returned collection is immutable.</p>
 */
public final class TaskGraph {

    /** One resolved {@code after} reference: the stored text, who wrote it, what it names. */
    public record ResolvedReference(String reference, TaskDocument source, TaskDocument target) {
    }

    /** One broken {@code after} reference: the stored text, who wrote it, and the concrete reason. */
    public record BrokenReference(String reference, TaskDocument source, String reason) {
    }

    /** One cycle membership: a closed edge path that starts and ends at {@code task}. */
    public record CycleDiagnostic(TaskId task, List<TaskId> path) {
        public CycleDiagnostic { path = List.copyOf(path); }

        /** The path as id text, e.g. {@code a -> b -> c -> a}. */
        public String text() {
            return path.stream().map(TaskId::text).collect(Collectors.joining(" -> "));
        }
    }

    /** Everything one task's references and cycle memberships say about it; never null lists. */
    public record TaskDiagnostics(TaskId task, List<ResolvedReference> resolved,
            List<BrokenReference> broken, List<CycleDiagnostic> cycles) {
        public TaskDiagnostics {
            resolved = List.copyOf(resolved);
            broken = List.copyOf(broken);
            cycles = List.copyOf(cycles);
        }
    }

    /** Internal resolution outcome: exactly one of target / reason is set. */
    private record Resolution(TaskDocument target, String reason) {
        static Resolution resolved(TaskDocument target) { return new Resolution(target, null); }

        static Resolution broken(String reason) { return new Resolution(null, reason); }
    }

    private final TaskSnapshot snapshot;
    private final Function<RepositoryId, String> projectSlugs;
    private final Map<String, List<RepositoryId>> bySlug;
    private final Map<TaskId, TaskSnapshot.IdCollision> collisions;
    private final Map<TaskId, List<TaskDocument>> dependents;
    private final Map<TaskId, TaskDiagnostics> diagnostics;

    /** Builds the graph for one snapshot; call via {@link #of}. */
    private TaskGraph(TaskSnapshot snapshot, Function<RepositoryId, String> projectSlugs) {
        this.snapshot = snapshot;
        this.projectSlugs = projectSlugs;
        Map<TaskId, TaskSnapshot.IdCollision> collisions = new HashMap<>();
        snapshot.idCollisions().forEach(collision -> collisions.put(collision.id(), collision));
        this.collisions = collisions;
        Map<String, List<RepositoryId>> bySlug = new LinkedHashMap<>();
        this.bySlug = bySlug;
        for (RepositoryId repository : snapshot.repositories()) {
            String slug = slugOf(projectSlugs, repository);
            if (slug != null) bySlug.computeIfAbsent(slug, any -> new ArrayList<>()).add(repository);
        }
        Map<TaskId, List<TaskId>> prerequisites = new LinkedHashMap<>();
        Map<TaskId, List<TaskId>> dependentsById = new LinkedHashMap<>();
        Map<TaskId, List<TaskDocument>> dependentsDocs = new LinkedHashMap<>();
        Map<TaskId, List<ResolvedReference>> resolvedByTask = new LinkedHashMap<>();
        Map<TaskId, List<BrokenReference>> brokenByTask = new LinkedHashMap<>();
        for (TaskDocument document : snapshot.documents()) {
            List<ResolvedReference> resolved = new ArrayList<>();
            List<BrokenReference> brokenHere = new ArrayList<>();
            List<TaskId> after = new ArrayList<>();
            for (String reference : document.after()) {
                Resolution outcome = resolve(reference, projectSlugs, bySlug, collisions);
                if (outcome.target() == null) {
                    BrokenReference failure = new BrokenReference(reference, document, outcome.reason());
                    brokenHere.add(failure);
                } else {
                    TaskDocument target = outcome.target();
                    resolved.add(new ResolvedReference(reference, document, target));
                    after.add(target.id());
                    dependentsById.computeIfAbsent(target.id(), any -> new ArrayList<>()).add(document.id());
                    dependentsDocs.computeIfAbsent(target.id(), any -> new ArrayList<>()).add(document);
                }
            }
            prerequisites.put(document.id(), after);
            resolvedByTask.put(document.id(), resolved);
            brokenByTask.put(document.id(), brokenHere);
        }
        Map<TaskId, List<TaskId>> cyclePaths = cyclePaths(prerequisites, dependentsById);
        Map<TaskId, TaskDiagnostics> diagnosticsMap = new LinkedHashMap<>();
        snapshot.documents().forEach(document -> {
            TaskId id = document.id();
            List<TaskId> cycle = cyclePaths.get(id);
            diagnosticsMap.put(id, new TaskDiagnostics(id, resolvedByTask.get(id), brokenByTask.get(id),
                    cycle == null ? List.of() : List.of(new CycleDiagnostic(id, cycle))));
        });
        Map<TaskId, List<TaskDocument>> dependentsMap = new LinkedHashMap<>();
        dependentsDocs.forEach((id, documents) -> dependentsMap.put(id, List.copyOf(documents)));
        this.dependents = Collections.unmodifiableMap(dependentsMap);
        this.diagnostics = Collections.unmodifiableMap(diagnosticsMap);
    }

    /** The graph of one snapshot; the mapping is applied once per repository and may return null. */
    public static TaskGraph of(TaskSnapshot snapshot, Function<RepositoryId, String> projectSlugs) {
        Objects.requireNonNull(projectSlugs, "projectSlugs");
        return new TaskGraph(Objects.requireNonNull(snapshot, "snapshot"), projectSlugs);
    }

    /** Computed inverse follow-ups (D3): each task and its direct dependents, resolved edges only. */
    public Map<TaskId, List<TaskDocument>> dependents() {
        return dependents;
    }

    /** Every broken reference in the snapshot, in document order. */
    public List<BrokenReference> brokenReferences() {
        return diagnostics.values().stream().flatMap(task -> task.broken().stream()).toList();
    }

    /** Per-task diagnostics for every document, in snapshot order. */
    public List<TaskDiagnostics> diagnostics() {
        return List.copyOf(diagnostics.values());
    }

    /** The diagnostics of one task, or empty when the snapshot holds no such document. */
    public Optional<TaskDiagnostics> diagnosticsOf(TaskId task) {
        return Optional.ofNullable(diagnostics.get(task));
    }

    /** Why {@code reference} names no task in this snapshot (the same reason a stored one would
      * show as broken), or empty when it resolves. */
    public Optional<String> brokenReason(String reference) {
        return Optional.ofNullable(resolve(Objects.requireNonNull(reference, "reference"),
                projectSlugs, bySlug, collisions).reason());
    }

    /** One stored reference text against P2: bare ids globally, qualified ids per slug. */
    private Resolution resolve(String reference, Function<RepositoryId, String> projectSlugs,
            Map<String, List<RepositoryId>> bySlug, Map<TaskId, TaskSnapshot.IdCollision> collisions) {
        int colon = reference.lastIndexOf(':');
        if (colon < 0) {
            TaskDocument document = lookup(reference);
            return document != null ? Resolution.resolved(document)
                    : Resolution.broken(unmatchedReason(reference, "", collisions));
        }
        String slug = reference.substring(0, colon);
        String idText = reference.substring(colon + 1);
        List<RepositoryId> named = bySlug.get(slug);
        if (named == null) {
            return Resolution.broken("no repository with project slug '" + slug + "'");
        }
        if (named.size() > 1) {
            return Resolution.broken("project slug '" + slug + "' is ambiguous: the supplied mapping names "
                    + named.size() + " repositories");
        }
        TaskDocument document = lookup(idText);
        if (document == null) {
            return Resolution.broken(unmatchedReason(idText, " in project '" + slug + "'", collisions));
        }
        String other = slugOf(projectSlugs, document.repository());
        String lives = other != null ? "in project '" + other + "'"
                : "in a repository without a supplied project slug";
        return document.repository().equals(named.get(0)) ? Resolution.resolved(document)
                : Resolution.broken("no task with id '" + idText + "' in project '" + slug + "' (the id is "
                        + lives + ")");
    }

    /** The miss or collision reason for an id resolving nowhere, optionally scoped by a project. */
    private static String unmatchedReason(String idText, String scope,
            Map<TaskId, TaskSnapshot.IdCollision> collisions) {
        TaskSnapshot.IdCollision collision = collisions.get(validId(idText));
        return collision != null
                ? "ambiguous id '" + idText + "' claimed by " + collision.paths().size() + " files"
                : "no task with id '" + idText + "'" + scope;
    }

    /** The unique document with this id text, or null when the text is not a stored id. */
    private TaskDocument lookup(String idText) {
        TaskId id = validId(idText);
        return id == null ? null : snapshot.byId().get(id);
    }

    /** The id text as a TaskId, or null when it is not a valid stem (D1: no invalid id exists). */
    private static TaskId validId(String idText) {
        try {
            return TaskId.of(idText);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The repository's display slug, or null when the mapping supplies none or a blank. */
    private static String slugOf(Function<RepositoryId, String> projectSlugs, RepositoryId repository) {
        String slug = projectSlugs.apply(repository);
        return slug != null && !slug.isBlank() ? slug : null;
    }

    /**
     * Every task's concrete cycle path (P3), keyed by task: iterative
     * Kosaraju components, then two component-restricted BFS per component.
     */
    private static Map<TaskId, List<TaskId>> cyclePaths(Map<TaskId, List<TaskId>> prerequisites,
            Map<TaskId, List<TaskId>> dependents) {
        Map<TaskId, List<TaskId>> paths = new LinkedHashMap<>();
        List<TaskId> postOrder = new ArrayList<>(prerequisites.size());
        Set<TaskId> visited = new HashSet<>();
        for (TaskId node : prerequisites.keySet()) {
            postOrder(node, prerequisites, visited, postOrder);
        }
        Set<TaskId> assigned = new HashSet<>();
        for (int i = postOrder.size() - 1; i >= 0; i--) {
            if (assigned.add(postOrder.get(i))) {
                component(postOrder.get(i), dependents, assigned, paths, prerequisites);
            }
        }
        return paths;
    }

    /** Iterative post-order DFS; one frame per stack entry, never recursion. */
    private static void postOrder(TaskId root, Map<TaskId, List<TaskId>> successors,
            Set<TaskId> visited, List<TaskId> order) {
        if (!visited.add(root)) {
            return;
        }
        Deque<TaskId> stack = new ArrayDeque<>();
        Deque<Iterator<TaskId>> frames = new ArrayDeque<>();
        stack.push(root);
        frames.push(successors.getOrDefault(root, List.of()).iterator());
        while (!stack.isEmpty()) {
            Iterator<TaskId> frame = frames.peek();
            if (frame.hasNext()) {
                TaskId next = frame.next();
                if (visited.add(next)) {
                    stack.push(next);
                    frames.push(successors.getOrDefault(next, List.of()).iterator());
                }
            } else {
                frames.pop();
                order.add(stack.pop());
            }
        }
    }

    /** One component from a reverse-order root: a self-loop or one path per member. */
    private static void component(TaskId root, Map<TaskId, List<TaskId>> dependents,
            Set<TaskId> assigned, Map<TaskId, List<TaskId>> paths,
            Map<TaskId, List<TaskId>> prerequisites) {
        Set<TaskId> component = new LinkedHashSet<>();
        Deque<TaskId> work = new ArrayDeque<>();
        assigned.add(root);
        work.push(root);
        while (!work.isEmpty()) {
            TaskId node = work.pop();
            component.add(node);
            for (TaskId next : dependents.getOrDefault(node, List.of())) {
                if (assigned.add(next)) {
                    work.push(next);
                }
            }
        }
        if (component.size() == 1) {
            if (prerequisites.getOrDefault(root, List.of()).contains(root)) paths.put(root, List.of(root, root));
            return;
        }
        Map<TaskId, TaskId> towardRoot = parents(root, dependents, component);
        Map<TaskId, TaskId> fromRoot = parents(root, prerequisites, component);
        for (TaskId task : component) {
            paths.put(task, memberPath(task, prerequisites, towardRoot, fromRoot));
        }
    }

    /** Breadth-first parents from one root over one adjacency, restricted to a component. */
    private static Map<TaskId, TaskId> parents(TaskId root, Map<TaskId, List<TaskId>> successors,
            Set<TaskId> component) {
        Map<TaskId, TaskId> parent = new LinkedHashMap<>();
        parent.put(root, null);
        Deque<TaskId> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            TaskId node = queue.poll();
            for (TaskId next : successors.getOrDefault(node, List.of())) {
                if (component.contains(next) && !parent.containsKey(next)) {
                    parent.put(next, node);
                    queue.add(next);
                }
            }
        }
        return parent;
    }

    /**
     * One member's closed path: to the component's representative, then
     * back through its tree. The representative itself (chain of one)
     * first steps out to another member, which a multi-node component has.
     */
    private static List<TaskId> memberPath(TaskId task, Map<TaskId, List<TaskId>> prerequisites,
            Map<TaskId, TaskId> towardRoot, Map<TaskId, TaskId> fromRoot) {
        List<TaskId> path = chain(task, towardRoot);
        if (path.size() == 1) {
            for (TaskId next : prerequisites.getOrDefault(task, List.of())) {
                if (!next.equals(task) && towardRoot.containsKey(next)) {
                    path.addAll(chain(next, towardRoot));
                    break;
                }
            }
            return path;
        }
        List<TaskId> fromRootReversed = chain(task, fromRoot);
        Collections.reverse(fromRootReversed);
        path.addAll(fromRootReversed.subList(1, fromRootReversed.size()));
        return path;
    }

    /** The parent chain from one node to its search root, both included. */
    private static List<TaskId> chain(TaskId from, Map<TaskId, TaskId> parent) {
        List<TaskId> chain = new ArrayList<>();
        for (TaskId hop = from; hop != null; hop = parent.get(hop)) {
            chain.add(hop);
        }
        return chain;
    }
}
