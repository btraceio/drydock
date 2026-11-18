package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;

import com.intellij.openapi.components.ComponentManagerEx;
import com.intellij.openapi.fileEditor.FileEditorComposite;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.fileEditor.FileEditorOpenRequest;
import com.intellij.openapi.fileEditor.impl.EditorWindow;
import com.intellij.openapi.fileEditor.impl.FileEditorManagerImpl;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.HeavyPlatformTestCase;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.ServiceContainerUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.SwingConstants;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/**
 * Placeholder closure at the end of the canonical open, through real opens and the real editor
 * manager, a fresh project per test: only the legacy tabs close, each split keeps its code file
 * and the current window, and nothing closes after disposal.
 *
 * <p>The fixture is headless: there is no focused AWT component, so the focus-owner capture and the
 * guarded {@code requestFocus} return are NOT exercised here (the snapshot helper's own test pins
 * that fact). Native focus restoration stays covered solely by the T32 manual gate, which remains
 * mandatory; this result is unverified for focus.</p>
 */
public class SessionHostMigrationLayoutPlatformTest extends HeavyPlatformTestCase {

    /** The production manager whose canonical open can be held back until a test releases it. */
    private static final class GatedManager extends FileEditorManagerImpl {
        private volatile CompletableFuture<Void> gate = CompletableFuture.completedFuture(null);
        private volatile int canonicalCalls;

        GatedManager(Project project) {
            super(project, ((ComponentManagerEx) project).getCoroutineScope());
        }

        @Override
        public @NotNull CompletableFuture<FileEditorComposite> requestOpenFile(@NotNull VirtualFile file,
                                                                               @NotNull FileEditorOpenRequest request) {
            if (!(file instanceof SessionHostFile)) {
                return super.requestOpenFile(file, request);
            }
            canonicalCalls++;
            return gate.thenCompose(released -> super.requestOpenFile(file, request));
        }
    }

