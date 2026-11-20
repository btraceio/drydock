package app.drydock.git;

import app.drydock.git.GhCliService.PrInfo.PrLifecycle;
import app.drydock.git.PrMonitorRead.Check;
import app.drydock.git.PrMonitorRead.Mergeable;
import app.drydock.git.PrMonitorRead.PrSnapshot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The monitor's read of one PR: the pure parse of {@code gh pr view --json}
 * output, and the run around it driven by a scripted gh.
 */
class PrMonitorReadTest {

    private static final String SHA = "ba3a460f9b85ef9d6c0db61880904311ded36f81";

    /** Everything but the rollup, which each test supplies. */
    private static String view(String extraMembers) {
        return "{\"baseRefName\":\"main\",\"headRefName\":\"topic\",\"headRefOid\":\"" + SHA + "\""
                + (extraMembers.isEmpty() ? "" : "," + extraMembers) + "}";
    }

    private static PrSnapshot.Snapshot snapshot(String stdout) {
        return assertInstanceOf(PrSnapshot.Snapshot.class, PrMonitorRead.parse(7, stdout));
    }

    @Test
    void parsesEveryFieldOfARealShapedView() {
        String stdout = """
                {"baseRefName":"main","headRefName":"rk/topic","headRefOid":"%s","isDraft":true,
                 "mergeStateStatus":"UNSTABLE","mergeable":"MERGEABLE","state":"OPEN",
                 "statusCheckRollup":[
                  {"__typename":"CheckRun","completedAt":"2026-11-18T13:34:42Z","conclusion":"FAILURE",
                   "detailsUrl":"https://github.com/o/r/actions/runs/44315117281/job/134839058118",
                   "name":"tests","startedAt":"2026-11-18T13:34:41Z","status":"COMPLETED","workflowName":"CI Run"},
                  {"__typename":"StatusContext","context":"DDCI Status","startedAt":"2026-11-18T14:11:01Z",
                   "state":"PENDING","targetUrl":"https://ci.example/change/1"}]}
                """.formatted(SHA);

        PrSnapshot.Snapshot snapshot = snapshot(stdout);

        assertEquals(7, snapshot.prNumber());
        assertEquals(PrLifecycle.OPEN, snapshot.lifecycle());
        assertTrue(snapshot.draft());
        assertEquals("main", snapshot.baseRefName());
        assertEquals("rk/topic", snapshot.headRefName());
        assertEquals(SHA, snapshot.headRefOid());
        assertEquals(Mergeable.MERGEABLE, snapshot.mergeable());
        assertEquals("UNSTABLE", snapshot.mergeStateStatus());
        assertEquals(List.of(
                new Check(Check.Kind.CHECK_RUN, "tests", "COMPLETED", "FAILURE", Optional.of("CI Run"),
                        Optional.of("https://github.com/o/r/actions/runs/44315117281/job/134839058118")),
                new Check(Check.Kind.STATUS_CONTEXT, "DDCI Status", "PENDING", "", Optional.empty(),
                        Optional.of("https://ci.example/change/1"))), snapshot.checks());
        assertEquals(Optional.of(44315117281L), snapshot.checks().get(0).runId());
        assertEquals(Optional.empty(), snapshot.checks().get(1).runId());
        assertEquals(1, snapshot.checks().stream().filter(Check::failing).count());
    }

