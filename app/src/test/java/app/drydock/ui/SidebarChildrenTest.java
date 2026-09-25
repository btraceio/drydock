package app.drydock.ui;

import app.drydock.domain.SessionWorkspace;
import app.drydock.domain.PrLink;
import app.drydock.domain.AgentBinding;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.drydock.agent.api.AgentKind;
import app.drydock.domain.ManagedAgentSession;
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
        return new Worktree(ROOT, Optional.of("main"), true, false, false, false, Optional.empty(), false);
    }

    private static Worktree wt(String branch, boolean detached, boolean prunable, boolean locked) {
        return new Worktree(Path.of("/wt/" + (branch == null ? "x" : branch)),
                Optional.ofNullable(branch), false, detached, prunable, locked, Optional.empty(), false);
    }

    private static ManagedAgentSession session(String name, Path worktreeRoot,
            SessionStatus status, Instant lastOpened) {
        return new ManagedAgentSession(
                ManagedSessionId.newId(), REPO, name,
                new AgentBinding(AgentKind.CLAUDE, Optional.empty(), Optional.empty()),
                new SessionWorkspace(worktreeRoot == null ? ROOT : worktreeRoot, Optional.ofNullable(worktreeRoot), false),
                status, Instant.EPOCH, lastOpened, Optional.empty(),
                PrLink.of(PrState.NONE, Optional.empty()), false, Optional.empty());
    }

    private static Function<ManagedSessionId, SessionActivity> noActivity() {
        return id -> SessionActivity.UNKNOWN;
    }

    @Test
    void banding_putsLiveSessionsBeforeIdle() {
        ManagedAgentSession live = session("live", Path.of("/wt/a"),
                SessionStatus.RUNNING, Instant.ofEpochSecond(10));
        ManagedAgentSession idle = session("idle", null,
                SessionStatus.INACTIVE, Instant.ofEpochSecond(20));
        SidebarChildren result = SidebarChildren.classify(
                List.of(main(), wt("a", false, false, false)),
                List.of(live, idle), noActivity());
        assertEquals(List.of("live"), result.liveSessions().stream().map(ManagedAgentSession::displayName).toList());
        assertEquals(List.of("idle"), result.idleSessions().stream().map(ManagedAgentSession::displayName).toList());
    }

    @Test
    void liveBand_sortsMostRecentFirst() {
        ManagedAgentSession older = session("older", Path.of("/wt/a"),
                SessionStatus.RUNNING, Instant.ofEpochSecond(10));
        ManagedAgentSession newer = session("newer", Path.of("/wt/b"),
                SessionStatus.RUNNING, Instant.ofEpochSecond(30));
        SidebarChildren result = SidebarChildren.classify(
                List.of(main(), wt("a", false, false, false), wt("b", false, false, false)),
                List.of(older, newer), noActivity());
        assertEquals(List.of("newer", "older"),
                result.liveSessions().stream().map(ManagedAgentSession::displayName).toList());
    }

    @Test
    void liveBand_pinsNeedsAttentionFirst() {
        ManagedAgentSession fresh = session("fresh", Path.of("/wt/a"),
                SessionStatus.RUNNING, Instant.ofEpochSecond(50));
        ManagedAgentSession waiting = session("waiting", Path.of("/wt/b"),
                SessionStatus.RUNNING, Instant.ofEpochSecond(10));
        Map<ManagedSessionId, SessionActivity> activity =
                Map.of(waiting.id(), SessionActivity.NEEDS_ATTENTION);
        SidebarChildren result = SidebarChildren.classify(
                List.of(main(), wt("a", false, false, false), wt("b", false, false, false)),
                List.of(fresh, waiting),
                id -> activity.getOrDefault(id, SessionActivity.UNKNOWN));
        assertEquals(List.of("waiting", "fresh"),
                result.liveSessions().stream().map(ManagedAgentSession::displayName).toList());
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
    void staleRule_mergedBranchNoSession_isStale() {
        // A worktree whose branch is already merged into the base is stale --
        // its work is in the base, so the Clean action can offer to remove it.
        SidebarChildren result = SidebarChildren.classify(
                List.of(main(), wt("merged", false, false, false).withMerged(true)),
                List.of(), noActivity());
        assertEquals(1, result.staleWorktrees().size());
        assertTrue(result.openWorktrees().stream().noneMatch(w -> !w.mainCheckout()));
    }

    @Test
    void staleRule_mergedWithSession_neverStale_staysASession() {
        // A session-backed worktree is never stale, even if its branch is
        // merged -- the session owns the row.
        ManagedAgentSession onMerged = session("onmerged", Path.of("/wt/merged"),
                SessionStatus.RUNNING, Instant.ofEpochSecond(10));
        SidebarChildren result = SidebarChildren.classify(
                List.of(main(), wt("merged", false, false, false).withMerged(true)),
                List.of(onMerged), noActivity());
        assertTrue(result.staleWorktrees().isEmpty());
        assertEquals(List.of("onmerged"),
                result.liveSessions().stream().map(ManagedAgentSession::displayName).toList());
    }

    @Test
    void staleRule_prunableWithSession_neverStale_staysASession() {
        ManagedAgentSession onStale = session("onstale", Path.of("/wt/gone"),
                SessionStatus.RUNNING, Instant.ofEpochSecond(10));
        SidebarChildren result = SidebarChildren.classify(
                List.of(main(), wt("gone", false, true, false)),
                List.of(onStale), noActivity());
        assertTrue(result.staleWorktrees().isEmpty());
        assertEquals(List.of("onstale"),
                result.liveSessions().stream().map(ManagedAgentSession::displayName).toList());
    }

    @Test
    void lockedRule_locked_goesToLockedBucketNotStaleOrOpen() {
        // A locked worktree (even one that is also detached and prunable) is
        // held on purpose: it folds into its own bucket, never stale, never a
        // cluttering open row.
        SidebarChildren result = SidebarChildren.classify(
                List.of(main(), wt(null, true, true, true)),
                List.of(), noActivity());
        assertTrue(result.staleWorktrees().isEmpty());
        assertEquals(0, result.openWorktrees().stream().filter(w -> !w.mainCheckout()).count());
        assertEquals(1, result.lockedWorktrees().size());
        assertEquals(1, result.lockedCount());
    }

    @Test
    void lockedRule_lockedWithSession_staysASession() {
        ManagedAgentSession onLocked = session("onlocked", Path.of("/wt/held"),
                SessionStatus.RUNNING, Instant.ofEpochSecond(10));
        SidebarChildren result = SidebarChildren.classify(
                List.of(main(), wt("held", false, false, true)),
                List.of(onLocked), noActivity());
        assertTrue(result.lockedWorktrees().isEmpty());
        assertEquals(List.of("onlocked"),
                result.liveSessions().stream().map(ManagedAgentSession::displayName).toList());
    }

    @Test
    void staleRule_mainCheckout_neverStale() {
        Worktree detachedMain = new Worktree(ROOT, Optional.empty(), true, true, false, false, Optional.empty(), false);
        SidebarChildren result = SidebarChildren.classify(
                List.of(detachedMain), List.of(), noActivity());
        assertTrue(result.staleWorktrees().isEmpty());
    }

    @Test
    void openWorktrees_sortByBranchThenBranchlessByPath() {
        SidebarChildren result = SidebarChildren.classify(
                List.of(main(), wt("zebra", false, false, false),
                        wt("alpha", false, false, false), wt(null, false, false, false)),
                List.of(), noActivity());
        List<String> order = result.openWorktrees().stream()
                .filter(w -> !w.mainCheckout())
                .map(w -> w.branch().orElse("(" + w.path().getFileName() + ")")).toList();
        assertEquals(List.of("alpha", "zebra", "(x)"), order);
    }

    @Test
    void counts_wtIncludesSessionBackedWorktrees_staleDisjoint() {
        ManagedAgentSession onWt = session("onwt", Path.of("/wt/a"),
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
        ManagedAgentSession orphan = session("orphan", Path.of("/wt/vanished"),
                SessionStatus.INACTIVE, Instant.ofEpochSecond(10));
        SidebarChildren result = SidebarChildren.classify(
                List.of(main()), List.of(orphan), noActivity());
        assertEquals(List.of("orphan"),
                result.idleSessions().stream().map(ManagedAgentSession::displayName).toList());
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

    // ---- staleSessions (bulk "Delete all stale sessions" target set) ----

    @Test
    void staleSessions_idleSession_isStale() {
        // An idle session on a live branch is stale by the idle arm -- the
        // "OR inactive" half of the definition.
        ManagedAgentSession idle = session("idle", Path.of("/wt/a"),
                SessionStatus.INACTIVE, Instant.ofEpochSecond(10));
        SidebarChildren result = SidebarChildren.classify(
                List.of(main(), wt("a", false, false, false)),
                List.of(idle), noActivity());
        assertEquals(List.of("idle"),
                result.staleSessions().stream().map(ManagedAgentSession::displayName).toList());
    }

    @Test
    void staleSessions_exitedSession_isStale() {
        ManagedAgentSession exited = session("exited", Path.of("/wt/a"),
                SessionStatus.EXITED, Instant.ofEpochSecond(10));
        SidebarChildren result = SidebarChildren.classify(
                List.of(main(), wt("a", false, false, false)),
                List.of(exited), noActivity());
        assertEquals(List.of("exited"),
                result.staleSessions().stream().map(ManagedAgentSession::displayName).toList());
    }

    @Test
    void staleSessions_runningOnMergedBranch_isStale() {
        // The discrepancy fix: a session on a merged-branch worktree is
        // stale-by-branch even while running, so the bulk delete count
        // includes it -- exactly the row the old "· N stale" header count
        // missed because it only counted session-less worktrees.
        ManagedAgentSession onMerged = session("onmerged", Path.of("/wt/merged"),
                SessionStatus.RUNNING, Instant.ofEpochSecond(10));
        SidebarChildren result = SidebarChildren.classify(
                List.of(main(), wt("merged", false, false, false).withMerged(true)),
                List.of(onMerged), noActivity());
        assertEquals(List.of("onmerged"),
                result.staleSessions().stream().map(ManagedAgentSession::displayName).toList());
    }

    @Test
    void staleSessions_runningOnPrunable_isStale() {
        ManagedAgentSession onPrunable = session("onprunable", Path.of("/wt/gone"),
                SessionStatus.RUNNING, Instant.ofEpochSecond(10));
        SidebarChildren result = SidebarChildren.classify(
                List.of(main(), wt("gone", false, true, false)),
                List.of(onPrunable), noActivity());
        assertEquals(List.of("onprunable"),
                result.staleSessions().stream().map(ManagedAgentSession::displayName).toList());
    }

    @Test
    void staleSessions_runningOnDetached_isStale() {
        ManagedAgentSession onDetached = session("ondetached", Path.of("/wt/x"),
                SessionStatus.RUNNING, Instant.ofEpochSecond(10));
        SidebarChildren result = SidebarChildren.classify(
                List.of(main(), wt(null, true, false, false)),
                List.of(onDetached), noActivity());
        assertEquals(List.of("ondetached"),
                result.staleSessions().stream().map(ManagedAgentSession::displayName).toList());
    }

    @Test
    void staleSessions_runningOnLiveBranch_isNotStale() {
        // A running session on a live, non-merged, non-prunable, non-detached
        // branch is not stale by either arm.
        ManagedAgentSession live = session("live", Path.of("/wt/a"),
                SessionStatus.RUNNING, Instant.ofEpochSecond(10));
        SidebarChildren result = SidebarChildren.classify(
                List.of(main(), wt("a", false, false, false)),
                List.of(live), noActivity());
        assertTrue(result.staleSessions().isEmpty());
    }

    @Test
    void staleSessions_mainCheckoutSession_onlyStaleWhenIdle() {
        // The main checkout is never stale-by-branch (mirrors bucket(): main
        // stays open), so a running main-checkout session is not stale; an
        // idle one is stale by the idle arm.
        ManagedAgentSession runningMain = session("rmain", null,
                SessionStatus.RUNNING, Instant.ofEpochSecond(10));
        ManagedAgentSession idleMain = session("imain", null,
                SessionStatus.INACTIVE, Instant.ofEpochSecond(20));
        SidebarChildren result = SidebarChildren.classify(
                List.of(main()), List.of(runningMain, idleMain), noActivity());
        assertEquals(List.of("imain"),
                result.staleSessions().stream().map(ManagedAgentSession::displayName).toList());
    }

    @Test
    void staleSessions_lockedWorktree_isNotStaleByBranch() {
        // Locked precedes stale (mirrors bucket): a session on a locked+
        // merged worktree is not stale-by-branch. It is still stale if idle.
        ManagedAgentSession running = session("running", Path.of("/wt/held"),
                SessionStatus.RUNNING, Instant.ofEpochSecond(10));
        ManagedAgentSession idle = session("idle", Path.of("/wt/held2"),
                SessionStatus.INACTIVE, Instant.ofEpochSecond(20));
        SidebarChildren result = SidebarChildren.classify(
                List.of(main(), wt("held", false, false, true).withMerged(true),
                        wt("held2", false, false, true).withMerged(true)),
                List.of(running, idle), noActivity());
        // running on locked+merged: not stale. idle on locked+merged: stale
        // by the idle arm only.
        assertEquals(List.of("idle"),
                result.staleSessions().stream().map(ManagedAgentSession::displayName).toList());
    }

    @Test
    void staleSessions_orphanIdleSession_isStale() {
        // An orphan session whose worktree dir is gone is idle here, so it is
        // stale -- and there is no worktree to be stale-by-branch, so only the
        // idle arm applies.
        ManagedAgentSession orphan = session("orphan", Path.of("/wt/vanished"),
                SessionStatus.INACTIVE, Instant.ofEpochSecond(10));
        SidebarChildren result = SidebarChildren.classify(
                List.of(main()), List.of(orphan), noActivity());
        assertEquals(List.of("orphan"),
                result.staleSessions().stream().map(ManagedAgentSession::displayName).toList());
    }

    @Test
    void staleSessions_sortsMostRecentFirst() {
        ManagedAgentSession older = session("older", Path.of("/wt/a"),
                SessionStatus.INACTIVE, Instant.ofEpochSecond(10));
        ManagedAgentSession newer = session("newer", Path.of("/wt/b"),
                SessionStatus.INACTIVE, Instant.ofEpochSecond(30));
        SidebarChildren result = SidebarChildren.classify(
                List.of(main(), wt("a", false, false, false), wt("b", false, false, false)),
                List.of(older, newer), noActivity());
        assertEquals(List.of("newer", "older"),
                result.staleSessions().stream().map(ManagedAgentSession::displayName).toList());
    }

    @Test
    void staleSessions_countMatchesSessionRowsNotJustStaleWorktrees() {
        // The headline fix: the deletable stale-session set is larger than the
        // stale-worktree count when sessions sit on merged branches. Here
        // there is 1 stale (session-less) worktree AND 2 running sessions on
        // merged branches -- the old staleCount() was 1, the stale-session
        // set is 2.
        ManagedAgentSession onMergedA = session("onmergedA", Path.of("/wt/mergedA"),
                SessionStatus.RUNNING, Instant.ofEpochSecond(10));
        ManagedAgentSession onMergedB = session("onmergedB", Path.of("/wt/mergedB"),
                SessionStatus.RUNNING, Instant.ofEpochSecond(20));
        SidebarChildren result = SidebarChildren.classify(
                List.of(main(),
                        wt("mergedA", false, false, false).withMerged(true),
                        wt("mergedB", false, false, false).withMerged(true),
                        wt("gone", false, true, false)),
                List.of(onMergedA, onMergedB), noActivity());
        assertEquals(1, result.staleCount());
        assertEquals(2, result.staleSessions().size());
    }
}
