package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;

import com.intellij.ide.impl.OpenProjectTask;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.notification.NotificationAction;
import com.intellij.openapi.fileEditor.FileEditorComposite;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorOpenRequest;
import com.intellij.openapi.fileEditor.impl.FileEditorManagerImpl;
import com.intellij.openapi.components.ComponentManagerEx;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ex.ProjectManagerEx;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.HeavyPlatformTestCase;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.ServiceContainerUtil;
import com.intellij.testFramework.TestActionEvent;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/**
 * The passive legacy-to-canonical conversion through real opens of the restored placeholders, a
 * fresh project per test: the canonical document appears once, code stays, nothing starts, and the
 * shown session is only ever the remembered NEW-format id.
 */
public class SessionHostMigrationPlatformTest extends HeavyPlatformTestCase {

    /** What the canonical open does on call {@code n}: the real open unless a scenario scripts it. */
    private interface Script {
        CompletableFuture<FileEditorComposite> open(int call, Script real);
    }

    /** The production manager, with only the canonical document's open call scriptable. */
    private static final class ScriptedManager extends FileEditorManagerImpl {
        private Script script = (call, real) -> real.open(call, real);
        private int canonicalCalls;

        ScriptedManager(Project project) {
            super(project, ((ComponentManagerEx) project).getCoroutineScope());
        }

        @Override
        public CompletableFuture<FileEditorComposite> requestOpenFile(VirtualFile file, FileEditorOpenRequest request) {
            if (!(file instanceof SessionHostFile)) {
                return super.requestOpenFile(file, request);
            }
            int call = ++canonicalCalls;
            return script.open(call, (n, ignored) -> super.requestOpenFile(file, request));
        }
    }

