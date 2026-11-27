package app.drydock.editorqa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import app.drydock.domain.ManagedSessionId;
import app.drydock.editorqa.EditorAskHistoryStore.Kind;
import app.drydock.editorqa.EditorAskHistoryStore.StoreException;
import app.drydock.editorqa.EditorAskRecord.Status;

class EditorAskHistoryStoreTest {

    static final String ROOT = "/work/repo";
    static final String OTHER = "/work/other";
    static final Instant SAME_TIME = Instant.parse("2026-01-01T00:00:00Z");
    static final ManagedSessionId OWNER = ManagedSessionId.newId();

    @TempDir
    Path base;

    static EditorAskRecord record(long seq, Status status) {
        EditorAskRecord r = new EditorAskRecord(EditorAskRecord.newId(), seq, OWNER, "A.java",
                new EditorAskRecord.TextRange(0, 4, 0, 0, 0, 4), "text", "q" + seq, SAME_TIME,
                Status.ACCEPTED, null, null, null, null);
        return switch (status) {
            case ACCEPTED -> r;
            case ANSWERED -> r.answered("answer " + seq, Instant.parse("2026-01-01T00:05:00Z"));
            default -> r.settled(status, status.isSettled() ? "reason" : null);
        };
    }

    static Throwable cause(Throwable t) {
        return t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
    }

    static Kind kindOf(CompletableFuture<?> f) {
        try {
            f.join();
        } catch (CompletionException e) {
            return ((StoreException) cause(e)).kind();
        }
        throw new AssertionError("expected failure");
    }

    @Test
    void roundTripAcrossStoreInstancesKeepsAnswerTimeAndId() {
        EditorAskRecord pending = record(1, Status.ACCEPTED);
        String id = pending.id();
        try (EditorAskHistoryStore store = new EditorAskHistoryStore(base)) {
            store.admit(ROOT, pending).join();
            Instant answered = Instant.parse("2026-02-03T04:05:06.789Z");
            store.update(ROOT, id, r -> r.answered("the full answer", answered)).join();
        }
        try (EditorAskHistoryStore store = new EditorAskHistoryStore(base)) {
            EditorAskRecord loaded = store.records(ROOT).get(0);
            assertEquals(id, loaded.id());
            assertEquals(Status.ANSWERED, loaded.status());
            assertEquals("the full answer", loaded.answer());
            assertEquals(Instant.parse("2026-02-03T04:05:06.789Z"), loaded.answeredAt());
            assertEquals(1, store.lastSequence(ROOT));
        }
    }

