package app.drydock.agent.providers.codex;

import app.drydock.agent.api.SessionIdDiscovery;
import app.drydock.agent.providers.codex.internal.CodexRolloutStore;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Snapshot-and-claim id capture for Codex (spike §Q1): no preset/marker exists,
 * so the id is found by polling the rollout store for a new {@code source:"cli"}
 * rollout under {@code cwd} created at/after launch, not already claimed.
 */
public final class CodexIdDiscovery implements SessionIdDiscovery {

    private static final Logger LOG = System.getLogger(CodexIdDiscovery.class.getName());
    private final CodexRolloutStore store;
    private final int attempts;
    private final long sleepMillis;

    public CodexIdDiscovery(CodexRolloutStore store) {
        this(store, 20, 250);   // ~5s best-effort window
    }

    CodexIdDiscovery(CodexRolloutStore store, int attempts, long sleepMillis) {
        this.store = store;
        this.attempts = attempts;
        this.sleepMillis = sleepMillis;
    }

    @Override
    public Object snapshot(Path workingDirectory) {
        return store.idsFor(workingDirectory);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<String> discover(Path workingDirectory, Instant launchedAt, Object snapshot,
                                     Set<String> claimedIds) {
        Set<String> snapshotIds = (Set<String>) snapshot;
        for (int i = 0; i < attempts; i++) {
            List<String> fresh = store.newCandidates(workingDirectory, launchedAt, snapshotIds).stream()
                    .map(CodexRolloutStore.RolloutMeta::id)
                    .filter(id -> !claimedIds.contains(id))
                    .toList();
            if (fresh.size() == 1) {
                String id = fresh.get(0);
                // Atomic claim: add() returns true if we won the race, false if another
                // concurrent discovery took this id (we re-poll). UnsupportedOperationException
                // propagates: immutable sets are contract violations, not graceful fallbacks.
                if (claimedIds.add(id)) {
                    return Optional.of(id);
                }
            } else if (fresh.size() >= 2) {
                // Ambiguous: concurrent same-cwd launches (or an external codex in this
                // cwd) produced multiple unclaimed rollouts. Binding any one risks the
                // WRONG session id — which looks successful, worse than degrading. Bail
                // -> the session keeps an empty id and resume falls back to the picker.
                LOG.log(Level.INFO,
                        "Codex id ambiguous for {0} ({1} candidates); resume will use the picker",
                        workingDirectory, fresh.size());
                return Optional.empty();
            }
            if (sleepMillis > 0 && i < attempts - 1) {
                try {
                    Thread.sleep(sleepMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Optional.empty();
                }
            }
        }
        LOG.log(Level.INFO, "Codex id not discovered for {0} (resume will use the picker)", workingDirectory);
        return Optional.empty();
    }
}
