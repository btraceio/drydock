package app.drydock.agent.api;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** The minimal session-record source {@link SnapshotClaimDiscovery} needs (off-FX-thread callers). */
public interface CandidateSource {
    Set<String> snapshotIds(Path workingDirectory);

    /** Ids new since {@code snapshotIds}, with record timestamp &ge; {@code launchedAt}, EARLIEST-first. */
    List<String> newCandidateIds(Path workingDirectory, Instant launchedAt, Set<String> snapshotIds);
}
