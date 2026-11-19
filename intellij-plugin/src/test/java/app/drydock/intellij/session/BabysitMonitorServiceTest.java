package app.drydock.intellij.session;

import app.drydock.agent.api.AgentKind;
import app.drydock.app.ApplicationStateStore;
import app.drydock.babysit.BabysitDelta;
import app.drydock.babysit.BabysitInstructions;
import app.drydock.babysit.BabysitLocks;
import app.drydock.babysit.BabysitPairing;
import app.drydock.babysit.BabysitState;
import app.drydock.babysit.BabysitStateTransforms;
import app.drydock.babysit.BabysitWakePolicy;
import app.drydock.babysit.BabysitWakePolicy.Decision;
import app.drydock.babysit.BabysitWriteGuard.Verdict;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.RepositorySettings;
import app.drydock.domain.SessionWorkspace;
import app.drydock.git.GhCliService.PrInfo.PrLifecycle;
import app.drydock.git.PrMonitorRead.Check;
import app.drydock.git.PrMonitorRead.Mergeable;
import app.drydock.git.PrMonitorRead.PrSnapshot;
import app.drydock.git.PrMonitorRead.PrSnapshot.Snapshot;
import app.drydock.intellij.session.BabysitWaker.WakeOutcome;
import app.drydock.state.JsonApplicationStateRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BabysitMonitorService}'s tick loop against a scripted PR reader, a
 * scripted write guard and a recording waker, over a real state store.
 * Ticks are driven directly; one test runs the real scheduler.
 */
class BabysitMonitorServiceTest {

    private static final String HEAD = "0123456789abcdef0123456789abcdef01234567";
    private static final String NEXT = "89abcdef0123456789abcdef0123456789abcdef";

    @TempDir
    Path base;

    private final RepositoryId repositoryId = RepositoryId.newId();
    private final ManagedSessionId watched = ManagedSessionId.newId();
    private final ManagedSessionId babysitter = ManagedSessionId.newId();
    private final AtomicLong now = new AtomicLong(1_800_000_000_000L);
    private final Deque<PrSnapshot> reads = new ArrayDeque<>();
    private final AtomicInteger readCalls = new AtomicInteger();
    private final List<String> guardCalls = new CopyOnWriteArrayList<>();
    private final List<String> pointers = new CopyOnWriteArrayList<>();
    private final List<BabysitState> stateDuringWake = new CopyOnWriteArrayList<>();
    private final AtomicInteger published = new AtomicInteger();
    private final List<ManagedSessionId> stoppedAgents = new CopyOnWriteArrayList<>();

    private ApplicationStateStore store;
    private Verdict verdict = new Verdict.Allowed(HEAD);
    private Supplier<CompletableFuture<WakeOutcome>> wakeAnswer =
            () -> CompletableFuture.completedFuture(new WakeOutcome.Submitted());
    private BabysitMonitorService monitor;

    @BeforeEach
    void setUp() {
        store = ApplicationStateStore.forRepository(new JsonApplicationStateRepository(base.resolve("state.json")));
        Repository repository = new Repository(repositoryId, base, "repo", Instant.parse("2026-08-12T10:00:00Z"),
                Instant.parse("2026-08-12T10:00:00Z"), RepositorySettings.DEFAULT);
        store.update(state -> state.withRepositories(List.of(repository))
                .withSessions(List.of(row(watched, "watched"), row(babysitter, "babysit PR #42")))
                .withPairings(List.of(new BabysitPairing(babysitter, watched, repositoryId, 42,
                        BabysitState.WATCHING))));
        monitor = monitor(60_000, 60_000);
    }

    @AfterEach
    void tearDown() {
        monitor.dispose();
        store.flush();
    }

    private ManagedAgentSession row(ManagedSessionId id, String name) {
        return new ManagedAgentSession.Builder(id, repositoryId, name,
                new AgentBinding(AgentKind.CLAUDE, Optional.of("conv-" + name), Optional.empty()),
                SessionWorkspace.inRepository(base), Instant.parse("2026-11-18T10:00:00Z")).build();
    }

