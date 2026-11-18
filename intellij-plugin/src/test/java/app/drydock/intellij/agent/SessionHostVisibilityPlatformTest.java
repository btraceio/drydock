package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;

import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.HeavyPlatformTestCase;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.util.ui.UIUtil;

import java.awt.AWTEvent;
import java.awt.Toolkit;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Visibility through the REAL opened host editor, fresh project per test. Headless has no peers, so
 * showing/active are saved-and-restored static seams; "showing" still needs the console mounted
 * under the opened editor, so closing the editor really un-shows it.
 */
public class SessionHostVisibilityPlatformTest extends HeavyPlatformTestCase {
    private final List<ManagedSessionId> acknowledged = new CopyOnWriteArrayList<>();
    private final List<ManagedSessionId> started = new ArrayList<>();
    private AgentExecutionRegistry registry;
    private SessionHostController controller;
    private SessionHostFile hostFile;
    private SessionHostEditor editor;
    private Runnable restoreSeams;
    private volatile boolean onScreen = true;
    private volatile boolean active = true;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        registry = AgentExecutionRegistry.getInstance();
        var showing = SessionHostVisibility.showing;
        var windowActive = SessionHostVisibility.windowActive;
        var acknowledger = SessionHostVisibility.acknowledger;
        restoreSeams = () -> {
            SessionHostVisibility.showing = showing;
            SessionHostVisibility.windowActive = windowActive;
            SessionHostVisibility.acknowledger = acknowledger;
        };
        controller = SessionHostController.getInstance(getProject());
        hostFile = AgentConsoleFileSystem.getInstance().sessionHostFile();
        hostFile.putUserData(FileEditorProvider.KEY, new AgentConsoleEditorProvider());
        editor = (SessionHostEditor) FileEditorManager.getInstance(getProject()).openFile(hostFile, true)[0];
        assertTrue(editor.leased());
        SessionHostVisibility.showing = c -> onScreen && UIUtil.isAncestor(editor.getComponent(), c);
        SessionHostVisibility.windowActive = c -> active;
        SessionHostVisibility.acknowledger = acknowledged::add;
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            restoreSeams.run();
            FileEditorManager.getInstance(getProject()).closeFile(hostFile);
            Disposer.dispose(editor);
            hostFile.putUserData(FileEditorProvider.KEY, null);
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
            if (System.currentTimeMillis() > deadline) {
                fail("condition not met within 10 s");
            }
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
    private ManagedSessionId shownLive() throws Exception {
        ManagedSessionId id = running();
        controller.show(id);
        editor.refreshLayout();
        resolve(id);
        return id;
    }
    private static SessionPaneFacts known() {
        return new SessionPaneFacts(SessionPaneFacts.State.KNOWN, "", Optional.empty(), false,
                Optional.empty(), false, false);
    }
    private void resolve(ManagedSessionId... ids) throws Exception {
        for (ManagedSessionId id : ids) {
            controller.paneFor(id);
            waitFor(() -> controller.facts(id).filter(f -> f.state() != SessionPaneFacts.State.LOADING).isPresent());
        }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        for (ManagedSessionId id : ids) {
            controller.paneFor(id).show(known(), false);
        }
    }
    private void selectedAndChecked(ManagedSessionId id) throws Exception {
        int before = controller.visibility.checks;
        getProject().getMessageBus().syncPublisher(SessionHostListener.TOPIC).sessionSelected(id);
        waitFor(() -> controller.visibility.checks > before);
    }
    private void notViewed(ManagedSessionId id, SessionPane.Body body, SessionPaneFacts facts, boolean elsewhere) {
        assertEquals(body, controller.paneFor(id).show(facts, elsewhere));
        assertFalse(body + " is a placeholder", controller.visibility.isViewed(id));
    }

