package app.drydock.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code needsPullRequestScan} gates every automatic PR-scan trigger
 * (repo add, repo-row expand). It exists because a scan launched before
 * worktree discovery has landed dedups {@code RepositoryPullRequests.scan}
 * against an empty worktree list, so every PR that already has a local
 * worktree wrongly earns a row -- the one thing the group exists to avoid.
 */
class RepositorySidebarPullRequestScanTest {

    @Test
    void aScanNeverLaunchesBeforeWorktreeDiscoveryHasLanded() {
        assertFalse(RepositorySidebar.needsPullRequestScan(false, false));
        // Even a repo with no prior PR outcome must wait for worktrees.
        assertFalse(RepositorySidebar.needsPullRequestScan(false, true));
    }

    @Test
    void aScanLaunchesOnceDiscoveryHasLandedAndNothingHasScannedYet() {
        assertTrue(RepositorySidebar.needsPullRequestScan(true, false));
    }

    @Test
    void aRepoAlreadyHoldingAnyOutcomeIsNotRescannedOnEveryRebuild() {
        assertFalse(RepositorySidebar.needsPullRequestScan(true, true));
    }
}
