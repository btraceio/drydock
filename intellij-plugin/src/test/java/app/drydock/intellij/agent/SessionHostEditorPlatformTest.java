package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;

import com.intellij.openapi.components.ComponentManagerEx;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.impl.EditorWindow;
import com.intellij.openapi.fileEditor.impl.FileEditorManagerImpl;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.terminal.TerminalExecutionConsole;
import com.intellij.testFramework.HeavyPlatformTestCase;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.ServiceContainerUtil;

import javax.swing.JComponent;
import javax.swing.SwingConstants;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Split, duplicate, restore and detached-window opens of the one Sessions document through the
 * real editor manager and the registered provider, fresh project per test: exactly one editor
 * holds the lease, every pane root and console has one parent, a legacy restored session tab
 * cannot take a console, and showing any session reuses the one document.
 *
 * <p>Component identity here is parentage only; it says nothing about draft, scroll, search or
 * IME state. The T32 manual gate stays mandatory for those.</p>
 */
public class SessionHostEditorPlatformTest extends HeavyPlatformTestCase {
    private final List<ManagedSessionId> started = new ArrayList<>();
    private AgentExecutionRegistry registry;
    private SessionHostController controller;
    private FileEditorManagerImpl files;
    private SessionHostFile hostFile;
    private ManagedSessionId a;
    private ManagedSessionId b;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        registry = AgentExecutionRegistry.getInstance();
        controller = SessionHostController.getInstance(getProject());
        files = new FileEditorManagerImpl(getProject(), ((ComponentManagerEx) getProject()).getCoroutineScope());
        ServiceContainerUtil.replaceService(getProject(), FileEditorManager.class, files, getTestRootDisposable());
        hostFile = AgentConsoleFileSystem.getInstance().sessionHostFile();
        a = running();
        b = running();
        controller.show(a);
        assertTrue(controller.hold());
        controller.show(b);
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            for (ManagedSessionId id : started) {
                registry.forget(id);
                waitFor(() -> registry.execution(id).isEmpty());
            }
        } finally {
            super.tearDown();
        }
    }

    private static void waitFor(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            assertTrue("condition not met within 10 s", System.currentTimeMillis() <= deadline);
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            Thread.sleep(20);
        }
    }

    private ManagedSessionId running() throws Exception {
        ManagedSessionId id = ManagedSessionId.newId();
        started.add(id);
        var future = registry.startArgv(id, Path.of(System.getProperty("java.io.tmpdir")),
                List.of("/bin/sh", "-c", "sleep 30"), getProject(), new AgentExecutionListener() { });
        waitFor(future::isDone);
        future.get(1, TimeUnit.SECONDS);
        return id;
    }

    private List<SessionHostEditor> hostEditors() {
        List<SessionHostEditor> editors = new ArrayList<>();
        for (FileEditor editor : files.getAllEditors(hostFile)) {
            editors.add((SessionHostEditor) editor);
        }
        return editors;
    }

    private void awaitHostEditors(int count) throws InterruptedException {
        waitFor(() -> hostEditors().size() == count);
    }

    /** The one leased editor owns both slots; every other editor is empty; every console has that one parent. */
    private SessionHostEditor assertOneOwner() {
        List<SessionHostEditor> leased = hostEditors().stream().filter(SessionHostEditor::leased).toList();
        assertEquals("exactly one editor holds the lease", 1, leased.size());
        SessionHostEditor owner = leased.get(0);
        owner.refreshLayout();
        for (SessionHostEditor other : hostEditors()) {
            if (other != owner) {
                other.refreshLayout();
                assertEquals(0, other.primarySlot().getComponentCount());
                assertEquals(0, other.heldSlot().getComponentCount());
            }
        }
        mount(owner, a, owner.heldSlot());
        mount(owner, b, owner.primarySlot());
        return owner;
    }

    private void mount(SessionHostEditor owner, ManagedSessionId id, JComponent slot) {
        SessionPane pane = controller.paneFor(id);
        pane.show(new SessionPaneFacts(SessionPaneFacts.State.KNOWN, "", Optional.empty(), false,
                Optional.empty(), false, false), false);
        TerminalExecutionConsole console = registry.execution(id).orElseThrow().console().orElseThrow();
        assertSame(slot, pane.root().getParent());
        assertTrue(owner.getComponent().isAncestorOf(console.getComponent()));
        assertTrue(pane.root().isAncestorOf(console.getComponent()));
    }

    private EditorWindow splitOpen() throws InterruptedException {
        files.openFile(hostFile, true);
        awaitHostEditors(1);
        EditorWindow left = files.getCurrentWindow();
        left.split(SwingConstants.VERTICAL, true, hostFile, true);
        awaitHostEditors(2);
        return left;
    }

    public void testSplitOfTheDocumentIsAnInertDuplicateAndStealsNothing() throws Exception {
        splitOpen();

        SessionHostEditor owner = assertOneOwner();

        assertFalse(controller.acquireLease(hostEditors().stream().filter(e -> e != owner).findFirst().orElseThrow()));
        assertEquals(Optional.of(controller.paneFor(b)), controller.focusedPane());
    }

    public void testDetachedWindowOpenHandsTheLeaseOverAndNeverMountsTwoHosts() throws Exception {
        files.openFile(hostFile, true);
        awaitHostEditors(1);
        SessionHostEditor owner = assertOneOwner();

        FileEditor detached = files.openFileInNewWindow(hostFile).first[0];

        assertNotSame("the window got its own editor", owner, detached);
        assertEquals("the platform moved the tab: one editor remains", 1, hostEditors().size());
        assertFalse("the replaced editor holds nothing", owner.leased());
        assertSame(detached, assertOneOwner());
    }

    public void testRestoreAfterCloseRemountsTheSamePanesAndALegacyTabTakesNoConsole() throws Exception {
        files.openFile(hostFile, true);
        awaitHostEditors(1);
        SessionPane pa = controller.paneFor(a), pb = controller.paneFor(b);
        assertOneOwner();
        files.closeFile(hostFile);
        awaitHostEditors(0);
        assertNull(pa.root().getParent());

        VirtualFile legacy = AgentConsoleFileSystem.getInstance().fileFor(a);
        files.openFile(legacy, true);
        waitFor(() -> files.getAllEditors(legacy).length == 1);
        assertTrue(files.getAllEditors(legacy)[0] instanceof LegacySessionEditor);
        assertNull("a restored session tab mounts nothing", pa.root().getParent());

        files.openFile(hostFile, true);
        awaitHostEditors(1);
        assertOneOwner();
        assertSame(pa, controller.paneFor(a));
        assertSame(pb, controller.paneFor(b));
    }

    public void testShowingAnySessionReusesTheOneDocumentEvenWithADuplicateOpen() throws Exception {
        splitOpen();
        List<SessionHostEditor> before = hostEditors();

        AgentConsoles.show(getProject(), a);
        AgentConsoles.show(getProject(), b);
        AgentConsoles.show(getProject(), a);

        assertEquals("no editor was added or replaced", before, hostEditors());
        assertEquals(0, files.getAllEditors(AgentConsoleFileSystem.getInstance().fileFor(a)).length);
        assertOneOwner();
    }
}
