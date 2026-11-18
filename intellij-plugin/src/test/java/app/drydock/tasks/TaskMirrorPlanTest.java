package app.drydock.tasks;

import app.drydock.domain.RepositoryId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskMirrorPlanTest {

    private final RepositoryId repo = RepositoryId.newId();
    private final RepositoryId other = RepositoryId.newId();
    private final List<TaskDocument> docs = new ArrayList<>();
    private final Map<TaskId, TaskDocument> byId = new LinkedHashMap<>();
    private final List<TaskSnapshot.IdCollision> collisions = new ArrayList<>();

    private void add(String id, String... after) { addIn(repo, id, TaskStatus.OPEN, after); }

    private void addIn(RepositoryId r, String id, TaskStatus status, String... after) {
        TaskDocument d = new TaskDocument(TaskId.of(id), r, "T " + id, status, null, Optional.empty(),
                List.of(after), List.of(), "", "");
        docs.add(d);
        byId.put(d.id(), d);
    }

    private TaskMirrorPlan plan(String root) {
        TaskSnapshot s = new TaskSnapshot(docs, List.of(repo, other), byId, Map.of(), List.of(), collisions);
        TaskGraph g = TaskGraph.of(s, r -> r.equals(repo) ? "main" : r.equals(other) ? "side" : null);
        return TaskMirrorPlan.of(s, g, TaskId.of(root));
    }

    private static List<String> ids(List<TaskDocument> l) { return l.stream().map(d -> d.id().text()).toList(); }

    @Test
    void chain() {
        add("root", "middle");
        add("middle", "base");
        add("base");
        add("unrelated");
        TaskMirrorPlan p = plan("root");
        assertEquals(List.of("base", "middle", "root"), ids(p.order()));
        assertEquals(2, p.edges().size());
        assertEquals(List.of("middle"), ids(p.prerequisitesOf(TaskId.of("root"))));
        assertFalse(ids(p.order()).contains("unrelated"));
    }

    @Test
    void diamondOrderedByOrdinal() {
        add("root", "right", "left");
        add("left", "base");
        add("right", "base");
        add("base");
        TaskMirrorPlan p = plan("root");
        assertEquals(List.of("base", "left", "right", "root"), ids(p.order()));
        assertEquals(4, p.edges().size());
        assertEquals(List.of("left", "right"), ids(p.prerequisitesOf(TaskId.of("root"))));
    }

    @Test
    void bareAndQualifiedDuplicatesCollapse() {
        add("root", "base", "main:base", "base");
        add("base");
        TaskMirrorPlan p = plan("root");
        assertEquals(1, p.edges().size());
        assertEquals(2, p.order().size());
    }

    @Test
    void longChainNoStackOverflow() {
        int n = 5000;
        add("n0");
        IntStream.range(1, n).forEach(i -> add("n" + i, "n" + (i - 1)));
        TaskMirrorPlan p = plan("n" + (n - 1));
        assertEquals(n, p.order().size());
        assertEquals("n0", p.order().get(0).id().text());
    }

    @Test
    void allStatusesParticipate() {
        addIn(repo, "root", TaskStatus.OPEN, "a", "b", "c", "d");
        int i = 0;
        for (TaskStatus s : TaskStatus.values()) {
            addIn(repo, "s" + (i++), s);
        }
        docs.set(0, new TaskDocument(TaskId.of("root"), repo, "T root", TaskStatus.OPEN, null, Optional.empty(),
                IntStream.range(0, i).mapToObj(k -> "s" + k).toList(), List.of(), "", ""));
        byId.put(TaskId.of("root"), docs.get(0));
        assertEquals(5, TaskStatus.values().length);
        assertEquals(i + 1, plan("root").order().size());
    }

    @Test
    void reusedPrerequisiteStillTraversed() {
        add("root", "mid");
        add("mid", "deep");
        add("deep");
        TaskMirrorPlan p = plan("root").withDispositions(Map.of(
                TaskId.of("mid"), new TaskMirrorPlan.Disposition.Reuse("https://github.com/o/r/issues/1"),
                TaskId.of("deep"), new TaskMirrorPlan.Disposition.Skip("no checkout")));
        assertEquals(List.of("deep", "mid", "root"), ids(p.order()));
        assertTrue(p.dispositionOf(TaskId.of("mid")).get() instanceof TaskMirrorPlan.Disposition.Reuse);
    }

    @Test
    void cycleAndBrokenMix() {
        add("root", "dep", "ghost");
        add("dep", "c1", "main:missing");
        add("c1", "c2");
        add("c2", "c1");
        add("lonely", "ghost2");
        TaskMirrorPlan p = plan("root");
        assertTrue(p.refusal().isEmpty());
        assertEquals(List.of("dep", "root"), ids(p.order()));
        assertEquals(List.of("c1", "c2"), p.excluded().stream().map(e -> e.task().id().text()).sorted().toList());
        assertEquals(2, p.brokenReferences().size());
        assertTrue(p.brokenReferences().stream().noneMatch(b -> b.source().id().text().equals("lonely")));
        assertEquals(1, p.edges().size());
        assertEquals(5, p.droppedEdges().size()); // 2 broken + dep->c1 + c1->c2 + c2->c1
        assertEquals(2, p.cycles().size());
    }

    @Test
    void rootSelfCycleRefuses() {
        add("root", "root");
        TaskMirrorPlan p = plan("root");
        TaskMirrorPlan.Refusal r = p.refusal().orElseThrow();
        assertEquals(List.of(TaskId.of("root"), TaskId.of("root")), r.path());
        assertEquals(List.of("T root", "T root"), r.titles());
        assertTrue(p.order().isEmpty());
    }

    @Test
    void rootDownstreamOfCycleDoesNotRefuse() {
        add("root", "c1");
        add("c1", "c2");
        add("c2", "c1");
        TaskMirrorPlan p = plan("root");
        assertTrue(p.refusal().isEmpty());
        assertEquals(List.of("root"), ids(p.order()));
    }

    @Test
    void cyclicRootInLongerCycleRefuses() {
        add("root", "b");
        add("b", "root");
        assertTrue(plan("root").refusal().orElseThrow().message().contains("T root -> T b -> T root"));
    }

    @Test
    void missingAndCollidedRoot() {
        add("a");
        assertTrue(plan("nope").refusal().isPresent());
        collisions.add(new TaskSnapshot.IdCollision(TaskId.of("dup"), List.of(java.nio.file.Path.of("x"),
                java.nio.file.Path.of("y"))));
        assertTrue(plan("dup").refusal().orElseThrow().message().contains("more than one"));
    }

    @Test
    void wrongProjectQualifiedRefIsBrokenNotFallback() {
        addIn(repo, "root", TaskStatus.OPEN, "side:base");
        addIn(repo, "base", TaskStatus.OPEN);
        TaskMirrorPlan p = plan("root");
        assertEquals(List.of("root"), ids(p.order()));
        assertEquals(1, p.brokenReferences().size());
    }

    @Test
    void dispositionReusesFirstRecognizedOfKind() {
        List<String> mirrors = List.of("https://example.com/x", "https://github.com/o/r/issues/7",
                "https://acme.atlassian.net/browse/AB-1", "https://github.com/o/r/issues/8");
        assertEquals(new TaskMirrorPlan.Disposition.Reuse("https://github.com/o/r/issues/7"),
                TaskMirrorPlan.Disposition.forMirrors(mirrors, TaskMirror.Kind.GITHUB));
        assertEquals(new TaskMirrorPlan.Disposition.Reuse("https://acme.atlassian.net/browse/AB-1"),
                TaskMirrorPlan.Disposition.forMirrors(mirrors, TaskMirror.Kind.JIRA));
        assertEquals(new TaskMirrorPlan.Disposition.Create(),
                TaskMirrorPlan.Disposition.forMirrors(List.of("https://acme.atlassian.net/browse/AB-1"),
                        TaskMirror.Kind.GITHUB));
    }
}
