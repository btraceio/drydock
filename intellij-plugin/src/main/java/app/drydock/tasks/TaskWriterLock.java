package app.drydock.tasks;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounded two-level write lock for one task store root (T05a, D2/D6),
 * SharedProjectsFile's verified pattern. The JVM lock is taken first and
 * shared per absolute root, so two threads or two TaskStore instances of
 * one JVM never reach the OS lock and trip its overlapping-lock error; the
 * OS lock then serializes this JVM against any other Drydock process. It
 * sits on a sibling of the root ({@code <parent>/<root-name>.lock}),
 * never inside the root, where TaskFiles.scan would report it as a bad
 * root entry. Both waits are bounded: a queue that does not drain and a
 * foreign live lock each surface a distinct visible failure, never a
 * hang. No lock is held between calls; the queue drains when every
 * holder returns.
 */
final class TaskWriterLock {

    /** One JVM lock per absolute root, shared by every TaskStore on it. */
    private static final ConcurrentHashMap<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

    private final Path root;
    private final Path lockFile;
    private final long waitMillis;
    private volatile boolean closed;

    TaskWriterLock(Path root, long waitMillis) {
        if (root.getFileName() == null) {
            throw new IllegalArgumentException("Task store root must not be the file system root: " + root);
        }
        this.root = root;
        this.lockFile = root.resolveSibling(root.getFileName() + ".lock");
        this.waitMillis = waitMillis;
    }

    /** One serialized mutation, run under both locks on the caller's thread. */
    interface Action<T> {
        T run() throws IOException, TaskFormatException, TaskStore.TaskStoreException;
    }

    <T> T write(Action<T> action) throws TaskStore.TaskStoreException {
        if (closed) {
            throw new TaskStore.TaskStoreException("The task store writer on " + root + " is closed");
        }
        ReentrantLock queue = JVM_LOCKS.computeIfAbsent(root, ignored -> new ReentrantLock());
        if (!await(queue)) {
            throw new TaskStore.TaskStoreException(
                    "Timed out or was interrupted waiting for this Drydock's task writer on " + root);
        }
        try {
            Files.createDirectories(lockFile.getParent());
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
                    FileLock ignored = osLock(channel)) {
                return action.run();
            }
        } catch (TaskStore.TaskStoreException e) {
            throw e;
        } catch (IOException | TaskFormatException e) {
            throw new TaskStore.TaskStoreException("Task store operation on " + root + " failed", e);
        } finally {
            queue.unlock();
        }
    }

    /** Bounded drain of the queue: false when a mutation still holds it. */
    boolean drain() {
        ReentrantLock queue = JVM_LOCKS.get(root);
        if (queue == null) {
            return true;
        }
        boolean held = await(queue);
        if (held) {
            queue.unlock();
        }
        return held;
    }

    /** Stops accepting work, then drains bounded and silently; idempotent. */
    void close() {
        closed = true;
        drain();
    }

    /** tryLock with the bounded wait; an interrupt restores the flag and gives up. */
    private boolean await(ReentrantLock queue) {
        try {
            return queue.tryLock(waitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Bounded exclusive OS lock; a foreign live lock is a distinct visible failure. */
    private FileLock osLock(FileChannel channel) throws IOException, TaskStore.TaskStoreException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis);
        while (true) {
            FileLock lock = channel.tryLock();
            if (lock != null) {
                return lock;
            }
            if (System.nanoTime() - deadline >= 0) {
                throw new TaskStore.TaskStoreException(root + " is locked by another Drydock");
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TaskStore.TaskStoreException("Interrupted while waiting for " + lockFile);
            }
        }
    }
}
