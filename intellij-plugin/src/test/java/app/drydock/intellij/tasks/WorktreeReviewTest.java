package app.drydock.intellij.tasks;

import app.drydock.domain.PullRequestTarget;
import app.drydock.git.GhCliService;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorktreeReviewTest {

    /** The label names the branch it reviews — the worktree's own identity, never a PR number. */
    @Test
    void namedAfterTheBranch() {
        assertEquals("Review feat/balloon", WorktreeReview.displayName("feat/balloon"));
    }

    @Test
    void missingTargetDoesNotPreventReviewTargetResolution() throws InterruptedException {
        CompletableFuture<Optional<GhCliService.BranchPullRequest>> lookup = new CompletableFuture<>();
        lookup.complete(Optional.empty());

        assertTrue(WorktreeReview.resolveTarget(lookup, 123, 1, TimeUnit.SECONDS).isEmpty());
    }

    @Test
    void failedAndTimedOutLookupsFallBackToNoTarget() throws InterruptedException {
        CompletableFuture<Optional<GhCliService.BranchPullRequest>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("lookup failed"));
        assertTrue(WorktreeReview.resolveTarget(failed, 123, 1, TimeUnit.SECONDS).isEmpty());

        assertTrue(WorktreeReview.resolveTarget(new CompletableFuture<>(), 123, 0, TimeUnit.MILLISECONDS).isEmpty());
    }

    @Test
    void malformedPrUrlFallsBackToNoTarget() throws InterruptedException {
        CompletableFuture<Optional<GhCliService.BranchPullRequest>> lookup = new CompletableFuture<>();
        lookup.complete(Optional.of(pullRequest("not a pull request URL")));

        assertTrue(WorktreeReview.resolveTarget(lookup, 123, 1, TimeUnit.SECONDS).isEmpty());
    }

    @Test
    void successfulLookupPreservesTheTarget() throws InterruptedException {
        String url = "https://github.com/example/project/pull/123";
        CompletableFuture<Optional<GhCliService.BranchPullRequest>> lookup = new CompletableFuture<>();
        lookup.complete(Optional.of(pullRequest(url)));

        PullRequestTarget target = WorktreeReview.resolveTarget(lookup, 123, 1, TimeUnit.SECONDS).orElseThrow();

        assertEquals(123, target.number());
        assertEquals(url, target.url());
    }

    @Test
    void interruptionIsPropagatedAndRestored() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class,
                    () -> WorktreeReview.resolveTarget(new CompletableFuture<>(), 123, 1, TimeUnit.SECONDS));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private static GhCliService.BranchPullRequest pullRequest(String url) {
        return new GhCliService.BranchPullRequest(123, GhCliService.PrInfo.PrLifecycle.OPEN, false,
                "pr-123", "Example", Optional.of(url));
    }
}
