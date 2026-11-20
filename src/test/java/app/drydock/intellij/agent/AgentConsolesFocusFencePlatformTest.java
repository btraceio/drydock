package app.drydock.intellij.agent;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.HeavyPlatformTestCase;
import com.intellij.testFramework.PlatformTestUtil;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * The facade's routing to the one canonical host document and its focus fence, in a booted IDE
 * with a fresh project per test. A delayed focus that was issued before a hide, a user departure,
 * a newer request or the controller's disposal must neither reopen the host nor take focus; the
 * epoch is the observable: a taken focus is an accepted controller request and advances it.
 */
public class AgentConsolesFocusFencePlatformTest extends HeavyPlatformTestCase {
    private final List<ManagedSessionId> started = new ArrayList<>();
    private AgentExecutionRegistry registry;
    private SessionHostController controller;
    private SessionHostFile hostFile;
    private FileEditorProvider previousProvider;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        registry = AgentExecutionRegistry.getInstance();
        controller = SessionHostController.getInstance(getProject());
        hostFile = AgentConsoleFileSystem.getInstance().sessionHostFile();
        previousProvider = hostFile.getUserData(FileEditorProvider.KEY);
        hostFile.putUserData(FileEditorProvider.KEY, new AgentConsoleEditorProvider());
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            FileEditorManager files = FileEditorManager.getInstance(getProject());
            for (FileEditor editor : files.getAllEditors(hostFile)) {
                Disposer.dispose(editor);
            }
            files.closeFile(hostFile);
            for (ManagedSessionId id : started) {
                registry.forget(id);
                waitFor(() -> registry.execution(id).isEmpty());
            }
        } finally {
            hostFile.putUserData(FileEditorProvider.KEY, previousProvider);
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

    private FileEditor[] hostEditors() {
        return FileEditorManager.getInstance(getProject()).getAllEditors(hostFile);
    }

    /** Runs the queued delayed focus, then returns. */
    private static void drain() {
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
    }

    /** The hosted editor goes away the way a closed tab does: its dispose releases the lease and hides. */
    private void closeHost() {
        for (FileEditor editor : hostEditors()) {
            Disposer.dispose(editor);
        }
        FileEditorManager.getInstance(getProject()).closeFile(hostFile);
        assertEquals(0, hostEditors().length);
    }

    public void testShowOpensAndReusesTheOneHostDocumentAndLaunchesNothing() throws Exception {
        ManagedSessionId first = ManagedSessionId.newId();
        ManagedSessionId second = ManagedSessionId.newId();
        List<String> events = new CopyOnWriteArrayList<>();
        ApplicationManager.getApplication().getMessageBus().connect(getTestRootDisposable())
                .subscribe(AgentExecutionListener.TOPIC, new AgentExecutionListener() {
                    @Override
                    public void executionShown(ManagedSessionId sessionId, Project to) {
                        events.add("shown " + sessionId);
                    }

                    @Override
                    public void executionTerminated(ManagedSessionId sessionId, boolean stop, Integer code) {
                        events.add("terminated " + sessionId);
                    }
                });

        AgentConsoles.show(getProject(), first);
        AgentConsoles.show(getProject(), second);
        AgentConsoles.show(getProject(), second);

        assertEquals("one document, reused for every session", 1, hostEditors().length);
        assertTrue(hostEditors()[0] instanceof SessionHostEditor);
        assertEquals(Optional.of(second), controller.layout().primary());
        assertEquals("no per-session file was opened", 0, FileEditorManager.getInstance(getProject())
                .getAllEditors(AgentConsoleFileSystem.getInstance().fileFor(first)).length);
        assertTrue("an accepted show issued focus permission", controller.focusPermitted(controller.epoch()));
        for (ManagedSessionId id : List.of(first, second)) {
            assertTrue(registry.execution(id).isEmpty());
            assertFalse(registry.isRunning(id));
            assertFalse(registry.isStarting(id));
        }
        assertEquals("Show launched or moved nothing", List.of(), events);
    }

    public void testStartForARunningSessionOnlyShowsAndNeverCallsTheLaunch() throws Exception {
        ManagedSessionId id = running();
        AtomicInteger launches = new AtomicInteger();

        AgentConsoles.start(getProject(), id, "Resuming…", "resume", sessions -> {
            launches.incrementAndGet();
            return CompletableFuture.<ManagedAgentSession>completedFuture(null);
        }, AgentConsoles::show);
        drain();

        assertEquals("the production decision routes a running session to show", 0, launches.get());
        assertEquals(Optional.of(id), controller.layout().primary());
        assertEquals(1, hostEditors().length);
        assertFalse(registry.isStarting(id));
    }

    public void testAcceptedFocusTakesFocusAndAdvancesTheEpoch() throws Exception {
        ManagedSessionId id = running();
        AgentConsoles.show(getProject(), id);
        long before = controller.epoch();

        AgentConsoles.focusConsoleLater(getProject(), id, before);
        drain();

        assertTrue("the permitted delayed focus was taken", controller.epoch() > before);
        assertTrue(controller.focusPermitted(controller.epoch()));
    }

    public void testFocusIssuedBeforeAHideNeitherReopensTheHostNorTakesFocus() throws Exception {
        ManagedSessionId id = running();
        AgentConsoles.show(getProject(), id);
        long issued = controller.epoch();
        closeHost();
        long hidden = controller.epoch();
        assertFalse(controller.focusPermitted(issued));

        AgentConsoles.focusConsoleLater(getProject(), id, issued);
        AgentConsoles.focusConsoleLater(getProject(), id);
        drain();

        assertEquals("no focus request reached the controller", hidden, controller.epoch());
        assertEquals("a hidden host stays closed", 0, hostEditors().length);
    }

    public void testFocusIssuedBeforeUserDepartureIsRefused() throws Exception {
        ManagedSessionId id = running();
        AgentConsoles.show(getProject(), id);
        long issued = controller.epoch();
        controller.focusDeparted();
        long departed = controller.epoch();

        AgentConsoles.focusConsoleLater(getProject(), id, issued);
        AgentConsoles.focusConsoleLater(getProject(), id, departed);
        drain();

        assertEquals("neither the old token nor the current epoch is honoured", departed, controller.epoch());
    }

    public void testFocusIssuedBeforeANewerRequestIsRefused() throws Exception {
        ManagedSessionId id = running();
        AgentConsoles.show(getProject(), id);
        long issued = controller.epoch();
        AgentConsoles.show(getProject(), ManagedSessionId.newId());
        long newer = controller.epoch();

        AgentConsoles.focusConsoleLater(getProject(), id, issued);
        drain();

        assertEquals("the older request takes no focus", newer, controller.epoch());
    }

    public void testFocusAfterTheControllerIsDisposedIsDroppedSilently() throws Exception {
        ManagedSessionId id = running();
        AgentConsoles.show(getProject(), id);
        long issued = controller.epoch();
        Disposer.dispose(controller);
        long closed = controller.epoch();

        AgentConsoles.focusConsoleLater(getProject(), id, issued);
        drain();

        assertFalse(controller.focusPermitted(issued));
        assertFalse(controller.focusPermitted(closed));
        assertEquals("the closed controller took no request", closed, controller.epoch());
    }

    public void testAStaleLaunchStillClearsTheStartingMarkAndRunsTheLaunch() throws Exception {
        ManagedSessionId id = ManagedSessionId.newId();   // nothing runs: the START path
        AtomicInteger launches = new AtomicInteger();
        AtomicLong departedEpoch = new AtomicLong(-1);

        AgentConsoles.start(getProject(), id, "Resuming…", "resume", sessions -> {
            launches.incrementAndGet();
            // The user leaves the host while the launch is still in flight.
            ApplicationManager.getApplication().invokeAndWait(() -> {
                controller.focusDeparted();
                departedEpoch.set(controller.epoch());
            });
            return CompletableFuture.<ManagedAgentSession>completedFuture(null);
        }, AgentConsoles::show);
        waitFor(() -> launches.get() == 1 && !registry.isStarting(id));
        drain();

        assertEquals("the launch ran once, outside the focus fence", 1, launches.get());
        assertFalse("progress cleanup ran", registry.isStarting(id));
        assertEquals("the stale completion took no focus", departedEpoch.get(), controller.epoch());
    }
}
