package app.drydock.github;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The only pure part of the GitHub links: what an origin URL may look like. */
class GitHubLinkServiceTest {

    @Test
    void anHttpsRemoteParsesToOwnerAndRepo() {
        assertEquals(Optional.of(new GitHubLinkService.Remote("DataDog", "profiling-backend")),
                GitHubLinkService.parseRemote("https://github.com/DataDog/profiling-backend.git"));
    }

    @Test
    void anHttpsWithoutTheGitSuffixParsesTheSame() {
        assertEquals(Optional.of(new GitHubLinkService.Remote("DataDog", "dd-trace-java")),
                GitHubLinkService.parseRemote("https://github.com/DataDog/dd-trace-java"));
    }

    @Test
    void anScpShapedSshRemoteParses() {
        assertEquals(Optional.of(new GitHubLinkService.Remote("dogfood", "drydock")),
                GitHubLinkService.parseRemote("git@github.com:dogfood/drydock.git"));
    }

    @Test
    void aSshSchemeRemoteParses() {
        assertEquals(Optional.of(new GitHubLinkService.Remote("dogfood", "drydock")),
                GitHubLinkService.parseRemote("ssh://git@github.com/dogfood/drydock.git"));
    }

    @Test
    void aNonGitHubRemoteIsNoSuchLink() {
        assertTrue(GitHubLinkService.parseRemote("git@gitlab.ddbuild.io:DataDog/profiling-backend.git")
                .isEmpty(), "not github.com, not this service's to link");
        assertTrue(GitHubLinkService.parseRemote("").isEmpty());
        assertTrue(GitHubLinkService.parseRemote("/some/plain/path").isEmpty());
    }

    @Test
    void aTrailingSlashAndDeeperPathsAreRejected() {
        assertTrue(GitHubLinkService.parseRemote("https://github.com/owner/repo/extra/").isEmpty(),
                "owner/name is what github.com links can address");
        assertTrue(GitHubLinkService.parseRemote("https://github.com/owner").isEmpty(),
                "no repository to link");
    }

    @Test
    void theTwoUrlsPointAtTheSameShaAndLine() {
        GitHubLinkService.Remote remote = new GitHubLinkService.Remote("DataDog", "profiling-backend");
        GitHubLinkService.Link link = GitHubLinkService.buildLink(remote, "a8f1c0e", "app/Foo.java", 41);
        assertEquals("https://github.com/DataDog/profiling-backend/blob/a8f1c0e/app/Foo.java#L41",
                link.githubUrl());
        assertEquals("https://github.dev/DataDog/profiling-backend/blob/a8f1c0e/app/Foo.java#L41",
                link.vscodeUrl(), "github.dev is github.com with the editor host; the #L line comes with it");
    }
}