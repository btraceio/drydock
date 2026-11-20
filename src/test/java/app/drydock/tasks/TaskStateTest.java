package app.drydock.tasks;

import app.drydock.domain.RepositoryId;
import app.drydock.tasks.TaskState.Actor;
import app.drydock.tasks.TaskState.Presentation;
import app.drydock.tasks.TaskState.Transition;
import app.drydock.tasks.TaskState.TaskView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T07b committed acceptance matrix for {@link TaskState}: the D6/D9-D11/D16
 * presentation and update rules. Snapshots and graphs are built through the
 * public constructors only - no files, no store, no mocking, no I/O.
 */
class TaskStateTest {

    /** A snapshot under construction: named repositories and documents. */
    private static final class Store {
        final Map<String, RepositoryId> repos = new LinkedHashMap<>();
        final List<TaskDocument> documents = new ArrayList<>();

        TaskDocument add(String repo, String taskId, TaskStatus status, DoneBy doneBy, String[] after,
                String[] sessions) {
            TaskDocument document = new TaskDocument(TaskId.of(taskId),
                    repos.computeIfAbsent(repo, any -> RepositoryId.newId()), "Task " + taskId,
                    status, doneBy, Optional.empty(), List.of(after), List.of(sessions), "", "");
            documents.add(document);
            return document;
        }

        TaskSnapshot snapshot() {
            Map<TaskId, TaskDocument> byId = new LinkedHashMap<>();
            Map<String, List<TaskDocument>> bySession = new LinkedHashMap<>();
            for (TaskDocument document : documents) {
                byId.put(document.id(), document);
                for (String session : document.sessions()) {
                    bySession.computeIfAbsent(session, any -> new ArrayList<>()).add(document);
                }
            }
            return new TaskSnapshot(documents, List.copyOf(repos.values()), byId, bySession,
                    List.of(), List.of());
        }

        /** The state of this snapshot under the given live catalog session ids. */
        TaskState state(Set<String> live) {
            TaskSnapshot snapshot = snapshot();
            Map<RepositoryId, String> slugs = new LinkedHashMap<>();
            repos.forEach((name, repository) -> slugs.put(repository, name));
            return TaskState.of(snapshot, TaskGraph.of(snapshot, slugs::get), live);
        }
    }

    private static TaskView view(TaskState state, String taskId) {
        return state.viewOf(TaskId.of(taskId)).orElseThrow();
    }

    private static Transition update(TaskState state, Actor actor, String taskId, TaskStatus requested) {
        return state.validateUpdate(TaskId.of(taskId), actor, requested);
    }

    /** Matrix 1: an agent's done maps by done_by, which defaults to human (D16). */
    @Test
    void agentDoneMapsByDoneByWithHumanDefault() {
        Store store = new Store();
        store.add("alpha", "human-validated", TaskStatus.STARTED, null, new String[0], new String[0]);
        store.add("alpha", "agent-validated", TaskStatus.STARTED, DoneBy.AGENT, new String[0], new String[0]);
        assertEquals(DoneBy.HUMAN, store.snapshot().byId().get(TaskId.of("human-validated")).doneBy());
        TaskState state = store.state(Set.of());
        Transition onHuman = update(state, Actor.AGENT, "human-validated", TaskStatus.DONE);
        assertTrue(onHuman.permitted());
        assertEquals(TaskStatus.REVIEW, onHuman.resulting());
        assertNull(onHuman.refusalReason());
        Transition onAgent = update(state, Actor.AGENT, "agent-validated", TaskStatus.DONE);
        assertTrue(onAgent.permitted());
        assertEquals(TaskStatus.DONE, onAgent.resulting());
        assertNull(onAgent.refusalReason());
        for (TaskStatus requested : new TaskStatus[] {TaskStatus.OPEN, TaskStatus.REVIEW}) {
            Transition transition = update(state, Actor.AGENT, "human-validated", requested);
            assertTrue(transition.permitted());
            assertEquals(requested, transition.resulting());
        }
    }

