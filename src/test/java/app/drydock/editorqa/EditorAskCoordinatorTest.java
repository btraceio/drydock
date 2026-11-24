package app.drydock.editorqa;

import app.drydock.agent.api.AgentKind;
import app.drydock.app.ApplicationStateStore;
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
import app.drydock.editorqa.EditorAskHarnessPolicy.Choice;
import app.drydock.editorqa.EditorAskLane.Event;
import app.drydock.editorqa.EditorAskLane.EventKind;
import app.drydock.editorqa.EditorAskLane.WriteOutcome;
import app.drydock.editorqa.EditorAskPorts.Events;
import app.drydock.editorqa.EditorAskPorts.LaunchOutcome;
import app.drydock.editorqa.EditorAskPorts.LaunchRequest;
import app.drydock.editorqa.EditorAskPorts.LaunchResult;
import app.drydock.editorqa.EditorAskPorts.Launcher;
import app.drydock.editorqa.EditorAskPorts.Resumability;
import app.drydock.editorqa.EditorAskPorts.Result;
import app.drydock.editorqa.EditorAskPorts.Settings;
import app.drydock.editorqa.EditorAskPorts.Submission;
import app.drydock.editorqa.EditorAskPorts.Wiring;
import app.drydock.editorqa.EditorAskRecord.Status;
import app.drydock.editorqa.EditorAskRecord.TextRange;
import app.drydock.state.ApplicationStateRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorAskCoordinatorTest {

    @TempDir
    Path tmp;

    private final List<EditorAskCoordinator> open = new CopyOnWriteArrayList<>();

    @AfterEach
    void closeAll() {
        open.forEach(EditorAskCoordinator::close);
    }

    // ---- fixtures ----

    static final class Repo implements ApplicationStateRepository {
        volatile ApplicationState state = ApplicationState.empty();
        final AtomicBoolean failSave = new AtomicBoolean();

        @Override
        public ApplicationState load() {
            return state;
        }

        @Override
        public void save(ApplicationState s) {
            if (failSave.get()) {
                throw new IllegalStateException("disk full");
            }
            state = s;
        }
    }

    static final class FakeLauncher implements Launcher {
        final List<LaunchRequest> launches = new CopyOnWriteArrayList<>();
        final List<String> writes = new CopyOnWriteArrayList<>();
        final List<String> stops = new CopyOnWriteArrayList<>();
        volatile Function<LaunchRequest, LaunchResult> onLaunch =
                r -> new LaunchResult(LaunchOutcome.STARTED, 7, null);
        volatile Resumability resumability = Resumability.EXACT;
        volatile Runnable beforeLaunch = () -> { };

        @Override
        public LaunchResult launch(LaunchRequest request) {
            beforeLaunch.run();
            launches.add(request);
            return onLaunch.apply(request);
        }

        @Override
        public WriteOutcome write(String executionId, long generation, String prompt) {
            writes.add(prompt);
            return WriteOutcome.DELIVERED;
        }

        @Override
        public Resumability verifyConversation(ManagedSessionId row, AgentKind kind, String id) {
            return resumability;
        }

        @Override
        public void stop(String executionId) {
            stops.add(executionId);
        }
    }

    static final class Recorder implements Events {
        final List<String> notices = new CopyOnWriteArrayList<>();
        final List<String> errors = new CopyOnWriteArrayList<>();
        volatile boolean failRecoveryWithError;

        @Override
        public void notice(String root, ManagedSessionId row, String message) {
            notices.add(message);
        }

        @Override
        public void recoveryError(String root, String message) {
            if (failRecoveryWithError) {
                throw new AssertionError("recovery listener failed");
            }
            errors.add(message);
        }
    }

    final Repo repo = new Repo();
    final FakeLauncher launcher = new FakeLauncher();
    final Recorder events = new Recorder();
    volatile Choice choice = Choice.CLAUDE;
    final Settings settings = new Settings() {
        @Override
        public Choice choice() {
            return choice;
        }

        @Override
        public boolean available(AgentKind kind) {
            return true;
        }

        @Override
        public Optional<String> model(AgentKind kind) {
            return Optional.of("m-" + kind.persistedName());
        }
    };
    ApplicationStateStore catalog = ApplicationStateStore.forRepository(repo);
    EditorAskHistoryStore store;
    EditorAskCoordinator coordinator;

    void start(EditorAskHistoryStore.PartitionWriter writer) {
        start(writer, null);
    }

    void start(EditorAskHistoryStore.PartitionWriter writer, Executor executor) {
        store = writer == null ? new EditorAskHistoryStore(tmp.resolve("base"))
                : new EditorAskHistoryStore(tmp.resolve("base"), null, writer);
        coordinator = new EditorAskCoordinator(store, new EditorAskRowBinder(catalog),
                new Wiring(launcher, settings, (row, gen) -> gen == 7, events),
                () -> Instant.parse("2030-01-01T00:00:00Z"), executor);
        open.add(coordinator);
    }

    static ManagedAgentSession candidate(Path root) {
        Instant now = Instant.EPOCH;
        return new ManagedAgentSession(ManagedSessionId.newId(), RepositoryId.newId(), "Drydock Q&A — repo",
                new AgentBinding(AgentKind.CLAUDE, Optional.empty(), Optional.empty(), SessionMode.READ_ONLY),
                new SessionWorkspace(root, Optional.empty(), true), SessionStatus.INACTIVE, now, now,
                Optional.empty(), PrLink.of(PrState.NONE, Optional.empty()), false, Optional.empty())
                .toBuilder().purpose(SessionPurpose.EDITOR_QA).build();
    }

    static Submission ask(Path root, String question) {
        return new Submission(root.toString(), candidate(root), "src/A.java", new TextRange(0, 4, 0, 0, 0, 4),
                "text", question, null);
    }

    Path checkout(String name) throws IOException {
        return Files.createDirectories(tmp.resolve(name)).toRealPath();
    }

    static void await(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 500 && !condition.getAsBoolean(); i++) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "condition not reached in time");
    }

    Status statusOf(Path root, String id) {
        return store.records(root.toString()).stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow().status();
    }

    long markedRows() {
        return catalog.state().sessions().stream().filter(ManagedAgentSession::editorQa).count();
    }

    Result submitAndGet(Submission s) throws Exception {
        return coordinator.submit(s).get(10, TimeUnit.SECONDS);
    }

    // ---- admission, ownership, durability ----

    @Test
    void racingFirstAsksFromTwoWindowsShareOneRowAndOneLaunch() throws Exception {
        start(null);
        Path root = checkout("repo");
        CountDownLatch gate = new CountDownLatch(1);
        launcher.beforeLaunch = () -> {
            try {
                gate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        var f1 = CompletableFuture.supplyAsync(() -> coordinator.submit(ask(root, "q1")).join());
        var f2 = CompletableFuture.supplyAsync(() -> coordinator.submit(ask(root, "q2")).join());
        Result a = f1.get(10, TimeUnit.SECONDS);
        Result b = f2.get(10, TimeUnit.SECONDS);
        assertTrue(a.accepted() && b.accepted());
        assertEquals(a.row(), b.row());
        assertEquals(1, markedRows());
        assertEquals(List.of(0L, 1L), List.of(Math.min(a.sequence(), b.sequence()), Math.max(a.sequence(), b.sequence())));
        assertEquals(0, launcher.launches.size(), "nothing launches before durable admission completes");
        gate.countDown();
        await(() -> launcher.launches.size() == 1);
        Thread.sleep(100);
        assertEquals(1, launcher.launches.size(), "the second ask queues behind the one launch");
    }

    @Test
    void symlinkSpellingOfTheSameCheckoutIsOneCheckout() throws Exception {
        start(null);
        Path real = checkout("real");
        Path link = Files.createSymbolicLink(tmp.resolve("link"), real);
        assertTrue(submitAndGet(ask(real, "q1")).accepted());
        assertTrue(submitAndGet(ask(link.toRealPath(), "q2")).accepted());
        assertEquals(1, markedRows());
        assertEquals(2, store.records(real.toString()).size());
    }

    @Test
    void ordinarySameTitleRowIsNeverReusedOrTouched() throws Exception {
        start(null);
        Path root = checkout("repo");
        ManagedAgentSession ordinary = candidate(root).toBuilder().purpose(SessionPurpose.ORDINARY).build();
        catalog.update(s -> s.withSessions(List.of(ordinary)));
        Result r = submitAndGet(ask(root, "q1"));
        assertNotEquals(ordinary.id(), r.row());
        assertTrue(catalog.state().sessions().contains(ordinary));
        assertEquals(1, markedRows());
    }

    @Test
    void rowOnlyCrashLeavesAReusableRow() throws Exception {
        start(null);
        Path root = checkout("repo");
        ManagedAgentSession reserved = new EditorAskRowBinder(catalog).reserve(candidate(root));
        Result r = submitAndGet(ask(root, "q1"));
        assertEquals(reserved.id(), r.row());
        assertEquals(1, markedRows());
    }

    @Test
    void catalogAndHistoryAreDurableBeforeAnySend() throws Exception {
        start(null);
        Path root = checkout("repo");
        AtomicBoolean durableAtSend = new AtomicBoolean();
        launcher.beforeLaunch = () -> {
            try {
                durableAtSend.set(Files.readString(store.partitionFile(root.toString())).contains("q1")
                        && repo.state.sessions().stream().anyMatch(ManagedAgentSession::editorQa));
            } catch (IOException e) {
                durableAtSend.set(false);
            }
        };
        assertTrue(submitAndGet(ask(root, "q1")).accepted());
        await(() -> launcher.launches.size() == 1);
        assertTrue(durableAtSend.get());
    }

    @Test
    void catalogSaveFailureRefusesBeforeAnySendAndRecovers() throws Exception {
        start(null);
        Path root = checkout("repo");
        repo.failSave.set(true);
        Result r = submitAndGet(ask(root, "q1"));
        assertFalse(r.accepted());
        assertTrue(store.records(root.toString()).isEmpty());
        assertEquals(0, launcher.launches.size());
        assertFalse(events.errors.isEmpty());
        repo.failSave.set(false);
        assertTrue(submitAndGet(ask(root, "q2")).accepted());
        await(() -> launcher.launches.size() == 1);
    }

    @Test
    void executorRejectionRefusesQueuedAdmissionsAndAllowsLaterDrain() throws Exception {
        CountDownLatch scheduling = new CountDownLatch(1);
        CountDownLatch reject = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        List<Runnable> scheduledDrains = new CopyOnWriteArrayList<>();
        Executor rejectOnce = task -> {
            if (calls.getAndIncrement() == 0) {
                scheduling.countDown();
                try {
                    if (!reject.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("timed out waiting to reject admission drain");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RejectedExecutionException("interrupted", e);
                }
                throw new RejectedExecutionException("executor is shutting down");
            }
            scheduledDrains.add(task);
        };
        start(null, rejectOnce);
        Path root = checkout("repo");

        CompletableFuture<CompletableFuture<Result>> firstSubmit =
                CompletableFuture.supplyAsync(() -> coordinator.submit(ask(root, "q1")));
        assertTrue(scheduling.await(5, TimeUnit.SECONDS));
        CompletableFuture<Result> queued = coordinator.submit(ask(root, "q2"));
        assertFalse(queued.isDone(), "queued admission must not run inline while scheduling is pending");
        reject.countDown();

        assertFalse(firstSubmit.get(5, TimeUnit.SECONDS).get(5, TimeUnit.SECONDS).accepted());
        assertFalse(queued.get(5, TimeUnit.SECONDS).accepted());
        assertTrue(store.records(root.toString()).isEmpty());
        assertTrue(launcher.launches.isEmpty());

        CompletableFuture<Result> later = coordinator.submit(ask(root, "q3"));
        assertFalse(later.isDone(), "the accepted drain should remain asynchronous");
        assertEquals(1, scheduledDrains.size());
        scheduledDrains.removeFirst().run();
        assertTrue(later.get(5, TimeUnit.SECONDS).accepted());
        assertEquals(1, scheduledDrains.size(), "dispatch remains a separate executor task");
        scheduledDrains.removeFirst().run();
        assertEquals(1, launcher.launches.size());
    }

    @Test
    void admissionErrorCompletesItsFutureAndDoesNotStrandLaterWork() throws Exception {
        start(null, Runnable::run);
        Path root = checkout("repo");
        repo.failSave.set(true);
        events.failRecoveryWithError = true;

        Result failed = submitAndGet(ask(root, "q1"));
        assertFalse(failed.accepted());
        assertTrue(launcher.launches.isEmpty());

        repo.failSave.set(false);
        events.failRecoveryWithError = false;
        Result recovered = submitAndGet(ask(root, "q2"));
        assertTrue(recovered.accepted());
        assertEquals(1, launcher.launches.size());
    }

    @Test
    void historyWriteFailureRefusesBeforeAnySend() throws Exception {
        start((target, bytes) -> {
            throw new IOException("disk full");
        });
        Path root = checkout("repo");
        Result r = submitAndGet(ask(root, "q1"));
        assertFalse(r.accepted());
        Thread.sleep(100);
        assertEquals(0, launcher.launches.size());
    }

    @Test
    void inconsistentOwnerIsAVisibleRecoveryError() throws Exception {
        start(null);
        Path root = checkout("repo");
        assertTrue(submitAndGet(ask(root, "q1")).accepted());
        // a different window's catalog lost the row: the history now names an owner the catalog does not
        catalog.update(s -> s.withSessions(List.of()));
        EditorAskRowBinder fresh = new EditorAskRowBinder(catalog);
        assertTrue(new EditorAskRowBinder(catalog).markedFor(root).isEmpty());
        coordinator = new EditorAskCoordinator(store, fresh,
                new Wiring(launcher, settings, (row, gen) -> true, events), () -> Instant.EPOCH);
        open.add(coordinator);
        Result r = submitAndGet(ask(root, "q2"));
        assertFalse(r.accepted());
        assertFalse(events.errors.isEmpty());
    }

    // ---- answers, late completions, resume ----

    private LaunchRequest firstLaunch() throws InterruptedException {
        await(() -> !launcher.launches.isEmpty());
        return launcher.launches.getFirst();
    }

    @Test
    void answerIsCommittedExactlyOnceWithServerTime() throws Exception {
        start(null);
        Path root = checkout("repo");
        Result r = submitAndGet(ask(root, "q1"));
        LaunchRequest launch = firstLaunch();
        assertTrue(launch.prompt().contains(r.id()));
        assertFalse(coordinator.commit(r.row(), 8, r.id(), "stale generation"));
        assertFalse(coordinator.commit(ManagedSessionId.newId(), 7, r.id(), "foreign caller"));
        assertTrue(coordinator.commit(r.row(), 7, r.id(), "the answer"));
        assertFalse(coordinator.commit(r.row(), 7, r.id(), "second answer"));
        EditorAskRecord saved = store.records(root.toString()).getFirst();
        assertEquals(Status.ANSWERED, saved.status());
        assertEquals("the answer", saved.answer());
        assertEquals(Instant.parse("2030-01-01T00:00:00Z"), saved.answeredAt());
    }

    @Test
    void answerSaveFailureIsNotSuccessAndNeverReopensTheRight() throws Exception {
        AtomicBoolean fail = new AtomicBoolean();
        start((target, bytes) -> {
            if (fail.get()) {
                throw new IOException("disk full");
            }
            EditorAskHistoryStore.writeAtomically(target, bytes);
        });
        Path root = checkout("repo");
        Result r = submitAndGet(ask(root, "q1"));
        firstLaunch();
        fail.set(true);
        assertFalse(coordinator.commit(r.row(), 7, r.id(), "the answer"));
        assertFalse(coordinator.commit(r.row(), 7, r.id(), "again"));
        assertFalse(events.errors.isEmpty());
        assertEquals(1, launcher.launches.size());
        assertEquals("the answer", store.records(root.toString()).getFirst().answer());
    }

    @Test
    void lateCompletionsCannotDemoteAnAnswerOrBindAReplacement() throws Exception {
        start(null);
        Path root = checkout("repo");
        Result r = submitAndGet(ask(root, "q1"));
        LaunchRequest launch = firstLaunch();
        String exec = launch.executionId();
        assertTrue(coordinator.commit(r.row(), 7, r.id(), "the answer"));
        coordinator.onEvent(root.toString(), new Event(exec, launch.generation(), EventKind.SUBMITTED, r.id(), 1));
        coordinator.onEvent(root.toString(), new Event(exec, launch.generation(), EventKind.END, r.id(), 2));
        assertEquals(Status.ANSWERED, statusOf(root, r.id()));
        assertFalse(coordinator.onConversationProven(root.toString(), "qa-old", launch.generation(), AgentKind.CLAUDE, "c-0"));
        assertFalse(coordinator.onConversationProven(root.toString(), exec, launch.generation() + 1, AgentKind.CLAUDE, "c-0"));
        assertFalse(coordinator.onConversationProven(root.toString(), exec, launch.generation(), AgentKind.PI, "c-0"));
        assertTrue(coordinator.onConversationProven(root.toString(), exec, launch.generation(), AgentKind.CLAUDE, "c-1"));
        assertEquals(Optional.of("c-1"), catalog.state().sessions().getFirst().binding().sessionId());
        coordinator.onExecutionEnded(root.toString(), "qa-other", launch.generation());
        assertEquals(Status.ANSWERED, statusOf(root, r.id()));
        assertFalse(coordinator.commit(r.row(), 7, r.id(), "late"));
    }

    @Test
    void secondAskIsWrittenLiveOnlyAfterProvenReadiness() throws Exception {
        start(null);
        Path root = checkout("repo");
        Result r1 = submitAndGet(ask(root, "q1"));
        Result r2 = submitAndGet(ask(root, "q2"));
        LaunchRequest launch = firstLaunch();
        String exec = launch.executionId();
        long gen = launch.generation();
        assertTrue(coordinator.commit(r1.row(), 7, r1.id(), "a1"));
        assertFalse(coordinator.commit(r1.row(), 7, r2.id(), "answer for a merely queued id"));
        Thread.sleep(100);
        assertTrue(launcher.writes.isEmpty(), "an answer does not release the turn");
        coordinator.onEvent(root.toString(), new Event(exec, gen, EventKind.SUBMITTED, r1.id(), 1));
        coordinator.onEvent(root.toString(), new Event(exec, gen, EventKind.END, r1.id(), 2));
        coordinator.onEvent(root.toString(), new Event(exec, gen, EventKind.READY, r1.id(), 3));
        await(() -> launcher.writes.size() == 1);
        assertTrue(launcher.writes.getFirst().contains(r2.id()));
        assertEquals(1, launcher.launches.size());
    }

    @Test
    void exactResumeKeepsTheOriginalHarnessWithoutAModel() throws Exception {
        start(null);
        Path root = checkout("repo");
        Result r1 = submitAndGet(ask(root, "q1"));
        LaunchRequest first = firstLaunch();
        assertTrue(coordinator.onConversationProven(root.toString(), first.executionId(), first.generation(),
                AgentKind.CLAUDE, "c-1"));
        coordinator.onExecutionEnded(root.toString(), first.executionId(), first.generation());
        choice = Choice.PI;
        submitAndGet(ask(root, "q2"));
        await(() -> launcher.launches.size() == 2);
        LaunchRequest second = launcher.launches.get(1);
        assertEquals(AgentKind.CLAUDE, second.harness());
        assertEquals(Optional.of("c-1"), second.resumeConversationId());
        assertEquals(Optional.empty(), second.model());
        assertTrue(events.notices.isEmpty());
        assertNotEquals(first.executionId(), second.executionId());
        assertNotEquals(r1.id(), second.prompt());
    }

    @Test
    void unprovableResumeClearsBindingAnnouncesLossAndStartsFresh() throws Exception {
        start(null);
        Path root = checkout("repo");
        submitAndGet(ask(root, "q1"));
        LaunchRequest first = firstLaunch();
        coordinator.onConversationProven(root.toString(), first.executionId(), first.generation(), AgentKind.CLAUDE, "c-1");
        coordinator.onExecutionEnded(root.toString(), first.executionId(), first.generation());
        launcher.resumability = Resumability.LOST;
        choice = Choice.PI;
        submitAndGet(ask(root, "q2"));
        await(() -> launcher.launches.size() == 2);
        LaunchRequest second = launcher.launches.get(1);
        assertEquals(AgentKind.PI, second.harness());
        assertEquals(Optional.of("m-pi"), second.model());
        assertTrue(second.resumeConversationId().isEmpty());
        assertEquals(1, events.notices.size());
        assertTrue(events.notices.getFirst().contains("context lost"));
        assertEquals(Optional.empty(), catalog.state().sessions().getFirst().binding().sessionId());
        assertEquals(1, markedRows());
    }

    @Test
    void ambiguousResumeIsNeverReplayed() throws Exception {
        start(null);
        Path root = checkout("repo");
        submitAndGet(ask(root, "q1"));
        LaunchRequest first = firstLaunch();
        coordinator.onConversationProven(root.toString(), first.executionId(), first.generation(), AgentKind.CLAUDE, "c-1");
        coordinator.onExecutionEnded(root.toString(), first.executionId(), first.generation());
        launcher.onLaunch = r -> new LaunchResult(LaunchOutcome.FAILED, -1, null);
        Result r2 = submitAndGet(ask(root, "q2"));
        await(() -> launcher.launches.size() == 2);
        await(() -> statusOf(root, r2.id()) == Status.INTERRUPTED_POSSIBLY_SENT);
        coordinator.poll();
        Thread.sleep(100);
        assertEquals(2, launcher.launches.size());
    }

    // ---- isolation, shutdown, retry, forget ----

    @Test
    void blockedCheckoutDoesNotStopAnother() throws Exception {
        start(null);
        Path a = checkout("a");
        Path b = checkout("b");
        launcher.onLaunch = r -> r.root().equals(a.toString())
                ? new LaunchResult(LaunchOutcome.FAILED, -1, null)
                : new LaunchResult(LaunchOutcome.STARTED, 7, null);
        Result a1 = submitAndGet(ask(a, "a1"));
        await(() -> statusOf(a, a1.id()) == Status.INTERRUPTED_POSSIBLY_SENT);
        Result a2 = submitAndGet(ask(a, "a2"));
        assertEquals(Status.FAILED_NOT_SENT, statusOf(a, a2.id()));
        Result b1 = submitAndGet(ask(b, "b1"));
        await(() -> launcher.launches.stream().anyMatch(l -> l.root().equals(b.toString())));
        assertNotEquals(a1.row(), b1.row());
        assertEquals(1, launcher.launches.stream().filter(l -> l.root().equals(a.toString())).count());
    }

    @Test
    void refusedLaunchSettlesNotSentAndRetryMintsANewId() throws Exception {
        start(null);
        Path root = checkout("repo");
        launcher.onLaunch = r -> new LaunchResult(LaunchOutcome.REFUSED, -1, "the harness is missing");
        Submission s = ask(root, "q1");
        Result first = submitAndGet(s);
        await(() -> statusOf(root, first.id()) == Status.FAILED_NOT_SENT);
        assertTrue(store.records(root.toString()).getFirst().failureReason().contains("the harness is missing"));
        launcher.onLaunch = r -> new LaunchResult(LaunchOutcome.STARTED, 7, null);
        Result retry = submitAndGet(s);
        assertNotEquals(first.id(), retry.id());
        await(() -> launcher.launches.size() == 2);
        assertEquals(2, store.records(root.toString()).size());
    }

    @Test
    void shutdownSettlesEverythingAndSendsNothing() throws Exception {
        start(null);
        Path root = checkout("repo");
        Result r1 = submitAndGet(ask(root, "q1"));
        Result r2 = submitAndGet(ask(root, "q2"));
        LaunchRequest launch = firstLaunch();
        coordinator.close();
        assertEquals(1, launcher.launches.size());
        assertTrue(launcher.writes.isEmpty());
        assertTrue(launcher.stops.contains(launch.executionId()));
        assertTrue(statusOf(root, r1.id()).isSettled());
        assertEquals(Status.FAILED_NOT_SENT, statusOf(root, r2.id()));
        assertFalse(submitAndGet(ask(root, "q3")).accepted());
        // restart: unfinished work loads as interrupted and nothing is sent automatically
        store.close();
        EditorAskHistoryStore reloaded = new EditorAskHistoryStore(tmp.resolve("base"));
        assertTrue(reloaded.records(root.toString()).stream().allMatch(EditorAskRecord::isSettled));
        Thread.sleep(100);
        assertEquals(1, launcher.launches.size());
        reloaded.close();
    }

    @Test
    void retainedQaRowIsProtectedFromForget() throws Exception {
        start(null);
        Path root = checkout("repo");
        ManagedAgentSession emptyRow = candidate(root);
        assertTrue(coordinator.forgetProtection(emptyRow).isEmpty(), "no history: nothing to strand");
        Result r = submitAndGet(ask(root, "q1"));
        ManagedAgentSession row = catalog.state().sessions().stream()
                .filter(s -> s.id().equals(r.row())).findFirst().orElseThrow();
        assertTrue(coordinator.forgetProtection(row).isPresent());
        assertTrue(coordinator.forgetProtection(row.toBuilder().purpose(SessionPurpose.ORDINARY).build()).isEmpty());
    }
}