    private final List<AgentConsoleFile> legacy = new ArrayList<>();
    private GatedManager files;
    private SessionHostFile canonical;
    private VirtualFile a;
    private VirtualFile c;
    private EditorWindow left;
    private EditorWindow right;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        files = new GatedManager(getProject());
        ServiceContainerUtil.replaceService(getProject(), FileEditorManager.class, files, getTestRootDisposable());
        canonical = AgentConsoleFileSystem.getInstance().sessionHostFile();
        a = getTempDir().createVirtualFile("a.txt", "a");
        c = getTempDir().createVirtualFile("c.txt", "c");
        files.openFile(a, true);
        waitFor(() -> files.getCurrentWindow() != null && List.of(files.getSelectedFiles()).contains(a));
        left = files.getCurrentWindow();
        left.split(SwingConstants.VERTICAL, true, c, false);
        waitFor(() -> files.getWindows().length == 2);
        right = files.getWindows()[0] == left ? files.getWindows()[1] : files.getWindows()[0];
        right.setAsCurrentWindow(false);
    }

    private static void waitFor(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            assertTrue("condition not met within 10 s", System.currentTimeMillis() <= deadline);
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            Thread.sleep(20);
        }
    }

    /** As a restore opens a tab, except that it takes the selection, so closing it disturbs the layout. */
    private AgentConsoleFile openSelectedLegacy() {
        AgentConsoleFile file = AgentConsoleFileSystem.getInstance().fileFor(ManagedSessionId.newId());
        legacy.add(file);
        files.requestOpenFile(file, FileEditorOpenRequest.defaults().withRequestFocus(false).withUsePreviewTab(false));
        return file;
    }

    private void awaitConverted() throws InterruptedException {
        waitFor(() -> files.getAllEditors(canonical).length == 1 && legacy.stream().noneMatch(files::isFileOpen));
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
    }

    private static VirtualFile selected(EditorWindow window) {
        return window.getSelectedComposite(true).getFile();
    }

    public void testOnlyPlaceholdersCloseAndEachSplitKeepsItsCodeAndTheCurrentWindow() throws Exception {
        files.gate = new CompletableFuture<>();
        openSelectedLegacy();
        openSelectedLegacy();
        waitFor(() -> legacy.stream().allMatch(files::isFileOpen));
        assertTrue("the placeholder took the selection", selected(right) instanceof AgentConsoleFile);
        files.gate.complete(null);

        awaitConverted();

        assertEquals(a, selected(left));
        assertEquals(c, selected(right));
        assertSame("the prior current window is back", right, files.getCurrentWindow());
        assertEquals("exactly the code and the canonical document remain", 3, files.getOpenFiles().length);
        assertTrue(files.isFileOpen(a) && files.isFileOpen(c));
        assertEquals("one open, one closure pass", 1, SessionHostMigration.getInstance(getProject()).opens());
    }

    public void testSelectedLegacyOnlyGroupGetsNoCodeTargetAndNoExtraSplit() throws Exception {
        AgentConsoleFile only = AgentConsoleFileSystem.getInstance().fileFor(ManagedSessionId.newId());
        legacy.add(only);
        files.gate = new CompletableFuture<>();
        right.split(SwingConstants.HORIZONTAL, true, only, true);
        waitFor(() -> files.getWindows().length == 3 && files.isFileOpen(only));
        files.gate.complete(null);

        awaitConverted();

        assertTrue("an emptied legacy-only group is not kept as a new split", files.getWindows().length <= 3);
        assertFalse(files.isFileOpen(only));
        for (EditorWindow window : files.getWindows()) {
            if (window.getSelectedComposite(true) != null) {
                assertFalse("no group is filled with a session", selected(window) instanceof AgentConsoleFile);
            }
        }
        assertEquals(a, selected(left));
        assertEquals(c, selected(right));
    }

    public void testClosingEventsThatReenterTheMigratorDoNotLoopOrReopen() throws Exception {
        SessionHostMigration migration = SessionHostMigration.getInstance(getProject());
        int[] closed = new int[1];
        getProject().getMessageBus().connect(getTestRootDisposable()).subscribe(
                FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {
                    @Override
                    public void fileClosed(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
                        if (file instanceof AgentConsoleFile) {
                            closed[0]++;
                            migration.legacyOpened();
                        }
                    }
                });
        files.gate = new CompletableFuture<>();
        openSelectedLegacy();
        openSelectedLegacy();
        waitFor(() -> legacy.stream().allMatch(files::isFileOpen));
        files.gate.complete(null);

        awaitConverted();
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();

        assertEquals("each placeholder closed once", 2, closed[0]);
        assertEquals(1, files.canonicalCalls);
        assertEquals(1, files.getAllEditors(canonical).length);
        assertEquals(c, selected(right));
    }

    public void testDisposalBeforeTheOpenCompletesClosesNothing() throws Exception {
        files.gate = new CompletableFuture<>();
        AgentConsoleFile placeholder = openSelectedLegacy();
        waitFor(() -> files.canonicalCalls == 1);
        Disposer.dispose(SessionHostMigration.getInstance(getProject()));

        files.gate.complete(null);
        waitFor(() -> files.getAllEditors(canonical).length == 1);
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();

        assertTrue("no work after disposal: the placeholder stays", files.isFileOpen(placeholder));
        assertTrue(files.isFileOpen(a) && files.isFileOpen(c));
    }

    public void testFailedOpenClosesNoPlaceholderAndNoCode() throws Exception {
        files.gate = CompletableFuture.failedFuture(new IllegalStateException("catalog broke"));
        AgentConsoleFile placeholder = openSelectedLegacy();
        waitFor(() -> files.canonicalCalls == 1);
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();

        assertTrue(files.isFileOpen(placeholder));
        assertTrue(files.isFileOpen(a) && files.isFileOpen(c));
        assertEquals(0, files.getAllEditors(canonical).length);
    }
}
