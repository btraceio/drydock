package app.drydock.editorqa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import app.drydock.editorqa.EditorAskLane.Admission;
import app.drydock.editorqa.EditorAskLane.Event;
import app.drydock.editorqa.EditorAskLane.EventKind;
import app.drydock.editorqa.EditorAskLane.Reservation;
import app.drydock.editorqa.EditorAskLane.Update;
import app.drydock.editorqa.EditorAskLane.WriteOutcome;
import app.drydock.editorqa.EditorAskRecord.Status;

class EditorAskLaneTest {

    static final class ManualClock implements EditorAskClock {
        volatile Instant now = Instant.parse("2030-01-01T00:00:00Z");

        @Override
        public Instant now() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    final ManualClock clock = new ManualClock();
    final List<Update> updates = new CopyOnWriteArrayList<>();
    EditorAskLane lane;
    long cursor;

    @BeforeEach
    void setUp() {
        lane = new EditorAskLane(clock, updates::add);
    }

    static String id(int n) {
        return "editor-00000000-0000-0000-0000-" + String.format("%012d", n);
    }

    String lastLabel(String id) {
        String label = null;
        for (Update u : updates) {
            if (u.id().equals(id)) {
                label = u.label();
            }
        }
        return label;
    }

    Status lastStatus(String id) {
        Status s = null;
        for (Update u : updates) {
            if (u.id().equals(id)) {
                s = u.status();
            }
        }
        return s;
    }

    String lastReservedId() {
        String reserved = null;
        for (Update u : updates) {
            if ("Starting…".equals(u.label())) {
                reserved = u.id();
            }
        }
        return reserved;
    }

    Event ev(String exec, long gen, EventKind kind, String marker) {
        return new Event(exec, gen, kind, marker, ++cursor);
    }

    /** Admits, starts and submits turn n as the first turn on execution "x1"; returns its reservation. */
    Reservation startFirst(int n) {
        Reservation r = lane.admit(id(n)).reservation();
        assertNotNull(r);
        assertTrue(lane.beginSend(r, "x1"));
        lane.writeReturned(r, WriteOutcome.DELIVERED);
        lane.onEvent(ev("x1", r.generation(), EventKind.SUBMITTED, id(n)));
        return r;
    }

    /** Terminal + readiness for turn n on x1/gen; returns the next reservation. */
    Reservation finish(int n, long gen) {
        lane.onEvent(ev("x1", gen, EventKind.END, id(n)));
        return lane.onEvent(ev("x1", gen, EventKind.READY, id(n)));
    }

    @Test
    void firstTurnIsReservedBeforeAnyIoAndLabelledStarting() {
        Admission a = lane.admit(id(1));
        assertTrue(a.accepted());
        assertEquals(EditorAskLane.Mode.LAUNCH, a.reservation().mode());
        assertEquals("Starting…", lastLabel(id(1)));
    }

    @Test
    void secondTurnQueuesWithAheadCountAndFollowsAsWriteAfterReadiness() {
        Reservation r1 = startFirst(1);
        assertEquals("Waiting for answer…", lastLabel(id(1)));
        Admission a2 = lane.admit(id(2));
        Admission a3 = lane.admit(id(3));
        assertNull(a2.reservation());
        assertEquals("Queued, 1 ahead", lastLabel(id(2)));
        assertEquals("Queued, 2 ahead", lastLabel(id(3)));
        Reservation r2 = finish(1, r1.generation());
        assertNotNull(r2);
        assertEquals(id(2), r2.id());
        assertEquals(EditorAskLane.Mode.WRITE, r2.mode());
        assertEquals("x1", r2.executionId());
        assertEquals("Starting…", lastLabel(id(2)));
        assertEquals("Queued, 1 ahead", lastLabel(id(3)));
        assertNull(a3.reservation());
    }

    @Test
    void answerNeverReleasesTheSlotAndQueuedLabelStillCountsAnsweredPredecessor() {
        Reservation r1 = startFirst(1);
        lane.admit(id(2));
        assertTrue(lane.acceptAnswer(id(1), "x1", r1.generation()));
        assertEquals(Status.ANSWERED, lastStatus(id(1)));
        assertEquals("Queued, 1 ahead", lastLabel(id(2)));
        assertNull(lane.poll());
        assertFalse(lane.acceptAnswer(id(1), "x1", r1.generation()), "duplicate answer refused");
    }

