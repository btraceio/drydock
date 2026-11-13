package app.drydock.delayedtasks;

import app.drydock.domain.RepositoryId;
import app.drydock.tasks.DoneBy;
import app.drydock.tasks.TaskDocument;
import app.drydock.tasks.TaskGraph;
import app.drydock.tasks.TaskId;
import app.drydock.tasks.TaskSnapshot;
import app.drydock.tasks.TaskStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The claim rules of {@link ClaimLaunch} (S4 of docs/StackedPrExecution.md),
 * pinned pure: follow-ups only (an in-repository, non-dropped prerequisite
 * -- DONE included, a valid stack base), the first prerequisite as the
 * stack base, the live-ownership and in-flight refusals, the P3 graph
 * refusals, the S5 cross-repository refusal, and the auto-start candidate
 * selection (descendants of a running plan's tree, open, unowned).
 * Snapshots and graphs come from the public constructors: no files, no
 * store, no git, no IDE.
 */
class ClaimLaunchTest {

    /** A snapshot under construction, across any number of repositories. */
    private static final class Store {
        final List<TaskDocument> documents = new ArrayList<>();

        void open(RepositoryId repository, String id, TaskStatus status, List<String> sessions,
                String... after) {
            documents.add(new TaskDocument(TaskId.of(id), repository, "Task " + id, status,
                    DoneBy.HUMAN, Optional.of(Instant.parse("2026-11-11T00:00:00Z")),
                    List.of(after), sessions, List.of(), "", ""));
        }

        TaskSnapshot snapshot() {
            Map<TaskId, TaskDocument> byId = new LinkedHashMap<>();
            documents.forEach(document -> byId.put(document.id(), document));
            List<RepositoryId> repositories = documents.stream().map(TaskDocument::repository)
                    .distinct().toList();
            return new TaskSnapshot(documents, repositories, byId, Map.of(), List.of(), List.of());
        }

        TaskGraph graph() {
            return TaskGraph.of(snapshot(), repository -> "repo");
        }
    }

    private static ClaimLaunch.Attempt attempt(Store store, String id, Set<String> live,
            Set<TaskId> inFlight) {
        TaskDocument document = store.snapshot().byId().get(TaskId.of(id));
        return ClaimLaunch.validate(document, store.graph(), live, inFlight);
    }

    @Test
    void aFollowUpStacksOnItsFirstInRepoPrerequisiteDoneIncluded() {
        RepositoryId repository = RepositoryId.newId();
        Store store = new Store();
        store.open(repository, "a", TaskStatus.DONE, List.of());
        store.open(repository, "b", TaskStatus.DONE, List.of(), "a");
        store.open(repository, "c", TaskStatus.OPEN, List.of(), "b");
        ClaimLaunch.Attempt attempt = attempt(store, "c", Set.of(), Set.of());
        assertTrue(!attempt.refused(), attempt.refusal());
        assertEquals(TaskId.of("c"), attempt.shape().task());
        assertEquals(TaskId.of("b"), attempt.shape().base(), "the FIRST prerequisite is the base");
        assertEquals(List.of(TaskId.of("b")), attempt.shape().waits());
        assertTrue(attempt.shape().waits().contains(attempt.shape().base()),
                "the base is always one of the waits");
    }

    @Test
    void aShapeWhoseWaitsOmitTheBaseIsRefused() {
        TaskId base = TaskId.of("a");
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new ClaimLaunch.Shape(TaskId.of("b"), base, List.of(TaskId.of("c"))));
        assertEquals("the stack base 'a' is always one of the waits", thrown.getMessage());
    }

    @Test
    void anUnfinishedInRepoPrerequisiteIsAWaitAndTheBranchProbeRefuses() {
        RepositoryId repository = RepositoryId.newId();
        Store store = new Store();
        store.open(repository, "a", TaskStatus.REVIEW, List.of("session-1"));
        store.open(repository, "b", TaskStatus.OPEN, List.of(), "a");
        ClaimLaunch.Attempt attempt = attempt(store, "b", Set.of(), Set.of());
        // S2: the review gate holds statuses, not stacking -- a claim on b
        // validates; only the branch probe (the caller's) can refuse it.
        assertTrue(!attempt.refused(), attempt.refusal());
        assertEquals(TaskId.of("a"), attempt.shape().base());
    }

    @Test
    void aTaskWithNoInRepoPrerequisiteIsNotAFollowUp() {
        RepositoryId repository = RepositoryId.newId();
        Store store = new Store();
        store.open(repository, "solo", TaskStatus.OPEN, List.of());
        ClaimLaunch.Attempt attempt = attempt(store, "solo", Set.of(), Set.of());
        assertTrue(attempt.refused());
        assertTrue(attempt.refusal().contains("not a claimable follow-up"),
                attempt.refusal());
    }

    @Test
    void onlyDroppedPrerequisitesLeftIsNotAFollowUp() {
        RepositoryId repository = RepositoryId.newId();
        Store store = new Store();
        store.open(repository, "a", TaskStatus.DROPPED, List.of());
        store.open(repository, "b", TaskStatus.OPEN, List.of(), "a");
        ClaimLaunch.Attempt attempt = attempt(store, "b", Set.of(), Set.of());
        assertTrue(attempt.refused());
        assertTrue(attempt.refusal().contains("not a claimable follow-up"), attempt.refusal());
    }

    @Test
    void anUnfinishedCrossRepoPrerequisiteKeepsTheStagedPlanModel() {
        RepositoryId here = RepositoryId.newId();
        RepositoryId there = RepositoryId.newId();
        Store store = new Store();
        store.open(there, "x", TaskStatus.STARTED, List.of("session-9"));
        store.open(here, "b", TaskStatus.OPEN, List.of(), "x");
        ClaimLaunch.Attempt attempt = attempt(store, "b", Set.of(), Set.of());
        assertTrue(attempt.refused());
        assertTrue(attempt.refusal().contains("OTHER repositories"), attempt.refusal());
        assertTrue(attempt.refusal().contains("S5"), attempt.refusal());
    }

    /** Only the S5 cross-repository refusal is healable (it stops firing once the
      * cross-repo prerequisite reaches DONE); every other validate refusal is
      * structural and only a human edit lifts it. */
    @Test
    void onlyTheCrossRepoRefusalIsHealableEveryOtherRefusalIsStructural() {
        RepositoryId here = RepositoryId.newId();
        RepositoryId there = RepositoryId.newId();

        Store crossRepo = new Store();
        crossRepo.open(there, "x", TaskStatus.STARTED, List.of("session-9"));
        crossRepo.open(here, "b", TaskStatus.OPEN, List.of(), "x");
        ClaimLaunch.Attempt healable = attempt(crossRepo, "b", Set.of(), Set.of());
        assertTrue(healable.refused());
        assertTrue(healable.healable(), "the S5 refusal heals when the cross-repo"
                + " prerequisite reaches DONE");

        Store notOpen = new Store();
        notOpen.open(here, "a", TaskStatus.DONE, List.of());
        notOpen.open(here, "b", TaskStatus.REVIEW, List.of(), "a");
        assertTrue(attempt(notOpen, "b", Set.of(), Set.of()).refused());
        assertFalse(attempt(notOpen, "b", Set.of(), Set.of()).healable(),
                "not open is structural");

        Store owned = new Store();
        owned.open(here, "a", TaskStatus.DONE, List.of());
        owned.open(here, "live", TaskStatus.OPEN, List.of("session-live"), "a");
        assertFalse(attempt(owned, "live", Set.of("session-live"), Set.of()).healable(),
                "live-session ownership is structural");

        Store inFlight = new Store();
        inFlight.open(here, "a", TaskStatus.DONE, List.of());
        inFlight.open(here, "flying", TaskStatus.OPEN, List.of(), "a");
        assertFalse(attempt(inFlight, "flying", Set.of(), Set.of(TaskId.of("flying"))).healable(),
                "in-flight is structural");

        Store broken = new Store();
        broken.open(here, "b", TaskStatus.OPEN, List.of(), "no-such-task-w1w2");
        assertFalse(attempt(broken, "b", Set.of(), Set.of()).healable(),
                "an unsound graph is structural");

        Store orphan = new Store();
        orphan.open(here, "solo", TaskStatus.OPEN, List.of());
        assertFalse(attempt(orphan, "solo", Set.of(), Set.of()).healable(),
                "no in-repository prerequisite is structural");
    }

    @Test
    void aDoneCrossRepoPrerequisiteDoesNotBlockTheClaim() {
        RepositoryId here = RepositoryId.newId();
        RepositoryId there = RepositoryId.newId();
        Store store = new Store();
        store.open(there, "x", TaskStatus.DONE, List.of());
        store.open(here, "a", TaskStatus.DONE, List.of());
        store.open(here, "b", TaskStatus.OPEN, List.of(), "a", "x");
        ClaimLaunch.Attempt attempt = attempt(store, "b", Set.of(), Set.of());
        assertTrue(!attempt.refused(), attempt.refusal());
        assertEquals(TaskId.of("a"), attempt.shape().base());
    }

    @Test
    void aLiveOwnedTaskRefusesAndADeadSessionDoesNot() {
        RepositoryId repository = RepositoryId.newId();
        Store store = new Store();
        store.open(repository, "a", TaskStatus.DONE, List.of());
        store.open(repository, "live", TaskStatus.OPEN, List.of("session-live"), "a");
        store.open(repository, "dead", TaskStatus.OPEN, List.of("session-dead"), "a");
        ClaimLaunch.Attempt owned = attempt(store, "live", Set.of("session-live"), Set.of());
        assertTrue(owned.refused());
        assertTrue(owned.refusal().contains("owned by the live session"), owned.refusal());
        ClaimLaunch.Attempt interrupted = attempt(store, "dead", Set.of("session-live"), Set.of());
        assertTrue(!interrupted.refused(), interrupted.refusal());
    }

    @Test
    void anInFlightTaskRefusesAndSoDoesANonOpenOne() {
        RepositoryId repository = RepositoryId.newId();
        Store store = new Store();
        store.open(repository, "a", TaskStatus.DONE, List.of());
        store.open(repository, "flying", TaskStatus.OPEN, List.of(), "a");
        store.open(repository, "reviewing", TaskStatus.REVIEW, List.of(), "a");
        ClaimLaunch.Attempt inFlight = attempt(store, "flying", Set.of(), Set.of(TaskId.of("flying")));
        assertTrue(inFlight.refused());
        assertTrue(inFlight.refusal().contains("already in flight"), inFlight.refusal());
        ClaimLaunch.Attempt closed = attempt(store, "reviewing", Set.of(), Set.of());
        assertTrue(closed.refused());
        assertTrue(closed.refusal().contains("review, not open"), closed.refusal());
    }

    @Test
    void aBrokenReferenceRefusesTheClaim() {
        RepositoryId repository = RepositoryId.newId();
        Store store = new Store();
        store.open(repository, "b", TaskStatus.OPEN, List.of(), "no-such-task-w1w2");
        ClaimLaunch.Attempt attempt = attempt(store, "b", Set.of(), Set.of());
        assertTrue(attempt.refused());
        assertTrue(attempt.refusal().contains("unsound"), attempt.refusal());
    }

    @Test
    void autoStartCandidatesAreTheOpenUnownedDescendantsOfThePlanTree() {
        RepositoryId repository = RepositoryId.newId();
        Store store = new Store();
        store.open(repository, "a", TaskStatus.STARTED, List.of("session-a"));
        store.open(repository, "b", TaskStatus.OPEN, List.of(), "a");
        store.open(repository, "c", TaskStatus.OPEN, List.of(), "b");
        store.open(repository, "d", TaskStatus.OPEN, List.of());
        store.open(repository, "e", TaskStatus.OPEN, List.of("session-live"), "a");
        store.open(repository, "f", TaskStatus.REVIEW, List.of(), "a");
        store.open(repository, "g", TaskStatus.DONE, List.of(), "a");
        List<TaskId> candidates = ClaimLaunch.autoStartCandidates(store.snapshot(), store.graph(),
                Set.of(TaskId.of("a")), Set.of("session-a", "session-live"));
        assertEquals(List.of(TaskId.of("b"), TaskId.of("c")), candidates,
                "open, unowned descendants in dependency order; the plan's own task, the"
                        + " live-owned, the closed and the unrelated are never candidates");
    }

    @Test
    void noPlanNoCandidates() {
        RepositoryId repository = RepositoryId.newId();
        Store store = new Store();
        store.open(repository, "a", TaskStatus.DONE, List.of());
        store.open(repository, "b", TaskStatus.OPEN, List.of(), "a");
        assertEquals(List.of(), ClaimLaunch.autoStartCandidates(store.snapshot(), store.graph(),
                Set.of(), Set.of()));
    }
}
