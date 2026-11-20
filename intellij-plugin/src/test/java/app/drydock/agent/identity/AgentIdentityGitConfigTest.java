package app.drydock.agent.identity;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentIdentityGitConfigTest {

    @Test
    void aHelperSpecComesWithAResettingEmptyHelperAheadOfIt() {
        AgentIdentitySpec spec = AgentIdentitySpec.parse(
                "GIT_CREDENTIAL_HELPER_SPEC=!/opt/glue/helper.sh\n");

        List<AgentIdentityGitConfig.Entry> entries =
                AgentIdentityGitConfig.forSession(spec, Optional.of("https://github.com/OWNER/REPO.git"));

        assertEquals(List.of(
                new AgentIdentityGitConfig.Entry("credential.helper", ""),
                new AgentIdentityGitConfig.Entry("credential.helper", "!/opt/glue/helper.sh")), entries);
    }

    @Test
    void aGithubSshOriginIsRewrittenToHttps() {
        AgentIdentitySpec spec = AgentIdentitySpec.parse(
                "GIT_CREDENTIAL_HELPER_SPEC=!/opt/glue/helper.sh\n");

        List<AgentIdentityGitConfig.Entry> entries =
                AgentIdentityGitConfig.forSession(spec, Optional.of("git@github.com:OWNER/REPO.git"));

        assertEquals(3, entries.size());
        assertEquals(new AgentIdentityGitConfig.Entry("url.https://github.com/OWNER/REPO.git.insteadOf",
                "git@github.com:OWNER/REPO.git"), entries.get(2));
    }

    @Test
    void aGithubSshOriginWithoutTheGitSuffixIsRewrittenToo() {
        AgentIdentitySpec spec = AgentIdentitySpec.parse(
                "GIT_CREDENTIAL_HELPER_SPEC=!/opt/glue/helper.sh\n");

        List<AgentIdentityGitConfig.Entry> entries =
                AgentIdentityGitConfig.forSession(spec, Optional.of("git@github.com:OWNER/REPO"));

        assertEquals(new AgentIdentityGitConfig.Entry("url.https://github.com/OWNER/REPO.git.insteadOf",
                "git@github.com:OWNER/REPO"), entries.get(2));
    }

    @Test
    void anSshUrlFormOfGithubIsRewritten() {
        assertEquals(AgentIdentityGitConfig.OriginKind.REWRITABLE_SSH,
                AgentIdentityGitConfig.originKind("ssh://git@github.com/OWNER/REPO.git"));
        assertEquals(AgentIdentityGitConfig.OriginKind.REWRITABLE_SSH,
                AgentIdentityGitConfig.originKind("ssh://git@github.com:22/OWNER/REPO"));
        assertEquals(AgentIdentityGitConfig.OriginKind.REWRITABLE_SSH,
                AgentIdentityGitConfig.originKind("ssh://github.com/OWNER/REPO.git"));

        AgentIdentitySpec spec = AgentIdentitySpec.parse(
                "GIT_CREDENTIAL_HELPER_SPEC=!/opt/glue/helper.sh\\n");
        for (String origin : List.of("ssh://git@github.com/OWNER/REPO.git",
                "ssh://github.com/OWNER/REPO.git")) {
            List<AgentIdentityGitConfig.Entry> entries = AgentIdentityGitConfig.forSession(spec, Optional.of(origin));
            assertEquals(new AgentIdentityGitConfig.Entry("url.https://github.com/OWNER/REPO.git.insteadOf", origin),
                    entries.get(2));
        }
    }

    @Test
    void anHttpsOriginNeedsNoRewrite() {
        AgentIdentitySpec spec = AgentIdentitySpec.parse(
                "GIT_CREDENTIAL_HELPER_SPEC=!/opt/glue/helper.sh\n");

        List<AgentIdentityGitConfig.Entry> entries =
                AgentIdentityGitConfig.forSession(spec, Optional.of("https://github.com/OWNER/REPO.git"));

        assertEquals(2, entries.size(), "no insteadOf entry for an HTTPS origin");
    }

    @Test
    void anAbsentOriginNeedsNoRewrite() {
        AgentIdentitySpec spec = AgentIdentitySpec.parse(
                "GIT_CREDENTIAL_HELPER_SPEC=!/opt/glue/helper.sh\n");

        List<AgentIdentityGitConfig.Entry> entries =
                AgentIdentityGitConfig.forSession(spec, Optional.empty());

        assertEquals(2, entries.size(), "no insteadOf entry without an origin");
    }

    @Test
    void aHelperPairedWithAnUnrewritableSshOriginFailsLoudly() {
        AgentIdentitySpec spec = AgentIdentitySpec.parse(
                "GIT_CREDENTIAL_HELPER_SPEC=!/opt/glue/helper.sh\n");

        AgentIdentityException thrown = assertThrows(AgentIdentityException.class,
                () -> AgentIdentityGitConfig.forSession(spec, Optional.of("git@gitlab.com:OWNER/REPO.git")));

        assertTrue(thrown.getMessage().contains("cannot be rewritten"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("git@gitlab.com:OWNER/REPO.git"), thrown.getMessage());
    }

    @Test
    void aMalformedGithubSshOriginIsUnrewritable() {
        assertEquals(AgentIdentityGitConfig.OriginKind.UNREWRITABLE_SSH,
                AgentIdentityGitConfig.originKind("git@github.com:REPO"));
        assertEquals(AgentIdentityGitConfig.OriginKind.UNREWRITABLE_SSH,
                AgentIdentityGitConfig.originKind("git@github.com:OWNER/REPO/EXTRA"));
        assertEquals(AgentIdentityGitConfig.OriginKind.UNREWRITABLE_SSH,
                AgentIdentityGitConfig.originKind("ssh://git@github.com"));
    }

    @Test
    void anScpStyleSshUrlToAnotherHostIsUnrewritableSsh() {
        assertEquals(AgentIdentityGitConfig.OriginKind.UNREWRITABLE_SSH,
                AgentIdentityGitConfig.originKind("git@gitlab.com:OWNER/REPO.git"));
    }

    @Test
    void aSchemeUrlIsNotSshWhateverItsHost() {
        assertEquals(AgentIdentityGitConfig.OriginKind.NOT_SSH,
                AgentIdentityGitConfig.originKind("https://gitlab.com/OWNER/REPO.git"));
        assertEquals(AgentIdentityGitConfig.OriginKind.NOT_SSH,
                AgentIdentityGitConfig.originKind("/some/local/path"));
    }

    @Test
    void withoutAHelperSpecThereAreNoEntriesAtAll() {
        AgentIdentitySpec spec = AgentIdentitySpec.parse("GH_TOKEN=tok\n");

        assertTrue(AgentIdentityGitConfig.forSession(spec, Optional.of("git@github.com:OWNER/REPO.git")).isEmpty(),
                "commit attribution alone must not touch git config");
    }
}
