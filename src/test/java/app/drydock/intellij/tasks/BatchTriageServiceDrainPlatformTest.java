package app.drydock.intellij.tasks;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionWorkspace;
import app.drydock.git.JiraIssue;
import app.drydock.intellij.agent.AgentExecutionListener;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * The drain's end-of-session wiring on the real application bus: a batch
 * whose parallelism is full launches the next queued issue only when
 * {@code AgentExecutionListener#executionTerminated} arrives for one of its
 * own running sessions -- which AgentExecutionRegistry publishes on the
 * application bus. A termination for a session the batch never launched
 * (another window's, another project's) frees no slot.
 */
public class BatchTriageServiceDrainPlatformTest extends BasePlatformTestCase {

    private final RepositoryId repoId = RepositoryId.newId();
    private final AtomicInteger launches = new AtomicInteger();
    private final List<String> launched = new ArrayList<>();
    private final List<CompletableFuture<ManagedAgentSession>> pendingLaunches = new ArrayList<>();

    private BatchTriageService service;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        service = new BatchTriageService(getProject());
        Disposer.register(getTestRootDisposable(), service);
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

    /** Publishes like the registry does: from a non-EDT thread, synchronously. */
    private static void publishTerminated(ManagedSessionId id) throws InterruptedException {
        Thread thread = new Thread(() -> ApplicationManager.getApplication().getMessageBus()
                .syncPublisher(AgentExecutionListener.TOPIC).executionTerminated(id, false, 0));
        thread.start();
        thread.join(10_000);
        assertFalse("publisher finished", thread.isAlive());
    }

    private static ManagedAgentSession session() {
        return new ManagedAgentSession.Builder(
                ManagedSessionId.newId(), RepositoryId.newId(), "Triage session",
                AgentBinding.unlaunched(AgentKind.CLAUDE),
                new SessionWorkspace(Path.of("/tmp/wt"), Optional.of(Path.of("/tmp/wt")), true),
                Instant.parse("2026-11-23T10:15:30Z"))
                .build();
    }

    private static JiraIssue issue(String key) {
        return new JiraIssue(key, "Summary " + key, "https://jira/" + key, List.of(),
                Optional.empty(), Optional.empty());
    }

    /** Starts a three-issue batch with parallelism two and controlled launch futures. */
    private void startBatch() {
        service.startJiraBatch(repoId, List.of(issue("A-1"), issue("A-2"), issue("A-3")),
                () -> Optional.empty(),
                (name, prompt) -> {
                    launches.incrementAndGet();
                    synchronized (launched) {
                        launched.add(name);
                    }
                    CompletableFuture<ManagedAgentSession> future = new CompletableFuture<>();
                    synchronized (pendingLaunches) {
                        pendingLaunches.add(future);
                    }
                    return future;
                },
                issue -> Optional.empty(),
                2);
    }

    public void testASessionEndFreesTheSlotAndLaunchesTheNextQueuedIssue() throws Exception {
        startBatch();
        waitFor(() -> launches.get() == 2);

        // The session the first launch settled into ends, as Done's
        // forgetConsole does: the registry stops the process and publishes
        // the end on the application bus.
        synchronized (pendingLaunches) {
            pendingLaunches.get(0).complete(session());
        }
        publishTerminated(launchedSessionId(0));

        waitFor(() -> launches.get() == 3);
        synchronized (launched) {
            assertEquals("Triage A-3 Summary A-3", launched.get(2));
        }
    }

    public void testATerminationForASessionTheBatchNeverLaunchedFreesNoSlot() throws Exception {
        startBatch();
        waitFor(() -> launches.get() == 2);

        publishTerminated(ManagedSessionId.newId());

        // One dispatch turn later the batch is still full: nothing launched.
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        Thread.sleep(50);
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        assertEquals(2, launches.get());
    }

    /** The session id of the launch at {@code index}, settled from its controlled future. */
    private ManagedSessionId launchedSessionId(int index) {
        CompletableFuture<ManagedAgentSession> future;
        synchronized (pendingLaunches) {
            future = pendingLaunches.get(index);
        }
        ManagedAgentSession session = future.getNow(null);
        assertNotNull("launch " + index + " has not settled", session);
        return session.id();
    }
}
