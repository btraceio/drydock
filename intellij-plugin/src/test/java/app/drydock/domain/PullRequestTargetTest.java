package app.drydock.domain;

import app.drydock.agent.api.AgentKind;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PullRequestTargetTest {
    @Test
    void targetsCarryRepositoryIdentityAndRejectNonPrUrls() {
        var target = PullRequestTarget.fromUrl("https://github.com/DataDog/java-profiler/pull/898");
        assertEquals("github.com/DataDog/java-profiler", target.repository());
        assertEquals(898, target.number());
        assertEquals("https://github.com/DataDog/java-profiler/pull/898", target.url());
        assertNotEquals(target, PullRequestTarget.fromUrl("https://github.com/other/repo/pull/898"));
        for (String bad : new String[] {"http://github.com/o/r/pull/1", "https://github.com/o/r/issues/898",
                "https://github.com/o/r/pull/0", "https://name@github.com/o/r/pull/1",
                "https://github.com/o/r/pull/1?x=1", "https://github.com/o/r/pull/99999999999"}) {
            assertThrows(IllegalArgumentException.class, () -> PullRequestTarget.fromUrl(bad), bad);
        }
    }

    @Test
    void targetSurvivesRenamingBranchStateAndWorkspaceChangesAndCanBeCleared() {
        var target = new PullRequestTarget("github.com/DataDog/java-profiler", 898);
        var session = new ManagedAgentSession.Builder(ManagedSessionId.newId(), RepositoryId.newId(), "session",
                AgentBinding.unlaunched(AgentKind.PI),
                new SessionWorkspace(Path.of("/tmp/worktree"), Optional.empty(), false), Instant.EPOCH)
                .targetPullRequest(Optional.of(target)).build();
        var changed = session.withDisplayName("new name").withWorkingDirectory(Path.of("/tmp/other"))
                .withPr(PrState.MERGED, Optional.of(943)).withStatus(SessionStatus.IDLE);
        assertEquals(Optional.of(target), changed.targetPullRequest());
        assertEquals(Optional.of(943), changed.prNumber());
        assertTrue(changed.toBuilder().targetPullRequest(Optional.empty()).build().targetPullRequest().isEmpty());
        assertEquals(Optional.of(target), changed.targetPullRequest());
    }
}
