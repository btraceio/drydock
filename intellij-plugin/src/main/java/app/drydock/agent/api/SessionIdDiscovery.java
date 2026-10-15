package app.drydock.agent.api;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * Captures the session id a DISCOVERED-strategy tool (Codex, Pi) mints for itself.
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

    /**
     * Best-effort late binding for a session whose create-time {@link #discover}
     * failed (timed out or was ambiguous): resolve the agent session id from
     * the drydock session's persisted creation time, among candidates not
     * already claimed by another session. Empty if not resolvable (no
     * unclaimed candidates near the creation time, or ambiguous).
     *
     * <p>Both methods may touch the filesystem and MUST run off the FX thread.
     * The default returns empty — providers that support DISCOVERED ids
     * override.</p>
     */
    default Optional<String> resolve(Path workingDirectory, Instant createdAt, Set<String> claimedIds) {
        return Optional.empty();
    }
}
