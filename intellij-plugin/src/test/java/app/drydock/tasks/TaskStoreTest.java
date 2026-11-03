package app.drydock.tasks;

import app.drydock.domain.RepositoryId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance fixtures for the single-writer {@link TaskStore}'s mutation
 * semantics (T05c, D2/D6/P1): the real production behavior over real files
 * under {@code @TempDir} -- no mocking framework, no faked state. Covers
 * lossless round-trips, external-edit freshness, failed-write and
 * guard-conflict refusals, listener unsubscribe, the distinct
 * missing/duplicate/broken refusals, and publish-only-committed. The
 * concurrency half of the matrix (concurrent creates, two instances on one
 * root, queue timeout, flush/close) lives in
 * {@link TaskStoreConcurrencyTest}; test-local guards are hand-written
 * implementations of the {@code UnsavedContentGuard} seam, not mocks.
 */
class TaskStoreTest {

    private static final String REPO_A = "11111111-1111-1111-1111-111111111111";
    private static final String REPO_B = "22222222-2222-2222-2222-222222222222";

    @TempDir
    Path root;

    private TaskStore store() {
        return new TaskStore(root);
    }

    private void writeTask(String repo, String stem, String text) throws IOException {
        Files.createDirectories(root.resolve(repo));
        Files.writeString(root.resolve(repo).resolve(stem + ".md"), text, StandardCharsets.UTF_8);
    }

    private String readTask(String repo, String stem) throws IOException {
        return Files.readString(root.resolve(repo).resolve(stem + ".md"), StandardCharsets.UTF_8);
    }

    private TaskDocument parse(String repo, TaskId id) throws IOException, TaskFormatException {
        return TaskFileReader.parse(id, RepositoryId.of(repo), readTask(repo, id.text()), null).document();
    }

    /** A status-only update; every other Updates field stays null (unchanged). */
    private static TaskFilePatch.Updates status(TaskStatus status) {
        return new TaskFilePatch.Updates(null, status, null, null, null, null);
    }

    private static String validTask(String title) {
        return "---\ntitle: \"" + title + "\"\nstatus: open\ndone_by: human\nafter: []\nsessions: []\n---\nBody.\n";
    }

    /** 2: status+note round-trip keeps unknown metadata byte for byte. */
    @Test
    void updateAndNoteRoundTripPreserveUnknownMetadata() throws Exception {
        String unknown = "extra:\n  nested: {a: 1, b: [x, y]}";
        writeTask(REPO_A, "round-trip-one", "---\ntitle: \"Round trip task\"\n# front matter comment\n"
                + "status: open\ndone_by: human\ncreated: 2026-10-30T10:00:00Z\nafter: []\n"
                + "sessions: [\"sess-alpha\"]\n" + unknown + "\n---\nBody line one.\n\n## Session notes\n");
        TaskId id = TaskId.of("round-trip-one");
        TaskStore store = store();
        AtomicInteger publishes = new AtomicInteger();
        store.addListener(snapshot -> publishes.incrementAndGet());
        TaskDocument updated = store.update(id, status(TaskStatus.REVIEW));
        store.appendNote(id, "Marked for review");
        TaskDocument reread = parse(REPO_A, id);
        assertEquals(TaskStatus.REVIEW, updated.status());
        assertEquals(TaskStatus.REVIEW, reread.status());
        assertTrue(reread.rawBody().contains("Marked for review"));
        assertTrue(reread.rawFrontMatter().contains(unknown), "unknown nested key byte-identical");
        assertTrue(reread.rawFrontMatter().contains("# front matter comment"));
        assertEquals("Round trip task", reread.title());
        assertTrue(publishes.get() >= 2, "both committed mutations published");
        store.close();
    }

    /** 3: an edit made between mutations is applied to the fresh disk bytes. */
    @Test
    void externalEditIsAppliedToFreshBytes() throws Exception {
        TaskStore store = store();
        TaskDocument created = store.create(RepositoryId.of(REPO_A), "Original title", List.of());
        writeTask(REPO_A, created.id().text(), "---\ntitle: \"Externally edited title\"\nstatus: open\n"
                + "done_by: human\nafter: []\nsessions: []\nexternal_key: keep me\n"
                + "---\nEdited body externally.\n");
        store.update(created.id(), status(TaskStatus.DONE));
        TaskDocument fresh = parse(REPO_A, created.id());
        assertEquals(TaskStatus.DONE, fresh.status());
        assertEquals("Externally edited title", fresh.title(), "fresh disk bytes, not a cached parse");
        assertTrue(fresh.rawFrontMatter().contains("external_key: keep me"));
        store.close();
    }

    /** 4: a write that fails leaves the file untouched and publishes nothing. */
    @Test
    void failedWriteIntoReadOnlyDirectoryPublishesNothing() throws Exception {
        writeTask(REPO_A, "read-only-task", validTask("Read only task"));
        TaskId id = TaskId.of("read-only-task");
        String before = readTask(REPO_A, id.text());
        Path repoDir = root.resolve(REPO_A);
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(repoDir);
        Set<PosixFilePermission> readOnly = new HashSet<>(original);
        assertTrue(readOnly.remove(PosixFilePermission.OWNER_WRITE));
        Files.setPosixFilePermissions(repoDir, readOnly);
        TaskStore store = store();
        AtomicInteger publishes = new AtomicInteger();
        store.addListener(snapshot -> publishes.incrementAndGet());
        try {
            assertThrows(TaskStore.TaskStoreException.class, () -> store.update(id, status(TaskStatus.DONE)));
            assertEquals(before, readTask(REPO_A, id.text()), "the failed write touched nothing");
            assertEquals(0, publishes.get(), "a failed write publishes no snapshot");
        } finally {
            Files.setPosixFilePermissions(repoDir, original);
            store.close();
        }
    }

