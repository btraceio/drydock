package app.cpm.app;

import app.cpm.domain.ManagedSessionId;

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
     * Attempts to mark {@code claudeSessionId} as actively open under {@code
     * sessionId}. Returns {@link Optional#empty()} if it was not already
     * open (and is now registered as active), or the id of the {@link
     * ManagedSessionId} that already holds it open otherwise (in which case
     * no change is made -- the caller should focus/reuse that existing
     * session instead of opening a second surface for the same Claude
     * session).
     */
    Optional<ManagedSessionId> tryMarkActive(String claudeSessionId, ManagedSessionId sessionId) {
        ManagedSessionId existing = activeByClaudeSessionId.putIfAbsent(claudeSessionId, sessionId);
        return Optional.ofNullable(existing);
    }

    /** The {@link ManagedSessionId} currently holding {@code claudeSessionId} open, if any. */
    Optional<ManagedSessionId> activeSessionId(String claudeSessionId) {
        return Optional.ofNullable(activeByClaudeSessionId.get(claudeSessionId));
    }

    /** Releases {@code claudeSessionId}, e.g. once its surface has been closed. */
    void release(String claudeSessionId) {
        activeByClaudeSessionId.remove(claudeSessionId);
    }

    boolean isEmpty() {
        return activeByClaudeSessionId.isEmpty();
    }
}
