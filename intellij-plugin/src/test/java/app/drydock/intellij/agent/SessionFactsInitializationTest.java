package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Readiness-aware construction of the facts loader: nothing is submitted
 * before the supplied initialization future succeeds, its failure or
 * cancellation settles each active request as FAILED without a probe, a
 * refused submission settles it too, and disposal skips every invalid job.
 * Plain futures, a hand-driven executor and in-memory probes; no threads.
 */
class SessionFactsInitializationTest {

    private static final ManagedSessionId A = ManagedSessionId.newId();

    /** How a readiness future ends unsuccessfully. */
    private enum Ending {
        FAILURE {
            @Override
            void end(CompletableFuture<Void> f) {
                f.completeExceptionally(new IllegalStateException("service broke"));
            }
        },
        CANCELLATION {
            @Override
            void end(CompletableFuture<Void> f) {
                assertTrue(f.cancel(false));
            }
        };

        abstract void end(CompletableFuture<Void> f);
    }

    /** Queues submissions for the test to drain; can refuse them like a shut-down executor. */
    private static final class Queued implements Executor {
        final Queue<Runnable> queue = new ArrayDeque<>();
        int attempts;
        boolean reject;

        @Override
        public void execute(Runnable task) {
            attempts++;
            if (reject) {
                throw new RejectedExecutionException("shut down");
            }
            queue.add(task);
        }

        void runNext() {
            queue.remove().run();
        }
    }

    /** Counts probes; {@code during} runs inside the next probe, standing in for a probe in flight. */
    private static final class Counting implements SessionFactsLoader.Probes {
        final AtomicInteger calls = new AtomicInteger();
        Runnable during;

        @Override
        public Optional<SessionPaneFacts.Inputs> probe(ManagedSessionId id) {
            calls.incrementAndGet();
            if (during != null) {
                Runnable hook = during;
                during = null;
                hook.run();
            }
            return Optional.of(new SessionPaneFacts.Inputs(Optional.of(Path.of("/w/a")), Path.of("/w/a"), true, false,
                    OptionalLong.of(4000), true, Optional.empty(), Optional.empty(), "a", true, true));
        }
    }

    private final CompletableFuture<Void> readiness = new CompletableFuture<>();
    private final Queued executor = new Queued();
    private final Counting probes = new Counting();
    private final List<SessionFactsLoader.Result> delivered = new ArrayList<>();
    private final SessionFactsLoader loader = new SessionFactsLoader(readiness, executor, probes, delivered::add);

    private static void assertFailedFor(SessionFactsLoader.Result result, SessionFactsLoader.Result request) {
        assertEquals(SessionPaneFacts.State.FAILED, result.facts().state());
        assertEquals(request.id(), result.id());
        assertEquals(request.generation(), result.generation());
    }

    @Test
    void pendingReadinessIsLoadingAndDeduplicatedUntilItSucceeds() {
        SessionFactsLoader.Result first = loader.select(A);
        SessionFactsLoader.Result again = loader.select(A);

        assertEquals(SessionPaneFacts.State.LOADING, first.facts().state());
        assertEquals(first.generation(), again.generation());
        assertEquals(0, executor.attempts);
        assertEquals(0, probes.calls.get());

        readiness.complete(null);
        assertEquals(1, executor.queue.size());
        assertEquals(0, probes.calls.get());
        assertTrue(delivered.isEmpty());

        executor.runNext();
        assertEquals(1, probes.calls.get());
        assertEquals(1, delivered.size());
        assertEquals(SessionPaneFacts.State.KNOWN, delivered.get(0).facts().state());
        assertEquals(first.generation(), delivered.get(0).generation());
    }

    @Test
    void alreadySuccessfulReadinessSubmitsWithoutProbingOnTheCaller() {
        readiness.complete(null);

        SessionFactsLoader.Result loading = loader.select(A);

        assertEquals(SessionPaneFacts.State.LOADING, loading.facts().state());
        assertEquals(1, executor.queue.size());
        assertEquals(0, probes.calls.get());
        executor.runNext();
        assertEquals(1, probes.calls.get());
        assertEquals(1, delivered.size());
    }

