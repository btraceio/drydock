package app.drydock.babysit;

import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.state.json.JsonParseException;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonBoolean;
import app.drydock.state.json.JsonValue.JsonNull;
import app.drydock.state.json.JsonValue.JsonNumber;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import app.drydock.state.json.JsonWriter;

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
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.UnaryOperator;

/**
 * The babysitting state shared between the plugin and a detached watcher
 * process: {@code <baseDir>/babysit/babysit-state.json}. It carries the CI
 * rerun counters (at most {@link #MAX_RERUNS_PER_RUN} per run, persisted so
 * a restart cannot refill them), the escalations a detached watcher queued
 * for the plugin to ingest, which process owns the file while detached, and
 * the {@link Detachment}: the engagements the plugin handed to a detached
 * watcher at IDE quit, with the end the watcher recorded for any of them
 * (the watcher never writes {@code state.json}; the plugin applies those
 * facts when it re-attaches).
 *
 * <p><b>Why its own file.</b> The plugin's {@code state.json} has exactly one
 * in-process writer and no cross-process lock; a second process writing it
 * would race the IDE's write-back. Everything babysitting must share with a
 * detached watcher lives here instead.</p>
 *
 * <p><b>Two processes write it</b>, with the {@code SharedProjectsFile}
 * discipline: a JVM-wide lock per file first (two threads of one JVM taking
 * the OS lock would fail with an overlapping-lock error), then an OS lock on
 * a sibling {@code babysit-state.json.lock} with a bounded wait, then
 * re-read, apply, write a temp file, force it, atomically rename it over the
 * file, unlock. A change the other process made between this one's read and
 * its write is therefore never lost.</p>
 *
 * <p><b>A file this version cannot fully trust is never overwritten</b>: a
 * malformed file or one written by a newer schema reads as empty with a
 * {@link Snapshot#problem()}, and every write refuses with {@link
 * BabysitStateFileException}. A malformed ENTRY is different: it is skipped
 * with a WARNING and the rest is kept (the file is machine-written; one bad
 * counter must not block every rerun).</p>
 *
 * <p>Every method blocks on the file system: call it off the EDT, on a
 * background executor or an MCP handler thread.</p>
 */
public final class BabysitStateFile {

    private static final Logger LOG = System.getLogger(BabysitStateFile.class.getName());

    /** The schema this version writes; a file declaring a higher one is read but never rewritten. */
    public static final int SCHEMA_VERSION = 1;

    /** Reruns of one failed workflow run the plugin will request (B4); the fourth is an escalation. */
    public static final int MAX_RERUNS_PER_RUN = 3;

    /** How long a change waits for the other process to release the file lock. */
    private static final long LOCK_WAIT_MILLIS = 5_000;
    private static final long LOCK_POLL_MILLIS = 50;

    private static final ConcurrentHashMap<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

    /** The process that owns the file while an engagement is detached. */
    public record Owner(long pid, Instant startedAt) {
        public Owner {
            Objects.requireNonNull(startedAt, "startedAt");
            if (pid <= 0) {
                throw new IllegalArgumentException("pid must be positive: " + pid);
            }
        }
    }