    private BabysitMonitorService monitor(long firstTickMs, long periodMs) {
        return new BabysitMonitorService(() -> store, base,
                (root, repository, prNumber) -> {
                    readCalls.incrementAndGet();
                    assertEquals(base, root);
                    assertEquals(42, prNumber);
                    PrSnapshot next = reads.size() > 1 ? reads.poll() : reads.peek();
                    return CompletableFuture.completedFuture(next);
                },
                root -> Optional.of("o/r"),
                (pairing, branch, knownBase) -> {
                    guardCalls.add(branch + "@" + knownBase);
                    return CompletableFuture.completedFuture(verdict);
                },
                (pairing, pointer) -> {
                    assertTrue(BabysitWakePolicy.isWakePointer(pointer), pointer);
                    pointers.add(pointer);
                    stateDuringWake.add(pairing().state());
                    return wakeAnswer.get();
                },
                published::incrementAndGet, stoppedAgents::add, now::get, firstTickMs, periodMs, 30_000, null);
    }

    private static Snapshot pr(PrLifecycle lifecycle, String head, Mergeable mergeable, Check... checks) {
        return new Snapshot(42, lifecycle, false, "main", "feat/x", head, mergeable, "CLEAN", List.of(checks));
    }

    private static Snapshot open(Mergeable mergeable, Check... checks) {
        return pr(PrLifecycle.OPEN, HEAD, mergeable, checks);
    }

    private void tickWith(PrSnapshot read) {
        reads.clear();
        reads.add(read);
        monitor.tick();
    }

    private BabysitPairing pairing() {
        return store.state().pairings().stream().filter(p -> p.babysitterId().equals(babysitter)).findFirst()
                .orElseThrow();
    }

    private String deltaText(String pointer) throws Exception {
        String path = pointer.substring(pointer.indexOf("read ") + 5, pointer.indexOf(" and act only"));
        return Files.readString(Path.of(path));
    }

