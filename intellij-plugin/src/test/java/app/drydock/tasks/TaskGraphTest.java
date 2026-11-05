package app.drydock.tasks;

import app.drydock.domain.RepositoryId;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T06b acceptance matrix for {@link TaskGraph}: P2 reference resolution,
 * D4 collision policy, D3 inverse follow-ups, P3 cycles. Snapshots are
 * built via the public constructors: no files, no store, no mocking.
 */
class TaskGraphTest {

    /** A snapshot under construction: repositories, documents, collisions. */
    private static final class Store {
        final Map<String, RepositoryId> repos = new LinkedHashMap<>();
        final List<TaskDocument> documents = new ArrayList<>();
        final Map<TaskId, TaskDocument> byId = new LinkedHashMap<>();
        final List<TaskSnapshot.IdCollision> collisions = new ArrayList<>();

        RepositoryId repo(String name) {
            return repos.computeIfAbsent(name, any -> RepositoryId.newId());
        }

        void add(String repo, String id, String... after) {
            TaskDocument document = new TaskDocument(TaskId.of(id), repo(repo), "Task " + id, null,
                    null, Optional.empty(), List.of(after), List.of(), "", "");
            documents.add(document);
            byId.put(document.id(), document);
        }

        void collision(String id, String... paths) {
            collisions.add(new TaskSnapshot.IdCollision(TaskId.of(id), List.of(paths).stream().map(Path::of).toList()));
        }

        TaskSnapshot snapshot() {
            return new TaskSnapshot(documents, new ArrayList<>(repos.values()), byId, Map.of(), List.of(), collisions);
        }

        Function<RepositoryId, String> slugs(Object... nameSlugPairs) {
            Map<RepositoryId, String> mapping = new LinkedHashMap<>();
            for (int i = 0; i < nameSlugPairs.length; i += 2) {
                mapping.put(repo((String) nameSlugPairs[i]), (String) nameSlugPairs[i + 1]);
            }
            return mapping::get;
        }
    }

    private static TaskId id(String text) { return TaskId.of(text); }

    private static String reasonFor(TaskGraph graph, TaskId source, String reference) {
        return graph.diagnosticsOf(source).orElseThrow().broken().stream()
                .filter(broken -> broken.reference().equals(reference)).findFirst()
                .map(TaskGraph.BrokenReference::reason).orElse("<no broken ref>");
    }

    private static boolean resolvesTo(TaskGraph graph, TaskId source, String reference, TaskId target) {
        return graph.diagnosticsOf(source).orElseThrow().resolved().stream()
                .filter(resolved -> resolved.reference().equals(reference))
                .anyMatch(resolved -> resolved.target().id().equals(target));
    }

    private static String cycleText(TaskGraph graph, TaskId task) {
        List<TaskGraph.CycleDiagnostic> cycles = graph.diagnosticsOf(task).orElseThrow().cycles();
        return cycles.size() == 1 ? cycles.get(0).text() : "<" + cycles.size() + " cycles>";
    }

    /** Every path step is a resolved edge and the path closes on its own task. */
    private static boolean closedWalk(TaskGraph graph, TaskGraph.CycleDiagnostic cycle) {
        List<TaskId> path = cycle.path();
        if (path.size() < 2 || !path.get(0).equals(cycle.task())
                || !path.get(path.size() - 1).equals(cycle.task())) {
            return false;
        }
        return IntStream.range(1, path.size())
                .allMatch(i -> resolvesTo(graph, path.get(i - 1), path.get(i).text(), path.get(i)));
    }

    // Matrix 1: bare-id global precedence and the collision stem policy.

    @Test
    void bareIdResolvesTheUniqueDocumentAcrossRepositories() {
        Store store = new Store();
        store.add("alpha", "bare-a", "cross-b");
        store.add("beta", "cross-b");
        TaskGraph graph = TaskGraph.of(store.snapshot(), store.slugs("alpha", "alpha", "beta", "beta"));
        assertTrue(resolvesTo(graph, id("bare-a"), "cross-b", id("cross-b")), "bare id is global");
        assertTrue(graph.brokenReferences().isEmpty(), "nothing broken");
    }

