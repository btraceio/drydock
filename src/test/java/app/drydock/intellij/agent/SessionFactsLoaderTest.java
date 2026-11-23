package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Generations, caching and failure handling of the off-EDT facts loader, with a hand-driven executor. */
class SessionFactsLoaderTest {

    private static final ManagedSessionId A = ManagedSessionId.newId();
    private static final ManagedSessionId B = ManagedSessionId.newId();

    /** Runs queued loads only when the test says so, so completion order is the test's choice. */
    private static final class Manual implements Executor {
        final Queue<Runnable> queue = new ArrayDeque<>();

        @Override
        public void execute(Runnable task) {
            queue.add(task);
        }

        void runNext() {
            queue.remove().run();
        }
    }

    /** Counts every probe, standing in for the transcript scan and its siblings. */
    private static final class Spy implements SessionFactsLoader.Probes {
        final AtomicInteger calls = new AtomicInteger();
        boolean present = true;
        OptionalLong tokens = OptionalLong.of(4000);
        Throwable failure;
        /** Per-session answers, so results of different sessions are distinguishable; absent ids use {@link #tokens}. */
        final Map<ManagedSessionId, OptionalLong> tokensById = new HashMap<>();
        /** Runs once, inside the next probe after it started, to drive a newer request while this one is running. */
        Runnable duringProbe;

        @Override
        public Optional<SessionPaneFacts.Inputs> probe(ManagedSessionId id) throws Exception {
            calls.incrementAndGet();
            OptionalLong tokens = tokensById.getOrDefault(id, this.tokens); // this probe's own answer, whatever the hook changes
            if (duringProbe != null) {
                Runnable hook = duringProbe;
                duringProbe = null;
                hook.run();
            }
            if (failure instanceof Exception e) {
                throw e;
            }
            if (failure instanceof Error e) {
                throw e;
            }
            if (!present) {
                return Optional.empty();
            }
            return Optional.of(new SessionPaneFacts.Inputs(Optional.of(Path.of("/w/a")), Path.of("/w/a"), true, false,
                    tokens, true, Optional.empty(), Optional.empty(), "a", true, true));
        }
    }

    private final Manual executor = new Manual();
    private final Spy spy = new Spy();
    private final List<SessionFactsLoader.Result> delivered = new ArrayList<>();
    private final SessionFactsLoader loader = new SessionFactsLoader(executor, spy, delivered::add);

    @Test
    void firstSelectionIsLoadingThenDeliversKnownFacts() {
        SessionFactsLoader.Result first = loader.select(A);

        assertEquals(SessionPaneFacts.State.LOADING, first.facts().state());
        assertEquals(0, spy.calls.get(), "nothing probes on the caller's thread");
        executor.runNext();

        assertEquals(1, delivered.size());
        assertEquals(SessionPaneFacts.State.KNOWN, delivered.get(0).facts().state());
        assertEquals(Optional.of(4000), delivered.get(0).facts().resumeTokens());
    }

    @Test
    void repeatSelectionReadsTheCacheWithoutAnyProbe() {
        loader.select(A);
        executor.runNext();
        int afterLoad = spy.calls.get();

        for (int i = 0; i < 5; i++) {
            assertEquals(SessionPaneFacts.State.KNOWN, loader.select(A).facts().state());
        }

        assertEquals(afterLoad, spy.calls.get());
        assertTrue(executor.queue.isEmpty(), "no load was scheduled");
        assertEquals(1, delivered.size());
    }

    @Test
    void selectingWhileALoadIsInFlightDoesNotStartAnother() {
        loader.select(A);
        loader.select(A);

        assertEquals(1, executor.queue.size());
    }

    @Test
    void sessionMissingFromTheCatalogIsUnknownUntilItWasOnceKnown() {
        spy.present = false;
        loader.select(B);
        executor.runNext();
        assertEquals(SessionPaneFacts.State.UNKNOWN, delivered.get(0).facts().state());

        assertEquals(SessionPaneFacts.State.UNKNOWN, loader.select(B).facts().state(), "unknown is cached, not re-probed");
        assertEquals(1, spy.calls.get());

        spy.present = true;
        loader.select(A);
        executor.runNext();
        spy.present = false;
        loader.refreshInitialized();
        executor.runNext();
        executor.runNext();

        assertEquals(SessionPaneFacts.State.DELETED, loader.select(A).facts().state(), "known, then missing");
        assertEquals(SessionPaneFacts.State.UNKNOWN, loader.select(B).facts().state(), "never known stays unknown");
    }

