package app.drydock.git;

import app.drydock.git.GhCliService.BranchPullRequest;
import app.drydock.git.GhCliService.CiFailure;
import app.drydock.git.GhCliService.PrInfo.PrLifecycle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.LogRecord;

import static app.drydock.git.GhCliPullRequestListingTest.scriptedGh;
import static app.drydock.git.GhCliPullRequestListingTest.warningsDuring;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    @Test
    void carriesConflictAndCiFailuresIndependentlyAndStillSkipsForks() {
        String stdout = """
                [{"number":1,"state":"OPEN","headRefName":"a","title":"a","mergeable":"CONFLICTING",
                  "statusCheckRollup":[{"__typename":"StatusContext","state":"ERROR"},
                    {"__typename":"CheckRun","status":"COMPLETED","conclusion":"FAILURE"}]},
                 {"number":2,"state":"MERGED","headRefName":"b","title":"b","mergeable":"UNKNOWN",
                  "statusCheckRollup":[{"__typename":"CheckRun","status":"COMPLETED","conclusion":"FAILURE"}]},
                 {"number":3,"state":"OPEN","headRefName":"c","title":"c","mergeable":"MERGEABLE",
                  "statusCheckRollup":[{"__typename":"CheckRun","status":"IN_PROGRESS","conclusion":null},
                    {"__typename":"CheckRun","status":"COMPLETED","conclusion":"CANCELLED"},
                    {"__typename":"StatusContext","state":"SUCCESS"}]},
                 {"number":4,"state":"OPEN","headRefName":"d","title":"d"},
                 {"number":5,"state":"OPEN","headRefName":"main","title":"fork","mergeable":"CONFLICTING",
                  "isCrossRepository":true,
                  "statusCheckRollup":[{"__typename":"StatusContext","state":"FAILURE"}]}]
                """;

        List<BranchPullRequest> parsed = GhCliService.parseBranchPullRequests(stdout);

        assertEquals(List.of(
                new BranchPullRequest(1, PrLifecycle.OPEN, false, "a", "a", Optional.empty(),
                        true, Set.of(CiFailure.FAILURE, CiFailure.ERROR)),
                new BranchPullRequest(2, PrLifecycle.MERGED, false, "b", "b", Optional.empty(),
                        false, Set.of(CiFailure.FAILURE)),
                new BranchPullRequest(3, PrLifecycle.OPEN, false, "c", "c", Optional.empty(), false, Set.of()),
                new BranchPullRequest(4, PrLifecycle.OPEN, false, "d", "d", Optional.empty(), false, Set.of())),
                parsed, "fork PRs stay excluded whatever their health");
    }

    @Test
    void theLegacyConstructorMeansNoConflictAndNoFailureSignal() {
        BranchPullRequest legacy = new BranchPullRequest(1, PrLifecycle.OPEN, false, "a", "t", Optional.empty());

        assertEquals(false, legacy.conflicting());
        assertEquals(Set.of(), legacy.ciFailures());
        assertNotEquals(new BranchPullRequest(1, PrLifecycle.OPEN, false, "a", "t", Optional.empty(),
                false, Set.of(CiFailure.ERROR)), legacy, "a status-only change is a different answer");
    }

    @Test
    void healthDoesNotChangeWhichPullRequestABranchResolvesTo() {
        BranchPullRequest failingMerged = new BranchPullRequest(10, PrLifecycle.MERGED, false, "x", "t",
                Optional.empty(), true, Set.of(CiFailure.FAILURE));
        BranchPullRequest healthyOpen = pr(7, PrLifecycle.OPEN, "x");
        BranchPullRequest olderOpen = new BranchPullRequest(3, PrLifecycle.OPEN, false, "y", "t",
                Optional.empty(), false, Set.of(CiFailure.ERROR));
        BranchPullRequest newerOpen = pr(4, PrLifecycle.OPEN, "y");

        Map<String, BranchPullRequest> byBranch =
                GhCliService.byHeadBranch(List.of(failingMerged, healthyOpen, olderOpen, newerOpen));

        assertEquals(healthyOpen, byBranch.get("x"));
        assertEquals(newerOpen, byBranch.get("y"));
    }

    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void theOneBatchedQueryAsksForMergeabilityAndRollup(@TempDir Path dir) throws Exception {
        Path receipt = dir.resolve("receipt.txt");
        Path gh = scriptedGh(dir, "printf 'args=%s\\n' \"$*\" >> " + receipt + "\n"
                + "printf '%s' '[{\"number\":5,\"state\":\"OPEN\",\"headRefName\":\"h\",\"title\":\"t\","
                + "\"mergeable\":\"CONFLICTING\",\"statusCheckRollup\":[{\"__typename\":\"CheckRun\","
                + "\"status\":\"COMPLETED\",\"conclusion\":\"FAILURE\"}]}]'\n");

        Map<String, BranchPullRequest> byBranch = GhCliService.branchPullRequestsWith(Optional.of(gh), dir,
                Optional.of("o/r"), Duration.ofSeconds(15));

        assertEquals(List.of("args=pr list --state all --limit 100 --json "
                + "number,state,isDraft,headRefName,title,url,isCrossRepository,mergeable,statusCheckRollup --repo o/r"),
                Files.readAllLines(receipt), "one listing call, no per-PR checks fan-out");
        assertEquals(new BranchPullRequest(5, PrLifecycle.OPEN, false, "h", "t", Optional.empty(),
                true, Set.of(CiFailure.FAILURE)), byBranch.get("h"));
    }

    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void anInvalidRepositoryNeverReachesGh(@TempDir Path dir) throws Exception {
        Path receipt = dir.resolve("receipt.txt");
        Path gh = scriptedGh(dir, "printf 'args=%s\\n' \"$*\" >> " + receipt + "\n");

        assertEquals(Map.of(), GhCliService.branchPullRequestsWith(Optional.of(gh), dir,
                Optional.of("--repo=evil"), Duration.ofSeconds(15)));
        assertTrue(!Files.exists(receipt));
    }

    @Test
    void missingGhReadsAsNoBadgesWithoutAWarning() throws Exception {
        List<LogRecord> warnings = new ArrayList<>();

        Map<String, BranchPullRequest> byBranch = warningsDuring(warnings, () -> GhCliService.branchPullRequestsWith(
                Optional.empty(), Path.of("."), Optional.empty(), Duration.ofSeconds(15)));

        assertEquals(Map.of(), byBranch);
        assertEquals(List.of(), warnings);
    }

    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void executionFailuresReadAsNoBadgesWithDistinctWarnings(@TempDir Path dir) throws Exception {
        List<LogRecord> nonzero = new ArrayList<>();
        Path failing = scriptedGh(dir.resolve("nonzero"), "echo 'gh: not authenticated' >&2\nexit 4\n");
        assertEquals(Map.of(), warningsDuring(nonzero, () -> GhCliService.branchPullRequestsWith(
                Optional.of(failing), dir, Optional.empty(), Duration.ofSeconds(15))));
        assertTrue(nonzero.stream().anyMatch(r -> r.getMessage().contains("exited 4")
                && r.getMessage().contains("not authenticated")), nonzero::toString);

        List<LogRecord> overflow = new ArrayList<>();
        Path flooding = scriptedGh(dir.resolve("overflow"),
                "head -c " + (GhCliService.PR_LIST_MAX_OUTPUT_BYTES + 1024) + " /dev/zero\n");
        assertEquals(Map.of(), warningsDuring(overflow, () -> GhCliService.branchPullRequestsWith(
                Optional.of(flooding), dir, Optional.empty(), Duration.ofSeconds(15))));
        assertTrue(overflow.stream().anyMatch(r -> r.getMessage().contains("exceeded")), overflow::toString);

        List<LogRecord> timeout = new ArrayList<>();
        Path hanging = scriptedGh(dir.resolve("timeout"), "exec sleep 30\n");
        assertEquals(Map.of(), warningsDuring(timeout, () -> GhCliService.branchPullRequestsWith(
                Optional.of(hanging), dir, Optional.empty(), Duration.ofMillis(300))));
        assertTrue(timeout.stream().anyMatch(r -> r.getMessage().contains("timed out")), timeout::toString);
    }
}
