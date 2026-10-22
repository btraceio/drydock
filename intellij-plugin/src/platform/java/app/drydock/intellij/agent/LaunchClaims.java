package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Serializes session launches against each other and against the release of a session's MCP access.
 *
 * <p>A launch claims its session with {@link #tryClaim} before it persists anything or mints credentials.
 * A claim is refused while the session has a live execution or another claim. A removal claims the
 * session with {@link #tryClaimForRemoval}, which only another claim refuses.
 *
 * <p>{@link #releaseIfIdle} checks for a claim and a live execution, and {@link #finish} removes its own
 * claim and then checks for a live execution; each runs the release, under this object's lock, as one step. So a launch either claims after a release (and mints a fresh token)
 * or claimed before the check (and the release is skipped).
 *
 * <p>The {@code release} runs while the lock is held. It must be short and must not block on another claim.
 * The {@code liveExecution} predicate runs under the lock too and must not block.
 *
 * <p>Thread-safe. Any thread may call it, but {@code release} may do file I/O, so callers keep these calls
 * off the EDT.
 */
public final class LaunchClaims {

    private final Predicate<ManagedSessionId> liveExecution;
    private final Set<ManagedSessionId> claimed = new HashSet<>();

    /** @param liveExecution whether the session has a live agent execution; must not block */
    public LaunchClaims(Predicate<ManagedSessionId> liveExecution) {
        this.liveExecution = Objects.requireNonNull(liveExecution, "liveExecution");
    }

    /**
     * Claims the session for a launch.
     *
     * @return false, claiming nothing, when the session has a live execution or is already claimed
     */
    public synchronized boolean tryClaim(ManagedSessionId id) {
        Objects.requireNonNull(id, "id");
        if (claimed.contains(id) || liveExecution.test(id)) {
            return false;
        }
        claimed.add(id);
        return true;
    }

    /**
     * Ends a claim, then runs {@code release} unless the session has a live execution. Ending an
     * absent claim is a no-op: {@code release} does not run. An exception from {@code release}
     * propagates; the claim is already removed.
     */
    public synchronized void finish(ManagedSessionId id, Runnable release) {
        if (!claimed.remove(id)) {
            return;
        }
        if (!liveExecution.test(id)) {
            release.run();
        }
    }

    /**
     * Claims the session for its removal. Unlike {@link #tryClaim}, a live execution does not refuse the
     * claim, because the removal stops that execution. The caller ends the claim with {@link #finish}.
     *
     * @return false, claiming nothing, when the session is already claimed
     */
    public synchronized boolean tryClaimForRemoval(ManagedSessionId id) {
        Objects.requireNonNull(id, "id");
        return claimed.add(id);
    }

    /**
     * Runs {@code release} only when the session has neither a claim nor a live execution.
     *
     * @return whether {@code release} ran
     */
    public synchronized boolean releaseIfIdle(ManagedSessionId id, Runnable release) {
        if (claimed.contains(id) || liveExecution.test(id)) {
            return false;
        }
        release.run();
        return true;
    }
}
