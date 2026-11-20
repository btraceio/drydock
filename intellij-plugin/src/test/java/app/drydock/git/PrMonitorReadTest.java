package app.drydock.git;

import app.drydock.git.GhCliService.PrInfo.PrLifecycle;
import app.drydock.git.PrMonitorRead.Check;
import app.drydock.git.PrMonitorRead.Core;
import app.drydock.git.PrMonitorRead.Mergeable;
import app.drydock.git.PrMonitorRead.PrSnapshot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The monitor's read of one PR: the pure parse of the REST answers
 * ({@code pulls/{n}}, {@code check-runs}, {@code status}, {@code reviews}),
 * and the conditional four-endpoint read around them driven by a scripted
 * gh that answers 200 the first time and 304 once the ETags are back.
 */
class PrMonitorReadTest {

    private static final String SHA = "ba3a460f9b85ef9d6c0db61880904311ded36f81";

    /** The pulls/{n} body with mergeability and lifecycle supplied per test. */
    private static String pull(String extraMembers) {
        return "{\"state\":\"open\",\"merged\":false,\"draft\":false,"
                + "\"base\":{\"ref\":\"main\"},\"head\":{\"ref\":\"topic\",\"sha\":\"" + SHA + "\"}"
                + (extraMembers.isEmpty() ? "" : "," + extraMembers) + "}";
    }

    private static Core core(String body) {
        return assertInstanceOf(Core.class, PrMonitorRead.parsePullCore(body));
    }

    @Test
    void parsesEveryFieldOfARealShapedPull() {
        Core core = core(pull("\"merged\":false,\"mergeable\":false,\"mergeable_state\":\"dirty\""));

        assertEquals(PrLifecycle.OPEN, core.lifecycle());
        assertFalse(core.draft());
        assertEquals("main", core.baseRefName());
        assertEquals("topic", core.headRefName());
        assertEquals(SHA, core.headRefOid());
        assertEquals(Mergeable.CONFLICTING, core.mergeable());
        assertEquals("DIRTY", core.mergeStateStatus());
    }

    @Test
    void aMergedPullIsTheMergedLifecycleNotClosed() {
        assertEquals(PrLifecycle.MERGED, core("{\"state\":\"closed\",\"merged\":true,\"base\":{\"ref\":\"main\"},"
                + "\"head\":{\"ref\":\"topic\",\"sha\":\"" + SHA + "\"}}").lifecycle());
        assertEquals(PrLifecycle.CLOSED, core("{\"state\":\"closed\",\"merged\":false,\"base\":{\"ref\":\"main\"},"
                + "\"head\":{\"ref\":\"topic\",\"sha\":\"" + SHA + "\"}}").lifecycle());
        assertEquals(PrLifecycle.OPEN, core(pull("")).lifecycle());
        assertEquals(PrLifecycle.UNKNOWN, core("{\"merged\":false,\"base\":{\"ref\":\"main\"},"
                + "\"head\":{\"ref\":\"topic\",\"sha\":\"" + SHA + "\"}}").lifecycle());
    }

    @Test
    void mergeabilityKeepsUnknownApartFromMergeable() {
        assertEquals(Mergeable.MERGEABLE, core(pull("\"mergeable\":true")).mergeable());
        assertEquals(Mergeable.CONFLICTING, core(pull("\"mergeable\":false")).mergeable());
        assertEquals(Mergeable.UNKNOWN, core(pull("\"mergeable\":null")).mergeable());
        assertEquals(Mergeable.UNKNOWN, core(pull("")).mergeable());
    }

    @Test
    void mergeStateIsUpperCasedOrUnknown() {
        assertEquals("CLEAN", core(pull("\"mergeable_state\":\"clean\"")).mergeStateStatus());
        assertEquals("BEHIND", core(pull("\"mergeable_state\":\"behind\"")).mergeStateStatus());
        assertEquals("UNSTABLE", core(pull("\"mergeable_state\":\"unstable\"")).mergeStateStatus());
        assertEquals("HAS_HOOKS", core(pull("\"mergeable_state\":\"has_hooks\"")).mergeStateStatus());
        assertEquals("SOMETHING_NEW", core(pull("\"mergeable_state\":\"something_new\"")).mergeStateStatus());
        for (String absent : List.of("", "\"mergeable_state\":null", "\"mergeable_state\":\"  \"")) {
            assertEquals(PrMonitorRead.MERGE_STATE_UNKNOWN, core(pull(absent)).mergeStateStatus(), absent);
        }
    }

