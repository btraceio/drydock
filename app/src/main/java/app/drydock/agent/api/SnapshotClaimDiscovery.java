package app.drydock.agent.api;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Best-effort DISCOVERED id capture: snapshot before launch, then claim the first new
 * unclaimed candidate; bail (empty) if 2+ are ambiguous. Race-safe via an atomic claim.
 */
public final class SnapshotClaimDiscovery implements SessionIdDiscovery {

    private static final Logger LOG = System.getLogger(SnapshotClaimDiscovery.class.getName());
    private final CandidateSource source;
    private final int attempts;
    private final long sleepMillis;

    public SnapshotClaimDiscovery(CandidateSource source) {
        this(source, 20, 250);   // ~5s best-effort window
    }

    public SnapshotClaimDiscovery(CandidateSource source, int attempts, long sleepMillis) {
        this.source = source;
        this.attempts = attempts;
        this.sleepMillis = sleepMillis;
    }

    @Override
    public Object snapshot(Path workingDirectory) {
        return source.snapshotIds(workingDirectory);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<String> discover(Path cwd, Instant launchedAt, Object snapshot, Set<String> claimedIds) {
        Set<String> snap = (Set<String>) snapshot;
        for (int i = 0; i < attempts; i++) {
            List<String> fresh = source.newCandidateIds(cwd, launchedAt, snap).stream()
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
                // Ambiguous: concurrent same-cwd launches (or an external tool in this
                // cwd) produced multiple unclaimed candidates. Binding any one risks the
                // WRONG session id -- which looks successful, worse than degrading. Bail
                // -> the session keeps an empty id and resume falls back to the picker.
                LOG.log(Level.INFO, "Session id ambiguous for {0} ({1} candidates); resume via picker",
                        cwd, fresh.size());
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
        LOG.log(Level.INFO, "Session id not discovered for {0} (resume via picker)", cwd);
        return Optional.empty();
    }
}
