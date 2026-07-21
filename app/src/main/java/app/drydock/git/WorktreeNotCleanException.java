package app.drydock.git;

import java.nio.file.Path;

/**
 * A plain {@code git worktree remove} was refused because the worktree
 * holds uncommitted work (modified or untracked files) that a forced
 * removal would discard. Distinct from {@link GitCommandFailedException}
 * so the UI can offer the user an explicit "delete anyway" confirmation
 * (retrying via {@link WorktreeService#removeForced}) instead of a dead-end
 * error dialog.
 */
public final class WorktreeNotCleanException extends GitException {

    private final Path worktreePath;

    WorktreeNotCleanException(Path worktreePath) {
        super("The worktree at " + worktreePath + " contains modified or untracked files");
        this.worktreePath = worktreePath;
    }

    public Path worktreePath() {
        return worktreePath;
    }
}