    @Test
    void collidedStemIsBrokenWithTheClaimCountForBareAndQualifiedForms() {
        Store store = new Store();
        store.collision("clash-x", "/tmp/one.md", "/tmp/two.md");
        store.add("alpha", "coll-a", "clash-x", "alpha:clash-x");
        TaskGraph graph = TaskGraph.of(store.snapshot(), store.slugs("alpha", "alpha"));
        assertEquals("ambiguous id 'clash-x' claimed by 2 files", reasonFor(graph, id("coll-a"), "clash-x"));
        assertEquals("ambiguous id 'clash-x' claimed by 2 files",
                reasonFor(graph, id("coll-a"), "alpha:clash-x"));
    }

    @Test
    void brokenReasonChecksAnUnstoredReferenceByTheSameRules() {
        Store store = new Store();
        store.add("alpha", "bare-a");
        store.add("beta", "cross-b");
        TaskGraph graph = TaskGraph.of(store.snapshot(), store.slugs("alpha", "alpha", "beta", "beta"));
        assertEquals(Optional.empty(), graph.brokenReason("cross-b"), "a bare id resolves");
        assertEquals(Optional.empty(), graph.brokenReason("beta:cross-b"), "a qualified id resolves");
        assertEquals(Optional.of("no task with id '<pr-task>'"), graph.brokenReason("<pr-task>"));
        assertEquals(Optional.of("no repository with project slug 'ghost'"), graph.brokenReason("ghost:bare-a"));
    }

    // Matrix 2: qualified refs scope per slug, never a global fallback.

    @Test
    void qualifiedRefResolvesWithinTheNamedRepositoryOnly() {
        Store store = new Store();
        store.add("alpha", "qual-a", "beta:cross-b", "alpha:cross-b");
        store.add("beta", "cross-b");
        TaskGraph graph = TaskGraph.of(store.snapshot(), store.slugs("alpha", "alpha", "beta", "beta"));
        assertTrue(resolvesTo(graph, id("qual-a"), "beta:cross-b", id("cross-b")));
        String wrongScope = reasonFor(graph, id("qual-a"), "alpha:cross-b");
        assertTrue(wrongScope.contains("no task with id 'cross-b' in project 'alpha'")
                && wrongScope.contains("the id is in project 'beta'"), "no global fallback: " + wrongScope);
    }

    @Test
    void unknownSlugAndDuplicateSlugsBreakWithConcreteReasons() {
        Store store = new Store();
        store.add("alpha", "qual-a", "ghost:cross-b");
        store.add("beta", "cross-b");
        TaskGraph graph = TaskGraph.of(store.snapshot(), store.slugs("alpha", "alpha", "beta", "beta"));
        assertEquals("no repository with project slug 'ghost'", reasonFor(graph, id("qual-a"), "ghost:cross-b"));
        Store dup = new Store();
        dup.add("alpha", "dup-a");
        dup.add("gamma", "dup-b", "dup:dup-a");
        TaskGraph dupGraph = TaskGraph.of(dup.snapshot(), dup.slugs("alpha", "dup", "gamma", "dup"));
        assertEquals("project slug 'dup' is ambiguous: the supplied mapping names 2 repositories",
                reasonFor(dupGraph, id("dup-b"), "dup:dup-a"));
    }

    // Matrix 2 (text forms) and Matrix 8: invalid after text never throws.

    @Test
    void invalidAndUnusualAfterTextsBreakWithReasonsNeverThrown() {
        Store store = new Store();
        store.add("alpha", "broken-a", "missing-id", "alpha:", "a:b:c", "Bad_ID", "also bad id");
        TaskGraph graph = TaskGraph.of(store.snapshot(), store.slugs("alpha", "alpha"));
        assertEquals("no task with id 'missing-id'", reasonFor(graph, id("broken-a"), "missing-id"));
        assertEquals("no task with id '' in project 'alpha'", reasonFor(graph, id("broken-a"), "alpha:"));
        assertEquals("no repository with project slug 'a:b'", reasonFor(graph, id("broken-a"), "a:b:c"));
        assertEquals("no task with id 'Bad_ID'", reasonFor(graph, id("broken-a"), "Bad_ID"));
        assertEquals("no task with id 'also bad id'", reasonFor(graph, id("broken-a"), "also bad id"));
        assertTrue(graph.diagnosticsOf(id("broken-a")).orElseThrow().resolved().isEmpty());
    }

