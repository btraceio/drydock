package app.drydock.git;

import app.drydock.git.GhCiRerunService.Outcome;
import app.drydock.process.ProcessResult;

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
 * The rerun request's argv and its four outcomes, driven by a scripted gh:
 * success is exit 0 with no body, 401 is unavailable, any other definitive
 * 4xx is a rejection with the stderr excerpt, and a killed request or a
 * failure without a 4xx status is unknown. Also the run read that ties a
 * run to its head commit.
 */
class GhCiRerunServiceTest {

    private static final Path GH = Path.of("/usr/bin/gh");

    @Test
    void theEndpointKeepsGhsPlaceholdersWithoutARepositorySetting() {
        assertEquals(List.of("/usr/bin/gh", "api", "--method", "POST",
                        "repos/{owner}/{repo}/actions/runs/123/rerun-failed-jobs"),
                GhCiRerunService.command(GH, Optional.empty(), 123));
    }

    @Test
    void anOwnerRepoSettingNamesTheRepositoryAndAHostAddsHostname() {
        assertEquals(List.of("/usr/bin/gh", "api", "--method", "POST",
                        "repos/o/r/actions/runs/9/rerun-failed-jobs"),
                GhCiRerunService.command(GH, Optional.of("o/r"), 9));
        assertEquals(List.of("/usr/bin/gh", "api", "--method", "POST", "--hostname", "ghe.example.com",
                        "repos/o/r/actions/runs/9/rerun-failed-jobs"),
                GhCiRerunService.command(GH, Optional.of("ghe.example.com/o/r"), 9));
    }

    @Test
    void exitZeroIsARerunWhateverTheBody() {
        assertInstanceOf(GhCiRerunService.Rerun.class,
                GhCiRerunService.outcomeOf(new ProcessResult(0, "", ""), 1));
        assertInstanceOf(GhCiRerunService.Rerun.class,
                GhCiRerunService.outcomeOf(new ProcessResult(0, "not json at all", ""), 1));
    }

    @Test
    void http401IsUnavailableAndAnyOtherDefinitive4xxARejectionWithTheExcerpt() {
        assertInstanceOf(GhCiRerunService.Unavailable.class, GhCiRerunService.outcomeOf(
                new ProcessResult(1, "", "gh: Bad credentials (HTTP 401)"), 1));
        GhCiRerunService.Rejected rejected = assertInstanceOf(GhCiRerunService.Rejected.class,
                GhCiRerunService.outcomeOf(new ProcessResult(1, "",
                        "gh: This workflow run is not completed (HTTP 403)"), 1));
        assertEquals("gh: This workflow run is not completed (HTTP 403)", rejected.message());
        for (String refusal : List.of("gh: Not Found (HTTP 404)", "gh: Validation Failed (HTTP 422)",
                "gh: Too Many Requests (HTTP 429)")) {
            assertInstanceOf(GhCiRerunService.Rejected.class,
                    GhCiRerunService.outcomeOf(new ProcessResult(1, "", refusal), 1), refusal);
        }
    }

    @Test
    void aFailureWithoutADefinitive4xxIsUnknownNotRejected() {
        // No status at all (a dropped connection, an EOF after the request was
        // sent), a server-side error, or a bare exit: the POST may have reached
        // GitHub, so the outcome is unknown and the count stands.
        for (String stderr : List.of("", "Post \"https://api.github.com/...\": read: connection reset by peer",
                "error connecting to api.github.com", "gh: Bad Gateway (HTTP 502)", "unexpected EOF",
                "gh: Server Error (HTTP 500)")) {
            GhCiRerunService.Unknown unknown = assertInstanceOf(GhCiRerunService.Unknown.class,
                    GhCiRerunService.outcomeOf(new ProcessResult(1, "", stderr), 7), stderr);
            assertTrue(unknown.message().contains("unknown"), unknown.message());
        }
        assertTrue(assertInstanceOf(GhCiRerunService.Unknown.class,
                GhCiRerunService.outcomeOf(new ProcessResult(4, "", ""), 1)).message().startsWith("gh exited 4"));
    }

    @Test
    void theHttpStatusIsReadFromGhsErrorLine() {
        assertEquals(404, GhCiRerunService.httpStatusOf("gh: Not Found (HTTP 404)\n{\"message\":\"x\"}"));
        assertEquals(-1, GhCiRerunService.httpStatusOf("HTTP/2 stream reset"));
        assertEquals(-1, GhCiRerunService.httpStatusOf("HTTP 4040"));
        assertEquals(-1, GhCiRerunService.httpStatusOf(""));
    }

