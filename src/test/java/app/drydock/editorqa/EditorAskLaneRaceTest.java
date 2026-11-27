package app.drydock.editorqa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import app.drydock.editorqa.EditorAskLane.Admission;
import app.drydock.editorqa.EditorAskLane.Event;
import app.drydock.editorqa.EditorAskLane.EventKind;
import app.drydock.editorqa.EditorAskLane.Reservation;
import app.drydock.editorqa.EditorAskLane.Update;
import app.drydock.editorqa.EditorAskLane.WriteOutcome;
import app.drydock.editorqa.EditorAskLaneTest.ManualClock;
import app.drydock.editorqa.EditorAskRecord.Status;

class EditorAskLaneRaceTest {

    static String id(int n) {
        return EditorAskLaneTest.id(n);
    }

    @Test
    void concurrentAdmissionsReserveExactlyOneLaunch() throws Exception {
        int n = 16;
        List<Update> updates = new CopyOnWriteArrayList<>();
        EditorAskLane lane = new EditorAskLane(new ManualClock(), updates::add);
        CyclicBarrier barrier = new CyclicBarrier(n);
        AtomicInteger reservations = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 1; i <= n; i++) {
                int k = i;
                fs.add(pool.submit(() -> {
                    barrier.await();
                    Admission a = lane.admit(id(k));
                    assertTrue(a.accepted());
                    if (a.reservation() != null) {
                        reservations.incrementAndGet();
                    }
                    return null;
                }));
            }
            for (Future<?> f : fs) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, reservations.get(), "one send reservation, one launch");
        assertEquals(n, lane.unfinishedCount());
    }

    @Test
    void aheadCountsNeverIncreaseBetweenTransitionsAsTurnsDrain() {
        ManualClock clock = new ManualClock();
        List<Update> updates = new ArrayList<>();
        EditorAskLane lane = new EditorAskLane(clock, updates::add);
        Reservation r = lane.admit(id(1)).reservation();
        for (int i = 2; i <= 5; i++) {
            lane.admit(id(i));
        }
        long cursor = 0;
        long gen = r.generation();
        lane.beginSend(r, "x1");
        for (int i = 1; i <= 4; i++) {
            lane.onEvent(new Event("x1", gen, EventKind.SUBMITTED, id(i), ++cursor));
            lane.onEvent(new Event("x1", gen, EventKind.END, id(i), ++cursor));
            Reservation next = lane.onEvent(new Event("x1", gen, EventKind.READY, id(i), ++cursor));
            assertNotNull(next);
            assertTrue(lane.beginSend(next, "x1"));
        }
        for (int i = 3; i <= 5; i++) {
            List<Integer> aheads = new ArrayList<>();
            for (Update u : updates) {
                if (u.id().equals(id(i)) && u.label().startsWith("Queued, ")) {
                    aheads.add(Integer.parseInt(u.label().replaceAll("\\D+", "")));
                }
            }
            for (int k = 1; k < aheads.size(); k++) {
                assertTrue(aheads.get(k) < aheads.get(k - 1), "labels strictly decrease: " + aheads);
            }
        }
    }

    @Test
    void callbackBeforeWriteReturnsCommitsAndLateFailureDoesNotDemoteIt() {
        List<Update> updates = new CopyOnWriteArrayList<>();
        EditorAskLane lane = new EditorAskLane(new ManualClock(), updates::add);
        Reservation r = lane.admit(id(1)).reservation();
        assertTrue(lane.beginSend(r, "x1"));
        assertTrue(lane.acceptAnswer(id(1), "x1", r.generation()), "right exists before the write returns");
        lane.writeReturned(r, WriteOutcome.FAILED);
        Status last = updates.get(updates.size() - 1).status();
        assertEquals(Status.ANSWERED, updates.stream().filter(u -> u.id().equals(id(1)))
                .reduce((a, b) -> b).orElseThrow().status(), "last=" + last);
    }

    @Test
    void lateWriteOutcomeOfAnOldReservationNeverPoisonsANewerAttempt() {
        ManualClock clock = new ManualClock();
        EditorAskLane lane = new EditorAskLane(clock, u -> { });
        Reservation old = lane.admit(id(1)).reservation();
        lane.admit(id(2));
        clock.advance(Duration.ofSeconds(30));
        Reservation fresh = lane.poll();
        assertEquals(id(2), fresh.id());
        assertTrue(lane.beginSend(fresh, "x2"));
        assertNull(lane.writeReturned(old, WriteOutcome.FAILED));
        assertFalse(lane.beginSend(old, "x1"));
        assertTrue(lane.hasAnswerRight(id(2)));
    }

    @Test
    void pausedPermissionPromptKeepsTheGateClosedUntilProvenReadiness() {
        EditorAskLane lane = new EditorAskLane(new ManualClock(), u -> { });
        Reservation r = lane.admit(id(1)).reservation();
        lane.admit(id(2));
        lane.beginSend(r, "x1");
        long g = r.generation();
        lane.onEvent(new Event("x1", g, EventKind.SUBMITTED, id(1), 1));
        assertTrue(lane.acceptAnswer(id(1), "x1", g));
        lane.onEvent(new Event("x1", g, EventKind.ACTIVITY, null, 2));
        assertNull(lane.poll());
        lane.onEvent(new Event("x1", g, EventKind.END, id(1), 3));
        assertNull(lane.onEvent(new Event("x1", g, EventKind.ACTIVITY, null, 4)));
        assertNotNull(lane.onEvent(new Event("x1", g, EventKind.READY, id(1), 5)));
    }

    @Test
    void concurrentLateCompletionsAndEventsLeaveOneConsistentOutcome() throws Exception {
        for (int round = 0; round < 50; round++) {
            List<Update> updates = new CopyOnWriteArrayList<>();
            EditorAskLane lane = new EditorAskLane(new ManualClock(), updates::add);
            Reservation r = lane.admit(id(1)).reservation();
            lane.beginSend(r, "x1");
            long g = r.generation();
            CyclicBarrier barrier = new CyclicBarrier(3);
            ExecutorService pool = Executors.newFixedThreadPool(3);
            try {
                Future<Boolean> answer = pool.submit(() -> {
                    barrier.await();
                    return lane.acceptAnswer(id(1), "x1", g);
                });
                Future<?> end = pool.submit(() -> {
                    barrier.await();
                    return lane.executionEnded("x1", g);
                });
                Future<?> fail = pool.submit(() -> {
                    barrier.await();
                    return lane.writeReturned(r, WriteOutcome.FAILED);
                });
                boolean answered = answer.get(10, TimeUnit.SECONDS);
                end.get(10, TimeUnit.SECONDS);
                fail.get(10, TimeUnit.SECONDS);
                Status finalStatus = updates.stream().filter(u -> u.id().equals(id(1)))
                        .reduce((a, b) -> b).orElseThrow().status();
                if (answered) {
                    assertEquals(Status.ANSWERED, finalStatus);
                } else {
                    assertTrue(finalStatus.isSettled(), "settled, got " + finalStatus);
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void busyCheckoutDoesNotBlockAnother() {
        EditorAskLane busy = new EditorAskLane(new ManualClock(), u -> { });
        EditorAskLane idle = new EditorAskLane(new ManualClock(), u -> { });
        busy.admit(id(1));
        assertNotNull(idle.admit(id(1)).reservation());
    }
}