    /** Matrix 2: a human may set every stored status, including dropped. */
    @Test
    void humanMaySetEveryStoredStatusIncludingDropped() {
        Store store = new Store();
        store.add("alpha", "open-x", TaskStatus.OPEN, DoneBy.HUMAN, new String[0], new String[0]);
        store.add("alpha", "started-x", TaskStatus.STARTED, DoneBy.HUMAN, new String[0], new String[0]);
        TaskState state = store.state(Set.of());
        for (TaskStatus requested : TaskStatus.values()) {
            Transition transition = update(state, Actor.HUMAN, "open-x", requested);
            assertTrue(transition.permitted(), "human may request " + requested);
            assertEquals(requested, transition.resulting());
            assertNull(transition.refusalReason());
        }
        Transition dropStarted = update(state, Actor.HUMAN, "started-x", TaskStatus.DROPPED);
        assertTrue(dropStarted.permitted());
        assertEquals(TaskStatus.DROPPED, dropStarted.resulting());
    }

    /** Matrix 3: Drop bypass and unknown task are refused with exact reasons. */
    @Test
    void dropBypassAndUnknownTaskRefusedWithExactReasons() {
        Store store = new Store();
        store.add("alpha", "started-x", TaskStatus.STARTED, DoneBy.HUMAN, new String[0], new String[0]);
        TaskState state = store.state(Set.of());
        Transition agentDrop = update(state, Actor.AGENT, "started-x", TaskStatus.DROPPED);
        assertFalse(agentDrop.permitted());
        assertEquals(TaskStatus.STARTED, agentDrop.resulting());
        assertEquals("Drop is human-only: an agent may not set task 'started-x' to dropped",
                agentDrop.refusalReason());
        Transition unknown = update(state, Actor.HUMAN, "no-such-task", TaskStatus.DONE);
        assertFalse(unknown.permitted());
        assertNull(unknown.resulting());
        assertEquals("no task with id 'no-such-task' in the snapshot (never stored, or excluded after"
                + " an id collision)", unknown.refusalReason());
        for (Actor actor : Actor.values()) {
            for (String taskId : new String[] {"started-x", "no-such-task"}) {
                for (TaskStatus requested : TaskStatus.values()) {
                    Transition transition = update(state, actor, taskId, requested);
                    assertTrue(transition.permitted() == (transition.refusalReason() == null),
                            actor + " " + taskId + " " + requested + " must not be a silent no-op");
                }
            }
        }
        TaskId started = TaskId.of("started-x");
        assertThrows(IllegalArgumentException.class, () -> new Transition(started, Actor.AGENT,
                TaskStatus.DROPPED, false, TaskStatus.STARTED, null));
        assertThrows(IllegalArgumentException.class, () -> new Transition(started, Actor.AGENT,
                TaskStatus.DROPPED, true, TaskStatus.DROPPED, "reason"));
    }

    /** Matrix 4: Confirm stores done and Reopen stores open (D16 naming). */
    @Test
    void confirmAndReopenMapThroughValidateUpdate() {
        Store store = new Store();
        store.add("alpha", "review-x", TaskStatus.REVIEW, DoneBy.HUMAN, new String[0], new String[0]);
        TaskState state = store.state(Set.of());
        assertEquals(Presentation.REVIEW, view(state, "review-x").presentation());
        Transition confirm = update(state, Actor.HUMAN, "review-x", TaskStatus.DONE);
        assertTrue(confirm.permitted());
        assertEquals(TaskStatus.DONE, confirm.resulting());
        Transition reopen = update(state, Actor.HUMAN, "review-x", TaskStatus.OPEN);
        assertTrue(reopen.permitted());
        assertEquals(TaskStatus.OPEN, reopen.resulting());
    }

    /** Matrix 5: a request for the status already stored is a no-op success. */
    @Test
    void sameStatusRequestIsNoOpSuccessForBothActors() {
        Store store = new Store();
        store.add("alpha", "open-x", TaskStatus.OPEN, DoneBy.HUMAN, new String[0], new String[0]);
        store.add("alpha", "started-x", TaskStatus.STARTED, DoneBy.HUMAN, new String[0], new String[0]);
        store.add("alpha", "review-x", TaskStatus.REVIEW, DoneBy.HUMAN, new String[0], new String[0]);
        store.add("alpha", "done-x", TaskStatus.DONE, DoneBy.HUMAN, new String[0], new String[0]);
        TaskState state = store.state(Set.of());
        for (Actor actor : Actor.values()) {
            for (String taskId : new String[] {"open-x", "started-x", "review-x"}) {
                TaskStatus current = store.snapshot().byId().get(TaskId.of(taskId)).status();
                Transition transition = update(state, actor, taskId, current);
                assertTrue(transition.permitted(), actor + " " + taskId + " same-status no-op");
                assertEquals(current, transition.resulting());
                assertNull(transition.refusalReason());
            }
        }
        Transition agentDoneOnDone = update(state, Actor.AGENT, "done-x", TaskStatus.DONE);
        assertTrue(agentDoneOnDone.permitted());
        assertEquals(TaskStatus.DONE, agentDoneOnDone.resulting());
        assertNull(agentDoneOnDone.refusalReason());
    }

