package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;

import com.intellij.execution.process.KillableProcessHandler;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ComponentManagerEx;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.impl.FileEditorManagerImpl;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.terminal.TerminalExecutionConsole;
import com.intellij.testFramework.HeavyPlatformTestCase;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.ServiceContainerUtil;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Twenty harmless {@code /bin/sh} agents behind the assembled facade ({@code AgentConsoles.show},
 * the real host editor, hide and reopen): each session keeps its execution, handler, PID,
 * {@link TerminalExecutionConsole} and router sink, output and exits that happen while the host
 * is closed still arrive, and no release, replay or launch event is ever published.
 *
 * <p><b>Not proven here.</b> Object identity says the same console instance survives; it is NOT
 * evidence that the draft, scroll position, search state or IME composition of that terminal
 * survived. The T32 manual gate stays mandatory for those.</p>
 */
public class SessionHostRetentionPlatformTest extends HeavyPlatformTestCase {
    private static final String WAIT_THEN = "while [ ! -e \"$0\" ]; do sleep 0.05; done; ";

    private record Held(AgentExecution execution, KillableProcessHandler handler, long pid,
                        TerminalExecutionConsole console, Object sink) {
    }

    private final List<ManagedSessionId> started = new ArrayList<>();
    private final List<String> lifecycle = new CopyOnWriteArrayList<>();
    private final Map<ManagedSessionId, Integer> exits = new ConcurrentHashMap<>();
    private AgentExecutionRegistry registry;
    private SessionHostController controller;
    private SessionHostFile hostFile;
    private FileEditorManagerImpl files;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        registry = AgentExecutionRegistry.getInstance();
        controller = SessionHostController.getInstance(getProject());
        hostFile = AgentConsoleFileSystem.getInstance().sessionHostFile();
        files = new FileEditorManagerImpl(getProject(), ((ComponentManagerEx) getProject()).getCoroutineScope());
        ServiceContainerUtil.replaceService(getProject(), FileEditorManager.class, files, getTestRootDisposable());
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            closeHost();
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

    private ManagedSessionId start(String script, Path trigger) throws Exception {
        ManagedSessionId id = ManagedSessionId.newId();
        started.add(id);
        var future = registry.startArgv(id, Path.of(System.getProperty("java.io.tmpdir")),
                List.of("/bin/sh", "-c", script, trigger.toString()), getProject(), new AgentExecutionListener() {
                    @Override
                    public void executionTerminated(ManagedSessionId sessionId, boolean stop, Integer code) {
                        exits.put(sessionId, code);
                    }
                });
        waitFor(future::isDone);
        future.get(1, TimeUnit.SECONDS);
        return id;
    }

    /** Subscribed only after every start, so any release, shown, started or starting event is a violation. */
    private void watchLifecycle() {
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
    }

    private Held capture(ManagedSessionId id) throws ReflectiveOperationException {
        AgentExecution execution = registry.execution(id).orElseThrow();
        Field current = OutputRouter.class.getDeclaredField("current");
        current.setAccessible(true);
        return new Held(execution, execution.handler(), execution.handler().getProcess().pid(),
                execution.console().orElseThrow(), current.get(execution.router()));
    }

    private Map<ManagedSessionId, Held> captureAll() throws ReflectiveOperationException {
        Map<ManagedSessionId, Held> all = new LinkedHashMap<>();
        for (ManagedSessionId id : started) {
            all.put(id, capture(id));
        }
        return all;
    }

    private void assertRetained(Map<ManagedSessionId, Held> before) throws ReflectiveOperationException {
        for (var entry : before.entrySet()) {
            Held was = entry.getValue();
            Held now = capture(entry.getKey());
            assertSame(was.execution(), now.execution());
            assertSame(was.handler(), now.handler());
            assertEquals(was.pid(), now.pid());
            assertSame(was.console(), now.console());
            assertNotNull("the router still feeds a sink", now.sink());
            assertSame(was.sink(), now.sink());
            assertFalse(Disposer.isDisposed(now.console()));
        }
        assertEquals("no release, replay, shown or launch event", List.of(), lifecycle);
    }

