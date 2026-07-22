package app.drydock.domain;

import java.nio.file.Path;
import java.util.List;

/**
 * Whether drydock may force-delete the branch of a worktree it is removing.
 *
 * <p>{@code git worktree remove} is recoverable; the {@code git branch -D}
 * that follows it is not, for a branch with unpushed commits. Deleting is
 * therefore allowed only where drydock knows it created the branch itself:
 * a branch checked out from one that already existed -- and any worktree
 * merely discovered on disk -- keeps its branch when the worktree goes.</p>
 */
public final class BranchOwnership {

    private BranchOwnership() {
    }

    public static boolean mayDeleteBranchOf(List<ManagedClaudeSession> sessions, Path worktreeRoot) {
        Path normalized = worktreeRoot.toAbsolutePath().normalize();
        return sessions.stream()
                .filter(session -> session.worktreeRoot()
                        .map(root -> root.toAbsolutePath().normalize().equals(normalized))
                        .orElse(false))
                .anyMatch(ManagedClaudeSession::branchCreatedHere);
    }
}
