package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.ui.UIUtil;

import javax.swing.JPanel;

import java.awt.BorderLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * The controller's pane identity over a real project and a real local
 * {@code sleep} registry execution (no provider): one pane and root per
 * session, the borrowed console kept, and no cached facts. Metadata delivery
 * is not here yet, so facts are shown to a pane directly.
 */
public class SessionHostControllerPanesTest extends BasePlatformTestCase {

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

    public void testPanesAndRootsAreTheSameAcrossSlotChangesHideAndReopen() {
        SessionPane pa = controller.paneFor(a), pb = controller.paneFor(b), pc = controller.paneFor(c);
        JPanel parent = new JPanel(new BorderLayout());
        controller.show(a);
        assertTrue(controller.hold());
        controller.show(b);
        controller.show(c);
        controller.hide();
        controller.show(a);
        parent.add(pa.root());
        pa.detachRoot();
        parent.add(pa.root());

        assertSame(pa, controller.paneFor(a));
        assertSame(pb, controller.paneFor(b));
        assertSame(pc, controller.paneFor(c));
        assertSame(parent, pa.root().getParent());
    }

    public void testSwitchingKeepsTheRegistryConsoleWithoutReleaseReplayOrLaunch() throws Exception {
        AgentExecution execution = start();
        SessionPane pa = controller.paneFor(a);
        List<String> registryEvents = new CopyOnWriteArrayList<>();
        MessageBusConnection connection = ApplicationManager.getApplication().getMessageBus().connect(getTestRootDisposable());
        connection.subscribe(AgentExecutionListener.TOPIC, new AgentExecutionListener() {
            @Override public void executionStarted(ManagedSessionId id) { registryEvents.add("started " + id); }
            @Override public void executionReleased(ManagedSessionId id) { registryEvents.add("released " + id); }
            @Override public void executionShown(ManagedSessionId id, Project to) { registryEvents.add("shown " + id); }
            @Override public void executionMoving(ManagedSessionId id, boolean moving) { registryEvents.add("moving " + id); }
        });
        assertEquals(SessionPane.Body.LIVE, pa.show(known(), false));
        var console = execution.console().orElseThrow();

        controller.show(a);
        controller.show(b);
        controller.show(c);
        controller.hold();
        controller.hide();
        controller.show(a);

        assertEquals(SessionPane.Body.LIVE, pa.show(known(), false));
        assertTrue(UIUtil.isAncestor(pa.root(), console.getComponent()));
        assertSame(execution, registry.execution(a).orElseThrow());
        assertSame(console, registry.execution(a).orElseThrow().console().orElseThrow());
        assertEquals(List.of(), registryEvents);
    }

    public void testFactsAreEmptyWhileNothingIsCached() {
        assertEquals(Optional.empty(), controller.facts(a));
        controller.show(a);
        assertEquals(Optional.empty(), controller.facts(a));
        controller.paneFor(a);
        assertTrue("the cold pane's real LOADING or terminal presentation exists", controller.facts(a).isPresent());
    }
}
