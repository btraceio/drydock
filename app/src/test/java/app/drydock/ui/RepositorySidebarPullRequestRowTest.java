package app.drydock.ui;

import app.drydock.git.GhCliService;
import app.drydock.review.RepositoryPullRequests;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RepositorySidebarPullRequestRowTest {

    private static GhCliService.OpenPullRequest pr(int number, String title, String author) {
        return new GhCliService.OpenPullRequest(number, title, "head", "main", false,
                Optional.of(author), Optional.empty());
    }

    @Test
    void theGroupCountsWhatItHolds() {
        assertEquals("PULL REQUESTS (2)", RepositorySidebar.pullRequestGroupLabel(
                new RepositoryPullRequests.Outcome.Rows(List.of(pr(1, "a", "x"), pr(2, "b", "y")))));
    }

    @Test
    void anUnavailableScanSaysSoAndOffersARetry() {
        assertEquals("PULL REQUESTS — unavailable · retry",
                RepositorySidebar.pullRequestGroupLabel(
                        new RepositoryPullRequests.Outcome.Unavailable("gh: not authenticated")));
    }

    @Test
    void aRowIsTheNumberTheTitleAndWhoOpenedIt() {
        assertEquals("#42  Teach the parser about tabs · @octocat",
                RepositorySidebar.pullRequestRowText(pr(42, "Teach the parser about tabs", "octocat")));
    }

    @Test
    void anAuthorlessRowDropsTheAuthorRatherThanShowingAnEmptyOne() {
        GhCliService.OpenPullRequest anonymous = new GhCliService.OpenPullRequest(
                7, "Bump deps", "head", "main", false, Optional.empty(), Optional.empty());

        assertEquals("#7  Bump deps", RepositorySidebar.pullRequestRowText(anonymous));
    }
}
