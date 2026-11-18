package app.drydock.git;

import app.drydock.git.GhCliService.IssueListing;
import app.drydock.git.GhCliService.OpenIssue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GhCliIssueListingTest {

    @Test
    void parsesIssueDetailsUsedByTheProjectTree() {
        IssueListing listing = GhCliService.parseIssueListing("""
                [{"number":42,"title":"Fix allocation accounting","url":"https://github.com/acme/api/issues/42",
                  "updatedAt":"2026-10-20T10:15:30Z","labels":[{"name":"bug"},{"name":"profiling"}],
                  "assignees":[{"login":"octocat"}]}]
                """);

        OpenIssue issue = assertInstanceOf(IssueListing.Listed.class, listing).issues().getFirst();
        assertEquals(42, issue.number());
        assertEquals("Fix allocation accounting", issue.title());
        assertEquals(List.of("bug", "profiling"), issue.labels());
        assertEquals(List.of("octocat"), issue.assignees());
        assertEquals(Instant.parse("2026-10-20T10:15:30Z"), issue.updatedAt().orElseThrow());
    }

    @Test
    void malformedRowsAreSkippedAndOptionalDetailsStayOptional() {
        IssueListing listing = GhCliService.parseIssueListing("""
                [{"number":7,"title":"Useful"},{"number":"bad","title":"Skip"},{"number":8}]
                """);

        List<OpenIssue> issues = assertInstanceOf(IssueListing.Listed.class, listing).issues();
        assertEquals(1, issues.size());
        assertTrue(issues.getFirst().url().isEmpty());
        assertTrue(issues.getFirst().updatedAt().isEmpty());
    }

    @Test
    void malformedTopLevelOutputIsAFailureNotAnEmptyTracker() {
        assertInstanceOf(IssueListing.Failed.class, GhCliService.parseIssueListing("not json"));
        assertInstanceOf(IssueListing.Failed.class, GhCliService.parseIssueListing("{}"));
    }

    @Test
    void repositoryOverridesCannotBecomeGhOptions() {
        assertTrue(GhCliService.validRepository("DataDog/profiling-backend"));
        assertTrue(GhCliService.validRepository("github.example.com/team/repo"));
        assertFalse(GhCliService.validRepository("--help"));
        assertFalse(GhCliService.validRepository("team/-repo"));
        assertFalse(GhCliService.validRepository("team/repo/extra/path"));
        assertFalse(GhCliService.validRepository("team/repo with spaces"));
    }

    @Test
    void aRepositoryWithIssuesDisabledIsNotPresentedAsAnError() {
        assertTrue(GhCliService.issuesDisabled("the 'acme/api' repository has disabled issues"));
        assertTrue(GhCliService.issuesDisabled("Issues are disabled for this repository"));
        assertFalse(GhCliService.issuesDisabled("HTTP 503: service unavailable"));
    }

    /** A fake gh in {@code dir/bin} running {@code body} under {@code /bin/sh}. */
    private static Path scriptedGh(Path dir, String body) throws IOException {
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Path gh = bin.resolve("gh");
        Files.writeString(gh, "#!/bin/sh\n" + body);
        assertTrue(gh.toFile().setExecutable(true));
        return gh;
    }

    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void theIssueSearchQueryRidesArgvBeforeTheLimit(@TempDir Path dir) throws Exception {
        Path receipt = dir.resolve("receipt.txt");
        Path gh = scriptedGh(dir, "printf 'args=%s\\n' \"$*\" >> " + receipt + "\n"
                + "printf '%s' '[{\"number\":7,\"title\":\"found\"}]'\n");

        IssueListing listing = GhCliService.searchIssuesWith(Optional.of(gh), dir,
                Optional.of("o/r"), "allocation");

        // The query rides --search and the limit stays behind it: the server
        // bounds the matches, not the repository.
        assertEquals(List.of("args=issue list --state open --search allocation --limit 100 --json "
                + "number,title,url,labels,assignees,updatedAt --repo o/r"), Files.readAllLines(receipt));
        assertEquals(7, assertInstanceOf(IssueListing.Listed.class, listing).issues().getFirst().number());
    }

    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void anIssueSearchOverARepositoryWithIssuesDisabledIsUnsupported(@TempDir Path dir) throws Exception {
        Path gh = scriptedGh(dir, "echo 'the acme/api repository has disabled issues' >&2\nexit 1\n");

        assertInstanceOf(IssueListing.Unsupported.class,
                GhCliService.searchIssuesWith(Optional.of(gh), dir, Optional.empty(), "allocation"));
    }
}
