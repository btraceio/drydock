package app.drydock.app;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ApplicationState;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedAgentSession.SessionPurpose;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PrLink;
import app.drydock.domain.PrState;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.state.ApplicationStateRepository;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the marked-row (editor Q&A) contract of {@link ApplicationStateStore}:
 * the keyed compare-and-insert runs inside one serialized {@code update}, so
 * racing first asks for one canonical checkout produce exactly one row; a
 * duplicate marked row for one directory is a recovery error, never a guessed
 * binding; and {@link ApplicationStateStore#flushDurably} reports a failed
 * catalog save while {@link ApplicationStateStore#flush} stays a barrier.
 */
class ApplicationStateStoreEditorQaTest {

    private static final Path CHECKOUT = Path.of("/tmp/checkouts/daring-minty-hedgehog");

    // ---- racing first asks ---------------------------------------------------

    @Test
    void racingFirstAsksProduceOneRowAndEveryCallerResolvesIt() throws Exception {
        RecordingRepository repository = new RecordingRepository();
        ApplicationStateStore store = ApplicationStateStore.forRepository(repository);
        int askers = 8;

        ExecutorService pool = Executors.newFixedThreadPool(askers);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<ManagedAgentSession>> futures = new ArrayList<>();
            for (int t = 0; t < askers; t++) {
                // Each window mints its own candidate id; only one may survive.
                futures.add(pool.submit(() -> {
                    start.await();
                    return store.ensureMarkedSession(markedRow(ManagedSessionId.newId(), CHECKOUT));
                }));
            }
            start.countDown();
            ManagedAgentSession first = futures.getFirst().get(60, TimeUnit.SECONDS);
            for (Future<ManagedAgentSession> future : futures) {
                assertSame(first, future.get(60, TimeUnit.SECONDS),
                        "every racing ask must resolve the same logical row");
            }
        } finally {
            pool.shutdown();
        }

        store.flush();
        assertEquals(1, markedRows(store.state(), CHECKOUT).size(),
                "racing first asks must leave exactly one marked row for the checkout");
        assertEquals(1, markedRows(repository.state, CHECKOUT).size(),
                "the persisted catalog must carry exactly one marked row");
    }

    @Test
    void ensureReturnsTheExistingRowWithoutMintingAnother() {
        RecordingRepository repository = new RecordingRepository();
        ApplicationStateStore store = ApplicationStateStore.forRepository(repository);
        ManagedAgentSession reserved = store.ensureMarkedSession(markedRow(ManagedSessionId.newId(), CHECKOUT));
        store.flush();

        // A crash after the row-only reservation left a reusable row: a later
        // ask with a freshly minted candidate must reuse it, not insert.
        ManagedAgentSession resolved = store.ensureMarkedSession(markedRow(ManagedSessionId.newId(), CHECKOUT));

        assertSame(reserved, resolved);
        assertEquals(1, store.state().sessions().size());
        store.flush();
        assertEquals(1, repository.state.sessions().size(),
                "a reusable row must not grow the catalog");
    }

    @Test
    void ensureNeverReusesAnOrdinaryRowAtTheSameDirectory() {
        RecordingRepository repository = new RecordingRepository();
        ApplicationStateStore store = ApplicationStateStore.forRepository(repository);
        ManagedAgentSession ordinary = plainRow("human's session", CHECKOUT);
        store.update(state -> state.withSessions(List.of(ordinary)));

        ManagedAgentSession resolved = store.ensureMarkedSession(markedRow(ManagedSessionId.newId(), CHECKOUT));

        assertNotEquals(ordinary.id(), resolved.id(),
                "a Q&A row must not take over a human's session at the same path");
        assertEquals(2, store.state().sessions().size());
        store.flush();
        assertEquals(List.of(ordinary), ordinaryRows(repository.state, CHECKOUT),
                "the ordinary row stays untouched");
        assertEquals(1, markedRows(repository.state, CHECKOUT).size());
    }

    @Test
    void differentCheckoutsGetDifferentRowsEvenWithTheSameBasename() {
        RecordingRepository repository = new RecordingRepository();
        ApplicationStateStore store = ApplicationStateStore.forRepository(repository);
        Path left = Path.of("/tmp/worktrees/one/repo");
        Path right = Path.of("/tmp/worktrees/two/repo");

        store.ensureMarkedSession(markedRow(ManagedSessionId.newId(), left));
        store.ensureMarkedSession(markedRow(ManagedSessionId.newId(), right));

        assertEquals(2, store.state().sessions().size());
        assertEquals(1, markedRows(store.state(), left).size());
        assertEquals(1, markedRows(store.state(), right).size());
    }

    @Test
    void spellingsOfOneDirectoryNormalizeToTheSameKey() {
        RecordingRepository repository = new RecordingRepository();
        ApplicationStateStore store = ApplicationStateStore.forRepository(repository);

        store.ensureMarkedSession(markedRow(ManagedSessionId.newId(), CHECKOUT));
        // The lookup normalizes the queried directory without I/O: a
        // redundant-separator/dot spelling resolves to the same key. (A row
        // itself can never carry such a spelling -- SessionWorkspace already
        // rejects unnormalized working directories.)
        assertTrue(store.markedSession(CHECKOUT.resolve(".")).isPresent(),
                "a dot spelling of the checkout resolves to the same marked row");
        assertEquals(1, store.state().sessions().size());
    }

    // ---- duplicate detection ---------------------------------------------------

    @Test
    void duplicateMarkedRowsSurfaceAsARecoveryError() {
        // A state file damaged or edited outside the store holds two marked
        // rows for one directory: loaded as-is, the store must refuse to
        // guess a binding.
        ManagedAgentSession first = markedRow(ManagedSessionId.newId(), CHECKOUT);
        ManagedAgentSession second = markedRow(ManagedSessionId.newId(), CHECKOUT);
        RecordingRepository repository =
                new RecordingRepository(ApplicationState.empty().withSessions(List.of(first, second)));
        ApplicationStateStore store = ApplicationStateStore.forRepository(repository);

        ApplicationStateStore.DuplicateMarkedSessionException read =
                assertThrows(ApplicationStateStore.DuplicateMarkedSessionException.class,
                        () -> store.markedSession(CHECKOUT));
        assertTrue(read.getMessage().contains(CHECKOUT.toString()),
                "the recovery error names the ambiguous directory");
        assertTrue(read.getMessage().contains(first.id().value().toString())
                        && read.getMessage().contains(second.id().value().toString()),
                "the recovery error names the ambiguous rows so they can be triaged");

        assertThrows(ApplicationStateStore.DuplicateMarkedSessionException.class,
                () -> store.ensureMarkedSession(markedRow(ManagedSessionId.newId(), CHECKOUT)),
                "no third row may be minted on top of an ambiguous index");
        assertEquals(2, store.state().sessions().size(),
                "the refusal must not mutate the catalog");
    }

    @Test
    void markedSessionIsEmptyForAnUnknownDirectoryAndRefusesAnUnmarkedCandidate() {
        RecordingRepository repository = new RecordingRepository();
        ApplicationStateStore store = ApplicationStateStore.forRepository(repository);

        assertTrue(store.markedSession(CHECKOUT).isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> store.ensureMarkedSession(plainRow("ordinary", CHECKOUT)),
                "the reserve path only accepts a marked candidate");
    }

    // ---- flushDurably reports a failed catalog save ------------------------------

    @Test
    void flushDurablyReportsAFailedSaveAndFlushStaysABarrier() {
        FailingRepository repository = new FailingRepository();
        ApplicationStateStore store = ApplicationStateStore.forRepository(repository);

        store.update(state -> state.withSessions(List.of(markedRow(ManagedSessionId.newId(), CHECKOUT))));

        assertFalse(store.flushDurably(),
                "a failed catalog save must be reported, never swallowed as success");
        // Both the coalesced background save from update() and the durable
        // write itself attempted (the writer is FIFO, so the durable task
        // runs after the background one) -- and both failed.
        assertEquals(2, repository.saveAttempts.get(),
                "the durable write actually attempted the save");
        assertEquals(CHECKOUT.toString(), repository.lastState.sessions().getFirst().workingDirectory().toString(),
                "the durable write attempted the latest state");

        // flush() remains a barrier even when the save fails: it must not
        // throw the failure back at a shutdown path, and adds no new save.
        store.flush();
        assertEquals(2, repository.saveAttempts.get());
    }

    @Test
    void flushDurablyReturnsTrueOnceTheStateIsOnDisk() throws Exception {
        RecordingRepository repository = new RecordingRepository();
        ApplicationStateStore store = ApplicationStateStore.forRepository(repository);

        store.update(state -> state.withSessions(List.of(markedRow(ManagedSessionId.newId(), CHECKOUT))));

        assertTrue(store.flushDurably(),
                "the state as it stands at this call is persisted");
        assertEquals(store.state(), repository.state);
    }

    @Test
    void flushDurablySurvivesASaveBlockedBeyondItsTimeout() throws Exception {
        // A save that never finishes must make flushDurably answer false
        // after its bound, not hang the caller (the coordinator waits on
        // this before the first partition record).
        GateRepository gated = new GateRepository();
        ApplicationStateStore store = ApplicationStateStore.forRepository(gated);

        store.update(state -> state.withSessions(List.of(markedRow(ManagedSessionId.newId(), CHECKOUT))));
        assertTrue(gated.saveEntered.await(5, TimeUnit.SECONDS), "the writer reached the save");

        assertFalse(store.flushDurably(), "a save that does not finish in time is not durable");

        gated.release.countDown();
        store.flush();
    }

    // ---- fixtures -------------------------------------------------------------

    /** The row the coordinator reserves: READ_ONLY, checkout-keyed, marked. */
    private static ManagedAgentSession markedRow(ManagedSessionId id, Path workingDirectory) {
        Instant now = Instant.EPOCH;
        return new ManagedAgentSession(
                id, RepositoryId.newId(), "Drydock Q&A — repo",
                new AgentBinding(AgentKind.CLAUDE, Optional.empty(), Optional.empty(), SessionMode.READ_ONLY),
                new SessionWorkspace(workingDirectory, Optional.empty(), true),
                SessionStatus.INACTIVE, now, now, Optional.empty(),
                PrLink.of(PrState.NONE, Optional.empty()), false, Optional.empty())
                .toBuilder().purpose(SessionPurpose.EDITOR_QA).build();
    }

    private static ManagedAgentSession plainRow(String displayName, Path workingDirectory) {
        Instant now = Instant.EPOCH;
        return new ManagedAgentSession(
                ManagedSessionId.newId(), RepositoryId.newId(), displayName,
                new AgentBinding(AgentKind.CLAUDE, Optional.empty(), Optional.empty()),
                new SessionWorkspace(workingDirectory, Optional.empty(), true),
                SessionStatus.INACTIVE, now, now, Optional.empty(),
                PrLink.of(PrState.NONE, Optional.empty()), false, Optional.empty());
    }

    private static List<ManagedAgentSession> markedRows(ApplicationState state, Path directory) {
        return state.sessions().stream()
                .filter(s -> s.purpose() != SessionPurpose.ORDINARY
                        && s.workingDirectory().toAbsolutePath().normalize()
                                .equals(directory.toAbsolutePath().normalize()))
                .toList();
    }

    private static List<ManagedAgentSession> ordinaryRows(ApplicationState state, Path directory) {
        return state.sessions().stream()
                .filter(s -> s.purpose() == SessionPurpose.ORDINARY
                        && s.workingDirectory().toAbsolutePath().normalize()
                                .equals(directory.toAbsolutePath().normalize()))
                .toList();
    }

    /** In-memory repository recording every save; optionally preloads a state. */
    private static final class RecordingRepository implements ApplicationStateRepository {
        volatile ApplicationState state;
        final List<ApplicationState> saves = new CopyOnWriteArrayList<>();

        RecordingRepository() {
            this(ApplicationState.empty());
        }

        RecordingRepository(ApplicationState seeded) {
            this.state = seeded;
        }

        @Override
        public ApplicationState load() {
            return state;
        }

        @Override
        public void save(ApplicationState newState) {
            state = newState;
            saves.add(newState);
        }
    }

    /** A repository whose catalog save always fails (injected disk error). */
    private static final class FailingRepository implements ApplicationStateRepository {
        final AtomicInteger saveAttempts = new AtomicInteger();
        volatile ApplicationState lastState;

        @Override
        public ApplicationState load() {
            return ApplicationState.empty();
        }

        @Override
        public void save(ApplicationState newState) {
            saveAttempts.incrementAndGet();
            lastState = newState;
            throw new IllegalStateException("injected disk failure");
        }
    }

    /** A repository whose first save blocks until released, signalling on entry. */
    private static final class GateRepository implements ApplicationStateRepository {
        final CountDownLatch saveEntered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public ApplicationState load() {
            return ApplicationState.empty();
        }

        @Override
        public void save(ApplicationState newState) {
            saveEntered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
