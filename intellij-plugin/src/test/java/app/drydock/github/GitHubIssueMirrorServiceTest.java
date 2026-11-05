package app.drydock.github;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
}