    /** Matrix 6: only the caller's live set decides whether started is live. */
    @Test
    void staleSessionIdsDeriveInterruptedOnlyFromTheLiveSet() {
        Store store = new Store();
        store.add("alpha", "stale-x", TaskStatus.STARTED, DoneBy.HUMAN, new String[0], new String[] {"sess-a"});
        TaskState live = store.state(Set.of("sess-a"));
        TaskState dead = store.state(Set.of());
        TaskView liveView = view(live, "stale-x");
        assertEquals(Presentation.STARTED, liveView.presentation());
        assertTrue(liveView.inSession());
        assertTrue(live.inSession(TaskId.of("stale-x")));
        assertEquals(List.of("sess-a"), liveView.liveSessions());
        TaskView deadView = view(dead, "stale-x");
        assertEquals(Presentation.INTERRUPTED, deadView.presentation());
        assertFalse(deadView.inSession());
        assertFalse(dead.inSession(TaskId.of("stale-x")));
    }

    /** Matrix 7: membership is row existence (idle or stopped alike); done stays done. */
    @Test
    void membershipIsRowExistenceAndTerminalStaysTerminal() {
        Store store = new Store();
        store.add("alpha", "idle-row", TaskStatus.STARTED, DoneBy.HUMAN, new String[0],
                new String[] {"sess-idle"});
        store.add("alpha", "stopped-row", TaskStatus.STARTED, DoneBy.HUMAN, new String[0],
                new String[] {"sess-stopped"});
        store.add("alpha", "removed-row", TaskStatus.STARTED, DoneBy.HUMAN, new String[0],
                new String[] {"sess-removed"});
        store.add("alpha", "done-x", TaskStatus.DONE, DoneBy.HUMAN, new String[0],
                new String[] {"sess-removed"});
        TaskState rowsPresent = store.state(Set.of("sess-idle", "sess-stopped"));
        assertEquals(Presentation.STARTED, view(rowsPresent, "idle-row").presentation());
        assertEquals(Presentation.STARTED, view(rowsPresent, "stopped-row").presentation());
        assertTrue(view(rowsPresent, "stopped-row").inSession());
        TaskState rowsGone = store.state(Set.of());
        assertEquals(Presentation.INTERRUPTED, view(rowsGone, "removed-row").presentation());
        assertEquals(Presentation.TERMINAL, view(rowsGone, "done-x").presentation());
        assertFalse(view(rowsGone, "done-x").inSession());
    }

    /** Matrix 8: a removed row changes only the presentation, never stored history. */
    @Test
    void removedRowKeepsStoredSessionsAndChangesOnlyPresentation() {
        Store store = new Store();
        store.add("alpha", "hist-x", TaskStatus.STARTED, DoneBy.HUMAN, new String[0], new String[] {"sess-a"});
        TaskSnapshot snapshot = store.snapshot();
        TaskGraph graph = TaskGraph.of(snapshot, repository -> "alpha");
        TaskState live = TaskState.of(snapshot, graph, Set.of("sess-a"));
        TaskState dead = TaskState.of(snapshot, graph, Set.of());
        assertEquals(Presentation.STARTED, view(live, "hist-x").presentation());
        assertEquals(Presentation.INTERRUPTED, view(dead, "hist-x").presentation());
        assertEquals(List.of("sess-a"), snapshot.byId().get(TaskId.of("hist-x")).sessions());
        assertEquals(List.of("sess-a"), snapshot.documents().get(0).sessions());
        assertEquals(1, snapshot.bySessionReference().get("sess-a").size());
    }

