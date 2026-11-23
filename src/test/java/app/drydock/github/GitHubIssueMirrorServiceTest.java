package app.drydock.github;

import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessTimeoutException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The argv {@code gh} receives for a mirror issue, and how GitHub's answer is read. */
class GitHubIssueMirrorServiceTest {

    private static final Path GH = Path.of("/usr/bin/gh");
    private static final Path PAYLOAD = Path.of("/tmp/drydock-issue-1.json");

    @Test
    void withoutARepositorySettingGhFillsTheRepositoryFromTheCheckout() {
        assertEquals(List.of("/usr/bin/gh", "api", "--method", "POST", "repos/{owner}/{repo}/issues",
                        "--input", "/tmp/drydock-issue-1.json"),
                GitHubIssueMirrorService.command(GH, Optional.empty(), PAYLOAD));
    }

    @Test
    void aConfiguredRepositoryNamesTheEndpointAndAHostSelectsTheHostname() {
        assertEquals(List.of("/usr/bin/gh", "api", "--method", "POST", "repos/DataDog/drydock/issues",
                        "--input", "/tmp/drydock-issue-1.json"),
                GitHubIssueMirrorService.command(GH, Optional.of("DataDog/drydock"), PAYLOAD));
        assertEquals(List.of("/usr/bin/gh", "api", "--method", "POST", "--hostname", "ghe.example.com",
                        "repos/team/repo/issues", "--input", "/tmp/drydock-issue-1.json"),
                GitHubIssueMirrorService.command(GH, Optional.of("ghe.example.com/team/repo"), PAYLOAD));
    }

    @Test
    void theCreatedIssueIsItsPageAndNumber() {
        GitHubIssueMirrorService.Created created = assertInstanceOf(GitHubIssueMirrorService.Created.class,
                GitHubIssueMirrorService.parseCreated(
                        "{\"number\": 12, \"html_url\": \"https://github.com/o/r/issues/12\"}"));
        assertEquals(12, created.number());
        assertEquals("https://github.com/o/r/issues/12", created.url());
    }

    @Test
    void anAnswerWithoutTheIssuePageIsNotTakenAsCreated() {
        GitHubIssueMirrorService.Rejected rejected = assertInstanceOf(GitHubIssueMirrorService.Rejected.class,
                GitHubIssueMirrorService.parseCreated("{\"number\": 12}"));
        assertTrue(rejected.message().contains("before retrying"), rejected.message());
        assertInstanceOf(GitHubIssueMirrorService.Rejected.class, GitHubIssueMirrorService.parseCreated("nope"));
    }

    private static final String REPO_JSON =
            "{\"full_name\": \"DataDog/drydock\", \"html_url\": \"https://github.com/DataDog/drydock\"}";

    /** A fake process boundary recording argv and replaying one answer. */
    private static final class Fake implements GitHubIssueMirrorService.Runner {
        final List<List<String>> argv = new ArrayList<>();
        final List<Path> dirs = new ArrayList<>();
        ProcessResult answer = new ProcessResult(0, REPO_JSON, "");
        Throwable failure;

        @Override
        public ProcessResult run(List<String> command, Path dir, Duration timeout)
                throws IOException, InterruptedException {
            argv.add(command);
            dirs.add(dir);
            if (failure instanceof IOException e) {
                throw e;
            } else if (failure instanceof InterruptedException e) {
                throw e;
            } else if (failure instanceof RuntimeException e) {
                throw e;
            }
            return answer;
        }
    }

    private static GitHubIssueMirrorService service(Fake fake) {
        return new GitHubIssueMirrorService(fake, GH);
    }

    @Test
    void preparationWithoutAnOverrideLetsGhInferFromTheCheckoutAndConfirmsTheRoute() {
        Fake fake = new Fake();
        Path root = Path.of("/work/a");
        var prepared = service(fake).prepareBlocking(root, Optional.empty());
        assertEquals(List.of(List.of("/usr/bin/gh", "api", "--method", "GET", "repos/{owner}/{repo}")), fake.argv);
        assertEquals(List.of(root), fake.dirs);
        GitHubIssueMirrorService.Confirmed confirmed =
                assertInstanceOf(GitHubIssueMirrorService.Confirmed.class, prepared);
        assertEquals("github.com/DataDog/drydock", confirmed.route());
        assertEquals("https://github.com/DataDog/drydock", confirmed.webUrl());
    }

    @Test
    void preparationWithAnOverrideNamesTheEndpointAndAnEnterpriseHostSelectsTheHostname() {
        Fake fake = new Fake();
        service(fake).prepareBlocking(Path.of("/w"), Optional.of("DataDog/drydock"));
        assertEquals(List.of("/usr/bin/gh", "api", "--method", "GET", "repos/DataDog/drydock"), fake.argv.get(0));
        fake.answer = new ProcessResult(0,
                "{\"full_name\": \"team/repo\", \"html_url\": \"https://ghe.example.com/team/repo\"}", "");
        var prepared = service(fake).prepareBlocking(Path.of("/w"), Optional.of("ghe.example.com/team/repo"));
        assertEquals(List.of("/usr/bin/gh", "api", "--method", "GET", "--hostname", "ghe.example.com",
                "repos/team/repo"), fake.argv.get(1));
        assertEquals("ghe.example.com/team/repo",
                assertInstanceOf(GitHubIssueMirrorService.Confirmed.class, prepared).route());
    }

