package app.drydock.intellij.review;

import app.drydock.git.UnifiedDiff;
import app.drydock.review.ReviewScope;
import com.intellij.diff.fragments.LineFragment;
import com.intellij.diff.fragments.LineFragmentImpl;
import com.intellij.diff.util.Side;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pure half of {@link ReviewDiffContents}: which side backs the right
 * content, and the fragment computer's guard against an edited file.
 */
class ReviewDiffContentsTest {

    private static final List<LineFragment> GIT = List.of(new LineFragmentImpl(1, 2, 1, 2, 2, 4, 2, 4, null));
    private static final List<LineFragment> PLATFORM = List.of(new LineFragmentImpl(0, 1, 0, 1, 0, 2, 0, 2, null));
    private static final ReviewDiffRequests.Plan PLAN =
            new ReviewDiffRequests.Plan("a\nb\n", "a\nB\n", GIT, Side.RIGHT, 1);

    @Test
    void aDocumentHoldingTheReviewedTextBacksTheRightSide() {
        assertEquals(ReviewDiffContents.RightSide.FILE, ReviewDiffContents.choose("a\nB\n", "a\nB\n"));
    }

    @Test
    void aDocumentThatDiffersAnywhereIsASnapshot() {
        // Outside the diff rows too: the fragments are offsets into the reviewed text.
        assertEquals(ReviewDiffContents.RightSide.SNAPSHOT, ReviewDiffContents.choose("a\nB\nc\n", "a\nB\n"));
        assertEquals(ReviewDiffContents.RightSide.SNAPSHOT, ReviewDiffContents.choose("A\nB\n", "a\nB\n"));
    }

    @Test
    void noDocumentIsASnapshot() {
        assertEquals(ReviewDiffContents.RightSide.SNAPSHOT, ReviewDiffContents.choose(null, "a\n"));
    }

    @Test
    void theReviewedTextsGetTheGitFragments() {
        ReviewDiffContents.Staleness staleness = new ReviewDiffContents.Staleness();
        List<LineFragment> result = ReviewDiffContents.fragments(PLAN, "a\nb\n", "a\nB\n", staleness,
                () -> PLATFORM);
        assertSame(GIT, result);
        assertFalse(staleness.isStale());
    }

    @Test
    void anEditedRightSideGetsThePlatformComparisonAndTurnsStaleOnce() {
        ReviewDiffContents.Staleness staleness = new ReviewDiffContents.Staleness();
        AtomicInteger fired = new AtomicInteger();
        staleness.onStale(fired::incrementAndGet);
        assertSame(PLATFORM, ReviewDiffContents.fragments(PLAN, "a\nb\n", "x\na\nB\n", staleness, () -> PLATFORM));
        assertSame(PLATFORM, ReviewDiffContents.fragments(PLAN, "a\nb\n", "y\na\nB\n", staleness, () -> PLATFORM));
        assertTrue(staleness.isStale());
        assertEquals(1, fired.get(), "listeners fire on the first mark only");
    }

    @Test
    void theWorktreeFileIsTheDiffRootsFile() {
        Path root = Path.of("/work/tree");
        ReviewScope worktree = scope(ReviewScope.Kind.WORKTREE, Optional.of(root));
        assertEquals(Optional.of(root.resolve("src/A.java")),
                ReviewTabProvider.worktreeFileOf(worktree, file("src/A.java", "M")));
    }

    @Test
    void aDeletedFileHasNoWorktreeFile() {
        ReviewScope worktree = scope(ReviewScope.Kind.WORKTREE, Optional.of(Path.of("/work/tree")));
        assertEquals(Optional.empty(), ReviewTabProvider.worktreeFileOf(worktree, file("src/A.java", "D")));
    }

    @Test
    void aBaseScopeWithoutItsOwnCheckoutHasNoWorktreeFile() {
        ReviewScope pr = scope(ReviewScope.Kind.PR, Optional.empty());
        assertEquals(Optional.empty(), ReviewTabProvider.worktreeFileOf(pr, file("src/A.java", "M")));
        ReviewScope checkedOut = scope(ReviewScope.Kind.PR, Optional.of(Path.of("/pr/tree")));
        assertEquals(Optional.of(Path.of("/pr/tree/src/A.java")),
                ReviewTabProvider.worktreeFileOf(checkedOut, file("src/A.java", "M")));
    }

    private static ReviewScope scope(ReviewScope.Kind kind, Optional<Path> worktree) {
        Optional<ReviewScope.PullRequestRef> pr = kind == ReviewScope.Kind.PR
                ? Optional.of(new ReviewScope.PullRequestRef(930, Optional.empty()))
                : Optional.empty();
        return new ReviewScope("s1", kind, Path.of("/repo"), worktree, "main", "feat",
                pr, Optional.empty(), Optional.empty());
    }

    private static UnifiedDiff.FileDiff file(String path, String kind) {
        return new UnifiedDiff.FileDiff(path, kind, 1, 0, false, false, List.of());
    }
}
