package app.drydock.tasks;

import app.drydock.domain.RepositoryId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static app.drydock.tasks.StartPlan.Kind.BROKEN_REFERENCE;
import static app.drydock.tasks.StartPlan.Kind.OWNED_TASK;
import static app.drydock.tasks.StartPlan.Kind.SEGMENT_CYCLE;
import static app.drydock.tasks.StartPlan.Kind.TARGET_FINISHED;
import static app.drydock.tasks.StartPlan.Kind.TASK_CYCLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T08b acceptance matrix for {@link StartPlanner}: the reachable unfinished
 * prerequisite closure grouped into per-repository segments in dependency
 * order (D7/D8), the segment DAG as stages with the target's segment last
 * (P4), done and dropped prerequisites satisfied and collected as context
 * with the direct follow-ups (D7/D13/D3), and every refusal carrying its
 * concrete text (P3/D10/D12). Snapshots and graphs come from the public
 * constructors: no files, no store, no mocking.
 */
class StartPlannerTest {

    /** A snapshot under construction: repositories, documents, the id index. */
    private static final class Store {
        final Map<String, RepositoryId> repos = new LinkedHashMap<>();
        final List<TaskDocument> documents = new ArrayList<>();
        final Map<TaskId, TaskDocument> byId = new LinkedHashMap<>();

        RepositoryId repo(String name) {
            return repos.computeIfAbsent(name, any -> RepositoryId.newId());
        }

        void add(String repository, String id, TaskStatus status, List<String> sessions, String... after) {
            TaskDocument document = new TaskDocument(TaskId.of(id), repo(repository), "Task " + id, status,
                    null, Optional.empty(), List.of(after), sessions, "", "");
            documents.add(document);
            byId.put(document.id(), document);
        }

        void open(String repository, String id, String... after) {
            add(repository, id, null, List.of(), after);
        }

        TaskSnapshot snapshot() {
            return new TaskSnapshot(documents, new ArrayList<>(repos.values()), byId, Map.of(), List.of(), List.of());
        }

        Function<RepositoryId, String> slugs(Object... nameSlugPairs) {
            Map<RepositoryId, String> mapping = new LinkedHashMap<>();
            for (int i = 0; i < nameSlugPairs.length; i += 2) {
                mapping.put(repo((String) nameSlugPairs[i]), (String) nameSlugPairs[i + 1]);
            }
            return mapping::get;
        }
    }

    private static StartPlan plan(Store store, Function<RepositoryId, String> slugs, String target,
            String... liveSessionIds) {
        TaskSnapshot snapshot = store.snapshot();
        return StartPlanner.plan(TaskId.of(target), snapshot, TaskGraph.of(snapshot, slugs), slugs,
                Set.of(liveSessionIds));
    }

    private static List<TaskId> ids(StartPlan.Segment segment) {
        return segment.tasks().stream().map(TaskDocument::id).toList();
    }

    private static StartPlan.Segment segment(StartPlan plan, RepositoryId repository) {
        return plan.segments().stream().filter(s -> s.repository().equals(repository)).findFirst().orElseThrow();
    }

    // Matrix 1 (same repository) and 6: one segment holds the whole diamond in
    // dependency order; unrelated tasks never leak into it.