    // Matrix 3: unslugged repositories are bare-reachable, never qualifiable.

    @Test
    void repositoryWithoutSlugIsBareReachableAndNeverAQualifier() {
        Store store = new Store();
        store.add("delta", "noslug-task");
        store.add("alpha", "reach-a", "noslug-task", "delta:noslug-task");
        TaskGraph graph = TaskGraph.of(store.snapshot(), store.slugs("alpha", "alpha"));
        assertTrue(resolvesTo(graph, id("reach-a"), "noslug-task", id("noslug-task")), "bare reach");
        assertEquals("no repository with project slug 'delta'", reasonFor(graph, id("reach-a"), "delta:noslug-task"));
        Store blank = new Store();
        blank.add("delta", "blank-task");
        blank.add("alpha", "blank-a", "delta:blank-task");
        TaskGraph blankGraph = TaskGraph.of(blank.snapshot(), blank.slugs("alpha", "alpha", "delta", "  "));
        assertEquals("no repository with project slug 'delta'",
                reasonFor(blankGraph, id("blank-a"), "delta:blank-task"));
    }

    // Matrix 4: cycles with exact path text; tails and broken-only tasks off cycles.

    @Test
    void selfReferenceIsAOneNodeCycleWithExactText() {
        Store store = new Store();
        store.add("alpha", "self-x", "self-x");
        TaskGraph graph = TaskGraph.of(store.snapshot(), store.slugs("alpha", "alpha"));
        assertEquals("self-x -> self-x", cycleText(graph, id("self-x")));
    }

    @Test
    void twoNodeCycleCarriesItsExactPathOnBothTasks() {
        Store store = new Store();
        store.add("alpha", "ring-a", "ring-b");
        store.add("alpha", "ring-b", "ring-a");
        TaskGraph graph = TaskGraph.of(store.snapshot(), store.slugs("alpha", "alpha"));
        assertEquals("ring-a -> ring-b -> ring-a", cycleText(graph, id("ring-a")));
        assertEquals("ring-b -> ring-a -> ring-b", cycleText(graph, id("ring-b")));
    }

    @Test
    void threeNodeCycleCarriesItsExactRotationAndTailsStayOffIt() {
        Store store = new Store();
        store.add("alpha", "ring-a", "ring-b");
        store.add("alpha", "ring-b", "ring-c");
        store.add("alpha", "ring-c", "ring-a");
        store.add("alpha", "tail-t", "ring-a");
        store.add("alpha", "orphan-o", "no-such-task");
        TaskGraph graph = TaskGraph.of(store.snapshot(), store.slugs("alpha", "alpha"));
        assertEquals("ring-a -> ring-b -> ring-c -> ring-a", cycleText(graph, id("ring-a")));
        assertEquals("ring-b -> ring-c -> ring-a -> ring-b", cycleText(graph, id("ring-b")));
        assertEquals("ring-c -> ring-a -> ring-b -> ring-c", cycleText(graph, id("ring-c")));
        assertTrue(graph.diagnosticsOf(id("tail-t")).orElseThrow().cycles().isEmpty(), "tail off");
        assertTrue(graph.diagnosticsOf(id("orphan-o")).orElseThrow().cycles().isEmpty(), "broken-only off");
        assertTrue(graph.dependents().get(id("ring-a")).stream().anyMatch(d -> d.id().equals(id("tail-t"))),
                "a cycle member still has its dependents");
        assertTrue(closedWalk(graph, graph.diagnosticsOf(id("ring-a")).orElseThrow().cycles().get(0)));
    }

    @Test
    void overlappingCyclesGiveEveryMemberOneValidClosedPath() {
        Store store = new Store();
        store.add("alpha", "o-a", "o-b");
        store.add("alpha", "o-b", "o-a", "o-c");
        store.add("alpha", "o-c", "o-b");
        TaskGraph graph = TaskGraph.of(store.snapshot(), store.slugs("alpha", "alpha"));
        for (TaskId member : List.of(id("o-a"), id("o-b"), id("o-c"))) {
            List<TaskGraph.CycleDiagnostic> cycles = graph.diagnosticsOf(member).orElseThrow().cycles();
            assertEquals(1, cycles.size(), member.text() + " has exactly one cycle");
            assertTrue(closedWalk(graph, cycles.get(0)), member.text() + " path is a closed walk");
        }
    }