    @Test
    void aPullWithoutRefsOrAUsableHeadCommitIsUnusable() {
        for (String body : List.of(
                "{\"state\":\"open\",\"head\":{\"ref\":\"topic\",\"sha\":\"" + SHA + "\"}}",
                "{\"state\":\"open\",\"base\":{\"ref\":\"main\"},\"head\":{\"ref\":\" \",\"sha\":\"" + SHA + "\"}}",
                "{\"state\":\"open\",\"base\":{\"ref\":\"main\"},\"head\":{\"ref\":\"topic\"}}",
                "{\"state\":\"open\",\"base\":{\"ref\":\"main\"},\"head\":{\"ref\":\"topic\",\"sha\":null}}",
                "{\"state\":\"open\",\"base\":{\"ref\":\"main\"},\"head\":{\"ref\":\"topic\",\"sha\":\"--force\"}}",
                "{\"state\":\"open\",\"base\":{\"ref\":\"main\"},\"head\":{\"ref\":\"topic\",\"sha\":\"ba3a460\"}}",
                "{\"state\":\"open\",\"base\":{\"ref\":\"main\"},\"head\":{\"ref\":\"topic\",\"sha\":\""
                        + SHA.toUpperCase() + "\"}}")) {
            assertThrows(PrMonitorRead.Unusable.class, () -> PrMonitorRead.parsePullCore(body), body);
        }
    }

    @Test
    void malformedPullOutputIsUnusableAndNeverAnEmptyAnswer() {
        for (String body : List.of("not json", "", "{\"base\":{\"ref\":\"main\"}", "[" + pull("") + "]")) {
            assertThrows(PrMonitorRead.Unusable.class, () -> PrMonitorRead.parsePullCore(body), body);
        }
    }

    @Test
    void checkRunsAreParsedWithRestSpellingsUpperCased() {
        List<Check> checks = PrMonitorRead.parseCheckRuns("""
                [{"name":"tests","status":"completed","conclusion":"failure",
                  "details_url":"https://github.com/o/r/actions/runs/44315117281/job/134839058118"},
                 {"name":"lint","status":"in_progress","conclusion":null},
                 {"name":"docs","status":"queued","conclusion":"timed_out","details_url":null}]""");

        assertEquals(List.of(
                new Check(Check.Kind.CHECK_RUN, "tests", "COMPLETED", "FAILURE", Optional.empty(),
                        Optional.of("https://github.com/o/r/actions/runs/44315117281/job/134839058118")),
                new Check(Check.Kind.CHECK_RUN, "lint", "IN_PROGRESS", "", Optional.empty(), Optional.empty()),
                new Check(Check.Kind.CHECK_RUN, "docs", "QUEUED", "TIMED_OUT", Optional.empty(), Optional.empty())),
                checks);
        assertEquals(Optional.of(44315117281L), checks.get(0).runId());
        assertEquals(1, checks.stream().filter(Check::failing).count());
    }

    @Test
    void aMissingOrMalformedCheckRunsPageIsNoChecks() {
        assertEquals(List.of(), PrMonitorRead.parseCheckRuns("not json"));
        assertEquals(List.of(), PrMonitorRead.parseCheckRuns(""));
        assertEquals(List.of(), PrMonitorRead.parseCheckRuns("{\"check_runs\":[]}"));
        assertEquals(List.of(), PrMonitorRead.parseCheckRuns("[1,\"FAILURE\",null,[]]"));
        assertTrue(PrMonitorRead.parseCheckRuns("[{\"name\":\"no fields\"}]").stream().noneMatch(Check::failing));
    }

