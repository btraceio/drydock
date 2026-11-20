package app.drydock.git;

import app.drydock.git.GhCliService.CiFailure;
import app.drydock.state.json.JsonParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void executablePathKeepsShimsButAddsLocationsTheyCanDelegateTo() {
        String separator = File.pathSeparator;

        assertEquals(String.join(separator, "/usr/local/bin", "/usr/bin", "/opt/homebrew/bin"),
                GhCliService.pathWithFallbacks(String.join(separator, "/usr/local/bin", "/usr/bin")));
        assertEquals(String.join(separator, "/opt/homebrew/bin", "/usr/local/bin"),
                GhCliService.pathWithFallbacks(String.join(separator, "/opt/homebrew/bin", "/usr/local/bin")),
                "known locations already on PATH must not be duplicated");
    }

    @Test
    void onlyAnExplicitConflictingMergeableMarksAConflict() {
        String stdout = """
                [{"number":1,"title":"a","headRefName":"a","baseRefName":"main","mergeable":"CONFLICTING"},
                 {"number":2,"title":"b","headRefName":"b","baseRefName":"main","mergeable":"MERGEABLE"},
                 {"number":3,"title":"c","headRefName":"c","baseRefName":"main","mergeable":"UNKNOWN"},
                 {"number":4,"title":"d","headRefName":"d","baseRefName":"main"}]
                """;

        List<GhCliService.OpenPullRequest> prs = assertInstanceOf(GhCliService.PullRequestListing.Listed.class,
                GhCliService.parsePullRequestListing(stdout)).pullRequests();

        assertEquals(List.of(true, false, false, false),
                prs.stream().map(GhCliService.OpenPullRequest::conflicting).toList());
    }

    /**
     * Rollup fixtures and the failures they carry: legacy StatusContext
     * {@code state}, completed CheckRun {@code conclusion}, and everything
     * that must not read as a failure.
     */
    static Stream<Arguments> rollups() {
        return Stream.of(
                Arguments.of("missing", null, Set.of()),
                Arguments.of("null", "null", Set.of()),
                Arguments.of("not an array", "{\"state\":\"FAILURE\"}", Set.of()),
                Arguments.of("empty", "[]", Set.of()),
                Arguments.of("malformed entries", "[1, \"FAILURE\", null, []]", Set.of()),
                Arguments.of("legacy failure", "[{\"__typename\":\"StatusContext\",\"state\":\"FAILURE\"}]",
                        Set.of(CiFailure.FAILURE)),
                Arguments.of("legacy error", "[{\"__typename\":\"StatusContext\",\"state\":\"ERROR\"}]",
                        Set.of(CiFailure.ERROR)),
                Arguments.of("legacy pending/success", "[{\"__typename\":\"StatusContext\",\"state\":\"PENDING\"},"
                        + "{\"__typename\":\"StatusContext\",\"state\":\"SUCCESS\"},"
                        + "{\"__typename\":\"StatusContext\",\"state\":\"EXPECTED\"}]", Set.of()),
                Arguments.of("check run failure", "[{\"__typename\":\"CheckRun\",\"status\":\"COMPLETED\","
                        + "\"conclusion\":\"FAILURE\"}]", Set.of(CiFailure.FAILURE)),
                Arguments.of("check run error", "[{\"__typename\":\"CheckRun\",\"status\":\"COMPLETED\","
                        + "\"conclusion\":\"ERROR\"}]", Set.of(CiFailure.ERROR)),
                Arguments.of("check run execution status is not a result",
                        "[{\"__typename\":\"CheckRun\",\"status\":\"FAILURE\",\"conclusion\":\"\"}]", Set.of()),
                Arguments.of("check run still running", "[{\"__typename\":\"CheckRun\",\"status\":\"IN_PROGRESS\","
                        + "\"conclusion\":\"FAILURE\"},{\"__typename\":\"CheckRun\",\"status\":\"QUEUED\","
                        + "\"conclusion\":\"ERROR\"}]", Set.of()),
                Arguments.of("check run with missing status", "[{\"__typename\":\"CheckRun\","
                        + "\"conclusion\":\"FAILURE\"}]", Set.of()),
                Arguments.of("check run reads conclusion not state", "[{\"__typename\":\"CheckRun\","
                        + "\"status\":\"COMPLETED\",\"state\":\"FAILURE\",\"conclusion\":\"SUCCESS\"}]", Set.of()),
                Arguments.of("other conclusions", "[" + String.join(",", Stream.of("SUCCESS", "CANCELLED", "SKIPPED",
                        "NEUTRAL", "ACTION_REQUIRED", "TIMED_OUT", "STALE", "STARTUP_FAILURE", "failure")
                        .map(c -> "{\"__typename\":\"CheckRun\",\"status\":\"COMPLETED\",\"conclusion\":\"" + c + "\"}")
                        .toList()) + "]", Set.of()),
                Arguments.of("unknown entry type", "[{\"__typename\":\"Mystery\",\"state\":\"FAILURE\"},"
                        + "{\"__typename\":\"Mystery\",\"status\":\"COMPLETED\",\"conclusion\":\"ERROR\"}]", Set.of()),
                Arguments.of("untyped entries go by shape", "[{\"state\":\"ERROR\"},"
                        + "{\"status\":\"COMPLETED\",\"conclusion\":\"FAILURE\"}]",
                        Set.of(CiFailure.FAILURE, CiFailure.ERROR)),
                Arguments.of("mixed: failure not hidden by passing and pending", "["
                        + "{\"__typename\":\"CheckRun\",\"status\":\"COMPLETED\",\"conclusion\":\"SUCCESS\"},"
                        + "{\"__typename\":\"CheckRun\",\"status\":\"IN_PROGRESS\",\"conclusion\":null},"
                        + "{\"__typename\":\"StatusContext\",\"state\":\"PENDING\"},"
                        + "{\"__typename\":\"CheckRun\",\"status\":\"COMPLETED\",\"conclusion\":\"FAILURE\"}]",
                        Set.of(CiFailure.FAILURE)),
                Arguments.of("both kinds retained", "["
                        + "{\"__typename\":\"StatusContext\",\"state\":\"ERROR\"},"
                        + "{\"__typename\":\"CheckRun\",\"status\":\"COMPLETED\",\"conclusion\":\"FAILURE\"}]",
                        Set.of(CiFailure.FAILURE, CiFailure.ERROR)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rollups")
    void ciFailuresComeOnlyFromExplicitTerminalFailureSignals(String name, String rollup, Set<CiFailure> expected)
            throws Exception {
        assertEquals(expected, GhCliService.ciFailuresOf(rollup == null ? null : JsonParser.parse(rollup)), name);

        String member = rollup == null ? "" : ",\"statusCheckRollup\":" + rollup;
        String stdout = "[{\"number\":1,\"title\":\"t\",\"headRefName\":\"h\",\"baseRefName\":\"main\""
                + member + "}]";
        GhCliService.OpenPullRequest pr = assertInstanceOf(GhCliService.PullRequestListing.Listed.class,
                GhCliService.parsePullRequestListing(stdout)).pullRequests().get(0);
        assertEquals(expected, pr.ciFailures(), name);
        assertTrue(!pr.conflicting(), "CI failure is independent of mergeability");
    }

    @Test
    void conflictAndCiFailureAreIndependentAndCarriedTogether() {
        String stdout = """
                [{"number":1,"title":"a","headRefName":"a","baseRefName":"main","mergeable":"CONFLICTING",
                  "statusCheckRollup":[{"__typename":"StatusContext","state":"ERROR"},
                    {"__typename":"CheckRun","status":"COMPLETED","conclusion":"FAILURE"}]},
                 {"number":2,"title":"b","headRefName":"b","baseRefName":"main","mergeable":"CONFLICTING",
                  "statusCheckRollup":[]},
                 {"number":3,"title":"c","headRefName":"c","baseRefName":"main","mergeable":null,"isDraft":true,
                  "statusCheckRollup":[{"__typename":"StatusContext","state":"FAILURE"}]}]
                """;

        List<GhCliService.OpenPullRequest> prs = assertInstanceOf(GhCliService.PullRequestListing.Listed.class,
                GhCliService.parsePullRequestListing(stdout)).pullRequests();

        assertEquals(List.of(true, true, false), prs.stream().map(GhCliService.OpenPullRequest::conflicting).toList());
        assertEquals(List.of(Set.of(CiFailure.FAILURE, CiFailure.ERROR), Set.of(), Set.of(CiFailure.FAILURE)),
                prs.stream().map(GhCliService.OpenPullRequest::ciFailures).toList());
        assertTrue(prs.get(2).draft(), "lifecycle/draft data survives next to the failure data");
    }

    @Test
    void legacyConstructorsMeanNoConflictAndNoFailureSignal() {
        GhCliService.OpenPullRequest seven = new GhCliService.OpenPullRequest(1, "t", "h", "main", false,
                Optional.empty(), Optional.empty());
        GhCliService.OpenPullRequest eight = new GhCliService.OpenPullRequest(1, "t", "h", "main", false,
                Optional.empty(), Optional.empty(), true);

        assertEquals(false, seven.conflicting());
        assertEquals(Set.of(), seven.ciFailures());
        assertEquals(true, eight.conflicting());
        assertEquals(Set.of(), eight.ciFailures());
        assertEquals(new GhCliService.OpenPullRequest(1, "t", "h", "main", false, Optional.empty(),
                Optional.empty(), true, Set.of()), eight);
    }

    @Test
    void failureSetIsAnImmutableCopyAndPartOfEquality() {
        Set<CiFailure> source = new HashSet<>(Set.of(CiFailure.ERROR));
        GhCliService.OpenPullRequest pr = new GhCliService.OpenPullRequest(1, "t", "h", "main", false,
                Optional.empty(), Optional.empty(), false, source);
        source.add(CiFailure.FAILURE);

        assertEquals(Set.of(CiFailure.ERROR), pr.ciFailures());
        assertThrows(UnsupportedOperationException.class, () -> pr.ciFailures().add(CiFailure.FAILURE));
        assertTrue(!pr.equals(new GhCliService.OpenPullRequest(1, "t", "h", "main", false,
                Optional.empty(), Optional.empty(), false, Set.of())), "a status-only change is a different answer");
    }

    @Test
    void listingOutputIsCappedAtEightMebibytesPerStream() {
        assertEquals(8 * 1024 * 1024, GhCliService.PR_LIST_MAX_OUTPUT_BYTES);
    }

    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void theListingQueryIsLightOnHeavyFields(@TempDir Path dir) throws Exception {
        Path receipt = dir.resolve("receipt.txt");
        Path gh = scriptedGh(dir, "printf 'args=%s\\n' \"$*\" >> " + receipt + "\n"
                + "printf '%s' '[{\"number\":5,\"title\":\"t\",\"headRefName\":\"h\","
                + "\"baseRefName\":\"main\"}]'\n");

        GhCliService.PullRequestListing listing = GhCliService.openPullRequestsWith(Optional.of(gh), dir,
                Optional.of("o/r"), Duration.ofSeconds(15));

        List<String> calls = Files.readAllLines(receipt);
        assertEquals(List.of("args=pr list --state open --limit 100 --json "
                + "number,title,headRefName,baseRefName,isDraft,author,url --repo o/r"), calls,
                "one light listing call: the heavy badge fields ride the slower badge listing alone");
        GhCliService.OpenPullRequest pr = assertInstanceOf(GhCliService.PullRequestListing.Listed.class, listing)
                .pullRequests().get(0);
        assertEquals(Set.of(), pr.ciFailures());
        assertTrue(!pr.conflicting(), "a light listing row carries no signal, not a healthy one");
    }

    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void theSearchQueryRidesArgvBeforeTheLimit(@TempDir Path dir) throws Exception {
        Path receipt = dir.resolve("receipt.txt");
        Path gh = scriptedGh(dir, "printf 'args=%s\\n' \"$*\" >> " + receipt + "\n"
                + "printf '%s' '[{\"number\":9,\"title\":\"old\",\"headRefName\":\"h\",\"baseRefName\":\"main\"}]'\n");

        GhCliService.PullRequestListing listing = GhCliService.searchPullRequestsWith(Optional.of(gh), dir,
                Optional.of("o/r"), "login token", Duration.ofSeconds(15));

        // The query rides --search and the limit stays behind it: the server
        // bounds the matches, not the repository.
        assertEquals(List.of("args=pr list --state open --search login token --limit 100 --json "
                + "number,title,headRefName,baseRefName,isDraft,author,url --repo o/r"),
                Files.readAllLines(receipt));
        assertEquals(9, assertInstanceOf(GhCliService.PullRequestListing.Listed.class, listing)
                .pullRequests().get(0).number());
    }

    @Test
    void missingGhMakesTheSearchedListingUnsupportedToo() {
        assertInstanceOf(GhCliService.PullRequestListing.Unsupported.class,
                GhCliService.searchPullRequestsWith(Optional.empty(), Path.of("."), Optional.empty(),
                        "query", Duration.ofSeconds(15)));
    }

    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void theDetailLookupIsLightLikeTheListings(@TempDir Path dir) throws Exception {
        Path receipt = dir.resolve("receipt.txt");
        Path gh = scriptedGh(dir, "printf 'args=%s\\n' \"$*\" >> " + receipt + "\n"
                + "printf '%s' '{\"number\":500,\"title\":\"old\",\"headRefName\":\"h\",\"baseRefName\":\"main\"}'\n");

        Optional<GhCliService.OpenPullRequest> answer =
                GhCliService.pullRequestDetailWith(gh, dir, Optional.of("o/r"), 500);

        assertEquals(List.of("args=pr view 500 --json "
                + "number,title,headRefName,baseRefName,isDraft,author,url --repo o/r"),
                Files.readAllLines(receipt));
        assertEquals(500, answer.orElseThrow().number());
        assertEquals("old", answer.orElseThrow().title());
    }

    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void aDetailLookupOfAnUnknownNumberAnswersEmptyWithAWarning(@TempDir Path dir) throws Exception {
        Path gh = scriptedGh(dir, "echo 'could not find pull request' >&2\nexit 1\n");
        List<LogRecord> warnings = new ArrayList<>();

        Optional<GhCliService.OpenPullRequest> answer = warningsDuring(warnings, () ->
                GhCliService.pullRequestDetailWith(gh, dir, Optional.empty(), 999999));

        assertTrue(answer.isEmpty());
        assertEquals(1, warnings.size(), warnings::toString);
    }

    @Test
    void missingGhIsUnsupportedAndNotAWarning() throws Exception {
        List<LogRecord> warnings = new ArrayList<>();
        GhCliService.PullRequestListing listing = warningsDuring(warnings, () -> GhCliService.openPullRequestsWith(
                Optional.empty(), Path.of("."), Optional.empty(), Duration.ofSeconds(15)));

        assertInstanceOf(GhCliService.PullRequestListing.Unsupported.class, listing);
        assertEquals(List.of(), warnings);
    }

    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void aNonzeroExitFailsWithTheStderrExcerptAndAWarning(@TempDir Path dir) throws Exception {
        Path gh = scriptedGh(dir, "echo 'gh: not logged into any host' >&2\nexit 1\n");
        List<LogRecord> warnings = new ArrayList<>();

        GhCliService.PullRequestListing listing = warningsDuring(warnings, () -> GhCliService.openPullRequestsWith(
                Optional.of(gh), dir, Optional.empty(), Duration.ofSeconds(15)));

        assertEquals("gh: not logged into any host",
                assertInstanceOf(GhCliService.PullRequestListing.Failed.class, listing).message());
        assertTrue(warnings.stream().anyMatch(r -> r.getMessage().contains("exited 1")
                && r.getMessage().contains("not logged in")), warnings::toString);
        assertTrue(warnings.stream().noneMatch(r -> r.getMessage().contains("retrying")), warnings::toString);
    }

    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void aServerSideErrorIsAFailedListingNotARetry(@TempDir Path dir) throws Exception {
        // The listing no longer carries the fields a reduced retry drops;
        // its one bounded run fails, and the failed-listing backoff in the
        // task tree governs the refetch.
        Path receipt = dir.resolve("receipt.txt");
        Path gh = scriptedGh(dir, "printf 'args=%s\\n' \"$*\" >> " + receipt + "\n"
                + "echo 'HTTP 504: 504 Gateway Timeout (https://api.github.com/graphql)' >&2\nexit 1\n");
        List<LogRecord> warnings = new ArrayList<>();

        GhCliService.PullRequestListing listing = warningsDuring(warnings, () -> GhCliService.openPullRequestsWith(
                Optional.of(gh), dir, Optional.empty(), Duration.ofSeconds(15)));

        assertInstanceOf(GhCliService.PullRequestListing.Failed.class, listing);
        assertEquals(1, Files.readAllLines(receipt).size(), "no reduced-field retry: nothing to drop");
        assertTrue(warnings.stream().noneMatch(r -> r.getMessage().contains("retrying")), warnings::toString);
    }

    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void aRateLimitDoesNotRetryTheListing(@TempDir Path dir) throws Exception {
        Path receipt = dir.resolve("receipt.txt");
        Path gh = scriptedGh(dir, "printf 'args=%s\\n' \"$*\" >> " + receipt + "\n"
                + "echo 'API rate limit exceeded' >&2\nexit 1\n");
        List<LogRecord> warnings = new ArrayList<>();

        GhCliService.PullRequestListing listing = warningsDuring(warnings, () -> GhCliService.openPullRequestsWith(
                Optional.of(gh), dir, Optional.empty(), Duration.ofSeconds(15)));

        assertInstanceOf(GhCliService.PullRequestListing.Failed.class, listing);
        assertEquals(1, Files.readAllLines(receipt).size(), "a quota failure does not issue a reduced-query retry");
        assertTrue(warnings.stream().noneMatch(r -> r.getMessage().contains("retrying")), warnings::toString);
    }

    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void aTimeoutFailsTheListingWithoutARetry(@TempDir Path dir) throws Exception {
        Path receipt = dir.resolve("receipt.txt");
        Path gh = scriptedGh(dir, "printf 'args=%s\\n' \"$*\" >> " + receipt + "\nexec sleep 30\n");
        List<LogRecord> warnings = new ArrayList<>();

        GhCliService.PullRequestListing listing = warningsDuring(warnings, () -> GhCliService.openPullRequestsWith(
                Optional.of(gh), dir, Optional.empty(), Duration.ofMillis(300)));

        assertInstanceOf(GhCliService.PullRequestListing.Failed.class, listing);
        assertEquals(1, Files.readAllLines(receipt).size(), "no reduced-field retry: nothing to drop");
        assertTrue(warnings.stream().anyMatch(r -> r.getMessage().contains("timed out")), warnings::toString);
    }

    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void outputOverflowFailsTheLightListingWithoutARetry(@TempDir Path dir) throws Exception {
        Path receipt = dir.resolve("receipt.txt");
        Path gh = scriptedGh(dir, "printf 'args=%s\\n' \"$*\" >> " + receipt + "\n"
                + "head -c " + (GhCliService.PR_LIST_MAX_OUTPUT_BYTES + 1024) + " /dev/zero\n");
        List<LogRecord> warnings = new ArrayList<>();

        GhCliService.PullRequestListing listing = warningsDuring(warnings, () -> GhCliService.openPullRequestsWith(
                Optional.of(gh), dir, Optional.empty(), Duration.ofSeconds(15)));

        assertInstanceOf(GhCliService.PullRequestListing.Failed.class, listing);
        assertEquals(1, Files.readAllLines(receipt).size(),
                "no reduced-field retry: the light listing has no fields to drop");
        assertTrue(warnings.stream().anyMatch(r -> r.getMessage().contains("did not run to completion")));
    }

    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void launchFailureDoesNotRetryTheListing(@TempDir Path dir) throws Exception {
        List<LogRecord> warnings = new ArrayList<>();
        Path nonexistentGh = dir.resolve("missing-gh");

        GhCliService.PullRequestListing listing = warningsDuring(warnings, () -> GhCliService.openPullRequestsWith(
                Optional.of(nonexistentGh), dir, Optional.empty(), Duration.ofSeconds(15)));

        assertInstanceOf(GhCliService.PullRequestListing.Failed.class, listing);
        assertEquals(1, warnings.size(), warnings::toString);
        assertTrue(warnings.get(0).getMessage().contains("did not run to completion"), warnings::toString);
        assertTrue(!warnings.get(0).getMessage().contains("retrying"), warnings::toString);
    }

    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void outputPastTheCapFailsWithAWarningNotAFailingCheck(@TempDir Path dir) throws Exception {
        Path gh = scriptedGh(dir, "head -c " + (GhCliService.PR_LIST_MAX_OUTPUT_BYTES + 1024) + " /dev/zero\n");
        List<LogRecord> warnings = new ArrayList<>();

        GhCliService.PullRequestListing listing = warningsDuring(warnings, () -> GhCliService.openPullRequestsWith(
                Optional.of(gh), dir, Optional.empty(), Duration.ofSeconds(15)));

        assertInstanceOf(GhCliService.PullRequestListing.Failed.class, listing);
        assertTrue(warnings.stream().anyMatch(r -> r.getMessage().contains("did not run to completion")
                && r.getMessage().contains("exceeded")), warnings::toString);
    }

    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void aTimeoutFailsWithADistinctWarning(@TempDir Path dir) throws Exception {
        Path gh = scriptedGh(dir, "exec sleep 30\n");
        List<LogRecord> warnings = new ArrayList<>();

        GhCliService.PullRequestListing listing = warningsDuring(warnings, () -> GhCliService.openPullRequestsWith(
                Optional.of(gh), dir, Optional.empty(), Duration.ofMillis(300)));

        assertInstanceOf(GhCliService.PullRequestListing.Failed.class, listing);
        assertTrue(warnings.stream().anyMatch(r -> r.getMessage().contains("timed out")), warnings::toString);
    }

    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void malformedListingIsAFailureNeverAFailingCheck(@TempDir Path dir) throws Exception {
        Path gh = scriptedGh(dir, "printf '%s' '[{\"number\":1,\"statusCheckRollup\":[{\"state\":\"FAILURE\"'\n");

        assertInstanceOf(GhCliService.PullRequestListing.Failed.class, GhCliService.openPullRequestsWith(
                Optional.of(gh), dir, Optional.empty(), Duration.ofSeconds(15)));
    }

    @Test
    void deeplyNestedRollupFailsTheParseSafely() {
        String deep = "[".repeat(10_000) + "]".repeat(10_000);
        String stdout = "[{\"number\":1,\"title\":\"t\",\"headRefName\":\"h\",\"baseRefName\":\"main\","
                + "\"statusCheckRollup\":" + deep + "}]";

        assertInstanceOf(GhCliService.PullRequestListing.Failed.class, GhCliService.parsePullRequestListing(stdout));
    }

    /** A fake gh in {@code dir/bin} running {@code body} under {@code /bin/sh}. */
    static Path scriptedGh(Path dir, String body) throws IOException {
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Path gh = bin.resolve("gh");
        Files.writeString(gh, "#!/bin/sh\n" + body);
        assertTrue(gh.toFile().setExecutable(true));
        return gh;
    }

    /** Runs {@code body}, collecting the WARNING+ records {@link GhCliService} logs meanwhile. */
    static <T> T warningsDuring(List<LogRecord> into, Callable<T> body) throws Exception {
        Logger logger = Logger.getLogger(GhCliService.class.getName());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    synchronized (into) {
                        into.add(record);
                    }
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        try {
            return body.call();
        } finally {
            logger.removeHandler(handler);
        }
    }
}