    @Test
    void aNonPositiveRunIdIsRefusedBeforeAnythingRuns() {
        assertThrows(IllegalArgumentException.class, () -> GhCiRerunService.rerunWith(Optional.of(GH),
                Path.of("."), Optional.empty(), 0, Duration.ofSeconds(1)));
        try (GhCiRerunService service = new GhCiRerunService()) {
            assertThrows(IllegalArgumentException.class,
                    () -> service.rerunFailedJobs(Path.of("."), Optional.empty(), -5));
        }
    }

    @Test
    void noGhIsUnavailable() {
        assertInstanceOf(GhCiRerunService.Unavailable.class, GhCiRerunService.rerunWith(Optional.empty(),
                Path.of("."), Optional.empty(), 5, Duration.ofSeconds(1)));
    }

    @Test
    void anInjectedExecutorIsNotShutDownOnClose() {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            new GhCiRerunService(executor).close();
            assertFalse(executor.isShutdown());
        } finally {
            executor.shutdownNow();
        }
    }

    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void anEmptyBodySuccessIsARerunAndTheArgvIsExact(@TempDir Path dir) throws Exception {
        Path receipt = dir.resolve("receipt.txt");
        Path gh = GhCliPullRequestListingTest.scriptedGh(dir, "cat > /dev/null\n"
                + "printf 'args=%s\\n' \"$*\" >> " + receipt + "\nexit 0\n");

        Outcome outcome = GhCiRerunService.rerunWith(Optional.of(gh), dir, Optional.of("o/r"), 4242,
                Duration.ofSeconds(15));

        assertInstanceOf(GhCiRerunService.Rerun.class, outcome);
        assertEquals(List.of("args=api --method POST repos/o/r/actions/runs/4242/rerun-failed-jobs"),
                Files.readAllLines(receipt));
    }

    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void aScripted401IsUnavailableAndA404ARejection(@TempDir Path dir) throws Exception {
        Path unauthorized = GhCliPullRequestListingTest.scriptedGh(dir.resolve("a"),
                "echo 'gh: Bad credentials (HTTP 401)' >&2\nexit 1\n");
        Path missing = GhCliPullRequestListingTest.scriptedGh(dir.resolve("b"),
                "echo 'gh: Not Found (HTTP 404)' >&2\nexit 1\n");

        assertInstanceOf(GhCiRerunService.Unavailable.class, GhCiRerunService.rerunWith(Optional.of(unauthorized),
                dir, Optional.empty(), 1, Duration.ofSeconds(15)));
        assertEquals("gh: Not Found (HTTP 404)", assertInstanceOf(GhCiRerunService.Rejected.class,
                GhCiRerunService.rerunWith(Optional.of(missing), dir, Optional.empty(), 1,
                        Duration.ofSeconds(15))).message());
    }

    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void aScriptedNetworkFailureIsUnknown(@TempDir Path dir) throws Exception {
        Path gh = GhCliPullRequestListingTest.scriptedGh(dir,
                "echo 'Post \"https://api.github.com/repos/o/r/actions/runs/1/rerun-failed-jobs\": EOF' >&2\nexit 1\n");

        assertInstanceOf(GhCiRerunService.Unknown.class, GhCiRerunService.rerunWith(Optional.of(gh), dir,
                Optional.empty(), 1, Duration.ofSeconds(15)));
    }

    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void aKilledRequestIsUnknownNotRejected(@TempDir Path dir) throws Exception {
        Path gh = GhCliPullRequestListingTest.scriptedGh(dir, "exec sleep 30\n");

        GhCiRerunService.Unknown unknown = assertInstanceOf(GhCiRerunService.Unknown.class,
                GhCiRerunService.rerunWith(Optional.of(gh), dir, Optional.empty(), 7, Duration.ofMillis(300)));
        assertTrue(unknown.message().contains("unknown"), unknown.message());
    }

    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void aLaunchFailureIsARejectionAndAnInvalidRepositoryNeverSpawns(@TempDir Path dir) throws Exception {
        Path receipt = dir.resolve("receipt.txt");
        Path gh = GhCliPullRequestListingTest.scriptedGh(dir, "touch " + receipt + "\n");

        assertInstanceOf(GhCiRerunService.Rejected.class, GhCiRerunService.rerunWith(
                Optional.of(dir.resolve("missing-gh")), dir, Optional.empty(), 7, Duration.ofSeconds(15)));
        for (String repository : List.of("--repo", "o", "o/r/x/y", "o/ r", "o/-r")) {
            assertInstanceOf(GhCiRerunService.Rejected.class, GhCiRerunService.rerunWith(Optional.of(gh), dir,
                    Optional.of(repository), 7, Duration.ofSeconds(15)), repository);
        }
        assertFalse(Files.exists(receipt));
    }

    // ---- the run read -------------------------------------------------------------

    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    @Test
    void theRunReadIsAnExplicitGetOfTheRun() {
        assertEquals(List.of("/usr/bin/gh", "api", "--method", "GET", "repos/{owner}/{repo}/actions/runs/123"),
                GhCiRerunService.readCommand(GH, Optional.empty(), 123));
        assertEquals(List.of("/usr/bin/gh", "api", "--method", "GET", "--hostname", "ghe.example.com",
                        "repos/o/r/actions/runs/9"),
                GhCiRerunService.readCommand(GH, Optional.of("ghe.example.com/o/r"), 9));
    }

    @Test
    void aRunParsesToItsHeadAndAnythingUnusableIsUnreadable() {
        assertEquals(new GhCiRerunService.RunHead(5, SHA, "feat/x"), GhCiRerunService.parseRun(5,
                "{\"id\":5,\"head_sha\":\"" + SHA + "\",\"head_branch\":\"feat/x\",\"status\":\"completed\"}"));
        assertEquals(new GhCiRerunService.RunHead(5, SHA, ""),
                GhCiRerunService.parseRun(5, "{\"head_sha\":\"" + SHA + "\",\"head_branch\":null}"));
        for (String bad : List.of("not json", "[]", "{}", "{\"id\":6,\"head_sha\":\"" + SHA + "\"}",
                "{\"id\":\"5\",\"head_sha\":\"" + SHA + "\"}", "{\"head_sha\":\"" + SHA.toUpperCase() + "\"}",
                "{\"head_sha\":\"--upload-pack\"}", "{\"head_sha\":\"0123\"}")) {
            assertInstanceOf(GhCiRerunService.RunUnreadable.class, GhCiRerunService.parseRun(5, bad), bad);
        }
    }

    @Test
    void noGhCannotReadARun() {
        assertInstanceOf(GhCiRerunService.RunUnreadable.class, GhCiRerunService.readRunWith(Optional.empty(),
                Path.of("."), Optional.empty(), 5, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> GhCiRerunService.readRunWith(Optional.of(GH),
                Path.of("."), Optional.empty(), 0, Duration.ofSeconds(1)));
    }

    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void aScriptedRunReadAnswersTheHeadWithTheExactArgv(@TempDir Path dir) throws Exception {
        Path receipt = dir.resolve("receipt.txt");
        Path gh = GhCliPullRequestListingTest.scriptedGh(dir, "printf 'args=%s\\n' \"$*\" >> " + receipt + "\n"
                + "printf '%s' '{\"id\":4242,\"head_sha\":\"" + SHA + "\",\"head_branch\":\"feat/x\"}'\n");

        GhCiRerunService.RunRead read = GhCiRerunService.readRunWith(Optional.of(gh), dir, Optional.of("o/r"), 4242,
                Duration.ofSeconds(15));

        assertEquals(new GhCiRerunService.RunHead(4242, SHA, "feat/x"), read);
        assertEquals(List.of("args=api --method GET repos/o/r/actions/runs/4242"), Files.readAllLines(receipt));
    }

    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test
    void aScriptedRunReadFailureIsUnreadableAndAnInvalidRepositoryNeverSpawns(@TempDir Path dir) throws Exception {
        Path missing = GhCliPullRequestListingTest.scriptedGh(dir.resolve("a"),
                "echo 'gh: Not Found (HTTP 404)' >&2\nexit 1\n");
        Path receipt = dir.resolve("receipt.txt");
        Path spy = GhCliPullRequestListingTest.scriptedGh(dir.resolve("b"), "touch " + receipt + "\n");

        assertEquals("gh: Not Found (HTTP 404)", assertInstanceOf(GhCiRerunService.RunUnreadable.class,
                GhCiRerunService.readRunWith(Optional.of(missing), dir, Optional.empty(), 1,
                        Duration.ofSeconds(15))).message());
        assertInstanceOf(GhCiRerunService.RunUnreadable.class, GhCiRerunService.readRunWith(Optional.of(spy), dir,
                Optional.of("--repo"), 1, Duration.ofSeconds(15)));
        assertFalse(Files.exists(receipt));
    }
}
