package app.drydock.review;

import app.drydock.git.GhCliService;
import app.drydock.git.WorktreeService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which pull requests earn a sidebar row: the open, non-draft ones with no
 * local worktree. A PR that is checked out is a badge on its worktree's row,
 * and listing it in both places is how two lists of the same thing start
 * disagreeing.
 */
class RepositoryPullRequestsTest {

    private static GhCliService.OpenPullRequest pr(int number, String head, boolean draft) {
        return new GhCliService.OpenPullRequest(number, "PR " + number, head, "main", draft,
                Optional.empty(), Optional.empty());
    }

    private static WorktreeService.Worktree worktree(String path, String branch) {
        return new WorktreeService.Worktree(Path.of(path), Optional.of(branch),
                false, false, false, false, Optional.empty());
    }

    @Test
    void draftsNeverGetARow() {
        List<GhCliService.OpenPullRequest> selected = RepositoryPullRequests.selectable(
                List.of(pr(1, "ready", false), pr(2, "wip", true)), List.of());

        assertEquals(List.of(1), selected.stream().map(GhCliService.OpenPullRequest::number).toList());
    }

    @Test
    void aPullRequestCheckedOutUnderItsOwnBranchIsExcluded() {
        List<GhCliService.OpenPullRequest> selected = RepositoryPullRequests.selectable(
                List.of(pr(1, "fix/tabs", false)),
                List.of(worktree("/tmp/wt-tabs", "fix/tabs")));

        assertTrue(selected.isEmpty());
    }

    @Test
    void aPullRequestCheckedOutUnderTheDrydockAliasIsExcluded() {
        // Drydock checks a PR out as pr-<n>, so the branch name no longer
        // matches headRefName -- the number is what identifies it.
        List<GhCliService.OpenPullRequest> selected = RepositoryPullRequests.selectable(
                List.of(pr(42, "someones-fork-branch", false)),
                List.of(worktree("/tmp/wt-42", "pr-42")));

        assertTrue(selected.isEmpty());
    }

    @Test
    void anUncheckedOutPullRequestSurvives() {
        List<GhCliService.OpenPullRequest> selected = RepositoryPullRequests.selectable(
                List.of(pr(9, "feature/x", false)),
                List.of(worktree("/tmp/wt-other", "unrelated")));

        assertEquals(1, selected.size());
        assertEquals(9, selected.get(0).number());
    }

    @Test
    void ghMissingIsAbsentNotUnavailable() throws ExecutionException, InterruptedException {
        RepositoryPullRequests service = new RepositoryPullRequests(
                root -> CompletableFuture.completedFuture(new GhCliService.PullRequestListing.Unsupported()));

        RepositoryPullRequests.Outcome outcome =
                service.scan(Path.of("/tmp/repo"), List.of()).get();

        assertInstanceOf(RepositoryPullRequests.Outcome.Absent.class, outcome);
    }

    @Test
    void ghFailingIsUnavailableAndCarriesTheReason() throws ExecutionException, InterruptedException {
        RepositoryPullRequests service = new RepositoryPullRequests(root ->
                CompletableFuture.completedFuture(
                        new GhCliService.PullRequestListing.Failed("gh: not authenticated")));

        RepositoryPullRequests.Outcome outcome =
                service.scan(Path.of("/tmp/repo"), List.of()).get();

        assertEquals("gh: not authenticated",
                assertInstanceOf(RepositoryPullRequests.Outcome.Unavailable.class, outcome).message());
    }

    @Test
    void aFailedFutureIsUnavailableRatherThanAThrow() throws ExecutionException, InterruptedException {
        RepositoryPullRequests service = new RepositoryPullRequests(root ->
                CompletableFuture.failedFuture(new IllegalStateException("boom")));

        RepositoryPullRequests.Outcome outcome =
                service.scan(Path.of("/tmp/repo"), List.of()).get();

        assertInstanceOf(RepositoryPullRequests.Outcome.Unavailable.class, outcome);
    }
}
