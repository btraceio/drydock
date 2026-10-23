package app.drydock.intellij.review;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.ReviewScope;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The Review tab's refresh policy (AC21, AC15's scheduling half) against
 * fakes only: a scheduler whose timers the test fires by hand (never real
 * time — scheduler tests sleep nowhere), a diff seam that counts calls and
 * in-flight runs, and an assembler wrapped around
 * {@code ReviewTabModel::snapshot} as the recompute-counting seam. The
 * executor is direct, so every path runs on the test thread and nothing
 * flakes.
 */
class ReviewTabRefresherTest {

    // ---- fixtures -----------------------------------------------------------

    private static ReviewScope scope(String id) {
        return new ReviewScope(id, ReviewScope.Kind.WORKING_TREE, Path.of("/repo"),
                Optional.of(Path.of("/repo")), "main", "HEAD", Optional.empty(),
                Optional.empty(), Optional.empty());
    }

    private static UnifiedDiff.FileDiff file(String path) {
        UnifiedDiff.Line added = new UnifiedDiff.Line(UnifiedDiff.Line.Kind.ADD,
                OptionalInt.empty(), OptionalInt.of(1), "x");
        return new UnifiedDiff.FileDiff(path, "M", 1, 1, false, false,
                List.of(new UnifiedDiff.Hunk("@@ -0,0 +1 @@", List.of(added))));
    }

    private static UnifiedDiff diff(String path) {
        return new UnifiedDiff(List.of(file(path)));
    }

    private final FakeScheduler scheduler = new FakeScheduler();
    private final FakeDiff diff = new FakeDiff();
    private final FakeBaseCommit git = new FakeBaseCommit();
    /** The base commit each assembled Inputs carried. */
    private final List<Optional<String>> joinedBases = new ArrayList<>();
    private final AtomicInteger assemblies = new AtomicInteger();
    private final List<ReviewTabModel.Snapshot> snapshots = new ArrayList<>();
    private final List<ReviewTabStatus> states = new ArrayList<>();
    private final List<String> texts = new ArrayList<>();

    private ReviewTabRefresher fresh() {
        return new ReviewTabRefresher(Runnable::run, scheduler, diff, git,
                (s, d, base) -> {
                    joinedBases.add(base);
                    return new ReviewTabModel.Inputs(d, List.of(), List.of(), base);
                },
                inputs -> {
                    assemblies.incrementAndGet();
                    return ReviewTabModel.snapshot(inputs);
                },
                snapshots::add,
                (state, text) -> {
                    states.add(state);
                    texts.add(text);
                });
    }

    /** Loads {@code path}'s diff fully: reload, debounce out, run done. */
    private void load(String path) {
        scheduler.fireNext();
        diff.completeOldest(diff(path));
    }

    // ---- AC21: the marks path -----------------------------------------------

    @Test
    void fiftyStoreEventsGiveOneMarkRecomputeAndNoGit() {
        ReviewTabRefresher refresher = fresh();
        refresher.reload(scope("s1"));
        load("a.txt");
        int afterLoad = assemblies.get();
        assertEquals(1, git.calls, "the reload resolves the base commit once");
        git.forbidden = true; // from here on, any git call fails the test

        for (int i = 0; i < 50; i++) {
            refresher.marksChanged();
        }
        assertEquals(1, scheduler.pending().size(),
                "50 events must coalesce to exactly one scheduled recompute");
        assertEquals(ReviewTabRefresher.MARKS_COALESCE_MS, scheduler.pending().get(0).delayMs);

        scheduler.fireNext();
        assertEquals(afterLoad + 1, assemblies.get(), "exactly one mark recompute");
        assertEquals(1, diff.calls, "the marks path runs no diff");
        assertEquals(1, git.calls, "the marks path resolves no base commit: zero git");
        assertEquals(afterLoad + 1, snapshots.size(), "the recompute emits one snapshot");
        assertEquals(Optional.of(FakeBaseCommit.COMMIT), joinedBases.get(joinedBases.size() - 1),
                "the recompute reuses the base commit cached at the reload");
    }

    @Test
    void eachReloadResolvesTheBaseCommitAfreshAndCachesIt() {
        ReviewTabRefresher refresher = fresh();
        refresher.reload(scope("s1"));
        load("a.txt");
        refresher.reload(scope("s1"));
        load("a2.txt");
        assertEquals(2, git.calls, "one base-commit resolution per reload");
        assertEquals(List.of(Optional.of(FakeBaseCommit.COMMIT), Optional.of(FakeBaseCommit.COMMIT)), joinedBases);
    }

    @Test
    void storeEventsBeforeTheFirstLoadDoNothing() {
        ReviewTabRefresher refresher = fresh();
        refresher.marksChanged();
        refresher.marksChanged();
        assertEquals(0, scheduler.pending().size(), "no cached diff yet: nothing to recompute");
        assertEquals(0, diff.calls);
        assertEquals(0, assemblies.get());
    }

    // ---- AC21/AC15: the reload path -----------------------------------------

