package app.drydock.review;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Kahn and Tarjan (spec §2.3, §6.1).
 *
 * <p>Hand-rolled rather than taken from a graph library: what this design
 * asks of a graph is a topological sort, strongly-connected components and
 * reachability over tens of nodes, and a library costs a megabyte of
 * transitives, an entry in the jlink module list that a test pins against
 * jdeps, and a POM dependency the jbang jar bundles nothing of.</p>
 *
 * <p>The tie-break is supplied by the caller and must be TOTAL: two runs may
 * not order equal units differently (spec §9.5). A caller-supplied edge that
 * points outside {@code nodes} is rejected with {@link IllegalArgumentException}
 * rather than silently dropped -- absent and broken must not look the same.
 * A node with no such dependency and a node whose dependency the caller
 * forgot to include would otherwise produce identical output, hiding a bug
 * in whatever built {@code dependsOn} behind a graph that looks merely
 * incomplete.</p>
 */
public final class Graphs {

    private Graphs() {
    }

    /**
     * {@code nodes} in reading order, foundation first. Each entry is one
     * unit: a single node, or the members of a cycle collapsed together and
     * ordered by {@code tieBreak}.
     */
    public static <T> List<List<T>> topologicalOrder(
            SortedSet<T> nodes, Function<T, SortedSet<T>> dependsOn, Comparator<T> tieBreak) {
        List<List<T>> components = stronglyConnected(nodes, dependsOn, tieBreak);

        Map<T, Integer> componentOf = new LinkedHashMap<>();
        for (int index = 0; index < components.size(); index++) {
            for (T member : components.get(index)) {
                componentOf.put(member, index);
            }
        }

        // Condense to a DAG over components, then Kahn it.
        Map<Integer, SortedSet<Integer>> prerequisites = new TreeMap<>();
        Map<Integer, SortedSet<Integer>> dependents = new TreeMap<>();
        for (int index = 0; index < components.size(); index++) {
            prerequisites.put(index, new TreeSet<>());
            dependents.put(index, new TreeSet<>());
        }
        // stronglyConnected already walked every node's dependsOn and would
        // have thrown on a target outside nodes, so every prerequisite here
        // is guaranteed to resolve to a component.
        for (T node : nodes) {
            for (T prerequisite : dependsOn.apply(node)) {
                int from = componentOf.get(prerequisite);
                int to = componentOf.get(node);
                if (from == to) {
                    continue;
                }
                prerequisites.get(to).add(from);
                dependents.get(from).add(to);
            }
        }

        Comparator<Integer> byFirstMember =
                Comparator.comparing(index -> components.get(index).get(0), tieBreak);
        TreeSet<Integer> ready = new TreeSet<>(byFirstMember);
        for (int index = 0; index < components.size(); index++) {
            if (prerequisites.get(index).isEmpty()) {
                ready.add(index);
            }
        }

        List<List<T>> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            Integer next = ready.first();
            ready.remove(next);
            ordered.add(List.copyOf(components.get(next)));
            for (Integer dependent : dependents.get(next)) {
                SortedSet<Integer> remaining = prerequisites.get(dependent);
                remaining.remove(next);
                if (remaining.isEmpty()) {
                    ready.add(dependent);
                }
            }
        }
        return List.copyOf(ordered);
    }

    /** Tarjan, iterative so a deep graph cannot overflow the stack. */
    private static <T> List<List<T>> stronglyConnected(
            SortedSet<T> nodes, Function<T, SortedSet<T>> edges, Comparator<T> tieBreak) {
        Map<T, Integer> index = new LinkedHashMap<>();
        Map<T, Integer> lowLink = new LinkedHashMap<>();
        Deque<T> stack = new ArrayDeque<>();
        Set<T> onStack = new LinkedHashSet<>();
        List<List<T>> components = new ArrayList<>();
        int[] counter = {0};

        for (T root : nodes) {
            if (index.containsKey(root)) {
                continue;
            }
            Deque<T> work = new ArrayDeque<>();
            Deque<Iterator<T>> pending = new ArrayDeque<>();
            work.push(root);
            pending.push(edges.apply(root).iterator());
            index.put(root, counter[0]);
            lowLink.put(root, counter[0]++);
            stack.push(root);
            onStack.add(root);

            while (!work.isEmpty()) {
                T node = work.peek();
                Iterator<T> children = pending.peek();
                if (children.hasNext()) {
                    T child = children.next();
                    if (!nodes.contains(child)) {
                        throw new IllegalArgumentException(
                                "dependsOn(" + node + ") named " + child
                                        + ", which is not in nodes");
                    }
                    if (!index.containsKey(child)) {
                        index.put(child, counter[0]);
                        lowLink.put(child, counter[0]++);
                        stack.push(child);
                        onStack.add(child);
                        work.push(child);
                        pending.push(edges.apply(child).iterator());
                    } else if (onStack.contains(child)) {
                        lowLink.put(node, Math.min(lowLink.get(node), index.get(child)));
                    }
                } else {
                    work.pop();
                    pending.pop();
                    if (!work.isEmpty()) {
                        T parent = work.peek();
                        lowLink.put(parent, Math.min(lowLink.get(parent), lowLink.get(node)));
                    }
                    if (lowLink.get(node).equals(index.get(node))) {
                        List<T> component = new ArrayList<>();
                        T member;
                        do {
                            member = stack.pop();
                            onStack.remove(member);
                            component.add(member);
                        } while (!member.equals(node));
                        component.sort(tieBreak);
                        components.add(component);
                    }
                }
            }
        }
        return components;
    }
}
