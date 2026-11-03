package app.drydock.tasks;

import app.drydock.domain.RepositoryId;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The single writer for the per-user task store root (T05a, D2): every
 * mutation serializes through one root-owned queue ({@link TaskWriterLock}
 * -- JVM lock first, then the OS lock), so no caller ever load-then-saves
 * on its own; two independent load/save cycles are a documented
 * data-loss bug. This slice owns the create path; field updates and note
 * appends arrive in T05b, the committed test matrix in T05c.
 *
 * <p><b>Fresh read under the lock.</b> Each mutation reads the store from
 * disk inside its lock section and never patches a cached parse, so an
 * edit made between two mutations cannot be lost and no mutation ever
 * builds on stale bytes.
 *
 * <p><b>Cost, stated honestly.</b> One create is one O(store) scan (id
 * uniqueness across ALL repositories, unreadable files and duplicate-id
 * collisions included), O(new file) rendering, one temp file + fsync +
 * atomic move, and one more O(store) publish scan. Incremental
 * verification is a possible future optimization, not implemented here.
 */
public final class TaskStore implements AutoCloseable {

    private static final Logger LOG = System.getLogger(TaskStore.class.getName());

    private static final int MAX_CREATE_ATTEMPTS = 64;
    private static final int SLUG_CAP = 60;
    private static final String SUFFIX_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Source of the random 4-char id suffix; package-private so tests can pin it. */
    static Supplier<String> suffixSource = () -> {
        StringBuilder out = new StringBuilder(4);
        for (int i = 0; i < 4; i++) {
            out.append(SUFFIX_ALPHABET.charAt(RANDOM.nextInt(SUFFIX_ALPHABET.length())));
        }
        return out.toString();
    };

    /**
     * The platform seam for unsaved in-editor content: the platform layer
     * will later answer for real open documents (T13); the core default is
     * {@link #NO_CONFLICTS}. Consulted under the lock, before any write.
     */
    public interface UnsavedContentGuard {
        boolean hasUnsavedContent(Path file);
    }

    /** The core default: no file ever has unsaved in-editor content. */
    public static final UnsavedContentGuard NO_CONFLICTS = file -> false;

    /** One visible, checked store failure: closed writer, lock timeout, foreign lock, conflict or failed write. */
    public static class TaskStoreException extends Exception {
        public TaskStoreException(String message) {
            super(message);
        }

        public TaskStoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final Path root;
    private final TaskWriterLock lock;
    private final UnsavedContentGuard guard;
    private final CopyOnWriteArrayList<Consumer<TaskSnapshot>> listeners = new CopyOnWriteArrayList<>();

    public TaskStore(Path root) {
        this(root, NO_CONFLICTS);
    }

    public TaskStore(Path root, UnsavedContentGuard guard) {
        this(root, guard, 5_000); // as long as SharedProjectsFile waits
    }

    TaskStore(Path root, UnsavedContentGuard guard, long lockWaitMillis) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.guard = Objects.requireNonNull(guard, "guard");
        this.lock = new TaskWriterLock(this.root, lockWaitMillis);
    }