    @Test
    void answerRightNeverExistsForQueuedIdOrForeignExecution() {
        Reservation r1 = startFirst(1);
        lane.admit(id(2));
        assertFalse(lane.hasAnswerRight(id(2)));
        assertFalse(lane.acceptAnswer(id(2), "x1", r1.generation()));
        assertFalse(lane.acceptAnswer(id(1), "other", r1.generation()));
        assertFalse(lane.acceptAnswer(id(1), "x1", r1.generation() + 1));
        assertTrue(lane.hasAnswerRight(id(1)));
    }

    @Test
    void terminalWithoutReadinessKeepsTheGateClosed() {
        Reservation r1 = startFirst(1);
        lane.admit(id(2));
        lane.onEvent(ev("x1", r1.generation(), EventKind.END, id(1)));
        assertEquals(Status.NO_ANSWER, lastStatus(id(1)));
        assertEquals("Queued, 1 ahead", lastLabel(id(2)));
        assertNull(lane.onEvent(ev("x1", r1.generation(), EventKind.ACTIVITY, null)));
        assertNull(lane.poll());
    }

    @Test
    void activityBetweenTerminalAndReadyInvalidatesTheCandidate() {
        Reservation r1 = startFirst(1);
        lane.admit(id(2));
        lane.onEvent(ev("x1", r1.generation(), EventKind.END, id(1)));
        lane.onEvent(new Event("x1", r1.generation(), EventKind.ACTIVITY, null, 100));
        assertNull(lane.onEvent(new Event("x1", r1.generation(), EventKind.READY, id(1), 50)));
        assertNotNull(lane.onEvent(new Event("x1", r1.generation(), EventKind.READY, id(1), 101)));
    }

    @Test
    void staleDuplicateForeignAndEarlyEventsAreIgnored() {
        Reservation r1 = startFirst(1);
        lane.admit(id(2));
        assertNull(lane.onEvent(ev("x1", r1.generation(), EventKind.READY, id(1))), "ready before terminal");
        assertNull(lane.onEvent(ev("x1", r1.generation(), EventKind.END, id(2))), "foreign marker");
        assertNull(lane.onEvent(ev("old", r1.generation(), EventKind.END, id(1))), "foreign execution");
        assertNull(lane.onEvent(ev("x1", r1.generation() + 1, EventKind.END, id(1))), "foreign generation");
        assertNull(lane.onEvent(new Event("x1", r1.generation(), EventKind.END, id(1), 1)), "cursor not increasing");
        assertEquals(Status.AWAITING, lastStatus(id(1)));
    }

    @Test
    void prepareDeadlineBoundaryIsInclusiveAndSettlesNotSentWithoutClosingTheGate() {
        Reservation r1 = lane.admit(id(1)).reservation();
        lane.admit(id(2));
        clock.advance(Duration.ofSeconds(30).minusMillis(1));
        assertNull(lane.poll());
        assertEquals("Starting…", lastLabel(id(1)));
        clock.advance(Duration.ofMillis(1));
        Reservation next = lane.poll();
        assertEquals(Status.FAILED_NOT_SENT, lastStatus(id(1)));
        assertNotNull(next, "nothing was sent, so the next head may launch");
        assertEquals(id(2), next.id());
        assertFalse(lane.beginSend(r1, "late"), "late launch of the expired reservation is fenced");
    }

    @Test
    void sendDeadlineWithoutSubmissionEvidenceClosesTheGateAndSettlesQueued() {
        Reservation r1 = lane.admit(id(1)).reservation();
        lane.admit(id(2));
        assertTrue(lane.beginSend(r1, "x1"));
        lane.writeReturned(r1, WriteOutcome.DELIVERED);
        clock.advance(Duration.ofSeconds(30));
        assertNull(lane.poll());
        assertEquals(Status.INTERRUPTED_POSSIBLY_SENT, lastStatus(id(1)));
        assertEquals(Status.FAILED_NOT_SENT, lastStatus(id(2)));
        assertEquals(EditorAskLane.RejectReason.GATE_CLOSED, lane.admit(id(3)).rejection());
        assertNull(lane.onEvent(ev("x1", r1.generation(), EventKind.READY, id(1))), "READY cannot reopen it");
        Reservation fresh = lane.executionEnded("x1", r1.generation());
        assertNull(fresh);
        assertEquals(EditorAskLane.Mode.LAUNCH, lane.admit(id(4)).reservation().mode());
    }

