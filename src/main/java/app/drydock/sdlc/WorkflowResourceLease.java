package app.drydock.sdlc;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Cleanup responsibility for materialized resources, held by exactly one owner at a time. The invocation owns
 * it until {@link #transferToSession()} succeeds once; {@link #release()} runs cleanup at most once.
 */
public final class WorkflowResourceLease {

    public enum Owner { PENDING_INVOCATION, RETAINED_SESSION, RELEASED }

    private final Supplier<CompletableFuture<Void>> cleanup;
    private Owner owner = Owner.PENDING_INVOCATION;
    private CompletableFuture<Void> released;

    public WorkflowResourceLease(Supplier<CompletableFuture<Void>> cleanup) {
        this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
    }

    public synchronized Owner owner() {
        return owner;
    }

    /** Moves ownership to the session once; false if already transferred or released. */
    public synchronized boolean transferToSession() {
        if (owner != Owner.PENDING_INVOCATION) {
            return false;
        }
        owner = Owner.RETAINED_SESSION;
        return true;
    }

    /** Idempotent: the first call starts cleanup, later calls return the same future. */
    public synchronized CompletableFuture<Void> release() {
        if (released == null) {
            owner = Owner.RELEASED;
            released = cleanup.get();
        }
        return released;
    }
}
