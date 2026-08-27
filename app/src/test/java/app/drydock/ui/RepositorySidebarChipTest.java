package app.drydock.ui;

import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.RepositorySettings;
import app.drydock.domain.SshRemote;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RepositorySidebarChipTest {

    @Test
    void chipTextPrefixesGlyph() {
        assertEquals("⇅ prod-box", RepositorySidebar.remoteChipText("prod-box"));
    }

    @Test
    void chipTextKeepsFullUserAtHost() {
        assertEquals("⇅ deploy@build.internal",
                RepositorySidebar.remoteChipText("deploy@build.internal"));
    }

    @Test
    void tooltipTextPrefixesRemoteHost() {
        assertEquals("Remote host: deploy@build.internal",
                RepositorySidebar.remoteChipTooltipText("deploy@build.internal"));
    }

    private static Repository localRepo(String name) {
        return new Repository(RepositoryId.newId(), Path.of("/repo/" + name), name,
                Instant.EPOCH, Instant.EPOCH, RepositorySettings.DEFAULT);
    }

    private static Repository remoteRepo(String name, String host) {
        SshRemote remote = new SshRemote(host, "/srv/" + name);
        return new Repository(RepositoryId.newId(), remote.placeholderRoot(), name,
                Instant.EPOCH, Instant.EPOCH, RepositorySettings.DEFAULT, remote);
    }

    @Test
    void repoTabLabelIsBareNameForLocalRepo() {
        assertEquals("my-app", RepositorySidebar.repoTabLabelText(localRepo("my-app")));
    }

    @Test
    void repoTabLabelSuffixesHostForRemoteRepo() {
        assertEquals("my-app [prod-box]",
                RepositorySidebar.repoTabLabelText(remoteRepo("my-app", "prod-box")));
    }

    @Test
    void repoTabLabelKeepsUserAtHost() {
        assertEquals("app [deploy@build.internal]",
                RepositorySidebar.repoTabLabelText(remoteRepo("app", "deploy@build.internal")));
    }

    @Test
    void repoTabTooltipIsBareNameForLocalRepo() {
        assertEquals("my-app", RepositorySidebar.repoTabTooltipText(localRepo("my-app")));
    }

    @Test
    void repoTabTooltipSpellsOutRemoteHost() {
        assertEquals("app — remote host: prod-box",
                RepositorySidebar.repoTabTooltipText(remoteRepo("app", "prod-box")));
    }
}
