package app.drydock.intellij.tasks;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Coalesces task-tree refreshes while retaining deferred worktree invalidation. */
final class TaskTreeRefreshRequests {

    record Pass(long version, boolean worktreesChanged) { }

    private final AtomicBoolean pending = new AtomicBoolean();
    private final AtomicBoolean worktreesChanged = new AtomicBoolean();
    private final AtomicLong version = new AtomicLong();

    void request(boolean changedWorktrees) {
        if (changedWorktrees) {
            worktreesChanged.set(true);
        }
        version.incrementAndGet();
    }

    Optional<Pass> beginPass() {
        if (!pending.compareAndSet(false, true)) {
            return Optional.empty();
        }
        return Optional.of(new Pass(version.get(), worktreesChanged.getAndSet(false)));
    }

    long version() {
        return version.get();
    }

    void finishPass() {
        pending.set(false);
    }
}
