package app.drydock.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PrState;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.RepositorySettings;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionStatusFacet;
import app.drydock.git.WorktreeService.Worktree;
import app.drydock.ui.RepositorySidebar.SidebarNode;
import app.drydock.ui.model.SessionFilter;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RepositorySidebarFacetTest {

    private static final RepositoryId REPO_ID = RepositoryId.newId();
    private static final Repository REPO = new Repository(REPO_ID, Path.of("/repo"), "repo",
            Instant.EPOCH, Instant.EPOCH, RepositorySettings.DEFAULT);

    private static ManagedAgentSession session(AgentKind kind, SessionStatus status) {
        return new ManagedAgentSession(ManagedSessionId.newId(), REPO_ID, kind, "s",
                Optional.empty(), Optional.empty(), Path.of("/repo"), Optional.empty(),
                status, Instant.EPOCH, Instant.EPOCH, Optional.empty(),
                PrState.NONE, Optional.empty(), false, false);
    }

    private static SidebarNode sessionNode(ManagedAgentSession session) {
        return new SidebarNode.SessionNode(session, REPO);
    }

    private static SidebarNode unopenedNode() {
        return new SidebarNode.UnopenedWorktreeNode(
                new Worktree(Path.of("/wt/a"), Optional.of("a"), false, false, false, false,
                        Optional.empty()),
                REPO);
    }

    private static SidebarNode staleNode() {
        return new SidebarNode.StaleWorktreesNode(
                List.of(new Worktree(Path.of("/wt/stale"), Optional.of("stale"), false, false, true,
                        false, Optional.empty())),
                REPO);
    }

    private static SidebarNode lockedNode() {
        return new SidebarNode.LockedWorktreesNode(
                List.of(new Worktree(Path.of("/wt/locked"), Optional.of("locked"), false, false, false,
                        true, Optional.empty())),
                REPO);
    }

    private static final SessionFilter RUNNING_ONLY =
            new SessionFilter(EnumSet.of(SessionStatusFacet.RUNNING), Set.of());

    @Test
    void anInactiveFilterIsTheIdentity() {
        List<SidebarNode> children = List.of(sessionNode(session(AgentKind.PI, SessionStatus.EXITED)),
                unopenedNode());
        List<SidebarNode> result =
                RepositorySidebar.applyFacets(children, SessionFilter.none(), id -> false);
        assertSame(children, result);
    }

    @Test
    void anActiveFilterDropsEveryNonSessionRow() {
        ManagedAgentSession live = session(AgentKind.CLAUDE, SessionStatus.RUNNING);
        List<SidebarNode> result = RepositorySidebar.applyFacets(
                List.of(sessionNode(live), unopenedNode(), staleNode(), lockedNode()), RUNNING_ONLY,
                id -> false);
        assertEquals(1, result.size());
        assertSame(live, ((SidebarNode.SessionNode) result.get(0)).session());
    }

    @Test
    void sessionsFailingTheFilterAreDropped() {
        List<SidebarNode> result = RepositorySidebar.applyFacets(
                List.of(sessionNode(session(AgentKind.CLAUDE, SessionStatus.EXITED))),
                RUNNING_ONLY, id -> false);
        assertEquals(List.of(), result);
    }

    /**
     * The frontmost session is always rendered, or clicking a chip would
     * leave the open session absent from the sidebar with the selection
     * cleared out from under it.
     */
    @Test
    void theExemptSessionSurvivesAFilterItDoesNotMatch() {
        ManagedAgentSession open = session(AgentKind.CLAUDE, SessionStatus.EXITED);
        List<SidebarNode> result = RepositorySidebar.applyFacets(
                List.of(sessionNode(open)), RUNNING_ONLY, open.id()::equals);
        assertEquals(1, result.size());
        assertSame(open, ((SidebarNode.SessionNode) result.get(0)).session());
    }
}