    private final List<AgentConsoleFile> legacy = new ArrayList<>();
    private final List<Notification> shown = new CopyOnWriteArrayList<>();
    private SessionHostFile canonical;
    private VirtualFile code;
    private ScriptedManager files;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // The production manager: the light one rejects the passive (non-selecting) open request.
        files = new ScriptedManager(getProject());
        ServiceContainerUtil.replaceService(getProject(), FileEditorManager.class, files, getTestRootDisposable());
        getProject().getMessageBus().connect(getTestRootDisposable()).subscribe(Notifications.TOPIC,
                new Notifications() {
                    @Override
                    public void notify(Notification notification) {
                        shown.add(notification);
                    }
                });
        canonical = AgentConsoleFileSystem.getInstance().sessionHostFile();
        // A restored workspace has a selected code tab; background opens need that window.
        code = getTempDir().createVirtualFile("code.txt", "class A {}");
        files.openFile(code, true);
        waitFor(() -> List.of(files.getSelectedFiles()).contains(code));
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            closeEverything(files);
        } finally {
            super.tearDown();
        }
    }

    private static void closeEverything(FileEditorManagerImpl manager) {
        for (VirtualFile open : manager.getOpenFiles()) {
            for (FileEditor editor : manager.getAllEditors(open)) {
                Disposer.dispose(editor);
            }
            manager.closeFile(open);
        }
    }

    private static void waitFor(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("condition not met within 10 s");
            }
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            Thread.sleep(20);
        }
    }

    private AgentConsoleFile openLegacy(ManagedSessionId id) {
        return openLegacy(files, id);
    }

    private AgentConsoleFile openLegacy(FileEditorManagerImpl manager, ManagedSessionId id) {
        AgentConsoleFile file = AgentConsoleFileSystem.getInstance().fileFor(id);
        legacy.add(file);
        // As a restore opens a tab: in the background, selecting nothing.
        manager.requestOpenFile(file, FileEditorOpenRequest.defaults().withSelectAsCurrent(false)
                .withRequestFocus(false).withUsePreviewTab(false));
        return file;
    }

    private void openTwentyLegacy() {
        for (int i = 0; i < 20; i++) {
            openLegacy(ManagedSessionId.newId());
        }
    }

    private void awaitCanonical() throws InterruptedException {
        waitFor(() -> files.getAllEditors(canonical).length == 1);
        waitFor(() -> SessionHostMigration.getInstance(getProject()).opens() > 0);
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
    }

    private void assertNothingStarted() {
        for (AgentConsoleFile file : legacy) {
            assertTrue("a legacy open never attaches an execution",
                    AgentExecutionRegistry.getInstance().execution(file.sessionId()).isEmpty());
        }
        assertTrue(AgentExecutionService.getInstance(getProject()).knownSessions().isEmpty());
    }

    private void assertOneCanonicalHost() {
        assertEquals(1, files.getAllEditors(canonical).length);
        assertTrue(files.getAllEditors(canonical)[0] instanceof SessionHostEditor);
    }

    public void testTwentyLegacyEntriesAndCodeConvertToOneCanonicalHostAndCodeStaysSelected() throws Exception {
        openTwentyLegacy();
        awaitCanonical();

        assertOneCanonicalHost();
        assertEquals("twenty notifications in one turn are one open", 1,
                SessionHostMigration.getInstance(getProject()).opens());
        assertTrue("code stays open", files.isFileOpen(code));
        assertEquals("code stays the selected document", List.of(code), List.of(files.getSelectedFiles()));
        waitFor(() -> legacy.stream().noneMatch(files::isFileOpen));
        assertEquals("every identified placeholder is closed once migration completes", 0,
                legacy.stream().filter(files::isFileOpen).count());
        assertOneCanonicalHost();
        assertTrue("code stays open after the placeholders close", files.isFileOpen(code));
        assertNothingStarted();
    }

    public void testDuplicateUnknownAndMalformedUuidsAreIdempotent() throws Exception {
        ManagedSessionId id = ManagedSessionId.newId();
        AgentConsoleFile duplicated = openLegacy(id);
        assertSame("a duplicate entry is the same file", duplicated, openLegacy(id));
        openLegacy(ManagedSessionId.newId());
        assertNull("a malformed URL resolves to no file, so there is no event for it",
                AgentConsoleFileSystem.getInstance().findFileByPath("not-a-uuid"));
        awaitCanonical();
        SessionHostMigration migration = SessionHostMigration.getInstance(getProject());
        assertEquals(1, migration.opens());

        // Closing the document and the tab, then restoring the same address, converts again.
        waitFor(() -> !files.isFileOpen(duplicated));
        assertTrue("code stays open", files.isFileOpen(code));
        files.closeFile(canonical);
        openLegacy(id);
        waitFor(() -> files.getAllEditors(canonical).length == 1);

        assertOneCanonicalHost();
        assertEquals("one more open, never a second host", 2, migration.opens());
        assertNothingStarted();
    }

    public void testNoRememberedIdMeansNoSelectionWhateverTheLegacyOrder() throws Exception {
        openTwentyLegacy();
        awaitCanonical();

        SessionHostController.Layout layout = SessionHostController.getInstance(getProject()).layout();
        assertEquals(Optional.empty(), layout.primary());
        assertEquals(Optional.empty(), layout.current());
        assertEquals(Optional.empty(), SessionHostState.getInstance(getProject()).selectedSession());
    }

    public void testOnlyARememberedNewFormatIdIsShownEvenWhenItIsUnknown() throws Exception {
        ManagedSessionId remembered = ManagedSessionId.newId();
        SessionHostState.getInstance(getProject()).setSelectedSession(Optional.of(remembered));

        openTwentyLegacy();
        awaitCanonical();

        SessionHostController.Layout layout = SessionHostController.getInstance(getProject()).layout();
        assertEquals("the remembered id, never a legacy one", Optional.of(remembered), layout.primary());
        assertEquals("passive: nothing becomes the intentional current", Optional.empty(), layout.current());
        assertNothingStarted();
    }

    public void testNotificationsInSeparateTurnsBeforeTheOpenRunsShareOneOpen() throws Exception {
        // Queued ahead of the migration's own queued pass, so its event is a later turn.
        ApplicationManager.getApplication().invokeLater(() -> openLegacy(ManagedSessionId.newId()),
                ModalityState.nonModal());
        openLegacy(ManagedSessionId.newId());
        waitFor(() -> legacy.size() == 2);
        awaitCanonical();

        assertOneCanonicalHost();
        assertEquals("both turns' events were served by one open", 1,
                SessionHostMigration.getInstance(getProject()).opens());
        assertNothingStarted();
    }

    private CompletableFuture<FileEditorComposite> delayedUntil(CompletableFuture<Void> catalog, Script real) {
        return catalog.thenCompose(arrived -> real.open(0, real));
    }

    private void drain() {
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
    }

    private void assertCodeUntouched() {
        assertTrue("code stays open", files.isFileOpen(code));
        assertEquals("code stays the selected document", List.of(code), List.of(files.getSelectedFiles()));
    }

    public void testDelayedCatalogConversionWaitsAndCompletesWhenItArrives() throws Exception {
        CompletableFuture<Void> catalog = new CompletableFuture<>();
        files.script = (call, real) -> delayedUntil(catalog, real);
        openLegacy(ManagedSessionId.newId());
        waitFor(() -> files.canonicalCalls == 1);
        openLegacy(ManagedSessionId.newId());
        waitFor(() -> legacy.size() == 2 && files.isFileOpen(legacy.get(1)));
        drain();

        assertEquals("the open waits for the catalog", 0, files.getAllEditors(canonical).length);
        assertEquals("events during the wait start no second open", 1, files.canonicalCalls);
        catalog.complete(null);
        awaitCanonical();

        assertOneCanonicalHost();
        assertEquals(1, files.canonicalCalls);
        assertTrue("a delayed catalog is no failure", shown.isEmpty());
        assertCodeUntouched();
        assertNothingStarted();
    }

    public void testEachProjectReconcilesItsOwnLegacyTabsWithoutBleed() throws Exception {
        Project other = ProjectManagerEx.getInstanceEx().newProject(Files.createTempDirectory("t25b-other"),
                OpenProjectTask.build());
        assertNotNull(other);
        ScriptedManager otherFiles = new ScriptedManager(other);
        try {
            ServiceContainerUtil.replaceService(other, FileEditorManager.class, otherFiles, getTestRootDisposable());
            VirtualFile otherCode = getTempDir().createVirtualFile("other.txt", "class B {}");
            otherFiles.openFile(otherCode, true);
            waitFor(() -> List.of(otherFiles.getSelectedFiles()).contains(otherCode));

            openLegacy(otherFiles, ManagedSessionId.newId());
            waitFor(() -> otherFiles.getAllEditors(canonical).length == 1);
            waitFor(() -> SessionHostMigration.getInstance(other).opens() == 1);
            drain();
            assertEquals("the other window is converted", 1, otherFiles.getAllEditors(canonical).length);
            assertEquals("this window is untouched", 0, files.getAllEditors(canonical).length);
            assertEquals(0, SessionHostMigration.getInstance(getProject()).opens());
            assertEquals(0, files.canonicalCalls);

            openLegacy(ManagedSessionId.newId());
            awaitCanonical();
            assertOneCanonicalHost();
            assertEquals("each project made exactly one open", 1, SessionHostMigration.getInstance(other).opens());
            assertEquals(1, SessionHostMigration.getInstance(getProject()).opens());
            assertEquals(1, otherFiles.canonicalCalls);
            assertEquals(1, files.canonicalCalls);
            assertNothingStarted();
        } finally {
            closeEverything(otherFiles);
            assertTrue(ProjectManagerEx.getInstanceEx().forceCloseProject(other));
        }
    }

    public void testInterleavedBatchesCoalesceWhileOpenAndConvertAgainAfterTheDocumentCloses() throws Exception {
        CompletableFuture<Void> catalog = new CompletableFuture<>();
        files.script = (call, real) -> call == 1 ? delayedUntil(catalog, real) : real.open(call, real);
        openLegacy(ManagedSessionId.newId());
        waitFor(() -> files.canonicalCalls == 1);
        for (int i = 0; i < 3; i++) {
            openLegacy(ManagedSessionId.newId());
            drain();
        }
        catalog.complete(null);
        awaitCanonical();
        drain();
        assertEquals("four events across the open are one open", 1, files.canonicalCalls);

        files.closeFile(canonical);
        waitFor(() -> files.getAllEditors(canonical).length == 0);
        openLegacy(ManagedSessionId.newId());
        openLegacy(ManagedSessionId.newId());
        waitFor(() -> files.getAllEditors(canonical).length == 1);
        drain();

        assertOneCanonicalHost();
        assertEquals("the later batch is one more open", 2, files.canonicalCalls);
        assertEquals(2, SessionHostMigration.getInstance(getProject()).opens());
        assertTrue(shown.isEmpty());
        assertCodeUntouched();
        assertNothingStarted();
    }

    public void testCancelledOpenClearsTheMarkerAndALaterEventRetries() throws Exception {
        CompletableFuture<FileEditorComposite> cancelled = new CompletableFuture<>();
        files.script = (call, real) -> call == 1 ? cancelled : real.open(call, real);
        openLegacy(ManagedSessionId.newId());
        waitFor(() -> files.canonicalCalls == 1);
        cancelled.cancel(true);
        // The completion handler's last act is the report, so a report means the marker is cleared.
        waitFor(() -> !shown.isEmpty());
        assertEquals("nothing was opened by the cancelled call", 0, files.getAllEditors(canonical).length);
        assertCodeUntouched();

        openLegacy(ManagedSessionId.newId());
        awaitCanonical();

        assertOneCanonicalHost();
        assertEquals("the retry is a second call", 2, files.canonicalCalls);
        assertCodeUntouched();
        assertNothingStarted();
    }

    public void testDisposedProjectDoesNoWorkAfterDisposal() throws Exception {
        CompletableFuture<Void> catalog = new CompletableFuture<>();
        files.script = (call, real) -> delayedUntil(catalog, real);
        openLegacy(ManagedSessionId.newId());
        waitFor(() -> files.canonicalCalls == 1);
        Disposer.dispose(SessionHostMigration.getInstance(getProject()));

        catalog.completeExceptionally(new IllegalStateException("late"));
        drain();
        openLegacy(ManagedSessionId.newId());
        waitFor(() -> legacy.size() == 2 && files.isFileOpen(legacy.get(1)));
        drain();

        assertTrue("a late failure after disposal is silent", shown.isEmpty());
        assertEquals("a disposed reconciler opens nothing more", 1, files.canonicalCalls);
        assertEquals(0, files.getAllEditors(canonical).length);
        assertCodeUntouched();
        assertNothingStarted();
    }

    public void testFailedOpenIsSurfacedWithRetryWithoutClosingCodeAndRetryIsUserDriven() throws Exception {
        files.script = (call, real) -> call == 1
                ? CompletableFuture.failedFuture(new IllegalStateException("catalog broke"))
                : real.open(call, real);
        AgentConsoleFile first = openLegacy(ManagedSessionId.newId());
        waitFor(() -> !shown.isEmpty());
        drain();

        assertEquals(1, shown.size());
        Notification failure = shown.get(0);
        assertEquals("Drydock migration", failure.getGroupId());
        assertEquals(NotificationType.WARNING, failure.getType());
        assertTrue(failure.getContent(), failure.getContent().contains("catalog broke"));
        assertEquals(0, files.getAllEditors(canonical).length);
        assertTrue("the placeholder stays", files.isFileOpen(first));
        assertCodeUntouched();
        assertEquals("a failure never loops into a retry by itself", 1, files.canonicalCalls);

        AnAction retry = failure.getActions().stream().filter(a -> "Retry".equals(a.getTemplateText()))
                .findFirst().orElseThrow(() -> new AssertionError("no Retry action"));
        ((NotificationAction) retry).actionPerformed(TestActionEvent.createTestEvent(), failure);
        awaitCanonical();

        assertOneCanonicalHost();
        assertEquals("the click is the second call", 2, files.canonicalCalls);
        assertEquals("only the failure was reported", 1, shown.size());
        assertCodeUntouched();
        assertNothingStarted();
    }
}
