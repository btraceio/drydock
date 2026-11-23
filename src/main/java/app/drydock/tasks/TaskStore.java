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
import java.util.ArrayList;
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
 * data-loss bug. Creates, known-field updates and note appends all
 * ride this path (T05b); the committed test matrix arrives in T05c.
 *
 * <p><b>Fresh read under the lock.</b> Each mutation reads the store from
 * disk inside its lock section and never patches a cached parse, so an
 * edit made between two mutations cannot be lost and no mutation ever
 * builds on stale bytes.
 *
 * <p><b>Cost, stated honestly.</b> One create is one O(store) scan (id
 * uniqueness across ALL repositories, unreadable files and duplicate-id
 * collisions included), O(new file) rendering, one temp file + fsync +
 * atomic move, and one more O(store) publish scan. One update or note
 * append is one O(store) locate scan, one O(file) fresh read and parse,
 * the O(file) patch with its reparse, one temp file + fsync + atomic
 * move, and one more O(store) publish scan -- two O(store) scans per
 * mutation, exactly like create. Incremental verification is a possible
 * future optimization, not implemented here.
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
    private volatile boolean closed;
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
        Committed committed = write(() -> {
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

    /**
     * Applies front-matter updates to one existing task and returns its
     * committed document (T05b, D6/P1). The task is located by a fresh
     * in-lock {@link TaskFiles#scan} across every repository: an id no
     * live document carries -- absent, or claimed only by a broken file
     * or a duplicate -- is a visible checked failure, never a silent
     * no-op. The target file is then read from disk under the same lock
     * (never the scan's cached parse) and rewritten through
     * {@link TaskFilePatch#patch}: only the known-key spans whose values
     * change are touched, so unknown keys, comments and line endings
     * survive byte for byte, and the result replaces the file by temp +
     * fsync + atomic move. A failed update publishes nothing and leaves
     * the file untouched; a successful one re-scans under the same lock
     * and hands the committed snapshot to every listener exactly as
     * create does.
     *
     * <p>An update that moves the task to {@link TaskStatus#DONE} from any
     * other status, as read from the file inside the lock, also writes
     * {@code done_at} as the current instant, unless the caller set
     * {@code doneAt} itself. An update that leaves the status as it is
     * does not touch {@code done_at}.</p>
     */
    public TaskDocument update(TaskId id, TaskFilePatch.Updates updates) throws TaskStoreException {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(updates, "updates");
        Committed committed = write(
                () -> mutate(id, (parsed, source) -> TaskFilePatch.patch(parsed, source,
                        stampDone(parsed.document(), updates, Instant.now()))));
        if (committed.snapshot() != null) {
            publish(committed.snapshot());
        }
        return committed.document();
    }

    /**
     * {@code updates}, with {@code done_at} set to {@code now} when they move
     * {@code current} to {@link TaskStatus#DONE} and name no {@code doneAt}
     * of their own. Pure.
     */
    static TaskFilePatch.Updates stampDone(TaskDocument current, TaskFilePatch.Updates updates, Instant now) {
        boolean becomesDone = updates.status() == TaskStatus.DONE && current.status() != TaskStatus.DONE;
        return becomesDone && updates.doneAt() == null ? updates.withDoneAt(now) : updates;
    }

    /**
     * Appends one session note under the task's {@code ## Session notes}
     * heading (T05b, P7) and returns the committed document. The same
     * single-writer path as {@link #update}: fresh in-lock locate, fresh
     * file read, {@link TaskFilePatch#appendNote}'s pure body insertion
     * (front matter and every existing body byte untouched), atomic
     * replace, publish of the committed snapshot. A blank note is
     * refused visibly before any lock is taken; TaskFilePatch's own
     * refusals (embedded line breaks, for one) surface as the store's
     * checked failure.
     */
    public TaskDocument appendNote(TaskId id, String note) throws TaskStoreException {
        Objects.requireNonNull(id, "id");
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("Session note must not be blank (P7)");
        }
        Committed committed = write(
                () -> mutate(id, (parsed, source) -> TaskFilePatch.appendNote(source, note)));
        if (committed.snapshot() != null) {
            publish(committed.snapshot());
        }
        return committed.document();
    }

    /**
     * Records one more mirror of a task: {@code url} is appended to the
     * task's {@code mirrors} list unless it is already there. The list is
     * read from the file inside the writer lock, not from a caller's
     * snapshot, so two mirrors recorded back to back both survive. Same
     * single-writer path, guard and publish as {@link #update}; when the
     * address is already recorded the file's text stays exactly as it was.
     */
    public TaskDocument addMirror(TaskId id, String url) throws TaskStoreException {
        Objects.requireNonNull(id, "id");
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("A mirror address must not be blank");
        }
        Committed committed = write(() -> mutate(id, (parsed, source) -> {
            List<String> mirrors = parsed.document().mirrors();
            if (mirrors.contains(url)) {
                return source;
            }
            List<String> extended = new ArrayList<>(mirrors);
            extended.add(url);
            return TaskFilePatch.patch(parsed, source,
                    new TaskFilePatch.Updates(null, null, null, null, null, null, extended));
        }));
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

    /** Stops accepting work, drains bounded and silently, never throws; idempotent.
     * The refusal is re-checked inside the lock section too (see
     * {@link #write}), so a mutation that raced past the entry check
     * before this call still fails visibly after the drain instead of
     * running on a closed writer. */
    @Override
    public void close() {
        closed = true;
        lock.close();
    }

    private record Committed(TaskDocument document, TaskSnapshot snapshot) {
    }

    /**
     * One serialized mutation with the close-window re-check: the entry
     * check in {@link TaskWriterLock#write} runs before the queue is
     * acquired, so a mutation that passed it can still be parked behind
     * {@link #close()}'s drain. The re-check here runs after the queue
     * (and the OS lock) is held, closing that window: the parked
     * mutation fails visibly instead of writing after close.
     */
    private <T> T write(TaskWriterLock.Action<T> action) throws TaskStoreException {
        return lock.write(() -> {
            if (closed) {
                throw new TaskStoreException("The task store writer on " + root + " is closed");
            }
            return action.run();
        });
    }

    /** One pure rewrite of a freshly read task file: patch or note append, no I/O. */
    private interface Mutation {
        String rewrite(TaskFileReader.Parsed parsed, String source) throws TaskFormatException;
    }

    /**
     * The shared mutation body, always run inside {@link #write}: locate
     * the task on a fresh scan, consult the guard, read the file's bytes
     * from disk (never the scan's parse), rewrite, reparse the result --
     * a text that will not parse is refused BEFORE any write -- replace
     * atomically, and re-scan for the committed snapshot. Cost: two
     * O(store) scans (locate + publish) plus O(file) work between them.
     */
    private Committed mutate(TaskId id, Mutation mutation)
            throws IOException, TaskFormatException, TaskStoreException {
        TaskDocument current = locate(id, TaskFiles.scan(root));
        Path target = root.resolve(current.repository().toString()).resolve(id.text() + ".md");
        if (guard.hasUnsavedContent(target)) {
            throw new TaskStoreException(target + " has unsaved changes in the editor; "
                    + "save or close it before changing the task");
        }
        String source = Files.readString(target, StandardCharsets.UTF_8);
        String updated = mutation.rewrite(
                TaskFileReader.parse(id, current.repository(), source, null), source);
        TaskDocument document = TaskFileReader
                .parse(id, current.repository(), updated, current.created().orElse(null)).document();
        writeAtomically(target, updated);
        try {
            return new Committed(document, TaskFiles.scan(root));
        } catch (IOException e) {
            LOG.log(Level.WARNING,
                    () -> "A task file was written but " + root + " could not be re-scanned: " + e);
            return new Committed(document, null);
        }
    }

    /**
     * The live document with this id on the caller's fresh in-lock scan,
     * or a visible checked failure. A file that exists but does not
     * parse, and a duplicate id, are refused with their own reasons --
     * neither is "missing", and neither is ever a silent winner (D4).
     */
    private TaskDocument locate(TaskId id, TaskSnapshot store) throws TaskStoreException {
        TaskDocument document = store.byId().get(id);
        if (document != null) {
            return document;
        }
        if (store.idCollisions().stream().anyMatch(c -> c.id().equals(id))) {
            throw new TaskStoreException("Task id '" + id.text() + "' is claimed by more than one file in "
                    + root + " (D4); remove the duplicate before changing the task");
        }
        String name = id.text() + ".md";
        Optional<TaskSnapshot.FileFailure> broken = store.failures().stream()
                .filter(failure -> failure.path().getFileName().toString().equals(name)).findFirst();
        if (broken.isPresent()) {
            throw new TaskStoreException("Task file " + broken.get().path() + " cannot be read: "
                    + broken.get().failure().getMessage());
        }
        throw new TaskStoreException("No task with id '" + id.text() + "' in " + root);
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
