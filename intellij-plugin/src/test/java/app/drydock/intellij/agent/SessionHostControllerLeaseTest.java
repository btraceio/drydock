package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;
import app.drydock.intellij.agent.SessionHostModel.Outcome;
import app.drydock.intellij.agent.SessionHostModel.Slot;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import javax.swing.JComponent;
import javax.swing.JPanel;

import java.awt.BorderLayout;
import java.beans.PropertyChangeListener;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * The controller's attachment lease, deliberate pane focus, strip-action
 * guards and lifetime over a real project and a real local {@code sleep}
 * registry execution (no provider). Pane identity is in
 * {@link SessionHostControllerPanesTest}.
 */
public class SessionHostControllerLeaseTest extends BasePlatformTestCase {

    /** A bare owner token: the lease is identity-only. */
    private static final class Editor extends UserDataHolderBase implements FileEditor {
        private final JPanel component = new JPanel();
        @Override public JComponent getComponent() { return component; }
        @Override public JComponent getPreferredFocusedComponent() { return null; }
        @Override public String getName() { return "stub"; }
        @Override public void setState(FileEditorState state) { }
        @Override public boolean isModified() { return false; }
        @Override public boolean isValid() { return true; }
        @Override public void addPropertyChangeListener(PropertyChangeListener listener) { }
        @Override public void removePropertyChangeListener(PropertyChangeListener listener) { }
        @Override public void dispose() { }
    }

