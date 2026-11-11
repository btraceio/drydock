package app.drydock.delayedtasks;

import app.drydock.domain.RepositoryId;
import app.drydock.git.GhCliService;
import app.drydock.git.GitStatusService;
import app.drydock.github.GitHubStackPrService;
import app.drydock.github.GitHubStackPrService.Created;
import app.drydock.github.GitHubStackPrService.Outcome;
import app.drydock.github.GitHubStackPrService.Rejected;
import app.drydock.tasks.DoneBy;
import app.drydock.tasks.TaskDocument;
import app.drydock.tasks.TaskId;
import app.drydock.tasks.TaskSnapshot;
import app.drydock.tasks.TaskStatus;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lifecycle tests for {@link StackPrService}: the observe backoff gate,
 * the POST mark invariant, the base selection, and the injected-executor
 * threading. All determinism comes from the package-private injection
 * constructor's collaborator hooks; no test spawns a real {@code gh} or
 * network call and none asserts executor timing beyond bounded latches.
 */
class StackPrServiceTest {

    private static final RepositoryId REPO = RepositoryId.newId();
    private static final RepositoryId OTHER_REPO = RepositoryId.newId();
    private static final Path ROOT = Path.of("/unused/test-root");

    private static TaskDocument document(RepositoryId repository, TaskId id, TaskStatus status,
            List<String> after) {
        return new TaskDocument(id, repository, "Task " + id.text(), status, DoneBy.HUMAN,
                Optional.empty(), after, List.of(), "", "");
    }

    private static TaskSnapshot snapshot(List<TaskDocument> documents) {
        Map<TaskId, TaskDocument> byId = new LinkedHashMap<>();
        Set<RepositoryId> repositories = new LinkedHashSet<>();
        for (TaskDocument document : documents) {
            byId.put(document.id(), document);
            repositories.add(document.repository());
        }
        return new TaskSnapshot(documents, List.copyOf(repositories), byId, Map.of(), List.of(),
                List.of());
    }

    /** The slug mapping: one distinct slug per repository, recorded when read. */
    private static Function<RepositoryId, String> slugs(AtomicInteger reads) {
        return repository -> {
            reads.incrementAndGet();
            return repository.equals(REPO) ? "alpha" : "beta";
        };
    }

    /** Runs every attempt body synchronously on the caller's thread. */
    private static ExecutorService directExecutor() {
        return new AbstractExecutorService() {
            @Override
            public void execute(Runnable command) {
                command.run();
            }

            @Override
            public void shutdown() {
            }

            @Override
            public List<Runnable> shutdownNow() {
                return List.of();
            }

            @Override
            public boolean isShutdown() {
                return false;
            }

            @Override
            public boolean isTerminated() {
                return false;
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) {
                return true;
            }
        };
    }

    /** A flag-gated executor: while armed, every dispatch is rejected. */
    private static ExecutorService rejectingExecutor(AtomicBoolean reject) {
        return new AbstractExecutorService() {
            @Override
            public void execute(Runnable command) {
                if (reject.get()) {
                    throw new RejectedExecutionException("closed");
                }
                command.run();
            }

            @Override
            public void shutdown() {
            }

            @Override
            public List<Runnable> shutdownNow() {
                return List.of();
            }

            @Override
            public boolean isShutdown() {
                return false;
            }

            @Override
            public boolean isTerminated() {
                return false;
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) {
                return true;
            }
        };
    }

    /** A probe that counts every ref lookup and answers from a missing set. */
    private static StackPrService.RefProbe countingProbe(AtomicInteger calls, Set<String> missingRefs) {
        return (root, ref) -> {
            calls.incrementAndGet();
            return missingRefs.contains(ref) ? Optional.empty() : Optional.of("abc123");
        };
    }

