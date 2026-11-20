package app.drydock.intellij.tasks;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Bounded cache of which stopped sessions still have a resumable
 * conversation.
 *
 * <p>A "can this stopped session be resumed" answer requires asking the agent
 * CLI whether its conversation still exists, which is a blocking process probe.
 * The tree gathers on every input change, so the answer has to be remembered
 * between gathers. Without a bound that memory would grow with every session
 * the application has ever recorded; here it is pruned to the stopped half of
 * the current catalog on every {@link #refresh}, so it can never hold more
 * than the sessions a human can currently see.</p>
 *
 * <p>An entry is keyed by the application's {@link ManagedSessionId} and
 * remembers the {@code agentSessionId} it was probed under. The CLI reassigns
 * that id when a session is relaunched with a fresh conversation, so an
 * unchanged answer is only valid while the bound conversation id is unchanged;
 * a change re-probes. A probe that throws leaves the session absent so the
 * next refresh retries it rather than caching a guess.</p>
 */
final class ResumabilityCache {

    private static final Logger LOG = System.getLogger(ResumabilityCache.class.getName());

    /** What was probed, and the answer: an answer is stale once the conversation id changes. */
    private record Known(Optional<String> agentSessionId, boolean resumable) {
    }

    private final ConcurrentHashMap<ManagedSessionId, Known> known = new ConcurrentHashMap<>();

    /**
     * Brings the cache in line with the current catalog and returns the
     * resumability of every stopped catalog session.
     *
     * <p><strong>Blocking.</strong> The probe may spawn a process and wait on
     * it; call this off the EDT only, and never from a paint or update path.
     * Gathers already run on a background executor.</p>
     *
     * <p>Sessions in {@code running} are dropped from the cache and neither
     * probed nor returned: while a process is live the resume label is
     * irrelevant, and the bound conversation id is about to change anyway.
     * The cache is pruned to the stopped catalog ids before probing, which is
     * what bounds its size.</p>
     *
     * @param catalog the current session catalog, running and stopped alike
     * @param running ids with a live agent process
     * @param probe   blocking test of whether a stopped session's conversation
     *                still exists; may throw {@link RuntimeException}, in
     *                which case the session is left absent (retried next time)
     * @return an immutable map from each stopped catalog id to whether it is
     *         resumable; sessions whose probe failed are absent from it
     */
    Map<ManagedSessionId, Boolean> refresh(List<ManagedAgentSession> catalog, Set<ManagedSessionId> running,
                                          Predicate<ManagedAgentSession> probe) {
        Set<ManagedSessionId> stopped = new HashSet<>();
        for (ManagedAgentSession session : catalog) {
            if (!running.contains(session.id())) {
                stopped.add(session.id());
            }
        }
        known.keySet().retainAll(stopped);

        Map<ManagedSessionId, Boolean> fresh = new HashMap<>();
        for (ManagedAgentSession session : catalog) {
            if (!stopped.contains(session.id())) {
                continue;
            }
            ManagedSessionId id = session.id();
            Known prior = known.get(id);
            if (prior != null && prior.agentSessionId().equals(session.agentSessionId())) {
                fresh.put(id, prior.resumable());
                continue;
            }
            try {
                boolean resumable = probe.test(session);
                known.put(id, new Known(session.agentSessionId(), resumable));
                fresh.put(id, resumable);
            } catch (RuntimeException e) {
                // A probe failure answers nothing: leave the session absent so
                // the next refresh retries it instead of caching a guess.
                known.remove(id);
                LOG.log(Level.DEBUG, "Resumability probe failed for " + id + "; will retry", e);
            }
        }
        return Map.copyOf(fresh);
    }

    /** Empties the cache; the next refresh re-probes everything. */
    void clear() {
        known.clear();
    }

    /** Current entry count, for tests; never more than the stopped catalog. */
    int size() {
        return known.size();
    }
}
