package app.drydock.github;

import app.drydock.process.ProcessResult;
import app.drydock.state.json.JsonValue.JsonBoolean;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance matrix for the pure surface of {@link GitHubStackPrService}
 * (S3 of docs/StackedPrExecution.md): the argv carries the pulls endpoint
 * with the payload file and honors a HOST/OWNER/REPO setting, the payload
 * builder is the single source of the POST body, the created-PR parser
 * accepts only answers with both an address and a number, the exit-code
 * mapping distinguishes refusals from an unavailable gh, and the
 * invalid-repository guard refuses before any process runs. No process
 * runs; no network is touched; gh need not be installed.
 */
class GitHubStackPrServiceTest {

    /** A gh path that exists nowhere, so any process attempt fails visibly. */
    private static final Path NO_GH = Path.of("/nonexistent/drydock-test-gh");

    @Test
    void commandPostsToThePlaceholderEndpointWithThePayloadFile() {
        List<String> argv = GitHubStackPrService.command(Path.of("/usr/bin/gh"), Optional.empty(),
                Path.of("/tmp/payload.json"));
        assertEquals(List.of("/usr/bin/gh", "api", "--method", "POST", "repos/{owner}/{repo}/pulls",
                "--input", "/tmp/payload.json"), argv);
    }

    @Test
    void commandHonorsAHostQualifiedRepositorySetting() {
        List<String> argv = GitHubStackPrService.command(Path.of("gh"),
                Optional.of("host.example/owner/repo"), Path.of("p.json"));
        assertEquals(List.of("gh", "api", "--method", "POST", "--hostname", "host.example",
                "repos/owner/repo/pulls", "--input", "p.json"), argv);
    }

    @Test
    void commandHonorsAnUnqualifiedRepositorySetting() {
        List<String> argv = GitHubStackPrService.command(Path.of("gh"), Optional.of("owner/repo"),
                Path.of("p.json"));
        assertEquals(List.of("gh", "api", "--method", "POST", "repos/owner/repo/pulls",
                "--input", "p.json"), argv);
    }

    @Test
    void createdParsesNumberAndUrl() {
        GitHubStackPrService.Outcome outcome = GitHubStackPrService.parseCreated(
                "{\"number\": 41, \"html_url\": \"https://github.com/owner/repo/pull/41\"}");
        assertEquals(new GitHubStackPrService.Created(41, "https://github.com/owner/repo/pull/41"),
                outcome);
    }

    @Test
    void anAnswerWithoutAnAddressIsAVisibleRefusal() {
        GitHubStackPrService.Outcome outcome = GitHubStackPrService.parseCreated("{\"number\": 41}");
        assertTrue(outcome instanceof GitHubStackPrService.Rejected);
    }

    @Test
    void anAnswerWithAnAddressButNoNumberIsNotAConfirmedCreation() {
        GitHubStackPrService.Outcome outcome = GitHubStackPrService.parseCreated(
                "{\"html_url\": \"https://github.com/owner/repo/pull/41\"}");
        assertTrue(outcome instanceof GitHubStackPrService.Rejected rejected
                && rejected.message().contains("unknown"));
    }

    @Test
    void aNonIntegralNumberIsARefusalNotAnException() {
        GitHubStackPrService.Outcome outcome = GitHubStackPrService.parseCreated(
                "{\"number\": 4.5, \"html_url\": \"https://github.com/owner/repo/pull/41\"}");
        assertTrue(outcome instanceof GitHubStackPrService.Rejected rejected
                && rejected.message().contains("unknown"));
    }

    @Test
    void thePayloadFileShapeCarriesHeadBaseAndDraft() {
        JsonObject payload = GitHubStackPrService.payload("Task x", "body",
                "dd/task-x-a1b2", "dd/task-y-c3d4");
        assertEquals("Task x", ((JsonString) payload.get("title")).value());
        assertEquals("body", ((JsonString) payload.get("body")).value());
        assertEquals("dd/task-x-a1b2", ((JsonString) payload.get("head")).value());
        assertEquals("dd/task-y-c3d4", ((JsonString) payload.get("base")).value());
        assertTrue(payload.get("draft") instanceof JsonBoolean draft && draft.value());
    }

    @Test
    void anInvalidRepositorySettingIsRefusedBeforeAnyProcessRuns() {
        GitHubStackPrService.Outcome outcome = new GitHubStackPrService().createBlocking(NO_GH,
                Path.of("."), Optional.of("not-a-repo"), "dd/task-x-a1b2", "dd/task-y-c3d4",
                "Task x", "body");
        assertTrue(outcome instanceof GitHubStackPrService.Rejected rejected
                && rejected.message().contains("OWNER/REPO"));
    }

    @Test
    void aValidRepositorySettingPassesTheGuardAndReachesGh() {
        GitHubStackPrService.Outcome outcome = new GitHubStackPrService().createBlocking(NO_GH,
                Path.of("."), Optional.of("owner/repo"), "dd/task-x-a1b2", "dd/task-y-c3d4",
                "Task x", "body");
        assertTrue(outcome instanceof GitHubStackPrService.Rejected rejected
                && rejected.message().startsWith("Could not run gh"));
    }

    @Test
    void aNonZeroExitWithHttp401IsUnavailable() {
        GitHubStackPrService.Outcome outcome = GitHubStackPrService.ghOutcome(
                new ProcessResult(1, "", "gh: HTTP 401: Unauthorized (gh.org)"));
        assertTrue(outcome instanceof GitHubStackPrService.Unavailable);
    }

    @Test
    void aNonZeroExitWithout401IsRejectedWithTheExcerpt() {
        GitHubStackPrService.Outcome outcome = GitHubStackPrService.ghOutcome(
                new ProcessResult(1, "", "gh: HTTP 404: Not Found"));
        assertTrue(outcome instanceof GitHubStackPrService.Rejected rejected
                && rejected.message().contains("HTTP 404"));
    }

    @Test
    void aNonZeroExitWithBlankStderrIsRejectedWithTheExitCode() {
        GitHubStackPrService.Outcome outcome = GitHubStackPrService.ghOutcome(
                new ProcessResult(1, "", ""));
        assertTrue(outcome instanceof GitHubStackPrService.Rejected rejected
                && rejected.message().contains("gh exited 1"));
    }

    @Test
    void aZeroExitParsesTheCreatedAnswer() {
        GitHubStackPrService.Outcome outcome = GitHubStackPrService.ghOutcome(new ProcessResult(0,
                "{\"number\": 41, \"html_url\": \"https://github.com/owner/repo/pull/41\"}", ""));
        assertEquals(new GitHubStackPrService.Created(41, "https://github.com/owner/repo/pull/41"),
                outcome);
    }
}
