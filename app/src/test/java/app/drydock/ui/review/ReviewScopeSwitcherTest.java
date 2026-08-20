package app.drydock.ui.review;

import app.drydock.review.ReviewScope;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What the two chips say. Pure text, so no FX toolkit is needed. */
class ReviewScopeSwitcherTest {

    private static ReviewScope scope(ReviewScope.Kind kind, Optional<Integer> prNumber) {
        return new ReviewScope("rs_1", kind, Path.of("/repo"), Optional.of(Path.of("/wt")),
                "main", "feature/x",
                prNumber.map(number -> new ReviewScope.PullRequestRef(number, Optional.empty())),
                Optional.empty(), Optional.empty());
    }

    @Test
    void theLocalChipSaysLocalChanges() {
        assertEquals("Local changes",
                ReviewScopeSwitcher.chipTextFor(scope(ReviewScope.Kind.WORKTREE, Optional.empty()),
                        Optional.empty()));
    }

    @Test
    void thePullRequestChipNamesTheNumber() {
        assertEquals("PR #42",
                ReviewScopeSwitcher.chipTextFor(scope(ReviewScope.Kind.PR, Optional.of(42)),
                        Optional.empty()));
    }

    @Test
    void openFindingsAppearOnTheChipThatHasThem() {
        assertEquals("PR #42 ◨3",
                ReviewScopeSwitcher.chipTextFor(scope(ReviewScope.Kind.PR, Optional.of(42)),
                        Optional.of(3)));
    }

    @Test
    void noReviewerHavingRunShowsNoCountRatherThanZero() {
        // A confident zero reads as "reviewed, nothing found".
        assertEquals("Local changes",
                ReviewScopeSwitcher.chipTextFor(scope(ReviewScope.Kind.WORKTREE, Optional.empty()),
                        Optional.empty()));
    }
}
