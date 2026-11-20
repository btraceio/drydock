package app.drydock.agent.api;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** The minimal session-record source {@link SnapshotClaimDiscovery} needs (off-FX-thread callers). */
public interface CandidateSource {

    /** A session record with its id and creation timestamp, for late-binding resolution. */
    record SessionRecord(String id, Instant timestamp) { }

    Set<String> snapshotIds(Path workingDirectory);

    /** Ids new since {@code snapshotIds}, with record timestamp &ge; {@code launchedAt}, EARLIEST-first. */
    List<String> newCandidateIds(Path workingDirectory, Instant launchedAt, Set<String> snapshotIds);

    /**
     * Sessions for {@code workingDirectory} with their creation timestamps, newest-first.
     * Used by {@link SessionIdDiscovery#resolve} for late-binding a session whose create-time
     * discovery failed. Default empty; providers that support DISCOVERED ids override.
     */
    default List<SessionRecord> sessionsWithTimestamps(Path workingDirectory) {
        return List.of();
    }
}