    @Test
    void diamondInOneRepositoryIsOneDependencyOrderedSegment() {
        Store store = new Store();
        store.open("alpha", "dia-a");
        store.open("alpha", "dia-b", "dia-a");
        store.open("alpha", "dia-c", "dia-a");
        store.open("alpha", "dia-d", "dia-b", "dia-c");
        store.open("alpha", "unl-a");
        store.open("beta", "unl-b");
        StartPlan plan = plan(store, store.slugs("alpha", "alpha", "beta", "beta"), "dia-d");
        assertTrue(plan.startable(), "no refusal");
        assertEquals(1, plan.segments().size(), "one repository, one segment");
        StartPlan.Segment segment = plan.segments().get(0);
        assertEquals(store.repo("alpha"), segment.repository());
        assertEquals("alpha", segment.projectName());
        assertEquals(List.of(TaskId.of("dia-a"), TaskId.of("dia-c"), TaskId.of("dia-b"), TaskId.of("dia-d")),
                ids(segment), "prerequisites first, the target last");
        assertEquals(1, plan.stages().size(), "no cross-repository wait: a single stage");
        assertEquals(1, plan.stages().get(0).number());
        assertEquals(List.of(segment), plan.stages().get(0).segments());
        assertFalse(ids(segment).contains(TaskId.of("unl-a")), "unrelated same-repository task excluded");
        assertFalse(plan.segments().stream().map(StartPlannerTest::ids).anyMatch(t -> t.contains(TaskId.of("unl-b"))),
                "unrelated cross-repository task excluded");
    }

    // Matrix 1 (cross repository) and 7: parallel segments staged by the wait
    // graph, the target's own segment the final stage.

    @Test
    void crossRepositoryDiamondStagesParallelSegmentsTargetLast() {
        Store store = new Store();
        store.open("delta", "xdi-a");
        store.open("beta", "xdi-b", "xdi-a");
        store.open("gamma", "xdi-c", "xdi-a");
        store.open("alpha", "xdi-d", "xdi-b", "xdi-c");
        StartPlan plan = plan(store, store.slugs("alpha", "alpha", "beta", "beta", "gamma", "gamma",
                "delta", "delta"), "xdi-d");
        assertTrue(plan.startable());
        assertEquals(4, plan.segments().size(), "one segment per repository");
        assertEquals(List.of(TaskId.of("xdi-d")), ids(segment(plan, store.repo("alpha"))));
        assertEquals(List.of(TaskId.of("xdi-b")), ids(segment(plan, store.repo("beta"))));
        assertEquals(List.of(TaskId.of("xdi-c")), ids(segment(plan, store.repo("gamma"))));
        assertEquals(List.of(TaskId.of("xdi-a")), ids(segment(plan, store.repo("delta"))));
        assertEquals("delta", segment(plan, store.repo("delta")).projectName());
        assertEquals(3, plan.stages().size());
        assertEquals(1, plan.stages().get(0).number());
        assertEquals(List.of(store.repo("delta")),
                plan.stages().get(0).segments().stream().map(StartPlan.Segment::repository).toList());
        List<RepositoryId> parallel = plan.stages().get(1).segments().stream()
                .map(StartPlan.Segment::repository).toList();
        assertEquals(2, parallel.size(), "b and c run in parallel");
        assertTrue(parallel.containsAll(List.of(store.repo("beta"), store.repo("gamma"))));
        assertEquals(3, plan.stages().get(2).number());
        assertEquals(List.of(store.repo("alpha")),
                plan.stages().get(2).segments().stream().map(StartPlan.Segment::repository).toList(),
                "the target's own segment is the last stage");
    }

    // Matrix 2: a same-project chain is one segment in dependency order.

    @Test
    void sameProjectChainIsOneSegmentInDependencyOrder() {
        Store store = new Store();
        store.open("alpha", "chn-1");
        store.open("alpha", "chn-2", "chn-1");
        store.open("alpha", "chn-3", "chn-2");
        StartPlan plan = plan(store, store.slugs("alpha", "alpha"), "chn-3");
        assertTrue(plan.startable());
        assertEquals(1, plan.segments().size());
        assertEquals(List.of(TaskId.of("chn-1"), TaskId.of("chn-2"), TaskId.of("chn-3")),
                ids(plan.segments().get(0)), "the target closes the segment");
        assertEquals(1, plan.stages().size());
    }

    // Matrix 3: segments waiting on each other although the task graph is
    // acyclic (the project collapse, P4.3): the refusal names both segments
    // and the closing task edges.

