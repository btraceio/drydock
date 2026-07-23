package app.drydock.ui.model;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PrState;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionStatus;
import app.drydock.git.GitBranchState;
import app.drydock.git.GitStatus;
import app.drydock.git.WorktreeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless tests for the diff/notification semantics of {@link
 * WorkspaceViewModel}: identical pushes are silent, field-level session
 * changes stay row-level, and anything that moves rows escalates to a
 * structure change.
 */
class WorkspaceViewModelTest {

    /** Records every notification as a readable line, in emission order. */
    private static final class RecordingListener implements WorkspaceViewModel.Listener {
        final List<String> events = new ArrayList<>();

        @Override
        public void structureChanged() {
            events.add("structure");
        }

        @Override
        public void sessionRowChanged(ManagedSessionId sessionId) {
            events.add("session:" + sessionId.value());
        }

        @Override
        public void repoChanged(RepositoryId repositoryId) {
            events.add("repo:" + repositoryId.value());
        }

        @Override
        public void worktreeRowChanged(Path worktreeRoot) {
            events.add("worktree:" + worktreeRoot);
        }

        @Override
        public void activeSessionChanged(Optional<ManagedSessionId> previous,
                                         Optional<ManagedSessionId> current) {
            events.add("active:" + previous.map(id -> id.value().toString()).orElse("-")
                    + "->" + current.map(id -> id.value().toString()).orElse("-"));
        }
    }

    private final WorkspaceViewModel model = new WorkspaceViewModel();
    private final RecordingListener listener = new RecordingListener();

    private final RepositoryId repoA = RepositoryId.newId();
    private final RepositoryId repoB = RepositoryId.newId();

    @BeforeEach
    void subscribe() {
        model.addListener(listener);
    }

    private ManagedAgentSession session(ManagedSessionId id, RepositoryId repositoryId, String name) {
        Instant t = Instant.parse("2026-01-01T00:00:00Z");
        return new ManagedAgentSession(id, repositoryId, AgentKind.CLAUDE, name, Optional.empty(), Optional.empty(),
                Path.of("/tmp/repo").toAbsolutePath(), Optional.empty(), SessionStatus.INACTIVE,
                t, t, Optional.empty(), PrState.NONE, Optional.empty(), true);
    }

    private static GitStatus status(String branch, boolean dirty) {
        return new GitStatus(new GitBranchState.OnBranch(branch), dirty, Optional.empty());
    }

    // ---- setSessions --------------------------------------------------------

    @Test
    void identicalSessionSnapshotEmitsNothing() {
        ManagedAgentSession s = session(ManagedSessionId.newId(), repoA, "One");
        model.setSessions(List.of(s));
        listener.events.clear();
        model.setSessions(List.of(s));
        assertEquals(List.of(), listener.events);
    }

    @Test
    void addingASessionIsStructural() {
        model.setSessions(List.of(session(ManagedSessionId.newId(), repoA, "One")));
        assertEquals(List.of("structure"), listener.events);
    }

    @Test
    void removingASessionIsStructural() {
        ManagedAgentSession s = session(ManagedSessionId.newId(), repoA, "One");
        model.setSessions(List.of(s));
        listener.events.clear();
        model.setSessions(List.of());
        assertEquals(List.of("structure"), listener.events);
    }

    @Test
    void statusFlipIsRowLevelAndTouchesTheRepoHeader() {
        ManagedSessionId id = ManagedSessionId.newId();
        ManagedAgentSession s = session(id, repoA, "One");
        ManagedAgentSession other = session(ManagedSessionId.newId(), repoB, "Two");
        model.setSessions(List.of(s, other));
        listener.events.clear();

        model.setSessions(List.of(s.withStatus(SessionStatus.RUNNING), other));
        assertEquals(List.of("session:" + id.value(), "repo:" + repoA.value()), listener.events);
    }

    @Test
    void twoChangedSessionsInOneRepoEmitOneRepoChange() {
        ManagedSessionId id1 = ManagedSessionId.newId();
        ManagedSessionId id2 = ManagedSessionId.newId();
        ManagedAgentSession s1 = session(id1, repoA, "One");
        ManagedAgentSession s2 = session(id2, repoA, "Two");
        model.setSessions(List.of(s1, s2));
        listener.events.clear();

        model.setSessions(List.of(s1.withStatus(SessionStatus.RUNNING), s2.withStatus(SessionStatus.RUNNING)));
        assertEquals(List.of("session:" + id1.value(), "session:" + id2.value(),
                "repo:" + repoA.value()), listener.events);
    }

    @Test
    void renameIsStructuralBecauseRowsSortByDisplayName() {
        ManagedSessionId id = ManagedSessionId.newId();
        ManagedAgentSession s = session(id, repoA, "One");
        model.setSessions(List.of(s));
        listener.events.clear();

        model.setSessions(List.of(s.withDisplayName("Zed")));
        assertEquals(List.of("structure"), listener.events);
    }