    @Test
    void aReloadBurstDuringTheDebounceCollapsesToOneRun() {
        ReviewTabRefresher refresher = fresh();
        for (int i = 0; i < 5; i++) {
            refresher.reload(scope("s1"));
        }
        assertEquals(1, scheduler.pending().size(), "the burst collapses into one debounced run");
        assertEquals(0, diff.calls, "no run before the debounce fires");

        scheduler.fireNext();
        assertEquals(1, diff.calls);
        diff.completeOldest(diff("a.txt"));
        assertEquals(1, diff.calls);
        assertEquals(0, scheduler.pending().size(), "no follow-up was asked for");
    }

    @Test
    void aBurstDuringARunKeepsOneDiffInFlightAndSchedulesExactlyOneFollowUp() {
        ReviewTabRefresher refresher = fresh();
        refresher.reload(scope("s1"));
        scheduler.fireNext(); // run 1 in flight
        assertEquals(1, diff.calls);

        for (int i = 0; i < 5; i++) {
            refresher.reload(scope("s1")); // all arrive while run 1 is going
        }
        assertEquals(1, diff.calls, "a request during a run starts no second diff");
        assertEquals(0, scheduler.pending().size(),
                "the burst itself schedules nothing; it only marks one follow-up");

        diff.completeOldest(diff("a.txt"));
        assertEquals(1, diff.maxInFlight, "at most one diff in flight at any time");
        assertEquals(1, scheduler.pending().size(),
                "the run's completion schedules exactly one follow-up");

        scheduler.fireNext(); // the follow-up run
        assertEquals(2, diff.calls);
        diff.completeOldest(diff("a2.txt"));
        assertEquals(1, diff.maxInFlight, "still at most one diff in flight");
        assertEquals(0, scheduler.pending().size(), "no second follow-up");
        assertEquals(2, snapshots.size());
    }

    @Test
    void aScopeChangeDuringARunDiscardsThatRunAndReloadsTheNewScope() {
        ReviewTabRefresher refresher = fresh();
        refresher.reload(scope("a"));
        scheduler.fireNext(); // scope a's run in flight

        refresher.reload(scope("b"));
        diff.completeOldest(diff("a.txt")); // a's result arrives after b was asked for
        assertTrue(snapshots.isEmpty(), "a's result is not this tab's anymore");
        assertEquals(List.of(ReviewTabStatus.LOADING, ReviewTabStatus.LOADING), states);

        scheduler.fireNext(); // the follow-up runs the current scope
        assertEquals(2, diff.calls);
        assertEquals(scope("b"), diff.calledWith.get(1));
        diff.completeOldest(diff("b.txt"));
        assertEquals(List.of(ReviewTabStatus.LOADING, ReviewTabStatus.LOADING, ReviewTabStatus.READY),
                states.subList(0, 3));
        assertEquals(1, snapshots.size());
    }

    // ---- the loading placeholder --------------------------------------------

    @Test
    void theFirstUserLoadShowsLoadingBeforeAnyBackgroundWorkStarts() {
        ReviewTabRefresher refresher = fresh();
        refresher.reload(scope("s1"));
        assertEquals(List.of(ReviewTabStatus.LOADING), states,
                "LOADING is reported synchronously, before any background work");
        assertEquals(List.of("Loading review…"), texts);
        assertEquals(0, diff.calls, "no diff before the debounce fires");
        assertEquals(1, scheduler.pending().size());

        load("a.txt");
        assertEquals(ReviewTabStatus.READY, states.get(states.size() - 1),
                "the terminal state replaces the loading placeholder");
    }

    // ---- AC22 outcomes -------------------------------------------------------

    @Test
    void anEmptyDiffMapsToTheEmptyDiffState() {
        ReviewTabRefresher refresher = fresh();
        refresher.reload(scope("s1"));
        scheduler.fireNext();
        diff.completeOldest(new UnifiedDiff(List.of()));
        assertEquals(ReviewTabStatus.EMPTY_DIFF, states.get(states.size() - 1));
        assertEquals("No changes to review", texts.get(texts.size() - 1));
        assertEquals(1, snapshots.size(), "the empty snapshot still reaches the tree");
    }

    @Test
    void aFailedDiffMapsToTheFailedStateWithTheCauseMessage() {
        ReviewTabRefresher refresher = fresh();
        refresher.reload(scope("s1"));
        scheduler.fireNext();
        diff.failOldest(new CompletionException(new IllegalStateException("exit 128")));
        assertEquals(ReviewTabStatus.DIFF_FAILED, states.get(states.size() - 1));
        assertEquals("Could not compute the diff: exit 128", texts.get(texts.size() - 1));
        assertTrue(snapshots.isEmpty());
    }

    @Test
    void aFailedDiffIsLoggedAtWarningWithItsCause() {
        Logger logger = Logger.getLogger(ReviewTabRefresher.class.getName());
        List<LogRecord> records = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        try {
            ReviewTabRefresher refresher = fresh();
            refresher.reload(scope("s1"));
            scheduler.fireNext();
            IllegalStateException cause = new IllegalStateException("git diff failed (exit 128): fatal: bad revision");
            diff.failOldest(new CompletionException(cause));

            List<LogRecord> warnings = records.stream()
                    .filter(r -> r.getLevel().equals(Level.WARNING)).toList();
            assertEquals(1, warnings.size(), "the failed diff is logged once, at WARNING");
            assertTrue(warnings.get(0).getMessage().contains("fatal: bad revision"),
                    "the log line carries the stderr excerpt: " + warnings.get(0).getMessage());
            assertSame(cause, warnings.get(0).getThrown().getCause(), "the cause travels with the record");
        } finally {
            logger.removeHandler(handler);
        }
    }

