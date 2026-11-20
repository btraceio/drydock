package app.drydock.domain;

import app.drydock.domain.SessionWorkspace;
import app.drydock.domain.PrLink;
import app.drydock.domain.AgentBinding;
import app.drydock.agent.api.AgentKind;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * git branch -D is irreversible for an unpushed branch, so the delete paths
 * only pass a branch name when drydock is the one that created it.
 */
class BranchOwnershipTest {

    private static ManagedAgentSession session(Path worktreeRoot, boolean branchCreatedHere) {
        Instant now = Instant.parse("2026-07-22T00:00:00Z");
        return new ManagedAgentSession(
                ManagedSessionId.newId(), RepositoryId.newId(), "s",
                new AgentBinding(AgentKind.CLAUDE, Optional.empty(), Optional.empty()),
                new SessionWorkspace(worktreeRoot, Optional.of(worktreeRoot), branchCreatedHere),
                SessionStatus.INACTIVE, now, now, Optional.empty(),
                PrLink.of(PrState.NONE, Optional.empty()), false, Optional.empty());
    }

    @Test
    void aBranchThisAppCreatedMayBeDeleted() {
        Path worktree = Path.of("/wt/feature");
        assertTrue(BranchOwnership.mayDeleteBranchOf(List.of(session(worktree, true)), worktree));
    }

    @Test
    void aPreExistingBranchCheckedOutHereMayNotBeDeleted() {
        Path worktree = Path.of("/wt/feature");
        assertFalse(BranchOwnership.mayDeleteBranchOf(List.of(session(worktree, false)), worktree));
    }

    @Test
    void aWorktreeWithNoSessionAtAllMayNotHaveItsBranchDeleted() {
        // Discovered on disk by the sidebar rescan: drydock never created it,
        // so it has no business force-deleting the branch.
        assertFalse(BranchOwnership.mayDeleteBranchOf(List.of(), Path.of("/wt/discovered")));
        assertFalse(BranchOwnership.mayDeleteBranchOf(
                List.of(session(Path.of("/wt/other"), true)), Path.of("/wt/discovered")));
    }
}
