package app.drydock.agent.api;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
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
        this(source, 60, 500);   // ~30s best-effort window
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

    /**
     * Late binding: among the unclaimed session records for {@code workingDirectory},
     * find the one whose creation timestamp is closest to the drydock session's
     * {@code createdAt}. The agent CLI writes its session record within seconds
     * of launch, so the matching record is the unclaimed one nearest in time.
     * Old "previous" sessions have timestamps well before {@code createdAt} and
     * are excluded by the lower bound; ambiguity (two unclaimed records both
     * near {@code createdAt}) bails rather than risk binding the wrong id.
     */
    @Override
    public Optional<String> resolve(Path workingDirectory, Instant createdAt, Set<String> claimedIds) {
        List<CandidateSource.SessionRecord> sessions = source.sessionsWithTimestamps(workingDirectory);
        if (sessions.isEmpty()) {
            return Optional.empty();
        }
        // Allow a small backward skew: the agent record's clock may lag the
        // Java clock by a fraction of a second.
        Instant earliest = createdAt.minusSeconds(5);
        record Near(String id, Duration delta) { }
        List<Near> near = sessions.stream()
                .filter(s -> !claimedIds.contains(s.id()))
                .filter(s -> !s.timestamp().isBefore(earliest))
                .map(s -> new Near(s.id(), Duration.between(createdAt, s.timestamp()).abs()))
                .sorted(Comparator.comparing(Near::delta))
                .toList();
        if (near.isEmpty()) {
            return Optional.empty();
        }
        // The agent session is written within seconds of the drydock session's
        // creation; anything farther than the tolerance is not a match.
        if (near.get(0).delta().compareTo(TOLERANCE) > 0) {
            return Optional.empty();
        }
        // Two unclaimed records both within the tolerance and close together
        // is ambiguous — binding either risks the wrong session.
        if (near.size() >= 2
                && near.get(1).delta().compareTo(TOLERANCE) <= 0
                && near.get(1).delta().minus(near.get(0).delta()).compareTo(AMBIGUITY_GAP) < 0) {
            LOG.log(Level.INFO, "Session id resolve ambiguous for {0} (two candidates near creation time)",
                    workingDirectory);
            return Optional.empty();
        }
        return Optional.of(near.get(0).id());
    }

    private static final Duration TOLERANCE = Duration.ofSeconds(120);
    private static final Duration AMBIGUITY_GAP = Duration.ofSeconds(10);
}