    @Test
    void refreshReloadsOnlyInitializedSessions() {
        loader.select(A);
        executor.runNext();
        loader.select(B); // still loading, not initialized

        loader.refreshInitialized();

        assertEquals(2, executor.queue.size(), "B's pending load plus A's refresh, nothing else");
        assertEquals(SessionPaneFacts.State.KNOWN, loader.select(A).facts().state(), "old answer stays readable");
    }

    @Test
    void disposedLoaderDropsLateResultsAndSchedulesNothing() {
        loader.select(A);
        loader.dispose();
        executor.runNext();

        assertTrue(delivered.isEmpty());
        assertEquals(0, spy.calls.get(), "the queued load was skipped before probing");
        assertEquals(0L, loader.select(B).generation());
        assertTrue(executor.queue.isEmpty());
        loader.refreshInitialized();
        assertTrue(executor.queue.isEmpty(), "refresh after dispose schedules nothing");
    }

    @Test
    void olderGenerationFinishingLateCannotOverwriteANewerAnswer() {
        loader.select(A);
        executor.runNext();
        spy.tokens = OptionalLong.of(1000);
        loader.refreshInitialized();
        spy.tokens = OptionalLong.of(2000);
        loader.refreshInitialized();
        assertEquals(2, executor.queue.size());

        // Both were requested before either ran; the newer one is the answer.
        executor.runNext();
        assertEquals(1, spy.calls.get(), "the queued superseded request skipped its probe");
        executor.runNext();
        assertEquals(2, spy.calls.get());

        assertEquals(2, delivered.size(), "the superseded load delivered nothing");
        assertEquals(Optional.of(2000), delivered.get(1).facts().resumeTokens());
        assertEquals(Optional.of(2000), loader.select(A).facts().resumeTokens());
    }

    @Test
    void anAlreadyRunningOlderProbeFinishingAfterANewerSuccessChangesNothing() {
        loader.select(A);
        executor.runNext();
        loader.refreshInitialized();
        spy.tokens = OptionalLong.of(1000);
        // The older probe has passed its validity check when the newer request is installed and finishes first.
        spy.duringProbe = () -> {
            spy.tokens = OptionalLong.of(2000);
            loader.refreshInitialized();
            executor.runNext();
        };

        executor.runNext(); // older probe finishes last, successfully, with 1000

        assertEquals(3, spy.calls.get());
        assertEquals(2, delivered.size(), "the late success notified nobody");
        SessionFactsLoader.Result newer = delivered.get(1);
        assertEquals(Optional.of(2000), newer.facts().resumeTokens());
        assertEquals(A, newer.id());
        assertTrue(newer.generation() > delivered.get(0).generation());
        assertEquals(newer, loader.select(A), "cache still holds the newer answer");
        assertTrue(executor.queue.isEmpty());
    }

    @Test
    void anAlreadyRunningOlderProbeFailingAfterANewerSuccessKeepsTheNewerAnswer() {
        loader.select(A);
        executor.runNext();
        loader.refreshInitialized();
        spy.duringProbe = () -> {
            spy.tokens = OptionalLong.of(2000);
            loader.refreshInitialized();
            executor.runNext();
            spy.failure = new IllegalStateException("late");
        };

        executor.runNext(); // older probe throws after the newer one succeeded

        assertEquals(2, delivered.size(), "the late failure notified nobody");
        assertEquals(SessionPaneFacts.State.KNOWN, delivered.get(1).facts().state());
        assertEquals(A, delivered.get(1).id());
        assertEquals(delivered.get(1), loader.select(A), "the failure did not remove the newer answer");
        assertTrue(executor.queue.isEmpty(), "nothing retried");
    }

