package app.drydock.intellij.delayedtasks;

import app.drydock.delayedtasks.DelayedTaskService;
import app.drydock.delayedtasks.TaskGotoDeclarationHandler;
import app.drydock.delayedtasks.TaskStoreWatcher;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.RepositorySettings;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.IsolatedCatalog;
import app.drydock.tasks.TaskId;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.TimeoutUtil;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * The T21b live-editor matrix for the accepted [[id]] handler (T21a, P5):
 * every case drives the REGISTERED production instance (from the
 * gotoDeclarationHandler extension point) with a real editor, caret and
 * document, resolving through the production watched snapshot over the
 * Gradle-sandboxed store root (asserted in setUp). The booted fixture
 * cannot reach the no-snapshot case (the production watcher publishes
 * during application startup, before any test can run), so that case parks
 * the published composition aside (a reflective write of the volatile
 * watchedSnapshot field, restored in a finally) and re-reads the field to
 * prove the window stayed clean. Waits pump the EDT and poll a deadline;
 * VfsRootAccess is the test-only permission for roots outside the project.
 */
public class TaskGotoDeclarationPlatformTest extends BasePlatformTestCase {

    private static final int INSIDE = 3; // chars past '[[': inside the reference text
    private static final long PUBLISH_SECONDS = 25; // the watcher re-reads every 5 s
    private static long counter;
    private Path root;
    private DrydockPluginService plugin;
    private Path outsideDirectory;
    private final List<Editor> editors = new ArrayList<>();
    private final List<Path> fixtures = new ArrayList<>();
    private final List<Repository> seededRepositories = new ArrayList<>();

