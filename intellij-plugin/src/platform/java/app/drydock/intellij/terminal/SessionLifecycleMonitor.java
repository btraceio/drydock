package app.drydock.intellij.terminal;

import app.drydock.activity.SessionActivityWatcher;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.SessionActivity;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab;
import com.intellij.terminal.frontend.view.TerminalViewSessionState;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentManager;
import com.intellij.ui.content.ContentManagerEvent;
import com.intellij.ui.content.ContentManagerListener;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Watches the project's Drydock terminal tabs and turns their raw state
 * into {@link SessionEventSink} events: natural exit (Terminated flow state,
 * AC13), tab close (content removed from the Terminal tool window, AC13),
 * activity (one {@link SessionActivityWatcher} poll joined per session,
 * AC16/AC17) and tab focus (watcher acknowledge, AC18).
 *
 * <p>Polling shape (AC19): one linear chain on the injected executor — the
 * next tick is scheduled only in the completion callback of the previous one,
 * so at most one tick (and therefore at most one watcher poll) is ever in
 * flight, however slow the filesystem is; ticks never queue up behind a
 * stalled poll. {@link #dispose()} breaks the chain and detaches the content
 * listener, so no task keeps ticking after the project closes.</p>
 *
 * <p>The monitor never terminates processes itself: closing a tab kills the
 * shell because that is the IDE terminal's own tab-close behavior.</p>
 *
 * <p>The per-tick decision table lives in the package-private static
 * {@link #runTick}, IDE-free, so tests drive it with fake inputs; the IDE
 * half of this class only feeds it real tab states and forwards listener
 * callbacks to {@link #tabClosed}/{@link #tabFocused}.</p>
 */
public final class SessionLifecycleMonitor implements Disposable {

    private static final Logger LOG = System.getLogger(SessionLifecycleMonitor.class.getName());

    /** Sessions live as tabs of the standard Terminal tool window (plan Q7). */
    private static final String TERMINAL_TOOL_WINDOW_ID = "Terminal";

    static final long DEFAULT_PERIOD_MILLIS = 1000;

    private final Project project;
    private final SessionTabRegistry registry;
    private final SessionActivityWatcher watcher;
    private final ExecutorService executor;
    private final Function<ManagedSessionId, Optional<String>> agentSessionIds;
    private final long periodMillis;
    private final Function<Long, CompletableFuture<Void>> delay;

    private final List<SessionEventSink> sinks = new CopyOnWriteArrayList<>();

    /** The state machine's memory; package-private so tests drive it through the static {@link #runTick}. */
    final TickState state = new TickState();

    private volatile boolean started;
    private volatile boolean disposed;
    private volatile ContentManager contentManager;

    /**
     * Constructs the monitor. {@code agentSessionIds} resolves a managed
     * session to the agent session id persisted for it (live — DISCOVERED ids
     * appear only after launch) and is the seam the monitor cannot own: the
     * registry's tab handles carry only the {@link ManagedSessionId}, while
     * the agent session id lives in the persisted session record.
     */
    public SessionLifecycleMonitor(
            Project project,
            SessionTabRegistry registry,
            SessionActivityWatcher watcher,
            ExecutorService executor,
            Function<ManagedSessionId, Optional<String>> agentSessionIds) {
        this(project, registry, watcher, executor, agentSessionIds, DEFAULT_PERIOD_MILLIS,
                millis -> delayedWake(millis, executor));
    }

    /** Full-seam constructor for tests: injectable period and delay. */
    SessionLifecycleMonitor(
            Project project,
            SessionTabRegistry registry,
            SessionActivityWatcher watcher,
            ExecutorService executor,
            Function<ManagedSessionId, Optional<String>> agentSessionIds,
            long periodMillis,
            Function<Long, CompletableFuture<Void>> delay) {
        this.project = project;
        this.registry = Objects.requireNonNull(registry, "registry");
        this.watcher = Objects.requireNonNull(watcher, "watcher");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.agentSessionIds = Objects.requireNonNull(agentSessionIds, "agentSessionIds");
        this.periodMillis = periodMillis;
        this.delay = Objects.requireNonNull(delay, "delay");
    }

    /**
     * Subscribes an event consumer; the session service (T10) and the tool
     * window model (T12) subscribe separately.
     */
    public void addSink(SessionEventSink sink) {
        sinks.add(Objects.requireNonNull(sink, "sink"));
    }

    /** Starts the single coalesced polling loop. Idempotent. */
    public synchronized void start() {
        if (started || disposed) {
            return;
        }
        started = true;
        attachContentListener();
        scheduleTick();
    }

    @Override
    public void dispose() {
        disposed = true;
        ContentManager attached = contentManager;
        contentManager = null;
        if (attached != null) {
            attached.removeContentManagerListener(contentListener);
        }
        sinks.clear();
    }

    // ---- polling loop --------------------------------------------------------

    private void scheduleTick() {
        if (disposed || !started) {
            return;
        }
        // Strictly linear chain: nothing else schedules ticks, and this call
        // happens only in the previous tick's completion callback, so at most
        // one tick is in flight at any time (AC19).
        delay.apply(periodMillis)
                .thenComposeAsync(ignored -> tickAsync(), executor)
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        LOG.log(Level.WARNING, "Session lifecycle tick failed: " + failure);
                    }
                    scheduleTick();
                });
    }

    private CompletableFuture<Void> tickAsync() {
        if (disposed) {
            return CompletableFuture.completedFuture(null);
        }
        // The Terminal tool window (hence its content manager) appears when
        // the first tab opens, not at project start; retrying from each tick
        // is cheap (one guard read) and attaches the listener eventually.
        attachContentListener();
        List<TerminalSessionLauncher.TerminalSessionTab> tabs = registry.tabs();
        List<TabInput> inputs = new ArrayList<>(tabs.size());
        for (TerminalSessionLauncher.TerminalSessionTab tab : tabs) {
            inputs.add(new TabInput(tab.sessionId(), tab.tabKey(), tabTerminated(tab),
                    safeAgentSessionId(tab.sessionId())));
        }
        // Exactly one watcher poll per tick (AC19 coalescing); the result map
        // is joined per session inside runTick.
        return watcher.poll().thenAcceptAsync(
                activities -> runTick(state, inputs, activities, dispatch), executor);
    }

    private static boolean tabTerminated(TerminalSessionLauncher.TerminalSessionTab tab) {
        try {
            Object flowState = tab.tab().getView().getSessionState().getValue();
            return flowState instanceof TerminalViewSessionState.Terminated;
        } catch (RuntimeException e) {
            // A tab mid-disposal can fail this read; unreadable must mean
            // "not terminated" — an exit is never fabricated from a failure.
            LOG.log(Level.WARNING, "Could not read terminal session state of " + tab.sessionId() + ": " + e);
            return false;
        }
    }

    private Optional<String> safeAgentSessionId(ManagedSessionId sessionId) {
        try {
            return Objects.requireNonNull(agentSessionIds.apply(sessionId), "agentSessionId");
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not resolve agent session id of " + sessionId + ": " + e);
            return Optional.empty();
        }
    }

    private static CompletableFuture<Void> delayedWake(long millis, ExecutorService executor) {
        // delayedExecutor supplies the delay on its own shared timer thread;
        // the wake-up itself runs on the owning executor, so no tick work ever
        // happens on a thread the caller does not own.
        CompletableFuture<Void> wake = new CompletableFuture<>();
        CompletableFuture.delayedExecutor(millis, TimeUnit.MILLISECONDS, executor)
                .execute(() -> wake.complete(null));
        return wake;
    }

    // ---- pure per-tick state machine -----------------------------------------

    /** Inputs of one tick, IDE-free so the static {@link #runTick} is testable. */
    static final class TabInput {
        final ManagedSessionId sessionId;
        final Object tabKey;
        final boolean terminated;
        final Optional<String> agentSessionId;

        TabInput(ManagedSessionId sessionId, Object tabKey, boolean terminated, Optional<String> agentSessionId) {
            this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
            this.tabKey = Objects.requireNonNull(tabKey, "tabKey");
            this.terminated = terminated;
            this.agentSessionId = Objects.requireNonNull(agentSessionId, "agentSessionId");
        }
    }

    /**
     * What the state machine remembers between ticks. Both collections are
     * concurrent: {@link #runTick} runs on the polling executor while the
     * content listener mutates {@link #exitAnnounced} on the EDT.
     */
    static final class TickState {
        /** Tab keys whose exit was already announced (idempotence, one per tab). */
        final Set<Object> exitAnnounced = ConcurrentHashMap.newKeySet();

        /** Last activity emitted per session; unchanged values are not re-emitted. */
        final Map<ManagedSessionId, Optional<SessionActivity>> lastActivity = new ConcurrentHashMap<>();
    }

    /**
     * One tick's decision table: previous state ({@link TickState}) x new
     * inputs (tab terminations, the poll's activity map) -> events.
     *
     * <ul>
     *   <li>Terminated fires {@code sessionExited(id, false)} exactly once per
     *       tab ({@code exitAnnounced} guards the second and every later
     *       tick; AC13 idempotence).</li>
     *   <li>Activity is emitted per session, only when it changed. A session
     *       whose agent session id is absent (or has no state file) emits an
     *       empty optional — never a fabricated activity (AC17).</li>
     * </ul>
     */
    static void runTick(TickState state, List<TabInput> tabs, Map<String, SessionActivity> activities,
                       SessionEventSink sink) {
        for (TabInput tab : tabs) {
            if (tab.terminated && state.exitAnnounced.add(tab.tabKey)) {
                sink.sessionExited(tab.sessionId, false);
            }
        }
        Map<ManagedSessionId, Optional<SessionActivity>> current = new LinkedHashMap<>();
        for (TabInput tab : tabs) {
            // All tabs of one session resolve the same id; the first one wins.
            current.computeIfAbsent(tab.sessionId, ignored -> tab.agentSessionId.map(activities::get));
        }
        // Sessions with no live tab anymore (closed since the last tick) drop
        // out of the join, so the memory stays bounded by live sessions.
        state.lastActivity.keySet().retainAll(current.keySet());
        for (Map.Entry<ManagedSessionId, Optional<SessionActivity>> entry : current.entrySet()) {
            if (!Objects.equals(state.lastActivity.get(entry.getKey()), entry.getValue())) {
                state.lastActivity.put(entry.getKey(), entry.getValue());
                sink.sessionActivityChanged(entry.getKey(), entry.getValue());
            }
        }
    }

    // ---- tab close / focus ---------------------------------------------------

    /**
     * A registered tab's content disappeared from the Terminal tool window:
     * the tab is dead, the exit fires with the closed variant — unless the
     * natural exit was already announced for this tab, in which case its
     * EXITED record must not be rewritten to INACTIVE. Package-private so
     * tests drive it without the IDE listener.
     */
    void tabClosed(TerminalSessionLauncher.TerminalSessionTab handle) {
        if (disposed) {
            return;
        }
        registry.remove(handle.tabKey());
        if (state.exitAnnounced.add(handle.tabKey())) {
            dispatch.sessionExited(handle.sessionId(), true);
        }
    }

    /**
     * A registered tab became the selected terminal content: the user is
     * looking at it, so the session's agent state is acknowledged (AC18).
     * Package-private for the same reason as {@link #tabClosed}.
     */
    void tabFocused(TerminalSessionLauncher.TerminalSessionTab handle) {
        if (disposed) {
            return;
        }
        dispatch.tabFocused(handle.sessionId());
        // Only a session whose agent reported an id can be acknowledged; an
        // absent id is skipped, never guessed.
        safeAgentSessionId(handle.sessionId()).ifPresent(watcher::acknowledge);
    }

    private final ContentManagerListener contentListener = new ContentManagerListener() {
        @Override
        public void contentRemoved(ContentManagerEvent event) {
            TerminalSessionLauncher.TerminalSessionTab handle = handleForContent(event.getContent());
            if (handle != null) {
                tabClosed(handle);
            }
        }

        @Override
        public void selectionChanged(ContentManagerEvent event) {
            TerminalSessionLauncher.TerminalSessionTab handle = handleForContent(event.getContent());
            if (handle != null) {
                tabFocused(handle);
            }
        }
    };

    /** Maps a content back to its registered tab, or null for non-Drydock tabs. */
    private TerminalSessionLauncher.TerminalSessionTab handleForContent(Content content) {
        for (TerminalSessionLauncher.TerminalSessionTab tab : registry.tabs()) {
            TerminalToolWindowTab ideTab = tab.tab();
            if (ideTab != null && ideTab.getContent() == content) {
                return tab;
            }
        }
        return null;
    }

    private void attachContentListener() {
        if (contentManager != null || project == null || disposed) {
            return;
        }
        try {
            ToolWindow terminal = ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOL_WINDOW_ID);
            if (terminal == null) {
                return;
            }
            // getContentManager() would initialize the tool window (EDT-only
            // work this poll thread must not trigger); ifCreated only reads
            // the initialized value and stays null until the first tab opens.
            ContentManager manager = terminal.getContentManagerIfCreated();
            if (manager == null) {
                return;
            }
            manager.addContentManagerListener(contentListener);
            contentManager = manager;
        } catch (RuntimeException e) {
            LOG.log(Level.DEBUG, "Terminal tool window not attachable yet: " + e);
        }
    }

    /** Fan-out sink: every subscriber hears every event, one failing subscriber cannot kill the loop. */
    private final SessionEventSink dispatch = new SessionEventSink() {
        @Override
        public void sessionExited(ManagedSessionId id, boolean tabClosed) {
            for (SessionEventSink sink : sinks) {
                try {
                    sink.sessionExited(id, tabClosed);
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "Session event sink failed on sessionExited: " + e);
                }
            }
        }

        @Override
        public void sessionActivityChanged(ManagedSessionId id, Optional<SessionActivity> activity) {
            for (SessionEventSink sink : sinks) {
                try {
                    sink.sessionActivityChanged(id, activity);
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "Session event sink failed on sessionActivityChanged: " + e);
                }
            }
        }

        @Override
        public void tabFocused(ManagedSessionId id) {
            for (SessionEventSink sink : sinks) {
                try {
                    sink.tabFocused(id);
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "Session event sink failed on tabFocused: " + e);
                }
            }
        }
    };
}