    @Test
    void legacyStatusesAreParsedAsStatusContexts() {
        List<Check> checks = PrMonitorRead.parseLegacyStatuses(
                "{\"state\":\"pending\",\"statuses\":["
                        + "{\"context\":\"DDCI Status\",\"state\":\"pending\",\"target_url\":\"https://ci.example/change/1\"},"
                        + "{\"context\":\"docs\",\"state\":\"error\",\"target_url\":null}]}");

        assertEquals(List.of(
                new Check(Check.Kind.STATUS_CONTEXT, "DDCI Status", "PENDING", "", Optional.empty(),
                        Optional.of("https://ci.example/change/1")),
                new Check(Check.Kind.STATUS_CONTEXT, "docs", "ERROR", "", Optional.empty(), Optional.empty())),
                checks);
        assertEquals(1, checks.stream().filter(Check::failing).count());
    }

    @Test
    void aMissingOrMalformedStatusBodyIsNoStatuses() {
        assertEquals(List.of(), PrMonitorRead.parseLegacyStatuses("not json"));
        assertEquals(List.of(), PrMonitorRead.parseLegacyStatuses("{\"state\":\"pending\"}"));
        assertEquals(List.of(), PrMonitorRead.parseLegacyStatuses("{\"statuses\":null}"));
    }

    @Test
    void reviewsAreParsedByAuthorStateTimeAndBodyAndNonActionableOnesAreSkipped() {
        List<PrMonitorRead.Review> reviews = PrMonitorRead.parseReviews("""
                [{"user":{"login":"alice"},"state":"CHANGES_REQUESTED",
                  "submitted_at":"2026-11-18T10:00:00Z","body":"Please fix the tests."},
                 {"user":{"login":"bob"},"state":"APPROVED","submitted_at":"2026-11-18T11:00:00Z","body":"Nice."},
                 {"user":{"login":"carol"},"state":"PENDING","submitted_at":"2026-11-18T12:00:00Z","body":"draft"},
                 {"user":{"login":"dave"},"state":"DISMISSED","submitted_at":"2026-11-18T13:00:00Z","body":"stale"},
                 {"user":null,"state":"COMMENTED","submitted_at":null,"body":null},
                 42,null]""");

        assertEquals(List.of(
                new PrMonitorRead.Review("alice", "CHANGES_REQUESTED", "2026-11-18T10:00:00Z", "Please fix the tests."),
                new PrMonitorRead.Review("bob", "APPROVED", "2026-11-18T11:00:00Z", "Nice."),
                new PrMonitorRead.Review("", "COMMENTED", "", "")), reviews);
    }

    @Test
    void aMissingOrMalformedReviewsArrayIsNoReviews() {
        assertEquals(List.of(), PrMonitorRead.parseReviews("not json"));
        assertEquals(List.of(), PrMonitorRead.parseReviews("[]"));
    }

    @Test
    void moreReviewsThanTheCapKeepTheLatest() {
        StringBuilder entries = new StringBuilder();
        for (int i = 0; i < PrMonitorRead.MAX_REVIEWS + 5; i++) {
            if (i > 0) {
                entries.append(',');
            }
            entries.append("{\"user\":{\"login\":\"u").append(i).append("\"},\"state\":\"COMMENTED\",")
                    .append("\"submitted_at\":\"t").append(i).append("\",\"body\":\"\"}");
        }
        List<PrMonitorRead.Review> reviews = PrMonitorRead.parseReviews("[" + entries + "]");

        assertEquals(PrMonitorRead.MAX_REVIEWS, reviews.size());
        assertEquals("u" + 5, reviews.get(0).author());
        assertEquals("u" + (PrMonitorRead.MAX_REVIEWS + 4), reviews.get(reviews.size() - 1).author());
    }