    @Test
    void projectCollapseCycleNamesSegmentsAndClosingEdges() {
        Store store = new Store();
        store.open("alpha", "col-t", "col-a1", "col-b1");
        store.open("alpha", "col-a1", "col-b2");
        store.open("alpha", "col-c1");
        store.open("beta", "col-b1", "col-c1");
        store.open("beta", "col-b2");
        StartPlan plan = plan(store, store.slugs("alpha", "alpha", "beta", "beta"), "col-t");
        assertFalse(plan.startable());
        assertEquals(List.of(new StartPlan.Refusal(SEGMENT_CYCLE, "project segments form a cycle although"
                + " the task graph is acyclic: 'alpha' -> 'beta' -> 'alpha' (the closing edges: task 'col-t'"
                + " in 'alpha' waits for 'col-b1' in 'beta'; task 'col-b1' in 'beta' waits for 'col-c1'"
                + " in 'alpha')")), plan.refusals());
        assertTrue(plan.segments().isEmpty() && plan.stages().isEmpty() && plan.context().isEmpty()
                && plan.followUps().isEmpty(), "a refused plan starts nothing");
    }

    // Matrix 4: review and started prerequisites are WORK - they stay in the
    // segment and their waits are not satisfied.

    @Test
    void reviewAndStartedPrerequisitesStayAsWork() {
        Store store = new Store();
        store.add("alpha", "rev-x", TaskStatus.REVIEW, List.of());
        store.add("alpha", "star-x", TaskStatus.STARTED, List.of());
        store.open("alpha", "wrk-t", "rev-x", "star-x");
        StartPlan plan = plan(store, store.slugs("alpha", "alpha"), "wrk-t");
        assertTrue(plan.startable(), "unfinished work is work, not a refusal");
        assertEquals(1, plan.segments().size());
        assertEquals(List.of(TaskId.of("star-x"), TaskId.of("rev-x"), TaskId.of("wrk-t")),
                ids(plan.segments().get(0)), "review and started stay in the segment");
        assertTrue(plan.context().isEmpty(), "their waits are not satisfied by context");
    }

    // Matrix 5: a dropped prerequisite satisfies the wait, is not work, and is
    // collected as context.

    @Test
    void droppedPrerequisiteSatisfiesAndBecomesContext() {
        Store store = new Store();
        store.add("alpha", "drp-x", TaskStatus.DROPPED, List.of());
        store.open("alpha", "sat-t", "drp-x");
        StartPlan plan = plan(store, store.slugs("alpha", "alpha"), "sat-t");
        assertTrue(plan.startable(), "the dropped wait is satisfied");
        assertEquals(1, plan.segments().size());
        assertEquals(List.of(TaskId.of("sat-t")), ids(plan.segments().get(0)), "dropped is not work");
        assertEquals(List.of(new StartPlan.Context(TaskId.of("drp-x"), store.repo("alpha"), "Task drp-x")),
                plan.context(), "dropped is collected as context");
        assertTrue(plan.followUps().isEmpty());
    }

    // Matrix 7: a done or dropped target starts nothing.

    @Test
    void doneAndDroppedTargetsStartNothing() {
        Store store = new Store();
        store.add("alpha", "fin-x", TaskStatus.DONE, List.of());
        store.add("alpha", "gone-x", TaskStatus.DROPPED, List.of());
        StartPlan done = plan(store, store.slugs("alpha", "alpha"), "fin-x");
        assertFalse(done.startable());
        assertEquals(List.of(new StartPlan.Refusal(TARGET_FINISHED,
                "target task 'fin-x' is already done: it starts nothing")), done.refusals());
        StartPlan dropped = plan(store, store.slugs("alpha", "alpha"), "gone-x");
        assertFalse(dropped.startable());
        assertEquals(List.of(new StartPlan.Refusal(TARGET_FINISHED,
                "target task 'gone-x' is already dropped: it starts nothing")), dropped.refusals());
        assertTrue(dropped.segments().isEmpty() && dropped.stages().isEmpty()
                && dropped.context().isEmpty() && dropped.followUps().isEmpty());
    }

