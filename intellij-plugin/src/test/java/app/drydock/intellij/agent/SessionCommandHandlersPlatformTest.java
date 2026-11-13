package app.drydock.intellij.agent;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.domain.WorkspaceBoxBinding;
import app.drydock.intellij.IsolatedCatalog;
import app.drydock.intellij.startup.DrydockStartupActivity;

import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.TestDialogManager;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.nio.file.Path;

import java.time.Instant;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BooleanSupplier;

/**
 * The remote halves of Stop and Done, in a booted IDE: a row bound to a
 * workspace box stops through the session service's explicit Stop — the
 * box-side kill — never through the registry's local process stop, and its
 * failure is surfaced as the service's box-naming reason; its Done is
 * refused before the flow can open, with the gate's own words. Local rows
 * keep both paths exactly as before: their Stop never reaches the remote
 * call, and their Done is never refused.
 *
 * <p>The remote Stop is pinned through {@link SessionCommandHandlers.RemoteStop}
 * — the same recording-seam pattern the tab-header actions use — because
 * the
 * production call routes through the window's real session service, whose
 * Stop would spawn a real ssh kill against a box no test has. The Done
 * refusal is pinned through the production path: its gate is a state read,
 * so the seeded row drives the real service. The Messages dialogs a
 * refusal or a failure shows are captured through {@code TestDialogManager},
 * never rendered.</p>
 */
public class SessionCommandHandlersPlatformTest extends BasePlatformTestCase {

    private static final WorkspaceBoxBinding BOX =
            new WorkspaceBoxBinding("drydock-repo", "/home/dev/repo");

    private ManagedSessionId id;

    @Override
    protected void setUp() throws Exception {
        // The guard runs BEFORE super.setUp(): before anything in this test
        // can initialize the plugin, and before any catalog write below.
        IsolatedCatalog.assertSandboxed();
        super.setUp();
        id = ManagedSessionId.newId();
    }

    @Override
    protected void tearDown() throws Exception {
        IsolatedCatalog.remove(id);
        super.tearDown();
    }

    public void testRemoteDoneIsRefusedWithTheNamedReason() throws Exception {
        ManagedAgentSession row = remoteRow("Box work");
        IsolatedCatalog.seed(row);
        List<String> dialogs = captureDialogs();

        SessionCommandHandlers.done(getProject(), row);

        // The gate's own words, naming the box and the endings a remote row
        // does have — and nothing else: the Done flow never opened, so no
        // "Checking worktree…" was ever spawned, nothing was stopped.
        assertEquals(List.of("Session 'Box work' runs on workspace box 'drydock-repo';"
                        + " the Done flow is local-only — use Stop or Forget instead"),
                dialogs);
        AgentExecutionRegistry registry = AgentExecutionRegistry.getInstance();
        assertTrue("the refusal stopped the flow before any stop", registry.execution(id).isEmpty());
        assertFalse(registry.isRunning(id));
    }

    public void testARemoteStopRoutesThroughTheSessionService() throws Exception {
        // Detached on purpose: no execution anywhere, which the local stop
        // policy leaves alone — a remote Stop must work there too, because
        // the agent runs in the box's tmux, not in any execution this IDE
        // owns.
        ManagedAgentSession row = remoteRow("Box work");
        List<ManagedSessionId> stopped = new ArrayList<>();
        List<String> dialogs = captureDialogs();
        CompletableFuture<Void> serviceStop = new CompletableFuture<>();
        boolean[] afterStop = {false};

        SessionCommandHandlers.deactivate(getProject(), row, false, () -> afterStop[0] = true,
                stoppedId -> {
                    stopped.add(stoppedId);
                    return serviceStop;
                });

        assertEquals("the service's Stop was called once, with the row's own id",
                List.of(id), stopped);
        assertTrue("the click visibly lands before the kill concludes", afterStop[0]);
        assertTrue("nothing is shown while the stop is in flight", dialogs.isEmpty());

        serviceStop.complete(null);
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        assertTrue("a concluded stop shows nothing", dialogs.isEmpty());
    }

    public void testARemoteStopFailureIsSurfacedNamingTheBox() throws Exception {
        ManagedAgentSession row = remoteRow("Box work");
        List<String> dialogs = captureDialogs();
        CompletableFuture<Void> serviceStop = new CompletableFuture<>();
        boolean[] afterStop = {false};

        SessionCommandHandlers.deactivate(getProject(), row, false, () -> afterStop[0] = true,
                stoppedId -> serviceStop);

        // The service's executor stages fail wrapped; the dialog unwraps to
        // the box-naming reason.
        String reason = "Could not stop the agent of session 'Box work' on box drydock-repo: ssh exited 255";
        serviceStop.completeExceptionally(new CompletionException(new IllegalStateException(reason)));
        waitFor(() -> !dialogs.isEmpty());
        assertEquals(List.of(reason), dialogs);
        assertTrue("the click's repaint ran even though the stop failed", afterStop[0]);
    }

    public void testALocalStopNeverReachesTheRemoteService() throws Exception {
        ManagedAgentSession row = localRow("Local work");
        IsolatedCatalog.seed(row);
        List<ManagedSessionId> stopped = new ArrayList<>();
        List<String> dialogs = captureDialogs();
        boolean[] afterStop = {false};

        SessionCommandHandlers.deactivate(getProject(), row, false, () -> afterStop[0] = true,
                stoppedId -> {
                    stopped.add(stoppedId);
                    return new CompletableFuture<>();
                });

        // The local stop policy is untouched: a row with nothing running is
        // left alone (its stopStep NOTHING), so no remote call, no afterStop,
        // no dialog — the seam was never consulted.
        assertTrue("a local row never reaches the remote stop", stopped.isEmpty());
        assertFalse("the NOTHING early return runs no afterStop", afterStop[0]);
        assertTrue(dialogs.isEmpty());
    }

    public void testALocalRowsDoneGateAnswersEmpty() throws Exception {
        ManagedAgentSession row = localRow("Local work");
        IsolatedCatalog.seed(row);

        // The gate the handler consults for a remote row answers empty for
        // a local one — the Done flow is local-worktree semantics, exactly
        // what a local row has — so the handler's remote-keyed check plus
        // this gate keeps every local Done on today's path. (The flow
        // itself cannot run here: its DialogWrapper is modal-only in
        // unit-test mode, so its half is pinned by the service's own tests.)
        assertEquals(Optional.empty(),
                DrydockStartupActivity.sessions(getProject()).doneFlowRefusal(id));
    }

    /** Captures every Messages dialog's text instead of showing it. */
    private List<String> captureDialogs() {
        List<String> shown = new ArrayList<>();
        TestDialogManager.setTestDialog(message -> {
            shown.add(message);
            return Messages.OK;
        }, getTestRootDisposable());
        return shown;
    }

    /** A remote row as a restart would have loaded it: the box binding present. */
    private ManagedAgentSession remoteRow(String displayName) {
        return row(displayName).workspaceBox(Optional.of(BOX)).build();
    }

    /** A local row: today's shape, no box binding. */
    private ManagedAgentSession localRow(String displayName) {
        return row(displayName).build();
    }

    private ManagedAgentSession.Builder row(String displayName) {
        return new ManagedAgentSession.Builder(id, RepositoryId.newId(), displayName,
                AgentBinding.unlaunched(AgentKind.CLAUDE, SessionMode.WRITES),
                SessionWorkspace.inRepository(Path.of("/wt/" + displayName)), Instant.EPOCH)
                .status(SessionStatus.IDLE);
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
}
