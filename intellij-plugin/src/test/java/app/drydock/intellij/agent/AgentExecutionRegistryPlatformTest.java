package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;

import com.intellij.openapi.util.Disposer;
import com.intellij.terminal.TerminalExecutionConsole;
import com.intellij.testFramework.LeakHunter;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.jediterm.terminal.model.TerminalLine;
import com.jediterm.terminal.model.TerminalTextBuffer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * The registry that owns agent processes, in a booted IDE with a real PTY
 * child: one process per session, shown in at most one place, kept alive
 * when nothing shows it, and every end reported exactly once to whoever
 * started it.
 *
 * <p>One project only — {@code BasePlatformTestCase} has one — so a move is
 * {@code moveTo} this same project: the registry builds a new console exactly
 * as it does for another window. The real two-window move is a live check.</p>
 */
public class AgentExecutionRegistryPlatformTest extends BasePlatformTestCase {

    /** Prints numbered lines for a few seconds, then idles: a stand-in agent. */
    private static final List<String> STREAMING = List.of("/bin/sh", "-c",
            "i=0; while [ $i -lt 3000 ]; do i=$((i+1)); printf 'LINE %06d\\n' $i; sleep 0.002; done; sleep 30");

    /** What the starting window's session service would be told. */
    private record Ended(ManagedSessionId id, boolean stopRequested, Integer exitCode) {
    }

    private final List<Ended> ended = new CopyOnWriteArrayList<>();
    private final AgentExecutionListener owner = new AgentExecutionListener() {
        @Override
        public void executionTerminated(ManagedSessionId sessionId, boolean stopRequested, Integer exitCode) {
            ended.add(new Ended(sessionId, stopRequested, exitCode));
        }
    };

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

    private AgentExecution start(List<String> argv) throws Exception {
        Path cwd = Path.of(System.getProperty("java.io.tmpdir"));
        var started = registry.startArgv(id, cwd, argv, getProject(), owner);
        waitFor(started::isDone);
        return started.get(1, TimeUnit.SECONDS);
    }

    /** Polls, pumping the EDT (the test runs on it), for up to ten seconds. */
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

    private static String text(TerminalExecutionConsole console) {
        return console.getTerminalWidget().getTerminalTextBuffer().getScreenLines()
                + console.getTerminalWidget().getTerminalTextBuffer().getHistoryLinesStorage().toString();
    }

    public void testAStartedAgentIsShownInItsHostAndRunsOnce() throws Exception {
        start(STREAMING);

        assertEquals(getProject(), registry.host(id).orElseThrow());
        assertTrue(registry.isRunning(id));
        assertEquals(List.of(id), registry.runningShownIn(getProject()));
        var second = registry.startArgv(id, Path.of("/tmp"), STREAMING, getProject(), owner);
        waitFor(second::isDone);
        try {
            second.get();
            fail("a second start of a running session must fail");
        } catch (ExecutionException e) {
            assertInstanceOf(e.getCause(), IllegalStateException.class);
        }
    }

    public void testShowingAgainMidStreamKeepsTheProcessAndSwapsTheConsole() throws Exception {
        AgentExecution execution = start(STREAMING);
        long pid = execution.handler().getProcess().pid();
        TerminalExecutionConsole before = execution.console().orElseThrow();
        Thread.sleep(300);

        assertSame(execution, registry.show(id, getProject()).orElseThrow());
        assertSame(before, execution.console().orElseThrow());
        registry.moveTo(id, getProject());

        TerminalExecutionConsole after = execution.console().orElseThrow();
        assertNotSame(before, after);
        assertTrue(Disposer.isDisposed(before));
        assertEquals(pid, execution.handler().getProcess().pid());
        assertTrue(registry.isRunning(id));
    }

    public void testAConsoleMovedAwayFromIsNotKeptByTheProcess() throws Exception {
        AgentExecution execution = start(STREAMING);
        TerminalExecutionConsole before = execution.console().orElseThrow();

        registry.moveTo(id, getProject());

        // The process lives on; a console it no longer feeds must not live
        // on with it, holding its screen buffer, for every move.
        LeakHunter.checkLeak(execution.handler(), TerminalExecutionConsole.class, console -> console == before);
    }

    public void testAReleasedAgentKeepsRunningAndIsNotShown() throws Exception {
        start(STREAMING);
        Thread.sleep(300);

        registry.release(id);

        assertTrue(registry.host(id).isEmpty());
        assertTrue(registry.isRunning(id));
        assertTrue(registry.shownIn(getProject()).isEmpty());
        AgentExecution shown = registry.show(id, getProject()).orElseThrow();
        waitFor(() -> text(shown.console().orElseThrow()).contains("LINE 000001"));
    }

    public void testStoppingANotShownAgentStillReportsTheStop() throws Exception {
        start(STREAMING);
        registry.release(id);

        registry.stop(id);

        waitFor(() -> !ended.isEmpty());
        Thread.sleep(200);
        assertEquals(1, ended.size());
        assertTrue(ended.get(0).stopRequested());
    }

