package app.drydock.agent.api;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * Captures the session id a DISCOVERED-strategy tool (Codex) mints for itself.
 * The tool assigns its own id only after launch, so Drydock snapshots the id
 * store just before spawning and claims the first new matching record after.
 *
 * <p>Both methods may touch the filesystem and MUST run off the FX thread.</p>
 */
public interface SessionIdDiscovery {

    /** Opaque pre-launch snapshot of the id store for {@code workingDirectory} (e.g. the set of existing ids). */
    Object snapshot(Path workingDirectory);

    /**
     * Best-effort: the id of a record that (a) is new since {@code snapshot},
     * (b) belongs to {@code workingDirectory}, (c) has timestamp &ge;
     * {@code launchedAt}, and (d) is not in {@code claimedIds}. Empty if none is
     * found (discovery failed/raced) — never throws for "not found".
     */
    Optional<String> discover(Path workingDirectory, Instant launchedAt, Object snapshot, Set<String> claimedIds);
}