    @Test
    void anAlreadyRunningProbeOfOneSessionNeverLeaksIntoAnotherSessionsAnswer() {
        spy.tokensById.put(A, OptionalLong.of(100));
        spy.tokensById.put(B, OptionalLong.of(200));
        loader.select(A);
        loader.select(B);
        executor.runNext();
        executor.runNext();
        delivered.clear();
        long oldGenerationA = loader.select(A).generation();
        long oldGenerationB = loader.select(B).generation();
        loader.refreshInitialized(); // one request per session; whichever runs first is the stale running one
        // Inside that probe both sessions are refreshed again with newer facts, and all newer work completes.
        spy.duringProbe = () -> {
            spy.tokensById.put(A, OptionalLong.of(101));
            spy.tokensById.put(B, OptionalLong.of(201));
            loader.refreshInitialized();
            while (!executor.queue.isEmpty()) {
                executor.runNext();
            }
        };

        executor.runNext(); // stale running probe finishes last, still carrying the old 100/200 answer

        assertEquals(2 + 1 + 2, spy.calls.get(), "initial two, the stale running one, the two newer; the queued stale one skipped");
        assertEquals(2, delivered.size(), "only the newer generations were delivered");
        for (SessionFactsLoader.Result r : delivered) {
            assertEquals(Optional.of(r.id().equals(A) ? 101 : 201), r.facts().resumeTokens(), "no stale or foreign payload");
        }
        assertEquals(A, loader.select(A).id());
        assertEquals(Optional.of(101), loader.select(A).facts().resumeTokens());
        assertEquals(B, loader.select(B).id());
        assertEquals(Optional.of(201), loader.select(B).facts().resumeTokens());
        for (SessionFactsLoader.Result r : delivered) {
            long old = r.id().equals(A) ? oldGenerationA : oldGenerationB;
            assertTrue(r.generation() > old, "each delivery is a newer generation than its own session's old answer");
        }
        assertEquals(loader.select(A).generation(), delivered.stream().filter(r -> r.id().equals(A)).findFirst().orElseThrow().generation());
        assertEquals(loader.select(B).generation(), delivered.stream().filter(r -> r.id().equals(B)).findFirst().orElseThrow().generation());
        assertEquals(1, delivered.stream().filter(r -> r.id().equals(A)).count());
        assertEquals(1, delivered.stream().filter(r -> r.id().equals(B)).count());
        assertEquals(delivered.stream().filter(r -> r.id().equals(A)).findFirst().orElseThrow(), loader.select(A));
        assertEquals(delivered.stream().filter(r -> r.id().equals(B)).findFirst().orElseThrow(), loader.select(B));
        assertTrue(executor.queue.isEmpty());
    }

    @Test
    void failedRefreshRemovesTheOldAnswerOnlyOnceItsGenerationIsAccepted() {
        loader.select(A);
        executor.runNext();
        SessionFactsLoader.Result old = delivered.get(0);
        spy.failure = new IllegalStateException("refresh broke");
        loader.refreshInitialized();
        assertEquals(old, loader.select(A), "the old answer is readable until the replacement settles");

        executor.runNext();

        assertEquals(2, delivered.size());
        assertEquals(SessionPaneFacts.State.FAILED, delivered.get(1).facts().state());
        assertEquals(A, delivered.get(1).id());
        assertTrue(delivered.get(1).generation() > old.generation());
        spy.failure = null;
        assertEquals(SessionPaneFacts.State.LOADING, loader.select(A).facts().state(), "the failed refresh dropped the old answer");
    }

    @Test
    void failedLoadIsReportedNotCachedAndRetriedOnNextSelection() {
        spy.failure = new IllegalStateException("boom");
        SessionFactsLoader.Result loading = loader.select(A);
        executor.runNext();

        assertEquals(1, delivered.size());
        assertEquals(SessionPaneFacts.State.FAILED, delivered.get(0).facts().state());
        assertEquals(loading.generation(), delivered.get(0).generation());
        assertEquals(A, delivered.get(0).id());
        assertTrue(delivered.get(0).facts().message().endsWith(": boom"));

        spy.failure = null;
        SessionFactsLoader.Result retry = loader.select(A);
        assertEquals(SessionPaneFacts.State.LOADING, retry.facts().state(), "failure was not cached");
        assertTrue(retry.generation() > loading.generation());
        executor.runNext();
        assertEquals(2, spy.calls.get());
        assertEquals(SessionPaneFacts.State.KNOWN, delivered.get(1).facts().state());
    }