    public void testReplacementStopKillsTheAgentChildBeforeTheWrapperReportsDone() throws Exception {
        Path pidFile = Files.createTempFile("drydock-agent-child", ".pid");
        long childPid = -1;
        try {
            start(List.of("/bin/sh", "-c",
                    "sleep 30 & child=$!; printf '%s' \"$child\" > \"$1\"; wait",
                    "drydock-test", pidFile.toString()));
            waitFor(() -> {
                try {
                    return Files.size(pidFile) > 0;
                } catch (java.io.IOException e) {
                    return false;
                }
            });
            childPid = Long.parseLong(Files.readString(pidFile));

            registry.stopProcessTree(id);

            long capturedChildPid = childPid;
            waitFor(() -> !registry.isRunning(id)
                    && ProcessHandle.of(capturedChildPid).map(handle -> !handle.isAlive()).orElse(true));
        } finally {
            if (childPid > 0) {
                ProcessHandle.of(childPid).ifPresent(ProcessHandle::destroyForcibly);
            }
            Files.deleteIfExists(pidFile);
        }
    }

    public void testShowingAFinishedAgentAttachesNoInput() throws Exception {
        AgentExecution execution = start(List.of("/bin/sh", "-c", "printf done"));
        waitFor(() -> !registry.isRunning(id));

        registry.moveTo(id, getProject());

        // Its transcript is still there to read, dimmed by the tab.
        assertFalse(registry.isRunning(id));
        TerminalExecutionConsole console = execution.console().orElseThrow();
        waitFor(() -> text(console).contains("done"));
    }

    public void testAColourAnAgentSetsSurvivesAChunkBoundary() throws Exception {
        // Two separate writes: red is switched on in the first, the text it
        // colours arrives in the second. The console must not reset or
        // recolour output between chunks.
        AgentExecution execution = start(List.of("/bin/sh", "-c",
                "printf 'ab\\033[31mc'; sleep 0.5; printf 'de\\033[0m\\n'; sleep 30"));
        TerminalExecutionConsole console = execution.console().orElseThrow();
        waitFor(() -> text(console).contains("abcde"));

        TerminalLine line = lineContaining(console, "abcde");
        int at = line.getText().indexOf("abcde");
        assertEquals(line.getStyleAt(at + 2).getForeground(), line.getStyleAt(at + 3).getForeground());
        assertFalse(line.getStyleAt(at).getForeground() != null
                && line.getStyleAt(at).getForeground().equals(line.getStyleAt(at + 2).getForeground()));
    }

    public void testBareCarriageReturnRemainsATerminalCursorControl() throws Exception {
        AgentExecution execution = start(List.of("/bin/sh", "-c", "printf 'ABCDE\\rZ'; sleep 30"));
        TerminalExecutionConsole console = execution.console().orElseThrow();

        waitFor(() -> text(console).contains("ZBCDE"));
        assertFalse(text(console), text(console).contains("ABCDE\nZ"));
    }

    private static TerminalLine lineContaining(TerminalExecutionConsole console, String text) {
        TerminalTextBuffer buffer = console.getTerminalWidget().getTerminalTextBuffer();
        for (int row = 0; row < buffer.getHeight(); row++) {
            TerminalLine line = buffer.getLine(row);
            if (line.getText().contains(text)) {
                return line;
            }
        }
        throw new AssertionError("no line contains " + text);
    }

    public void testAnAgentThatEndedWhileNotShownCannotBeShown() throws Exception {
        start(List.of("/bin/sh", "-c", "sleep 0.3"));
        registry.release(id);
        waitFor(() -> !ended.isEmpty());

        assertTrue(registry.show(id, getProject()).isEmpty());
        assertTrue(registry.execution(id).isEmpty());
    }

    public void testForgettingANotShownAgentLeavesNothing() throws Exception {
        start(STREAMING);
        registry.release(id);

        registry.forget(id);

        waitFor(() -> registry.execution(id).isEmpty());
        waitFor(() -> !ended.isEmpty());
    }

    public void testForgettingFromABackgroundThreadClearsTheViewOnTheEdt() throws Exception {
        AgentExecution execution = start(STREAMING);
        TerminalExecutionConsole console = execution.console().orElseThrow();

        // Done runs this from the session service's executor.
        Thread forgetter = new Thread(() -> registry.forget(id));
        forgetter.start();
        forgetter.join();

        // The console and host are EDT state: untouched until the EDT gets to it.
        assertSame(console, execution.console().orElse(null));
        waitFor(() -> execution.console().isEmpty() && execution.host().isEmpty());
        assertTrue(Disposer.isDisposed(console));
    }

    public void testANaturalExitIsReportedOnceAsNotRequested() throws Exception {
        start(List.of("/bin/sh", "-c", "exit 3"));

        waitFor(() -> !ended.isEmpty());
        Thread.sleep(200);
        assertEquals(List.of(new Ended(id, false, 3)), ended);
    }

    public void testTheWindowServiceAnswersOnlyForWhatItShows() throws Exception {
        start(STREAMING);
        AgentExecutionService window = AgentExecutionService.getInstance(getProject());
        assertTrue(window.isRunning(id));

        registry.release(id);

        assertFalse(window.isRunning(id));
        assertTrue(registry.isRunning(id));
    }
}
