package app.drydock.tasks;

import app.drydock.domain.RepositoryId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The concurrency half of the T05c task-store acceptance matrix: concurrent
 * creates from many threads, two {@link TaskStore} instances interleaving
 * on one root, the bounded queue wait, and flush/close under an in-flight
 * mutation. Real files under {@code @TempDir}, no mocking framework; the
 * slow guards are hand-written {@code UnsavedContentGuard} implementations
 * that park on test-owned latches, so every assertion is deterministic
 * (no sleep-based hopes). Not covered here: the OS-level "locked by
 * another Drydock" refusal -- the per-root JVM lock keeps in-JVM callers
 * off the OS lock, so that branch is only reachable from a second process
 * (the multi-process matrix is T43).
 */
class TaskStoreConcurrencyTest {

    private static final String REPO_A = "11111111-1111-1111-1111-111111111111";

    @TempDir
    Path root;

    private TaskStore store() {
        return new TaskStore(root);
    }

    private TaskDocument parse(String repo, TaskId id) throws IOException, TaskFormatException {
        return TaskFileReader.parse(id, RepositoryId.of(repo),
                Files.readString(root.resolve(repo).resolve(id.text() + ".md"), StandardCharsets.UTF_8), null)
                .document();
    }

    /** A status-only update; every other Updates field stays null (unchanged). */
    private static TaskFilePatch.Updates status(TaskStatus status) {
        return new TaskFilePatch.Updates(null, status, null, null, null, null);
    }

    /** Bounded latch wait; an interrupt restores the flag instead of hanging. */
    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * A test-local guard that parks inside the lock section once the
     * existing slow-* file is consulted: the mutation then HOLDS the JVM
     * write queue until the test releases it.
     */
    private static TaskStore.UnsavedContentGuard parkingGuard(CountDownLatch consulted, CountDownLatch release) {
        return file -> {
            if (file.getFileName().toString().startsWith("slow-") && Files.exists(file)) {
                consulted.countDown();
                await(release);
            }
            return false;
        };
    }

    /** One checked-failing store call as a future; failures surface at get(). */
    private static <T> CompletableFuture<T> async(ExecutorService pool, StoreCall<T> call) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return call.run();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, pool);
    }

    @FunctionalInterface
    private interface StoreCall<T> {
        T run() throws Exception;
    }

    /** 1: concurrent creates from many threads all commit with distinct, valid ids. */
    @Test
    void concurrentCreatesCommitDistinctIds() throws Exception {
        TaskStore store = store();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<CompletableFuture<TaskDocument>> futures = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            futures.add(async(pool, () -> store.create(RepositoryId.of(REPO_A), "Same title", List.of())));
        }
        Set<TaskId> ids = new HashSet<>();
        for (CompletableFuture<TaskDocument> future : futures) {
            ids.add(future.get(30, TimeUnit.SECONDS).id());
        }
        pool.shutdown();
        assertEquals(16, ids.size(), "distinct ids");
        assertEquals(ids, TaskFiles.scan(root).byId().keySet(), "scan sees every created task");
        for (TaskId id : ids) {
            assertEquals("Same title", parse(REPO_A, id).title(), "every file parses via TaskFileReader");
        }
        store.close();
    }

    /** 6a: two TaskStore instances on one root interleave concurrent creates safely. */
    @Test
    void twoStoresOnOneRootInterleaveConcurrentCreates() throws Exception {
        TaskStore first = store();
        TaskStore second = store();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<CompletableFuture<TaskDocument>> futures = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            TaskStore instance = i % 2 == 0 ? first : second;
            futures.add(async(pool, () -> instance.create(RepositoryId.of(REPO_A), "Shared title", List.of())));
        }
        Set<TaskId> ids = new HashSet<>();
        for (CompletableFuture<TaskDocument> future : futures) {
            ids.add(future.get(30, TimeUnit.SECONDS).id());
        }
        pool.shutdown();
        assertEquals(12, ids.size(), "distinct ids across both instances");
        assertEquals(ids, TaskFiles.scan(root).byId().keySet());
        first.close();
        second.close();
    }

    /** 6b: the bounded queue wait -- a held queue fails flush visibly, then drains. */
    @Test
    void flushTimesOutBoundedBehindHeldQueue() throws Exception {
        CountDownLatch consulted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TaskStore store = new TaskStore(root, parkingGuard(consulted, release), 200);
        TaskDocument slowTask = store.create(RepositoryId.of(REPO_A), "Slow task", List.of());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CompletableFuture<TaskDocument> slow =
                async(pool, () -> store.update(slowTask.id(), status(TaskStatus.DONE)));
        assertTrue(consulted.await(5, TimeUnit.SECONDS), "the mutation holds the queue inside its guard");
        TaskStore.TaskStoreException timeout = assertThrows(TaskStore.TaskStoreException.class, store::flush);
        assertTrue(timeout.getMessage().contains("Timed out or was interrupted"));
        release.countDown();
        slow.get(5, TimeUnit.SECONDS);
        store.flush();
        assertEquals(TaskStatus.DONE, parse(REPO_A, slowTask.id()).status());
        pool.shutdown();
        store.close();
    }

    /** 7: flush drains behind an in-flight mutation; close stops accepting work, idempotently. */
    @Test
    void flushDrainsBehindInFlightMutationThenCloseRefuses() throws Exception {
        CountDownLatch consulted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger publishes = new AtomicInteger();
        TaskStore store = new TaskStore(root, parkingGuard(consulted, release));
        store.addListener(snapshot -> publishes.incrementAndGet());
        TaskDocument slowTask = store.create(RepositoryId.of(REPO_A), "Slow drain task", List.of());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CompletableFuture<TaskDocument> note =
                async(pool, () -> store.appendNote(slowTask.id(), "noted while the writer was held"));
        assertTrue(consulted.await(5, TimeUnit.SECONDS), "the mutation holds the queue inside its guard");
        CompletableFuture<Void> flushed = CompletableFuture.runAsync(() -> {
            try {
                store.flush();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertFalse(flushed.isDone(), "flush cannot return while the mutation holds the queue");
        release.countDown();
        flushed.get(5, TimeUnit.SECONDS);
        note.get(5, TimeUnit.SECONDS);
        assertTrue(parse(REPO_A, slowTask.id()).rawBody().contains("noted while the writer was held"),
                "the held mutation committed before flush returned");
        assertTrue(publishes.get() >= 2, "create and the drained mutation both published");
        pool.shutdown();
        store.close();
        store.close();
        TaskStore.TaskStoreException refused = assertThrows(TaskStore.TaskStoreException.class,
                () -> store.create(RepositoryId.of(REPO_A), "Too late", List.of()));
        assertTrue(refused.getMessage().contains("closed"));
        assertThrows(TaskStore.TaskStoreException.class, () -> store.update(slowTask.id(), status(TaskStatus.DONE)));
        assertThrows(TaskStore.TaskStoreException.class, () -> store.appendNote(slowTask.id(), "too late"));
    }
}
