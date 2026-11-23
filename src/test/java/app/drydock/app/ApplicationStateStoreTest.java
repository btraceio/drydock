package app.drydock.app;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ApplicationState;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PrLink;
import app.drydock.domain.PrState;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.state.ApplicationStateRepository;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the single-writer contract of {@link ApplicationStateStore}: every
 * concurrent {@code update} transform lands (none is lost), bursts of
 * updates coalesce into fewer {@code save()} calls, {@link
 * ApplicationStateStore#flush} is a real barrier, and a throwing transform
 * leaves the state -- and the save schedule -- untouched.
 *
 * <p>All tests run against a fake in-memory {@link
 * ApplicationStateRepository} that records every save; its {@code save()}
 * can block on a gate so coalescing is observed deterministically (no
 * sleeps racing the writer thread).</p>
 */
class ApplicationStateStoreTest {

    // ---- single-writer serialization --------------------------------------

    @Test
    void concurrentUpdatesNeverLoseOne() throws Exception {
        RecordingRepository repository = new RecordingRepository();
        ApplicationStateStore store = ApplicationStateStore.forRepository(repository);
        int threads = 8;
        int updatesPerThread = 25;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Void>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                String name = "thread-" + t + "-";
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < updatesPerThread; i++) {
                        String sessionName = name + i;
                        store.update(state -> state.withSessions(
                                append(state.sessions(), sessionNamed(sessionName))));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<Void> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
        }

        store.flush();
        assertEquals(threads * updatesPerThread, store.state().sessions().size(),
                "every concurrent update transform must land in the authoritative state");
        assertEquals(threads * updatesPerThread, repository.state.sessions().size(),
                "the last persisted state must carry every update");
    }

    // ---- coalesced save ----------------------------------------------------

    @Test
    void burstOfUpdatesWhileSaveIsInFlightCoalescesIntoFewerSaves() throws Exception {
        RecordingRepository repository = new RecordingRepository(new CountDownLatch(1), new CountDownLatch(1));
        ApplicationStateStore store = ApplicationStateStore.forRepository(repository);

        store.update(state -> state.withSessions(List.of(sessionNamed("s0"))));
        // Block the writer inside the first save() so the burst below is
        // fully coalesced deterministically rather than by timing luck.
        assertTrue(repository.firstSaveEntered.await(5, TimeUnit.SECONDS),
                "the writer thread must reach the first save");

        for (int i = 1; i < 11; i++) {
            String sessionName = "s" + i;
            store.update(state -> state.withSessions(
                    append(state.sessions(), sessionNamed(sessionName))));
        }

        repository.releaseFirstSave.countDown();
        store.flush();

        assertEquals(1, repository.saves.get(0).sessions().size(),
                "the blocked first save snapshots the pre-burst state");
        assertEquals(2, repository.saves.size(),
                "ten updates queued while one save was in flight must coalesce into one save");
        assertEquals(11, repository.saves.get(1).sessions().size(),
                "the coalesced save persists the latest state, not a stale one");
    }

    @Test
    void updateReturningEqualStateSchedulesNoSave() {
        RecordingRepository repository = new RecordingRepository();
        ApplicationStateStore store = ApplicationStateStore.forRepository(repository);

        store.update(state -> state);

        store.flush();
        assertEquals(List.of(), repository.saves,
                "an equal transform must not schedule any save");
    }

    // ---- flush barrier -----------------------------------------------------

    @Test
    void flushReturnsOnlyAfterThePendingSaveCompleted() {
        RecordingRepository repository = new RecordingRepository();
        ApplicationStateStore store = ApplicationStateStore.forRepository(repository);

        ApplicationState updated = store.update(state -> state.withSessions(
                List.of(sessionNamed("only"))));

        store.flush();

        assertEquals(updated, repository.state,
                "flush() must be a barrier: the pending save is done, no sleeps");
        assertEquals(List.of(updated), repository.saves);
    }

    // ---- transform failure -------------------------------------------------

    @Test
    void failedTransformLeavesStateUnchangedAndSchedulesNothing() {
        RecordingRepository repository = new RecordingRepository();
        ApplicationStateStore store = ApplicationStateStore.forRepository(repository);

        store.update(state -> state.withSessions(List.of(sessionNamed("before"))));
        store.flush();
        assertEquals(1, repository.saves.size());
        ApplicationState before = store.state();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> store.update(state -> {
                    throw new IllegalStateException("boom");
                }));
        assertEquals("boom", failure.getMessage());

        assertEquals(before, store.state(),
                "a throwing transform must not change the authoritative state");
        store.flush();
        assertEquals(1, repository.saves.size(),
                "a throwing transform must not schedule a save");
        assertEquals(before, repository.state);
    }

    // ---- shared-store registry ----------------------------------------------

    @Test
    void forRepositoryHandsOutOneStorePerRepository() {
        RecordingRepository first = new RecordingRepository();
        RecordingRepository second = new RecordingRepository();
        assertSame(ApplicationStateStore.forRepository(first), ApplicationStateStore.forRepository(first),
                "one repository means one shared store (the single writer)");
        assertNotSame(ApplicationStateStore.forRepository(first), ApplicationStateStore.forRepository(second));
    }

    // ---- fixtures -----------------------------------------------------------

    /** Copies {@code sessions} plus one new element; the record's lists stay immutable. */
    private static List<ManagedAgentSession> append(List<ManagedAgentSession> sessions, ManagedAgentSession session) {
        List<ManagedAgentSession> next = new ArrayList<>(sessions);
        next.add(session);
        return next;
    }

    private static ManagedAgentSession sessionNamed(String displayName) {
        Instant now = Instant.now();
        return new ManagedAgentSession(
                ManagedSessionId.newId(), RepositoryId.newId(), displayName,
                new AgentBinding(AgentKind.CLAUDE, Optional.empty(), Optional.empty()),
                new SessionWorkspace(Path.of("/tmp"), Optional.empty(), true),
                SessionStatus.INACTIVE, now, now, Optional.empty(),
                PrLink.of(PrState.NONE, Optional.empty()), false, Optional.empty());
    }

    /**
     * In-memory repository recording every save. When constructed with the
     * two latches, the first {@link #save} blocks on {@code
     * releaseFirstSave} so a test can hold the store's writer thread inside
     * a save and observe coalescing deterministically; the latch constructor
     * is only for that test, plain construction never blocks.
     */
    private static final class RecordingRepository implements ApplicationStateRepository {
        // volatile: saves arrive on the state store's background writer thread.
        private volatile ApplicationState state = ApplicationState.empty();
        private final List<ApplicationState> saves = new CopyOnWriteArrayList<>();
        private final CountDownLatch firstSaveEntered;
        private final CountDownLatch releaseFirstSave;

        RecordingRepository() {
            this(null, null);
        }

        RecordingRepository(CountDownLatch firstSaveEntered, CountDownLatch releaseFirstSave) {
            this.firstSaveEntered = firstSaveEntered;
            this.releaseFirstSave = releaseFirstSave;
        }

        @Override
        public ApplicationState load() {
            return state;
        }

        @Override
        public void save(ApplicationState newState) {
            if (firstSaveEntered != null && saves.isEmpty()) {
                firstSaveEntered.countDown();
                try {
                    releaseFirstSave.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            state = newState;
            saves.add(newState);
        }
    }
}
