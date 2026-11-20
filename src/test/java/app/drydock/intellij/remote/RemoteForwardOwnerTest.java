package app.drydock.intellij.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.drydock.intellij.remote.RemoteForwardOwner.ForwardException;
import app.drydock.intellij.remote.RemoteForwardOwner.ForwardHandle;
import app.drydock.intellij.remote.RemoteForwardOwner.Prepared;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class RemoteForwardOwnerTest {

    private static final class FakeHandle implements ForwardHandle {
        final CompletableFuture<Integer> exit = new CompletableFuture<>();
        final AtomicInteger closes = new AtomicInteger();
        final List<String> argv;

        FakeHandle(List<String> argv) {
            this.argv = argv;
        }

        boolean live() {
            return closes.get() == 0;
        }

        @Override
        public CompletableFuture<Integer> exit() {
            return exit;
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            exit.complete(143);
        }
    }

    private long now;
    private final List<FakeHandle> handles = new ArrayList<>();
    private final List<Integer> probeCalls = new ArrayList<>();
    private int exitFirstN;
    private boolean ready = true;
    private boolean refuseStart;
    private Runnable duringProbe = () -> { };

    private RemoteForwardOwner owner() {
        return new RemoteForwardOwner(argv -> {
            if (refuseStart) {
                throw new IOException("spawn refused");
            }
            FakeHandle h = new FakeHandle(argv);
            handles.add(h);
            if (handles.size() <= exitFirstN) {
                h.exit.complete(255);
            }
            return h;
        }, (host, exclude) -> {
            int port = 40000;
            while (exclude.contains(port)) {
                port++;
            }
            probeCalls.add(port);
            return port;
        }, (id, host, port) -> {
            duringProbe.run();
            return ready;
        }, () -> now, d -> now += d.toNanos());
    }

    private long live() {
        return handles.stream().filter(FakeHandle::live).count();
    }

    private static final BooleanSupplier NEVER = () -> false;

    @Test
    void readyForwardIsPublishedWithReverseForwardArgv() throws Exception {
        try (RemoteForwardOwner owner = owner()) {
            Prepared p = owner.prepare("s1", "box", 7777, NEVER);
            assertTrue(p.isReady());
            assertEquals(1, owner.handleCount());
            assertTrue(handles.get(0).argv.contains("127.0.0.1:" + p.remotePort() + ":127.0.0.1:7777"));
        }
        assertEquals(0, live());
    }

    @Test
    void notReadyWithinBudgetFailsAndClosesHandle() {
        ready = false;
        RemoteForwardOwner owner = owner();
        assertThrows(ForwardException.class, () -> owner.prepare("s1", "box", 1, NEVER));
        assertTrue(now >= RemoteForwardOwner.READINESS_BUDGET.toNanos());
        assertTrue(now < RemoteForwardOwner.READINESS_BUDGET.toNanos() + Duration.ofSeconds(1).toNanos());
        assertEquals(0, live());
        assertEquals(0, owner.handleCount());
    }

    @Test
    void earlyExitIsRetriedAtMostThreeTimesThenFails() {
        exitFirstN = 99;
        RemoteForwardOwner owner = owner();
        assertThrows(ForwardException.class, () -> owner.prepare("s1", "box", 1, NEVER));
        assertEquals(RemoteForwardOwner.MAX_BIND_ATTEMPTS, handles.size());
        assertEquals(0, live());
    }

    @Test
    void bindRaceRetryThatSucceedsPublishesOnlyTheGoodHandle() throws Exception {
        exitFirstN = 2;
        RemoteForwardOwner owner = owner();
        Prepared p = owner.prepare("s1", "box", 1, NEVER);
        assertEquals(3, handles.size());
        assertEquals(1, live());
        assertTrue(p.isReady());
        assertEquals(1, owner.handleCount());
    }

    @Test
    void spawnRefusalFailsWithoutLeakingReservation() {
        refuseStart = true;
        RemoteForwardOwner owner = owner();
        assertThrows(ForwardException.class, () -> owner.prepare("s1", "box", 1, NEVER));
        assertEquals(0, owner.handleCount());
        assertTrue(probeCalls.stream().allMatch(port -> port == 40000), "failed ports are released");
    }

    @Test
    void cancelDuringReadinessClosesHandle() {
        AtomicBoolean cancelled = new AtomicBoolean();
        ready = false;
        duringProbe = () -> cancelled.set(true);
        RemoteForwardOwner owner = owner();
        assertThrows(ForwardException.class, () -> owner.prepare("s1", "box", 1, cancelled::get));
        assertEquals(1, handles.size());
        assertEquals(0, live());
    }

    @Test
    void ownerCloseDuringReadinessClosesInFlightHandleAndRejectsLaterPrepare() {
        RemoteForwardOwner owner = owner();
        ready = false;
        duringProbe = owner::close;
        assertThrows(ForwardException.class, () -> owner.prepare("s1", "box", 1, NEVER));
        assertEquals(0, live());
        assertEquals(0, owner.handleCount());
        assertThrows(ForwardException.class, () -> owner.prepare("s2", "box", 1, NEVER));
        assertEquals(1, handles.size());
    }

    @Test
    void ownerCloseRacingAReadyProbeNeverPublishes() {
        RemoteForwardOwner owner = owner();
        duringProbe = owner::close;
        assertThrows(ForwardException.class, () -> owner.prepare("s1", "box", 1, NEVER));
        assertEquals(0, live());
    }

    @Test
    void exitAfterPublicationInvalidatesReadinessOnce() throws Exception {
        RemoteForwardOwner owner = owner();
        Prepared p = owner.prepare("s1", "box", 1, NEVER);
        AtomicInteger invalidations = new AtomicInteger();
        p.onInvalidated(invalidations::incrementAndGet);
        handles.get(0).exit.complete(255); // keepalive loss
        assertFalse(p.isReady());
        assertEquals(1, invalidations.get());
        assertEquals(0, live());
        assertEquals(0, owner.handleCount());
    }

    @Test
    void ownerInitiatedCloseDoesNotReportKeepaliveFailure() throws Exception {
        RemoteForwardOwner owner = owner();
        Prepared p = owner.prepare("s1", "box", 1, NEVER);
        AtomicInteger invalidations = new AtomicInteger();
        p.onInvalidated(invalidations::incrementAndGet);
        p.close();
        assertFalse(p.isReady());
        assertEquals(0, invalidations.get());
        assertEquals(0, live());
    }

    @Test
    void sessionsGetIsolatedPortsAndExactHandleCountsOnOneBoxAndAcrossBoxes() throws Exception {
        RemoteForwardOwner owner = owner();
        Prepared a = owner.prepare("s1", "box", 1, NEVER);
        Prepared b = owner.prepare("s2", "box", 1, NEVER);
        Prepared c = owner.prepare("s3", "other", 1, NEVER);
        assertNotEquals(a.remotePort(), b.remotePort());
        assertNotEquals(a.generation(), b.generation());
        assertEquals(3, owner.handleCount());
        assertEquals(3, live());
        a.close();
        assertEquals(2, owner.handleCount());
        assertTrue(b.isReady() && c.isReady());
        assertTrue(handles.get(1).live() && handles.get(2).live());
        owner.close();
        assertEquals(0, live());
    }

    @Test
    void replacingASessionClosesItsPreviousGeneration() throws Exception {
        RemoteForwardOwner owner = owner();
        Prepared first = owner.prepare("s1", "box", 1, NEVER);
        Prepared second = owner.prepare("s1", "box", 1, NEVER);
        assertFalse(first.isReady());
        assertTrue(second.generation() > first.generation());
        assertEquals(1, owner.handleCount());
        assertEquals(1, live());
    }
}