    public void testEachPlaceholderBodyIsNotViewed() throws Exception {
        ManagedSessionId id = ManagedSessionId.newId();   // no execution: nothing to mount
        controller.show(id);
        editor.refreshLayout();
        resolve(id);
        notViewed(id, SessionPane.Body.LOADING, SessionPaneFacts.loading(), false);
        notViewed(id, SessionPane.Body.FAILED, SessionPaneFacts.failed("boom"), false);
        notViewed(id, SessionPane.Body.UNKNOWN, SessionPaneFacts.unknown(), false);
        notViewed(id, SessionPane.Body.DELETED, SessionPaneFacts.deleted(), false);
        notViewed(id, SessionPane.Body.DETACHED, known(), true);
        notViewed(id, SessionPane.Body.MISSING, new SessionPaneFacts(SessionPaneFacts.State.KNOWN, "",
                Optional.empty(), false, Optional.empty(), false, true), false);
        notViewed(id, SessionPane.Body.EMPTY, known(), false);
    }

    public void testHeldUnfocusedAndPrimaryAreViewedAndAcknowledged() throws Exception {
        ManagedSessionId held = running(), primary = running();
        controller.show(held);
        assertTrue(controller.hold());
        controller.show(primary);
        controller.focus(primary);
        editor.refreshLayout();
        resolve(held, primary);
        assertTrue(controller.visibility.isViewed(primary) && controller.visibility.isViewed(held));
        selectedAndChecked(primary);
        assertTrue(acknowledged.containsAll(List.of(held, primary)));
    }

    public void testHiddenInactiveAndCodeReplacedAreNotViewedAndNotAcknowledged() throws Exception {
        ManagedSessionId id = shownLive();
        SessionHostVisibility visibility = controller.visibility;
        assertTrue(visibility.isViewed(id));
        onScreen = false;
        assertFalse("hidden", visibility.isViewed(id));
        onScreen = true;
        active = false;
        assertFalse("inactive window", visibility.isViewed(id));
        selectedAndChecked(id);
        assertTrue("an event ran but nothing was viewed: " + acknowledged, acknowledged.isEmpty());
        active = true;
        assertTrue(visibility.isViewed(id));
        FileEditorManager.getInstance(getProject()).closeFile(hostFile);
        Disposer.dispose(editor);
        assertFalse("code replaced the host: its roots are unmounted", visibility.isViewed(id));
    }

    public void testPreviousReturnToASessionWithoutAMountedConsoleIsNotAcknowledged() throws Exception {
        ManagedSessionId moved = ManagedSessionId.newId();   // its agent is not in this window
        ManagedSessionId live = running();
        controller.show(moved);
        controller.show(live);
        editor.refreshLayout();
        resolve(live);
        selectedAndChecked(live);
        assertTrue("positive control", acknowledged.remove(live));
        assertEquals(SessionHostModel.Outcome.SHOWN_IN_PRIMARY, controller.previous());
        editor.refreshLayout();
        controller.paneFor(moved).show(known(), true);
        selectedAndChecked(moved);
        assertFalse(controller.visibility.isViewed(moved));
        assertFalse("the session left the screen", controller.visibility.isViewed(live));
        assertTrue("nothing acknowledged on return: " + acknowledged, acknowledged.isEmpty());
    }

    public void testInstallationAndDisposalAreSymmetric() throws Exception {
        int baseline = listeners();
        var extra = new SessionHostController(getProject(), new SessionHostState(), id -> { });
        Disposer.register(getTestRootDisposable(), extra);
        SessionHostVisibility visibility = extra.visibility;
        assertEquals("one global listener installed", baseline + 1, listeners());
        Disposer.dispose(extra);
        assertEquals("the listener left with the controller", baseline, listeners());
        getProject().getMessageBus().syncPublisher(SessionHostListener.TOPIC).sessionSelected(ManagedSessionId.newId());
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        assertEquals("a disposed visibility checks nothing", 0, visibility.checks);
    }
    private static int listeners() {
        return Toolkit.getDefaultToolkit().getAWTEventListeners(
                AWTEvent.HIERARCHY_EVENT_MASK | AWTEvent.WINDOW_EVENT_MASK).length;
    }
}
