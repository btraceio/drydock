package app.drydock.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.drydock.domain.ManagedClaudeSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PrState;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionActivity;
import app.drydock.domain.SessionStatus;
import app.drydock.git.WorktreeService.Worktree;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class SidebarChildrenTest {

    private static final RepositoryId REPO = RepositoryId.newId();
    private static final Path ROOT = Path.of("/repo");

    private static Worktree main() {
        return new Worktree(ROOT, Optional.of("main"), true, false, false, false, Optional.empty());
    }

    private static Worktree wt(String branch, boolean detached, boolean prunable, boolean locked) {
        return new Worktree(Path.of("/wt/" + (branch == null ? "x" : branch)),
                Optional.ofNullable(branch), false, detached, prunable, locked, Optional.empty());
    }

    private static ManagedClaudeSession session(String name, Path worktreeRoot,
            SessionStatus status, Instant lastOpened) {
        return new ManagedClaudeSession(ManagedSessionId.newId(), REPO, name,
                Optional.empty(), Optional.empty(),
                worktreeRoot == null ? ROOT : worktreeRoot,
                Optional.ofNullable(worktreeRoot),
                status, Instant.EPOCH, lastOpened, Optional.empty(),
                PrState.NONE, Optional.empty(), false);
    }

    private static Function<ManagedSessionId, SessionActivity> noActivity() {
        return id -> SessionActivity.UNKNOWN;
    }

    @Test
    void banding_putsLiveSessionsBeforeIdle() {
        ManagedClaudeSession live = session("live", Path.of("/wt/a"),
                SessionStatus.RUNNING, Instant.ofEpochSecond(10));
        ManagedClaudeSession idle = session("idle", null,
                SessionStatus.INACTIVE, Instant.ofEpochSecond(20));
        SidebarChildren result = SidebarChildren.classify(
                List.of(main(), wt("a", false, false, false)),
                List.of(live, idle), noActivity());
        assertEquals(List.of("live"), result.liveSessions().stream().map(ManagedClaudeSession::displayName).toList());
        assertEquals(List.of("idle"), result.idleSessions().stream().map(ManagedClaudeSession::displayName).toList());
    }

    @Test
    void liveBand_sortsMostRecentFirst() {
        ManagedClaudeSession older = session("older", Path.of("/wt/a"),
                SessionStatus.RUNNING, Instant.ofEpochSecond(10));
        ManagedClaudeSession newer = session("newer", Path.of("/wt/b"),
                SessionStatus.RUNNING, Instant.ofEpochSecond(30));
        SidebarChildren result = SidebarChildren.classify(
                List.of(main(), wt("a", false, false, false), wt("b", false, false, false)),
                List.of(older, newer), noActivity());
        assertEquals(List.of("newer", "older"),
                result.liveSessions().stream().map(ManagedClaudeSession::displayName).toList());
    }

    @Test
    void liveBand_pinsNeedsAttentionFirst() {
        ManagedClaudeSession fresh = session("fresh", Path.of("/wt/a"),
                SessionStatus.RUNNING, Instant.ofEpochSecond(50));
        ManagedClaudeSession waiting = session("waiting", Path.of("/wt/b"),
                SessionStatus.RUNNING, Instant.ofEpochSecond(10));
        Map<ManagedSessionId, SessionActivity> activity =
                Map.of(waiting.id(), SessionActivity.NEEDS_ATTENTION);
        SidebarChildren result = SidebarChildren.classify(
                List.of(main(), wt("a", false, false, false), wt("b", false, false, false)),
                List.of(fresh, waiting),
                id -> activity.getOrDefault(id, SessionActivity.UNKNOWN));
        assertEquals(List.of("waiting", "fresh"),
                result.liveSessions().stream().map(ManagedClaudeSession::displayName).toList());
    }

    @Test
    void staleRule_prunableNoSession_isStale() {
        SidebarChildren result = SidebarChildren.classify(
                List.of(main(), wt("gone", false, true, false)),
                List.of(), noActivity());
        assertEquals(1, result.staleWorktrees().size());
        assertTrue(result.openWorktrees().stream().noneMatch(w -> !w.mainCheckout()));
    }

    @Test
    void staleRule_detachedNoSession_isStale() {
        SidebarChildren result = SidebarChildren.classify(
                List.of(main(), wt(null, true, false, false)),
                List.of(), noActivity());
        assertEquals(1, result.staleWorktrees().size());
    }

    @Test
    void staleRule_prunableWithSession_neverStale_staysASession() {
        ManagedClaudeSession onStale = session("onstale", Path.of("/wt/gone"),
                SessionStatus.RUNNING, Instant.ofEpochSecond(10));
        SidebarChildren result = SidebarChildren.classify(
                List.of(main(), wt("gone", false, true, false)),
                List.of(onStale), noActivity());
        assertTrue(result.staleWorktrees().isEmpty());
        assertEquals(List.of("onstale"),
                result.liveSessions().stream().map(ManagedClaudeSession::displayName).toList());
    }

    @Test
    void staleRule_locked_neverStale() {
        SidebarChildren result = SidebarChildren.classify(
                List.of(main(), wt(null, true, true, true)),
                List.of(), noActivity());
        assertTrue(result.staleWorktrees().isEmpty());
        assertEquals(1, result.openWorktrees().stream().filter(w -> !w.mainCheckout()).count());
    }

    @Test
    void staleRule_mainCheckout_neverStale() {
        Worktree detachedMain = new Worktree(ROOT, Optional.empty(), true, true, false, false, Optional.empty());
        SidebarChildren result = SidebarChildren.classify(
                List.of(detachedMain), List.of(), noActivity());
        assertTrue(result.staleWorktrees().isEmpty());
    }

    @Test
    void openWorktrees_sortByBranchThenBranchlessByPath() {
        SidebarChildren result = SidebarChildren.classify(
                List.of(main(), wt("zebra", false, false, false),
                        wt("alpha", false, false, false), wt(null, false, false, true)),
                List.of(), noActivity());
        List<String> order = result.openWorktrees().stream()
                .filter(w -> !w.mainCheckout())
                .map(w -> w.branch().orElse("(" + w.path().getFileName() + ")")).toList();
        assertEquals(List.of("alpha", "zebra", "(x)"), order);
    }

    @Test
    void counts_wtIncludesSessionBackedWorktrees_staleDisjoint() {
        ManagedClaudeSession onWt = session("onwt", Path.of("/wt/a"),
                SessionStatus.INACTIVE, Instant.ofEpochSecond(10));
        SidebarChildren result = SidebarChildren.classify(
                List.of(main(), wt("a", false, false, false),
                        wt("open", false, false, false), wt("gone", false, true, false)),
                List.of(onWt), noActivity());
        // a (session-backed) + open = 2 worktrees; gone = 1 stale; no overlap.
        assertEquals(2, result.worktreeCount());
        assertEquals(1, result.staleCount());
    }

    @Test
    void counts_mainOpenRowIsNotAWorktree() {
        // Main checkout with no session becomes an open row but must NOT count as "N wt".
        SidebarChildren result = SidebarChildren.classify(
                List.of(main()), List.of(), noActivity());
        assertEquals(0, result.worktreeCount());
        assertFalse(result.openWorktrees().isEmpty()); // the main row is still emitted
    }

    @Test
    void orphanSession_whoseWorktreeDirIsGone_stillAppears() {
        ManagedClaudeSession orphan = session("orphan", Path.of("/wt/vanished"),
                SessionStatus.INACTIVE, Instant.ofEpochSecond(10));
        SidebarChildren result = SidebarChildren.classify(
                List.of(main()), List.of(orphan), noActivity());
        assertEquals(List.of("orphan"),
                result.idleSessions().stream().map(ManagedClaudeSession::displayName).toList());
    }

    @Test
    void nextLiveIndex_wrapsForward() {
        assertEquals(0, RepositorySidebar.nextLiveIndex(3, 2, +1));
        assertEquals(2, RepositorySidebar.nextLiveIndex(3, 0, -1));
        assertEquals(0, RepositorySidebar.nextLiveIndex(3, -1, +1)); // nothing selected → first
        assertEquals(2, RepositorySidebar.nextLiveIndex(3, -1, -1)); // nothing selected → last
    }

    @Test
    void nextLiveIndex_noLiveSessions_returnsMinusOne() {
        assertEquals(-1, RepositorySidebar.nextLiveIndex(0, -1, +1));
    }
}