    /**
     * One engagement a detached watcher carries on: everything it needs
     * without {@code state.json}. {@code repositoryRoot} is the watched
     * repository's main checkout (gh reads and the write guard's fetch run
     * there), {@code repository} its configured GitHub {@code owner/repo}
     * (empty: gh infers it from the checkout's remote), {@code worktree} the
     * babysitter's detached worktree (the headless run's working directory),
     * {@code agentKind} the babysitter's {@code AgentKind.persistedName()},
     * {@code agentSessionId} its bound conversation (empty: never resumed
     * headlessly -- an unbound resume is a picker), {@code knownHead} the
     * PR head the plugin last read. {@code ended} is set by the watcher when
     * the engagement ended while detached (the PR merged or closed): the
     * plugin then stops the pairing at re-attach. Delta and instructions
     * files are not carried: both are derived from the base directory, the
     * watched id and the PR number ({@link BabysitDelta}, {@link
     * BabysitInstructions#fileFor}).
     */
    public record DetachedEngagement(ManagedSessionId babysitterId, ManagedSessionId watchedId,
                                     RepositoryId repositoryId, int prNumber, Path repositoryRoot,
                                     Optional<String> repository, Path worktree, String agentKind,
                                     Optional<String> agentSessionId, Optional<String> knownHead,
                                     Optional<String> ended) {
        public DetachedEngagement {
            Objects.requireNonNull(babysitterId, "babysitterId");
            Objects.requireNonNull(watchedId, "watchedId");
            Objects.requireNonNull(repositoryId, "repositoryId");
            Objects.requireNonNull(repositoryRoot, "repositoryRoot");
            Objects.requireNonNull(repository, "repository");
            Objects.requireNonNull(worktree, "worktree");
            Objects.requireNonNull(agentKind, "agentKind");
            Objects.requireNonNull(agentSessionId, "agentSessionId");
            Objects.requireNonNull(knownHead, "knownHead");
            Objects.requireNonNull(ended, "ended");
            if (prNumber <= 0) {
                throw new IllegalArgumentException("prNumber must be positive: " + prNumber);
            }
            if (agentKind.isBlank()) {
                throw new IllegalArgumentException("agentKind must not be blank");
            }
            if (knownHead.isPresent() && !BabysitPairing.isCommitId(knownHead.get())) {
                throw new IllegalArgumentException("knownHead must be a full lowercase commit id: " + knownHead.get());
            }
            agentSessionId = agentSessionId.filter(id -> !id.isBlank());
            ended = ended.filter(reason -> !reason.isBlank());
        }

        /** The pairing this engagement continues, for rendering deltas (state WATCHING, head as carried). */
        public BabysitPairing pairing() {
            return new BabysitPairing(babysitterId, watchedId, repositoryId, prNumber, BabysitState.WATCHING,
                    knownHead);
        }

        public DetachedEngagement withEnded(String reason) {
            return new DetachedEngagement(babysitterId, watchedId, repositoryId, prNumber, repositoryRoot,
                    repository, worktree, agentKind, agentSessionId, knownHead, Optional.of(reason));
        }
    }

    /**
     * What the plugin handed over at IDE quit. {@code generation} names the
     * one watcher allowed to act on it: a watcher whose generation is no
     * longer the file's stops (that is how the plugin asks a watcher to stop
     * -- at re-attach, or when a newer detach replaces an older one).
     *
     * <p>{@code revoked}: the plugin has taken the engagements back (the
     * first write of a re-attach), but has not yet ingested what the
     * watcher recorded. A revoked detachment is current for no watcher, so
     * none starts a run or records another end in it; its recorded ends are
     * kept until the plugin has them durably in {@code state.json} and
     * removes the detachment itself.</p>
     */
    public record Detachment(UUID generation, Instant detachedAt, List<DetachedEngagement> engagements,
                             boolean revoked) {
        public Detachment {
            Objects.requireNonNull(generation, "generation");
            Objects.requireNonNull(detachedAt, "detachedAt");
            engagements = List.copyOf(engagements);
        }

        /** A detachment still in force (not revoked). */
        public Detachment(UUID generation, Instant detachedAt, List<DetachedEngagement> engagements) {
            this(generation, detachedAt, engagements, false);
        }

        /** This detachment, revoked: no watcher may act on it any more. */
        public Detachment asRevoked() {
            return revoked ? this : new Detachment(generation, detachedAt, engagements, true);
        }
    }

