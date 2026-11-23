package app.drydock.intellij.agent;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.intellij.agent.SessionHostModel.Outcome;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.TestDialogManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.HeavyPlatformTestCase;
import com.intellij.testFramework.PlatformTestUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The assembled async paths of the session host in a booted IDE with a fresh project per test:
 * facts loads that finish out of order, fail, are rejected or never start, and delayed focus from
 * a launch or the README grace, must each settle without a stuck progress mark, a stale pane
 * answer or a focus that a hide, a user departure or a disposal already voided. Loads are driven by
 * hand (a queued executor and an initialization future the test completes), so order is the test's.
 */
public class SessionHostAsyncPlatformTest extends HeavyPlatformTestCase {
    private final ManagedSessionId a = ManagedSessionId.newId();
    private final ManagedSessionId b = ManagedSessionId.newId();
    private final ManagedSessionId c = ManagedSessionId.newId();
    private final List<Runnable> queued = new ArrayList<>();
    private final List<ManagedSessionId> probed = new CopyOnWriteArrayList<>();
    private final List<ManagedSessionId> events = new ArrayList<>();
    private final Map<ManagedSessionId, Exception> failures = new HashMap<>();
    private final List<ManagedSessionId> started = new ArrayList<>();
    private CompletableFuture<Void> initialization = CompletableFuture.completedFuture(null);
    private boolean rejecting;
    private SessionHostController controller;
    private AgentExecutionRegistry registry;
    private SessionHostFile hostFile;
    private FileEditorProvider previousProvider;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        registry = AgentExecutionRegistry.getInstance();
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

    /** A controller whose loader runs on the test's queue after the test's initialization future. */
    private void manual() {
        controller = new SessionHostController(getProject(), new SessionHostState(), events::add,
                listener -> new SessionFactsLoader(initialization, task -> {
                    if (rejecting) {
                        throw new RejectedExecutionException("drained");
                    }
                    queued.add(task);
                }, id -> {
                    probed.add(id);
                    if (failures.containsKey(id)) {
                        throw failures.get(id);
                    }
                    return Optional.of(new SessionPaneFacts.Inputs(Optional.of(Path.of("/w/a")), Path.of("/w/a"),
                            true, false, OptionalLong.of(1), true, Optional.empty(), Optional.empty(), "a", true, true));
                }, listener));
        Disposer.register(getTestRootDisposable(), controller);
    }

    private void present(ManagedSessionId id) {
        controller.paneFor(id);
        controller.show(id);
    }