    // Matrix 8: a closure member owned by a live session refuses the start,
    // naming the task and the session (D10/D12).

    @Test
    void ownedTaskRefusalNamesTaskAndSession() {
        Store store = new Store();
        store.add("alpha", "own-x", TaskStatus.OPEN, List.of("sess-live-1"));
        store.open("alpha", "own-t", "own-x");
        StartPlan plan = plan(store, store.slugs("alpha", "alpha"), "own-t", "sess-live-1");
        assertFalse(plan.startable());
        assertEquals(List.of(new StartPlan.Refusal(OWNED_TASK, "task 'own-x' (Task own-x) is already owned"
                + " by live session 'sess-live-1'")), plan.refusals());
        assertTrue(plan.segments().isEmpty() && plan.stages().isEmpty());
    }

    // Matrix 9: done context and direct follow-ups with exact contents.

    @Test
    void doneContextAndDirectFollowUpsCarryExactContents() {
        Store store = new Store();
        store.add("alpha", "done-x", TaskStatus.DONE, List.of());
        store.add("gamma", "done-g", TaskStatus.DONE, List.of());
        store.open("alpha", "ctx-t", "done-x", "done-g");
        store.open("alpha", "fu-a", "ctx-t");
        store.open("beta", "fu-b", "ctx-t");
        StartPlan plan = plan(store, store.slugs("alpha", "alpha", "beta", "beta", "gamma", "gamma"), "ctx-t");
        assertTrue(plan.startable());
        assertEquals(1, plan.segments().size());
        assertEquals(List.of(TaskId.of("ctx-t")), ids(plan.segments().get(0)), "done is not work");
        assertEquals(List.of(new StartPlan.Context(TaskId.of("done-x"), store.repo("alpha"), "Task done-x"),
                new StartPlan.Context(TaskId.of("done-g"), store.repo("gamma"), "Task done-g")),
                plan.context(), "context: id, repository, title");
        assertEquals(List.of(new StartPlan.FollowUp(TaskId.of("fu-a"), store.repo("alpha"), "Task fu-a"),
                new StartPlan.FollowUp(TaskId.of("fu-b"), store.repo("beta"), "Task fu-b")),
                plan.followUps(), "direct dependents: id, repository, title");
    }

    // Matrix 10: broken references and task cycles refuse with the graph's own
    // concrete texts.

    @Test
    void brokenReferenceAndTaskCycleRefusalsCarryGraphTexts() {
        Store broken = new Store();
        broken.open("alpha", "brk-t", "no-such-id");
        StartPlan refused = plan(broken, broken.slugs("alpha", "alpha"), "brk-t");
        assertFalse(refused.startable());
        assertEquals(List.of(new StartPlan.Refusal(BROKEN_REFERENCE, "task 'brk-t': broken prerequisite"
                + " reference 'no-such-id': no task with id 'no-such-id'")), refused.refusals());
        Store cycled = new Store();
        cycled.open("alpha", "cyc-a", "cyc-b");
        cycled.open("alpha", "cyc-b", "cyc-a");
        cycled.open("alpha", "cyc-t", "cyc-a");
        StartPlan cycle = plan(cycled, cycled.slugs("alpha", "alpha"), "cyc-t");
        assertFalse(cycle.startable());
        assertEquals(List.of(
                new StartPlan.Refusal(TASK_CYCLE, "task 'cyc-a' is in a dependency cycle: cyc-a -> cyc-b -> cyc-a"),
                new StartPlan.Refusal(TASK_CYCLE, "task 'cyc-b' is in a dependency cycle: cyc-b -> cyc-a -> cyc-b")),
                cycle.refusals());
        assertTrue(cycle.segments().isEmpty() && cycle.stages().isEmpty());
    }
}
