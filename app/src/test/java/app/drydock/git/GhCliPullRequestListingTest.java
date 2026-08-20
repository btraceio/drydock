package app.drydock.git;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pure half of the open-PR listing: what {@code gh pr list --json} is
 * turned into. Driven directly so the two failures the sidebar has to tell
 * apart -- gh absent, gh broken -- are both reachable without a gh on PATH.
 */
class GhCliPullRequestListingTest {

    @Test
    void parsesEveryFieldARowRenders() {
        String stdout = """
                [{"number":42,"title":"Teach the parser about tabs","headRefName":"fix/tabs",
                  "baseRefName":"main","isDraft":false,
                  "author":{"login":"octocat"},"url":"https://github.com/o/r/pull/42"}]
                """;

        GhCliService.PullRequestListing listing = GhCliService.parsePullRequestListing(stdout);

        GhCliService.OpenPullRequest pr =
                assertInstanceOf(GhCliService.PullRequestListing.Listed.class, listing).pullRequests().get(0);
        assertEquals(42, pr.number());
        assertEquals("Teach the parser about tabs", pr.title());
        assertEquals("fix/tabs", pr.headRefName());
        assertEquals("main", pr.baseRefName());
        assertEquals(false, pr.draft());
        assertEquals("octocat", pr.author().orElseThrow());
        assertEquals("https://github.com/o/r/pull/42", pr.url().orElseThrow());
    }

    @Test
    void keepsDraftsSoTheCallerCanDecide() {
        String stdout = """
                [{"number":7,"title":"WIP","headRefName":"wip","baseRefName":"main","isDraft":true}]
                """;

        GhCliService.PullRequestListing listing = GhCliService.parsePullRequestListing(stdout);

        List<GhCliService.OpenPullRequest> prs =
                assertInstanceOf(GhCliService.PullRequestListing.Listed.class, listing).pullRequests();
        assertEquals(1, prs.size());
        assertTrue(prs.get(0).draft());
    }

    @Test
    void unparseableOutputIsAFailureNotAnEmptyList() {
        GhCliService.PullRequestListing listing = GhCliService.parsePullRequestListing("not json");

        assertInstanceOf(GhCliService.PullRequestListing.Failed.class, listing);
    }

    @Test
    void aRowMissingRequiredFieldsIsSkippedRatherThanFailingTheRest() {
        String stdout = """
                [{"number":1,"headRefName":"a","baseRefName":"main","isDraft":false},
                 {"number":2,"title":"Good","headRefName":"b","baseRefName":"main","isDraft":false}]
                """;

        GhCliService.PullRequestListing listing = GhCliService.parsePullRequestListing(stdout);

        List<GhCliService.OpenPullRequest> prs =
                assertInstanceOf(GhCliService.PullRequestListing.Listed.class, listing).pullRequests();
        assertEquals(1, prs.size());
        assertEquals(2, prs.get(0).number());
    }
}
