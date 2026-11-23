package app.drydock.review;

import app.drydock.git.DiffScope;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The one mapping from a review scope to the diff it covers. Every reader
 * (the diff column, the tour, the agent's review tools) goes through
 * it, so pinning it here pins what the human and the agent both see.
 */
class ReviewScopeDiffScopeTest {

    private static ReviewScope scope(ReviewScope.Kind kind) {
        return ReviewScopeRegistry.spec(kind, Path.of("/repo"), Optional.of(Path.of("/repo/wt")),
                "origin/main", "feat/x",
                kind == ReviewScope.Kind.PR
                        ? Optional.of(new ReviewScope.PullRequestRef(21, Optional.of("https://example.com/pull/21")))
                        : Optional.empty(),
                Optional.empty());
    }

    /** A worktree's "Local changes" covers its commits AND its uncommitted work. */
    @Test
    void aWorktreeReviewsItsBranchAndItsUncommittedWork() {
        assertEquals(DiffScope.BRANCH_WORKING_TREE, scope(ReviewScope.Kind.WORKTREE).diffScope());
    }

    /** The main checkout's "Local changes" stays its uncommitted edits against HEAD. */
    @Test
    void theMainCheckoutReviewsItsUncommittedChanges() {
        assertEquals(DiffScope.WORKING_TREE, scope(ReviewScope.Kind.WORKING_TREE).diffScope());
    }

    /** A pull request is what GitHub shows: the committed branch against its base. */
    @Test
    void aPullRequestReviewsTheCommittedBranch() {
        assertEquals(DiffScope.BASE, scope(ReviewScope.Kind.PR).diffScope());
        assertEquals(DiffScope.BASE, scope(ReviewScope.Kind.BRANCH).diffScope());
    }
}
