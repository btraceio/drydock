package app.drydock.app;

import app.drydock.domain.ManagedSessionId;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory-only "Claude session ID -&gt; active {@link ManagedSessionId}"
 * bookkeeping for duplicate-open protection (plan section 11.3).
 *
 * <p>Deliberately not persisted: an "active" Claude session only means
 * "this application currently has a live {@code GhosttySurface}/process for
 * it", which is inherently a runtime fact that does not survive an
 * application restart (plan section 2.2: an inactive session does not need
 * a live process). Package-private and surface-free by design so its
 * bookkeeping logic is unit-testable with fake ids alone, with no real
 * {@code GhosttySurface}/window required.</p>
 */
final class ActiveSessionRegistry {

    private final Map<String, ManagedSessionId> activeByClaudeSessionId = new ConcurrentHashMap<>();

    /**
     * Attempts to mark {@code agentSessionId} as actively open under {@code
     * sessionId}. Returns {@link Optional#empty()} if it was not already
     * open (and is now registered as active), or the id of the {@link
     * ManagedSessionId} that already holds it open otherwise (in which case
     * no change is made -- the caller should focus/reuse that existing
     * session instead of opening a second surface for the same Claude
     * session).
     */
    Optional<ManagedSessionId> tryMarkActive(String agentSessionId, ManagedSessionId sessionId) {
        ManagedSessionId existing = activeByClaudeSessionId.putIfAbsent(agentSessionId, sessionId);
        return Optional.ofNullable(existing);
    }

    /** The {@link ManagedSessionId} currently holding {@code agentSessionId} open, if any. */
    Optional<ManagedSessionId> activeSessionId(String agentSessionId) {
        return Optional.ofNullable(activeByClaudeSessionId.get(agentSessionId));
    }

    /** Releases {@code agentSessionId}, e.g. once its surface has been closed. */
    void release(String agentSessionId) {
        activeByClaudeSessionId.remove(agentSessionId);
    }

    boolean isEmpty() {
        return activeByClaudeSessionId.isEmpty();
    }
}
