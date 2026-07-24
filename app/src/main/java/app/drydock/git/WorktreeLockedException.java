package app.drydock.git;

import java.nio.file.Path;
import java.util.Optional;

/**
 * A plain {@code git worktree remove} was refused because the worktree is
 * <em>locked</em> -- a deliberate "do not remove me" marker set by whoever
 * (or whatever) created it: git itself for a removable-media checkout, or a
 * tool mid-setup (sphinx locks its {@code .sphinx/worktrees/*} with reason
 * {@code initializing}). Unlike a dirty worktree, a lock is not cleared by
 * a single {@code --force}; git demands {@code -f -f} or an explicit unlock.
 *
 * <p>Distinct from {@link GitCommandFailedException} so the UI can name the
 * lock (and its {@link #lockReason() reason}, when git reported one) in an
 * explicit "remove anyway?" confirmation -- overriding an intentional lock
 * may disrupt the tool that set it -- instead of a dead-end error dialog.
 * A confirmed retry goes through {@link WorktreeService#removeForced}, whose
 * double-force overrides the lock.</p>
 */
public final class WorktreeLockedException extends GitException {

    private final Path worktreePath;
    private final Optional<String> lockReason;

    WorktreeLockedException(Path worktreePath, Optional<String> lockReason) {
        super("The worktree at " + worktreePath + " is locked"
                + lockReason.map(reason -> " (reason: " + reason + ")").orElse(""));
        this.worktreePath = worktreePath;
        this.lockReason = lockReason;
    }

    public Path worktreePath() {
        return worktreePath;
    }

    /** The lock reason git recorded, if any ({@code git worktree lock --reason}). */
    public Optional<String> lockReason() {
        return lockReason;
    }
}
