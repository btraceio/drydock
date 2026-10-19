package app.drydock.projects;

import app.drydock.domain.RepositoryCatalog;
import app.drydock.state.json.JsonParseException;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * The registered-projects list shared by the standalone app and the
 * IntelliJ plugin: {@code ~/.drydock/projects.json}.
 *
 * <p><b>Why its own file.</b> Each app install keeps its whole state in a
 * {@code state.json} that exactly one process may write (see {@code
 * StateDirectory}: two writers on one state file wiped each other's
 * sessions). Only the project list is shared, so only the project list
 * lives here; everything else stays in each tool's own state.</p>
 *
 * <p><b>Two processes write it.</b> Every change is a short transaction
 * under an OS lock on a sibling {@code projects.json.lock}: lock, re-read,
 * apply, write a temp file, force it, atomically rename it over the list,
 * unlock. A change made by the other tool between this tool's read and its
 * write is therefore never lost. The OS lock is per process, and two
 * threads of one JVM taking it would fail with an overlapping-lock error,
 * so a JVM-wide lock per file is taken first.</p>
 *
 * <p><b>A malformed file is never overwritten.</b> It may be a hand edit in
 * progress or a newer tool's format; reads give an empty list and say so,
 * writes refuse with {@link SharedProjectsException}.</p>
 *
 * <p>Every method blocks on the file system: call from a background thread.</p>
 */
public final class SharedProjectsFile {

    private static final Logger LOG = System.getLogger(SharedProjectsFile.class.getName());

    /** How long a change waits for the other tool to release the file lock. */
    private static final long LOCK_WAIT_MILLIS = 5_000;
    private static final long LOCK_POLL_MILLIS = 50;

    private static final ConcurrentHashMap<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

    /**
     * One read of the file. {@code present} is false when no file exists;
     * {@code malformed} when it exists but cannot be decoded (then
     * {@code projects} is empty). {@code stamp} changes whenever the file
     * does, and is 0 when it is absent.
     */
    public record Snapshot(List<SharedProject> projects, boolean present, boolean malformed, long stamp) {
        public Snapshot {
            projects = List.copyOf(projects);
        }
    }

    private final Path file;
    private final Path lockFile;

    public SharedProjectsFile(Path file) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        this.lockFile = this.file.resolveSibling(this.file.getFileName() + ".lock");
    }

    /** Overrides {@link #defaultFile()}: a sandbox IDE or a test run keeps its own list. */
    public static final String OVERRIDE_PROPERTY = "drydock.projectsFile";

    /**
     * {@code ~/.drydock/projects.json}, beside the shared {@code config.json},
     * unless the {@value #OVERRIDE_PROPERTY} system property names another
     * file.
     */
    public static Path defaultFile() {
        String override = System.getProperty(OVERRIDE_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return Path.of(System.getProperty("user.home"), ".drydock", "projects.json");
    }

    public Path file() {
        return file;
    }

    public Snapshot read() {
        long stamp;
        String text;
        try {
            stamp = stampOf(file);
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return new Snapshot(List.of(), false, false, 0L);
        } catch (IOException e) {
            LOG.log(Level.WARNING, () -> "Could not read " + file + ": " + e);
            return new Snapshot(List.of(), true, true, 0L);
        }
        try {
            return new Snapshot(SharedProjectsCodec.fromJson(text), true, false, stamp);
        } catch (JsonParseException e) {
            LOG.log(Level.WARNING, () -> file + " is malformed and will not be changed until it is fixed: "
                    + e.getMessage());
            return new Snapshot(List.of(), true, true, stamp);
        }
    }

    /**
     * Applies {@code change} to the current list and writes the result,
     * de-duplicated by canonical root (the first entry wins). Writes nothing
     * when the result equals what is there. Returns the list as written.
     */
    public List<SharedProject> update(UnaryOperator<List<SharedProject>> change) throws SharedProjectsException {
        return locked(() -> {
            Snapshot current = read();
            if (current.malformed()) {
                throw new SharedProjectsException(file + " is malformed; fix or delete it before changing projects");
            }
            List<SharedProject> next = distinctByRoot(change.apply(new ArrayList<>(current.projects())));
            if (!current.present() || !next.equals(current.projects())) {
                write(next);
            }
            return next;
        });
    }

    /**
     * Writes {@code seed} as the list when no file exists yet; returns
     * whether it did. The supplier runs only in that case, under the lock,
     * so two tools starting together seed once.
     */
    public boolean seedIfAbsent(Supplier<List<SharedProject>> seed) throws SharedProjectsException {
        return locked(() -> {
            if (Files.exists(file)) {
                return false;
            }
            write(distinctByRoot(seed.get()));
            return true;
        });
    }

    private interface Transaction<T> {
        T run() throws SharedProjectsException;
    }

    private <T> T locked(Transaction<T> transaction) throws SharedProjectsException {
        ReentrantLock jvmLock = JVM_LOCKS.computeIfAbsent(file, ignored -> new ReentrantLock());
        jvmLock.lock();
        try {
            Files.createDirectories(file.getParent());
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock ignored = acquire(channel)) {
                return transaction.run();
            }
        } catch (IOException e) {
            throw new SharedProjectsException("Could not update " + file + ": " + e.getMessage(), e);
        } finally {
            jvmLock.unlock();
        }
    }

    private FileLock acquire(FileChannel channel) throws IOException, SharedProjectsException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LOCK_WAIT_MILLIS);
        while (true) {
            FileLock lock = channel.tryLock();
            if (lock != null) {
                return lock;
            }
            if (System.nanoTime() > deadline) {
                throw new SharedProjectsException(file.getFileName() + " is locked by another Drydock");
            }
            try {
                Thread.sleep(LOCK_POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SharedProjectsException("Interrupted while waiting for " + lockFile);
            }
        }
    }

    private void write(List<SharedProject> projects) throws SharedProjectsException {
        byte[] bytes = SharedProjectsCodec.toJson(projects).getBytes(StandardCharsets.UTF_8);
        Path temp = null;
        try {
            temp = Files.createTempFile(file.getParent(), "projects", ".json.tmp");
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // the temp file is harmless; the failure below is what matters
                }
            }
            throw new SharedProjectsException("Could not write " + file + ": " + e.getMessage(), e);
        }
    }

    private static List<SharedProject> distinctByRoot(List<SharedProject> projects) {
        Set<Path> seen = new HashSet<>();
        List<SharedProject> distinct = new ArrayList<>();
        for (SharedProject project : projects) {
            if (seen.add(RepositoryCatalog.canonicalize(project.root()))) {
                distinct.add(project);
            }
        }
        return distinct;
    }

    private static long stampOf(Path path) throws IOException {
        return Files.getLastModifiedTime(path).to(TimeUnit.NANOSECONDS) ^ (Files.size(path) * 31);
    }
}