    @Test
    void failingMatchesTheBadgeSignal() {
        List<Check> checks = PrMonitorRead.parseCheckRuns("["
                + "{\"name\":\"a\",\"status\":\"completed\",\"conclusion\":\"failure\"},"
                + "{\"name\":\"b\",\"status\":\"completed\",\"conclusion\":\"error\"},"
                + "{\"name\":\"c\",\"status\":\"in_progress\",\"conclusion\":\"failure\"},"
                + "{\"name\":\"d\",\"status\":\"completed\",\"conclusion\":\"success\"},"
                + "{\"name\":\"e\",\"status\":\"completed\",\"conclusion\":\"timed_out\"},"
                + "{\"name\":\"f\",\"status\":\"completed\",\"conclusion\":\"cancelled\"}]");
        List<Check> statuses = PrMonitorRead.parseLegacyStatuses("{\"statuses\":["
                + "{\"context\":\"a\",\"state\":\"failure\"},"
                + "{\"context\":\"b\",\"state\":\"error\"},"
                + "{\"context\":\"c\",\"state\":\"pending\"},"
                + "{\"context\":\"d\",\"state\":\"success\"}]}");

        assertEquals(List.of(true, true, false, false, false, false),
                checks.stream().map(Check::failing).toList());
        assertEquals(List.of(true, true, false, false), statuses.stream().map(Check::failing).toList());
    }

    @Test
    void runIdComesOnlyFromAnActionsRunUrl() {
        assertEquals(Optional.of(44315117281L), runId("https://github.com/o/r/actions/runs/44315117281/job/1"));
        assertEquals(Optional.of(12L), runId("https://github.com/o/r/actions/runs/12"));
        assertEquals(Optional.of(12L), runId("https://github.com/o/r/actions/runs/12?pr=3"));
        assertEquals(Optional.empty(), runId("https://github.com/o/r/pull/3/checks"));
        assertEquals(Optional.empty(), runId("https://github.com/o/r/actions/runs/abc/job/1"));
        assertEquals(Optional.empty(), runId("https://github.com/o/r/actions/runs/0"));
        assertEquals(Optional.empty(), runId("https://github.com/o/r/actions/runs/9999999999999999999"));
        assertEquals(Optional.empty(), runId("https://github.com/o/r/actions/runs/12345678901234567890"));
    }

    private static Optional<Long> runId(String url) {
        return new Check(Check.Kind.CHECK_RUN, "c", "COMPLETED", "FAILURE", Optional.empty(), Optional.of(url))
                .runId();
    }

    @Test
    void theBodyStartsAfterTheFirstBlankLineWhereverTheLineEndingsSit() {
        assertEquals("{\"a\":1}", PrMonitorRead.bodyAfterHeaders("HTTP/2.0 200 OK\nETag: \"e\"\n\n{\"a\":1}"));
        assertEquals("{\"a\":1}",
                PrMonitorRead.bodyAfterHeaders("HTTP/2.0 200 OK\r\nETag: \"e\"\r\n\r\n{\"a\":1}"));
        assertEquals("", PrMonitorRead.bodyAfterHeaders("HTTP/2.0 304 Not Modified\nETag: \"e\"\n"));
    }

    @Test
    void theEtagHeaderIsFoundCaseInsensitivelyAndOnlyInTheHeaders() {
        assertEquals(Optional.of("\"e1\""), PrMonitorRead.etagOf("HTTP/2.0 200 OK\nETag: \"e1\"\n\nbody"));
        assertEquals(Optional.of("\"e2\""), PrMonitorRead.etagOf("HTTP/2.0 200 OK\nEtag: \"e2\"\n\nbody"));
        assertEquals(Optional.empty(), PrMonitorRead.etagOf("HTTP/2.0 200 OK\nX-Other: etag: no\n\nbody"));
        assertEquals(Optional.empty(), PrMonitorRead.etagOf("HTTP/2.0 200 OK\n\nbody"));
    }

    @Test
    void aNonPositivePrNumberIsRefused() {
        try (PrMonitorRead read = new PrMonitorRead()) {
            for (int number : new int[] {0, -1}) {
                assertThrows(IllegalArgumentException.class, () -> read.read(Path.of("."), Optional.empty(), number));
                assertThrows(IllegalArgumentException.class, () -> PrMonitorRead.readWith(Optional.empty(),
                        Path.of("."), Optional.empty(), number, Duration.ofSeconds(15), Map.of()));
            }
        }
    }

    @Test
    void missingGhIsUnsupported() {
        assertInstanceOf(PrSnapshot.Unsupported.class, PrMonitorRead.readWith(Optional.empty(), Path.of("."),
                Optional.of("o/r"), 7, Duration.ofSeconds(15), Map.of()));
    }

