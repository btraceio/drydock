package app.drydock.babysit;

import app.drydock.domain.ManagedSessionId;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The two long-held OS file locks of detached babysitting, beside the
 * babysitting state file. Unlike {@link BabysitStateFile}'s transaction
 * lock (held for one read-modify-write), these are held for as long as what
 * they stand for lasts, and the OS releases them when the holding process
 * dies -- so "is it held" is a liveness answer no stale pid can fake.
 *
 * <ul>
 *   <li>{@link #watcherLockFile}: held by the detached watcher for its whole
 *       life. Held means a watcher is alive; free means none is, whatever
 *       owner marker the state file still carries.</li>
 *   <li>{@link #runLockFile}: held by the watcher while one headless run of
 *       a babysitter's conversation is in flight. The in-IDE monitor checks
 *       it before it resumes that babysitter, so the two never run one
 *       conversation at once.</li>
 * </ul>
 *
 * <p>A lock already held by THIS JVM counts as held, and is answered from a
 * JVM-wide set without opening the file at all: on POSIX the OS lock is per
 * process, and closing any channel of a locked file can release the lock
 * another channel of the same JVM holds -- a probe must never do that to
 * the watcher's own lock. Every method blocks on the file system.</p>
 */
public final class BabysitLocks {

    private static final Logger LOG = System.getLogger(BabysitLocks.class.getName());

    /** The locks this JVM holds, by canonical path. */
    private static final Set<Path> HELD = ConcurrentHashMap.newKeySet();

    private BabysitLocks() {
    }

    /** A held lock; closing releases it. */
    public static final class Held implements AutoCloseable {
        private final Path key;
        private final FileChannel channel;
        private final FileLock lock;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Held(Path key, FileChannel channel, FileLock lock) {
            this.key = key;
            this.channel = channel;
            this.lock = lock;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                lock.release();
            } catch (IOException e) {
                LOG.log(Level.DEBUG, () -> "Releasing a babysitting lock failed: " + e);
            }
            try {
                channel.close();
            } catch (IOException e) {
                LOG.log(Level.DEBUG, () -> "Closing a babysitting lock failed: " + e);
            }
            HELD.remove(key);
        }
    }

    /** {@code <baseDir>/babysit/babysit-watcher.lock}. */
    public static Path watcherLockFile(Path baseDir) {
        return BabysitStateFile.defaultFile(baseDir).resolveSibling("babysit-watcher.lock");
    }

    /** {@code <baseDir>/babysit/run-<babysitterId>.lock}. */
    public static Path runLockFile(Path baseDir, ManagedSessionId babysitterId) {
        return BabysitStateFile.defaultFile(baseDir).resolveSibling("run-" + babysitterId + ".lock");
    }

    /** The lock on {@code file} when it is free now; empty when anyone (this JVM included) holds it. */
    public static Optional<Held> tryLock(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path key = BabysitStateFile.lockKey(file);
        if (!HELD.add(key)) {
            return Optional.empty();
        }
        FileChannel channel = null;
        try {
            channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = channel.tryLock();
            if (lock != null) {
                return Optional.of(new Held(key, channel, lock));
            }
        } catch (OverlappingFileLockException e) {
            // held by this JVM outside this class; held all the same
        } catch (IOException | RuntimeException e) {
            closeQuietly(channel);
            HELD.remove(key);
            throw e;
        }
        closeQuietly(channel);
        HELD.remove(key);
        return Optional.empty();
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException e) {
                LOG.log(Level.DEBUG, () -> "Closing a babysitting lock file failed: " + e);
            }
        }
    }

    /**
     * Whether someone holds the lock on {@code file} right now: it is taken
     * and released at once when free. An I/O failure answers held -- the
     * caller then waits rather than racing.
     */
    public static boolean isHeld(Path file) {
        try {
            Optional<Held> held = tryLock(file);
            held.ifPresent(Held::close);
            return held.isEmpty();
        } catch (IOException e) {
            LOG.log(Level.WARNING, () -> "Could not probe the babysitting lock " + file + ": " + e);
            return true;
        }
    }
}
