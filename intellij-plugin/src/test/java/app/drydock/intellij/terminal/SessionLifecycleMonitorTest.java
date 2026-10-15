package app.drydock.intellij.terminal;

import app.drydock.activity.SessionActivityWatcher;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.SessionActivity;
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the lifecycle monitor's seams directly — the static {@code runTick}
 * state machine, the package-private tab-close/focus handlers, and the real
 * loop under manual executors — with no IDE instance. Everything is
 * deterministic: the executors only run what the test runs, so "one in-flight
 * poll" and "dispose stops the loop" are queue-shape assertions, never
 * sleeps.
 */
class SessionLifecycleMonitorTest {

    // ---- pure state machine --------------------------------------------------

    @Test
    void terminatedTransitionFiresExitExactlyOncePerTab() {
        SessionLifecycleMonitor.TickState state = new SessionLifecycleMonitor.TickState();
        ManagedSessionId id = ManagedSessionId.newId();
        RecordingSink sink = new RecordingSink();

        SessionLifecycleMonitor.TabInput running =
                new SessionLifecycleMonitor.TabInput(id, "tab-1", false, Optional.empty());
        SessionLifecycleMonitor.TabInput terminated =
                new SessionLifecycleMonitor.TabInput(id, "tab-1", true, Optional.empty());

        SessionLifecycleMonitor.runTick(state, List.of(running), Map.of(), sink);
        assertEquals(0, sink.exits.size(), "a running tab fires nothing");

        SessionLifecycleMonitor.runTick(state, List.of(terminated), Map.of(), sink);
        SessionLifecycleMonitor.runTick(state, List.of(terminated), Map.of(), sink);
        SessionLifecycleMonitor.runTick(state, List.of(terminated), Map.of(), sink);
        assertEquals(List.of(new ExitEvent(id, false)), sink.exits,
                "Terminated fires sessionExited(id, false) exactly once");
    }

    @Test
    void activityIsMappedByAgentSessionIdAndNeverFabricated() {
        SessionLifecycleMonitor.TickState state = new SessionLifecycleMonitor.TickState();
        ManagedSessionId claude = ManagedSessionId.newId();
        ManagedSessionId pi = ManagedSessionId.newId();
        RecordingSink sink = new RecordingSink();

        SessionLifecycleMonitor.TabInput claudeTab = new SessionLifecycleMonitor.TabInput(
                claude, "tab-a", false, Optional.of("claude-1"));
        SessionLifecycleMonitor.TabInput piTab = new SessionLifecycleMonitor.TabInput(
                pi, "tab-b", false, Optional.empty());
        Map<String, SessionActivity> busy = Map.of("claude-1", SessionActivity.BUSY);

        SessionLifecycleMonitor.runTick(state, List.of(claudeTab, piTab), busy, sink);
        assertEquals(Map.of(
                claude, Optional.of(SessionActivity.BUSY),
                pi, Optional.empty()), sink.latest(),
                "the reported id maps its activity; a session without an id gets an empty optional (AC17)");

        sink.activities.clear();
        SessionLifecycleMonitor.runTick(state, List.of(claudeTab, piTab), busy, sink);
        assertEquals(0, sink.activities.size(), "an unchanged activity is not re-emitted");

        sink.activities.clear();
        SessionLifecycleMonitor.runTick(state, List.of(claudeTab, piTab), Map.of(), sink);
        assertEquals(List.of(new ActivityEvent(claude, Optional.empty())), sink.activities,
                "a vanished state file maps back to empty activity, never a stale BUSY;"
                        + " the unchanged pi session is not re-emitted");
    }

    // ---- tab close ------------------------------------------------------------

    @Test
    void tabCloseFiresTheClosedVariantAndDropsTheTabFromTheRegistry() {
        SessionTabRegistry registry = new SessionTabRegistry();
        SessionActivityWatcher watcher = watcherOverEmptyDir();
        RecordingSink sink = new RecordingSink();
        SessionLifecycleMonitor monitor = newMonitor(registry, watcher, sink, id -> Optional.empty());
        ManagedSessionId id = ManagedSessionId.newId();
        FakeTab tab = new FakeTab(id, "tab-1");
        registry.register(tab);

        monitor.tabClosed(tab);

        assertEquals(List.of(new ExitEvent(id, true)), sink.exits,
                "closing the tab fires sessionExited(id, true) — the tabClosed variant");
        assertNull(registry.lookup("tab-1"), "the closed tab leaves the registry");
    }

