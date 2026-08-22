package app.drydock.ui;

import app.drydock.git.WorktreeService.Worktree;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pure decisions behind the PR group's three async triggers
 * (automatic scan, worktree-change re-scan, in-flight coalescing). Each
 * exists because getting it wrong reintroduced the same defect: a scan
 * launched before worktree discovery has landed dedups {@code
 * RepositoryPullRequests.scan} against an empty worktree list, so every PR
 * that already has a local worktree wrongly earns a row -- the one thing
 * the group exists to avoid.
 */
class RepositorySidebarPullRequestScanTest {

    private static Worktree worktree(String path) {
        return new Worktree(Path.of(path), Optional.of("branch"), false, false, false, false, Optional.empty(), false);
    }

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

    @Test
    void theFirstWorktreeLandingAlwaysCountsAsChanged() {
        assertTrue(RepositorySidebar.worktreeListChanged(null, List.of()));
        assertTrue(RepositorySidebar.worktreeListChanged(null, List.of(worktree("/wt/a"))));
    }

    @Test
    void anIdenticalWorktreeListIsNotAChange() {
        List<Worktree> same = List.of(worktree("/wt/a"));
        assertFalse(RepositorySidebar.worktreeListChanged(same, List.of(worktree("/wt/a"))));
    }

    @Test
    void aWorktreeAppearingOrDisappearingIsAChange() {
        List<Worktree> before = List.of(worktree("/wt/a"));
        List<Worktree> after = List.of(worktree("/wt/a"), worktree("/wt/b"));
        assertTrue(RepositorySidebar.worktreeListChanged(before, after));
        assertTrue(RepositorySidebar.worktreeListChanged(after, before));
    }

    @Test
    void aRequestIsQueuedWhenTheListMovedUnderTheInFlightScan() {
        List<Worktree> captured = List.of(worktree("/wt/a"));
        List<Worktree> current = List.of(worktree("/wt/a"), worktree("/wt/b"));
        assertTrue(RepositorySidebar.shouldQueuePullRequestRescan(captured, current));
    }

    @Test
    void aDuplicateRequestAgainstTheUnchangedListIsNotQueued() {
        List<Worktree> same = List.of(worktree("/wt/a"));
        assertFalse(RepositorySidebar.shouldQueuePullRequestRescan(same, List.of(worktree("/wt/a"))));
    }

    @Test
    void aRequestWithNothingCapturedYetIsQueued() {
        // No scan should ever be in flight with a null captured list, but a
        // request arriving in that window is not silently swallowed.
        assertTrue(RepositorySidebar.shouldQueuePullRequestRescan(null, List.of(worktree("/wt/a"))));
    }
}
