package app.drydock.ui.model;

import app.drydock.domain.RepositoryId;
import app.drydock.git.GhCliService;
import app.drydock.review.RepositoryPullRequests;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless tests for {@link WorkspaceViewModel}'s per-repository pull-request
 * store: reads back what was set, and follows the model's diff/notify
 * pattern -- an unchanged scan stays silent.
 */
class WorkspaceViewModelPullRequestsTest {

    private final RepositoryId repositoryId = RepositoryId.newId();

    private static GhCliService.OpenPullRequest pullRequest(int number, String headRefName) {
        return new GhCliService.OpenPullRequest(
                number, "title", headRefName, "main", false, Optional.empty(), Optional.empty());
    }

    @Test
    void pullRequestsAreEmptyUntilAScanLands() {
        assertTrue(new WorkspaceViewModel().pullRequests(repositoryId).isEmpty());
    }

    @Test
    void aScanResultIsReadBackAsGiven() {
        WorkspaceViewModel model = new WorkspaceViewModel();
        RepositoryPullRequests.Outcome outcome =
                new RepositoryPullRequests.Outcome.Rows(List.of(pullRequest(42, "fix/tabs")));

        model.setPullRequests(repositoryId, outcome);

        assertEquals(outcome, model.pullRequests(repositoryId).orElseThrow());
    }

    @Test
    void anUnchangedScanDoesNotNotifyListeners() {
        // The scan re-runs on every rescan; a notification per scan would
        // rebuild the tree for nothing.
        WorkspaceViewModel model = new WorkspaceViewModel();
        RepositoryPullRequests.Outcome outcome =
                new RepositoryPullRequests.Outcome.Rows(List.of(pullRequest(42, "fix/tabs")));
        model.setPullRequests(repositoryId, outcome);
        AtomicInteger notifications = new AtomicInteger();
        model.addListener(new WorkspaceViewModel.Listener() {
            @Override
            public void structureChanged() {
                notifications.incrementAndGet();
            }
        });

        model.setPullRequests(repositoryId,
                new RepositoryPullRequests.Outcome.Rows(List.of(pullRequest(42, "fix/tabs"))));

        assertEquals(0, notifications.get());
    }

    @Test
    void removingARepositoryDropsItsPullRequests() {
        WorkspaceViewModel model = new WorkspaceViewModel();
        model.setPullRequests(repositoryId,
                new RepositoryPullRequests.Outcome.Rows(List.of(pullRequest(42, "fix/tabs"))));

        model.removeRepository(repositoryId);

        assertTrue(model.pullRequests(repositoryId).isEmpty());
    }
}