    /** Matrix 9 (D11): a running prerequisite's blocker names its live session. */
    @Test
    void runningBlockerLinkNamesTheLiveSession() {
        Store store = new Store();
        store.add("alpha", "prereq-live", TaskStatus.STARTED, DoneBy.HUMAN, new String[0],
                new String[] {"sess-b"});
        store.add("alpha", "prereq-gone", TaskStatus.STARTED, DoneBy.HUMAN, new String[0],
                new String[] {"sess-c"});
        store.add("alpha", "blocked-by-live", TaskStatus.OPEN, DoneBy.HUMAN,
                new String[] {"prereq-live"}, new String[0]);
        store.add("alpha", "blocked-by-gone", TaskStatus.OPEN, DoneBy.HUMAN,
                new String[] {"prereq-gone"}, new String[0]);
        TaskState state = store.state(Set.of("sess-b"));
        TaskView blocked = view(state, "blocked-by-live");
        assertEquals(Presentation.BLOCKED, blocked.presentation());
        assertEquals(List.of("blocked by 'Task prereq-live' (in session 'sess-b')"), blocked.blockers());
        TaskView blockedGone = view(state, "blocked-by-gone");
        assertEquals(Presentation.BLOCKED, blockedGone.presentation());
        assertEquals(List.of("blocked by 'Task prereq-gone' (started)"), blockedGone.blockers());
    }

    /** Matrix 10: ready needs finished prerequisites and no broken or cyclic reference. */
    @Test
    void readyRequiresFinishedPrereqsAndNoBrokenOrCyclicReferences() {
        Store store = new Store();
        store.add("alpha", "done-y", TaskStatus.DONE, DoneBy.HUMAN, new String[0], new String[0]);
        store.add("alpha", "dropped-y", TaskStatus.DROPPED, DoneBy.HUMAN, new String[0], new String[0]);
        store.add("alpha", "ready-x", TaskStatus.OPEN, DoneBy.HUMAN,
                new String[] {"done-y", "dropped-y"}, new String[0]);
        store.add("alpha", "open-y", TaskStatus.OPEN, DoneBy.HUMAN, new String[0], new String[0]);
        store.add("alpha", "blocked-x", TaskStatus.OPEN, DoneBy.HUMAN,
                new String[] {"open-y"}, new String[0]);
        store.add("alpha", "broken-x", TaskStatus.OPEN, DoneBy.HUMAN,
                new String[] {"alpha:missing-id"}, new String[0]);
        store.add("alpha", "cyc-y", TaskStatus.DONE, DoneBy.HUMAN, new String[] {"cyc-x"}, new String[0]);
        store.add("alpha", "cyc-x", TaskStatus.OPEN, DoneBy.HUMAN, new String[] {"cyc-y"}, new String[0]);
        store.add("alpha", "self-cyc", TaskStatus.OPEN, DoneBy.HUMAN, new String[] {"self-cyc"}, new String[0]);
        TaskState state = store.state(Set.of());
        TaskView ready = view(state, "ready-x");
        assertEquals(Presentation.READY, ready.presentation());
        assertEquals(List.of(), ready.blockers());
        TaskView blocked = view(state, "blocked-x");
        assertEquals(Presentation.BLOCKED, blocked.presentation());
        assertEquals(List.of("blocked by 'Task open-y' (open)"), blocked.blockers());
        TaskView broken = view(state, "broken-x");
        assertEquals(Presentation.BLOCKED, broken.presentation());
        assertEquals(List.of("broken prerequisite reference 'alpha:missing-id': no task with id"
                + " 'missing-id' in project 'alpha'"), broken.blockers());
        TaskView cycle = view(state, "cyc-x");
        assertEquals(Presentation.BLOCKED, cycle.presentation());
        assertEquals(List.of("in a dependency cycle: 'Task cyc-x' -> 'Task cyc-y' -> 'Task cyc-x'"), cycle.blockers());
        TaskView selfCycle = view(state, "self-cyc");
        assertEquals(Presentation.BLOCKED, selfCycle.presentation());
        assertEquals(List.of("in a dependency cycle: 'Task self-cyc' -> 'Task self-cyc'",
                "blocked by 'Task self-cyc' (open)"), selfCycle.blockers());
        assertEquals(Presentation.TERMINAL, view(state, "cyc-y").presentation());
    }
}