    @Test
    void linkageErrorAndBlankMessageAreFailuresWithANonEmptyMessage() {
        spy.failure = new NoClassDefFoundError();
        loader.select(A);
        executor.runNext();
        spy.failure = new Exception("");
        loader.select(B);
        executor.runNext();

        assertEquals(2, delivered.size());
        for (SessionFactsLoader.Result r : delivered) {
            assertEquals(SessionPaneFacts.State.FAILED, r.facts().state());
        }
        assertTrue(delivered.get(0).facts().message().endsWith(": " + NoClassDefFoundError.class.getName()));
        assertTrue(delivered.get(1).facts().message().endsWith(": " + Exception.class.getName()));
    }

    @Test
    void rejectedSchedulingIsAFailureThatClearsStateSoARetryCanRun() {
        List<SessionFactsLoader.Result> got = new ArrayList<>();
        boolean[] refuse = {true};
        Executor refusing = task -> {
            if (refuse[0]) {
                throw new RejectedExecutionException("shut down");
            }
            executor.execute(task);
        };
        SessionFactsLoader rejecting = new SessionFactsLoader(refusing, spy, got::add);

        SessionFactsLoader.Result first = rejecting.select(A);

        assertEquals(SessionPaneFacts.State.FAILED, first.facts().state());
        assertEquals(0, spy.calls.get(), "no probe ran");
        assertEquals(1, got.size());
        assertEquals(first, got.get(0), "returned and notified: same ID and generation, once");

        refuse[0] = false;
        SessionFactsLoader.Result retry = rejecting.select(A);
        assertEquals(SessionPaneFacts.State.LOADING, retry.facts().state());
        assertTrue(retry.generation() > first.generation());
        executor.runNext();
        assertEquals(1, spy.calls.get());
        assertEquals(2, got.size());
    }

    @Test
    void listenerExceptionFromASynchronousExecutorIsNotASecondOutcome() {
        List<SessionFactsLoader.Result> got = new ArrayList<>();
        SessionFactsLoader throwing = new SessionFactsLoader(Runnable::run, spy, r -> {
            got.add(r);
            throw new IllegalArgumentException("listener");
        });

        try {
            throwing.select(A);
        } catch (IllegalArgumentException expected) {
            // propagated, not converted into a FAILED outcome
        }

        assertEquals(1, got.size());
        assertEquals(SessionPaneFacts.State.KNOWN, got.get(0).facts().state());
    }

    @Test
    void listenerRunsWithTheMonitorFreeAndMayReenterWithoutDoubleNotification() {
        List<SessionFactsLoader.Result> got = new ArrayList<>();
        List<Boolean> held = new ArrayList<>();
        SessionFactsLoader[] self = new SessionFactsLoader[1];
        boolean[] reenter = {true};
        self[0] = new SessionFactsLoader(task -> {
            throw new RejectedExecutionException();
        }, spy, r -> {
            held.add(Thread.holdsLock(self[0]));
            got.add(r);
            if (reenter[0]) {
                reenter[0] = false;
                self[0].select(B);
            }
        });

        SessionFactsLoader.Result failed = self[0].select(A);
        assertEquals(SessionPaneFacts.State.FAILED, failed.facts().state());
        assertEquals(List.of(A, B), got.stream().map(SessionFactsLoader.Result::id).toList());
        assertEquals(List.of(false, false), held);

        // queued completion: listener disposes while notified
        got.clear();
        held.clear();
        SessionFactsLoader[] q = new SessionFactsLoader[1];
        q[0] = new SessionFactsLoader(executor, spy, r -> {
            held.add(Thread.holdsLock(q[0]));
            got.add(r);
            q[0].select(r.id());
            q[0].dispose();
        });
        q[0].select(A);
        executor.runNext();
        assertEquals(1, got.size());
        assertEquals(List.of(false), held);
    }
}