    /**
     * Creates one task and returns its committed document. The id is the
     * title slug plus a short random suffix (D4), minted under the lock
     * against a fresh {@link TaskFiles#scan}: a live document, a duplicate
     * collision or an unreadable file with that name, in ANY repository,
     * all make the id taken, with bounded retries on a fresh suffix. The
     * file is rendered by {@link TaskFilePatch#newFile} and installed by
     * temp file + fsync + atomic move. A failed create publishes nothing
     * and leaves the store untouched; a successful one re-scans under the
     * same lock and hands the committed snapshot to every listener (if
     * that re-scan itself fails, the task stays committed but nothing is
     * published and the failure is logged).
     */
    public TaskDocument create(RepositoryId repository, String title, List<String> sessions)
            throws TaskStoreException {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(sessions, "sessions");
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Task title is required (P1)");
        }
        List<String> ownSessions = List.copyOf(sessions);
        Committed committed = lock.write(() -> {
            TaskId id = freeId(title, TaskFiles.scan(root));
            TaskDocument document = new TaskDocument(id, repository, title, TaskStatus.OPEN, DoneBy.HUMAN,
                    Optional.of(Instant.now()), List.of(), ownSessions, "", "");
            Path target = root.resolve(repository.toString()).resolve(id.text() + ".md");
            if (guard.hasUnsavedContent(target)) {
                throw new TaskStoreException(target + " has unsaved changes in the editor; "
                        + "save or close it before changing the task");
            }
            writeAtomically(target, TaskFilePatch.newFile(document));
            try {
                return new Committed(document, TaskFiles.scan(root));
            } catch (IOException e) {
                LOG.log(Level.WARNING,
                        () -> "A task file was written but " + root + " could not be re-scanned: " + e);
                return new Committed(document, null);
            }
        });
        if (committed.snapshot() != null) {
            publish(committed.snapshot());
        }
        return committed.document();
    }

    /** Registers a snapshot listener; the returned runnable unsubscribes it. */
    public Runnable addListener(Consumer<TaskSnapshot> listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** Bounded drain of the write queue; a queue that does not drain is a visible failure. */
    public void flush() throws TaskStoreException {
        if (!lock.drain()) {
            throw new TaskStoreException(
                    "Timed out or was interrupted waiting for this Drydock's task writer on " + root);
        }
    }

    /** Stops accepting work, drains bounded and silently, never throws; idempotent. */
    @Override
    public void close() {
        lock.close();
    }

    private record Committed(TaskDocument document, TaskSnapshot snapshot) {
    }

    /**
     * One id no file claims anywhere in the store, or a visible failure
     * after bounded retries. The snapshot is the caller's fresh in-lock
     * scan, which settles every retry because nobody else can write while
     * the lock is held. An id is taken when a live document, a duplicate
     * collision, or an unreadable file with that name exists in ANY
     * repository -- even a file that never parsed claims its stem.
     */
    private TaskId freeId(String title, TaskSnapshot store) throws TaskStoreException {
        String slug = slug(title);
        for (int attempt = 0; attempt < MAX_CREATE_ATTEMPTS; attempt++) {
            TaskId id = TaskId.of(slug + "-" + suffixSource.get());
            String name = id.text() + ".md";
            if (!store.byId().containsKey(id)
                    && store.idCollisions().stream().noneMatch(c -> c.id().equals(id))
                    && store.failures().stream().noneMatch(f -> f.path().getFileName().toString().equals(name))) {
                return id;
            }
        }
        throw new TaskStoreException(
                "No free task id for this title in " + root + " after " + MAX_CREATE_ATTEMPTS + " attempts");
    }

    /** The title slug (D4): lowercase letters and digits, inner hyphens, capped. */
    private static String slug(String title) {
        StringBuilder out = new StringBuilder(SLUG_CAP + 1);
        for (int i = 0; i < title.length() && out.length() < SLUG_CAP; i++) {
            char c = Character.toLowerCase(title.charAt(i));
            if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9') {
                out.append(c);
            } else if (out.length() > 0 && out.charAt(out.length() - 1) != '-') {
                out.append('-');
            }
        }
        int length = out.length();
        if (length > 0 && out.charAt(length - 1) == '-') {
            out.setLength(length - 1);
        }
        return out.isEmpty() ? "task" : out.toString();
    }

    /** A temp file in the target's directory, fsynced, then atomically moved over it. */
    private static void writeAtomically(Path target, String text) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), "task", ".md.tmp");
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true); // fsync before the move: the rename publishes durable bytes
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) { // the temp file is harmless; the failure below is what matters
            }
            throw e;
        }
    }

    /** A listener bug never stops the store or the remaining listeners. */
    private void publish(TaskSnapshot snapshot) {
        for (Consumer<TaskSnapshot> listener : listeners) {
            try {
                listener.accept(snapshot);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, () -> "Task snapshot listener failed: " + e);
            }
        }
    }
}
