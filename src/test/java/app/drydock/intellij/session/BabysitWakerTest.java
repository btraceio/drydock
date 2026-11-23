package app.drydock.intellij.session;

import app.drydock.agent.api.AgentKind;
import app.drydock.babysit.BabysitWakePolicy.Decision;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionActivity;
import app.drydock.domain.SessionWorkspace;
import app.drydock.intellij.agent.PromptOutcome;
import app.drydock.intellij.session.BabysitWaker.WakeOutcome;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BabysitWaker} executing the wake policy against recording seams:
 * the engagement gate, the per-babysitter serialisation and woken mark, and
 * the mapping of every write outcome.
 */
class BabysitWakerTest {

    private final ManagedSessionId id = ManagedSessionId.newId();
    private final List<String> submitted = new CopyOnWriteArrayList<>();
    private final List<Optional<String>> resumed = new CopyOnWriteArrayList<>();

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private volatile boolean engaged = true;
    private final List<ManagedSessionId> stopped = new CopyOnWriteArrayList<>();
    private PromptOutcome submitAnswer = PromptOutcome.DELIVERED;
    private boolean running;
    private boolean startingOrClaimed;
    private Optional<SessionActivity> activity = Optional.empty();
    private Optional<ManagedAgentSession> row = Optional.of(session(Optional.of("conv-1")));
    private CompletableFuture<ManagedAgentSession> resumeAnswer;

    private ManagedAgentSession session(Optional<String> agentSessionId) {
        return new ManagedAgentSession.Builder(id, RepositoryId.newId(), "babysit PR #42",
                new AgentBinding(AgentKind.CLAUDE, agentSessionId, Optional.empty()),
                SessionWorkspace.inRepository(Path.of("/wt")), Instant.parse("2026-11-18T10:00:00Z")).build();
    }

    private BabysitWaker waker;

    private BabysitWaker newWaker() {
        return new BabysitWaker(CompletableFuture.completedFuture(null),
                ignored -> engaged, ignored -> running, ignored -> startingOrClaimed, ignored -> activity,
                ignored -> row,
                (sessionId, prompt) -> {
                    submitted.add(prompt);
                    return submitAnswer;
                },
                (sessionId, prompt) -> {
                    resumed.add(prompt);
                    return resumeAnswer != null ? resumeAnswer : CompletableFuture.completedFuture(row.orElseThrow());
                },
                stopped::add, Runnable::run, nanos::get);
    }

    private BabysitWaker waker() {
        if (waker == null) {
            waker = newWaker();
        }
        return waker;
    }

    private WakeOutcome wake() throws Exception {
        return waker().wake(id, "Read /tmp/delta.md").get(5, TimeUnit.SECONDS);
    }

    @Test
    void anIdleRunningBabysitterIsTypedInto() throws Exception {
        running = true;
        activity = Optional.of(SessionActivity.IDLE);

        assertEquals(new WakeOutcome.Submitted(), wake());
        assertEquals(List.of("Read /tmp/delta.md"), submitted);
        assertTrue(resumed.isEmpty());
    }

    @Test
    void aWakesOwnIdleIsStaleUntilANonIdleSampleArrives() throws Exception {
        running = true;
        activity = Optional.of(SessionActivity.IDLE);
        assertEquals(new WakeOutcome.Submitted(), wake());

        // The monitor still shows the IDLE from before the paste: not trusted.
        assertEquals(new WakeOutcome.Skipped(Decision.SKIP_BUSY), wake());
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(60));
        assertEquals(new WakeOutcome.Skipped(Decision.SKIP_BUSY), wake());
        assertEquals(1, submitted.size());

