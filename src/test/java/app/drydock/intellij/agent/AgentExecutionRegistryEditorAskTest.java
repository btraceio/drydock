package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;
import app.drydock.editorqa.EditorAskLane;

import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.jediterm.terminal.TtyConnector;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The display-only marked terminal and the generation-bound Q&amp;A write, on
 * a real PTY child that records every byte it receives to a file.
 */
public class AgentExecutionRegistryEditorAskTest extends BasePlatformTestCase {

    private static final AgentExecutionListener OWNER = new AgentExecutionListener() { };

    private AgentExecutionRegistry registry;
    private ManagedSessionId id;
    private Path sink;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        registry = AgentExecutionRegistry.getInstance();
        id = ManagedSessionId.newId();
        sink = Files.createTempFile("ask-sink", ".bin");
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            registry.forget(id);
            waitFor(() -> registry.execution(id).isEmpty());
            Files.deleteIfExists(sink);
        } finally {
            super.tearDown();
        }
    }

    private AgentExecution start(Path into) throws Exception {
        List<String> argv = List.of("/bin/sh", "-c", "stty raw -echo; cat > '" + into + "'");
        var started = registry.startArgv(id, Path.of(System.getProperty("java.io.tmpdir")), argv, getProject(), OWNER);
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

    private static <T> T offEdt(Supplier<T> call) throws Exception {
        CompletableFuture<T> future = CompletableFuture.supplyAsync(call);
        waitFor(future::isDone);
        return future.get();
    }

    private String received() throws Exception {
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        Thread.sleep(300);
        return Files.readString(sink, StandardCharsets.UTF_8);
    }

    private TtyConnector connector(AgentExecution execution) {
        return execution.console().orElseThrow().getTerminalWidget().getTtyConnector();
    }

    private static EditorAskLane.Reservation reservation(AgentExecution execution) {
        return new EditorAskLane.Reservation("q1", 1, EditorAskLane.Mode.WRITE, 1,
                AgentExecutionRegistry.executionKey(execution));
    }

    public void testMarkedRowDropsKeysPasteAndRawProgrammaticInput() throws Exception {
        registry.markDisplayOnly(id);
        AgentExecution execution = start(sink);

        connector(execution).write("typed\r");
        connector(execution).write("\u001b[200~pasted\u001b[201~".getBytes(StandardCharsets.UTF_8));
        PromptOutcome raw = offEdt(() -> registry.submitPrompt(id, "imperative"));

        assertEquals(PromptOutcome.REFUSED, raw);
        assertEquals("", received());
    }

    public void testProtocolRepliesStillReachTheProcess() throws Exception {
        registry.markDisplayOnly(id);
        AgentExecution execution = start(sink);

        connector(execution).write("\u001b[3;4R");

        assertEquals("\u001b[3;4R", received());
    }

    public void testOrdinaryRowStaysInteractive() throws Exception {
        AgentExecution execution = start(sink);

        connector(execution).write("typed");
        assertEquals("typed", received());
        assertEquals(PromptOutcome.DELIVERED, offEdt(() -> registry.submitPrompt(id, "line")));
        assertFalse(registry.isDisplayOnly(id));
    }

    public void testMoveReleaseAndRebuildKeepTheGuard() throws Exception {
        registry.markDisplayOnly(id);
        AgentExecution execution = start(sink);

        registry.moveTo(id, getProject());
        connector(execution).write("after-move");
        registry.release(id);
        registry.moveTo(id, getProject());
        connector(execution).write("after-release");

        assertEquals("", received());
    }

    public void testBoundReservationWritesTheFramedPromptExactlyOnce() throws Exception {
        registry.markDisplayOnly(id);
        AgentExecution execution = start(sink);

        PromptOutcome outcome = offEdt(() -> registry.submitAskPrompt(id, "Why é?", reservation(execution), r -> true));

        assertEquals(PromptOutcome.DELIVERED, outcome);
        assertEquals(AgentExecutionRegistry.PASTE_START + "Why é?" + AgentExecutionRegistry.PASTE_END + "\r",
                received());
    }

    public void testStaleForeignOrOrdinaryReservationsWriteNothing() throws Exception {
        registry.markDisplayOnly(id);
        AgentExecution execution = start(sink);
        EditorAskLane.Reservation foreign = new EditorAskLane.Reservation("q1", 1, EditorAskLane.Mode.WRITE, 1,
                "other@0#0");

        assertEquals(PromptOutcome.REFUSED,
                offEdt(() -> registry.submitAskPrompt(id, "p", reservation(execution), r -> false)));
        assertEquals(PromptOutcome.REFUSED, offEdt(() -> registry.submitAskPrompt(id, "p", foreign, r -> true)));
        assertEquals(PromptOutcome.REFUSED,
                offEdt(() -> registry.submitAskPrompt(id, "line\nbreak", reservation(execution), r -> true)));
        assertEquals(PromptOutcome.REFUSED,
                registry.submitAskPrompt(id, "p", reservation(execution), r -> true));   // on the EDT
        assertEquals("", received());
    }

    public void testAThrowingCheckIsAmbiguousAndNothingIsWritten() throws Exception {
        registry.markDisplayOnly(id);
        AgentExecution execution = start(sink);
        Predicate<EditorAskLane.Reservation> boom = r -> {
            throw new IllegalStateException("boom");
        };

        assertEquals(PromptOutcome.FAILED, offEdt(() -> registry.submitAskPrompt(id, "p", reservation(execution), boom)));
        assertEquals("", received());
    }

    public void testAStalledWriteNeverReachesAReplacementExecution() throws Exception {
        registry.markDisplayOnly(id);
        AgentExecution first = start(sink);
        CountDownLatch release = new CountDownLatch(1);
        EditorAskLane.Reservation old = reservation(first);
        CompletableFuture<PromptOutcome> stalled = CompletableFuture.supplyAsync(() ->
                registry.submitAskPrompt(id, "late", old, r -> {
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return true;
                }));

        registry.stop(id);
        waitFor(() -> !first.isRunning());
        Path second = Files.createTempFile("ask-sink2", ".bin");
        try {
            AgentExecution replacement = start(second);
            assertNotSame(first, replacement);
            release.countDown();
            waitFor(stalled::isDone);

            assertTrue(stalled.get() != PromptOutcome.DELIVERED);
            Thread.sleep(300);
            assertEquals("", Files.readString(second));
            assertEquals(PromptOutcome.REFUSED,
                    offEdt(() -> registry.submitAskPrompt(id, "p", old, r -> true)));
        } finally {
            Files.deleteIfExists(second);
        }
    }

    public void testMarkedExecutionsReportStartAndEndWithTheirOwnIdentity() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        registry.markedListener(new AgentExecutionRegistry.MarkedExecutionListener() {
            @Override public void started(ManagedSessionId sessionId, String executionKey) {
                seen.add("start:" + sessionId.equals(id) + ":" + executionKey);
            }

            @Override public void ended(ManagedSessionId sessionId, String executionKey) {
                seen.add("end:" + sessionId.equals(id) + ":" + executionKey);
            }
        });
        try {
            registry.markDisplayOnly(id);
            AgentExecution first = start(sink);
            String firstKey = AgentExecutionRegistry.executionKey(first);
            assertEquals(List.of("start:true:" + firstKey), seen);

            registry.stop(id);
            waitFor(() -> seen.size() == 2);
            assertEquals("end:true:" + firstKey, seen.get(1));

            Path second = Files.createTempFile("ask-sink2", ".bin");
            try {
                AgentExecution replacement = start(second);
                String secondKey = AgentExecutionRegistry.executionKey(replacement);
                assertFalse("each launch has its own identity", firstKey.equals(secondKey));
                assertEquals("start:true:" + secondKey, seen.get(2));
            } finally {
                Files.deleteIfExists(second);
            }
        } finally {
            registry.markedListener(null);
        }
    }

    public void testAnOrdinaryExecutionReportsNothingToTheMarkedListener() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        registry.markedListener(new AgentExecutionRegistry.MarkedExecutionListener() {
            @Override public void started(ManagedSessionId sessionId, String executionKey) {
                seen.add("start");
            }

            @Override public void ended(ManagedSessionId sessionId, String executionKey) {
                seen.add("end");
            }
        });
        try {
            AgentExecution execution = start(sink);
            registry.stop(id);
            waitFor(() -> !execution.isRunning());
            Thread.sleep(200);
            assertTrue(seen.isEmpty());
        } finally {
            registry.markedListener(null);
        }
    }

    public void testForgetDropsTheDisplayOnlyGuardEntry() throws Exception {
        registry.markDisplayOnly(id);
        start(sink);
        assertTrue(registry.isDisplayOnly(id));

        registry.forget(id);

        assertFalse("a forgotten row's guard does not outlive it", registry.isDisplayOnly(id));
    }
}
