package app.drydock.intellij.agent;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A bounded, single-snapshot index from session id to the catalog entry.
 *
 * <p>Tab titles, tab icons, and the per-tab header actions all resolve the
 * session behind a file on every paint or action-update tick, and the old
 * resolution walked the whole catalog each time — O(tabs × catalog) per tick
 * once every tab carries three actions. This index holds ONE snapshot of the
 * current catalog and replaces it wholesale whenever the catalog list
 * instance changes, so a lookup is O(1) and the map never keeps removed
 * sessions and never accumulates per-session state.</p>
 *
 * <p>The invalidation key is the catalog {@code List} instance itself: {@code
 * ApplicationState} is an immutable record whose {@code sessions()} accessor
 * returns the one list it was constructed with, and {@code
 * ApplicationStateStore.state()} hands out the same state (hence the same
 * list) until an {@code update} swaps it for a new one — so {@code !=} on the
 * list means "the catalog changed", and the same instance means it cannot
 * have. The map is keyed by id and keeps the first entry for any duplicate,
 * matching the linear scan it replaces.</p>
 */
final class CatalogSessionIndex {

    /** The current snapshot: the catalog list it mirrors, and the map built from it. */
    private record Snapshot(List<ManagedAgentSession> source, Map<ManagedSessionId, ManagedAgentSession> byId) {
    }

    private volatile Snapshot snapshot = new Snapshot(List.of(), Map.of());

    /** How many times the map has been rebuilt; for the boundedness tests. */
    private int rebuilds;

    /**
     * The session with {@code id} in {@code current}, or null. Rebuilds the
     * map only when {@code current} is not the list the snapshot was built
     * from (reference identity); otherwise the lookup is an O(1) map get.
     * Called on the EDT during paints, so it must stay memory-only.
     */
    ManagedAgentSession find(List<ManagedAgentSession> current, ManagedSessionId id) {
        Snapshot snap = snapshot;
        if (current != snap.source) {
            Map<ManagedSessionId, ManagedAgentSession> byId = new HashMap<>();
            for (ManagedAgentSession candidate : current) {
                // putIfAbsent keeps the first match, like the scan it replaces.
                byId.putIfAbsent(candidate.id(), candidate);
            }
            snap = new Snapshot(current, Map.copyOf(byId));
            snapshot = snap;
            rebuilds++;
        }
        return snap.byId.get(id);
    }

    /** Number of rebuilds performed so far; for tests. */
    int rebuilds() {
        return rebuilds;
    }

    /** Sessions in the current snapshot; for tests. */
    int size() {
        return snapshot.byId.size();
    }
}