    @Override
    protected void setUp() throws Exception {
        IsolatedCatalog.assertSandboxed(); // before anything can touch the plugin (T14 discipline)
        super.setUp();
        plugin = DrydockPluginService.getInstance();
        try {
            plugin.initialization().get(60, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Drydock initialization did not complete in 60 s", e);
        }
        root = plugin.taskService().root().toAbsolutePath().normalize();
        assertTrue("the production store root must be the Gradle test sandbox, never the"
                + " user's store: " + root,
                System.getProperty(DelayedTaskService.OVERRIDE_PROPERTY) != null
                        && root.startsWith(Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize())
                        && !root.startsWith(Path.of(System.getProperty("user.home"), ".drydock")));
        VfsRootAccess.allowRootAccess(getTestRootDisposable(), root.toString());
    }

    @Override
    protected void tearDown() throws Exception {
        for (Editor editor : new ArrayList<>(editors)) {
            try {
                EditorFactory.getInstance().releaseEditor(editor);
            } catch (RuntimeException ignored) {
            }
        }
        removeSeededRepositories();
        for (Path path : fixtures) {
            try { Files.deleteIfExists(path); } catch (IOException ignored) { }
        }
        for (Path directory : fixtures.stream().map(Path::getParent).distinct().toList()) {
            try { Files.deleteIfExists(directory); } catch (IOException ignored) { } // empty dirs only
        }
        delete(outsideDirectory);
        super.tearDown();
    }

    // 1. Non-project .md outside the store root: the handler offers nothing for it.
    public void testANonProjectDocumentOutsideTheStoreIsNeverNavigated() throws Exception {
        TaskId target = publishTask("Non-project target", RepositoryId.newId());
        outsideDirectory = Files.createTempDirectory("drydock-goto-noproject");
        // macOS resolves /var/folders to /private/var/...: the VFS canonicalizes
        // to the real path, so the allowlist needs both spellings.
        VfsRootAccess.allowRootAccess(getTestRootDisposable(), outsideDirectory.toString(),
                outsideDirectory.toRealPath().toString());
        Path note = outsideDirectory.resolve("note-outside-" + unique() + ".md");
        Files.writeString(note, referenceBody(target.text()));
        assertNoTargets(gotoInside(openEditor(note), target.text()), "a non-project .md outside"
                + " the store root (the same reference resolves from a store document)");
    }

    // 2. The caret inside the brackets resolves to the task file; outside offers nothing.
    public void testTheCaretInsideTheBracketsResolvesAndOutsideItDoesNot() throws Exception {
        RepositoryId repository = RepositoryId.newId();
        TaskId target = publishTask("Caret target", repository);
        Editor editor = openNote(repository, "caret-link", referenceBody(target.text()));
        assertResolvesTo(editor, target.text(), repository, target);
        assertNoTargets(gotoAt(editor, target.text(), target.text().length() + 5),
                "the caret past the closing brackets");
        assertNoTargets(gotoAt(editor, target.text(), -2), "the caret before the opening line");
    }

    // 3. Missing, ambiguous and unclosed references offer nothing, and never crash.
    public void testMissingAmbiguousAndUnclosedReferencesOfferNothingWithoutCrashing() throws Exception {
        RepositoryId repository = RepositoryId.newId();
        TaskId neighbour = publishTask("A resolvable neighbour", repository);
        RepositoryId second = RepositoryId.newId();
        String ambiguous = "clash-goto-" + unique();
        writeTask(repository, ambiguous, "The clashing task");
        writeTask(second, ambiguous, "The other clashing task");
        awaitWatched(c -> c.snapshot().idCollisions().stream()
                        .anyMatch(collision -> collision.id().text().equals(ambiguous)),
                "the publication of the id collision " + ambiguous);
        String missing = "missing-goto-" + unique();
        Editor refusing = openNote(repository, "refusing-link", "Depends on [["
                + neighbour.text() + "]] and [[" + missing + "]] and [["
                + ambiguous + "]] here, then a broken [[ never closed bracket.");
        assertNotNull("the control resolves first: the snapshot is serving this document",
                gotoInside(refusing, neighbour.text()));
        assertNoTargets(gotoInside(refusing, missing),
                "a reference to no stored task");
        assertNoTargets(gotoInside(refusing, ambiguous), "a reference to a colliding id");
        assertNoTargets(gotoAtRaw(refusing, refusing.getDocument().getText().indexOf("[[ never") + 4),
                "an unclosed bracket");
    }

    // 4. Unrelated Markdown follows the DIRECTORY restriction exactly: the reserved
    // plans/ and runs/ metadata documents are out of scope even with a resolving
    // reference, while a plain non-task .md under the root IS in scope
    // (isTaskDocument is directory-based, not content-based).
    public void testUnrelatedMarkdownFollowsTheDirectoryRestrictionExactly() throws Exception {
        RepositoryId repository = RepositoryId.newId();
        TaskId target = publishTask("Restriction target", repository);
        for (String reserved : List.of("plans", "runs")) {
            assertNoTargets(gotoInside(openNote(reserved, reserved, referenceBody(target.text())),
                    target.text()), "a " + reserved + "/ metadata document inside the store root");
        }
        assertResolvesTo(openNote(repository, "plain-non-task", referenceBody(target.text())),
                target.text(), repository, target);
    }

    // 5. A qualified [[project:id]] reference resolves to the right task file.
    public void testAQualifiedProjectReferenceResolvesToTheRightTaskFile() throws Exception {
        RepositoryId alpha = RepositoryId.newId();
        RepositoryId beta = RepositoryId.newId();
        seedProjectRepository(alpha, "Goto Alpha");
        seedProjectRepository(beta, "Goto Beta");
        TaskId inAlpha = publishTask("Qualified in alpha", alpha);
        TaskId inBeta = publishTask("Qualified in beta", beta);
        Editor editor = openNote(alpha, "qualified-link", "Depends on [[goto-alpha:" + inAlpha.text()
                + "]] and [[goto-beta:" + inBeta.text() + "]] and [[goto-beta:" + inAlpha.text() + "]] here.\n");
        assertResolvesTo(editor, "goto-alpha:" + inAlpha.text(), alpha, inAlpha);
        assertResolvesTo(editor, "goto-beta:" + inBeta.text(), beta, inBeta);
        assertNoTargets(gotoInside(editor, "goto-beta:" + inAlpha.text()),
                "a qualified reference naming the wrong project");
    }

    // 6. No published snapshot: the handler offers nothing, no blocking fetch.
    public void testWithNoPublishedSnapshotTheHandlerOffersNothingWithoutFetching() throws Exception {
        TaskId target = publishTask("No-snapshot target", RepositoryId.newId());
        Editor editor = openNote(RepositoryId.newId(), "no-snapshot-link", referenceBody(target.text()));
        assertNotNull("the control resolves with the published composition",
                gotoInside(editor, target.text()));
        Field watched = DelayedTaskService.class.getDeclaredField("watchedSnapshot");
        watched.setAccessible(true);
        Object service = plugin.taskService();
        TaskStoreWatcher.WatchedSnapshot parked = (TaskStoreWatcher.WatchedSnapshot) watched.get(service);
        assertNotNull("the booted IDE's watcher has published a composition", parked);
        try {
            PsiElement[] offered = null;
            long start = System.nanoTime();
            boolean clean = false;
            for (int attempt = 0; attempt < 50 && !clean; attempt++) {
                watched.set(service, null); // the pre-publication state, parked aside
                start = System.nanoTime();
                offered = gotoInside(editor, target.text());
                clean = watched.get(service) == null; // no publication raced this window
                parked = clean ? parked : (TaskStoreWatcher.WatchedSnapshot) watched.get(service);
            }
            assertTrue("the parked window never stayed clean; publications race every retry", clean);
            assertNoTargets(offered, "a document whose service has no published snapshot");
            assertTrue("the handler answers without a blocking fetch",
                    System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
        } finally {
            watched.set(service, parked);
        }
        assertEquals("the parked composition is restored", parked, plugin.taskService().watchedSnapshot());
    }

    /** One store task file (title-only front matter; the reader defaults the rest). */
    private TaskId writeTask(RepositoryId repository, String idText, String title) throws IOException {
        TaskId id = TaskId.of(idText);
        Path file = root.resolve(repository.toString()).resolve(id.text() + ".md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "---\ntitle: " + title + "\n---\n\nA body without references.\n");
        fixtures.add(file);
        return id;
    }

    /** A store task, awaited into the published snapshot before it is used. */
    private TaskId publishTask(String title, RepositoryId repository) throws IOException {
        TaskId id = writeTask(repository, "goto-" + unique(), title);
        awaitWatched(c -> c.snapshot().byId().containsKey(id), "the publication of " + id);
        return id;
    }

    /** A plain document under any root segment (a repository, or a reserved
     * name such as plans/), opened in a real editor: the handler scopes
     * documents by directory, not by task content. */
    private Editor openNote(Object segment, String stem, String body) throws IOException {
        Path file = root.resolve(segment.toString()).resolve(stem + "-" + unique() + ".md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "A bracket-free opening line.\n\n" + body + "\n");
        fixtures.add(file);
        return openEditor(file);
    }

    /** A body carrying one reference between bracket-free lines. */
    private static String referenceBody(String ref) {
        return "Depends on [[" + ref + "]] here.\n\nA bracket-free closing line.\n";
    }

    /** Adopts the file into the VFS (a real platform refresh) and opens a real editor. */
    private Editor openEditor(Path file) {
        VirtualFile virtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file);
        assertNotNull("the document file is visible in the VFS: " + file, virtual);
        Document document = FileDocumentManager.getInstance().getDocument(virtual);
        assertNotNull("a document is opened for " + file, document);
        Editor editor = EditorFactory.getInstance().createEditor(document, getProject());
        editors.add(editor);
        return editor;
    }

    /** The registered production instance; proves the plugin.xml registration too. */
    private static TaskGotoDeclarationHandler registeredHandler() {
        for (GotoDeclarationHandler handler : GotoDeclarationHandler.EP_NAME.getExtensionList()) {
            if (handler instanceof TaskGotoDeclarationHandler drydock) {
                return drydock;
            }
        }
        fail("the gotoDeclarationHandler extension carries no Drydock TaskGotoDeclarationHandler");
        return null;
    }

    /** The handler with the caret inside the reference's brackets. */
    private PsiElement[] gotoInside(Editor editor, String ref) {
        return gotoAt(editor, ref, INSIDE);
    }

    /** The handler with the caret {@code pastOpen} chars from '[[' (negative: before it). */
    private PsiElement[] gotoAt(Editor editor, String ref, int pastOpen) {
        Document document = editor.getDocument();
        int open = document.getText().indexOf("[[" + ref + "]]");
        assertTrue("the document carries the reference [[" + ref + "]]", open >= 0);
        return gotoAtRaw(editor, open + pastOpen);
    }

    /** The handler at an explicit caret offset, with the real caret model moved there. */
    private PsiElement[] gotoAtRaw(Editor editor, int caret) {
        assertTrue("the caret stays inside the document",
                caret >= 0 && caret <= editor.getDocument().getTextLength());
        editor.getCaretModel().moveToOffset(caret);
        PsiFile psi = PsiDocumentManager.getInstance(getProject()).getPsiFile(editor.getDocument());
        PsiElement at = psi == null ? null : psi.findElementAt(caret);
        return registeredHandler().getGotoDeclarationTargets(at != null ? at : psi, caret, editor);
    }

    /** Asserts the caret resolves to exactly the referenced task's store file. */
    private void assertResolvesTo(Editor editor, String ref, RepositoryId repository, TaskId id) {
        PsiElement[] offered = gotoInside(editor, ref);
        assertTrue("the reference " + ref + " resolves to the task's file element",
                offered != null && offered.length == 1 && offered[0] instanceof PsiFile);
        assertEquals(root.resolve(repository.toString()).resolve(id.text() + ".md"),
                ((PsiFile) offered[0]).getVirtualFile().toNioPath().toAbsolutePath().normalize());
    }

    private static void assertNoTargets(PsiElement[] offered, String what) {
        assertTrue(what + " offers no navigation targets", offered == null || offered.length == 0);
    }

    /** Seeds a catalog repository through the plugin's single writer, guarded
     * like every catalog write (IsolatedCatalog): the qualified form resolves
     * through the live project-slug mapping, which needs a catalog entry. */
    private void seedProjectRepository(RepositoryId id, String displayName) {
        Repository repository = new Repository(id, root.resolve(id.toString()), displayName,
                Instant.EPOCH, Instant.EPOCH, RepositorySettings.DEFAULT);
        seededRepositories.add(repository);
        plugin.stateStore().update(state -> {
            List<Repository> repositories = new ArrayList<>(state.repositories());
            repositories.add(repository);
            return state.withRepositories(repositories);
        });
    }

    private void removeSeededRepositories() {
        if (plugin == null || seededRepositories.isEmpty()) {
            return;
        }
        plugin.stateStore().update(state -> state.withRepositories(state.repositories().stream()
                .filter(existing -> seededRepositories.stream()
                        .noneMatch(seed -> seed.id().equals(existing.id())))
                .toList()));
        plugin.stateStore().flush();
        seededRepositories.clear();
    }

    /** Spins (never sleeps) until the published composition satisfies the condition. */
    private void awaitWatched(Predicate<TaskStoreWatcher.WatchedSnapshot> condition, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PUBLISH_SECONDS);
        TaskStoreWatcher.WatchedSnapshot composition = plugin.taskService().watchedSnapshot();
        while (composition == null || !condition.test(composition)) {
            assertTrue("the store watcher did not publish " + what + " within " + PUBLISH_SECONDS
                    + " s; last composition: " + composition, System.nanoTime() < deadline);
            pump();
            composition = plugin.taskService().watchedSnapshot();
        }
    }

    /** Drains rounds of the EDT queue (the test runs on it) so watcher flushes run. */
    private static void pump() {
        for (int i = 0; i < 3; i++) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            TimeoutUtil.sleep(20);
        }
    }

    /** A unique, file-name-safe suffix (ids must survive previous runs of the suite). */
    private static String unique() {
        return Long.toUnsignedString(System.nanoTime(), 36) + "-" + Long.toString(counter++, 36);
    }

    private void delete(Path directory) {
        if (directory == null) {
            return;
        }
        try (Stream<Path> entries = Files.walk(directory)) {
            entries.sorted(Comparator.reverseOrder()).forEach(
                    path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) { } });
        } catch (IOException ignored) {
        }
    }
}
