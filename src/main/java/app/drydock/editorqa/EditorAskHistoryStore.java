package app.drydock.editorqa;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The durable editor-ask history: one partition file per canonical checkout
 * root under {@code <baseDir>/editor-asks/<SHA-256 hex of root>.json}. It is
 * deliberately separate from {@code state.json} and the annotation store:
 * the catalog's sole updater stays {@code ApplicationStateStore}, and a bad
 * history file can never take the catalog down with it.
 *
 * <p><b>Threading.</b> Every method that touches disk ({@link #load},
 * the first call per root of any other method, and the futures'
 * completion) does blocking file I/O: call from a background thread, never
 * the EDT. The mutators return a future that completes <em>after the write
 * has landed</em> (or fails), so a caller that must be durable before an
 * irreversible send simply waits for it off the EDT.</p>
 *
 * <p><b>One writer per partition.</b> Mutations apply to the in-memory
 * partition under its monitor, bump a revision and encode the snapshot
 * there; the encoded bytes are then written by a task holding the
 * partition's write lock. A task whose revision is not newer than the last
 * revision that landed is dropped as superseded, so a delayed older
 * snapshot can never overwrite a newer one. The revision only counts as
 * landed after a successful atomic replace, so a failed write is never
 * reported as success and never recorded as written.</p>
 *
 * <p><b>Failure isolation.</b> A partition that is corrupt, over a bound,
 * of another version or stored for another root is marked bad and reported
 * by {@link #problem}; mutations on it are refused. It is never rewritten,
 * wiped or rerouted to another file, and other roots are unaffected.</p>
 *
 * <p><b>Retention</b> (per checkout, applied after every mutation): at most
 * {@value EditorAskLimits#MAX_UNFINISHED_RECORDS} unfinished records --
 * admission beyond that is refused, never dropped -- and the oldest
 * <em>settled</em> records by acceptance sequence beyond
 * {@value EditorAskLimits#MAX_SETTLED_RECORDS} are evicted, except those
 * the {@link #setActiveProtection active-turn predicate} protects.</p>
 */
public final class EditorAskHistoryStore implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(EditorAskHistoryStore.class.getName());

    static final String DIRECTORY = "editor-asks";

    /** Reason stored on records an earlier IDE run left unfinished. */
    static final String INTERRUPTED_REASON =
            "Interrupted when the IDE closed before an answer arrived; it may have reached the agent. "
                    + "Ask again to retry (a retry is a new question).";

    /** What went wrong, for a caller that must pick a recovery. */
    public enum Kind {
        /** The partition is unreadable or mismatched; it is left untouched. */
        BAD_PARTITION,
        /** Admission refused: the checkout already has the maximum unfinished records. */
        OVERFLOW,
        DUPLICATE_ID,
        /** Admission refused: sequence is not above the last admitted one. */
        SEQUENCE_NOT_INCREASING,
        UNKNOWN_ID,
        /** The requested change would rewrite immutable identity, content or a settled outcome. */
        ILLEGAL_UPDATE,
        /** The write did not land. The change stays in memory; see {@link #retrySave}. */
        WRITE_FAILED,
        CLOSED
    }

    /** A refused or failed store operation. */
    public static final class StoreException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final Kind kind;

        StoreException(Kind kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }

        public Kind kind() {
            return kind;
        }
    }

    /** The file-writing seam; the default is {@link #writeAtomically}. */
    @FunctionalInterface
    public interface PartitionWriter {
        void write(Path target, byte[] bytes) throws IOException;
    }

    /** A loaded partition: its records ascending by sequence, and the problem if it is bad. */
    public record LoadResult(List<EditorAskRecord> records, long lastSequence, String problem, boolean interruptionSaveFailed) {
        public LoadResult {
            records = List.copyOf(records);
        }

        public boolean ok() {
            return problem == null;
        }
    }

    private static final class Partition {
        final String root;
        final Path file;
        final ReentrantLock writeLock = new ReentrantLock();
        // Guarded by `this`:
        boolean loaded;
        String problem;
        List<EditorAskRecord> records = new ArrayList<>();
        long lastSequence = -1;
        long revision;
        // Guarded by writeLock:
        long writtenRevision;
        // Guarded by `this`; the latest unlanded failure, cleared on a landed write.
        volatile IOException saveFailure;
        volatile byte[] latestBytes;
        volatile long latestRevision;

        Partition(String root, Path file) {
            this.root = root;
            this.file = file;
        }
    }

    private final Path directory;
    private final Executor writerExecutor;
    private final ExecutorService ownedExecutor;
    private final PartitionWriter writer;
    private final Map<String, Partition> partitions = new ConcurrentHashMap<>();
    private final Set<CompletableFuture<Void>> inFlight = ConcurrentHashMap.newKeySet();
    private volatile Predicate<String> activeProtection = id -> false;
    private volatile boolean closed;

    /** Production constructor: partitions under {@code baseDir/editor-asks}. */
    public EditorAskHistoryStore(Path baseDir) {
        this(baseDir, null, EditorAskHistoryStore::writeAtomically);
    }

    /** Test seam: a custom writer executor (null = own virtual-thread executor) and file writer. */
    EditorAskHistoryStore(Path baseDir, Executor executor, PartitionWriter writer) {
        this.directory = baseDir.resolve(DIRECTORY);
        if (executor == null) {
            this.ownedExecutor = Executors.newVirtualThreadPerTaskExecutor();
            this.writerExecutor = ownedExecutor;
        } else {
            this.ownedExecutor = null;
            this.writerExecutor = executor;
        }
        this.writer = writer;
    }

    /** The partition file for a canonical root: a SHA-256 hex name, never derived from path text. */
    public Path partitionFile(String root) {
        EditorAskLimits.checkRoot(root);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(root.getBytes(StandardCharsets.UTF_8));
            return directory.resolve(HexFormat.of().formatHex(digest) + ".json");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Declares which records are still driving a live turn even though
     * settled (an answered turn the agent has not yet finished); the store
     * never evicts them. The default protects nothing.
     */
    public void setActiveProtection(Predicate<String> isActive) {
        this.activeProtection = isActive == null ? id -> false : isActive;
    }

    /**
     * Loads (once) and returns the partition for {@code root}. On first load
     * every unfinished record is marked interrupted -- answered records are
     * kept as they are -- and nothing is scheduled or sent. A bad partition
     * yields a result with {@link LoadResult#problem()} set and no records;
     * the file is not touched. Blocking: background thread only.
     */
    public LoadResult load(String root) {
        Partition p = partition(root);
        boolean interruptedSaveFailed = false;
        CompletableFuture<Void> save = null;
        synchronized (p) {
            if (!p.loaded) {
                loadFromDisk(p);
                p.loaded = true;
                if (p.problem == null && markInterrupted(p)) {
                    save = scheduleWriteLocked(p);
                }
            }
        }
        if (save != null) {
            try {
                save.join();
            } catch (RuntimeException e) {
                interruptedSaveFailed = true;
            }
        }
        synchronized (p) {
            return p.problem != null
                    ? new LoadResult(List.of(), p.lastSequence, p.problem, false)
                    : new LoadResult(p.records, p.lastSequence, null, interruptedSaveFailed || p.saveFailure != null);
        }
    }

    /** Snapshot of the records of {@code root}, ascending by sequence; empty for a bad partition. */
    public List<EditorAskRecord> records(String root) {
        return load(root).records();
    }

    /** The reason {@code root}'s partition is unusable, or empty when it is healthy. */
    public Optional<String> problem(String root) {
        return Optional.ofNullable(load(root).problem());
    }

    /** The highest acceptance sequence ever admitted for {@code root}, or -1 when none. */
    public long lastSequence(String root) {
        return load(root).lastSequence();
    }

    /** The most recent write failure not yet cleared by a landed write, if any. */
    public Optional<IOException> lastSaveFailure(String root) {
        return Optional.ofNullable(partition(root).saveFailure);
    }

    /**
     * Admits a new record. Completes after the write lands; fails with
     * {@link Kind#OVERFLOW} when the checkout already holds the maximum
     * unfinished records (nothing is dropped), {@link Kind#DUPLICATE_ID},
     * {@link Kind#SEQUENCE_NOT_INCREASING}, {@link Kind#BAD_PARTITION} or
     * {@link Kind#WRITE_FAILED}. After a write failure the record stays in
     * memory; the caller must not resubmit the question but may
     * {@link #retrySave}.
     */
    public CompletableFuture<Void> admit(String root, EditorAskRecord record) {
        try {
            Partition p = readyPartition(root);
            synchronized (p) {
                checkOpen();
                checkUsable(p);
                if (p.records.stream().anyMatch(r -> r.id().equals(record.id()))) {
                    throw new StoreException(Kind.DUPLICATE_ID, "an exchange with this ID already exists", null);
                }
                if (record.acceptanceSequence() <= p.lastSequence) {
                    throw new StoreException(Kind.SEQUENCE_NOT_INCREASING, "acceptance sequence "
                            + record.acceptanceSequence() + " is not above the last admitted " + p.lastSequence, null);
                }
                if (!record.isSettled() && unfinished(p) >= EditorAskLimits.MAX_UNFINISHED_RECORDS) {
                    throw new StoreException(Kind.OVERFLOW, "this checkout already has "
                            + EditorAskLimits.MAX_UNFINISHED_RECORDS + " unfinished questions; wait for one to finish", null);
                }
                p.records.add(record);
                p.lastSequence = record.acceptanceSequence();
                return scheduleWriteLocked(p);
            }
        } catch (StoreException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Transforms the record {@code id}; completes with the new record after
     * the write lands. The transform may not change identity, content or
     * the selection, and a settled record may only gain a new anchor --
     * a settled outcome, and above all an accepted answer, is immutable.
     */
    public CompletableFuture<EditorAskRecord> update(String root, String id, UnaryOperator<EditorAskRecord> change) {
        try {
            Partition p = readyPartition(root);
            EditorAskRecord updated;
            CompletableFuture<Void> saved;
            synchronized (p) {
                checkOpen();
                checkUsable(p);
                int index = indexOf(p, id);
                if (index < 0) {
                    throw new StoreException(Kind.UNKNOWN_ID, "no such exchange in this checkout", null);
                }
                EditorAskRecord before = p.records.get(index);
                updated = change.apply(before);
                checkTransition(before, updated);
                p.records.set(index, updated);
                saved = scheduleWriteLocked(p);
            }
            EditorAskRecord result = updated;
            return saved.thenApply(v -> result);
        } catch (StoreException e) {
            return CompletableFuture.failedFuture(e);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(new StoreException(Kind.ILLEGAL_UPDATE, "update was rejected", e));
        }
    }

    /** Rewrites the current in-memory partition; use after {@link Kind#WRITE_FAILED}. */
    public CompletableFuture<Void> retrySave(String root) {
        try {
            Partition p = readyPartition(root);
            synchronized (p) {
                checkOpen();
                checkUsable(p);
                return scheduleWriteLocked(p);
            }
        } catch (StoreException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /** Blocks until every write scheduled so far has landed or failed. */
    public void flushPendingSaves() {
        for (CompletableFuture<Void> f : List.copyOf(inFlight)) {
            try {
                f.join();
            } catch (RuntimeException ignored) {
                // failure is observable on the future/lastSaveFailure; flush is only a barrier
            }
        }
    }

    /** Flushes pending writes, then refuses further mutations. Idempotent. */
    @Override
    public void close() {
        closed = true;
        flushPendingSaves();
        if (ownedExecutor != null) {
            ownedExecutor.shutdown();
        }
    }

    // ---- internals ----

    private Partition partition(String root) {
        EditorAskLimits.checkRoot(root);
        return partitions.computeIfAbsent(root, r -> new Partition(r, partitionFile(r)));
    }

    private Partition readyPartition(String root) {
        load(root);
        return partition(root);
    }

    private void checkOpen() {
        if (closed) {
            throw new StoreException(Kind.CLOSED, "history store is closed", null);
        }
    }

    private static void checkUsable(Partition p) {
        if (p.problem != null) {
            throw new StoreException(Kind.BAD_PARTITION, p.problem, null);
        }
    }

    private static int unfinished(Partition p) {
        return (int) p.records.stream().filter(r -> !r.isSettled()).count();
    }

    private static int indexOf(Partition p, String id) {
        for (int i = 0; i < p.records.size(); i++) {
            if (p.records.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private static void checkTransition(EditorAskRecord before, EditorAskRecord after) {
        if (after == null || !before.id().equals(after.id())
                || before.acceptanceSequence() != after.acceptanceSequence()
                || !before.owner().equals(after.owner())
                || !Objects.equals(before.relativeFile(), after.relativeFile())
                || !Objects.equals(before.range(), after.range())
                || !Objects.equals(before.selectedText(), after.selectedText())
                || !before.question().equals(after.question())
                || !before.askedAt().equals(after.askedAt())) {
            throw new StoreException(Kind.ILLEGAL_UPDATE, "an update may not change an exchange's identity or content", null);
        }
        if (before.isSettled() && (before.status() != after.status()
                || !Objects.equals(before.answer(), after.answer())
                || !Objects.equals(before.answeredAt(), after.answeredAt())
                || !Objects.equals(before.failureReason(), after.failureReason()))) {
            throw new StoreException(Kind.ILLEGAL_UPDATE, "a settled exchange's outcome is immutable", null);
        }
    }

    /** Applies retention, bumps the revision, snapshots the bytes and queues the write. Caller holds {@code p}. */
    private CompletableFuture<Void> scheduleWriteLocked(Partition p) {
        applyRetention(p);
        long rev = ++p.revision;
        byte[] bytes;
        try {
            bytes = EditorAskPartitionCodec.encode(p.root, p.lastSequence, p.records);
        } catch (IllegalArgumentException e) {
            IOException failure = new IOException("history partition cannot be encoded: " + e.getMessage(), e);
            p.saveFailure = failure;
            return CompletableFuture.failedFuture(new StoreException(Kind.WRITE_FAILED, failure.getMessage(), failure));
        }
        p.latestBytes = bytes;
        p.latestRevision = rev;
        CompletableFuture<Void> done = new CompletableFuture<>();
        inFlight.add(done);
        done.whenComplete((v, t) -> inFlight.remove(done));
        try {
            writerExecutor.execute(() -> runWrite(p, rev, bytes, done));
        } catch (RejectedExecutionException e) {
            IOException failure = new IOException("history writer is not accepting work", e);
            p.saveFailure = failure;
            done.completeExceptionally(new StoreException(Kind.WRITE_FAILED, failure.getMessage(), failure));
        }
        return done;
    }

    private void runWrite(Partition p, long rev, byte[] bytes, CompletableFuture<Void> done) {
        p.writeLock.lock();
        try {
            if (p.writtenRevision >= rev) {
                done.complete(null); // a newer snapshot already landed: this one is superseded, never written
                return;
            }
            writer.write(p.file, bytes);
            p.writtenRevision = rev;
            if (p.latestRevision == rev) {
                p.saveFailure = null;
            }
            done.complete(null);
        } catch (IOException | RuntimeException e) {
            IOException failure = e instanceof IOException io ? io : new IOException("history write failed", e);
            p.saveFailure = failure;
            LOG.log(Level.WARNING, "editor ask history write failed: " + failure.getClass().getSimpleName());
            done.completeExceptionally(new StoreException(Kind.WRITE_FAILED, "history could not be saved", failure));
        } finally {
            p.writeLock.unlock();
        }
    }

    private void applyRetention(Partition p) {
        Predicate<String> protectedIds = activeProtection;
        long settled = p.records.stream().filter(EditorAskRecord::isSettled).count();
        // records are ascending by acceptance sequence, so a forward scan evicts oldest first
        for (int i = 0; i < p.records.size() && settled > EditorAskLimits.MAX_SETTLED_RECORDS; ) {
            EditorAskRecord r = p.records.get(i);
            if (r.isSettled() && !protectedIds.test(r.id())) {
                p.records.remove(i);
                settled--;
            } else {
                i++;
            }
        }
    }

    private boolean markInterrupted(Partition p) {
        boolean changed = false;
        for (int i = 0; i < p.records.size(); i++) {
            EditorAskRecord r = p.records.get(i);
            if (!r.isSettled()) {
                p.records.set(i, r.settled(EditorAskRecord.Status.INTERRUPTED_POSSIBLY_SENT, INTERRUPTED_REASON));
                changed = true;
            }
        }
        return changed;
    }

    private void loadFromDisk(Partition p) {
        try {
            if (!Files.exists(p.file, LinkOption.NOFOLLOW_LINKS)) {
                return; // a first ask: empty, healthy
            }
            if (!Files.isRegularFile(p.file, LinkOption.NOFOLLOW_LINKS)) {
                p.problem = "history file is not a regular file";
                return;
            }
            long size = Files.size(p.file);
            if (size > EditorAskLimits.MAX_PARTITION_BYTES) {
                p.problem = "history file is " + size + " bytes; the limit is " + EditorAskLimits.MAX_PARTITION_BYTES;
                return;
            }
            byte[] bytes;
            try (InputStream in = Files.newInputStream(p.file, LinkOption.NOFOLLOW_LINKS)) {
                bytes = in.readNBytes((int) EditorAskLimits.MAX_PARTITION_BYTES + 1);
            }
            EditorAskPartitionCodec.Partition decoded = EditorAskPartitionCodec.decode(bytes, p.root);
            p.records = new ArrayList<>(decoded.records());
            p.lastSequence = decoded.lastSequence();
            p.writtenRevision = 0;
        } catch (NoSuchFileException e) {
            // raced a delete: treat as absent
        } catch (EditorAskPartitionCodec.CorruptPartitionException e) {
            p.problem = "history file is unusable: " + e.getMessage();
        } catch (IOException | RuntimeException e) {
            p.problem = "history file could not be read: " + e.getClass().getSimpleName();
        }
        if (p.problem != null) {
            LOG.log(Level.WARNING, "editor ask history partition isolated: " + p.problem);
        }
    }

    /**
     * Writes {@code bytes} to {@code target} via an fsynced, owner-private
     * temp file in the same directory and an atomic replace.
     */
    static void writeAtomically(Path target, byte[] bytes) throws IOException {
        Path dir = target.getParent();
        boolean posix = dir.getFileSystem().supportedFileAttributeViews().contains("posix");
        if (posix) {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } else {
            Files.createDirectories(dir);
        }
        FileAttribute<?>[] attrs = posix
                ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(Set.of(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))}
                : new FileAttribute<?>[0];
        Path temp = Files.createTempFile(dir, "ask", ".tmp", attrs);
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) { // a stray temp file is harmless; the failure below matters
            }
            throw e;
        }
    }
}
