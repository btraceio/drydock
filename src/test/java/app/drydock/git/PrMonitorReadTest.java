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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

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

    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void a200WithoutAnEtagStillAnswersTheRead(@TempDir Path dir) throws Exception {
        Path receipt = dir.resolve("receipt.txt");
        Path gh = GhCliPullRequestListingTest.scriptedGh(dir,
                "printf 'args=%s\\n' \"$*\" >> " + receipt + "\n" + noEtagScript());

        Map<String, PrMonitorRead.CachedResponse> etags = new HashMap<>();
        PrSnapshot first = PrMonitorRead.readWith(Optional.of(gh), dir, Optional.empty(), 42,
                Duration.ofSeconds(15), etags);
        PrSnapshot second = PrMonitorRead.readWith(Optional.of(gh), dir, Optional.empty(), 42,
                Duration.ofSeconds(15), etags);

        assertInstanceOf(PrSnapshot.Snapshot.class, first);
        assertEquals(first, second, "an ETag-less endpoint re-reads in full every poll, never fails");
        assertTrue(etags.isEmpty(), "nothing was cached without a validator");
        List<String> calls = Files.readAllLines(receipt);
        assertEquals(8, calls.size(), "two full reads: four endpoints each, none conditional");
        assertTrue(calls.stream().noneMatch(call -> call.contains("If-None-Match")), calls::toString);
    }

    /** A full four-endpoint script whose 200 answers carry no ETag header at all. */
    private static String noEtagScript() {
        return "case \"$2\" in\n"
                + "  pulls/42)\n"
                + "    printf 'HTTP/2.0 200 OK\\n\\n%s' '" + pull("\"mergeable\":false,\"mergeable_state\":\"dirty\"") + "'\n"
                + "    ;;\n"
                + "  *" + SHA + "/check-runs*)\n"
                + "    printf 'HTTP/2.0 200 OK\\n\\n%s' '{\"total_count\":1,\"check_runs\":[]}'\n"
                + "    ;;\n"
                + "  *" + SHA + "/status)\n"
                + "    printf 'HTTP/2.0 200 OK\\n\\n%s' '{\"state\":\"pending\",\"statuses\":[]}'\n"
                + "    ;;\n"
                + "  *pulls/42/reviews*)\n"
                + "    printf 'HTTP/2.0 200 OK\\n\\n%s' '[]';\n"
                + "    ;;\n"
                + "esac\n";
    }

    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void aDifferentHostNeverReusesAnotherHostsCache(@TempDir Path dir) throws Exception {
        Path receipt = dir.resolve("receipt.txt");
        Path gh = GhCliPullRequestListingTest.scriptedGh(dir, apiScript(receipt, true));

        Map<String, PrMonitorRead.CachedResponse> etags = new HashMap<>();
        assertInstanceOf(PrSnapshot.Snapshot.class, PrMonitorRead.readWith(Optional.of(gh), dir,
                Optional.of("ghe1.example.com/o/r"), 42, Duration.ofSeconds(15), etags));
        assertInstanceOf(PrSnapshot.Snapshot.class, PrMonitorRead.readWith(Optional.of(gh), dir,
                Optional.of("ghe1.example.com/o/r"), 42, Duration.ofSeconds(15), etags));
        assertInstanceOf(PrSnapshot.Snapshot.class, PrMonitorRead.readWith(Optional.of(gh), dir,
                Optional.of("ghe2.example.com/o/r"), 42, Duration.ofSeconds(15), etags));

        List<String> calls = Files.readAllLines(receipt);
        assertEquals(12, calls.size(), "three reads; the third host starts cold");
        for (int i = 4; i < 8; i++) {
            assertTrue(calls.get(i).contains("If-None-Match"), "warm second read: " + calls.get(i));
        }
        for (int i = 8; i < 12; i++) {
            assertFalse(calls.get(i).contains("If-None-Match"),
                    "the second host must not reuse the first host's validators: " + calls.get(i));
        }
    }

    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void aPushDropsTheDeadCommitScopeCacheEntries(@TempDir Path dir) throws Exception {
        String sha2 = "c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d0";
        Path pulls = dir.resolve("pulls.json");
        Path gh = GhCliPullRequestListingTest.scriptedGh(dir,
                "case \"$2\" in\n"
                + "  pulls/42)\n"
                + "    printf 'HTTP/2.0 200 OK\\nEtag: \"p\"\\n\\n%s' \"$(cat " + pulls + ")\"\n"
                + "    ;;\n"
                + "  *" + SHA + "*)\n"
                + "    printf 'HTTP/2.0 200 OK\\nEtag: \"c1\"\\n\\n%s' '[]'\n"
                + "    ;;\n"
                + "  *" + sha2 + "*)\n"
                + "    printf 'HTTP/2.0 200 OK\\nEtag: \"c2\"\\n\\n%s' '[]'\n"
                + "    ;;\n"
                + "  *reviews*)\n"
                + "    printf 'HTTP/2.0 200 OK\\nEtag: \"rv\"\\n\\n%s' '[]'\n"
                + "    ;;\n"
                + "  *) echo \"unexpected: $*\" >&2; exit 1 ;;\n"
                + "esac\n");

        Files.writeString(pulls, pull("\"mergeable\":false,\"mergeable_state\":\"dirty\""));
        Map<String, PrMonitorRead.CachedResponse> etags = new HashMap<>();
        PrSnapshot.Snapshot before = assertInstanceOf(PrSnapshot.Snapshot.class,
                PrMonitorRead.readWith(Optional.of(gh), dir, Optional.empty(), 42, Duration.ofSeconds(15), etags));
        // The push: the second read reports a new head commit.
        Files.writeString(pulls, pull("\"mergeable\":false,\"mergeable_state\":\"dirty\"")
                .replace(SHA, sha2));
        PrSnapshot.Snapshot after = assertInstanceOf(PrSnapshot.Snapshot.class,
                PrMonitorRead.readWith(Optional.of(gh), dir, Optional.empty(), 42, Duration.ofSeconds(15), etags));

        assertEquals(SHA, before.headRefOid());
        assertEquals(sha2, after.headRefOid());
        for (String key : etags.keySet()) {
            assertFalse(key.contains(SHA), "dead pre-push cache entry survived: " + key);
        }
        assertTrue(etags.keySet().stream().anyMatch(key -> key.contains(sha2)),
                "the new head's commit-scoped entries are cached: " + etags.keySet());
        assertEquals(4, etags.size(), "pulls + two commit-scoped pages + reviews, nothing dead");
    }

    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void aFreshFirstPageForcesTheLaterPagesOfTheSameRead(@TempDir Path dir) throws Exception {
        Path receipt = dir.resolve("receipt.txt");
        Path gh = GhCliPullRequestListingTest.scriptedGh(dir,
                "printf 'args=%s\\n' \"$*\" >> " + receipt + "\n" + straddlingReviewsScript());

        Map<String, PrMonitorRead.CachedResponse> etags = new HashMap<>();
        assertInstanceOf(PrSnapshot.Snapshot.class, PrMonitorRead.readWith(Optional.of(gh), dir,
                Optional.empty(), 42, Duration.ofSeconds(15), etags));
        PrSnapshot secondRaw = PrMonitorRead.readWith(Optional.of(gh), dir, Optional.empty(), 42, Duration.ofSeconds(15), etags);
        if (secondRaw instanceof PrSnapshot.Failed f) { java.nio.file.Files.writeString(java.nio.file.Path.of("/tmp/straddle-failed.txt"), f.message()); }
        PrSnapshot.Snapshot second = assertInstanceOf(PrSnapshot.Snapshot.class, secondRaw);

        List<String> authors = second.reviews().stream().map(PrMonitorRead.Review::author).toList();
        assertTrue(authors.stream().noneMatch(author -> author.startsWith("old-")),
                "a stale 304 page beside a fresh page 1 leaked old entries: " + authors.size() + " reviews");
        assertTrue(authors.contains("new-extra"), "the forced page 2 answered fresh: " + authors);
        assertTrue(Files.readAllLines(receipt).stream()
                .filter(call -> call.contains("page=2"))
                .anyMatch(call -> !call.contains("If-None-Match")),
                "page 2 of the second read was fetched unconditionally");
    }

    /**
     * A two-read, two-page reviews endpoint: the first read caches both
     * pages (old entries); the second read's page 1 shifts to new entries
     * while its page 2 would answer 304 if asked conditionally -- the stale
     * page that must not be reused beside a fresh page 1.
     */
    private static String straddlingReviewsScript() {
        return reviewArrayFn()
                + "R=$(cat RD 2>/dev/null || echo 0); R=$((R+1)); echo $R > RD\n"
                + "if printf '%s' \"$*\" | grep -q 'If-None-Match'; then"
                + " case \"$2\" in *reviews*) ;; *) printf 'HTTP/2.0 304 Not Modified\\nEtag: \"x\"\\n\\n'; exit 1;; esac; fi\n"
                + "case \"$2\" in\n"
                + "  pulls/42)\n"
                + "    printf 'HTTP/2.0 200 OK\\nEtag: \"p\"\\n\\n%s' '" + pull("\"mergeable\":false,\"mergeable_state\":\"dirty\"") + "'\n"
                + "    ;;\n"
                + "  *" + SHA + "/check-runs*)\n"
                + "    printf 'HTTP/2.0 200 OK\\nEtag: \"c\"\\n\\n%s' '{\"total_count\":0,\"check_runs\":[]}'\n"
                + "    ;;\n"
                + "  *" + SHA + "/status)\n"
                + "    printf 'HTTP/2.0 200 OK\\nEtag: \"s\"\\n\\n%s' '{\"state\":\"pending\",\"statuses\":[]}'\n"
                + "    ;;\n"
                + "  *'reviews?per_page=100&page=1')\n"
                + "    if [ \"$(cat RD)\" -ge 2 ]; then\n"
                + "      printf 'HTTP/2.0 200 OK\\nEtag: \"r2p1\"\\n\\n%s' \"$(reviewArray new)\"\n"
                + "    else\n"
                + "      printf 'HTTP/2.0 200 OK\\nEtag: \"r1p1\"\\n\\n%s' \"$(reviewArray old)\"\n"
                + "    fi\n"
                + "    ;;\n"
                + "  *'reviews?per_page=100&page=2')\n"
                + "    if [ \"$(cat RD)\" -ge 2 ]; then\n"
                + "      printf 'HTTP/2.0 200 OK\\nEtag: \"r2p2\"\\n\\n%s' "
                + "'[{\"user\":{\"login\":\"new-extra\"},\"state\":\"COMMENTED\",\"submitted_at\":\"t\",\"body\":\"\"}]'\n"
                + "    else\n"
                + "      printf 'HTTP/2.0 200 OK\\nEtag: \"r1p2\"\\n\\n%s' "
                + "'[{\"user\":{\"login\":\"old-extra\"},\"state\":\"COMMENTED\",\"submitted_at\":\"t\",\"body\":\"\"}]'\n"
                + "    fi\n"
                + "    ;;\n"
                + "  *) echo \"unexpected: $*\" >&2; exit 1 ;;\n"
                + "esac\n";
    }

    /** A shell function emitting 100 review entries whose authors are {@code <prefix>-0..99}. */
    private static String reviewArrayFn() {
        return "reviewArray() { printf '['; i=0; while [ $i -lt 100 ]; do "
                + "[ $i -gt 0 ] && printf ','; "
                + "printf '{\"user\":{\"login\":\"%s-%d\"},\"state\":\"COMMENTED\","
                + "\"submitted_at\":\"t\",\"body\":\"\"}' \"$1\" \"$i\"; i=$((i+1)); done; printf ']'; }\n";
    }

    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void stillFullPagesAtTheCapWarnInsteadOfTruncatingSilently(@TempDir Path dir) throws Exception {
        Path gh = GhCliPullRequestListingTest.scriptedGh(dir, fullPagesScript());
        List<LogRecord> warnings = new ArrayList<>();

        PrSnapshot read = warningsDuring(warnings, () -> PrMonitorRead.readWith(
                Optional.of(gh), dir, Optional.empty(), 42, Duration.ofSeconds(15), new HashMap<>()));
        PrSnapshot.Snapshot snapshot = assertInstanceOf(PrSnapshot.Snapshot.class, read);

        assertEquals(PrMonitorRead.MAX_REVIEWS, snapshot.reviews().size());
        assertTrue(warnings.stream().anyMatch(record -> record.getMessage().contains("truncated")),
                warnings::toString);
    }

    /** Reviews pages that never come back short: five full pages, a sixth is never requested. */
    private static String fullPagesScript() {
        return reviewArrayFn()
                + "if printf '%s' \"$*\" | grep -q 'If-None-Match'; then"
                + " printf 'HTTP/2.0 304 Not Modified\\nEtag: \"x\"\\n\\n'; exit 1; fi\n"
                + "case \"$2\" in\n"
                + "  pulls/42)\n"
                + "    printf 'HTTP/2.0 200 OK\\nEtag: \"p\"\\n\\n%s' '" + pull("\"mergeable\":false,\"mergeable_state\":\"dirty\"") + "'\n"
                + "    ;;\n"
                + "  *" + SHA + "/check-runs*)\n"
                + "    printf 'HTTP/2.0 200 OK\\nEtag: \"c\"\\n\\n%s' '{\"total_count\":0,\"check_runs\":[]}'\n"
                + "    ;;\n"
                + "  *" + SHA + "/status)\n"
                + "    printf 'HTTP/2.0 200 OK\\nEtag: \"s\"\\n\\n%s' '{\"state\":\"pending\",\"statuses\":[]}'\n"
                + "    ;;\n"
                + "  *reviews*)\n"
                + "    printf 'HTTP/2.0 200 OK\\nEtag: \"rv\"\\n\\n%s' \"$(reviewArray full)\"\n"
                + "    ;;\n"
                + "  *) echo \"unexpected: $*\" >&2; exit 1 ;;\n"
                + "esac\n";
    }

    /** Runs {@code body}, collecting the WARNING+ records {@link PrMonitorRead} logs meanwhile. */
    private static <T> T warningsDuring(List<LogRecord> into, Callable<T> body) throws Exception {
        Logger logger = Logger.getLogger(PrMonitorRead.class.getName());
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
            public void flush() { }

            @Override
            public void close() { }
        };
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
        try {
            return body.call();
        } finally {
            logger.removeHandler(handler);
        }
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
                + "'{\"total_count\":1,\"check_runs\":[{\"name\":\"tests\",\"status\":\"completed\",\"conclusion\":\"failure\","
                + "\"details_url\":\"https://github.com/o/r/actions/runs/44315117281/job/134839058118\"}]}'\n"
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
