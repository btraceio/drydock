package app.drydock.tasks;

import app.drydock.domain.RepositoryId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance matrix for {@link StackUnits} (S1 of docs/StackedPrExecution.md):
 * maximal linear chains as units, forks split, merges as their own unit
 * stacking on the first prerequisite, roots starting immediately, every task
 * in exactly one unit, units in dependency order, and the input validations.
 * Snapshots and graphs come from the public constructors: no files, no
 * store, no mocking.
 */
class StackUnitsTest {

    /** A one-repository snapshot under construction, in the given order. */
    private static final class Store {
        final RepositoryId repository = RepositoryId.newId();
        final List<TaskDocument> documents = new java.util.ArrayList<>();

        void open(String id, String... after) {
            documents.add(new TaskDocument(TaskId.of(id), repository, "Task " + id, null, null,
                    java.util.Optional.empty(), List.of(after), List.of(), "", ""));
        }

        List<TaskId> orderedIds() {
            return documents.stream().map(TaskDocument::id).toList();
        }

        Map<TaskId, List<TaskId>> edges() {
            return StackUnits.inSegmentEdges(documents, graph());
        }

        TaskGraph graph() {
            return TaskGraph.of(snapshot(), repository -> "repo");
        }

        TaskSnapshot snapshot() {
            Map<TaskId, TaskDocument> byId = new java.util.LinkedHashMap<>();
            documents.forEach(document -> byId.put(document.id(), document));
            return new TaskSnapshot(documents, List.of(repository), byId, Map.of(), List.of(), List.of());
        }
    }

    private static List<String> texts(List<TaskId> ids) {
        return ids.stream().map(TaskId::text).toList();
    }

    @Test
    void linearChainIsOneUnitStackingOnNothing() {
        Store store = new Store();
        store.open("a");
        store.open("b", "a");
        store.open("c", "b");
        List<StackUnits.Unit> units = StackUnits.decompose(store.orderedIds(), store.edges());
        assertEquals(1, units.size());
        assertEquals(List.of("a", "b", "c"), texts(units.get(0).tasks()));
        assertTrue(units.get(0).isRoot());
        assertNull(units.get(0).stackBase());
    }

    @Test
    void forkSplitsIntoParentChainAndOneUnitPerForkedChild() {
        Store store = new Store();
        store.open("base");
        store.open("left", "base");
        store.open("right", "base");
        store.open("leftchild", "left");
        List<StackUnits.Unit> units = StackUnits.decompose(store.orderedIds(), store.edges());
        // base continues with "left" (first in dependency order); "right" forks;
        // "leftchild" continues the base-left chain.
        assertEquals(2, units.size());
        assertEquals(List.of("base", "left", "leftchild"), texts(units.get(0).tasks()));
        assertTrue(units.get(0).isRoot());
        assertEquals(List.of("right"), texts(units.get(1).tasks()));
        assertEquals("base", units.get(1).stackBase().text());
        assertEquals(List.of("base"), texts(units.get(1).waits()));
    }

    @Test
    void twoIndependentRootsAreTwoUnitsInOrder() {
        Store store = new Store();
        store.open("one");
        store.open("two");
        List<StackUnits.Unit> units = StackUnits.decompose(store.orderedIds(), store.edges());
        assertEquals(2, units.size());
        assertEquals(List.of("one"), texts(units.get(0).tasks()));
        assertEquals(List.of("two"), texts(units.get(1).tasks()));
        assertTrue(units.get(0).isRoot());
        assertTrue(units.get(1).isRoot());
    }

    @Test
    void mergeOpensItsOwnUnitStackingOnTheFirstPrerequisite() {
        Store store = new Store();
        store.open("a");
        store.open("b");
        store.open("m", "a", "b");
        List<StackUnits.Unit> units = StackUnits.decompose(store.orderedIds(), store.edges());
        assertEquals(3, units.size());
        assertEquals(List.of("m"), texts(units.get(2).tasks()));
        assertEquals(List.of("a", "b"), texts(units.get(2).waits()));
        assertEquals("a", units.get(2).stackBase().text());
    }

    @Test
    void stackedChildWaitsOnlyForItsOwnPrerequisiteTask() {
        Store store = new Store();
        store.open("root");
        store.open("mid", "root");
        store.open("fork", "mid");
        store.open("latechild", "mid");
        List<StackUnits.Unit> units = StackUnits.decompose(store.orderedIds(), store.edges());
        // root->mid->fork is the first chain; latechild forks off mid (mid is taken
        // as left's continuation only while unassigned; latechild's prerequisite mid
        // belongs to the first unit) and stacks on MID's branch, not the unit tip.
        assertEquals(2, units.size());
        assertEquals(List.of("root", "mid", "fork"), texts(units.get(0).tasks()));
        assertEquals(List.of("latechild"), texts(units.get(1).tasks()));
        assertEquals("mid", units.get(1).stackBase().text());
    }

    @Test
    void everyTaskAppearsInExactlyOneUnitAndUnitsCoverTheSegment() {
        Store store = new Store();
        store.open("a");
        store.open("b", "a");
        store.open("c", "a");
        store.open("d", "b", "c");
        store.open("e", "d");
        List<StackUnits.Unit> units = StackUnits.decompose(store.orderedIds(), store.edges());
        long assigned = units.stream().mapToLong(unit -> unit.tasks().size()).sum();
        assertEquals(5, assigned);
        assertEquals(5, units.stream().flatMap(unit -> unit.tasks().stream()).distinct().count());
    }

    @Test
    void inSegmentEdgesResolveGraphReferencesAndDropSatisfiedPrerequisites() {
        Store store = new Store();
        store.open("open-prereq");
        store.open("done-prereq");
        store.open("dropped-prereq");
        store.open("child", "open-prereq", "done-prereq", "dropped-prereq");
        store.documents.set(1, new TaskDocument(TaskId.of("done-prereq"), store.repository, "Task",
                TaskStatus.DONE, null, java.util.Optional.empty(), List.of(), List.of(), "", ""));
        store.documents.set(2, new TaskDocument(TaskId.of("dropped-prereq"), store.repository, "Task",
                TaskStatus.DROPPED, null, java.util.Optional.empty(), List.of(), List.of(), "", ""));
        Map<TaskId, List<TaskId>> edges = store.edges();
        assertEquals(List.of("open-prereq"), texts(edges.get(TaskId.of("child"))));
    }

    @Test
    void decompositionRefusesForeignMembersAndRepeatedTasks() {
        Store store = new Store();
        store.open("a");
        store.open("b", "a");
        Map<TaskId, List<TaskId>> edges = store.edges();
        assertThrows(IllegalArgumentException.class, () -> StackUnits.decompose(
                List.of(TaskId.of("a"), TaskId.of("a")), edges));
        assertThrows(IllegalArgumentException.class, () -> StackUnits.decompose(
                List.of(TaskId.of("a")), Map.of(TaskId.of("a"), List.of(TaskId.of("b")))));
        assertThrows(IllegalArgumentException.class, () -> StackUnits.decompose(
                List.of(TaskId.of("a")), Map.of(TaskId.of("b"), List.of(TaskId.of("a")))));
        assertThrows(IllegalArgumentException.class, () -> new StackUnits.Unit(List.of(), List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> new StackUnits.Unit(
                List.of(TaskId.of("a")), List.of(), TaskId.of("a")));
    }
}
