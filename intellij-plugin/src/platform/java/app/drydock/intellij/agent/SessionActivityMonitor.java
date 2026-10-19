package app.drydock.intellij.agent;

import app.drydock.activity.SessionActivityWatcher;
import app.drydock.app.ApplicationStateStore;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.SessionActivity;
import app.drydock.domain.SessionStatus;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Turns the activity watcher's state files into
 * {@link SessionActivityListener} events, for the whole application.
 *
 * <p>Polling shape, carried over from the v2 lifecycle monitor because it is
 * the part of it that was right: one strictly linear chain, where the next
 * tick is scheduled only from the previous tick's completion. At most one
 * watcher poll is ever in flight, however slow the filesystem is, and ticks
 * never queue up behind a stalled one.</p>
 *
 * <p>Activity is reported only for sessions in the running band. Activity is a
 * property of a live process, so a leftover state file must never label an
 * exited session "working" — the same rule {@code SessionDots} enforces on the
 * display side, applied here so the event never exists in the first place.</p>
 *
 * <p>An unchanged activity is not re-emitted: subscribers repaint on every
 * event, and a once-a-second repaint of every strip and widget in every window
 * is exactly the kind of idle cost that made the app's pulse driver burn 26 h
 * of CPU per 70 h of uptime.</p>
 */
public final class SessionActivityMonitor implements Disposable {

    private static final Logger LOG = System.getLogger(SessionActivityMonitor.class.getName());

    static final long DEFAULT_PERIOD_MILLIS = 1_000;

    private final Supplier<ApplicationStateStore> stateStore;
    private final Supplier<SessionActivityWatcher> watcher;
    private final ExecutorService executor;
    private final long periodMillis;
    private final Function<Long, CompletableFuture<Void>> delay;

    /** Last activity emitted per session; an unchanged value is not re-emitted. */
    private final Map<ManagedSessionId, Optional<SessionActivity>> lastActivity = new ConcurrentHashMap<>();

    private volatile boolean started;
    private volatile boolean disposed;

    public SessionActivityMonitor(Supplier<ApplicationStateStore> stateStore,
                                  Supplier<SessionActivityWatcher> watcher, ExecutorService executor) {
        this(stateStore, watcher, executor, DEFAULT_PERIOD_MILLIS, millis -> delayedWake(millis, executor));
    }

