package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.agent.SessionHostController.Layout;
import app.drydock.intellij.agent.SessionHostModel.Outcome;
import app.drydock.intellij.agent.SessionPane.Body;
import app.drydock.intellij.agent.SessionPaneFacts.State;

import com.intellij.ide.impl.OpenProjectTask;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ex.ProjectManagerEx;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import javax.swing.JComponent;
import javax.swing.JPanel;

import java.beans.PropertyChangeListener;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * The controller's metadata wiring over the real {@link SessionFactsLoader}:
 * readiness, executor and probes are the test's own, so a result can be
 * queued, arrive on a real thread, fail inside the selection, or be in flight
 * when the controller closes. The test's factory lambda is the only seam and
 * records only that it ran; the last case goes through the production factory.
 */
public class SessionHostControllerFactsTest extends BasePlatformTestCase {

    /** A bare lease owner token. */
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

    /** What a controller's presentation must keep through any result. */
    private record Frozen(Layout layout, long epoch, boolean permitted, Optional<ManagedSessionId> hint,
                          List<ManagedSessionId> events) {
    }

    private final ManagedSessionId a = ManagedSessionId.newId();
    private final ManagedSessionId b = ManagedSessionId.newId();
    private final ManagedSessionId c = ManagedSessionId.newId();
    private final List<ManagedSessionId> events = new ArrayList<>();
    private final List<Runnable> queued = new ArrayList<>();
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final AtomicInteger factories = new AtomicInteger();
    private final AtomicInteger probed = new AtomicInteger();
    private final AtomicBoolean probedOnUiThread = new AtomicBoolean();
    private final AtomicBoolean factoryOnUiThread = new AtomicBoolean();
    private volatile boolean failing;
    private SessionHostState state;
    private SessionFactsLoader created;
    private SessionHostController controller;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        state = new SessionHostState();
        controller = host(getProject(), state, queued::add);
    }

    private SessionHostController host(Project in, SessionHostState with, Executor executor) {
        SessionHostController made = new SessionHostController(in, with, events::add, listener -> {
            factories.incrementAndGet();
            factoryOnUiThread.set(ApplicationManager.getApplication().isDispatchThread());
            created = new SessionFactsLoader(ready, executor, id -> {
                probed.incrementAndGet();
                probedOnUiThread.set(ApplicationManager.getApplication().isDispatchThread());
                if (failing) {
                    throw new IllegalStateException("probe failed");
                }
                return Optional.empty();
            }, listener);
            return created;
        });
        Disposer.register(getTestRootDisposable(), made);
        return made;
    }

    /** Runs what the loader queued, without letting the UI thread see the results yet. */
    private void step() {
        List<Runnable> work = List.copyOf(queued);
        queued.clear();
        work.forEach(Runnable::run);
    }

    private void drain() {
        step();
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
    }

    private void retried(int totalProbes) {
        assertEquals("exactly one load was queued", 1, queued.size());
        drain();
        assertEquals(totalProbes, probed.get());
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

    private Frozen freeze() {
        return new Frozen(controller.layout(), controller.epoch(), controller.focusPermitted(controller.epoch()),
                state.selectedSession(), List.copyOf(events));
    }

    private static SessionFactsLoader.Result result(ManagedSessionId id, long generation, SessionPaneFacts facts) {
        return new SessionFactsLoader.Result(id, generation, facts);
    }

    private void assertIgnored(SessionPane pane, SessionFactsLoader.Result ignored) {
        SessionPaneFacts held = controller.facts(ignored.id()).orElse(null);
        Body shown = pane.shown();
        Frozen before = freeze();
        controller.apply(ignored);
        assertSame(ignored + " replaces nothing held", held, controller.facts(ignored.id()).orElse(null));
        assertEquals(shown, pane.shown());
        assertEquals(before, freeze());
    }

    public void testTheLoaderExistsOnlyAfterAPaneIsFirstRequested() {
        SessionHostState remembered = new SessionHostState();
        remembered.getState().selectedSessionId = a.toString();
        SessionHostController adopted = host(getProject(), remembered, queued::add);
        assertEquals(Optional.of(a), adopted.layout().primary());
        assertNotNull("the service-resolved controller is built without a loader", SessionHostController.getInstance(getProject()));
        assertEquals(Outcome.SHOWN_IN_PRIMARY, adopted.show(a));
        assertEquals(Outcome.SHOWN_IN_PRIMARY, controller.show(b));
        assertTrue(controller.hold());
        assertEquals(Outcome.UNAVAILABLE, controller.focus(c));
        assertEquals("nothing is retained, so nothing asked for a loader", 0, factories.get());

        controller.paneFor(a);
        assertEquals(1, factories.get());
        assertTrue("the factory runs on the caller's (UI) thread, inside the first paneFor", factoryOnUiThread.get());
        controller.paneFor(b);
        controller.paneFor(a);
        assertEquals("a second pane reuses the one loader", 1, factories.get());
    }

    public void testAFailureDeliveredInsideTheSelectionIsPresentedOnce() {
        ready.completeExceptionally(new IllegalStateException("not ready"));
        SessionPane pane = controller.paneFor(a);

        assertEquals("the synchronous FAILED is painted before paneFor returns", Body.FAILED, pane.shown());
        SessionPaneFacts failed = controller.facts(a).orElseThrow();
        assertEquals(State.FAILED, failed.state());
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        pane.refresh();
        assertSame("the duplicate hop and a refresh replace nothing", failed, controller.facts(a).orElseThrow());
        assertEquals(0, probed.get());
        assertTrue(queued.isEmpty());
    }

    public void testPendingLoadingIsReplacedByItsOwnGenerationsAnswerAndNothingElseOverwrites() {
        ready.complete(null);
        SessionPane pane = controller.paneFor(a), hidden = controller.paneFor(c);
        assertEquals(State.LOADING, controller.facts(a).orElseThrow().state());
        assertEquals(Body.LOADING, pane.shown());
        Frozen before = freeze();
        drain();
        assertEquals("a terminal answer of the same generation replaces LOADING", Body.UNKNOWN, pane.shown());
        assertEquals(State.UNKNOWN, controller.facts(a).orElseThrow().state());
        assertEquals("a hidden retained pane updates", Body.UNKNOWN, hidden.shown());
        assertEquals("without selecting, reopening or focusing", before, freeze());

        SessionPane other = controller.paneFor(b);
        queued.clear();
        assertIgnored(other, result(b, 0, SessionPaneFacts.failed("inert")));
        assertIgnored(other, result(ManagedSessionId.newId(), 9, SessionPaneFacts.failed("no retained pane")));
        controller.apply(result(b, 50, SessionPaneFacts.loading()));
        assertEquals("a newer generation's LOADING is accepted", Body.LOADING, other.shown());
        assertIgnored(other, result(b, 49, SessionPaneFacts.failed("older generation")));
        assertIgnored(other, result(b, 50, SessionPaneFacts.loading()));
        controller.apply(result(b, 50, SessionPaneFacts.unknown()));
        assertEquals(Body.UNKNOWN, other.shown());
        assertEquals("an accepted result leaves the presentation alone", before, freeze());
        assertIgnored(other, result(b, 49, SessionPaneFacts.failed("older generation after the answer")));
        assertIgnored(other, result(b, 50, SessionPaneFacts.failed("late terminal duplicate")));
        assertIgnored(other, result(b, 50, SessionPaneFacts.loading()));
        assertEquals("a result is routed by its id to its own pane", Body.UNKNOWN, pane.shown());
    }

    public void testAnAcceptedShowRetriesAFailedPaneAndItsOlderQueuedFailureIsRejected() {
        ready.complete(null);
        failing = true;
        SessionPane pane = controller.paneFor(a);
        step();
        failing = false;
        assertEquals(Outcome.SHOWN_IN_PRIMARY, controller.show(a));
        assertEquals("the retry shows its own LOADING", Body.LOADING, pane.shown());
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        assertEquals("the older queued FAILED is rejected by generation", Body.LOADING, pane.shown());
        retried(2);
        assertEquals(Body.UNKNOWN, pane.shown());

        controller.show(a);
        controller.focus(a);
        pane.refresh();
        assertEquals("a known answer is not probed again", 2, probed.get());
        assertTrue(queued.isEmpty());
    }

    public void testFocusPreviousAndPaneFocusEachRetryAFailedPaneOnceAndRefusalsProbeNothing() {
        ready.complete(null);
        failing = true;
        SessionPane pa = controller.paneFor(a);
        controller.paneFor(b);
        drain();
        assertEquals(2, probed.get());

        assertEquals(Outcome.UNAVAILABLE, controller.focus(a));
        assertEquals(Outcome.NO_PREVIOUS, controller.previous());
        controller.invalidate(b);
        assertEquals(Outcome.UNAVAILABLE, controller.show(b));
        controller.revalidate(b);
        assertTrue("refused presentations probe nothing", queued.isEmpty());

        controller.show(a);
        retried(3);
        controller.show(b);
        retried(4);
        assertEquals(Outcome.SHOWN_IN_PRIMARY, controller.previous());
        retried(5);
        assertTrue(controller.hold());
        assertTrue("hold probes nothing", queued.isEmpty());
        assertEquals(Outcome.FOCUSED_HELD, controller.focus(a));
        retried(6);
        assertTrue(controller.acquireLease(new Editor()));
        controller.paneFocused(a);
        retried(7);
        assertEquals(Body.FAILED, pa.shown());
        pa.refresh();
        controller.facts(a);
        assertTrue("refresh and facts retry nothing", queued.isEmpty());
    }

    public void testResultsInFlightWhenTheControllerOrProjectClosesAreDropped() throws Exception {
        ready.complete(null);
        SessionPane pane = controller.paneFor(a);
        step();
        controller.dispose();
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        assertEquals("the queued result never reached the disposed pane", Body.LOADING, pane.shown());
        assertEquals(Optional.empty(), controller.facts(a));
        controller.apply(result(a, 7, SessionPaneFacts.failed("late")));
        assertEquals(Optional.empty(), controller.facts(a));

        ExecutorService background = Executors.newSingleThreadExecutor();
        Disposer.register(getTestRootDisposable(), background::shutdownNow);
        SessionPane threaded = host(getProject(), new SessionHostState(), background).paneFor(b);
        long deadline = System.currentTimeMillis() + 10_000;
        while (probed.get() < 2) {
            assertTrue("the background probe never ran", System.currentTimeMillis() < deadline);
            Thread.sleep(10);
        }
        assertEquals("the UI thread has not been given the result", Body.LOADING, threaded.shown());
        waitFor(() -> threaded.shown() == Body.UNKNOWN);
        assertFalse("the probe ran off the UI thread", probedOnUiThread.get());

        Path directory = Files.createTempDirectory("t40-project");
        Project other = ProjectManagerEx.getInstanceEx().newProject(directory, OpenProjectTask.build());
        assertNotNull(other);
        SessionPane inOther = host(other, new SessionHostState(), queued::add).paneFor(c);
        step();
        assertTrue(ProjectManagerEx.getInstanceEx().forceCloseProject(other));
        assertTrue("the child project is gone before its queued hop runs", other.isDisposed());
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        assertEquals("a result for a closed project is dropped", Body.LOADING, inOther.shown());
    }

    public void testDisposalLeavesSharedReadinessAndExecutorAloneAndTheLoaderInert() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Disposer.register(getTestRootDisposable(), executor::shutdownNow);
        SessionHostController owner = host(getProject(), new SessionHostState(), executor);
        SessionPane pane = owner.paneFor(a);
        SessionFactsLoader loader = created;
        owner.dispose();
        owner.dispose();
        Disposer.dispose(owner);

        Method late = SessionHostController.class.getDeclaredMethod("loader");
        late.setAccessible(true);
        assertNull("a loader requested after the disposal claim is refused", late.invoke(owner));
        assertEquals("the refused request does not reach the factory", 1, factories.get());

        assertEquals("a disposed loader answers inert", 0, loader.select(b).generation());
        assertEquals(1, factories.get());
        assertFalse("the shared readiness is not cancelled", ready.isDone());
        assertFalse("the shared executor is not shut down", executor.isShutdown());
        ready.complete(null);
        executor.submit(() -> { }).get(10, TimeUnit.SECONDS);
        assertEquals("no probe starts after disposal", 0, probed.get());
        assertEquals(Optional.empty(), owner.facts(a));
        assertThrows(IllegalStateException.class, () -> owner.paneFor(a));
        assertEquals(Body.LOADING, pane.shown());
    }

    public void testTheProductionFactoryReachesAnUnknownAnswerThroughTheRealCatalogProbe() throws Exception {
        assertNotNull("the real plugin service is required", DrydockPluginService.getInstance());
        SessionHostController production = new SessionHostController(getProject(), new SessionHostState(), events::add);
        Disposer.register(getTestRootDisposable(), production);
        SessionPane pane = production.paneFor(a);

        // An empty catalog makes the probe settle in microseconds, and the
        // loader's virtual thread can win the race to settle request.terminal
        // before select() re-reads it -- paneFor then returns already showing
        // UNKNOWN. Synchronous settlement is the intended contract (a
        // readiness failure must paint FAILED before paneFor returns), so a
        // fast UNKNOWN is legitimate; only assert the pane never shows
        // something else, and let the wait prove the answer is reached.
        assertTrue("LOADING or an already-settled answer, never FAILED",
                pane.shown() == Body.LOADING || pane.shown() == Body.UNKNOWN);
        waitFor(() -> pane.shown() == Body.UNKNOWN);
        assertEquals(State.UNKNOWN, production.facts(a).orElseThrow().state());
    }
}