    private List<Path> babysitFiles() throws Exception {
        Path dir = BabysitDelta.directory(base);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(file -> file.getFileName().toString().startsWith(watched.toString())).toList();
        }
    }

    @Test
    void theFirstObservationOfARestoredEngagementWakesNobodyAndRecordsTheHeadAndTheInstructions()
            throws Exception {
        monitor.restore();
        tickWith(open(Mergeable.CONFLICTING, new Check(Check.Kind.CHECK_RUN, "test", "COMPLETED", "FAILURE",
                Optional.empty(), Optional.empty())));

        assertEquals(List.of(), pointers);
        assertEquals(List.of(), guardCalls);
        assertEquals(Optional.of(HEAD), pairing().knownHead());
        assertEquals(BabysitState.WATCHING, pairing().state());
        Path instructions = BabysitInstructions.fileFor(base, watched, 42);
        assertEquals(BabysitInstructions.baseline(42, "feat/x"), Files.readString(instructions));

        // the same reading again: still nothing to say
        tickWith(open(Mergeable.CONFLICTING, new Check(Check.Kind.CHECK_RUN, "test", "COMPLETED", "FAILURE",
                Optional.empty(), Optional.empty())));
        assertEquals(List.of(), pointers);
    }

    @Test
    void aFreshEngagementWhosePrIsAlreadyConflictingAndRedWakesOnTheFirstObservation() throws Exception {
        // No restore() saw this pairing: it started while the monitor ran.
        tickWith(open(Mergeable.CONFLICTING, new Check(Check.Kind.CHECK_RUN, "test", "COMPLETED", "FAILURE",
                Optional.of("CI"), Optional.empty())));

        assertEquals(List.of("feat/x@" + HEAD), guardCalls);
        assertEquals(1, pointers.size());
        String delta = deltaText(pointers.get(0));
        assertTrue(delta.contains("- conflict appeared"), delta);
        assertTrue(delta.contains("`CI / test`"), delta);
        assertEquals(Optional.of(HEAD), pairing().knownHead());

        // delivered: the same reading again says nothing new
        tickWith(open(Mergeable.CONFLICTING, new Check(Check.Kind.CHECK_RUN, "test", "COMPLETED", "FAILURE",
                Optional.of("CI"), Optional.empty())));
        assertEquals(1, pointers.size());
    }

    @Test
    void aFreshEngagementWhosePrIsCleanWakesNobody() {
        tickWith(open(Mergeable.MERGEABLE, new Check(Check.Kind.CHECK_RUN, "test", "COMPLETED", "SUCCESS",
                Optional.of("CI"), Optional.empty())));
        tickWith(open(Mergeable.UNKNOWN));

        assertEquals(List.of(), pointers);
        assertEquals(List.of(), guardCalls);
    }

    @Test
    void aConflictAppearingWakesTheBabysitterWithAnActDeltaWhileMarkedActing() throws Exception {
        CompletableFuture<WakeOutcome> held = new CompletableFuture<>();
        wakeAnswer = () -> held;
        tickWith(open(Mergeable.MERGEABLE));
        CountDownLatch ticked = new CountDownLatch(1);
        Thread tick = Thread.ofVirtual().start(() -> {
            tickWith(open(Mergeable.CONFLICTING));
            ticked.countDown();
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (pointers.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }

        assertEquals(List.of("feat/x@" + HEAD), guardCalls);
        assertEquals(1, pointers.size());
        assertEquals(List.of(BabysitState.ACTING), stateDuringWake);
        String delta = deltaText(pointers.get(0));
        assertTrue(delta.contains("- conflict appeared"), delta);
        assertTrue(delta.contains("You MAY write"), delta);

        held.complete(new WakeOutcome.Submitted());
        assertTrue(ticked.await(5, TimeUnit.SECONDS));
        tick.join();
        assertEquals(BabysitState.WATCHING, pairing().state());

        // delivered: the event is consumed, an unchanged PR wakes nobody again
        wakeAnswer = () -> CompletableFuture.completedFuture(new WakeOutcome.Submitted());
        tickWith(open(Mergeable.CONFLICTING));
        assertEquals(1, pointers.size());
    }

    @Test
    void aBabysitterStillRunningHeadlesslyInTheDetachedWatcherIsNotWokenUntilItsRunLockIsFree() throws Exception {
        tickWith(open(Mergeable.MERGEABLE));
        try (BabysitLocks.Held held = BabysitLocks.tryLock(BabysitLocks.runLockFile(base, babysitter))
                .orElseThrow()) {
            tickWith(open(Mergeable.CONFLICTING));
            assertEquals(List.of(), pointers);
            assertEquals(BabysitState.WATCHING, pairing().state(), "no ACTING mark for a skipped wake");
        }
        tickWith(open(Mergeable.CONFLICTING));

        assertEquals(1, pointers.size());
        assertTrue(deltaText(pointers.get(0)).contains("- conflict appeared"));
    }

    @Test
    void aSkippedWakeRevertsActingAndTheEventIsRetriedNextTick() throws Exception {
        wakeAnswer = () -> CompletableFuture.completedFuture(new WakeOutcome.Skipped(Decision.SKIP_BUSY));
        tickWith(open(Mergeable.MERGEABLE));
        tickWith(open(Mergeable.CONFLICTING));

        assertEquals(List.of(BabysitState.ACTING), stateDuringWake);
        assertEquals(BabysitState.WATCHING, pairing().state());

        wakeAnswer = () -> CompletableFuture.completedFuture(new WakeOutcome.Submitted());
        tickWith(open(Mergeable.CONFLICTING));

        assertEquals(2, pointers.size());
        assertTrue(deltaText(pointers.get(1)).contains("- conflict appeared"));
        assertEquals(BabysitState.WATCHING, pairing().state());
    }

    @Test
    void anEscalationDuringTheWakeIsNotOverwrittenByTheRevert() {
        CompletableFuture<WakeOutcome> held = new CompletableFuture<>();
        wakeAnswer = () -> {
            store.update(state -> BabysitStateTransforms.withPairingState(state, babysitter, BabysitState.ESCALATED));
            held.complete(new WakeOutcome.Resumed(row(babysitter, "babysit PR #42")));
            return held;
        };
        tickWith(open(Mergeable.MERGEABLE));
        tickWith(open(Mergeable.CONFLICTING));

        assertEquals(1, pointers.size());
        assertEquals(BabysitState.ESCALATED, pairing().state());
    }

    @Test
    void aFailedReadBacksOffKeepsTheLastGoodSnapshotAndNeverEndsOrWakes() {
        tickWith(open(Mergeable.MERGEABLE));
        tickWith(new PrSnapshot.Failed("gh: HTTP 502"));
        assertEquals(2, readCalls.get());

        now.addAndGet(29_000);
        tickWith(new PrSnapshot.Failed("gh: HTTP 502"));
        assertEquals(2, readCalls.get(), "within the 30 s backoff nothing is read");

        now.addAndGet(1_000);
        tickWith(new PrSnapshot.Failed("gh: HTTP 502"));
        assertEquals(3, readCalls.get());

        now.addAndGet(59_000);
        tickWith(open(Mergeable.MERGEABLE));
        assertEquals(3, readCalls.get(), "the second failure doubled the backoff to 60 s");

        now.addAndGet(1_000);
        tickWith(open(Mergeable.MERGEABLE));
        assertEquals(4, readCalls.get());
        assertEquals(List.of(), pointers, "the good read after failures equals the last good one: no event");
        assertEquals(BabysitState.WATCHING, pairing().state());

        tickWith(open(Mergeable.CONFLICTING));
        assertEquals(1, pointers.size(), "a good read resets the backoff and diffs against the last good one");
    }

    @Test
    void aMergedPullRequestStopsTheEngagementAndCleansItsFiles() throws Exception {
        tickWith(open(Mergeable.MERGEABLE));
        wakeAnswer = () -> CompletableFuture.completedFuture(new WakeOutcome.Skipped(Decision.SKIP_BUSY));
        tickWith(open(Mergeable.CONFLICTING));
        assertFalse(babysitFiles().isEmpty());
        int publishedBefore = published.get();

        tickWith(pr(PrLifecycle.MERGED, NEXT, Mergeable.UNKNOWN));

        assertEquals(BabysitState.STOPPED, pairing().state());
        assertEquals(publishedBefore + 1, published.get());
        assertEquals(List.of(babysitter), stoppedAgents, "the babysitter's agent is stopped, its row kept");
        assertTrue(store.state().sessions().stream().anyMatch(row -> row.id().equals(babysitter)));
        assertEquals(List.of(), babysitFiles());
        int reads = readCalls.get();
        tickWith(open(Mergeable.CONFLICTING));
        assertEquals(reads, readCalls.get(), "a stopped engagement is never read again");
        assertEquals(1, pointers.size());
    }

    @Test
    void aClosedPullRequestAtTheFirstReadEndsTheEngagementToo() {
        tickWith(pr(PrLifecycle.CLOSED, HEAD, Mergeable.UNKNOWN));

        assertEquals(BabysitState.STOPPED, pairing().state());
        assertEquals(List.of(), pointers);
        assertEquals(List.of(babysitter), stoppedAgents);
    }

    @Test
    void aFailingAgentStopDoesNotKeepTheEngagementAlive() {
        monitor.dispose();
        monitor = new BabysitMonitorService(() -> store, base,
                (root, repository, prNumber) -> CompletableFuture.completedFuture(
                        pr(PrLifecycle.MERGED, HEAD, Mergeable.UNKNOWN)),
                root -> Optional.empty(),
                (pairing, branch, knownBase) -> CompletableFuture.completedFuture(verdict),
                (pairing, pointer) -> wakeAnswer.get(),
                published::incrementAndGet, id -> {
                    throw new IllegalStateException("no registry");
                }, now::get, 60_000, 60_000, 30_000, null);

        monitor.tick();

        assertEquals(BabysitState.STOPPED, pairing().state());
    }

    @Test
    void startEndsDanglingPairingsAndRevertsAStaleActingMark() {
        ManagedSessionId ghost = ManagedSessionId.newId();
        ManagedSessionId orphanedBabysitter = ManagedSessionId.newId();
        ManagedSessionId orphanedWatched = ManagedSessionId.newId();
        store.update(state -> state
                .withSessions(List.of(row(watched, "watched"), row(babysitter, "babysit PR #42"),
                        row(orphanedBabysitter, "babysit PR #7")))
                .withPairings(List.of(
                        new BabysitPairing(babysitter, watched, repositoryId, 42, BabysitState.ACTING),
                        new BabysitPairing(ghost, watched, repositoryId, 9, BabysitState.WATCHING),
                        new BabysitPairing(orphanedBabysitter, orphanedWatched, repositoryId, 7,
                                BabysitState.ESCALATED))));

        monitor.restore();

        List<BabysitState> states = store.state().pairings().stream().map(BabysitPairing::state).toList();
        assertEquals(List.of(BabysitState.WATCHING, BabysitState.STOPPED, BabysitState.STOPPED), states);
        assertEquals(3, published.get());
        assertEquals(List.of(ghost, orphanedBabysitter), stoppedAgents,
                "the babysitters of the ended engagements are stopped (a no-op for the one with no row)");
    }

    @Test
    void aTickSkipsAPairingWhoseBabysitterRowDoesNotExistYet() {
        // a start reserves the pairing before the babysitter row is persisted
        store.update(state -> state.withSessions(List.of(row(watched, "watched"))));

        tickWith(open(Mergeable.CONFLICTING));

        assertEquals(0, readCalls.get());
        assertEquals(BabysitState.WATCHING, pairing().state());
    }

    @Test
    void aBusyWatchedSessionHoldsTheEventUntilTheGuardAllows() throws Exception {
        tickWith(open(Mergeable.MERGEABLE));
        verdict = new Verdict.WatchedBusy();
        tickWith(open(Mergeable.CONFLICTING));
        tickWith(open(Mergeable.CONFLICTING));

        assertEquals(2, guardCalls.size());
        assertEquals(List.of(), pointers);
        assertEquals(BabysitState.WATCHING, pairing().state());

        verdict = new Verdict.Allowed(HEAD);
        tickWith(open(Mergeable.CONFLICTING));

        assertEquals(1, pointers.size());
        assertTrue(deltaText(pointers.get(0)).contains("You MAY write"));
    }

    @Test
    void aMovedHeadGetsOneReadOnlyDeltaAndTheWriteFollowsOnceTheHeadIsRead() throws Exception {
        tickWith(open(Mergeable.MERGEABLE));
        verdict = new Verdict.HeadMoved(NEXT, HEAD);
        tickWith(open(Mergeable.CONFLICTING));
        tickWith(open(Mergeable.CONFLICTING));

        assertEquals(1, pointers.size(), "one read-only delta per batch of events");
        assertEquals(List.of(BabysitState.WATCHING), stateDuringWake, "a read-only wake is not ACTING");
        assertTrue(deltaText(pointers.get(0)).contains("You may NOT write"));

        verdict = new Verdict.Allowed(NEXT);
        tickWith(pr(PrLifecycle.OPEN, NEXT, Mergeable.CONFLICTING));

        assertEquals(Optional.of(NEXT), pairing().knownHead());
        assertEquals("feat/x@" + NEXT, guardCalls.get(guardCalls.size() - 1));
        assertEquals(2, pointers.size());
        String delta = deltaText(pointers.get(1));
        assertTrue(delta.contains("You MAY write") && delta.contains("--force-with-lease=feat/x:" + NEXT), delta);
    }

    @Test
    void aGitFailureBacksOffExponentiallyAndKeepsTheEvent() {
        tickWith(open(Mergeable.MERGEABLE));
        verdict = new Verdict.GitFailed("fetch timed out");
        tickWith(open(Mergeable.CONFLICTING));
        tickWith(open(Mergeable.CONFLICTING));

        assertEquals(1, guardCalls.size(), "backed off: the second tick did not read or check");

        now.addAndGet(30_000);
        tickWith(open(Mergeable.CONFLICTING));
        assertEquals(2, guardCalls.size(), "retried after 30 s, and failed again");

        // The read between succeeded, yet the delivery streak was not reset: 60 s now.
        now.addAndGet(30_000);
        tickWith(open(Mergeable.CONFLICTING));
        assertEquals(2, guardCalls.size(), "30 s is inside the doubled 60 s window");

        verdict = new Verdict.Allowed(HEAD);
        now.addAndGet(30_000);
        tickWith(open(Mergeable.CONFLICTING));
        assertEquals(1, pointers.size());
    }

    @Test
    void aFailedWakeBacksOffExponentiallyAndADeliveredOneResetsTheStreak() {
        wakeAnswer = () -> CompletableFuture.completedFuture(new WakeOutcome.Failed(Optional.empty(), "pty gone"));
        tickWith(open(Mergeable.MERGEABLE));
        tickWith(open(Mergeable.CONFLICTING));
        assertEquals(1, pointers.size());

        now.addAndGet(30_000);
        tickWith(open(Mergeable.CONFLICTING));
        assertEquals(2, pointers.size());

        now.addAndGet(30_000);
        tickWith(open(Mergeable.CONFLICTING));
        assertEquals(2, pointers.size(), "the second failure doubled the wait to 60 s");

        wakeAnswer = () -> CompletableFuture.completedFuture(new WakeOutcome.Submitted());
        now.addAndGet(30_000);
        tickWith(open(Mergeable.CONFLICTING));
        assertEquals(3, pointers.size(), "delivered");

        wakeAnswer = () -> CompletableFuture.completedFuture(new WakeOutcome.Failed(Optional.empty(), "pty gone"));
        tickWith(open(Mergeable.MERGEABLE));
        tickWith(open(Mergeable.CONFLICTING));
        assertEquals(4, pointers.size());
        now.addAndGet(30_000);
        tickWith(open(Mergeable.CONFLICTING));
        assertEquals(5, pointers.size(), "the delivery reset the streak: back to 30 s");
    }

    @Test
    void heldWakesSkipOnlyTheHeldBabysittersUntilReleasedAndKeepTheEvent() throws Exception {
        monitor.holdWakes(id -> true);
        tickWith(open(Mergeable.MERGEABLE));
        tickWith(open(Mergeable.CONFLICTING));
        assertEquals(List.of(), pointers, "held: a detached watcher may still own this conversation");
        assertEquals(BabysitState.WATCHING, pairing().state(), "no ACTING mark for a held wake");

        monitor.holdWakes(id -> !id.equals(babysitter));
        tickWith(open(Mergeable.CONFLICTING));
        assertEquals(1, pointers.size(), "released for this babysitter: the kept event is delivered");
        assertTrue(deltaText(pointers.get(0)).contains("- conflict appeared"));
    }

    @Test
    void anUnsupportedReadPausesWithoutBackoffOrEnd() {
        tickWith(new PrSnapshot.Unsupported());
        tickWith(new PrSnapshot.Unsupported());

        assertEquals(2, readCalls.get());
        assertEquals(BabysitState.WATCHING, pairing().state());
        assertEquals(Optional.empty(), pairing().knownHead());
    }

    @Test
    void failingChecksWakeAndOnlyTheNewestDeltasAreKept() throws Exception {
        tickWith(open(Mergeable.MERGEABLE));
        for (int i = 0; i < 7; i++) {
            now.addAndGet(60_000);
            tickWith(open(Mergeable.MERGEABLE, new Check(Check.Kind.CHECK_RUN, "test-" + i, "COMPLETED",
                    "TIMED_OUT", Optional.of("CI"), Optional.empty())));
        }

        assertEquals(7, pointers.size());
        assertTrue(deltaText(pointers.get(6)).contains("- checks failed (1): `CI / test-6`."));
        long deltas = babysitFiles().stream().filter(file -> file.getFileName().toString().contains("-delta-"))
                .count();
        assertEquals(BabysitDelta.KEEP, deltas);
    }

    @Test
    void theSchedulerTicksUntilDisposedAndAThrowingTickDoesNotEndTheSeries() throws Exception {
        monitor.dispose();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch three = new CountDownLatch(3);
        monitor = new BabysitMonitorService(() -> store, base,
                (root, repository, prNumber) -> {
                    calls.incrementAndGet();
                    three.countDown();
                    throw new IllegalStateException("reader exploded");
                },
                root -> Optional.empty(),
                (pairing, branch, knownBase) -> CompletableFuture.completedFuture(verdict),
                (pairing, pointer) -> CompletableFuture.completedFuture(
                        new WakeOutcome.Skipped(Decision.SKIP_BUSY)),
                published::incrementAndGet, stoppedAgents::add, now::get, 1, 1, 30_000, null);

        monitor.start();
        monitor.start();

        assertTrue(three.await(5, TimeUnit.SECONDS));
        monitor.dispose();
        int afterDispose = calls.get();
        Thread.sleep(50);
        assertEquals(afterDispose, calls.get());
    }

    @Test
    void aMonitorDisposedBeforeStartNeverTicks() throws Exception {
        monitor.dispose();
        monitor.start();
        Thread.sleep(20);
        assertEquals(0, readCalls.get());
    }
}