    @Test
    void turnDeadlineIsTenMinutesFromSubmissionEvidence() {
        Reservation r1 = startFirst(1);
        clock.advance(Duration.ofMinutes(10).minusMillis(1));
        lane.poll();
        assertEquals(Status.AWAITING, lastStatus(id(1)));
        clock.advance(Duration.ofMillis(1));
        lane.poll();
        assertEquals(Status.INTERRUPTED_POSSIBLY_SENT, lastStatus(id(1)));
        assertNull(lane.admit(id(2)).reservation());
    }

    @Test
    void queuedEntryExpiresFifteenMinutesAfterAdmission() {
        Reservation r1 = startFirst(1);
        lane.admit(id(2));
        clock.advance(Duration.ofMinutes(14));
        lane.onEvent(ev("x1", r1.generation(), EventKind.ACTIVITY, null));
        assertEquals("Queued, 1 ahead", lastLabel(id(2)));
        clock.advance(Duration.ofMinutes(1));
        lane.poll();
        assertEquals(Status.FAILED_NOT_SENT, lastStatus(id(2)));
    }

    @Test
    void refusedWriteIsNotSentAndKeepsTheGateOpenButFailedIsAmbiguous() {
        Reservation r1 = startFirst(1);
        Reservation r2 = null;
        lane.admit(id(2));
        lane.admit(id(3));
        r2 = finish(1, r1.generation());
        assertTrue(lane.beginSend(r2, "x1"));
        Reservation r3 = lane.writeReturned(r2, WriteOutcome.REFUSED);
        assertEquals(Status.FAILED_NOT_SENT, lastStatus(id(2)));
        assertEquals(id(3), r3.id());
        assertTrue(lane.beginSend(r3, "x1"));
        assertNull(lane.writeReturned(r3, WriteOutcome.FAILED));
        assertEquals(Status.INTERRUPTED_POSSIBLY_SENT, lastStatus(id(3)));
    }

    @Test
    void executionEndAfterSubmissionIsNoAnswerButAnsweredStaysAnswered() {
        Reservation r1 = startFirst(1);
        lane.admit(id(2));
        lane.executionEnded("x1", r1.generation());
        assertEquals(Status.NO_ANSWER, lastStatus(id(1)));
        assertEquals(id(2), lane.admit(id(3)).accepted() ? lastReservedId() : null);
    }

    @Test
    void lateEndOfAReplacedExecutionDoesNotPoisonTheNewOne() {
        Reservation r1 = startFirst(1);
        Reservation r2 = lane.executionEnded("x1", r1.generation());
        assertNull(r2);
        Reservation n = lane.admit(id(2)).reservation();
        assertTrue(lane.beginSend(n, "x2"));
        assertNull(lane.executionEnded("x1", r1.generation()));
        assertTrue(lane.hasAnswerRight(id(2)));
    }

    @Test
    void capacityIs32CountingTheActiveTurn() {
        for (int i = 1; i <= 32; i++) {
            assertTrue(lane.admit(id(i)).accepted(), "admit " + i);
        }
        Admission over = lane.admit(id(33));
        assertFalse(over.accepted());
        assertEquals(EditorAskLane.RejectReason.CAPACITY, over.rejection());
        assertEquals(32, lane.unfinishedCount());
    }

    @Test
    void duplicateIdIsRejected() {
        lane.admit(id(1));
        assertEquals(EditorAskLane.RejectReason.DUPLICATE, lane.admit(id(1)).rejection());
    }

    @Test
    void shutdownSettlesEverythingAndRefusesFurtherWork() {
        Reservation r1 = startFirst(1);
        lane.admit(id(2));
        lane.shutdown();
        assertEquals(Status.NO_ANSWER, lastStatus(id(1)));
        assertEquals(Status.FAILED_NOT_SENT, lastStatus(id(2)));
        assertEquals(EditorAskLane.RejectReason.SHUTDOWN, lane.admit(id(3)).rejection());
        assertFalse(lane.acceptAnswer(id(1), "x1", r1.generation()));
        assertEquals(0, lane.unfinishedCount());
    }

    @Test
    void checkoutsAreIndependent() {
        List<Update> other = new ArrayList<>();
        EditorAskLane lane2 = new EditorAskLane(clock, other::add);
        startFirst(1);
        Admission b = lane2.admit(id(9));
        assertNotNull(b.reservation(), "another checkout is not blocked by the busy one");
    }
}