    @Test
    void delayedRejectionSettlesTheRequestOnceAndALaterSelectRecovers() {
        SessionFactsLoader.Result request = loader.select(A);
        executor.reject = true;

        readiness.complete(null);

        assertEquals(1, delivered.size());
        assertFailedFor(delivered.get(0), request);
        assertEquals(0, probes.calls.get());
        assertTrue(executor.queue.isEmpty());

        executor.reject = false;
        SessionFactsLoader.Result retry = loader.select(A);
        assertEquals(SessionPaneFacts.State.LOADING, retry.facts().state());
        assertTrue(retry.generation() > request.generation());
        executor.runNext();
        assertEquals(2, delivered.size());
        assertEquals(SessionPaneFacts.State.KNOWN, delivered.get(1).facts().state());
        assertEquals(retry.generation(), delivered.get(1).generation());
        assertEquals(1, probes.calls.get());
    }

    @Test
    void rejectionAfterAlreadyCompleteReadinessIsTheSynchronousFailedAnswerNotifiedOnce() {
        readiness.complete(null);
        executor.reject = true;

        SessionFactsLoader.Result returned = loader.select(A);

        assertEquals(1, delivered.size());
        assertFailedFor(returned, delivered.get(0));
        assertEquals(returned, delivered.get(0));
        assertEquals(0, probes.calls.get());
        executor.reject = false;
        assertEquals(SessionPaneFacts.State.LOADING, loader.select(A).facts().state()); // retries, not cached
    }

    @ParameterizedTest
    @EnumSource(Ending.class)
    void pendingReadinessThatEndsUnsuccessfullyFailsTheActiveRequestWithoutWork(Ending ending) {
        SessionFactsLoader.Result request = loader.select(A);

        ending.end(readiness);

        assertEquals(1, delivered.size());
        assertFailedFor(delivered.get(0), request);
        assertFalse(delivered.get(0).facts().message().isBlank());
        assertEquals(0, executor.attempts);
        assertEquals(0, probes.calls.get());
    }

    @ParameterizedTest
    @EnumSource(Ending.class)
    void alreadyEndedReadinessFailsSynchronouslyAndAReselectFailsAgainHonestly(Ending ending) {
        ending.end(readiness);

        SessionFactsLoader.Result first = loader.select(A);
        assertEquals(1, delivered.size());
        assertFailedFor(first, delivered.get(0));

        SessionFactsLoader.Result second = loader.select(A);
        assertEquals(2, delivered.size());
        assertFailedFor(second, delivered.get(1));
        assertTrue(second.generation() > first.generation());
        assertEquals(0, executor.attempts);
        assertEquals(0, probes.calls.get());
    }

    @Test
    void theFailureTextComesFromTheSuppliedFailureOrItsClass() {
        readiness.completeExceptionally(new IllegalStateException("service broke"));
        assertTrue(loader.select(A).facts().message().endsWith(": service broke"));

        CompletableFuture<Void> blank = new CompletableFuture<>();
        blank.completeExceptionally(new IllegalStateException());
        assertTrue(new SessionFactsLoader(blank, executor, probes, delivered::add).select(A).facts().message()
                .endsWith(": " + IllegalStateException.class.getName()));

        CompletableFuture<Void> cancelled = new CompletableFuture<>();
        cancelled.cancel(false);
        assertTrue(new SessionFactsLoader(cancelled, executor, probes, delivered::add).select(A).facts().message()
                .endsWith(": " + CancellationException.class.getName()));
    }

    @Test
    void theExecutorConstructorIsReadyImmediately() {
        SessionFactsLoader ready = new SessionFactsLoader(executor, probes, delivered::add);

        ready.select(A);

        assertEquals(1, executor.queue.size());
        assertEquals(0, probes.calls.get());
    }

    @ParameterizedTest
    @EnumSource(Ending.class)
    void disposeBeforeReadinessEndsKeepsEverythingSilent(Ending ending) {
        loader.select(A);
        loader.dispose();

        ending.end(readiness);
        assertTrue(delivered.isEmpty());
        assertEquals(0, executor.attempts);
        assertEquals(0, probes.calls.get());
    }

