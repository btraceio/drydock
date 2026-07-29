package app.drydock.ui.review;

import app.drydock.review.ReviewItem;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewScopeRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The quick-search predicate, tested without a JavaFX toolkit -- the rule
 * lives in a static so it can be exercised directly, the same reason
 * {@code nextIndex} is one (see {@link ReviewQueueRailSelectionTest}).
 */
class ReviewQueueRailMatchTest {

    private static final ReviewItem AGENT_WORKTREE =
            item(ReviewItem.Group.AGENTS, "agent/issue-919-metadata", "btrace · vs develop");
    private static final ReviewItem PULL_REQUEST =
            item(ReviewItem.Group.REQUESTED, "PR #854 renovate/all-minor-patch", "btrace · @renovate");

    @Test
    void aTitleSubstringMatches() {
        assertTrue(ReviewQueueRail.matches(AGENT_WORKTREE, "919"));
    }

    @Test
    void aSubtitleSubstringMatches() {
        assertTrue(ReviewQueueRail.matches(AGENT_WORKTREE, "develop"));
        assertTrue(ReviewQueueRail.matches(PULL_REQUEST, "btrace"));
    }

    /**
     * The rail prints the group label at the head of every row's second
     * line, so a reviewer can read "agents" on screen -- and must therefore
     * be able to search for it, even though it is not part of the subtitle.
     */
    @Test
    void aGroupLabelQueryMatchesThatGroupAndNoOther() {
        assertTrue(ReviewQueueRail.matches(AGENT_WORKTREE, "agents"));
        assertFalse(ReviewQueueRail.matches(PULL_REQUEST, "agents"));
    }

    @Test
    void matchingIsCaseInsensitive() {
        assertTrue(ReviewQueueRail.matches(PULL_REQUEST, "RENOVATE"));
        assertTrue(ReviewQueueRail.matches(PULL_REQUEST, "ReNoVaTe"));
    }

    @Test
    void aBlankQueryMatchesEverything() {
        assertTrue(ReviewQueueRail.matches(AGENT_WORKTREE, ""));
        assertTrue(ReviewQueueRail.matches(AGENT_WORKTREE, "   "));
        assertTrue(ReviewQueueRail.matches(PULL_REQUEST, "\t"));
    }

    @Test
    void aQueryInNoFieldDoesNotMatch() {
        assertFalse(ReviewQueueRail.matches(AGENT_WORKTREE, "zzz"));
    }

    /**
     * The fields are joined by separators precisely so a query cannot match
     * the artifact where one field's tail meets the next field's head.
     */
    @Test
    void aQueryConcatenatingTwoFieldsDoesNotMatch() {
        assertFalse(ReviewQueueRail.matches(PULL_REQUEST, "patchbtrace"));
        assertFalse(ReviewQueueRail.matches(AGENT_WORKTREE, "agentsagent"));
    }

    private static ReviewItem item(ReviewItem.Group group, String title, String subtitle) {
        ReviewScope scope = new ReviewScopeRegistry().mint(ReviewScopeRegistry.spec(
                ReviewScope.Kind.WORKTREE, Path.of("/repo"), Optional.of(Path.of("/wt/x")),
                "develop", title, Optional.empty(), Optional.empty()));
        return new ReviewItem(scope, group, title, subtitle);
    }
}