    @Test
    void closingATabWhoseNaturalExitWasAnnouncedDoesNotRewriteIt() {
        SessionTabRegistry registry = new SessionTabRegistry();
        SessionActivityWatcher watcher = watcherOverEmptyDir();
        RecordingSink sink = new RecordingSink();
        SessionLifecycleMonitor monitor = newMonitor(registry, watcher, sink, id -> Optional.empty());
        ManagedSessionId id = ManagedSessionId.newId();
        FakeTab tab = new FakeTab(id, "tab-1");
        registry.register(tab);

        SessionLifecycleMonitor.runTick(monitor.state,
                List.of(new SessionLifecycleMonitor.TabInput(id, "tab-1", true, Optional.empty())),
                Map.of(), sink);
        monitor.tabClosed(tab);

        assertEquals(List.of(new ExitEvent(id, false)), sink.exits,
                "the natural EXITED announcement stands; the close of a dead tab adds no closed variant");
    }

    // ---- tab focus ------------------------------------------------------------

    @Test
    void tabFocusAcknowledgesTheWatchersActivityState(@TempDir Path activityDir) throws Exception {
        Files.writeString(activityDir.resolve("claude-1"), "attention", StandardCharsets.UTF_8);
        SessionActivityWatcher watcher = new SessionActivityWatcher(activityDir, new DirectExecutor());
        RecordingSink sink = new RecordingSink();
        SessionLifecycleMonitor monitor = newMonitor(new SessionTabRegistry(), watcher, sink,
                id -> Optional.of("claude-1"));
        ManagedSessionId id = ManagedSessionId.newId();
        FakeTab tab = new FakeTab(id, "tab-1");

        assertEquals(SessionActivity.NEEDS_ATTENTION, watcher.poll().join().get("claude-1"),
                "precondition: the state file reads NEEDS_ATTENTION");

        monitor.tabFocused(tab);

        assertEquals(List.of(id), sink.focuses, "tabFocused reaches every sink (AC18)");
        assertEquals(SessionActivity.IDLE, watcher.poll().join().get("claude-1"),
                "focusing the tab acknowledged the agent session id: attention now reads idle");
    }

    @Test
    void tabFocusSkipsTheWatcherWhenNoAgentSessionIdIsKnown(@TempDir Path activityDir) throws Exception {
        Files.writeString(activityDir.resolve("claude-1"), "attention", StandardCharsets.UTF_8);
        SessionActivityWatcher watcher = new SessionActivityWatcher(activityDir, new DirectExecutor());
        RecordingSink sink = new RecordingSink();
        SessionLifecycleMonitor monitor = newMonitor(new SessionTabRegistry(), watcher, sink,
                id -> Optional.empty());
        monitor.tabFocused(new FakeTab(ManagedSessionId.newId(), "tab-1"));
        assertEquals(SessionActivity.NEEDS_ATTENTION, watcher.poll().join().get("claude-1"),
                "no id was guessed, so nothing was acknowledged (AC17)");
    }

    // ---- the coalesced loop ---------------------------------------------------

    @Test
    void atMostOneTickIsInFlightWhileTheWatcherPollIsSlow() {
        SessionTabRegistry registry = new SessionTabRegistry();
        ManagedSessionId id = ManagedSessionId.newId();
        registry.register(new FakeTab(id, "tab-1"));
        ManualExecutor watcherExecutor = new ManualExecutor();
        ManualExecutor monitorExecutor = new ManualExecutor();
        SessionActivityWatcher watcher = new SessionActivityWatcher(Path.of("nonexistent"), watcherExecutor);
        RecordingSink sink = new RecordingSink();
        SessionLifecycleMonitor monitor = new SessionLifecycleMonitor(
                null, registry, watcher, monitorExecutor, session -> Optional.empty(),
                1, millis -> CompletableFuture.completedFuture(null));
        monitor.addSink(sink);

        monitor.start();
        assertEquals(1, monitorExecutor.size(), "the first tick is queued on the monitor executor");

        monitorExecutor.runOne(); // tick body: submits the watcher poll, waits for it
        assertEquals(1, watcherExecutor.submitted(), "exactly one poll was submitted for this tick");
        assertEquals(0, monitorExecutor.size(),
                "no second tick queued behind the still-pending poll (AC19: no per-tick queuing)");

        watcherExecutor.runOne(); // the poll completes...
        assertEquals(1, monitorExecutor.size(), "...which schedules exactly one continuation task");

        monitorExecutor.runOne(); // runTick runs, the tick completes, the chain reschedules
        assertEquals(1, monitorExecutor.size(), "the next tick is queued — one, not a burst");
        assertEquals(1, sink.activities.size(), "the first tick emitted the session's activity baseline");
        assertEquals(0, sink.exits.size(), "a tab whose state cannot be read never reports an exit");

        monitorExecutor.runOne(); // second tick body: a second (and only second) poll
        assertEquals(2, watcherExecutor.submitted(), "two completed ticks meant two polls, never more in flight");
    }