    private SessionPaneFacts.State state(ManagedSessionId id) {
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        return controller.facts(id).orElseThrow().state();
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

    public void testRapidSwitchingWithOutOfOrderSuccessAndFailureMovesNothing() {
        manual();
        failures.put(a, new IllegalStateException("boom"));
        present(a);
        present(b);
        present(c);
        assertEquals(3, queued.size());
        var layout = controller.layout();
        long epoch = controller.epoch();

        queued.get(2).run();
        queued.get(0).run();
        queued.get(1).run();

        assertEquals(SessionPaneFacts.State.KNOWN, state(c));
        assertEquals(SessionPaneFacts.State.FAILED, state(a));
        assertEquals(SessionPaneFacts.State.KNOWN, state(b));
        assertSame("results moved no layout", layout, controller.layout());
        assertEquals("results neither fenced nor issued focus", epoch, controller.epoch());
        assertTrue(controller.focusPermitted(epoch));
        assertEquals(List.of(a, b, c), events);
    }

    public void testASlowResultAfterHideAndAfterDepartureTakesNoFocus() {
        manual();
        present(a);
        controller.hide();
        controller.focusDeparted();
        long epoch = controller.epoch();

        queued.get(0).run();

        assertEquals(SessionPaneFacts.State.KNOWN, state(a));
        assertEquals(epoch, controller.epoch());
        assertFalse(controller.focusPermitted(epoch));
    }

    public void testARejectedSubmissionIsFailedNotStuckAndTheNextShowRetries() {
        manual();
        rejecting = true;
        controller.paneFor(a);
        assertEquals(SessionPaneFacts.State.FAILED, state(a));
        assertTrue("nothing was queued or probed", queued.isEmpty() && probed.isEmpty());

        rejecting = false;
        controller.show(a);
        queued.get(0).run();

        assertEquals(SessionPaneFacts.State.KNOWN, state(a));
    }

    public void testAFailedOrCancelledInitializationFailsEveryPendingLoadWithoutProbing() {
        for (boolean cancel : new boolean[] {false, true}) {
            initialization = new CompletableFuture<>();
            manual();
            ManagedSessionId id = cancel ? b : a;
            controller.paneFor(id);
            assertEquals(SessionPaneFacts.State.LOADING, state(id));

            if (cancel) {
                initialization.cancel(true);
            } else {
                initialization.completeExceptionally(new IllegalStateException("init"));
            }

            assertEquals(SessionPaneFacts.State.FAILED, state(id));
        }
        assertTrue("no probe and no executor task for a failed readiness", probed.isEmpty() && queued.isEmpty());
    }

    public void testDisposalWithALoadInFlightSettlesNothingAndLeavesNoTimerOrRequest() {
        manual();
        present(a);
        Disposer.dispose(controller);

        queued.get(0).run();
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();

        assertTrue("the queued load skipped its probes", probed.isEmpty());
        assertTrue(controller.facts(a).isEmpty());
        assertFalse(controller.ageTickRunning());
        assertEquals(Outcome.UNAVAILABLE, controller.show(b));
        assertThrows(IllegalStateException.class, () -> controller.paneFor(b));
    }

    /**
     * Runs one launch under the facade. In tests the progress task runs to completion before
     * {@code start} returns, so the launch stub itself plays the user: it checks the progress mark,
     * departs, and only then lets {@code settle} end the launch (from a pooled thread, or by throwing).
     */
    private void launchThenDepart(Consumer<CompletableFuture<ManagedAgentSession>> settle) throws Exception {
        controller = SessionHostController.getInstance(getProject());
        ManagedSessionId id = ManagedSessionId.newId();
        AtomicBoolean visible = new AtomicBoolean();
        AtomicLong departed = new AtomicLong(-1);
        AgentConsoles.start(getProject(), id, "Resuming…", "resume", sessions -> {
            visible.set(registry.isStarting(id));
            controller.focusDeparted();
            departed.set(controller.epoch());
            CompletableFuture<ManagedAgentSession> launch = new CompletableFuture<>();
            settle.accept(launch);
            return launch;
        }, AgentConsoles::show);
        waitFor(() -> !registry.isStarting(id));
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();

        assertTrue("progress was visible while the launch ran", visible.get());
        assertEquals("the stale completion took no focus", departed.get(), controller.epoch());
        assertFalse(controller.focusPermitted(departed.get()));
    }

    public void testSlowSuccessErrorAndThrowingLaunchesAllClearProgressAndTakeNoStaleFocus() throws Exception {
        List<String> dialogs = new CopyOnWriteArrayList<>();
        TestDialogManager.setTestDialog(message -> {
            dialogs.add(message);
            return Messages.OK;
        }, getTestRootDisposable());
        launchThenDepart(launch -> ApplicationManager.getApplication().executeOnPooledThread(
                () -> launch.complete(null)));
        assertTrue("a slow success shows no dialog", dialogs.isEmpty());

        launchThenDepart(launch -> ApplicationManager.getApplication().executeOnPooledThread(
                () -> launch.completeExceptionally(new IllegalStateException("slow failure"))));
        waitFor(() -> dialogs.size() == 1);

        launchThenDepart(launch -> {
            throw new IllegalStateException("launch refused");
        });
        waitFor(() -> dialogs.size() == 2);
    }

    private void openReadme(String directory) throws Exception {
        Path file = Files.createDirectory(getTempDir().createVirtualDir().toNioPath().resolve(directory))
                .resolve("README.md");
        Files.writeString(file, "readme");
        VirtualFile readme = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file);
        FileEditorManager.getInstance(getProject()).openFile(readme, false);
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
    }

    public void testTheReadmeGraceFocusesTheAgentOnceAndOnlyWhilePermitted() throws Exception {
        ManagedSessionId id = running();
        controller = SessionHostController.getInstance(getProject());
        AgentConsoles.show(getProject(), id);
        AgentConsoles.keepInFrontOfReadme(getProject(), id);
        long shown = controller.epoch();

        openReadme("one");
        long focused = controller.epoch();
        openReadme("two");

        assertTrue("the first README sent focus back to the agent", focused > shown);
        assertEquals("the listener went away after one use", focused, controller.epoch());

        controller.focusDeparted();
        long departed = controller.epoch();
        AgentConsoles.keepInFrontOfReadme(getProject(), id);
        openReadme("three");
        assertEquals("a departed user is not pulled back", departed, controller.epoch());
    }
}
