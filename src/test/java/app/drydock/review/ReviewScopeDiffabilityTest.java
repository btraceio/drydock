package app.drydock.review;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A PR with no worktree pairs the PR's base with the *main checkout* as its
 * diff root (see {@link ReviewScope#diffRoot()}), so any diff it produced
 * would be "the main checkout's HEAD vs the PR's base" -- wrong by
 * construction, and never the PR's changes. Nothing may run one.
 */
class ReviewScopeDiffabilityTest {

    @Test
    void aPullRequestWithNoWorktreeIsNotDiffable() {
        ReviewScope scope = new ReviewScope("s1", ReviewScope.Kind.PR, Path.of("/repo"),
                Optional.empty(), "main", "feature",
                Optional.of(new ReviewScope.PullRequestRef(7, Optional.empty())),
                Optional.empty(), Optional.empty());

        assertFalse(scope.diffable());
    }

    @Test
    void theSamePullRequestCheckedOutIsDiffable() {
        ReviewScope scope = new ReviewScope("s1", ReviewScope.Kind.PR, Path.of("/repo"),
                Optional.of(Path.of("/repo/.worktrees/pr-7")), "main", "feature",
                Optional.of(new ReviewScope.PullRequestRef(7, Optional.empty())),
                Optional.empty(), Optional.empty());

        assertTrue(scope.diffable());
    }

    @Test
    void aWorkingTreeIsAlwaysDiffable() {
        ReviewScope scope = new ReviewScope("s1", ReviewScope.Kind.WORKING_TREE, Path.of("/repo"),
                Optional.of(Path.of("/repo")), "main", "main",
                Optional.empty(), Optional.empty(), Optional.empty());

        assertTrue(scope.diffable());
    }
}