    @Test
    void reviewsAreParsedByAuthorStateTimeAndBodyAndNonActionableOnesAreSkipped() {
        String stdout = view("\"reviews\":["
                + "{\"author\":{\"login\":\"alice\"},\"state\":\"CHANGES_REQUESTED\","
                + "\"submittedAt\":\"2026-11-18T10:00:00Z\",\"body\":\"Please fix the tests.\"},"
                + "{\"author\":{\"login\":\"bob\"},\"state\":\"APPROVED\","
                + "\"submittedAt\":\"2026-11-18T11:00:00Z\",\"body\":\"Nice.\"},"
                + "{\"author\":{\"login\":\"carol\"},\"state\":\"PENDING\","
                + "\"submittedAt\":\"2026-11-18T12:00:00Z\",\"body\":\"draft\"},"
                + "{\"author\":{\"login\":\"dave\"},\"state\":\"DISMISSED\","
                + "\"submittedAt\":\"2026-11-18T13:00:00Z\",\"body\":\"stale\"},"
                + "{\"author\":null,\"state\":\"COMMENTED\",\"submittedAt\":null,\"body\":null},"
                + "42,null]");

        List<PrMonitorRead.Review> reviews = snapshot(stdout).reviews();

        assertEquals(List.of(
                new PrMonitorRead.Review("alice", "CHANGES_REQUESTED", "2026-11-18T10:00:00Z", "Please fix the tests."),
                new PrMonitorRead.Review("bob", "APPROVED", "2026-11-18T11:00:00Z", "Nice."),
                new PrMonitorRead.Review("", "COMMENTED", "", "")), reviews);
    }

    @Test
    void aMissingOrMalformedReviewsArrayIsNoReviews() {
        assertEquals(List.of(), snapshot(view("\"reviews\":null")).reviews());
        assertEquals(List.of(), snapshot(view("\"reviews\":\"approved\"")).reviews());
        assertEquals(List.of(), snapshot(view("")).reviews());
    }

    @Test
    void moreReviewsThanTheCapKeepTheLatest() {
        StringBuilder entries = new StringBuilder();
        for (int i = 0; i < PrMonitorRead.MAX_REVIEWS + 5; i++) {
            if (i > 0) {
                entries.append(',');
            }
            entries.append("{\"author\":{\"login\":\"u").append(i).append("\"},\"state\":\"COMMENTED\",")
                    .append("\"submittedAt\":\"t").append(i).append("\",\"body\":\"\"}");
        }
        List<PrMonitorRead.Review> reviews = snapshot(view("\"reviews\":[" + entries + "]")).reviews();

        assertEquals(PrMonitorRead.MAX_REVIEWS, reviews.size());
        assertEquals("u" + 5, reviews.get(0).author());
        assertEquals("u" + (PrMonitorRead.MAX_REVIEWS + 4), reviews.get(reviews.size() - 1).author());
    }

    @Test
    void mergeabilityKeepsUnknownApartFromMergeable() {
        assertEquals(Mergeable.MERGEABLE, snapshot(view("\"mergeable\":\"MERGEABLE\"")).mergeable());
        assertEquals(Mergeable.CONFLICTING, snapshot(view("\"mergeable\":\"CONFLICTING\"")).mergeable());
        assertEquals(Mergeable.UNKNOWN, snapshot(view("\"mergeable\":\"UNKNOWN\"")).mergeable());
        assertEquals(Mergeable.UNKNOWN, snapshot(view("\"mergeable\":null")).mergeable());
        assertEquals(Mergeable.UNKNOWN, snapshot(view("\"mergeable\":true")).mergeable());
        assertEquals(Mergeable.UNKNOWN, snapshot(view("")).mergeable());
    }

    @Test
    void mergeStateStatusIsRawOrUnknown() {
        assertEquals("BEHIND", snapshot(view("\"mergeStateStatus\":\"BEHIND\"")).mergeStateStatus());
        assertEquals("DIRTY", snapshot(view("\"mergeStateStatus\":\"DIRTY\"")).mergeStateStatus());
        assertEquals("SOMETHING_NEW", snapshot(view("\"mergeStateStatus\":\"SOMETHING_NEW\"")).mergeStateStatus());
        for (String absent : List.of("", "\"mergeStateStatus\":null", "\"mergeStateStatus\":\"  \"",
                "\"mergeStateStatus\":3")) {
            assertEquals(PrMonitorRead.MERGE_STATE_UNKNOWN, snapshot(view(absent)).mergeStateStatus(), absent);
        }
    }

