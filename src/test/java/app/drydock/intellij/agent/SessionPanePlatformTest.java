package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;

import com.intellij.openapi.util.Disposer;
import com.intellij.terminal.TerminalExecutionConsole;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.ui.UIUtil;

import javax.swing.JLabel;
import javax.swing.JPanel;

import java.awt.BorderLayout;
import java.awt.Component;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * The retained pane over a real registry execution (a local {@code sleep}
 * child, not a provider): the console is the execution's, the root can move
 * between hosts, every unresolved state says its own thing, and disposing
 * the pane leaves the console alone.
 */
public class SessionPanePlatformTest extends BasePlatformTestCase {

    private final AgentExecutionListener owner = new AgentExecutionListener() { };
    private AgentExecutionRegistry registry;
    private ManagedSessionId id;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        registry = AgentExecutionRegistry.getInstance();
        id = ManagedSessionId.newId();
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            registry.forget(id);
            waitFor(() -> registry.execution(id).isEmpty());
        } finally {
            super.tearDown();
        }
    }

    private AgentExecution start() throws Exception {
        var started = registry.startArgv(id, Path.of(System.getProperty("java.io.tmpdir")),
                List.of("/bin/sh", "-c", "sleep 30"), getProject(), owner);
        waitFor(started::isDone);
        return started.get(1, TimeUnit.SECONDS);
    }

    private SessionPane pane() {
        return new SessionPane(getProject(), id, new SessionPane.Host() {
            @Override public boolean focusPermitted(long epoch) { return epoch == 7; }
            @Override public long epoch() { return 7; }
            @Override public void paneFocused(ManagedSessionId focused) { }
            @Override public Optional<SessionPaneFacts> facts(ManagedSessionId asked) {
                return asked.equals(id) ? Optional.of(known()) : Optional.empty();
            }
            @Override public void onStripAction(SessionPane pane, AgentConsoleStrip.StripAction action) { }
        });
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

    private static String bodyText(SessionPane pane) {
        // Only the body: the strip above it has labels of its own.
        Component body = ((BorderLayout) pane.root().getLayout()).getLayoutComponent(BorderLayout.CENTER);
        List<JLabel> labels = UIUtil.findComponentsOfType((JPanel) body, JLabel.class);
        assertEquals("one placeholder label", 1, labels.size());
        return labels.get(0).getText();
    }

    public void testRootReparentKeepsTheLiveConsoleAndHasOneParent() throws Exception {
        TerminalExecutionConsole console = start().console().orElseThrow();
        SessionPane pane = pane();
        JPanel first = new JPanel(new BorderLayout()), second = new JPanel(new BorderLayout());

        assertEquals(SessionPane.Body.LIVE, pane.show(known(), false));
        first.add(pane.root());
        assertSame(first, pane.root().getParent());
        assertSame(pane.root(), pane.detachRoot());
        assertNull(pane.root().getParent());
        second.add(pane.root());

        assertSame(second, pane.root().getParent());
        assertEquals(0, first.getComponentCount());
        assertTrue(UIUtil.isAncestor(pane.root(), console.getComponent()));
        assertSame(console, registry.execution(id).orElseThrow().console().orElseThrow());
        assertFalse(Disposer.isDisposed(console));
    }

    public void testEveryUnresolvedStateHasItsOwnBodyAndText() throws Exception {
        SessionPane pane = pane();
        Set<String> texts = new HashSet<>();
        SessionPaneFacts missing = new SessionPaneFacts(SessionPaneFacts.State.KNOWN, "", Optional.empty(),
                false, Optional.empty(), false, true);

        assertEquals(SessionPane.Body.LOADING, pane.show(SessionPaneFacts.loading(), false));
        texts.add(bodyText(pane));
        assertEquals(SessionPane.Body.FAILED, pane.show(SessionPaneFacts.failed("boom"), false));
        assertTrue(bodyText(pane).contains("boom"));
        texts.add(bodyText(pane));
        assertEquals(SessionPane.Body.UNKNOWN, pane.show(SessionPaneFacts.unknown(), false));
        texts.add(bodyText(pane));
        assertEquals(SessionPane.Body.DELETED, pane.show(SessionPaneFacts.deleted(), false));
        texts.add(bodyText(pane));
        assertEquals(SessionPane.Body.DETACHED, pane.show(known(), true));
        texts.add(bodyText(pane));
        assertEquals(SessionPane.Body.MISSING, pane.show(missing, false));
        texts.add(bodyText(pane));
        assertEquals(SessionPane.Body.EMPTY, pane.show(known(), false));
        texts.add(bodyText(pane));

        assertEquals("seven states, seven distinct texts: " + texts, 7, texts.size());
    }

    public void testStoppedExecutionShowsTheDimmedConsoleAndGivesItBack() throws Exception {
        AgentExecution execution = start();
        TerminalExecutionConsole console = execution.console().orElseThrow();
        SessionPane pane = pane();
        execution.handler().destroyProcess();
        waitFor(() -> !execution.isRunning());

        assertEquals(SessionPane.Body.STOPPED, pane.show(known(), false));
        assertTrue(UIUtil.isAncestor(pane.root(), console.getComponent()));
        assertEquals(SessionPane.Body.LOADING, pane.show(SessionPaneFacts.loading(), false));
        assertFalse("the borrowed console left the pane", UIUtil.isAncestor(pane.root(), console.getComponent()));
        assertFalse(Disposer.isDisposed(console));
    }

    public void testDisposingThePaneLeavesTheExecutionsConsole() throws Exception {
        TerminalExecutionConsole console = start().console().orElseThrow();
        SessionPane pane = pane();
        JPanel host = new JPanel(new BorderLayout());
        host.add(pane.root());
        pane.show(known(), false);

        pane.dispose();
        pane.dispose();

        assertFalse("the pane never disposes the execution's console", Disposer.isDisposed(console));
        assertSame(console, registry.execution(id).orElseThrow().console().orElseThrow());
        assertNull(pane.root().getParent());
        assertFalse(UIUtil.isAncestor(pane.root(), console.getComponent()));
        assertEquals("a disposed pane ignores later shows", SessionPane.Body.LIVE,
                pane.show(SessionPaneFacts.failed("late"), false));
        SessionPane next = pane();
        assertEquals(SessionPane.Body.LIVE, next.show(known(), false));
        assertTrue(UIUtil.isAncestor(next.root(), console.getComponent()));
    }

    private static Component bodyComponent(SessionPane pane) {
        JPanel body = (JPanel) ((BorderLayout) pane.root().getLayout()).getLayoutComponent(BorderLayout.CENTER);
        assertEquals("exactly one mounted body component", 1, body.getComponentCount());
        return body.getComponent(0);
    }

    public void testUnchangedRefreshKeepsTheMountedBodyAndTheStoppedWrapper() throws Exception {
        AgentExecution execution = start();
        SessionPane pane = pane();
        assertEquals(SessionPane.Body.LIVE, pane.show(known(), false));
        Component live = bodyComponent(pane);
        assertEquals(SessionPane.Body.LIVE, pane.show(known(), false));
        assertSame("same console, same body: not remounted", live, bodyComponent(pane));

        execution.handler().destroyProcess();
        waitFor(() -> !execution.isRunning());
        assertEquals(SessionPane.Body.STOPPED, pane.show(known(), false));
        Component wrapper = bodyComponent(pane);
        assertNotSame("a real live-to-stopped transition swaps the body", live, wrapper);
        assertEquals(SessionPane.Body.STOPPED, pane.show(known(), false));
        assertSame("the stopped wrapper is kept, not reallocated", wrapper, bodyComponent(pane));
        assertTrue(UIUtil.isAncestor(pane.root(), execution.console().orElseThrow().getComponent()));
    }

    public void testUnchangedPlaceholderKeepsItsLabelAndTakesNewText() {
        SessionPane pane = pane();
        pane.show(SessionPaneFacts.failed("one"), false);
        Component label = bodyComponent(pane);
        pane.show(SessionPaneFacts.failed("one"), false);
        assertSame(label, bodyComponent(pane));
        pane.show(SessionPaneFacts.failed("two"), false);
        assertSame("same kind: only the text changes", label, bodyComponent(pane));
        assertTrue(bodyText(pane).endsWith("two"));
        pane.show(SessionPaneFacts.deleted(), false);
        assertNotSame("a real transition builds the new placeholder", label, bodyComponent(pane));
    }

    /** The host hears about real console mounts (live and stopped), never about placeholder rounds. */
    public void testConsoleMountedFiresOnRealMountsOnly() throws Exception {
        AgentExecution execution = start();
        List<ManagedSessionId> mounted = new ArrayList<>();
        SessionPane pane = new SessionPane(getProject(), id, new SessionPane.Host() {
            @Override public boolean focusPermitted(long epoch) { return epoch == 7; }
            @Override public long epoch() { return 7; }
            @Override public void paneFocused(ManagedSessionId focused) { }
            @Override public Optional<SessionPaneFacts> facts(ManagedSessionId asked) {
                return asked.equals(id) ? Optional.of(known()) : Optional.empty();
            }
            @Override public void onStripAction(SessionPane pane, AgentConsoleStrip.StripAction action) { }
            @Override public void consoleMounted(ManagedSessionId mountedId) { mounted.add(mountedId); }
        });

        pane.show(SessionPaneFacts.loading(), false);
        assertEquals("a placeholder is no console", List.of(), mounted);
        assertEquals(SessionPane.Body.LIVE, pane.show(known(), false));
        assertEquals(List.of(id), mounted);
        assertEquals(SessionPane.Body.LIVE, pane.show(known(), false));
        assertEquals("same console, same body: no second mount", List.of(id), mounted);

        execution.handler().destroyProcess();
        waitFor(() -> !execution.isRunning());
        assertEquals(SessionPane.Body.STOPPED, pane.show(known(), false));
        assertEquals("the stopped view wraps the console: a mount again", List.of(id, id), mounted);
    }
}
