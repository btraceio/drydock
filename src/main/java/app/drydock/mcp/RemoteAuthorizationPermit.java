package app.drydock.mcp;

import app.drydock.domain.ManagedSessionId;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * One authenticated remote request's authorization: the session its credential
 * resolved to, that credential's generation, and the owner's revocation fence
 * for exactly that session/generation.
 *
 * <p>{@link McpServer} captures a permit right after the token resolves, refuses
 * the request before dispatch if it is no longer current, and binds it for the
 * dispatch in {@link Bound}. A mutation commits through
 * {@link #commitIfCurrent(Supplier)}, which holds the fence's lock until the
 * supplier returns. Lock order is fence first, then any state-store lock; the
 * owner's revocation marks the fence invalid under that lock ALONE and does its
 * cleanup after releasing it. The supplier must not acquire another permit or
 * revoke.</p>
 */
public final class RemoteAuthorizationPermit {

    /**
     * The owner's per-session/generation authorization fence; every permit of
     * that session/generation shares the same instance, and revocation marks
     * the same one.
     */
    public interface Fence {
        /** The monitor guarding the revoked flag and every commit. */
        Object lock();

        /** Whether the generation is revoked or superseded; read only under {@link #lock()}. */
        boolean revoked();
    }

    /** The commit found the authorization revoked, superseded or foreign; nothing was applied. */
    public static final class StaleAuthorizationException extends IllegalStateException {
        public StaleAuthorizationException() {
            super("Remote authorization is no longer current");
        }
    }

    /** Supplied by the owner: the permit for a session whose token has just resolved, if it may act. */
    @FunctionalInterface
    public interface Source {
        Optional<RemoteAuthorizationPermit> permitFor(ManagedSessionId authenticated);
    }

    /** Called for an authenticated initialize with the launch identity headers (either may be null). */
    @FunctionalInterface
    public interface InitializeObserver {
        void authenticatedInitialize(ManagedSessionId authenticated, String launchNonce, String bridgePid);
    }

    /** The two optional remote seams; the local endpoint uses {@link #NONE}. */
    public record Seams(Source permits, InitializeObserver observer) {
        public static final Seams NONE = new Seams(null, null);
    }

    private final ManagedSessionId sessionId;
    private final long generation;
    private final Fence fence;

    public RemoteAuthorizationPermit(ManagedSessionId sessionId, long generation, Fence fence) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.generation = generation;
        this.fence = Objects.requireNonNull(fence, "fence");
    }

    public ManagedSessionId sessionId() {
        return sessionId;
    }

    public long generation() {
        return generation;
    }

    /** A point-in-time check for refusing a request before dispatch; commits recheck under the lock. */
    public boolean isCurrent() {
        synchronized (fence.lock()) {
            return !fence.revoked();
        }
    }

    /** Runs the supplier only if still current for the authenticated id; fails closed otherwise. */
    public <T> T commitIfCurrent(ManagedSessionId authenticated, Supplier<T> commit) {
        if (!sessionId.equals(authenticated)) {
            throw new StaleAuthorizationException();
        }
        return commitIfCurrent(commit);
    }

    /** Holds the fence lock through the supplier's return; throws {@link StaleAuthorizationException} if revoked. */
    public <T> T commitIfCurrent(Supplier<T> commit) {
        Objects.requireNonNull(commit, "commit");
        synchronized (fence.lock()) {
            if (fence.revoked()) {
                throw new StaleAuthorizationException();
            }
            return commit.get();
        }
    }

    /** The request-scoped holder context delegates read; bound per dispatch, never retained across requests. */
    public static final class Bound implements AutoCloseable {
        private static final ThreadLocal<RemoteAuthorizationPermit> CURRENT = new ThreadLocal<>();
        private final RemoteAuthorizationPermit previous;

        private Bound(RemoteAuthorizationPermit previous) {
            this.previous = previous;
        }

        public static Bound bind(RemoteAuthorizationPermit permit) {
            Bound bound = new Bound(CURRENT.get());
            CURRENT.set(permit);
            return bound;
        }

        /** The permit of the request being dispatched on this thread; empty on the local endpoint. */
        public static Optional<RemoteAuthorizationPermit> current() {
            return Optional.ofNullable(CURRENT.get());
        }

        @Override
        public void close() {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