    @Test
    void disposeBeforeReadinessSucceedsSubmitsNothingAndLeavesTheFutureAlone() {
        loader.select(A);
        loader.dispose();

        readiness.complete(null);

        assertTrue(readiness.isDone() && !readiness.isCompletedExceptionally());
        assertEquals(0, executor.attempts);
        assertEquals(0, probes.calls.get());
        assertTrue(delivered.isEmpty());
    }

    @Test
    void disposeDoesNotCancelTheSharedReadiness() {
        loader.select(A);
        loader.dispose();

        assertFalse(readiness.isDone());
    }

    @Test
    void disposeAfterQueueAcceptanceSkipsTheProbe() {
        readiness.complete(null);
        loader.select(A);
        assertEquals(1, executor.queue.size());

        loader.dispose();
        executor.runNext();

        assertEquals(0, probes.calls.get());
        assertTrue(delivered.isEmpty());
    }

    @Test
    void aProbeRunningWhenDisposedFinishesWithoutNotifyingAndLaterWorkSchedulesNothing() {
        readiness.complete(null);
        loader.select(A);
        probes.during = loader::dispose;

        executor.runNext();

        assertEquals(1, probes.calls.get());
        assertTrue(delivered.isEmpty());
        assertEquals(0L, loader.select(A).generation());
        loader.refreshInitialized();
        assertEquals(0, executor.queue.size());
        assertEquals(1, executor.attempts);
    }

    @Test
    void aSupersededReplacementWrapperIsSkippedBeforeItsProbe() {
        readiness.complete(null);
        loader.select(A);
        executor.runNext();
        assertEquals(1, delivered.size());

        loader.refreshInitialized();
        loader.refreshInitialized(); // supersedes the first replacement
        assertEquals(2, executor.queue.size());
        executor.runNext(); // stale wrapper: skipped
        executor.runNext();

        assertEquals(2, probes.calls.get());
        assertEquals(2, delivered.size());
    }

    @Test
    void aDirectExecutorRunsHarmlessProbesAndAReentrantRecipientWithTheMonitorFree() {
        List<Boolean> held = new ArrayList<>();
        List<SessionFactsLoader.Result> got = new ArrayList<>();
        SessionFactsLoader[] self = new SessionFactsLoader[1];
        boolean[] reenter = {true};
        self[0] = new SessionFactsLoader(readiness, task -> {
            held.add(Thread.holdsLock(self[0]));
            task.run();
        }, id -> {
            held.add(Thread.holdsLock(self[0]));
            return probes.probe(id);
        }, r -> {
            held.add(Thread.holdsLock(self[0]));
            got.add(r);
            if (reenter[0]) {
                reenter[0] = false;
                self[0].select(r.id());
                self[0].refreshInitialized();
            }
        });
        readiness.complete(null);

        SessionFactsLoader.Result result = self[0].select(A);

        assertEquals(SessionPaneFacts.State.KNOWN, result.facts().state());
        assertEquals(2, got.size()); // the original answer, then the reentrant refresh's
        assertEquals(2, probes.calls.get());
        assertTrue(held.stream().noneMatch(h -> h));
    }

    @Test
    void synchronousRejectionNotifiesWithTheMonitorFreeAndAListenerFailureIsNotASecondOutcome() {
        readiness.complete(null);
        List<Boolean> held = new ArrayList<>();
        SessionFactsLoader[] self = new SessionFactsLoader[1];
        self[0] = new SessionFactsLoader(readiness, task -> {
            throw new RejectedExecutionException();
        }, probes, r -> {
            held.add(Thread.holdsLock(self[0]));
            throw new IllegalArgumentException("listener");
        });

        assertThrows(IllegalArgumentException.class, () -> self[0].select(A));

        assertEquals(List.of(false), held);
        assertEquals(0, probes.calls.get());
    }

    @Test
    void aListenerFailureOnTheReadinessFailurePathPropagatesFromTheSynchronousSelectOnly() {
        Ending.FAILURE.end(readiness);
        int[] calls = {0};
        SessionFactsLoader throwing = new SessionFactsLoader(readiness, executor, probes, r -> {
            calls[0]++;
            throw new IllegalArgumentException("listener");
        });

        assertThrows(IllegalArgumentException.class, () -> throwing.select(A));

        assertEquals(1, calls[0]);
    }
}
