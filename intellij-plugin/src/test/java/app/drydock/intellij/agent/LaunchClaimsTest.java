package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The claim and guarded-release rules of {@link LaunchClaims}. */
class LaunchClaimsTest {

    private static final ManagedSessionId ID = ManagedSessionId.of("11111111-1111-1111-1111-111111111111");

    private final Set<ManagedSessionId> live = new HashSet<>();
    private final LaunchClaims claims = new LaunchClaims(live::contains);
    private final AtomicInteger releases = new AtomicInteger();

    private void release() {
        releases.incrementAndGet();
    }

    @Test
    void aClaimIsRefusedWhileTheSessionIsLive() {
        live.add(ID);
        assertFalse(claims.tryClaim(ID));
    }

    @Test
    void aSecondClaimIsRefused() {
        assertTrue(claims.tryClaim(ID));
        assertFalse(claims.tryClaim(ID));
    }

    @Test
    void aClaimIsAllowedAgainAfterFinish() {
        assertTrue(claims.tryClaim(ID));
        claims.finish(ID, this::release);
        assertTrue(claims.tryClaim(ID));
    }

    @Test
    void finishReleasesWhenTheSessionIsNotLive() {
        claims.tryClaim(ID);
        claims.finish(ID, this::release);
        assertEquals(1, releases.get());
    }

    @Test
    void finishSkipsTheReleaseWhenTheSessionIsLive() {
        claims.tryClaim(ID);
        live.add(ID);
        claims.finish(ID, this::release);
        assertEquals(0, releases.get());
        assertFalse(claims.tryClaim(ID), "still refused, now because it is live");
    }

    @Test
    void releaseIfIdleSkipsWhileClaimed() {
        claims.tryClaim(ID);
        assertFalse(claims.releaseIfIdle(ID, this::release));
        assertEquals(0, releases.get());
    }

    @Test
    void releaseIfIdleSkipsWhileLive() {
        live.add(ID);
        assertFalse(claims.releaseIfIdle(ID, this::release));
        assertEquals(0, releases.get());
    }

    @Test
    void releaseIfIdleRunsOtherwise() {
        assertTrue(claims.releaseIfIdle(ID, this::release));
        assertEquals(1, releases.get());
    }

    @Test
    void releaseIfIdleAfterFinishOfALiveLaunchSkips() {
        claims.tryClaim(ID);
        live.add(ID);
        claims.finish(ID, this::release);
        assertFalse(claims.releaseIfIdle(ID, this::release));
        assertEquals(0, releases.get());
    }

    @Test
    void finishWithoutAClaimDoesNotRelease() {
        claims.finish(ID, this::release);
        assertEquals(0, releases.get());
    }

    @Test
    void finishWithoutAClaimLeavesAnotherLaunchsClaimInPlace() {
        assertTrue(claims.tryClaim(ID));
        assertFalse(claims.tryClaim(ID));
        // The refused launch never claimed, so it must not end the claim it lost to.
        claims.finish(ManagedSessionId.of("22222222-2222-2222-2222-222222222222"), this::release);
        assertFalse(claims.tryClaim(ID));
        assertFalse(claims.releaseIfIdle(ID, this::release));
        assertEquals(0, releases.get());
    }

    @Test
    void aRemovalClaimIsAllowedWhileTheSessionIsLive() {
        live.add(ID);
        assertTrue(claims.tryClaimForRemoval(ID));
    }

    @Test
    void aRemovalClaimIsRefusedWhileALaunchHoldsTheClaim() {
        assertTrue(claims.tryClaim(ID));
        assertFalse(claims.tryClaimForRemoval(ID));
    }

    @Test
    void aLaunchIsRefusedWhileARemovalHoldsTheClaim() {
        assertTrue(claims.tryClaimForRemoval(ID));
        assertFalse(claims.tryClaim(ID));
        claims.finish(ID, this::release);
        assertEquals(1, releases.get());
        assertTrue(claims.tryClaim(ID));
    }

    @Test
    void concurrentClaimsOfOneSessionHaveExactlyOneWinner() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < 200; round++) {
                LaunchClaims shared = new LaunchClaims(id -> false);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Boolean>> results = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    results.add(pool.submit(() -> {
                        start.await();
                        return shared.tryClaim(ID);
                    }));
                }
                start.countDown();
                int winners = 0;
                for (Future<Boolean> result : results) {
                    if (result.get(10, TimeUnit.SECONDS)) {
                        winners++;
                    }
                }
                assertEquals(1, winners, "round " + round);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