    /**
     * The file's content. {@code rerunCounts} maps a workflow run id to the
     * reruns already requested for it (1..n; a run with none has no entry).
     */
    public record Contents(Map<Long, Integer> rerunCounts, List<Escalation> escalationQueue,
                           Optional<Owner> owner, Optional<Detachment> detachment) {
        public Contents {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(detachment, "detachment");
            TreeMap<Long, Integer> counts = new TreeMap<>();
            for (Map.Entry<Long, Integer> entry : rerunCounts.entrySet()) {
                if (entry.getKey() <= 0 || entry.getValue() <= 0) {
                    throw new IllegalArgumentException("A rerun count needs a positive run id and count: "
                            + entry);
                }
                counts.put(entry.getKey(), entry.getValue());
            }
            rerunCounts = Map.copyOf(counts);
            escalationQueue = List.copyOf(escalationQueue);
        }

        /** Contents with no detachment. */
        public Contents(Map<Long, Integer> rerunCounts, List<Escalation> escalationQueue, Optional<Owner> owner) {
            this(rerunCounts, escalationQueue, owner, Optional.empty());
        }

        public static Contents empty() {
            return new Contents(Map.of(), List.of(), Optional.empty(), Optional.empty());
        }

        /** The reruns already requested for {@code runId}; 0 when none. */
        public int rerunCount(long runId) {
            return rerunCounts.getOrDefault(runId, 0);
        }

        /** These contents with {@code runId}'s count set to {@code count}; 0 drops the entry. */
        public Contents withRerunCount(long runId, int count) {
            Map<Long, Integer> next = new TreeMap<>(rerunCounts);
            if (count <= 0) {
                next.remove(runId);
            } else {
                next.put(runId, count);
            }
            return new Contents(next, escalationQueue, owner, detachment);
        }

        public Contents withEscalationQueued(Escalation escalation) {
            List<Escalation> next = new ArrayList<>(escalationQueue);
            next.add(Objects.requireNonNull(escalation, "escalation"));
            return new Contents(rerunCounts, next, owner, detachment);
        }

        public Contents withEscalationQueue(List<Escalation> queue) {
            return new Contents(rerunCounts, queue, owner, detachment);
        }

        /** These contents without the queued escalations whose ids are in {@code ids}. */
        public Contents withoutQueued(Set<UUID> ids) {
            return withEscalationQueue(escalationQueue.stream().filter(e -> !ids.contains(e.id())).toList());
        }

        public Contents withOwner(Optional<Owner> newOwner) {
            return new Contents(rerunCounts, escalationQueue, newOwner, detachment);
        }

        public Contents withDetachment(Optional<Detachment> newDetachment) {
            return new Contents(rerunCounts, escalationQueue, owner, newDetachment);
        }

        /** Whether {@code generation} is the current detachment's and not revoked: the watcher holding it may act. */
        public boolean current(UUID generation) {
            return detachment.filter(d -> !d.revoked() && d.generation().equals(generation)).isPresent();
        }

        /** These contents with the detachment, if any, revoked ({@link Detachment#revoked()}). */
        public Contents withDetachmentRevoked() {
            return detachment.isEmpty() || detachment.get().revoked() ? this
                    : withDetachment(Optional.of(detachment.get().asRevoked()));
        }

        /**
         * These contents with {@code babysitterId}'s engagement of the
         * {@code generation} detachment marked ended for {@code reason}; the
         * same contents when that detachment is no longer current (the plugin
         * re-attached meanwhile) or the engagement already ended.
         */
        public Contents withEngagementEnded(UUID generation, ManagedSessionId babysitterId, String reason) {
            if (!current(generation)) {
                return this;
            }
            Detachment current = detachment.get();
            List<DetachedEngagement> next = new ArrayList<>(current.engagements().size());
            for (DetachedEngagement engagement : current.engagements()) {
                next.add(engagement.babysitterId().equals(babysitterId) && engagement.ended().isEmpty()
                        ? engagement.withEnded(reason) : engagement);
            }
            return withDetachment(Optional.of(new Detachment(current.generation(), current.detachedAt(), next,
                    false)));
        }
    }

    /**
     * One read of the file. {@code present} is false when no file exists.
     * {@code stamp} changes whenever the file does, and is 0 when it is
     * absent. {@code problem} says why the file must not be rewritten
     * (malformed, unreadable, or a newer schema); the contents are then
     * empty or what could be read.
     */
    public record Snapshot(Contents contents, boolean present, long stamp, Optional<String> problem) {
        public Snapshot {
            Objects.requireNonNull(contents, "contents");
            Objects.requireNonNull(problem, "problem");
        }
    }

