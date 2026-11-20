package app.drydock.intellij.workspace;

import app.drydock.domain.ManagedSessionId;
import app.drydock.intellij.DrydockSessionsListener;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The application-level, in-memory liveness of <em>detached</em> remote
 * sessions: the {@code ManagedSessionId} → state map that says what the box
 * knows about a session this IDE is not currently attached to.
 *
 * <p>Deliberately memory-only (INV-4): a detached remote row's row status
 * stays IDLE — RUNNING means <em>this</em> IDE owns a live ssh execution — so
 * the ALIVE/DEAD answer lives only here, where it disappears with the IDE
 * and never reaches the state file or its schema. It is owned by {@code
 * DrydockPluginService}, holds no resources of its own (no executor, no
 * files), and therefore needs no close in the service's dispose path.</p>
 *
 * <p>Every change publishes {@link DrydockSessionsListener#publishChanged()}
 * so the session surfaces re-read the catalog and its liveness together. A
 * set that stores the state already recorded is not a change and publishes
 * nothing, so a revalidation pass over an unchanged box stays quiet.
 * Subscribers run on the caller's thread and hop to the EDT themselves, per
 * that topic's contract.</p>
 */
public final class RemoteSessionLiveness {

    /**
     * What the box says about one detached remote session. {@code ALIVE},
     * {@code DEAD} and {@code UNKNOWN} are probe verdicts; {@code
     * BOX_STOPPED} and {@code BOX_GONE} name the box itself as the reason a
     * session cannot be reached, set by the resume paths that classified the
     * box rather than by a per-session probe.
     */
    public enum State {
        ALIVE,
        DEAD,
        UNKNOWN,
        BOX_STOPPED,
        BOX_GONE
    }

    private final Map<ManagedSessionId, State> states = new ConcurrentHashMap<>();

    /** The session's recorded liveness, or empty when nothing has been probed yet. */
    public Optional<State> state(ManagedSessionId id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(states.get(id));
    }

    /**
     * Records the session's liveness and publishes a catalog change when it
     * differs from what was already recorded. Never blocks, never throws
     * into a caller's persistence path ({@code publishChanged} guards its
     * own listeners).
     */
    public void set(ManagedSessionId id, State state) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(state, "state");
        State before = states.put(id, state);
        if (state != before) {
            DrydockSessionsListener.publishChanged();
        }
    }

    /**
     * Forgets the session's liveness — the row is being deleted, so the
     * entry must not linger for the rest of the IDE run. Publishes a change
     * only when something was actually removed.
     *
     * @return the removed state, or empty when there was none
     */
    public Optional<State> remove(ManagedSessionId id) {
        Objects.requireNonNull(id, "id");
        State removed = states.remove(id);
        if (removed != null) {
            DrydockSessionsListener.publishChanged();
        }
        return Optional.ofNullable(removed);
    }
}