    @Test
    void anInvalidOverrideSpawnsNothingAndIsADiagnostic() {
        Fake fake = new Fake();
        var prepared = assertInstanceOf(GitHubIssueMirrorService.PreparationFailed.class,
                service(fake).prepareBlocking(Path.of("/w"), Optional.of("-bad/repo")));
        assertEquals(GitHubIssueMirrorService.FailureKind.INVALID_OVERRIDE, prepared.kind());
        assertTrue(fake.argv.isEmpty());
    }

    @Test
    void preparationFailuresAreDistinctDiagnostics() {
        Fake fake = new Fake();
        fake.answer = new ProcessResult(1, "", "gh: Could not resolve to a Repository");
        var failed = assertInstanceOf(GitHubIssueMirrorService.PreparationFailed.class,
                service(fake).prepareBlocking(Path.of("/w"), Optional.empty()));
        assertEquals(GitHubIssueMirrorService.FailureKind.PROBE_FAILED, failed.kind());
        assertTrue(failed.message().contains("Could not resolve"), failed.message());

        fake.failure = new ProcessTimeoutException(List.of("gh"), Duration.ofSeconds(30));
        assertEquals(GitHubIssueMirrorService.FailureKind.TIMEOUT, assertInstanceOf(
                GitHubIssueMirrorService.PreparationFailed.class,
                service(fake).prepareBlocking(Path.of("/w"), Optional.empty())).kind());

        fake.failure = new IOException("no such file");
        assertEquals(GitHubIssueMirrorService.FailureKind.TOOL_MISSING, assertInstanceOf(
                GitHubIssueMirrorService.PreparationFailed.class,
                service(fake).prepareBlocking(Path.of("/w"), Optional.empty())).kind());

        fake.failure = null;
        for (String bad : List.of("nope", "{}", "{\"full_name\": \"a/b\"}",
                "{\"full_name\": \"ab\", \"html_url\": \"https://github.com/ab\"}")) {
            fake.answer = new ProcessResult(0, bad, "");
            assertEquals(GitHubIssueMirrorService.FailureKind.MALFORMED, assertInstanceOf(
                    GitHubIssueMirrorService.PreparationFailed.class,
                    service(fake).prepareBlocking(Path.of("/w"), Optional.empty())).kind(), bad);
        }
    }

    @Test
    void aPinnedCreateUsesTheExplicitRouteAndTimeoutInterruptAndMalformedSuccessAreUncertain() {
        Fake fake = new Fake();
        fake.answer = new ProcessResult(0, "{\"number\": 3, \"html_url\": \"https://github.com/o/r/issues/3\"}", "");
        var created = service(fake).createResult(Path.of("/w"), Optional.of("github.com/o/r"), "t", "b");
        assertEquals(new GitHubIssueMirrorService.Created(3, "https://github.com/o/r/issues/3"), created);
        List<String> argv = fake.argv.get(0);
        assertEquals(List.of("/usr/bin/gh", "api", "--method", "POST", "--hostname", "github.com",
                "repos/o/r/issues", "--input"), argv.subList(0, 8));

        fake.failure = new ProcessTimeoutException(List.of("gh"), Duration.ofSeconds(30));
        assertInstanceOf(GitHubIssueMirrorService.Uncertain.class,
                service(fake).createResult(Path.of("/w"), Optional.of("github.com/o/r"), "t", "b"));
        fake.failure = new InterruptedException();
        assertInstanceOf(GitHubIssueMirrorService.Uncertain.class,
                service(fake).createResult(Path.of("/w"), Optional.of("github.com/o/r"), "t", "b"));
        Thread.interrupted();
        fake.failure = null;
        fake.answer = new ProcessResult(0, "{\"number\": 12}", "");
        assertInstanceOf(GitHubIssueMirrorService.Uncertain.class,
                service(fake).createResult(Path.of("/w"), Optional.of("github.com/o/r"), "t", "b"));
    }

    @Test
    void confirmedRejectionAndMissingToolAreNotUncertainAndLegacyCreateKeepsItsShape() {
        Fake fake = new Fake();
        fake.answer = new ProcessResult(1, "", "HTTP 422 Validation Failed");
        var rejected = service(fake).createResult(Path.of("/w"), Optional.empty(), "t", "b");
        assertInstanceOf(GitHubIssueMirrorService.Rejected.class, rejected);
        assertFalse(rejected instanceof GitHubIssueMirrorService.Uncertain);
        fake.answer = new ProcessResult(1, "", "HTTP 401");
        assertInstanceOf(GitHubIssueMirrorService.Unavailable.class,
                service(fake).createResult(Path.of("/w"), Optional.empty(), "t", "b"));
        // The legacy API maps the unknown outcome onto Rejected, argv unchanged.
        fake.failure = new ProcessTimeoutException(List.of("gh"), Duration.ofSeconds(30));
        assertInstanceOf(GitHubIssueMirrorService.Rejected.class,
                service(fake).createBlocking(Path.of("/w"), Optional.empty(), "t", "b"));
        assertEquals("repos/{owner}/{repo}/issues", fake.argv.get(2).get(4));
    }
}