    private final AgentExecutionListener owner = new AgentExecutionListener() { };
    private final ManagedSessionId a = ManagedSessionId.newId();
    private final ManagedSessionId b = ManagedSessionId.newId();
    private final ManagedSessionId c = ManagedSessionId.newId();
    private final List<ManagedSessionId> events = new ArrayList<>();
    private AgentExecutionRegistry registry;
    private SessionHostController controller;
    private SessionHostState state;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        registry = AgentExecutionRegistry.getInstance();
        controller = host();
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            registry.forget(a);
            waitFor(() -> registry.execution(a).isEmpty());
        } finally {
            super.tearDown();
        }
    }

    private SessionHostController host() {
        state = new SessionHostState();
        SessionHostController made = new SessionHostController(getProject(), state, events::add);
        Disposer.register(getTestRootDisposable(), made);
        return made;
    }

    private AgentExecution start() throws Exception {
        var started = registry.startArgv(a, Path.of(System.getProperty("java.io.tmpdir")),
                List.of("/bin/sh", "-c", "sleep 30"), getProject(), owner);
        waitFor(started::isDone);
        return started.get(1, TimeUnit.SECONDS);
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

    private static SessionPaneFacts known() {
        return new SessionPaneFacts(SessionPaneFacts.State.KNOWN, "", Optional.empty(), false,
                Optional.empty(), false, false);
    }

    public void testLeaseIsOneIdentityOwnerAndItsReleaseDetachesWithoutDisposing() {
        Editor first = new Editor(), second = new Editor();
        JPanel parent = new JPanel(new BorderLayout());
        assertTrue(controller.acquireLease(first));
        assertTrue("the same owner again is idempotent", controller.acquireLease(first));
        assertFalse("another owner is refused", controller.acquireLease(second));
        controller.show(a);
        SessionPane pa = controller.paneFor(a);
        parent.add(pa.root());
        long issued = controller.epoch();
        assertTrue(controller.focusPermitted(issued));
        assertEquals(Optional.of(pa), controller.focusedPane());

        controller.releaseLease(second);
        assertTrue("a non-owner release is ignored", controller.focusPermitted(issued));
        assertSame(parent, pa.root().getParent());
        controller.releaseLease(first);

        assertFalse(controller.focusPermitted(issued));
        assertFalse(controller.focusPermitted(controller.epoch()));
        assertNull("the mounted slot root is detached", pa.root().getParent());
        assertEquals(Optional.empty(), controller.focusedPane());
        assertEquals("no pane was disposed", SessionPane.Body.FAILED,
                pa.show(SessionPaneFacts.failed("still live"), false));
        assertSame(pa, controller.paneFor(a));
        assertEquals(Optional.of(a), controller.layout().primary());
        long before = controller.epoch();
        assertTrue("another owner may acquire after release", controller.acquireLease(second));
        assertEquals("acquire issues no focus", before, controller.epoch());
        assertFalse(controller.focusPermitted(controller.epoch()));
    }

    public void testDeliberatePaneFocusIsFocusOnlyForARetainedPaneUnderALiveLease() {
        Editor editor = new Editor();
        controller.acquireLease(editor);
        controller.show(a);
        controller.hold();
        controller.show(b);
        controller.paneFor(a);
        controller.paneFor(c);
        controller.focusDeparted();
        long departed = controller.epoch();

        controller.paneFocused(b);
        assertEquals("b is on screen but has no retained pane", departed, controller.epoch());
        controller.paneFocused(c);
        assertEquals(Slot.NONE, controller.layout().focusedSlot());
        assertFalse("c is retained but not on screen", controller.focusPermitted(controller.epoch()));
        assertEquals(List.of(a, b), events);

        controller.paneFocused(a);
        assertEquals(Slot.HELD, controller.layout().focusedSlot());
        assertTrue(controller.focusPermitted(controller.epoch()));

        long token = controller.epoch();
        Object focusedLayout = controller.layout();
        List<ManagedSessionId> eventsBefore = List.copyOf(events);
        Optional<ManagedSessionId> hint = state.selectedSession();
        controller.paneFocused(c);
        assertTrue("an off-slot Host callback leaves the issued token valid", controller.focusPermitted(token));
        assertEquals(token, controller.epoch());
        assertSame(focusedLayout, controller.layout());
        assertEquals(eventsBefore, events);
        assertEquals(hint, state.selectedSession());

        controller.releaseLease(editor);
        long released = controller.epoch();
        Object layout = controller.layout();
        controller.paneFocused(a);
        assertEquals("a released attachment's focus is ignored", released, controller.epoch());
        assertSame(layout, controller.layout());
        controller.dispose();
        long disposed = controller.epoch();
        controller.paneFocused(a);
        assertEquals(disposed, controller.epoch());
        assertEquals(Outcome.UNAVAILABLE, controller.focus(a));
    }

    public void testStripActionsFromAnythingButAnAttachedSlotPaneAreDropped() throws Exception {
        SessionPane retained = controller.paneFor(a), offScreen = controller.paneFor(b);
        SessionPane foreign = new SessionPane(getProject(), a, controller);
        controller.show(a);
        controller.onStripAction(retained, AgentConsoleStrip.StripAction.DONE);   // no lease yet
        controller.acquireLease(new Editor());
        controller.onStripAction(foreign, AgentConsoleStrip.StripAction.DONE);
        controller.onStripAction(offScreen, AgentConsoleStrip.StripAction.DONE);
        controller.onStripAction(null, AgentConsoleStrip.StripAction.DONE);

        Path source = Path.of("src/platform/java/app/drydock/intellij/agent/SessionHostController.java");
        String text = Files.readString(source);
        assertEquals("the one forwarding call site", 1, text.split("pane\\.command\\(", -1).length - 1);
        assertEquals("the one pane-cleanup site", 1, text.split("SessionPane::dispose", -1).length - 1);
        assertFalse("no registry or router effect", text.matches("(?s).*\\b(release|moveTo|attach|detach)\\(.*"));
        int regionStart = text.indexOf("public void dispose()");
        int cleanupStart = text.indexOf("private void cleanup()");
        assertTrue("the dispose and cleanup markers are found", regionStart >= 0 && cleanupStart > regionStart);
        int regionEnd = text.indexOf("\n    }\n", cleanupStart);
        assertTrue("the cleanup end is found", regionEnd > cleanupStart);
        assertFalse("cleanup is never expired with the project",
                text.substring(regionStart, regionEnd).contains("getDisposed()"));
    }

    public void testDisposeOnTheUiThreadTakesPanesAndLeaseApartButNotConsoles() throws Exception {
        var console = start().console().orElseThrow();
        controller.acquireLease(new Editor());
        controller.show(a);
        SessionPane pa = controller.paneFor(a);
        pa.show(known(), false);

        controller.dispose();
        controller.dispose();
        Disposer.dispose(controller);

        assertEquals("the pane is disposed", SessionPane.Body.LIVE, pa.show(SessionPaneFacts.failed("x"), false));
        assertNull(pa.root().getParent());
        assertTrue(registry.execution(a).isPresent());
        assertFalse(Disposer.isDisposed(console));
        assertThrows(IllegalStateException.class, () -> controller.paneFor(a));
        assertFalse(controller.acquireLease(new Editor()));
        assertEquals(Optional.empty(), controller.focusedPane());
        var leaseField = SessionHostController.class.getDeclaredField("lease");
        leaseField.setAccessible(true);
        assertNull("cleanup drops the retained lease owner", leaseField.get(controller));
    }

    public void testDisposeOffTheUiThreadRejectsAtOnceAndCleansOnTheUiThread() throws Exception {
        controller.acquireLease(new Editor());
        controller.show(a);
        SessionPane pa = controller.paneFor(a);
        JPanel parent = new JPanel(new BorderLayout());
        parent.add(pa.root());
        assertTrue(controller.focusPermitted(controller.epoch()));

        Thread caller = new Thread(controller::dispose);
        caller.start();
        caller.join(10_000);

        assertFalse(caller.isAlive());
        assertFalse(controller.focusPermitted(controller.epoch()));
        assertEquals(Outcome.UNAVAILABLE, controller.show(b));
        assertEquals("cleanup waits for the UI thread", SessionPane.Body.FAILED,
                pa.show(SessionPaneFacts.failed("not yet"), false));
        assertSame(parent, pa.root().getParent());
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        assertEquals("now disposed", SessionPane.Body.FAILED, pa.show(SessionPaneFacts.unknown(), false));
        assertNull(pa.root().getParent());
        assertFalse(controller.acquireLease(new Editor()));
    }
}