        // The agent reports its turn; the IDLE after it is a real one.
        activity = Optional.of(SessionActivity.BUSY);
        assertEquals(new WakeOutcome.Skipped(Decision.SKIP_BUSY), wake());
        activity = Optional.of(SessionActivity.IDLE);
        assertEquals(new WakeOutcome.Submitted(), wake());
        assertEquals(2, submitted.size());
    }

    @Test
    void aWakesOwnIdleIsTrustedAgainOnceTheWindowHasPassed() throws Exception {
        running = true;
        activity = Optional.of(SessionActivity.IDLE);
        assertEquals(new WakeOutcome.Submitted(), wake());

        nanos.addAndGet(BabysitWaker.WOKEN_STALE_WINDOW.toNanos() - 1);
        assertEquals(new WakeOutcome.Skipped(Decision.SKIP_BUSY), wake());
        nanos.addAndGet(1);
        assertEquals(new WakeOutcome.Submitted(), wake());
        assertEquals(2, submitted.size());
    }

    @Test
    void aResumedBabysittersIdleIsStaleTooAndAFailedResumeDropsTheMark() throws Exception {
        assertInstanceOf(WakeOutcome.Resumed.class, wake());
        running = true;
        activity = Optional.of(SessionActivity.IDLE);
        assertEquals(new WakeOutcome.Skipped(Decision.SKIP_BUSY), wake(), "the resumed turn has not been reported");

        BabysitWaker fresh = newWaker();
        running = false;
        resumeAnswer = CompletableFuture.failedFuture(new IllegalStateException("tab could not open"));
        assertInstanceOf(WakeOutcome.Failed.class, fresh.wake(id, "Read /tmp/delta.md").get(5, TimeUnit.SECONDS));
        running = true;
        assertEquals(new WakeOutcome.Submitted(), fresh.wake(id, "Read /tmp/delta.md").get(5, TimeUnit.SECONDS));
    }

    @Test
    void twoConcurrentWakesOfOneBabysitterTypeOnce() throws Exception {
        running = true;
        activity = Optional.of(SessionActivity.IDLE);
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger writes = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            BabysitWaker serialised = new BabysitWaker(CompletableFuture.completedFuture(null),
                    ignored -> true, ignored -> running, ignored -> false, ignored -> activity, ignored -> row,
                    (sessionId, prompt) -> {
                        writes.incrementAndGet();
                        writing.countDown();
                        try {
                            release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return PromptOutcome.DELIVERED;
                    },
                    (sessionId, prompt) -> CompletableFuture.completedFuture(row.orElseThrow()),
                    stopped::add, pool, nanos::get);

            CompletableFuture<WakeOutcome> first = serialised.wake(id, "Read /tmp/a.md");
            assertTrue(writing.await(5, TimeUnit.SECONDS));
            CompletableFuture<WakeOutcome> second = serialised.wake(id, "Read /tmp/b.md");
            Thread.sleep(50);
            assertFalse(second.isDone(), "the second wake waits for the first's write");
            release.countDown();

            assertEquals(new WakeOutcome.Submitted(), first.get(5, TimeUnit.SECONDS));
            assertEquals(new WakeOutcome.Skipped(Decision.SKIP_BUSY), second.get(5, TimeUnit.SECONDS));
            assertEquals(1, writes.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aWriteThatWasNotDeliveredIsNeverReportedAsSubmitted() throws Exception {
        running = true;
        activity = Optional.of(SessionActivity.IDLE);

        submitAnswer = PromptOutcome.REFUSED;
        assertEquals(new WakeOutcome.Skipped(Decision.SKIP_BUSY), wake(), "nothing was written: retry later");
        submitAnswer = PromptOutcome.FAILED;
        WakeOutcome.Failed failed = assertInstanceOf(WakeOutcome.Failed.class, wake());
        assertEquals(Optional.of(Decision.WAKE_VIA_SUBMIT), failed.attempted());

        // Neither marked the babysitter woken: the next delivered write goes through.
        submitAnswer = PromptOutcome.DELIVERED;
        assertEquals(new WakeOutcome.Submitted(), wake());
    }

    @Test
    void aBabysitterWithoutAnActiveEngagementIsNeverWoken() throws Exception {
        engaged = false;
        running = true;
        activity = Optional.of(SessionActivity.IDLE);
        assertEquals(new WakeOutcome.Inactive(), wake());

        running = false;
        assertEquals(new WakeOutcome.Inactive(), wake());
        assertTrue(submitted.isEmpty());
        assertTrue(resumed.isEmpty());
    }

    @Test
    void aRunningBabysitterThatMayBeMidTurnIsLeftAlone() throws Exception {
        running = true;
        for (Optional<SessionActivity> unsafe : List.of(Optional.<SessionActivity>empty(),
                Optional.of(SessionActivity.BUSY), Optional.of(SessionActivity.NEEDS_ATTENTION))) {
            activity = unsafe;
            assertEquals(new WakeOutcome.Skipped(Decision.SKIP_BUSY), wake(), unsafe.toString());
        }
        startingOrClaimed = true;
        running = false;
        assertEquals(new WakeOutcome.Skipped(Decision.SKIP_BUSY), wake());
        assertTrue(submitted.isEmpty());
        assertTrue(resumed.isEmpty());
    }

    @Test
    void aStoppedBoundBabysitterIsResumedWithThePointer() throws Exception {
        WakeOutcome outcome = wake();

        assertInstanceOf(WakeOutcome.Resumed.class, outcome);
        assertEquals(List.of(Optional.of("Read /tmp/delta.md")), resumed);
        assertTrue(submitted.isEmpty());
    }

    @Test
    void aStopThatLandsWhileTheResumeIsDispatchedStopsTheResumedAgent() throws Exception {
        CompletableFuture<ManagedAgentSession> launching = new CompletableFuture<>();
        resumeAnswer = launching;
        BabysitWaker waker = waker();
        CompletableFuture<WakeOutcome> outcome = waker.wake(id, "Read /tmp/delta.md");
        assertEquals(List.of(Optional.of("Read /tmp/delta.md")), resumed, "dispatched while still engaged");

        // Stop wins in state before the resume has registered its agent: its own stop found nothing.
        engaged = false;
        launching.complete(row.orElseThrow());

        assertEquals(new WakeOutcome.Inactive(), outcome.get(5, TimeUnit.SECONDS));
        assertEquals(List.of(id), stopped, "the agent the late resume started is stopped");
    }

    @Test
    void aResumeThatCompletesWhileStillEngagedStopsNothing() throws Exception {
        assertInstanceOf(WakeOutcome.Resumed.class, wake());
        assertEquals(List.of(), stopped);
    }

    @Test
    void aStoppedUnboundBabysitterIsSkippedWithoutAResume() throws Exception {
        row = Optional.of(session(Optional.empty()));

        assertEquals(new WakeOutcome.Skipped(Decision.SKIP_UNBOUND), wake());
        assertTrue(resumed.isEmpty());
    }

    @Test
    void aResumeThatLostTheRaceIsASkipAndAnyOtherFailureIsReported() throws Exception {
        resumeAnswer = CompletableFuture.failedFuture(new DrydockSessionService.SessionBusyException(id));
        assertEquals(new WakeOutcome.Skipped(Decision.SKIP_BUSY), wake());

        resumeAnswer = CompletableFuture.failedFuture(
                new DrydockSessionService.UnboundConversationException(id, "no conversation id is bound yet"));
        assertEquals(new WakeOutcome.Skipped(Decision.SKIP_UNBOUND), wake());

        resumeAnswer = CompletableFuture.failedFuture(new IllegalStateException("tab could not open"));
        WakeOutcome.Failed failed = assertInstanceOf(WakeOutcome.Failed.class, wake());
        assertEquals(Optional.of(Decision.WAKE_VIA_RESUME), failed.attempted());
        assertEquals("tab could not open", failed.message());
    }

    @Test
    void aGoneBabysitterFailsAndAMultiLinePointerIsRefusedUpFront() throws Exception {
        row = Optional.empty();
        assertInstanceOf(WakeOutcome.Failed.class, wake());

        assertThrows(IllegalArgumentException.class, () -> waker().wake(id, "Read this\nand that"));
        assertTrue(submitted.isEmpty());
        assertTrue(resumed.isEmpty());
    }
}