    private FileEditor[] hostEditors() {
        return files.getAllEditors(hostFile);
    }

    private void closeHost() {
        files.closeFile(hostFile);
        assertEquals(0, hostEditors().length);
    }

    /** Switches through the facade and mounts the session's retained console, as the pane does for known facts. */
    private void switchTo(ManagedSessionId id, Map<ManagedSessionId, Held> held) {
        AgentConsoles.show(getProject(), id);
        assertEquals(1, hostEditors().length);
        SessionHostEditor editor = (SessionHostEditor) hostEditors()[0];
        SessionPane pane = controller.paneFor(id);
        pane.show(new SessionPaneFacts(SessionPaneFacts.State.KNOWN, "", Optional.empty(), false,
                Optional.empty(), false, false), false);
        assertTrue(editor.leased());
        assertSame(editor.primarySlot(), pane.root().getParent());
        assertTrue("the retained console is the one mounted",
                pane.root().isAncestorOf(held.get(id).console().getComponent()));
    }

    private static String text(TerminalExecutionConsole console) {
        var buffer = console.getTerminalWidget().getTerminalTextBuffer();
        return buffer.getScreenLines() + buffer.getHistoryLinesStorage().toString();
    }

    private List<ManagedSessionId> twentyHarmless(Path go, Path leave) throws Exception {
        for (int i = 0; i < 18; i++) {
            start("sleep 30", go);
        }
        start(WAIT_THEN + "echo HIDDEN-OUTPUT; sleep 30", go);
        start(WAIT_THEN + "exit 3", leave);
        assertEquals(20, started.size());
        return List.copyOf(started);
    }

    public void testRepeatedSwitchingAndHideReopenKeepEveryInstanceOfTwentySessions() throws Exception {
        Path dir = Files.createTempDirectory(getTempDir().createVirtualDir().toNioPath(), "go");
        List<ManagedSessionId> all = twentyHarmless(dir.resolve("go"), dir.resolve("leave"));
        Map<ManagedSessionId, Held> before = captureAll();
        watchLifecycle();
        ManagedSessionId a = all.get(0), b = all.get(1), c = all.get(2);

        for (int round = 0; round < 4; round++) {
            for (ManagedSessionId id : List.of(a, b, c)) {
                switchTo(id, before);
                assertEquals(Optional.of(id), controller.layout().primary());
            }
            closeHost();
            assertNull("hiding detaches the root, nothing else", controller.paneFor(c).root().getParent());
            assertRetained(before);
            switchTo(a, before);
            assertSame("the pane survives a reopen", controller.paneFor(a), controller.paneFor(a));
        }

        assertRetained(before);
    }

    public void testOutputAndExitsWhileTheHostIsClosedArriveWithoutAnyRelease() throws Exception {
        Path dir = Files.createTempDirectory(getTempDir().createVirtualDir().toNioPath(), "go");
        List<ManagedSessionId> all = twentyHarmless(dir.resolve("go"), dir.resolve("leave"));
        ManagedSessionId chatty = all.get(18), leaving = all.get(19);
        Map<ManagedSessionId, Held> before = captureAll();
        watchLifecycle();
        switchTo(chatty, before);
        closeHost();
        assertFalse(text(before.get(chatty).console()).contains("HIDDEN-OUTPUT"));

        Files.createFile(dir.resolve("go"));
        Files.createFile(dir.resolve("leave"));
        waitFor(() -> text(before.get(chatty).console()).contains("HIDDEN-OUTPUT") && exits.containsKey(leaving));

        assertEquals(Integer.valueOf(3), exits.get(leaving));
        assertTrue(before.get(leaving).handler().isProcessTerminated());
        switchTo(chatty, before);
        assertRetained(before);
    }
}