    @Test
    void reorderingSessionsIsStructural() {
        ManagedAgentSession s1 = session(ManagedSessionId.newId(), repoA, "One");
        ManagedAgentSession s2 = session(ManagedSessionId.newId(), repoA, "Two");
        model.setSessions(List.of(s1, s2));
        listener.events.clear();

        model.setSessions(List.of(s2, s1));
        assertEquals(List.of("structure"), listener.events);
    }

    @Test
    void sessionByIdResolvesTheCurrentSnapshot() {
        ManagedSessionId id = ManagedSessionId.newId();
        ManagedAgentSession s = session(id, repoA, "One");
        model.setSessions(List.of(s));
        model.setSessions(List.of(s.withStatus(SessionStatus.RUNNING)));

        assertEquals(SessionStatus.RUNNING, model.sessionById(id).orElseThrow().status());
        assertTrue(model.sessionById(ManagedSessionId.newId()).isEmpty());
    }

    // ---- Repo status --------------------------------------------------------

    @Test
    void repoStatusEmitsOnlyOnChange() {
        GitStatus main = status("main", false);
        model.setRepoStatus(repoA, main);
        model.setRepoStatus(repoA, main);
        assertEquals(List.of("repo:" + repoA.value()), listener.events);
        assertEquals(Optional.of(main), model.repoStatus(repoA));
    }

    @Test
    void repoStatusFailureReplacesStatusAndViceVersa() {
        model.setRepoStatus(repoA, status("main", false));
        listener.events.clear();

        RuntimeException failure = new RuntimeException("gone");
        model.setRepoStatusFailure(repoA, failure);
        assertEquals(List.of("repo:" + repoA.value()), listener.events);
        assertTrue(model.repoStatus(repoA).isEmpty());
        assertEquals(Optional.of(failure), model.repoStatusFailure(repoA));

        listener.events.clear();
        model.setRepoStatus(repoA, status("main", true));
        assertEquals(List.of("repo:" + repoA.value()), listener.events);
        assertTrue(model.repoStatusFailure(repoA).isEmpty());
    }

    // ---- Worktree status ----------------------------------------------------

    @Test
    void worktreeStatusEmitsPerRootAndOnlyOnChange() {
        Path root = Path.of("/tmp/wt").toAbsolutePath();
        GitStatus st = status("feature", true);
        model.setWorktreeStatus(root, st);
        model.setWorktreeStatus(root, st);
        assertEquals(List.of("worktree:" + root), listener.events);

        listener.events.clear();
        model.removeWorktreeStatus(root);
        model.removeWorktreeStatus(root);
        assertEquals(List.of("worktree:" + root), listener.events);
        assertTrue(model.worktreeStatus(root).isEmpty());
    }

    // ---- Worktree discovery -------------------------------------------------

    @Test
    void worktreeDiscoveryIsStructuralOnlyWhenTheListChanges() {
        List<WorktreeService.Worktree> discovered = List.of(
                new WorktreeService.Worktree(Path.of("/tmp/repo").toAbsolutePath(),
                        Optional.of("main"), true, false, false, false));
        assertTrue(!model.anyWorktreesDiscovered());
        model.setWorktrees(repoA, discovered);
        model.setWorktrees(repoA, discovered);
        assertEquals(List.of("structure"), listener.events);
        assertEquals(Optional.of(discovered), model.worktrees(repoA));
        assertTrue(model.anyWorktreesDiscovered());
    }

    @Test
    void removeRepositoryForgetsEverythingOnce() {
        model.setRepoStatus(repoA, status("main", false));
        model.setWorktrees(repoA, List.of());
        listener.events.clear();

        model.removeRepository(repoA);
        model.removeRepository(repoA);
        assertEquals(List.of("structure"), listener.events);
        assertTrue(model.repoStatus(repoA).isEmpty());
        assertTrue(model.worktrees(repoA).isEmpty());
    }

    // ---- Active session -----------------------------------------------------

    @Test
    void activeSessionEmitsPreviousAndCurrentOnlyOnChange() {
        ManagedSessionId id = ManagedSessionId.newId();
        model.setActiveSession(Optional.of(id));
        model.setActiveSession(Optional.of(id));
        assertEquals(List.of("active:-->" + id.value()), listener.events);

        listener.events.clear();
        model.setActiveSession(Optional.empty());
        assertEquals(List.of("active:" + id.value() + "->-"), listener.events);
        assertTrue(model.activeSession().isEmpty());
    }

    @Test
    void removedListenerStopsReceivingEvents() {
        model.removeListener(listener);
        model.setRepoStatus(repoA, status("main", false));
        assertEquals(List.of(), listener.events);
    }
}
