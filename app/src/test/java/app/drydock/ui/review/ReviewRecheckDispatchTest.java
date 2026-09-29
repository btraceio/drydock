package app.drydock.ui.review;

import app.drydock.review.BaseMove;
import app.drydock.review.HunkDigest;
import app.drydock.review.ReviewVerdict;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The automatic recheck as a FEATURE, not as parts.
 *
 * <p>{@link SectionStatesTest} drives {@code requestRechecks} directly, which
 * leaves the wiring untested: deleting the call from {@link
 * SessionReviewView#refreshReviewState} left the whole review-UI suite green,
 * so the feature could be made completely inert without a single failure.
 * These tests go through a real render.</p>
 */
class ReviewRecheckDispatchTest extends ReviewViewFixture {

    private static final String OLD_BASE = "0".repeat(40);

    /** The render pass must actually ask. Nothing else pins that it is called. */
    @Test
    void aRenderDispatchesTheRecheckForAStaleApproval() {
        approveFileAAtAnOlderBase();

        render();

        assertEquals(List.of(OLD_BASE + "->" + host.baseCommit), host.recheckDispatches);
    }

    /** One claim per move, however many times the board re-renders. */
    @Test
    void manyRendersInsideOneMoveAskOnce() {
        approveFileAAtAnOlderBase();

        render();
        render();
        render();

        assertEquals(1, host.recheckDispatches.size());
    }

    /** Spec §9.7, through the render: an inline harness is never asked. */
    @Test
    void anInlineHarnessIsNeverAskedByARender() {
        host.supportsAutomaticRecheck = false;
        approveFileAAtAnOlderBase();

        render();

        assertTrue(host.recheckDispatches.isEmpty());
    }

    private void approveFileAAtAnOlderBase() {
        host.baseDelta = new BaseMove.Delta(false, new TreeSet<>(List.of(FILE_A)));
        host.store.putVerdict(new ReviewVerdict(scope.id(), digestOfFirstHunkOfFileA(),
                ReviewVerdict.Decision.APPROVED, Optional.empty(), Instant.EPOCH,
                OLD_BASE, host.headCommit));
    }

    private void render() {
        interact(() -> view.refreshReviewState());
        WaitForAsyncUtils.waitForFxEvents();
    }
}