    @Test
    void anInjectedExecutorIsNotShutDownOnClose() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            new PrMonitorRead(executor).close();
            assertFalse(executor.isShutdown());
        } finally {
            executor.shutdownNow();
        }
    }

    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void oneReadAssemblesTheSnapshotFromTheFourEndpoints(@TempDir Path dir) throws Exception {
        Path receipt = dir.resolve("receipt.txt");
        Path gh = GhCliPullRequestListingTest.scriptedGh(dir, apiScript(receipt, false));

        PrSnapshot.Snapshot snapshot = assertInstanceOf(PrSnapshot.Snapshot.class,
                PrMonitorRead.readWith(Optional.of(gh), dir, Optional.of("o/r"), 42,
                        Duration.ofSeconds(15), new HashMap<>()));

        assertEquals(42, snapshot.prNumber());
        assertEquals(PrLifecycle.OPEN, snapshot.lifecycle());
        assertFalse(snapshot.draft());
        assertEquals("main", snapshot.baseRefName());
        assertEquals("topic", snapshot.headRefName());
        assertEquals(SHA, snapshot.headRefOid());
        assertEquals(Mergeable.CONFLICTING, snapshot.mergeable());
        assertEquals("DIRTY", snapshot.mergeStateStatus());
        assertEquals(List.of(
                new Check(Check.Kind.CHECK_RUN, "tests", "COMPLETED", "FAILURE", Optional.empty(),
                        Optional.of("https://github.com/o/r/actions/runs/44315117281/job/134839058118")),
                new Check(Check.Kind.STATUS_CONTEXT, "DDCI Status", "PENDING", "", Optional.empty(),
                        Optional.of("https://ci.example/change/1"))), snapshot.checks());
        assertEquals(List.of(new PrMonitorRead.Review("alice", "APPROVED", "2026-11-18T11:00:00Z", "Nice.")),
                snapshot.reviews());
        assertEquals(List.of(
                "args=api repos/o/r/pulls/42 --include",
                "args=api repos/o/r/commits/" + SHA + "/check-runs?per_page=100&page=1 --include",
                "args=api repos/o/r/commits/" + SHA + "/status --include",
                "args=api repos/o/r/pulls/42/reviews?per_page=100&page=1 --include"), Files.readAllLines(receipt));
    }

    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void aHostQualifiedRepositoryNamesItsHost(@TempDir Path dir) throws Exception {
        Path receipt = dir.resolve("receipt.txt");
        Path gh = GhCliPullRequestListingTest.scriptedGh(dir, apiScript(receipt, false));

        PrSnapshot.Snapshot snapshot = assertInstanceOf(PrSnapshot.Snapshot.class,
                PrMonitorRead.readWith(Optional.of(gh), dir, Optional.of("ghe.example.com/o/r"), 42,
                        Duration.ofSeconds(15), new HashMap<>()));

        assertEquals(42, snapshot.prNumber());
        assertTrue(Files.readAllLines(receipt).get(0).contains("--hostname ghe.example.com"));
    }

    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void anUnchangedEndpointReusesItsCachedAnswer(@TempDir Path dir) throws Exception {
        Path receipt = dir.resolve("receipt.txt");
        Path gh = GhCliPullRequestListingTest.scriptedGh(dir, apiScript(receipt, true));

        Map<String, PrMonitorRead.CachedResponse> etags = new HashMap<>();
        PrSnapshot first = PrMonitorRead.readWith(Optional.of(gh), dir, Optional.empty(), 42,
                Duration.ofSeconds(15), etags);
        PrSnapshot second = PrMonitorRead.readWith(Optional.of(gh), dir, Optional.empty(), 42,
                Duration.ofSeconds(15), etags);

        assertInstanceOf(PrSnapshot.Snapshot.class, first);
        assertEquals(first, second, "a 304-answered poll reads as the same snapshot");
        List<String> calls = Files.readAllLines(receipt);
        assertEquals(8, calls.size(), "the second read still asked all four endpoints, conditionally");
        for (int i = 4; i < 8; i++) {
            assertTrue(calls.get(i).contains("If-None-Match"), calls.get(i));
        }
    }

    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void stdinIsDiscardedSoAPromptCannotHangTheRead(@TempDir Path dir) throws Exception {
        Path gh = GhCliPullRequestListingTest.scriptedGh(dir, "cat > /dev/null\n" + apiScript(null, false));

        assertInstanceOf(PrSnapshot.Snapshot.class,
                PrMonitorRead.readWith(Optional.of(gh), dir, Optional.empty(), 42, Duration.ofSeconds(10),
                        new HashMap<>()));
    }

    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void anInvalidRepositoryFailsWithoutRunningGh(@TempDir Path dir) throws Exception {
        Path receipt = dir.resolve("receipt.txt");
        Path gh = GhCliPullRequestListingTest.scriptedGh(dir, "touch " + receipt + "\n");

        for (String repository : List.of("--repo", "o", "o/r/x/y", "o/ r", "o/-r")) {
            assertInstanceOf(PrSnapshot.Failed.class, PrMonitorRead.readWith(Optional.of(gh), dir,
                    Optional.of(repository), 7, Duration.ofSeconds(15), new HashMap<>()), repository);
        }
        assertFalse(Files.exists(receipt));
    }

    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void aFailedEndpointFailsTheWholeReadWithTheStderrExcerpt(@TempDir Path dir) throws Exception {
        Path gh = GhCliPullRequestListingTest.scriptedGh(dir, "echo 'HTTP 502: bad gateway' >&2\nexit 1\n");

        assertEquals("HTTP 502: bad gateway", assertInstanceOf(PrSnapshot.Failed.class,
                PrMonitorRead.readWith(Optional.of(gh), dir, Optional.empty(), 7, Duration.ofSeconds(15),
                        new HashMap<>())).message());
    }

    /**
     * A scripted {@code gh} answering the monitor's four endpoints. With
     * {@code conditionally}, any call carrying {@code If-None-Match} is
     * answered 304 (gh's real behavior: non-2xx exit, headers on stdout, no
     * body); without it, every call is answered 200 with a fresh body.
     */
    private static String apiScript(Path receipt, boolean conditionally) {
        String log = receipt == null ? "" : "printf 'args=%s\\n' \"$*\" >> " + receipt + "\n";
        String conditional = conditionally
                ? "case \"$*\" in *If-None-Match*) "
                    + "printf 'HTTP/2.0 304 Not Modified\\nEtag: \"e1\"\\n\\n'; exit 1;; esac\n"
                : "";
        return log + conditional + "case \"$2\" in\n"
                + "  pulls/42|repos/o/r/pulls/42)\n"
                + "    printf 'HTTP/2.0 200 OK\\nEtag: \"ep\"\\n\\n%s' '"
                + pull("\"mergeable\":false,\"mergeable_state\":\"dirty\"") + "'\n    ;;\n"
                + "  *" + SHA + "/check-runs*)\n"
                + "    printf 'HTTP/2.0 200 OK\\nEtag: \"ec\"\\n\\n%s' "
                + "'[{\"name\":\"tests\",\"status\":\"completed\",\"conclusion\":\"failure\","
                + "\"details_url\":\"https://github.com/o/r/actions/runs/44315117281/job/134839058118\"}]'\n"
                + "    ;;\n"
                + "  *" + SHA + "/status)\n"
                + "    printf 'HTTP/2.0 200 OK\\nEtag: \"es\"\\n\\n%s' "
                + "'{\"state\":\"pending\",\"statuses\":[{\"context\":\"DDCI Status\",\"state\":\"pending\","
                + "\"target_url\":\"https://ci.example/change/1\"}]}'\n    ;;\n"
                + "  *pulls/42/reviews*)\n"
                + "    printf 'HTTP/2.0 200 OK\\nEtag: \"er\"\\n\\n%s' "
                + "'[{\"user\":{\"login\":\"alice\"},\"state\":\"APPROVED\",\"submitted_at\":\"2026-11-18T11:00:00Z\","
                + "\"body\":\"Nice.\"}]'\n    ;;\n"
                + "  *) echo \"unexpected call: $*\" >&2; exit 1 ;;\n"
                + "esac\n";
    }
}
