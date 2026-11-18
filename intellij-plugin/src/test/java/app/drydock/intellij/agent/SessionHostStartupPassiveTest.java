package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.HeavyPlatformTestCase;
import com.intellij.testFramework.PlatformTestUtil;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Startup is passive (INV-14): a remembered session id is adopted as data only. Constructing the
 * host, with or without a remembered id, opens no document, mounts no pane, loads no facts,
 * announces nothing, issues no focus permission, starts no timer and reattaches no console; only
 * an intentional request ever writes the one remembered id.
 */
public class SessionHostStartupPassiveTest extends HeavyPlatformTestCase {
    private final ManagedSessionId remembered = ManagedSessionId.newId();
    private final List<String> lifecycle = new CopyOnWriteArrayList<>();
    private final List<ManagedSessionId> selections = new CopyOnWriteArrayList<>();
    private final AtomicInteger loaders = new AtomicInteger();

    private SessionHostController adopt(SessionHostState state) {
        var controller = new SessionHostController(getProject(), state, selections::add, listener -> {
            loaders.incrementAndGet();
            throw new IllegalStateException("startup must not load facts");
        });
        Disposer.register(getTestRootDisposable(), controller);
        return controller;
    }

    private SessionHostState remembering(String id) {
        SessionHostState state = new SessionHostState();
        state.getState().selectedSessionId = id;
        return state;
    }

    public void testAdoptingARememberedIdOpensAndReattachesNothing() {
        ApplicationManager.getApplication().getMessageBus().connect(getTestRootDisposable())
                .subscribe(AgentExecutionListener.TOPIC, new AgentExecutionListener() {
                    @Override
                    public void executionStarted(ManagedSessionId sessionId) {
                        lifecycle.add("started " + sessionId);
                    }

                    @Override
                    public void executionStarting(ManagedSessionId sessionId, boolean starting) {
                        lifecycle.add("starting " + sessionId);
                    }

                    @Override
                    public void executionShown(ManagedSessionId sessionId, Project to) {
                        lifecycle.add("shown " + sessionId);
                    }

                    @Override
                    public void executionReleased(ManagedSessionId sessionId) {
                        lifecycle.add("released " + sessionId);
                    }
                });
        long epoch = SessionHostController.getInstance(getProject()).epoch();

        SessionHostController controller = adopt(remembering(remembered.toString()));
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();

        assertEquals(Optional.of(remembered), controller.layout().primary());
        assertEquals(Optional.empty(), controller.layout().held());
        assertEquals("adoption is no history", Optional.empty(), controller.layout().current());
        assertEquals(Optional.empty(), controller.layout().previous());
        assertEquals(epoch, controller.epoch());
        assertFalse("adoption is no focus permission", controller.focusPermitted(controller.epoch()));
        assertFalse(controller.ageTickRunning());
        assertEquals("no loader was even asked for, so no facts load", 0, loaders.get());
        assertEquals(Optional.empty(), controller.facts(remembered));
        assertEquals("no selection announced", List.of(), selections);
        assertEquals("no document and no editor was opened", 0,
                FileEditorManager.getInstance(getProject()).getOpenFiles().length);
        AgentExecutionRegistry registry = AgentExecutionRegistry.getInstance();
        assertTrue(registry.execution(remembered).isEmpty());
        assertFalse(registry.isRunning(remembered));
        assertFalse(registry.isStarting(remembered));
        assertEquals("no start, shown or release event", List.of(), lifecycle);
    }

    public void testAMalformedOrAbsentRememberedIdAdoptsNothing() {
        for (String stored : new String[] {null, "not-an-id"}) {
            SessionHostController controller = adopt(remembering(stored));
            assertEquals(Optional.empty(), controller.layout().primary());
            assertEquals(Optional.empty(), controller.layout().current());
        }
        assertEquals(List.of(), selections);
    }

    public void testOnlyTheLastIntentionalSelectionIsRememberedNeverHeldOrPrevious() {
        SessionHostState state = new SessionHostState();
        SessionHostController first = adopt(state);
        ManagedSessionId held = ManagedSessionId.newId();
        first.show(held);
        first.hold();
        first.show(remembered);
        first.focusDeparted();
        assertEquals(Optional.of(remembered), state.selectedSession());
        assertEquals("the stored schema is the one id", 1, SessionHostState.Stored.class.getFields().length);

        SessionHostController reopened = adopt(state);

        assertEquals(Optional.of(remembered), reopened.layout().primary());
        assertEquals("no persisted held session", Optional.empty(), reopened.layout().held());
        assertEquals("no persisted previous session", Optional.empty(), reopened.layout().previous());
        assertEquals(List.of(held, remembered), selections);
    }
}