    @Test
    void closedIsNotFoldedIntoOpenAndAMissingStateIsUnknown() {
        assertEquals(PrLifecycle.CLOSED, snapshot(view("\"state\":\"CLOSED\"")).lifecycle());
        assertEquals(PrLifecycle.MERGED, snapshot(view("\"state\":\"MERGED\"")).lifecycle());
        assertEquals(PrLifecycle.UNKNOWN, snapshot(view("\"state\":null")).lifecycle());
        assertEquals(PrLifecycle.UNKNOWN, snapshot(view("")).lifecycle());
        assertFalse(snapshot(view("\"isDraft\":null")).draft());
    }

    @Test
    void aViewWithoutRefsOrAUsableHeadCommitFails() {
        for (String stdout : List.of(
                "{\"headRefName\":\"topic\",\"headRefOid\":\"" + SHA + "\"}",
                "{\"baseRefName\":\"main\",\"headRefName\":\" \",\"headRefOid\":\"" + SHA + "\"}",
                "{\"baseRefName\":\"main\",\"headRefName\":\"topic\"}",
                "{\"baseRefName\":\"main\",\"headRefName\":\"topic\",\"headRefOid\":null}",
                "{\"baseRefName\":\"main\",\"headRefName\":\"topic\",\"headRefOid\":\"--force\"}",
                "{\"baseRefName\":\"main\",\"headRefName\":\"topic\",\"headRefOid\":\"ba3a460\"}",
                "{\"baseRefName\":\"main\",\"headRefName\":\"topic\",\"headRefOid\":\"" + SHA.toUpperCase() + "\"}")) {
            assertInstanceOf(PrSnapshot.Failed.class, PrMonitorRead.parse(7, stdout), stdout);
        }
    }

    @Test
    void malformedOutputFailsAndIsNeverAnEmptyAnswer() {
        assertInstanceOf(PrSnapshot.Failed.class, PrMonitorRead.parse(7, "not json"));
        assertInstanceOf(PrSnapshot.Failed.class, PrMonitorRead.parse(7, ""));
        assertInstanceOf(PrSnapshot.Failed.class, PrMonitorRead.parse(7, "{\"baseRefName\":\"main\""));
        assertInstanceOf(PrSnapshot.Failed.class, PrMonitorRead.parse(7, "[" + view("") + "]"));
        String deep = "[".repeat(10_000) + "]".repeat(10_000);
        assertInstanceOf(PrSnapshot.Failed.class, PrMonitorRead.parse(7, view("\"statusCheckRollup\":" + deep)));
    }

    @Test
    void aMissingOrMalformedRollupIsNoChecks() {
        assertEquals(List.of(), snapshot(view("")).checks());
        assertEquals(List.of(), snapshot(view("\"statusCheckRollup\":null")).checks());
        assertEquals(List.of(), snapshot(view("\"statusCheckRollup\":{\"state\":\"FAILURE\"}")).checks());
        assertEquals(List.of(), snapshot(view("\"statusCheckRollup\":[1,\"FAILURE\",null,[]]")).checks());
    }

    @Test
    void unknownEntryTypesAreSkippedAndUntypedOnesGoByShape() {
        List<Check> checks = snapshot(view("\"statusCheckRollup\":["
                + "{\"__typename\":\"Mystery\",\"name\":\"m\",\"state\":\"FAILURE\"},"
                + "{\"__typename\":7,\"conclusion\":\"FAILURE\"},"
                + "{\"name\":\"untyped run\",\"status\":\"COMPLETED\",\"conclusion\":\"ERROR\"},"
                + "{\"__typename\":null,\"context\":\"untyped status\",\"state\":\"FAILURE\"},"
                + "{\"name\":\"no shape\"}]")).checks();

        assertEquals(List.of(
                new Check(Check.Kind.CHECK_RUN, "untyped run", "COMPLETED", "ERROR", Optional.empty(), Optional.empty()),
                new Check(Check.Kind.STATUS_CONTEXT, "untyped status", "FAILURE", "", Optional.empty(),
                        Optional.empty())), checks);
    }