    /** What {@link #chargeRerun} did. */
    public sealed interface RerunCharge {
        /** The rerun was counted: {@code count} is the run's total, this one included, flushed to disk. */
        record Charged(int count) implements RerunCharge {
        }

        /** The run already had {@code count} reruns, at or over the cap; nothing was written. */
        record AtCap(int count) implements RerunCharge {
        }
    }

    private final Path file;
    private final Path lockFile;

    public BabysitStateFile(Path file) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        this.lockFile = this.file.resolveSibling(this.file.getFileName() + ".lock");
    }

    /** {@code <baseDir>/babysit/babysit-state.json}: beside, never inside, {@code state.json}. */
    public static Path defaultFile(Path baseDir) {
        return baseDir.resolve("babysit").resolve("babysit-state.json");
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
            return new Snapshot(Contents.empty(), false, 0L, Optional.empty());
        } catch (IOException e) {
            LOG.log(Level.WARNING, () -> "Could not read " + file + ": " + e);
            return new Snapshot(Contents.empty(), true, 0L, Optional.of("Could not read " + file + "."));
        }
        try {
            Decoded decoded = decode(text);
            if (decoded.schemaVersion() > SCHEMA_VERSION) {
                String problem = file.getFileName() + " was written by a newer Drydock (schema "
                        + decoded.schemaVersion() + "); update this one to change babysitting state.";
                LOG.log(Level.WARNING, () -> file + ": " + problem);
                return new Snapshot(decoded.contents(), true, stamp, Optional.of(problem));
            }
            return new Snapshot(decoded.contents(), true, stamp, Optional.empty());
        } catch (JsonParseException e) {
            LOG.log(Level.WARNING, () -> file + " is malformed and will not be changed until it is fixed: "
                    + e.getMessage());
            return new Snapshot(Contents.empty(), true, stamp,
                    Optional.of(file.getFileName() + " is malformed; fix or remove it to change babysitting state."));
        }
    }

    /**
     * Applies {@code change} to the current contents under both locks and
     * writes the result when it differs. {@code change} runs under the lock:
     * keep it pure and quick. Returns the contents as they now stand.
     */
    public Contents update(UnaryOperator<Contents> change) throws BabysitStateFileException {
        return locked(() -> {
            Snapshot current = writable();
            Contents next = Objects.requireNonNull(change.apply(current.contents()), "change result");
            if (!next.equals(current.contents())) {
                write(next);
            }
            return next;
        });
    }

    /**
     * Counts one rerun of {@code runId} unless it already has {@code cap}:
     * the check and the increment are one locked transaction, and the
     * increment is on disk (forced, renamed) before this returns -- so the
     * caller may only then run the rerun itself.
     */
    public RerunCharge chargeRerun(long runId, int cap) throws BabysitStateFileException {
        requirePositive(runId);
        return locked(() -> {
            Snapshot current = writable();
            int count = current.contents().rerunCount(runId);
            if (count >= cap) {
                return new RerunCharge.AtCap(count);
            }
            write(current.contents().withRerunCount(runId, count + 1));
            return new RerunCharge.Charged(count + 1);
        });
    }

    /**
     * Releases one counted rerun of {@code runId} whose request then
     * definitely did not reach GitHub. Never drops below zero. Never call it
     * for an outcome that is unknown: that rerun may have happened.
     */
    public void refundRerun(long runId) throws BabysitStateFileException {
        requirePositive(runId);
        update(contents -> contents.withRerunCount(runId, contents.rerunCount(runId) - 1));
    }

    private Snapshot writable() throws BabysitStateFileException {
        Snapshot current = read();
        if (current.problem().isPresent()) {
            throw new BabysitStateFileException(current.problem().get());
        }
        return current;
    }

    private interface Transaction<T> {
        T run() throws BabysitStateFileException;
    }

    private <T> T locked(Transaction<T> transaction) throws BabysitStateFileException {
        Path key;
        try {
            Files.createDirectories(file.getParent());
            key = lockKey(file);
        } catch (IOException e) {
            throw new BabysitStateFileException("Could not update " + file + ": " + e.getMessage(), e);
        }
        ReentrantLock jvmLock = JVM_LOCKS.computeIfAbsent(key, ignored -> new ReentrantLock());
        jvmLock.lock();
        try {
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock ignored = acquire(channel)) {
                return transaction.run();
            }
        } catch (IOException e) {
            throw new BabysitStateFileException("Could not update " + file + ": " + e.getMessage(), e);
        } finally {
            jvmLock.unlock();
        }
    }

    /**
     * The per-JVM lock's key: the file under its parent's real path, so two
     * spellings of one file (a symlinked or otherwise aliased base
     * directory) share one lock. Two keys for one file would let two
     * threads reach {@code tryLock} on the same file, and the second would
     * get an {@code OverlappingFileLockException} instead of waiting. The
     * parent must exist; the file need not.
     */
    static Path lockKey(Path file) throws IOException {
        Path absolute = file.toAbsolutePath().normalize();
        return absolute.getParent().toRealPath().resolve(absolute.getFileName());
    }

    private FileLock acquire(FileChannel channel) throws IOException, BabysitStateFileException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LOCK_WAIT_MILLIS);
        while (true) {
            FileLock lock = channel.tryLock();
            if (lock != null) {
                return lock;
            }
            if (System.nanoTime() > deadline) {
                throw new BabysitStateFileException(file.getFileName() + " is locked by another Drydock process");
            }
            try {
                Thread.sleep(LOCK_POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BabysitStateFileException("Interrupted while waiting for " + lockFile);
            }
        }
    }

    private void write(Contents contents) throws BabysitStateFileException {
        byte[] bytes = encode(contents).getBytes(StandardCharsets.UTF_8);
        Path temp = null;
        try {
            temp = Files.createTempFile(file.getParent(), "babysit-state", ".json.tmp");
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
            throw new BabysitStateFileException("Could not write " + file + ": " + e.getMessage(), e);
        }
    }

    private static long stampOf(Path path) throws IOException {
        return Files.getLastModifiedTime(path).to(TimeUnit.NANOSECONDS) ^ (Files.size(path) * 31);
    }

    private static void requirePositive(long runId) {
        if (runId <= 0) {
            throw new IllegalArgumentException("runId must be positive: " + runId);
        }
    }

    // ---- codec ----------------------------------------------------------------

    /** A decoded file and the schema version it declares (0 when it declares none). */
    record Decoded(Contents contents, int schemaVersion) {
    }

    /**
     * {@code {"schemaVersion":1,"rerunCounts":{"<runId>":<n>},"escalationQueue":[…],
     * "owner":{"pid":<n>,"startedAt":"<instant>"},"detachment":{"generation":"<uuid>",
     * "detachedAt":"<instant>","engagements":[…],"revoked":true}}}; {@code owner} only while owned,
     * {@code detachment} only while engagements are handed over, {@code revoked} only once revoked.
     */
    static String encode(Contents contents) {
        JsonObject counts = JsonObject.empty();
        for (Map.Entry<Long, Integer> entry : new TreeMap<>(contents.rerunCounts()).entrySet()) {
            counts.put(Long.toString(entry.getKey()), JsonNumber.of((long) entry.getValue()));
        }
        List<JsonValue> queue = new ArrayList<>();
        for (Escalation escalation : contents.escalationQueue()) {
            queue.add(JsonObject.empty()
                    .put("id", new JsonString(escalation.id().toString()))
                    .put("watchedId", new JsonString(escalation.watchedId().toString()))
                    .put("babysitterId", new JsonString(escalation.babysitterId().toString()))
                    .put("prNumber", JsonNumber.of((long) escalation.prNumber()))
                    .put("message", new JsonString(escalation.message()))
                    .put("createdAt", new JsonString(escalation.createdAt().toString()))
                    .put("resolved", new JsonBoolean(escalation.resolved())));
        }
        JsonObject root = JsonObject.empty()
                .put("schemaVersion", JsonNumber.of((long) SCHEMA_VERSION))
                .put("rerunCounts", counts)
                .put("escalationQueue", JsonArray.of(queue));
        contents.owner().ifPresent(owner -> root.put("owner", JsonObject.empty()
                .put("pid", JsonNumber.of(owner.pid()))
                .put("startedAt", new JsonString(owner.startedAt().toString()))));
        contents.detachment().ifPresent(detachment -> root.put("detachment", encodeDetachment(detachment)));
        return JsonWriter.write(root);
    }

    private static JsonObject encodeDetachment(Detachment detachment) {
        List<JsonValue> engagements = new ArrayList<>();
        for (DetachedEngagement engagement : detachment.engagements()) {
            engagements.add(JsonObject.empty()
                    .put("babysitterId", new JsonString(engagement.babysitterId().toString()))
                    .put("watchedId", new JsonString(engagement.watchedId().toString()))
                    .put("repositoryId", new JsonString(engagement.repositoryId().value().toString()))
                    .put("prNumber", JsonNumber.of((long) engagement.prNumber()))
                    .put("repositoryRoot", new JsonString(engagement.repositoryRoot().toString()))
                    .put("repository", optional(engagement.repository()))
                    .put("worktree", new JsonString(engagement.worktree().toString()))
                    .put("agentKind", new JsonString(engagement.agentKind()))
                    .put("agentSessionId", optional(engagement.agentSessionId()))
                    .put("knownHead", optional(engagement.knownHead()))
                    .put("ended", optional(engagement.ended())));
        }
        JsonObject encoded = JsonObject.empty()
                .put("generation", new JsonString(detachment.generation().toString()))
                .put("detachedAt", new JsonString(detachment.detachedAt().toString()))
                .put("engagements", JsonArray.of(engagements));
        if (detachment.revoked()) {
            encoded.put("revoked", new JsonBoolean(true));
        }
        return encoded;
    }

    private static JsonValue optional(Optional<String> value) {
        return value.<JsonValue>map(JsonString::new).orElse(JsonNull.INSTANCE);
    }

    /**
     * Lenient: absent members are empty, a bad counter or queue entry or
     * owner is dropped with a WARNING. Throws {@link JsonParseException} only
     * when the document is not a JSON object (or does not parse at all).
     */
    static Decoded decode(String json) {
        if (!(JsonParser.parse(json) instanceof JsonObject root)) {
            throw new JsonParseException("Expected a JSON object");
        }
        int schemaVersion = 0;
        if (root.get("schemaVersion") instanceof JsonNumber number) {
            try {
                schemaVersion = number.asInt();
            } catch (NumberFormatException e) {
                // A version that is not a small integer is no version this build wrote: refuse to rewrite.
                schemaVersion = Integer.MAX_VALUE;
            }
        }
        return new Decoded(new Contents(countsOf(root.get("rerunCounts")), queueOf(root.get("escalationQueue")),
                ownerOf(root.get("owner")), detachmentOf(root.get("detachment"))), schemaVersion);
    }

    private static Map<Long, Integer> countsOf(JsonValue value) {
        Map<Long, Integer> counts = new TreeMap<>();
        if (!(value instanceof JsonObject object)) {
            return counts;
        }
        for (Map.Entry<String, JsonValue> entry : object.members().entrySet()) {
            try {
                long runId = Long.parseLong(entry.getKey());
                int count = entry.getValue() instanceof JsonNumber number ? number.asInt() : 0;
                if (runId <= 0 || count <= 0) {
                    throw new IllegalArgumentException("not a positive run id and count");
                }
                counts.put(runId, count);
            } catch (IllegalArgumentException e) {
                LOG.log(Level.WARNING, () -> "Skipping a malformed rerun counter '" + entry.getKey() + "': "
                        + e.getMessage());
            }
        }
        return counts;
    }

    private static List<Escalation> queueOf(JsonValue value) {
        List<Escalation> queue = new ArrayList<>();
        if (!(value instanceof JsonArray array)) {
            return queue;
        }
        for (JsonValue element : array.elements()) {
            try {
                if (!(element instanceof JsonObject entry)) {
                    throw new IllegalArgumentException("not an object");
                }
                queue.add(new Escalation(UUID.fromString(string(entry, "id")),
                        ManagedSessionId.of(string(entry, "watchedId")),
                        ManagedSessionId.of(string(entry, "babysitterId")),
                        integer(entry, "prNumber"),
                        string(entry, "message"),
                        Instant.parse(string(entry, "createdAt")),
                        entry.get("resolved") instanceof JsonBoolean resolved && resolved.value()));
            } catch (IllegalArgumentException | DateTimeException e) {
                LOG.log(Level.WARNING, () -> "Skipping a malformed queued escalation: " + e.getMessage());
            }
        }
        return queue;
    }

    private static Optional<Owner> ownerOf(JsonValue value) {
        if (!(value instanceof JsonObject object)) {
            return Optional.empty();
        }
        try {
            if (!(object.get("pid") instanceof JsonNumber pid)) {
                throw new IllegalArgumentException("missing \"pid\"");
            }
            return Optional.of(new Owner(pid.asLong(), Instant.parse(string(object, "startedAt"))));
        } catch (IllegalArgumentException | DateTimeException e) {
            LOG.log(Level.WARNING, () -> "Ignoring a malformed babysitting owner marker: " + e.getMessage());
            return Optional.empty();
        }
    }

    /** Lenient: a bad detachment is dropped whole, a bad engagement inside a good one alone; both with a WARNING. */
    private static Optional<Detachment> detachmentOf(JsonValue value) {
        if (!(value instanceof JsonObject object)) {
            return Optional.empty();
        }
        Detachment detachment;
        try {
            detachment = new Detachment(UUID.fromString(string(object, "generation")),
                    Instant.parse(string(object, "detachedAt")), List.of());
        } catch (IllegalArgumentException | DateTimeException e) {
            LOG.log(Level.WARNING, () -> "Ignoring a malformed babysitting detachment: " + e.getMessage());
            return Optional.empty();
        }
        List<DetachedEngagement> engagements = new ArrayList<>();
        if (object.get("engagements") instanceof JsonArray array) {
            for (JsonValue element : array.elements()) {
                try {
                    if (!(element instanceof JsonObject entry)) {
                        throw new IllegalArgumentException("not an object");
                    }
                    engagements.add(new DetachedEngagement(
                            ManagedSessionId.of(string(entry, "babysitterId")),
                            ManagedSessionId.of(string(entry, "watchedId")),
                            RepositoryId.of(string(entry, "repositoryId")),
                            integer(entry, "prNumber"),
                            Path.of(string(entry, "repositoryRoot")),
                            optionalString(entry, "repository"),
                            Path.of(string(entry, "worktree")),
                            string(entry, "agentKind"),
                            optionalString(entry, "agentSessionId"),
                            optionalString(entry, "knownHead"),
                            optionalString(entry, "ended")));
                } catch (IllegalArgumentException e) { // InvalidPathException included
                    LOG.log(Level.WARNING, () -> "Skipping a malformed detached engagement: " + e.getMessage());
                }
            }
        }
        // A revocation that cannot be read is no revocation lost: read as revoked, the watcher stops.
        JsonValue revoked = object.get("revoked");
        boolean isRevoked = revoked != null && !(revoked instanceof JsonNull)
                && !(revoked instanceof JsonBoolean flag && !flag.value());
        return Optional.of(new Detachment(detachment.generation(), detachment.detachedAt(), engagements, isRevoked));
    }

    private static Optional<String> optionalString(JsonObject object, String key) {
        JsonValue value = object.get(key);
        if (value == null || value instanceof JsonNull) {
            return Optional.empty();
        }
        if (value instanceof JsonString string) {
            return Optional.of(string.value());
        }
        throw new IllegalArgumentException("\"" + key + "\" is not a string");
    }

    private static String string(JsonObject object, String key) {
        if (object.get(key) instanceof JsonString value) {
            return value.value();
        }
        throw new IllegalArgumentException("missing \"" + key + "\"");
    }

    private static int integer(JsonObject object, String key) {
        if (object.get(key) instanceof JsonNumber value) {
            return value.asInt();
        }
        throw new IllegalArgumentException("missing \"" + key + "\"");
    }
}