    private static StackPrService.ViewPrProbe emptyViewPr(AtomicInteger calls) {
        return (root, head) -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(Optional.empty());
        };
    }

    private static StackPrService.DefaultBranchLookup fixedDefaultBranch(AtomicInteger calls,
            String branch) {
        return root -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(Optional.of(branch));
        };
    }

    /** A create hook that records the requested base and answers {@code answers} in order. */
    private static StackPrService.CreateCall createRecording(AtomicInteger calls,
            AtomicReference<String> bases, List<Outcome> answers) {
        return (root, repository, head, base, title, body) -> {
            calls.incrementAndGet();
            bases.set(base);
            return CompletableFuture.completedFuture(answers.get(Math.min(calls.get() - 1,
                    answers.size() - 1)));
        };
    }

    private static StackPrService service(ExecutorService executor, long backoffInitialMs,
            StackPrService.RefProbe refProbe, StackPrService.ViewPrProbe viewPr,
            StackPrService.DefaultBranchLookup defaultBranch, StackPrService.CreateCall create) {
        return new StackPrService(id -> new StackPrService.OptionalRoot(ROOT), new GhCliService(),
                new GitStatusService(), new GitHubStackPrService(), Optional.empty(), executor,
                viewPr, refProbe, defaultBranch, create, 1, backoffInitialMs);
    }

    private static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aProbedNotYetPushableTaskIsGatedForTheBackoffWindow() {
        AtomicInteger probeCalls = new AtomicInteger();
        StackPrService service = service(directExecutor(), 60_000,
                countingProbe(probeCalls, Set.of("dd/task-a", "dd/task-a@{upstream}")),
                emptyViewPr(new AtomicInteger()), fixedDefaultBranch(new AtomicInteger(), "main"),
                createRecording(new AtomicInteger(), new AtomicReference<>(), List.of()));
        TaskSnapshot snapshot = snapshot(List.of(document(REPO, TaskId.of("task-a"),
                TaskStatus.DONE, List.of())));
        service.observe(snapshot, slugs(new AtomicInteger()));
        service.observe(snapshot, slugs(new AtomicInteger()));
        assertEquals(1, probeCalls.get(), "the second snapshot must be gated by the backoff");
        service.close();
    }

    @Test
    void anExpiredBackoffDeadlineReconsidersTheTask() {
        AtomicInteger probeCalls = new AtomicInteger();
        StackPrService service = service(directExecutor(), 40,
                countingProbe(probeCalls, Set.of("dd/task-a", "dd/task-a@{upstream}")),
                emptyViewPr(new AtomicInteger()), fixedDefaultBranch(new AtomicInteger(), "main"),
                createRecording(new AtomicInteger(), new AtomicReference<>(), List.of()));
        TaskSnapshot snapshot = snapshot(List.of(document(REPO, TaskId.of("task-a"),
                TaskStatus.DONE, List.of())));
        service.observe(snapshot, slugs(new AtomicInteger()));
        assertEquals(1, probeCalls.get());
        sleepMs(150);
        service.observe(snapshot, slugs(new AtomicInteger()));
        assertEquals(2, probeCalls.get(), "an expired deadline is always re-evaluated");
        service.close();
    }

    @Test
    void aJoinFailureKeepsTheTaskMarked() {
        AtomicInteger probeCalls = new AtomicInteger();
        AtomicInteger viewPrCalls = new AtomicInteger();
        AtomicInteger createCalls = new AtomicInteger();
        CompletableFuture<Outcome> failed = new CompletableFuture<>();
        failed.completeExceptionally(new TimeoutException("join timeout"));
        StackPrService service = service(directExecutor(), 40,
                countingProbe(probeCalls, Set.of()),
                emptyViewPr(viewPrCalls),
                fixedDefaultBranch(new AtomicInteger(), "main"),
                (root, repository, head, base, title, body) -> {
                    createCalls.incrementAndGet();
                    return failed;
                });
        TaskSnapshot snapshot = snapshot(List.of(document(REPO, TaskId.of("task-a"),
                TaskStatus.DONE, List.of())));
        assertDoesNotThrow(() -> service.observe(snapshot, slugs(new AtomicInteger())));
        assertEquals(1, createCalls.get());
        service.observe(snapshot, slugs(new AtomicInteger()));
        assertEquals(1, createCalls.get(),
                "an unknown-outcome POST join keeps the mark: no second POST");
        service.close();
    }

    @Test
    void aRefusedPrRetriesOnlyAfterTheBackoffAndTheExistingPrShortCircuits() {
        AtomicInteger probeCalls = new AtomicInteger();
        AtomicInteger viewPrCalls = new AtomicInteger();
        AtomicInteger createCalls = new AtomicInteger();
        AtomicReference<String> bases = new AtomicReference<>();
        AtomicReference<Optional<GhCliService.PrInfo>> existing =
                new AtomicReference<>(Optional.empty());
        StackPrService service = service(directExecutor(), 40,
                countingProbe(probeCalls, Set.of()),
                (root, head) -> {
                    viewPrCalls.incrementAndGet();
                    return CompletableFuture.completedFuture(existing.get());
                },
                fixedDefaultBranch(new AtomicInteger(), "main"),
                createRecording(createCalls, bases,
                        List.of(new Rejected("base branch not found"))));
        TaskSnapshot snapshot = snapshot(List.of(document(REPO, TaskId.of("task-a"),
                TaskStatus.DONE, List.of())));
        service.observe(snapshot, slugs(new AtomicInteger()));
        assertEquals(1, createCalls.get());
        service.observe(snapshot, slugs(new AtomicInteger()));
        assertEquals(1, createCalls.get(), "the refused task is backed off, not retried at once");
        sleepMs(150);
        existing.set(Optional.of(new GhCliService.PrInfo(7, GhCliService.PrInfo.PrLifecycle.OPEN,
                Optional.of("https://example/pr/7"))));
        service.observe(snapshot, slugs(new AtomicInteger()));
        assertEquals(1, createCalls.get(), "the pre-POST viewPr guard short-circuits a retry");
        // The guard probes on every attempt: the first (refused) attempt's
        // pre-POST probe plus this retry's short-circuit probe.
        assertEquals(2, viewPrCalls.get());
        service.observe(snapshot, slugs(new AtomicInteger()));
        assertEquals(2, viewPrCalls.get(),
                "the existing-PR path keeps the mark: no re-probe on the next snapshot");
        assertEquals(1, createCalls.get(), "never a second POST for one task");
        service.close();
    }

    @Test
    void anActiveInRepoPrerequisiteIsTheBase() {
        AtomicInteger createCalls = new AtomicInteger();
        AtomicReference<String> bases = new AtomicReference<>();
        StackPrService service = service(directExecutor(), 60_000,
                countingProbe(new AtomicInteger(), Set.of()),
                emptyViewPr(new AtomicInteger()),
                fixedDefaultBranch(new AtomicInteger(), "main"),
                createRecording(createCalls, bases, List.of(new Created(1, "https://example/pr/1"))));
        TaskSnapshot snapshot = snapshot(List.of(
                document(REPO, TaskId.of("task-b"), TaskStatus.OPEN, List.of()),
                document(REPO, TaskId.of("task-a"), TaskStatus.DONE, List.of("task-b"))));
        service.observe(snapshot, slugs(new AtomicInteger()));
        assertEquals(1, createCalls.get());
        assertEquals("dd/task-b", bases.get(), "the active in-repo prerequisite is the base");
        service.close();
    }

    @Test
    void aSatisfiedInRepoPrerequisiteIsNeverTheBase() {
        AtomicInteger createCalls = new AtomicInteger();
        AtomicReference<String> bases = new AtomicReference<>();
        AtomicInteger defaultBranchCalls = new AtomicInteger();
        for (TaskStatus satisfied : List.of(TaskStatus.DONE, TaskStatus.DROPPED)) {
            StackPrService service = service(directExecutor(), 60_000,
                    countingProbe(new AtomicInteger(), Set.of()),
                    emptyViewPr(new AtomicInteger()),
                    fixedDefaultBranch(defaultBranchCalls, "main"),
                    createRecording(createCalls, bases, List.of(new Created(1, "https://e/1"))));
            TaskSnapshot snapshot = snapshot(List.of(
                    document(REPO, TaskId.of("task-b"), satisfied, List.of()),
                    document(REPO, TaskId.of("task-a"), TaskStatus.DONE, List.of("task-b"))));
            service.observe(snapshot, slugs(new AtomicInteger()));
            assertEquals("main", bases.get(),
                    satisfied + " prerequisites are skipped for the default branch");
            service.close();
        }
        // Three default-branch lookups: the DONE prerequisite in the first
        // iteration is itself a finished stack root that attempts (2 calls:
        // task-b, then task-a); in the DROPPED iteration only task-a attempts (1).
        assertEquals(3, defaultBranchCalls.get());
    }

    @Test
    void aCrossRepoPrerequisiteIsNeverTheBase() {
        AtomicInteger createCalls = new AtomicInteger();
        AtomicReference<String> bases = new AtomicReference<>();
        StackPrService service = service(directExecutor(), 60_000,
                countingProbe(new AtomicInteger(), Set.of()),
                emptyViewPr(new AtomicInteger()),
                fixedDefaultBranch(new AtomicInteger(), "main"),
                createRecording(createCalls, bases, List.of(new Created(1, "https://example/pr/1"))));
        TaskSnapshot snapshot = snapshot(List.of(
                document(OTHER_REPO, TaskId.of("task-b"), TaskStatus.OPEN, List.of()),
                document(REPO, TaskId.of("task-a"), TaskStatus.DONE, List.of("task-b"))));
        service.observe(snapshot, slugs(new AtomicInteger()));
        assertEquals(1, createCalls.get());
        assertEquals("main", bases.get(), "a cross-repository prerequisite is no branch base");
        service.close();
    }

    @Test
    void anUnpushedBaseDefersTheAttempt() {
        AtomicInteger probeCalls = new AtomicInteger();
        AtomicInteger createCalls = new AtomicInteger();
        StackPrService service = service(directExecutor(), 60_000,
                countingProbe(probeCalls, Set.of("dd/task-b@{upstream}")),
                emptyViewPr(new AtomicInteger()),
                fixedDefaultBranch(new AtomicInteger(), "main"),
                createRecording(createCalls, new AtomicReference<>(), List.of()));
        TaskSnapshot snapshot = snapshot(List.of(
                document(REPO, TaskId.of("task-b"), TaskStatus.OPEN, List.of()),
                document(REPO, TaskId.of("task-a"), TaskStatus.DONE, List.of("task-b"))));
        assertDoesNotThrow(() -> service.observe(snapshot, slugs(new AtomicInteger())));
        assertEquals(0, createCalls.get(), "an unpushed base never opens a wrong-base PR");
        int afterFirstPass = probeCalls.get();
        service.observe(snapshot, slugs(new AtomicInteger()));
        assertEquals(afterFirstPass, probeCalls.get(),
                "the deferred attempt is backed off, not re-probed at once");
        service.close();
    }

    @Test
    void aTaskWithoutGraphNeverPaysForTheGraph() {
        AtomicInteger probeCalls = new AtomicInteger();
        AtomicInteger slugReads = new AtomicInteger();
        StackPrService service = service(directExecutor(), 60_000,
                countingProbe(probeCalls, Set.of("dd/task-a", "dd/task-a@{upstream}")),
                emptyViewPr(new AtomicInteger()),
                fixedDefaultBranch(new AtomicInteger(), "main"),
                createRecording(new AtomicInteger(), new AtomicReference<>(), List.of()));
        TaskSnapshot snapshot = snapshot(List.of(document(REPO, TaskId.of("task-a"),
                TaskStatus.DONE, List.of())));
        service.observe(snapshot, slugs(slugReads));
        assertEquals(1, probeCalls.get());
        assertEquals(0, slugReads.get(),
                "a non-stack task never builds the task graph (the slugs mapping is untouched)");
        service.close();
    }

    @Test
    void attemptsRunOnTheInjectedExecutor() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicInteger probeCalls = new AtomicInteger();
        AtomicReference<Thread> probeThread = new AtomicReference<>();
        CountDownLatch probed = new CountDownLatch(1);
        StackPrService service = service(executor, 60_000,
                (root, ref) -> {
                    if (probeCalls.incrementAndGet() == 1) {
                        probeThread.set(Thread.currentThread());
                        probed.countDown();
                    }
                    return Optional.of("abc123");
                },
                emptyViewPr(new AtomicInteger()),
                fixedDefaultBranch(new AtomicInteger(), "main"),
                createRecording(new AtomicInteger(), new AtomicReference<>(), List.of()));
        TaskSnapshot snapshot = snapshot(List.of(document(REPO, TaskId.of("task-a"),
                TaskStatus.DONE, List.of())));
        service.observe(snapshot, slugs(new AtomicInteger()));
        assertTrue(probed.await(5, TimeUnit.SECONDS), "the attempt body must run at all");
        assertNotEquals(Thread.currentThread(), probeThread.get(),
                "the probes and POST join run on the injected executor, never the caller's thread");
        service.close();
    }

    @Test
    void anExecutorRejectionLeavesTheTaskReconsiderable() {
        AtomicBoolean reject = new AtomicBoolean(true);
        AtomicInteger probeCalls = new AtomicInteger();
        StackPrService service = service(rejectingExecutor(reject), 60_000,
                countingProbe(probeCalls, Set.of("dd/task-a", "dd/task-a@{upstream}")),
                emptyViewPr(new AtomicInteger()),
                fixedDefaultBranch(new AtomicInteger(), "main"),
                createRecording(new AtomicInteger(), new AtomicReference<>(), List.of()));
        TaskSnapshot snapshot = snapshot(List.of(document(REPO, TaskId.of("task-a"),
                TaskStatus.DONE, List.of())));
        assertDoesNotThrow(() -> service.observe(snapshot, slugs(new AtomicInteger())),
                "a rejected dispatch never escapes observe");
        assertEquals(0, probeCalls.get());
        reject.set(false);
        service.observe(snapshot, slugs(new AtomicInteger()));
        assertEquals(1, probeCalls.get(),
                "the task stays reconsiderable: unmarked, with no blocking backoff deadline");
        service.close();
    }
}