    @Test
    void missingAndNullCheckFieldsReadAsEmpty() {
        List<Check> checks = snapshot(view("\"statusCheckRollup\":["
                + "{\"__typename\":\"CheckRun\",\"name\":null,\"status\":\"IN_PROGRESS\",\"conclusion\":\"\","
                + "\"workflowName\":\"\",\"detailsUrl\":null},"
                + "{\"__typename\":\"CheckRun\"},"
                + "{\"__typename\":\"StatusContext\",\"name\":\"ignored\",\"detailsUrl\":\"https://x/actions/runs/5\"}]"))
                .checks();

        assertEquals(List.of(
                new Check(Check.Kind.CHECK_RUN, "", "IN_PROGRESS", "", Optional.empty(), Optional.empty()),
                new Check(Check.Kind.CHECK_RUN, "", "", "", Optional.empty(), Optional.empty()),
                new Check(Check.Kind.STATUS_CONTEXT, "", "", "", Optional.empty(), Optional.empty())), checks);
        assertTrue(checks.stream().noneMatch(Check::failing));
    }

    @Test
    void failingMatchesTheBadgeSignal() {
        PrSnapshot.Snapshot snapshot = snapshot(view("\"statusCheckRollup\":["
                + "{\"__typename\":\"CheckRun\",\"status\":\"COMPLETED\",\"conclusion\":\"FAILURE\"},"
                + "{\"__typename\":\"CheckRun\",\"status\":\"COMPLETED\",\"conclusion\":\"ERROR\"},"
                + "{\"__typename\":\"StatusContext\",\"state\":\"FAILURE\"},"
                + "{\"__typename\":\"StatusContext\",\"state\":\"ERROR\"},"
                + "{\"__typename\":\"CheckRun\",\"status\":\"IN_PROGRESS\",\"conclusion\":\"FAILURE\"},"
                + "{\"__typename\":\"CheckRun\",\"status\":\"COMPLETED\",\"conclusion\":\"failure\"},"
                + "{\"__typename\":\"CheckRun\",\"status\":\"COMPLETED\",\"conclusion\":\"TIMED_OUT\"},"
                + "{\"__typename\":\"CheckRun\",\"status\":\"COMPLETED\",\"conclusion\":\"CANCELLED\"},"
                + "{\"__typename\":\"CheckRun\",\"status\":\"COMPLETED\",\"state\":\"FAILURE\",\"conclusion\":\"SUCCESS\"},"
                + "{\"__typename\":\"StatusContext\",\"state\":\"PENDING\"},"
                + "{\"__typename\":\"StatusContext\",\"state\":\"SUCCESS\"}]"));

        assertEquals(List.of(true, true, true, true, false, false, false, false, false, false, false),
                snapshot.checks().stream().map(Check::failing).toList());
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
    void aNonPositivePrNumberIsRefused() {
        try (PrMonitorRead read = new PrMonitorRead()) {
            for (int number : new int[] {0, -1}) {
                assertThrows(IllegalArgumentException.class, () -> read.read(Path.of("."), Optional.empty(), number));
                assertThrows(IllegalArgumentException.class, () -> PrMonitorRead.readWith(Optional.empty(),
                        Path.of("."), Optional.empty(), number, Duration.ofSeconds(15)));
                assertThrows(IllegalArgumentException.class, () -> PrMonitorRead.parse(number, view("")));
            }
        }
    }

    @Test
    void missingGhIsUnsupported() {
        assertInstanceOf(PrSnapshot.Unsupported.class, PrMonitorRead.readWith(Optional.empty(), Path.of("."),
                Optional.of("o/r"), 7, Duration.ofSeconds(15)));
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
    void oneViewCallCarriesEveryFieldAndTheRepository(@TempDir Path dir) throws Exception {
        Path receipt = dir.resolve("receipt.txt");
        Path gh = GhCliPullRequestListingTest.scriptedGh(dir, "printf 'args=%s\\n' \"$*\" >> " + receipt + "\n"
                + "printf '%s' '" + view("\"state\":\"OPEN\",\"mergeable\":\"CONFLICTING\","
                + "\"mergeStateStatus\":\"DIRTY\"") + "'\n");

        PrSnapshot.Snapshot snapshot = assertInstanceOf(PrSnapshot.Snapshot.class,
                PrMonitorRead.readWith(Optional.of(gh), dir, Optional.of("o/r"), 42, Duration.ofSeconds(15)));

        assertEquals(List.of("args=pr view 42 --json state,isDraft,baseRefName,headRefName,headRefOid,"
                + "mergeable,mergeStateStatus,statusCheckRollup,reviews --repo o/r"), Files.readAllLines(receipt));
        assertEquals(42, snapshot.prNumber());
        assertEquals(Mergeable.CONFLICTING, snapshot.mergeable());
        assertEquals("DIRTY", snapshot.mergeStateStatus());
    }

    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void stdinIsDiscardedSoAPromptCannotHangTheRead(@TempDir Path dir) throws Exception {
        Path gh = GhCliPullRequestListingTest.scriptedGh(dir, "cat > /dev/null\nprintf '%s' '" + view("") + "'\n");

        assertInstanceOf(PrSnapshot.Snapshot.class,
                PrMonitorRead.readWith(Optional.of(gh), dir, Optional.empty(), 7, Duration.ofSeconds(10)));
    }

    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void anInvalidRepositoryFailsWithoutRunningGh(@TempDir Path dir) throws Exception {
        Path receipt = dir.resolve("receipt.txt");
        Path gh = GhCliPullRequestListingTest.scriptedGh(dir, "touch " + receipt + "\n");

        for (String repository : List.of("--repo", "o", "o/r/x/y", "o/ r", "o/-r")) {
            assertInstanceOf(PrSnapshot.Failed.class, PrMonitorRead.readWith(Optional.of(gh), dir,
                    Optional.of(repository), 7, Duration.ofSeconds(15)), repository);
        }
        assertFalse(Files.exists(receipt));
    }

    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void aNonzeroExitFailsWithTheStderrExcerpt(@TempDir Path dir) throws Exception {
        Path gh = GhCliPullRequestListingTest.scriptedGh(dir, "echo 'HTTP 502: bad gateway' >&2\nexit 1\n");

        assertEquals("HTTP 502: bad gateway", assertInstanceOf(PrSnapshot.Failed.class,
                PrMonitorRead.readWith(Optional.of(gh), dir, Optional.empty(), 7, Duration.ofSeconds(15))).message());
    }

    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void aTimeoutOrALaunchFailureFails(@TempDir Path dir) throws Exception {
        Path gh = GhCliPullRequestListingTest.scriptedGh(dir, "exec sleep 30\n");

        PrSnapshot.Failed timedOut = assertInstanceOf(PrSnapshot.Failed.class,
                PrMonitorRead.readWith(Optional.of(gh), dir, Optional.empty(), 7, Duration.ofMillis(300)));
        assertTrue(timedOut.message().contains("timed out"), timedOut.message());
        assertInstanceOf(PrSnapshot.Failed.class, PrMonitorRead.readWith(Optional.of(dir.resolve("missing-gh")),
                dir, Optional.empty(), 7, Duration.ofSeconds(15)));
    }

    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void malformedOutputFromGhFails(@TempDir Path dir) throws Exception {
        Path gh = GhCliPullRequestListingTest.scriptedGh(dir, "printf '%s' '{\"baseRefName\":\"main\"'\n");

        assertInstanceOf(PrSnapshot.Failed.class,
                PrMonitorRead.readWith(Optional.of(gh), dir, Optional.empty(), 7, Duration.ofSeconds(15)));
    }
}