    // Matrix 5: inverse follow-ups from resolved edges only, in snapshot order.

    @Test
    void dependentsListDirectFollowUpsOfResolvedEdgesOnly() {
        Store store = new Store();
        store.add("alpha", "dm-a");
        store.add("alpha", "dm-b", "dm-a");
        store.add("alpha", "dm-c", "dm-a");
        store.add("alpha", "dm-d", "dm-b", "dm-c", "ghost:dm-a");
        TaskGraph graph = TaskGraph.of(store.snapshot(), store.slugs("alpha", "alpha"));
        assertEquals(List.of(id("dm-b"), id("dm-c")),
                graph.dependents().get(id("dm-a")).stream().map(TaskDocument::id).toList());
        assertTrue(graph.dependents().getOrDefault(id("dm-d"), List.of()).isEmpty(), "no dependents");
        assertEquals(2, graph.dependents().get(id("dm-a")).size(), "broken ref claims no entry");
        assertEquals(1, graph.diagnosticsOf(id("dm-d")).orElseThrow().broken().size());
    }

    @Test
    void diagnosticsCoverEveryDocumentInSnapshotOrderAndNoOthers() {
        Store store = new Store();
        store.add("alpha", "i-a", "i-b");
        store.add("alpha", "i-b");
        TaskGraph graph = TaskGraph.of(store.snapshot(), store.slugs("alpha", "alpha"));
        assertEquals(List.of(id("i-a"), id("i-b")),
                graph.diagnostics().stream().map(TaskGraph.TaskDiagnostics::task).toList());
        assertTrue(graph.diagnosticsOf(id("nope")).isEmpty());
    }

    // Matrix 6: the non-recursive proof, a committed 1000-node chain.

    @Test
    void thousandNodeChainResolvesFullyAndStaysAcyclic() {
        int size = 1000;
        Store store = new Store();
        List<String> ids = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            ids.add(String.format("chain-%04d", i));
        }
        for (int i = 0; i < size; i++) {
            store.add("alpha", ids.get(i), i + 1 < size ? new String[] {ids.get(i + 1)} : new String[0]);
        }
        TaskGraph graph = TaskGraph.of(store.snapshot(), store.slugs("alpha", "alpha"));
        assertEquals(size - 1, graph.diagnostics().stream()
                .mapToInt(task -> task.resolved().size()).sum(), "999 refs resolve");
        assertTrue(graph.diagnostics().stream().allMatch(task -> task.cycles().isEmpty()), "acyclic");
        assertEquals(size, graph.diagnostics().size());
        List<TaskDocument> dependents = graph.dependents().get(id("chain-0500"));
        assertEquals(1, dependents.size());
        assertEquals(id("chain-0499"), dependents.get(0).id(), "inverse map at every link");
    }

    // Matrix 7: every returned collection rejects mutation.

    @Test
    void everyReturnedCollectionRejectsMutation() {
        Store store = new Store();
        store.add("alpha", "im-a", "im-b");
        store.add("alpha", "im-b");
        store.add("alpha", "im-c", "missing");
        TaskGraph graph = TaskGraph.of(store.snapshot(), store.slugs("alpha", "alpha"));
        assertThrows(UnsupportedOperationException.class, () -> graph.diagnostics().add(null));
        assertThrows(UnsupportedOperationException.class, () -> graph.brokenReferences().add(null));
        assertThrows(UnsupportedOperationException.class, () -> graph.dependents().put(id("im-b"), List.of()));
        TaskGraph.TaskDiagnostics ofA = graph.diagnosticsOf(id("im-a")).orElseThrow();
        assertThrows(UnsupportedOperationException.class, () -> ofA.resolved().add(null));
        assertThrows(UnsupportedOperationException.class, () -> ofA.cycles().add(null));
        TaskGraph.TaskDiagnostics ofC = graph.diagnosticsOf(id("im-c")).orElseThrow();
        assertEquals(1, ofC.broken().size());
        assertThrows(UnsupportedOperationException.class, () -> ofC.broken().add(null));
    }
}