    /** 5: a conflicting test-local guard blocks update and create, publishes nothing. */
    @Test
    void guardConflictBlocksMutationsVisibly() throws Exception {
        writeTask(REPO_A, "conflict-task", validTask("Conflict task"));
        TaskId id = TaskId.of("conflict-task");
        String before = readTask(REPO_A, id.text());
        TaskStore store = new TaskStore(root, file -> file.getFileName().toString().contains("conflict-task"));
        AtomicInteger publishes = new AtomicInteger();
        store.addListener(snapshot -> publishes.incrementAndGet());
        TaskStore.TaskStoreException updateFailure = assertThrows(TaskStore.TaskStoreException.class,
                () -> store.update(id, status(TaskStatus.DONE)));
        assertTrue(updateFailure.getMessage().contains("unsaved changes"));
        TaskStore.TaskStoreException createFailure = assertThrows(TaskStore.TaskStoreException.class,
                () -> store.create(RepositoryId.of(REPO_A), "Conflict task", List.of()));
        assertTrue(createFailure.getMessage().contains("unsaved changes"));
        assertEquals(before, readTask(REPO_A, id.text()), "the conflicted update touched no file");
        assertEquals(0, publishes.get(), "a conflicted mutation publishes no snapshot");
        store.close();
    }

    /** 8: an unsubscribed listener receives no further committed snapshots. */
    @Test
    void unsubscribedListenerStopsReceivingSnapshots() throws Exception {
        TaskStore store = store();
        AtomicInteger publishes = new AtomicInteger();
        Runnable unsubscribe = store.addListener(snapshot -> publishes.incrementAndGet());
        store.create(RepositoryId.of(REPO_A), "First task", List.of());
        unsubscribe.run();
        TaskDocument second = store.create(RepositoryId.of(REPO_A), "Second task", List.of());
        store.appendNote(second.id(), "still committed, unheard");
        assertEquals(1, publishes.get(), "nothing published after unsubscribe");
        assertEquals(2, TaskFiles.scan(root).byId().size(), "the store kept committing regardless");
        store.close();
    }

    /** 9: missing, duplicate and broken ids refuse with their own distinct messages. */
    @Test
    void missingDuplicateAndBrokenIdsRefuseDistinctly() throws Exception {
        writeTask(REPO_A, "dup-task", validTask("Duplicate task"));
        writeTask(REPO_B, "dup-task", validTask("Duplicate task"));
        writeTask(REPO_A, "broken-one", "---\ntitle: \"Broken task\"\nstatus: no-such-status\n---\nBody.\n");
        TaskStore store = store();
        AtomicInteger publishes = new AtomicInteger();
        store.addListener(snapshot -> publishes.incrementAndGet());
        TaskStore.TaskStoreException missing = assertThrows(TaskStore.TaskStoreException.class,
                () -> store.update(TaskId.of("no-such-task"), status(TaskStatus.DONE)));
        assertTrue(missing.getMessage().contains("No task with id 'no-such-task'"));
        TaskStore.TaskStoreException duplicate = assertThrows(TaskStore.TaskStoreException.class,
                () -> store.update(TaskId.of("dup-task"), status(TaskStatus.DONE)));
        assertTrue(duplicate.getMessage().contains("claimed by more than one file"));
        TaskStore.TaskStoreException broken = assertThrows(TaskStore.TaskStoreException.class,
                () -> store.update(TaskId.of("broken-one"), status(TaskStatus.DONE)));
        assertTrue(broken.getMessage().contains("cannot be read"));
        assertEquals(0, publishes.get(), "a refused mutation publishes no snapshot");
        store.close();
    }

    /** 10: listeners see exactly the committed snapshots; failures publish nothing. */
    @Test
    void onlyCommittedSnapshotsArePublished() throws Exception {
        TaskStore store = store();
        List<TaskSnapshot> received = Collections.synchronizedList(new ArrayList<>());
        store.addListener(received::add);
        TaskDocument task = store.create(RepositoryId.of(REPO_A), "Publish task", List.of());
        assertThrows(TaskStore.TaskStoreException.class,
                () -> store.update(TaskId.of("missing-id"), status(TaskStatus.DONE)));
        store.update(task.id(), status(TaskStatus.STARTED));
        store.appendNote(task.id(), "one committed note");
        assertEquals(3, received.size(), "one publish per committed mutation, none for the failure");
        for (TaskSnapshot snapshot : received) {
            assertEquals(TaskFiles.scan(root).byId().keySet(), snapshot.byId().keySet(),
                    "every published snapshot is a full committed scan");
        }
        TaskDocument last = received.get(2).byId().get(task.id());
        assertEquals(TaskStatus.STARTED, last.status());
        assertTrue(last.rawBody().contains("one committed note"));
        store.close();
    }
}