    // ---- lifecycle ----------------------------------------------------------

    @Test
    void disposeUnregistersListenersCancelsScheduledWorkAndIgnoresLaterEvents() {
        ReviewTabRefresher refresher = fresh();
        List<String> unregistered = new ArrayList<>();
        refresher.onDispose(() -> unregistered.add("store"));
        refresher.onDispose(() -> unregistered.add("registry"));

        refresher.reload(scope("s1"));
        load("a.txt"); // a cached diff exists, so marks events schedule
        refresher.marksChanged();
        assertEquals(1, scheduler.pending().size());
        int beforeDispose = assemblies.get();
        assertEquals(1, beforeDispose, "only the load assembled a snapshot so far");

        refresher.dispose();
        assertEquals(List.of("store", "registry"), unregistered);
        assertTrue(scheduler.pending().isEmpty(), "the pending marks timer was cancelled");

        refresher.marksChanged();
        refresher.reload(scope("s1"));
        assertTrue(scheduler.pending().isEmpty(), "later events schedule nothing");
        assertEquals(1, diff.calls, "later events spawn no git");
        assertEquals(beforeDispose, assemblies.get(),
                "the cancelled marks recompute and the later events assemble nothing");
    }

    @Test
    void aRunStillInFlightWhenDisposedCompletesToNothing() {
        ReviewTabRefresher refresher = fresh();
        refresher.reload(scope("s1"));
        scheduler.fireNext(); // in flight

        refresher.dispose();
        diff.completeOldest(diff("a.txt"));
        assertTrue(snapshots.isEmpty(), "a disposed tab does not emit");
        assertEquals(List.of(ReviewTabStatus.LOADING), states,
                "the in-flight completion reports no state after dispose");
        assertEquals(0, scheduler.pending().size());
    }

    @Test
    void anUnregisterHandedOverAfterDisposeRunsAtOnce() {
        ReviewTabRefresher refresher = fresh();
        refresher.dispose();
        List<String> unregistered = new ArrayList<>();
        refresher.onDispose(() -> unregistered.add("late"));
        assertEquals(List.of("late"), unregistered);
    }

    // ---- fakes ---------------------------------------------------------------

    /** Timer seam whose tasks the test fires by hand; cancel marks a task dead. */
    private static final class FakeScheduler implements ReviewTabRefresher.Scheduler {
        private final List<Task> tasks = new ArrayList<>();

        @Override
        public ReviewTabRefresher.Cancelable schedule(Runnable task, long delayMs) {
            Task t = new Task(task, delayMs);
            tasks.add(t);
            return t;
        }

        List<Task> pending() {
            return tasks.stream().filter(t -> !t.cancelled).toList();
        }

        /** Fires the oldest pending task. */
        void fireNext() {
            Task next = pending().get(0);
            tasks.remove(next);
            next.runnable.run();
        }

        private static final class Task implements ReviewTabRefresher.Cancelable {
            private final Runnable runnable;
            private final long delayMs;
            private volatile boolean cancelled;

            private Task(Runnable runnable, long delayMs) {
                this.runnable = runnable;
                this.delayMs = delayMs;
            }

            @Override
            public void cancel() {
                cancelled = true;
            }
        }
    }

    /** Base-commit seam: counts git calls, and fails the test outright once forbidden. */
    private static final class FakeBaseCommit implements Function<ReviewScope, Optional<String>> {
        static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";
        private int calls;
        private boolean forbidden;

        @Override
        public Optional<String> apply(ReviewScope scope) {
            if (forbidden) {
                fail("git rev-parse spawned on a path that must spawn no git");
            }
            calls++;
            return Optional.of(COMMIT);
        }
    }

    /** Diff seam: counts calls, tracks in-flight runs and the scopes it was asked for. */
    private static final class FakeDiff implements Function<ReviewScope, CompletableFuture<UnifiedDiff>> {
        private int calls;
        private int inFlight;
        private int maxInFlight;
        private final ArrayDeque<CompletableFuture<UnifiedDiff>> open = new ArrayDeque<>();
        private final List<ReviewScope> calledWith = new ArrayList<>();

        @Override
        public CompletableFuture<UnifiedDiff> apply(ReviewScope scope) {
            calls++;
            calledWith.add(scope);
            inFlight++;
            maxInFlight = Math.max(maxInFlight, inFlight);
            CompletableFuture<UnifiedDiff> future = new CompletableFuture<>();
            open.addLast(future);
            return future;
        }

        void completeOldest(UnifiedDiff result) {
            inFlight--;
            open.pollFirst().complete(result);
        }

        void failOldest(Throwable error) {
            inFlight--;
            open.pollFirst().completeExceptionally(error);
        }
    }
}
