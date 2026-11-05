package app.drydock.git;

import app.drydock.git.GhCliService.BranchPullRequest;
import app.drydock.git.GhCliService.PrInfo.PrLifecycle;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pure half of the session-badge lookup: what {@code gh pr list --state
 * all --json} is turned into, and which PR a head branch resolves to.
 */
class GhCliBranchPullRequestsTest {

    @Test
    void parsesEveryFieldABadgeShowsAndSkipsForks() {
        String stdout = """
                [{"number":42,"state":"OPEN","isDraft":true,"headRefName":"fix/tabs",
                  "title":"Teach the parser about tabs","url":"https://github.com/o/r/pull/42",
                  "isCrossRepository":false},
                 {"number":43,"state":"OPEN","isDraft":false,"headRefName":"main",
                  "title":"From a fork","url":"https://github.com/f/r/pull/43","isCrossRepository":true},
                 {"state":"OPEN","headRefName":"no-number"}]
                """;

        List<BranchPullRequest> parsed = GhCliService.parseBranchPullRequests(stdout);

        assertEquals(List.of(new BranchPullRequest(42, PrLifecycle.OPEN, true, "fix/tabs",
                "Teach the parser about tabs", Optional.of("https://github.com/o/r/pull/42"))), parsed);
    }

    @Test
    void unparseableOutputReadsAsNoPullRequests() {
        assertTrue(GhCliService.parseBranchPullRequests("not json").isEmpty());
        assertTrue(GhCliService.parseBranchPullRequests("{}").isEmpty());
    }

    @Test
    void anOpenPullRequestBeatsAnEarlierMergedOneOnTheSameBranch() {
        BranchPullRequest merged = pr(10, PrLifecycle.MERGED, "feature/x");
        BranchPullRequest open = pr(7, PrLifecycle.OPEN, "feature/x");
        BranchPullRequest olderMerged = pr(3, PrLifecycle.MERGED, "feature/y");
        BranchPullRequest newerClosed = pr(5, PrLifecycle.CLOSED, "feature/y");

        Map<String, BranchPullRequest> byBranch =
                GhCliService.byHeadBranch(List.of(merged, open, olderMerged, newerClosed));

        assertEquals(open, byBranch.get("feature/x"), "open wins over merged, whatever the numbers");
        assertEquals(newerClosed, byBranch.get("feature/y"), "among non-open PRs the newer number wins");
        assertEquals(2, byBranch.size());
    }

    private static BranchPullRequest pr(int number, PrLifecycle state, String head) {
        return new BranchPullRequest(number, state, false, head, "t" + number, Optional.empty());
    }
}
