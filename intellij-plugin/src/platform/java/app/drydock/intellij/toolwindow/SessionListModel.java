package app.drydock.intellij.toolwindow;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionActivity;
import app.drydock.domain.SessionStatusFacet;
import app.drydock.intellij.terminal.SessionEventSink;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * The session list's data model (task T12): this repository's persisted
 * sessions, each joined with its live activity, newest-opened first. Pure —
 * no IntelliJ (and no Swing) type appears here, so it is unit-testable
 * without an IDE; the Swing/IntelliJ half lives in {@link SessionsPanel}.
 *
 * <p>Inputs are plain data: a supplier of the persisted sessions (all of
 * them; the repository filter below is the {@code sessionsFor} predicate)
 * and the last activity state keyed by agent session id — the shape of
 * {@code SessionActivityWatcher.poll()}. The join happens on the session's
 * own {@code agentSessionId}: a session with no id (Pi before discovery,
 * TERMINAL) or with no state file (any provider without an ActivityReporter)
 * is lifecycle-only, never a fabricated BUSY/IDLE (AC17). The lifecycle
 * half comes from the persisted {@link SessionStatus}.</p>
 *
 * <p>Events ({@link SessionEventSink}, delivered by the lifecycle monitor)
 * update the model; N events coalesce into one rebuild via a single
 * pending flag — the rebuild itself reads the persisted state snapshot, so
 * exit/close persistence and list display converge in one pass (AGENTS.md:
 * coalesce N async completions into one rebuild, not one rebuild per
 * completion). The rebuild runs on the injected executor; the panel injects
 * the EDT, which is also the thread listeners fire on.</p>
 *
 * <p>Thread-safety: sink callbacks may arrive on the monitor's polling
 * executor or on the EDT (the monitor's content-listener variant); all
 * mutable state is concurrent, and listeners fire only from
 * {@link #refreshNow()} on whatever thread runs the coalesced refresh.</p>
 */
public final class SessionListModel implements SessionEventSink {

    /** One row: the session plus its joined activity (empty = lifecycle-only). */
    public record Row(ManagedAgentSession session, Optional<SessionActivity> activity) {
    }

    /** Notified on the thread the coalesced refresh runs on (the EDT in production). */
    public interface Listener {
        void rowsChanged();
    }

    /**
     * Newest-opened first; ties break on creation time and then on id, so
     * the order is total and stable across rebuilds of identical data.
     */
    private static final Comparator<ManagedAgentSession> NEWEST_FIRST = (a, b) -> {
        int byLastOpened = b.lastOpenedAt().compareTo(a.lastOpenedAt());
        if (byLastOpened != 0) {
            return byLastOpened;
        }
        int byCreated = b.createdAt().compareTo(a.createdAt());
        if (byCreated != 0) {
            return byCreated;
        }
        return b.id().value().compareTo(a.id().value());
    };

    private final RepositoryId repositoryId;
    private final Supplier<List<ManagedAgentSession>> sessions;
    private final Executor refreshExecutor;

    /** The last activity state per agent session id, as the watcher's poll reported it. */
    private final Map<String, SessionActivity> lastPoll = new ConcurrentHashMap<>();

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    /** Set while a rebuild is queued: the N-events-one-rebuild coalescing gate. */
    private final AtomicBoolean refreshPending = new AtomicBoolean();

    private volatile List<Row> rows = List.of();

    public SessionListModel(RepositoryId repositoryId, Supplier<List<ManagedAgentSession>> sessions,
            Executor refreshExecutor) {
        this.repositoryId = Objects.requireNonNull(repositoryId, "repositoryId");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.refreshExecutor = Objects.requireNonNull(refreshExecutor, "refreshExecutor");
    }

    /** The current rows; an immutable snapshot. */
    public List<Row> rows() {
        return rows;
    }

    public void addListener(Listener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeListener(Listener listener) {
        listeners.remove(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Schedules one coalesced rebuild: the first request after a quiet
     * period queues exactly one refresh; every request that arrives before
     * it runs rides along with it.
     */
    public void requestRefresh() {
        if (refreshPending.compareAndSet(false, true)) {
            refreshExecutor.execute(this::runCoalescedRefresh);
        }
    }

    private void runCoalescedRefresh() {
        // Cleared before (not after) the rebuild: an event arriving during
        // the rebuild must queue a fresh refresh, not be swallowed by a flag
        // that is still held.
        refreshPending.set(false);
        refreshNow();
    }

    /**
     * Recomputes the rows from the current inputs and fires the listeners
     * when (and only when) they changed. Runs on the calling thread — the
     * panel only calls this on the EDT.
     */
    public void refreshNow() {
        List<Row> updated = join(sessions.get(), repositoryId, lastPoll);
        if (!updated.equals(rows)) {
            rows = updated;
            for (Listener listener : listeners) {
                listener.rowsChanged();
            }
        }
    }

    // ---- the pure join ---------------------------------------------------------

    /**
     * The rows for one repository: the sessions belonging to {@code
     * repositoryId} (the {@code ProjectRepositoryService.sessionsFor}
     * predicate — a worktree's session stays out of the main checkout's
     * list, AC25), each joined with its activity, newest-opened first.
     */
    static List<Row> join(List<ManagedAgentSession> sessions, RepositoryId repositoryId,
            Map<String, SessionActivity> activityByAgentSessionId) {
        List<ManagedAgentSession> mine = new ArrayList<>();
        for (ManagedAgentSession session : sessions) {
            if (session.repositoryId().equals(repositoryId)) {
                mine.add(session);
            }
        }
        mine.sort(NEWEST_FIRST);
        List<Row> joined = new ArrayList<>(mine.size());
        for (ManagedAgentSession session : mine) {
            joined.add(new Row(session, joinedActivity(session, activityByAgentSessionId)));
        }
        return List.copyOf(joined);
    }

    /**
     * The activity join. Empty (lifecycle-only) when the session has no
     * agent session id or the poll reported no state file for it (AC17), or
     * when the session is outside the running band — activity is a property
     * of a live process, so a state file left behind by a killed agent must
     * not label an inactive row "busy".
     */
    private static Optional<SessionActivity> joinedActivity(ManagedAgentSession session,
            Map<String, SessionActivity> activityByAgentSessionId) {
        if (SessionStatusFacet.of(session.status()) != SessionStatusFacet.RUNNING) {
            return Optional.empty();
        }
        // Optional.map yields empty when the poll has no entry for the id,
        // which is exactly the lifecycle-only case.
        return session.agentSessionId().map(activityByAgentSessionId::get);
    }

    // ---- SessionEventSink ------------------------------------------------------

    /**
     * A session's terminal tab went away; the persistence half (the session
     * service) recorded the terminal status synchronously before this sink
     * runs, so one rebuild picks the new status up.
     */
    @Override
    public void sessionExited(ManagedSessionId id, boolean tabClosed) {
        requestRefresh();
    }

    /**
     * The session's live activity changed. The event carries the joined
     * result per managed session; it is re-keyed onto the agent session id
     * here so the rebuild joins through the same map the watcher's poll
     * produces. An empty optional drops the entry again (the session
     * reported nothing — lifecycle-only).
     */
    @Override
    public void sessionActivityChanged(ManagedSessionId id, Optional<SessionActivity> activity) {
        ManagedAgentSession session = find(sessions.get(), id);
        if (session != null) {
            session.agentSessionId().ifPresent(agentSessionId -> {
                if (activity.isPresent()) {
                    lastPoll.put(agentSessionId, activity.get());
                } else {
                    lastPoll.remove(agentSessionId);
                }
            });
        }
        requestRefresh();
    }

    /** Focusing a tab has no display state; the watcher's acknowledge arrives as the next activity event. */
    @Override
    public void tabFocused(ManagedSessionId id) {
        // Deliberately empty (see javadoc).
    }

    private static ManagedAgentSession find(List<ManagedAgentSession> sessions, ManagedSessionId id) {
        for (ManagedAgentSession session : sessions) {
            if (session.id().equals(id)) {
                return session;
            }
        }
        return null;
    }
}
