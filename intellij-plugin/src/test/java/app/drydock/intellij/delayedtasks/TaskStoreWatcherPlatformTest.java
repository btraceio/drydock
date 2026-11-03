package app.drydock.intellij.delayedtasks;

import app.drydock.delayedtasks.DelayedTaskService;
import app.drydock.delayedtasks.TaskStoreWatcher;
import app.drydock.domain.RepositoryId;
import app.drydock.mcp.TaskToolContext;
import app.drydock.tasks.TaskDocument;
import app.drydock.tasks.TaskId;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.util.TimeoutUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * The T13b acceptance matrix for the accepted store watcher, in a booted
 * IDE: each test owns a real store root, and the service itself starts the
 * production watcher (VFS_CHANGES subscription, periodic re-read, D6/P5
 * unsaved guard) exactly as the plugin does. Every wait pumps the EDT and
 * polls with a deadline, never a bare sleep; VfsRootAccess is the test-only
 * permission for temp roots outside every project.
 */
public class TaskStoreWatcherPlatformTest extends BasePlatformTestCase {

    // The debounce plus one refresh, also covering the first periodic re-read (some fixture changes fire no VFS event).
    private static final long EVENT_SECONDS = 8;
    private static final long PERIODIC_SECONDS = 15; // the watcher re-reads every 5 s
    private static final long GRACE_MILLIS = 700; // a second, non-coalesced cycle would follow

