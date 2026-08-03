package app.drydock.ui.review;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Four situations produce an empty rail, and rendering one as another is
 * how a reader learns to distrust it: a failed diff is not "still loading",
 * and a worktree with nothing changed is not "not checked out".
 */
class ReviewIntentRailEmptyStateTest {

    @Test
    void everyEmptyReasonHasItsOwnSentence() {
        assertEquals("Diffing…", ReviewIntentRail.Empty.DIFFING.message());
        assertEquals("Not checked out — check out to group changes",
                ReviewIntentRail.Empty.NOT_CHECKED_OUT.message());
        assertEquals("Could not diff — see the message beside this",
                ReviewIntentRail.Empty.DIFF_FAILED.message());
        assertEquals("No changes", ReviewIntentRail.Empty.NO_CHANGES.message());
    }

    @Test
    void theNonEmptyCaseHasNoMessageAtAll() {
        assertEquals("", ReviewIntentRail.Empty.NONE.message());
    }
}
