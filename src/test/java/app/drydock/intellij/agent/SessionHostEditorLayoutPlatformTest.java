package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;
import app.drydock.intellij.agent.SessionHostModel.Slot;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * The session host editor over the real controller, panes and a local {@code sleep}
 * execution: layout, reopen, inert duplicate, empty states, detach-only disposal.
 */
public class SessionHostEditorLayoutPlatformTest extends BasePlatformTestCase {

    private final AgentExecutionListener owner = new AgentExecutionListener() { };
    private final ManagedSessionId a = ManagedSessionId.newId();
    private final ManagedSessionId b = ManagedSessionId.newId();
    private AgentExecutionRegistry registry;
    private SessionHostController controller;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        registry = AgentExecutionRegistry.getInstance();
        controller = new SessionHostController(getProject(), new SessionHostState(), id -> { });
        Disposer.register(getTestRootDisposable(), controller);
    }

    @Override
    protected void tearDown() throws Exception {
        registry.forget(a);
        waitFor(() -> registry.execution(a).isEmpty());
        super.tearDown();
    }

    private SessionHostEditor editor() {
        SessionHostEditor made = new SessionHostEditor(getProject(), controller);
        Disposer.register(getTestRootDisposable(), made);
        return made;
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

    public void testTitleAndToolbarGroupAreDeclared() {
        assertEquals("Drydock Sessions", editor().getName());
        assertNotNull("the toolbar group is declared in plugin.xml",
                ActionManager.getInstance().getAction(SessionHostEditor.TOOLBAR_GROUP));
    }

    public void testRealOpenOfTheCanonicalFileYieldsAnEditorWhoseGetFileIsThatFile() {
        SessionHostFile canonical = AgentConsoleFileSystem.getInstance().sessionHostFile();
        canonical.putUserData(FileEditorProvider.KEY, new AgentConsoleEditorProvider());
        try {
            FileEditor opened = FileEditorManager.getInstance(getProject()).openFile(canonical, true)[0];
            assertSame(canonical, opened.getFile());
        } finally {
            FileEditorManager.getInstance(getProject()).closeFile(canonical);
            canonical.putUserData(FileEditorProvider.KEY, null);
        }
    }

    public void testEmptyPrimaryShowsPlaceholderWithAllSessionsSlotAndNoHeldSlot() {
        SessionHostEditor editor = editor();
        assertTrue(editor.leased());
        assertTrue(editor.primarySlot().isAncestorOf(editor.allSessionsSlot()));
        assertFalse(editor.getComponent().isAncestorOf(editor.heldSlot()));
        assertEquals(0, editor.allSessionsSlot().getComponentCount());
    }

    public void testTwoSlotLayoutHostsPaneRootsAndMarksTheFocusedSlot() {
        controller.show(a);
        controller.hold();
        controller.show(b);
        SessionHostEditor editor = editor();

        SessionPane pa = controller.paneFor(a), pb = controller.paneFor(b);
        assertSame(editor.primarySlot(), pb.root().getParent());
        assertSame(editor.heldSlot(), pa.root().getParent());
        assertTrue(editor.getComponent().isAncestorOf(editor.heldSlot()));
        assertTrue(editor.getComponent().isAncestorOf(editor.primarySlot()));
        assertEquals(Slot.PRIMARY, controller.layout().focusedSlot());
        assertNotNull("the focused slot carries the accent border",
                editor.primarySlot().getBorder());
        assertFalse(editor.primarySlot().getBorder().equals(editor.heldSlot().getBorder()));
        assertEquals(Optional.of(pb), controller.focusedPane());

        controller.focus(a);
        editor.refreshLayout();
        assertFalse(editor.primarySlot().getBorder().equals(editor.heldSlot().getBorder()));
        assertSame(pa.root(), editor.getPreferredFocusedComponent());
    }

    public void testReopenReattachesTheSamePanesAndRestoresTheFocusedSlot() {
        controller.show(a);
        controller.hold();
        controller.show(b);
        controller.focus(a);
        SessionHostEditor first = editor();
        SessionPane pa = controller.paneFor(a), pb = controller.paneFor(b);
        Disposer.dispose(first);
        assertNull(pa.root().getParent());
        assertNull(pb.root().getParent());
        assertEquals("focus memory survives the close", Slot.HELD, controller.layout().focusedSlot());

        SessionHostEditor second = editor();
        assertTrue(second.leased());
        assertSame(pa, controller.paneFor(a));
        assertSame(second.heldSlot(), pa.root().getParent());
        assertSame(second.primarySlot(), pb.root().getParent());
        assertEquals(Optional.of(pa), controller.focusedPane());
    }

    public void testDuplicateWrapperIsInertAndItsDisposalReleasesNothing() {
        controller.show(a);
        SessionHostEditor real = editor();
        SessionPane pa = controller.paneFor(a);
        SessionHostEditor wrapper = editor();

        assertTrue(real.leased());
        assertFalse(wrapper.leased());
        assertNull(wrapper.getPreferredFocusedComponent());
        assertSame("the real editor keeps the single parent", real.primarySlot(), pa.root().getParent());
        wrapper.refreshLayout();
        assertSame(real.primarySlot(), pa.root().getParent());
        assertNull(wrapper.primarySlot().getParent());
        assertEquals(0, wrapper.primarySlot().getComponentCount());

        Disposer.dispose(wrapper);
        assertSame("a wrapper's disposal does not release the lease", real.primarySlot(), pa.root().getParent());
        assertEquals(Optional.of(pa), controller.focusedPane());
    }

    public void testDisposeDetachesButLeavesPanesAndConsoles() throws Exception {
        var started = registry.startArgv(a, Path.of(System.getProperty("java.io.tmpdir")),
                List.of("/bin/sh", "-c", "sleep 30"), getProject(), owner);
        waitFor(started::isDone);
        var console = started.get(1, TimeUnit.SECONDS).console().orElseThrow();
        controller.show(a);
        SessionHostEditor editor = editor();
        SessionPane pa = controller.paneFor(a);
        pa.show(new SessionPaneFacts(SessionPaneFacts.State.KNOWN, "", Optional.empty(), false,
                Optional.empty(), false, false), false);

        Disposer.dispose(editor);

        assertFalse(editor.isValid());
        assertNull(pa.root().getParent());
        assertSame("the pane is retained", pa, controller.paneFor(a));
        assertTrue(registry.execution(a).isPresent());
        assertFalse(Disposer.isDisposed(console));
        assertEquals(Optional.empty(), controller.focusedPane());
        assertTrue("the lease is free again", controller.acquireLease(editor()));
    }
}