    private Path root;
    private DelayedTaskService service;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        root = Files.createTempDirectory("drydock-watch-store");
        VfsRootAccess.allowRootAccess(getTestRootDisposable(), root.toString());
        service = new DelayedTaskService(root, Runnable::run, List::of, List::of);
        awaitWatched(c -> c.snapshot().documents().isEmpty() && c.saveConflicts().isEmpty()
                && c.scanError() == null, "the initial empty composition", EVENT_SECONDS);
    }

    @Override
    protected void tearDown() throws Exception {
        if (service != null) {
            service.close();
        }
        super.tearDown();
        delete(root);
    }

    // 1. External create, atomic replace and delete of files the IDE never opened (P5).
    public void testExternalCreateReplaceAndDeleteOfUnopenedFilesAreObserved() throws IOException {
        RepositoryId repository = RepositoryId.newId();
        TaskId id = createTask("Fix the login redirect", repository);
        Path file = storeFile(repository, id);
        TaskStoreWatcher.WatchedSnapshot created = awaitWatched(c -> c.snapshot().byId().containsKey(id),
                "the external creation of " + id, PERIODIC_SECONDS);
        assertEquals("Fix the login redirect", titleOf(created, id));
        assertTrue(created.scanError() == null && created.saveConflicts().isEmpty());
        Path temp = Files.createTempFile(root.getParent(), "replace", ".md.tmp");
        Files.writeString(temp, taskFile("Replace the login redirect"));
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        awaitWatched(c -> "Replace the login redirect".equals(titleOf(c, id)),
                "the atomic replacement of " + id, PERIODIC_SECONDS);

        Files.delete(file);
        TaskStoreWatcher.WatchedSnapshot deleted = awaitWatched(composition ->
                        !composition.snapshot().byId().containsKey(id)
                                && composition.saveConflicts().isEmpty() && composition.scanError() == null,
                "the external deletion of " + id, PERIODIC_SECONDS);
        assertTrue("The store scans empty after the delete", deleted.snapshot().documents().isEmpty());
    }

    // 2. An IDE save of an opened document updates the published snapshot.
    public void testAnIdeSaveUpdatesThePublishedSnapshot() {
        RepositoryId repository = RepositoryId.newId();
        TaskId id = createTask("Ide save updates the snapshot", repository);
        Document document = openDocument(storeFile(repository, id));
        awaitWatched(c -> c.snapshot().byId().containsKey(id), "the opened task settling", EVENT_SECONDS);

        editAndSave(document, taskFile("Ide save changed the title"));
        TaskStoreWatcher.WatchedSnapshot saved = awaitWatched(
                c -> "Ide save changed the title".equals(titleOf(c, id)), "the IDE save reaching the snapshot",
                EVENT_SECONDS);
        assertTrue(saved.scanError() == null && saved.saveConflicts().isEmpty()
                && saved.snapshot().failures().isEmpty());
    }

    // 3. A malformed entry edited valid loses its per-entry scan failure.
    public void testAnInvalidEntryEditedValidClearsItsError() throws IOException {
        RepositoryId repository = RepositoryId.newId();
        createTask("Valid neighbour of a malformed entry", repository);
        Path malformed = root.resolve(repository.toString()).resolve("malformed-watcher-entry-x1.md");
        TaskId malformedId = TaskId.of("malformed-watcher-entry-x1");
        Files.writeString(malformed, "this entry has no front matter");
        Document document = openDocument(malformed);
        TaskStoreWatcher.WatchedSnapshot invalid = awaitWatched(composition ->
                        composition.snapshot().failures().stream()
                                .anyMatch(failure -> failure.path().equals(malformed)),
                "the malformed entry's scan failure", EVENT_SECONDS);
        assertTrue("The malformed entry is refused, never indexed",
                !invalid.snapshot().byId().containsKey(malformedId) && invalid.scanError() == null);
        editAndSave(document, taskFile("Recovered from a malformed entry"));
        TaskStoreWatcher.WatchedSnapshot valid = awaitWatched(composition ->
                        composition.snapshot().byId().containsKey(malformedId)
                                && composition.snapshot().failures().stream()
                                        .noneMatch(failure -> failure.path().equals(malformed)),
                "the recovered entry", EVENT_SECONDS);
        assertEquals("Recovered from a malformed entry", titleOf(valid, malformedId));
        assertTrue(valid.scanError() == null && valid.saveConflicts().isEmpty());
    }

    // 4. An unsaved document refuses the reload (D6/P5): reuse the last snapshot, never clobber the buffer, saving clears it.
    public void testAnUnsavedConflictRefusesTheReloadWithoutClobbering() throws IOException {
        RepositoryId repository = RepositoryId.newId();
        TaskId conflictedId = createTask("Conflicted but not clobbered", repository);
        TaskId triggerId = createTask("Cycle trigger for the conflict", repository);
        Document conflicted = openDocument(storeFile(repository, conflictedId));
        Document trigger = openDocument(storeFile(repository, triggerId));
        awaitWatched(composition -> composition.snapshot().byId().containsKey(conflictedId)
                        && composition.snapshot().byId().containsKey(triggerId),
                "both opened tasks settling", EVENT_SECONDS);
        String unsavedText = taskFile("Unsaved local edit");
        ApplicationManager.getApplication().runWriteAction(() -> conflicted.setText(unsavedText)); // deliberately unsaved
        TaskId siblingId = TaskId.of("external-sibling-y2");
        Files.writeString(root.resolve(repository.toString()).resolve(siblingId.text() + ".md"),
                taskFile("External sibling on disk"));
        TaskStoreWatcher.WatchedSnapshot settled = settleFor(GRACE_MILLIS);
        editAndSave(trigger, taskFile("Trigger save after the conflict"));
        TaskStoreWatcher.WatchedSnapshot conflict = awaitWatched(composition ->
                        composition != settled && !composition.saveConflicts().isEmpty(),
                "the unsaved-conflict composition", EVENT_SECONDS);
        assertEquals(1, conflict.saveConflicts().size());
        assertEquals(storeFile(repository, conflictedId), conflict.saveConflicts().get(0).file());
        assertSame("A refused reload reuses the last snapshot; nothing was re-read",
                settled.snapshot(), conflict.snapshot());
        assertFalse("The external sibling stays unpublished while the reload is refused",
                conflict.snapshot().byId().containsKey(siblingId));
        assertEquals("The unsaved editor buffer is not clobbered", unsavedText, conflicted.getText());
        FileDocumentManager.getInstance().saveDocument(conflicted);
        TaskStoreWatcher.WatchedSnapshot cleared = awaitWatched(composition -> composition.saveConflicts().isEmpty()
                        && "Unsaved local edit".equals(titleOf(composition, conflictedId))
                        && composition.snapshot().byId().containsKey(siblingId),
                "the re-read after the save cleared the conflict", EVENT_SECONDS);
        assertTrue(cleared.scanError() == null);
    }

    // 5. A burst of rapid save events coalesces into exactly one re-read.
    public void testABurstOfSaveEventsCoalescesIntoOneReRead() {
        RepositoryId repository = RepositoryId.newId();
        TaskId id = createTask("Burst source task", repository);
        Document document = openDocument(storeFile(repository, id));
        awaitWatched(c -> c.snapshot().byId().containsKey(id), "the burst source settling", EVENT_SECONDS);
        TaskStoreWatcher.WatchedSnapshot baseline = settleFor(GRACE_MILLIS);
        for (int round = 1; round <= 8; round++)
            editAndSave(document, taskFile("Burst round " + round));
        TaskStoreWatcher.WatchedSnapshot burst = awaitWatched(
                c -> c != baseline && "Burst round 8".equals(titleOf(c, id)),
                "the coalesced re-read", EVENT_SECONDS);
        long end = System.currentTimeMillis() + GRACE_MILLIS;
        while (System.currentTimeMillis() < end) { // no second composition follows the burst
            assertSame("Eight rapid saves coalesce into one re-read", burst, service.watchedSnapshot());
            pump();
        }
    }

    // 6. A closed service publishes nothing: the coalescing thread stops, no composition follows.
    public void testAClosedServicePublishesNothing() {
        RepositoryId repository = RepositoryId.newId();
        TaskId id = createTask("Closed before any further publication", repository);
        Document document = openDocument(storeFile(repository, id));
        awaitWatched(c -> c.snapshot().byId().containsKey(id), "the pre-close composition", EVENT_SECONDS);
        TaskStoreWatcher.WatchedSnapshot closed = settleFor(GRACE_MILLIS);
        int liveWatchers = watchThreadCount(); // captured before the close: the thread is surely alive
        service.close();
        long end = System.currentTimeMillis() + 5_000;
        while (watchThreadCount() >= liveWatchers) {
            assertTrue("The watcher's coalescing thread 'drydock-task-store-watch' outlived the close;"
                    + " a live scheduler can still publish", System.currentTimeMillis() < end);
            pump();
        }
        editAndSave(document, taskFile("Edited after the close"));
        end = System.currentTimeMillis() + GRACE_MILLIS;
        while (System.currentTimeMillis() < end) { // nothing is published after the close
            assertSame("A closed watcher publishes nothing", closed, service.watchedSnapshot());
            pump();
        }
    }

    /** Drains rounds of the EDT queue (the test runs on it) so the watcher's refresh flushers run. */
    private static void pump() {
        for (int i = 0; i < 3; i++) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            TimeoutUtil.sleep(20);
        }
    }

    /** Spins (never sleeps) until the published composition satisfies the condition, or fails. */
    private TaskStoreWatcher.WatchedSnapshot awaitWatched(
            Predicate<TaskStoreWatcher.WatchedSnapshot> condition, String what, long seconds) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        TaskStoreWatcher.WatchedSnapshot composition = service.watchedSnapshot();
        while (composition == null || !condition.test(composition)) {
            assertTrue("The watcher did not publish " + what + " within " + seconds
                    + " s; last composition: " + composition, System.nanoTime() < deadline);
            pump();
            composition = service.watchedSnapshot();
        }
        return composition;
    }

    /** A quiescence barrier: answers the composition once it stops being replaced. */
    private TaskStoreWatcher.WatchedSnapshot settleFor(long millis) {
        TaskStoreWatcher.WatchedSnapshot stable = service.watchedSnapshot();
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            TaskStoreWatcher.WatchedSnapshot current = service.watchedSnapshot();
            if (current != stable) { // wait a full window after every change
                stable = current;
                end = System.currentTimeMillis() + millis;
            }
            pump();
        }
        return stable;
    }

    /** A valid task file: title-only front matter (the reader defaults the rest). */
    private static String taskFile(String title) {
        return "---\ntitle: " + title + "\n---\n\nBody written by the watcher acceptance fixtures.\n";
    }

    private TaskId createTask(String title, RepositoryId repository) {
        TaskToolContext.Outcome outcome = service.create(title, List.of(), repository).join();
        assertFalse("The task creation was refused: " + outcome.refusal(), outcome.refused());
        return outcome.document().id();
    }

    private Path storeFile(RepositoryId repository, TaskId id) {
        return root.resolve(repository.toString()).resolve(id.text() + ".md");
    }

    private static String titleOf(TaskStoreWatcher.WatchedSnapshot composition, TaskId id) {
        TaskDocument document = composition.snapshot().byId().get(id);
        return document == null ? null : document.title();
    }

    /** Adopts the file into the VFS (a real platform refresh) and opens its document. */
    private Document openDocument(Path file) {
        VirtualFile virtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file);
        assertNotNull("The task file is not visible in the VFS: " + file, virtual);
        Document document = FileDocumentManager.getInstance().getDocument(virtual);
        assertNotNull("No document is opened for " + file, document);
        return document;
    }

    /** One IDE edit and save of exactly this document: the platform's own write path and events. */
    private static void editAndSave(Document document, String text) {
        ApplicationManager.getApplication().runWriteAction(() -> document.setText(text));
        FileDocumentManager.getInstance().saveDocument(document);
    }

    /** Live watcher coalescing threads; the only threads that publish. */
    private static int watchThreadCount() {
        return (int) Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> "drydock-task-store-watch".equals(thread.getName())).count();
    }

    private static void delete(Path directory) {
        if (directory == null) {
            return;
        }
        try (Stream<Path> entries = Files.walk(directory)) {
            entries.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