    /** Full-seam constructor for tests: injectable period and delay. */
    SessionActivityMonitor(Supplier<ApplicationStateStore> stateStore, Supplier<SessionActivityWatcher> watcher,
                           ExecutorService executor, long periodMillis,
                           Function<Long, CompletableFuture<Void>> delay) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.watcher = Objects.requireNonNull(watcher, "watcher");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.periodMillis = periodMillis;
        this.delay = Objects.requireNonNull(delay, "delay");
    }

    /**
     * The activity last emitted for {@code sessionId}, for a surface that has
     * just appeared and would otherwise show nothing until the next change.
     * Empty both when the session has no activity and when none has been
     * observed yet — the two are indistinguishable here, and both correctly
     * mean "do not claim one".
     */
    public Optional<SessionActivity> current(ManagedSessionId sessionId) {
        return lastActivity.getOrDefault(sessionId, Optional.empty());
    }

    /**
     * The user has seen {@code sessionId} while it was waiting for them: its
     * NEEDS_ATTENTION reads as IDLE from now on, until the agent's state
     * changes and it can ask again.
     *
     * <p>Without this nothing ever cleared the state. Claude's hook reports
     * "needs you" both for a permission prompt and for its idle "waiting for
     * your input" reminder, so in {@code runIde} a Claude session turned
     * amber a minute after finishing a turn and stayed amber while the user
     * was looking straight at it — and the close confirmation then asked
     * before stopping an agent that was only waiting.</p>
     *
     * <p>Republished at once rather than on the next tick, so the dot and
     * the strip change as the user looks, not up to a second later. A
     * session not currently waiting is left alone.</p>
     */
    public void acknowledge(ManagedSessionId sessionId) {
        if (!current(sessionId).equals(Optional.of(SessionActivity.NEEDS_ATTENTION))) {
            return;
        }
        Optional<String> agentSessionId = stateStore.get().state().sessions().stream()
                .filter(session -> session.id().equals(sessionId))
                .findFirst()
                .flatMap(ManagedAgentSession::agentSessionId);
        if (agentSessionId.isEmpty()) {
            return;
        }
        watcher.get().acknowledge(agentSessionId.get());
        Optional<SessionActivity> seen = Optional.of(SessionActivity.IDLE);
        lastActivity.put(sessionId, seen);
        publish(sessionId, seen);
    }

    /** Starts the single coalesced polling loop. Idempotent. */
    public synchronized void start() {
        if (started || disposed) {
            return;
        }
        started = true;
        scheduleTick();
    }

    @Override
    public void dispose() {
        disposed = true;
        lastActivity.clear();
    }

    private void scheduleTick() {
        if (disposed || !started) {
            return;
        }
        try {
            delay.apply(periodMillis)
                    .thenComposeAsync(ignored -> tickAsync(), executor)
                    .whenComplete((ignored, failure) -> {
                        if (failure != null) {
                            LOG.log(Level.WARNING, "Session activity tick failed: " + failure);
                        }
                        scheduleTick();
                    });
        } catch (RejectedExecutionException e) {
            // Shutdown drained the executor; the loop ends here by design.
            LOG.log(Level.DEBUG, "Session activity polling stopped during shutdown");
        }
    }

    private CompletableFuture<Void> tickAsync() {
        if (disposed) {
            return CompletableFuture.completedFuture(null);
        }
        Map<ManagedSessionId, Optional<String>> running = runningAgentSessionIds();
        if (running.isEmpty()) {
            // Nothing live to report on. The remembered values still have to
            // go, or a session that comes back would not re-emit its first
            // activity (the value would compare equal to the stale one).
            lastActivity.keySet().retainAll(running.keySet());
            return CompletableFuture.completedFuture(null);
        }
        // Exactly one watcher poll per tick, joined per session below.
        return watcher.get().poll().thenAcceptAsync(activities -> emitChanges(running, activities), executor);
    }

    /** The running-band sessions and the agent session id each one's activity is filed under. */
    private Map<ManagedSessionId, Optional<String>> runningAgentSessionIds() {
        Map<ManagedSessionId, Optional<String>> running = new LinkedHashMap<>();
        for (ManagedAgentSession session : stateStore.get().state().sessions()) {
            SessionStatus status = session.status();
            if (status == SessionStatus.RUNNING || status == SessionStatus.STARTING) {
                running.put(session.id(), session.agentSessionId());
            }
        }
        return running;
    }

    private void emitChanges(Map<ManagedSessionId, Optional<String>> running,
                             Map<String, SessionActivity> activities) {
        lastActivity.keySet().retainAll(running.keySet());
        for (Map.Entry<ManagedSessionId, Optional<String>> entry : running.entrySet()) {
            Optional<SessionActivity> activity = entry.getValue().map(activities::get);
            if (Objects.equals(lastActivity.get(entry.getKey()), activity)) {
                continue;
            }
            lastActivity.put(entry.getKey(), activity);
            publish(entry.getKey(), activity);
        }
    }

    private static void publish(ManagedSessionId sessionId, Optional<SessionActivity> activity) {
        Application application = ApplicationManager.getApplication();
        if (application == null || application.isDisposed()) {
            return;
        }
        try {
            application.getMessageBus().syncPublisher(SessionActivityListener.TOPIC)
                    .activityChanged(sessionId, activity);
        } catch (RuntimeException e) {
            // One broken subscriber must not stop the polling loop for every
            // other surface.
            LOG.log(Level.WARNING, "A Drydock activity listener failed: " + e);
        }
    }

    private static CompletableFuture<Void> delayedWake(long millis, ExecutorService executor) {
        // delayedExecutor supplies the delay on its own shared timer thread;
        // the wake-up itself runs on the owning executor, so no tick work ever
        // happens on a thread this class does not own.
        CompletableFuture<Void> wake = new CompletableFuture<>();
        CompletableFuture.delayedExecutor(millis, TimeUnit.MILLISECONDS, executor)
                .execute(() -> wake.complete(null));
        return wake;
    }
}