    @Test
    void partitionIsHashedFileUnderEditorAsksOwnerPrivateAndHoldsNoSecrets() throws Exception {
        try (EditorAskHistoryStore store = new EditorAskHistoryStore(base)) {
            store.admit(ROOT, record(1, Status.ACCEPTED)).join();
            Path file = store.partitionFile(ROOT);
            assertEquals(base.resolve("editor-asks"), file.getParent());
            assertTrue(file.getFileName().toString().matches("[0-9a-f]{64}\\.json"));
            assertFalse(file.toString().contains("repo"));
            String text = Files.readString(file, StandardCharsets.UTF_8);
            assertTrue(text.contains("\"root\": \"" + ROOT + "\""));
            assertFalse(text.contains("token"));
            assertFalse(text.contains("Bearer"));
            if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                        Files.getPosixFilePermissions(file));
                assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE), Files.getPosixFilePermissions(file.getParent()));
            }
            try (var entries = Files.list(file.getParent())) {
                assertEquals(1, entries.count(), "no temp file left behind");
            }
        }
    }

    @Test
    void corruptWrongKeyAndOversizePartitionsAreIsolatedAndNeverWiped() throws Exception {
        EditorAskHistoryStore probe = new EditorAskHistoryStore(base);
        Path corrupt = probe.partitionFile(ROOT);
        Files.createDirectories(corrupt.getParent());
        Files.writeString(corrupt, "{garbage");
        // a valid partition for OTHER copied onto ROOT's filename: wrong full key
        probe.admit(OTHER, record(1, Status.ACCEPTED)).join();
        Path wrongKey = probe.partitionFile("/work/wrongkey");
        Files.copy(probe.partitionFile(OTHER), wrongKey);
        Path oversize = probe.partitionFile("/work/big");
        try (var ch = FileChannel.open(oversize, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE)) {
            ch.write(ByteBuffer.wrap(new byte[] {0}), EditorAskLimits.MAX_PARTITION_BYTES);
        }
        probe.close();

        try (EditorAskHistoryStore store = new EditorAskHistoryStore(base)) {
            for (String root : List.of(ROOT, "/work/wrongkey", "/work/big")) {
                EditorAskHistoryStore.LoadResult result = store.load(root);
                assertFalse(result.ok(), root);
                assertTrue(result.records().isEmpty());
                assertNotNull(result.problem());
                assertEquals(Kind.BAD_PARTITION, kindOf(store.admit(root, record(1, Status.ACCEPTED))));
            }
            assertEquals("{garbage", Files.readString(corrupt), "bad partition is never rewritten");
            assertEquals(EditorAskLimits.MAX_PARTITION_BYTES + 1, Files.size(oversize));
            // the healthy checkout is untouched and still writable
            assertEquals(1, store.records(OTHER).size());
            store.admit(OTHER, record(2, Status.ACCEPTED)).join();
            assertEquals(2, store.records(OTHER).size());
        }
    }

    @Test
    void retention32UnfinishedPlus128SettledWithEqualTimestampsEvictsBySequence() {
        try (EditorAskHistoryStore store = new EditorAskHistoryStore(base)) {
            store.admit(OTHER, record(1, Status.NO_ANSWER)).join();
            long seq = 1;
            List<String> settledIds = new ArrayList<>();
            for (int i = 0; i < 130; i++) {
                EditorAskRecord r = record(seq++, Status.NO_ANSWER);
                settledIds.add(r.id());
                store.admit(ROOT, r).join();
            }
            List<String> unfinished = new ArrayList<>();
            for (int i = 0; i < EditorAskLimits.MAX_UNFINISHED_RECORDS; i++) {
                EditorAskRecord r = record(seq++, Status.QUEUED);
                unfinished.add(r.id());
                store.admit(ROOT, r).join();
            }
            List<EditorAskRecord> records = store.records(ROOT);
            assertEquals(128 + 32, records.size());
            List<String> ids = records.stream().map(EditorAskRecord::id).toList();
            assertFalse(ids.contains(settledIds.get(0)));
            assertFalse(ids.contains(settledIds.get(1)));
            assertTrue(ids.contains(settledIds.get(2)));
            assertTrue(ids.containsAll(unfinished));

            // overflow: refused visibly, nothing dropped
            long refusedSeq = seq++;
            assertEquals(Kind.OVERFLOW, kindOf(store.admit(ROOT, record(refusedSeq, Status.QUEUED))));
            assertEquals(128 + 32, store.records(ROOT).size());
            assertEquals(refusedSeq - 1, store.lastSequence(ROOT), "refused admit did not advance the sequence");

            // settling an unfinished record evicts the oldest settled, not the unfinished
            store.update(ROOT, unfinished.get(0), r -> r.settled(Status.NO_ANSWER, "gone")).join();
            ids = store.records(ROOT).stream().map(EditorAskRecord::id).toList();
            assertFalse(ids.contains(settledIds.get(2)));
            assertTrue(ids.containsAll(unfinished));

            // the other checkout was never evicted from
            assertEquals(1, store.records(OTHER).size());
        }
    }

    @Test
    void activeAnsweredRecordIsProtectedFromEviction() {
        try (EditorAskHistoryStore store = new EditorAskHistoryStore(base)) {
            EditorAskRecord first = record(1, Status.ANSWERED);
            store.setActiveProtection(first.id()::equals);
            store.admit(ROOT, first).join();
            for (int i = 2; i <= 131; i++) {
                store.admit(ROOT, record(i, Status.NO_ANSWER)).join();
            }
            List<EditorAskRecord> records = store.records(ROOT);
            assertEquals(first.id(), records.get(0).id());
            assertEquals(128, records.size(), "protected record kept; oldest unprotected evicted instead");
            assertEquals(5, records.get(1).acceptanceSequence());
        }
    }

    @Test
    void sequenceNeverRepeatsAndDuplicateIdsAndSettledMutationAreRefused() {
        try (EditorAskHistoryStore store = new EditorAskHistoryStore(base)) {
            EditorAskRecord a = record(5, Status.ACCEPTED);
            store.admit(ROOT, a).join();
            assertEquals(Kind.SEQUENCE_NOT_INCREASING, kindOf(store.admit(ROOT, record(5, Status.ACCEPTED))));
            assertEquals(Kind.DUPLICATE_ID, kindOf(store.admit(ROOT, a.settled(Status.NO_ANSWER, null))));
            assertEquals(Kind.UNKNOWN_ID, kindOf(store.update(ROOT, EditorAskRecord.newId(), r -> r)));
            store.update(ROOT, a.id(), r -> r.answered("done", SAME_TIME)).join();
            assertEquals(Kind.ILLEGAL_UPDATE, kindOf(store.update(ROOT, a.id(), r -> r.settled(Status.NO_ANSWER, "x"))));
            assertEquals(Kind.ILLEGAL_UPDATE,
                    kindOf(store.update(ROOT, a.id(), r -> r.answered("other", SAME_TIME))));
            assertEquals("done", store.records(ROOT).get(0).answer());
        }
    }

    @Test
    void delayedOlderWriterLosesToNewerSnapshot() throws Exception {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        List<String> written = new ArrayList<>();
        EditorAskHistoryStore store = new EditorAskHistoryStore(base, queue::add,
                (target, bytes) -> {
                    written.add(new String(bytes, StandardCharsets.UTF_8));
                    EditorAskHistoryStore.writeAtomically(target, bytes);
                });
        var older = store.admit(ROOT, record(1, Status.ACCEPTED));
        var newer = store.admit(ROOT, record(2, Status.ACCEPTED));
        assertEquals(2, queue.size());
        Runnable olderTask = queue.pollFirst();
        Runnable newerTask = queue.pollFirst();
        newerTask.run(); // the newer snapshot lands first
        olderTask.run(); // the delayed older writer must not overwrite it
        newer.join();
        older.join(); // superseded, and durable via the newer snapshot
        assertEquals(1, written.size());
        EditorAskHistoryStore reread = new EditorAskHistoryStore(base);
        assertEquals(2, reread.records(ROOT).size());
        reread.close();
        store.close();
    }

    @Test
    void injectedWriteFailureIsObservableNeverSuccessAndRetryable() {
        AtomicBoolean fail = new AtomicBoolean(true);
        EditorAskHistoryStore store = new EditorAskHistoryStore(base, null, (target, bytes) -> {
            if (fail.get()) {
                throw new IOException("disk full");
            }
            EditorAskHistoryStore.writeAtomically(target, bytes);
        });
        EditorAskRecord a = record(1, Status.ACCEPTED);
        assertEquals(Kind.WRITE_FAILED, kindOf(store.admit(ROOT, a)));
        assertTrue(store.lastSaveFailure(ROOT).isPresent());
        assertEquals(1, store.records(ROOT).size(), "change is kept in memory, not silently dropped");
        assertEquals(Kind.WRITE_FAILED,
                kindOf(store.update(ROOT, a.id(), r -> r.answered("x", SAME_TIME))));
        fail.set(false);
        store.retrySave(ROOT).join();
        assertTrue(store.lastSaveFailure(ROOT).isEmpty());
        store.close();
        try (EditorAskHistoryStore reread = new EditorAskHistoryStore(base)) {
            assertEquals(Status.ANSWERED, reread.records(ROOT).get(0).status());
        }
    }

    @Test
    void restartMarksUnfinishedInterruptedKeepsAnsweredAndSchedulesNothing() {
        List<EditorAskRecord> in = new ArrayList<>();
        long seq = 1;
        for (Status s : List.of(Status.ACCEPTED, Status.QUEUED, Status.SUBMITTING, Status.AWAITING,
                Status.ANSWERED, Status.NO_ANSWER)) {
            in.add(record(seq++, s));
        }
        // QUEUED/SUBMITTING/AWAITING cannot be admitted settled; admit as given (unfinished are legal inputs)
        try (EditorAskHistoryStore store = new EditorAskHistoryStore(base)) {
            for (EditorAskRecord r : in) {
                store.admit(ROOT, r).join();
            }
        }
        try (EditorAskHistoryStore store = new EditorAskHistoryStore(base)) {
            EditorAskHistoryStore.LoadResult result = store.load(ROOT);
            assertFalse(result.interruptionSaveFailed());
            List<Status> statuses = result.records().stream().map(EditorAskRecord::status).toList();
            assertEquals(List.of(Status.INTERRUPTED_POSSIBLY_SENT, Status.INTERRUPTED_POSSIBLY_SENT,
                    Status.INTERRUPTED_POSSIBLY_SENT, Status.INTERRUPTED_POSSIBLY_SENT, Status.ANSWERED,
                    Status.NO_ANSWER), statuses);
            assertEquals("answer 5", result.records().get(4).answer());
            assertEquals(in.get(0).id(), result.records().get(0).id());
        }
        // the interruption itself is durable: a third open sees the same
        try (EditorAskHistoryStore store = new EditorAskHistoryStore(base)) {
            assertEquals(Status.INTERRUPTED_POSSIBLY_SENT, store.records(ROOT).get(0).status());
        }
    }

    @Test
    void closeFlushesPendingWritesAndRefusesFurtherMutations() {
        EditorAskHistoryStore store = new EditorAskHistoryStore(base);
        for (int i = 1; i <= 20; i++) {
            store.admit(ROOT, record(i, Status.ACCEPTED)); // futures deliberately not awaited
        }
        store.close();
        assertEquals(Kind.CLOSED, kindOf(store.admit(ROOT, record(21, Status.ACCEPTED))));
        try (EditorAskHistoryStore reread = new EditorAskHistoryStore(base)) {
            assertEquals(20, reread.records(ROOT).size());
        }
    }
}