    @Test
    void disposeStopsTheLoopAfterTheInFlightTick() {
        SessionTabRegistry registry = new SessionTabRegistry();
        registry.register(new FakeTab(ManagedSessionId.newId(), "tab-1"));
        ManualExecutor watcherExecutor = new ManualExecutor();
        ManualExecutor monitorExecutor = new ManualExecutor();
        SessionActivityWatcher watcher = new SessionActivityWatcher(Path.of("nonexistent"), watcherExecutor);
        RecordingSink sink = new RecordingSink();
        SessionLifecycleMonitor monitor = new SessionLifecycleMonitor(
                null, registry, watcher, monitorExecutor, session -> Optional.empty(),
                1, millis -> CompletableFuture.completedFuture(null));
        monitor.addSink(sink);

        monitor.start();
        monitorExecutor.runOne(); // tick in flight, its poll gated in watcherExecutor

        monitor.dispose();
        watcherExecutor.runOne(); // the in-flight poll completes...
        monitorExecutor.runOne(); // ...its runTick continuation drains...
        assertEquals(0, monitorExecutor.size(), "no further tick is scheduled after dispose (no stranded ticking)");
        assertEquals(1, watcherExecutor.submitted(), "no second poll was ever submitted");
    }

    // ---- helpers --------------------------------------------------------------

    private SessionLifecycleMonitor newMonitor(SessionTabRegistry registry, SessionActivityWatcher watcher,
                                              RecordingSink sink,
                                              Function<ManagedSessionId, Optional<String>> agentSessionIds) {
        SessionLifecycleMonitor monitor = new SessionLifecycleMonitor(
                null, registry, watcher, new DirectExecutor(), agentSessionIds,
                1, millis -> CompletableFuture.completedFuture(null));
        monitor.addSink(sink);
        return monitor;
    }

    private static SessionActivityWatcher watcherOverEmptyDir() {
        return new SessionActivityWatcher(Path.of("nonexistent"), new DirectExecutor());
    }

    private record ExitEvent(ManagedSessionId id, boolean tabClosed) {
    }

    private record ActivityEvent(ManagedSessionId id, Optional<SessionActivity> activity) {
    }

    private static final class RecordingSink implements SessionEventSink {
        final List<ExitEvent> exits = new ArrayList<>();
        final List<ActivityEvent> activities = new ArrayList<>();
        final List<ManagedSessionId> focuses = new ArrayList<>();

        @Override
        public void sessionExited(ManagedSessionId id, boolean tabClosed) {
            exits.add(new ExitEvent(id, tabClosed));
        }

        @Override
        public void sessionActivityChanged(ManagedSessionId id, Optional<SessionActivity> activity) {
            activities.add(new ActivityEvent(id, activity));
        }

        @Override
        public void tabFocused(ManagedSessionId id) {
            focuses.add(id);
        }

        Map<ManagedSessionId, Optional<SessionActivity>> latest() {
            Map<ManagedSessionId, Optional<SessionActivity>> latest = new HashMap<>();
            for (ActivityEvent event : activities) {
                latest.put(event.id(), event.activity());
            }
            return latest;
        }
    }

    /**
     * A registered-tab stand-in: the seams under test never touch the IDE tab
     * ({@code tab()} may be null — the monitor guards every read of it), so
     * this is enough to drive close, focus and tick behavior.
     */
    private static final class FakeTab implements TerminalSessionLauncher.TerminalSessionTab {
        private final ManagedSessionId sessionId;
        private final Object tabKey;

        FakeTab(ManagedSessionId sessionId, Object tabKey) {
            this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
            this.tabKey = Objects.requireNonNull(tabKey, "tabKey");
        }

        @Override
        public ManagedSessionId sessionId() {
            return sessionId;
        }

        @Override
        public Object tabKey() {
            return tabKey;
        }

        @Override
        public TerminalToolWindowTab tab() {
            return null;
        }

        @Override
        public void close() {
            // not exercised by these seams
        }
    }

    /**
     * Runs nothing until the test says so: each {@link #runOne()} drains
     * exactly one task, which makes "how many tasks are queued right now"
     * a precise assertion about the loop's shape.
     */
    private static final class ManualExecutor extends AbstractExecutorService {
        private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        private int submitted;
        private boolean shutdown;

        @Override
        public void execute(Runnable command) {
            Objects.requireNonNull(command);
            queue.addLast(command);
            submitted++;
        }

        void runOne() {
            Runnable task = queue.pollFirst();
            assertNotNull(task, "expected a queued task");
            task.run();
        }

        int size() {
            return queue.size();
        }

        int submitted() {
            return submitted;
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.copyOf(queue);
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown && queue.isEmpty();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }
    }

    /** Executes everything on the calling thread, for immediately-completable futures. */
    private static final class DirectExecutor extends AbstractExecutorService {
        private boolean shutdown;

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }
    }
}
