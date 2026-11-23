package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.SessionActivity;
import app.drydock.intellij.DrydockSessionsListener;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import javax.swing.JComponent;
import javax.swing.JPanel;

import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * The controller's event lifecycle on the real application bus: an execution
 * event repaints only its own pane, a catalog change refreshes the loader's
 * initialized answers once per burst, bursts coalesce into one flush, and
 * nothing listens or ticks after the lease or the controller is gone.
 */
public class SessionHostControllerEventsPlatformTest extends BasePlatformTestCase {

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

    private final ManagedSessionId a = ManagedSessionId.newId();
    private final ManagedSessionId b = ManagedSessionId.newId();
    private final List<ManagedSessionId> events = new ArrayList<>();
    private final AtomicInteger probed = new AtomicInteger();
    private SessionHostController controller;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        controller = new SessionHostController(getProject(), new SessionHostState(), events::add,
                listener -> new SessionFactsLoader(CompletableFuture.completedFuture(null), Runnable::run, id -> {
                    probed.incrementAndGet();
                    return Optional.empty();
                }, listener));
        Disposer.register(getTestRootDisposable(), controller);
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

    private static void publish(Runnable publisher) throws InterruptedException {
        Thread thread = new Thread(publisher);
        thread.start();
        thread.join(10_000);
        assertFalse("publisher finished", thread.isAlive());
    }

    private static AgentExecutionListener executions() {
        return ApplicationManager.getApplication().getMessageBus().syncPublisher(AgentExecutionListener.TOPIC);
    }

    public void testTheMoveOfOneSessionRepaintsOnlyItsPaneAndLeavesTheDocument() throws Exception {
        controller.show(a);
        controller.hold();
        controller.show(b);
        controller.paneFor(a);
        controller.paneFor(b);
        var layout = controller.layout();
        long epoch = controller.epoch();
        List<ManagedSessionId> announced = List.copyOf(events);

        publish(() -> executions().executionMoving(b, true));
        waitFor(() -> controller.refreshCount(b) == 1);
        publish(() -> executions().executionReleased(b));
        publish(() -> executions().executionTerminated(b, true, 0));
        waitFor(() -> controller.refreshCount(b) >= 2);

        assertEquals("A was never repainted", 0, controller.refreshCount(a));
        assertSame("layout, so the document and slots, are untouched", layout, controller.layout());
        assertEquals(epoch, controller.epoch());
        assertEquals(announced, events);
        assertNotNull(controller.paneFor(a));
    }

    public void testActivityRepaintsThatPaneAndAnUnretainedSessionIsIgnored() throws Exception {
        controller.paneFor(a);
        publish(() -> ApplicationManager.getApplication().getMessageBus()
                .syncPublisher(SessionActivityListener.TOPIC)
                .activityChanged(b, Optional.of(SessionActivity.NEEDS_ATTENTION)));
        publish(() -> ApplicationManager.getApplication().getMessageBus()
                .syncPublisher(SessionActivityListener.TOPIC)
                .activityChanged(a, Optional.empty()));
        waitFor(() -> controller.refreshCount(a) == 1);
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        assertEquals(0, controller.refreshCount(b));
        assertEquals(1, controller.refreshCount(a));
    }

    public void testACatalogChangeRefreshesTheInitializedFactsOncePerBurst() throws Exception {
        controller.paneFor(a);
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        waitFor(() -> probed.get() == 1);

        publish(() -> {
            for (int i = 0; i < 5; i++) {
                DrydockSessionsListener.publishChanged();
            }
        });
        waitFor(() -> probed.get() == 2);
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        Thread.sleep(200);
        assertEquals("one refresh of the one initialized answer, no per-event rescans", 2, probed.get());
        assertEquals("one coalesced repaint", 1, controller.refreshCount(a));
    }

    public void testABurstCoalescesIntoOneRepaint() throws Exception {
        controller.paneFor(a);
        publish(() -> {
            for (int i = 0; i < 20; i++) {
                executions().executionMoving(a, i % 2 == 0);
            }
        });
        waitFor(() -> controller.refreshCount(a) >= 1);
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        assertEquals(1, controller.refreshCount(a));
    }

    public void testTheAgeTickRunsOnlyWhileALeaseIsHeld() {
        Editor owner = new Editor();
        assertFalse(controller.ageTickRunning());
        assertTrue(controller.acquireLease(owner));
        assertTrue(controller.ageTickRunning());
        controller.releaseLease(owner);
        assertFalse(controller.ageTickRunning());
        assertTrue(controller.acquireLease(owner));
        assertTrue(controller.ageTickRunning());
        Disposer.dispose(controller);
        assertFalse("disposal stops the tick", controller.ageTickRunning());
    }

    public void testNothingListensAfterDisposal() throws Exception {
        controller.paneFor(a);
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        int probes = probed.get();
        Disposer.dispose(controller);

        publish(() -> {
            executions().executionMoving(a, true);
            DrydockSessionsListener.publishChanged();
            ApplicationManager.getApplication().getMessageBus().syncPublisher(SessionActivityListener.TOPIC)
                    .activityChanged(a, Optional.empty());
        });
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        Thread.sleep(200);
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();

        assertEquals(0, controller.refreshCount(a));
        assertEquals(probes, probed.get());
        assertFalse(controller.acquireLease(new Editor()));
        assertFalse(controller.ageTickRunning());
    }
}
