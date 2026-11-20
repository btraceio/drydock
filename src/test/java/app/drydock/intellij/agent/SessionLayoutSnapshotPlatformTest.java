package app.drydock.intellij.agent;

import app.drydock.intellij.agent.SessionLayoutSnapshot.Outcome;

import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.fileEditor.impl.FileEditorManagerImpl;
import com.intellij.openapi.fileEditor.impl.EditorWindow;
import com.intellij.openapi.components.ComponentManagerEx;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.testFramework.HeavyPlatformTestCase;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.ServiceContainerUtil;

import javax.swing.SwingConstants;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * The layout snapshot over the real editor manager, a fresh project per test: split selections and
 * the current window come back, and a stale, closed or disposed snapshot is never replayed.
 *
 * <p>The fixture is headless: there is no focused AWT component, so native keyboard focus is NOT
 * exercised. {@link #testFocusOwnerIsUnobservableHeadlessSoRestoreTakesNoFocusAction} pins that
 * fact (it fails if the fixture ever gains a focus owner) and checks only that a restore with no
 * owner neither requests focus nor disturbs the restored layout. Focus capture and the guarded
 * {@code requestFocus} return stay covered solely by the T32 manual gate, which remains mandatory.</p>
 */
public class SessionLayoutSnapshotPlatformTest extends HeavyPlatformTestCase {

    private final List<VirtualFile> events = new ArrayList<>();
    private VirtualFile a;
    private VirtualFile b;
    private VirtualFile c;
    private FileEditorManagerImpl files;
    private SessionLayoutSnapshot snapshot;
    private EditorWindow left;
    private EditorWindow right;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // The production manager: the project's default one has no editor windows to split.
        getProject().getMessageBus().connect(getTestRootDisposable()).subscribe(
                FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {
                    @Override
                    public void selectionChanged(FileEditorManagerEvent event) {
                        events.add(event.getNewFile());
                    }
                });
        files = new FileEditorManagerImpl(getProject(), ((ComponentManagerEx) getProject()).getCoroutineScope());
        ServiceContainerUtil.replaceService(getProject(), FileEditorManager.class, files, getTestRootDisposable());
        a = getTempDir().createVirtualFile("a.txt", "a");
        b = getTempDir().createVirtualFile("b.txt", "b");
        c = getTempDir().createVirtualFile("c.txt", "c");
        files.openFile(a, true);
        files.openFile(b, true);
        waitFor(() -> files.getCurrentWindow() != null && files.getSelectedFiles().length > 0
                && files.getSelectedFiles()[0].equals(b));
        left = files.getCurrentWindow();
        left.split(SwingConstants.VERTICAL, true, c, false);
        waitFor(() -> files.getWindows().length == 2);
        right = files.getWindows()[0] == left ? files.getWindows()[1] : files.getWindows()[0];
        left.setSelectedComposite(a, false);
        right.setAsCurrentWindow(false);
        // The manager publishes selection changes after the call returns; let setup's settle first.
        waitFor(() -> events.contains(a));
        snapshot = new SessionLayoutSnapshot(getProject(), getTestRootDisposable());
    }

    private static void waitFor(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            assertTrue("condition not met within 10 s", System.currentTimeMillis() <= deadline);
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            Thread.sleep(20);
        }
    }

    /** The migrator's own step: a selection change and a current-window change. */
    private void disturb() {
        snapshot.runOwnChange(() -> {
            left.setAsCurrentWindow(false);
            left.setSelectedComposite(b, false);
        });
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
    }

    private VirtualFile selected(EditorWindow window) {
        return window.getSelectedComposite(true).getFile();
    }

    public void testEachSplitsSelectedFileIsRestored() throws Exception {
        snapshot.capture();
        disturb();
        assertEquals(b, selected(left));

        assertEquals(Outcome.RESTORED, snapshot.restore());

        assertEquals(a, selected(left));
        assertEquals(c, selected(right));
    }

    public void testTheCurrentWindowIsRestored() throws Exception {
        snapshot.capture();
        disturb();
        assertSame(left, files.getCurrentWindow());

        assertEquals(Outcome.RESTORED, snapshot.restore());

        assertSame(right, files.getCurrentWindow());
    }

    public void testAUserSelectionAfterTheCaptureIsNeverReplayedOver() throws Exception {
        snapshot.capture();
        int before = events.size();
        left.setSelectedComposite(b, false);
        waitFor(() -> events.size() > before);

        assertEquals(Outcome.STALE, snapshot.restore());
        assertEquals("the user's later choice stands", b, selected(left));
        assertEquals(Outcome.NOTHING, snapshot.restore());
    }

    public void testExplicitInvalidationRejectsTheSnapshot() throws Exception {
        snapshot.capture();
        disturb();
        snapshot.invalidate();

        assertEquals(Outcome.STALE, snapshot.restore());
        assertEquals(b, selected(left));
    }

    public void testAFileClosedMeanwhileIsNotReopenedOrSelected() throws Exception {
        snapshot.capture();
        snapshot.runOwnChange(() -> files.closeFile(a));

        assertEquals(Outcome.RESTORED, snapshot.restore());
        assertFalse(files.isFileOpen(a));
        assertEquals(c, selected(right));
    }

    public void testDisposalClearsTheCaptureAndEndsAllWork() throws Exception {
        snapshot.capture();
        disturb();
        snapshot.dispose();

        assertEquals(Outcome.DISPOSED, snapshot.restore());
        snapshot.capture();
        snapshot.invalidate();
        assertEquals(Outcome.DISPOSED, snapshot.restore());
        assertEquals(b, selected(left));
    }

    public void testTheEchoOfTheHelpersOwnChangeDoesNotBumpTheEpoch() throws Exception {
        snapshot.capture();
        int before = events.size();
        disturb();
        // The echo may publish late under load; correctness does not depend on its arrival, because
        // runOwnChange re-baselines and the echo finds the layout unchanged. Wait for either signal.
        waitFor(() -> events.size() > before || b.equals(selected(left)));
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        assertEquals(b, selected(left));

        assertEquals("the echoed selectionChanged is not a user action", Outcome.RESTORED, snapshot.restore());
        assertEquals(a, selected(left));
    }

    public void testAUserActionAfterTheHelpersOwnChangeMakesTheRestoreANoOp() throws Exception {
        snapshot.capture();
        disturb();
        assertEquals(b, selected(left));
        int before = events.size();
        files.openFile(c, true);
        waitFor(() -> events.size() > before);
        VirtualFile userChoice = selected(files.getCurrentWindow());
        VirtualFile otherSplit = selected(files.getCurrentWindow() == left ? right : left);

        assertEquals(Outcome.STALE, snapshot.restore());
        assertEquals("the user's choice stands", userChoice, selected(files.getCurrentWindow()));
        assertEquals("the other split is left alone", otherSplit,
                selected(files.getCurrentWindow() == left ? right : left));
        assertEquals(Outcome.NOTHING, snapshot.restore());
    }

    public void testFocusOwnerIsUnobservableHeadlessSoRestoreTakesNoFocusAction() throws Exception {
        assertNull("the headless fixture has no focus owner; native focus needs the T32 manual gate",
                IdeFocusManager.getInstance(getProject()).getFocusOwner());
        snapshot.capture();
        disturb();

        assertEquals(Outcome.RESTORED, snapshot.restore());
        assertSame(right, files.getCurrentWindow());
        assertEquals(a, selected(left));
    }
}
