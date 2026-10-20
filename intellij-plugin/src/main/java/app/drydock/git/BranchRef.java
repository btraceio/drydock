package app.drydock.git;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * One branch offered by the create-worktree modal's picker: either a local
 * branch ({@code feat/minors}) or a remote-tracking one
 * ({@code origin/feature/x}). {@link #checkedOutAt()} is the worktree that
 * already holds it -- git refuses to check the same branch out twice, so a
 * present value blocks selection.
 *
 * <p>{@link #prunable()} and {@link #locked()} stay apart because the way
 * out of each differs: {@code git worktree prune} releases a prunable
 * (administratively stale) worktree but <em>silently skips a locked one</em>,
 * which needs {@code git worktree unlock} first. Collapsing them would hand
 * a locked branch advice that provably does nothing. Either way the branch
 * is blocked.</p>
 */
public record BranchRef(String name, boolean remote, Optional<Path> checkedOutAt, boolean prunable,
                        boolean locked) {

    public BranchRef {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(checkedOutAt, "checkedOutAt");
        if (name.isBlank()) {
            throw new IllegalArgumentException("BranchRef name must not be blank");
        }
    }

    public static BranchRef local(String name) {
        return new BranchRef(name, false, Optional.empty(), false, false);
    }

    public static BranchRef remote(String name) {
        return new BranchRef(name, true, Optional.empty(), false, false);
    }

    /** Whether a worktree can be created on this branch right now. */
    public boolean available() {
        return checkedOutAt.isEmpty();
    }
}
