package app.drydock.ui;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.SessionActivity;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionStatusFacet;
import app.drydock.git.WorktreeService.Worktree;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Pure classification of a repository's on-disk worktrees and managed
 * sessions into the sidebar's display bands, the collapsed stale bucket, and
 * the header counts. Toolkit-free and side-effect-free so it can be unit
 * tested without a JavaFX runtime; {@code RepositorySidebar} wraps the result
 * into tree rows.
 *
 * <p>{@code staleSessions} is the set a bulk "Delete all stale sessions"
 * gesture operates on, and it is what makes that action's count agree with
 * what a user can delete by hand: a session is stale when its worktree's
 * branch is merged/prunable/detached (the same test {@link #staleWorktrees}
 * applies to session-less worktrees, now applied to session-backed ones too)
 * <em>or</em> the session is idle ({@link SessionStatusFacet#IDLE}). Without
 * this, the header's {@code · N stale} count -- which only ever sees
 * session-less worktrees -- under-counts: a merged-branch worktree that still
 * has a session renders as a session row and was never counted, so a user
 * could delete far more sessions by hand than the "stale" number promised.
 * The {@code staleSessions} list carries the same definition onto sessions,
 * so the action's label count and the deletable set cannot drift apart.</p>
 */
record SidebarChildren(
        List<ManagedAgentSession> liveSessions,
        List<ManagedAgentSession> idleSessions,
        List<ManagedAgentSession> staleSessions,
        List<Worktree> openWorktrees,
        List<Worktree> staleWorktrees,
        List<Worktree> lockedWorktrees,
        int worktreeCount,
        int staleCount,
        int lockedCount) {

    /** {@code liveSessions} followed by {@code idleSessions}, in display order. */
    List<ManagedAgentSession> orderedSessions() {
        List<ManagedAgentSession> all = new ArrayList<>(liveSessions);
        all.addAll(idleSessions);
        return all;
    }

    static SidebarChildren classify(List<Worktree> worktrees,
            List<ManagedAgentSession> sessions,
            Function<ManagedSessionId, SessionActivity> activityOf) {

        List<ManagedAgentSession> mainSessions = sessions.stream()
                .filter(session -> session.worktreeRoot().isEmpty()).toList();
        Set<ManagedAgentSession> placed = new LinkedHashSet<>();

        List<ManagedAgentSession> sessionRows = new ArrayList<>();
        List<Worktree> openWorktrees = new ArrayList<>();
        List<Worktree> staleWorktrees = new ArrayList<>();
        List<Worktree> lockedWorktrees = new ArrayList<>();

        // Match sessions to worktrees exactly as childNodesFor did.
        for (Worktree worktree : worktrees) {
            if (worktree.mainCheckout()) {
                if (mainSessions.isEmpty()) {
                    bucket(worktree, openWorktrees, staleWorktrees, lockedWorktrees);
                } else {
                    sessionRows.addAll(mainSessions);
                    placed.addAll(mainSessions);
                }
            } else {
                Optional<ManagedAgentSession> match = sessions.stream()
                        .filter(session -> session.worktreeRoot()
                                .map(root -> root.equals(worktree.path())).orElse(false))
                        .findFirst();
                if (match.isPresent()) {
                    sessionRows.add(match.get());
                    placed.add(match.get());
                } else {
                    bucket(worktree, openWorktrees, staleWorktrees, lockedWorktrees);
                }
            }
        }
        // Orphan sessions whose worktree directory no longer exists.
        for (ManagedAgentSession session : sessions) {
            if (!placed.contains(session) && session.worktreeRoot().isPresent()) {
                sessionRows.add(session);
            }
        }

        // Band the session rows: live first, then idle. NEEDS_ATTENTION pins to
        // the front of the live band; otherwise most-recently-opened first.
        Comparator<ManagedAgentSession> byRecency =
                Comparator.comparing(ManagedAgentSession::lastOpenedAt).reversed();
        List<ManagedAgentSession> live = new ArrayList<>();
        List<ManagedAgentSession> idle = new ArrayList<>();
        for (ManagedAgentSession session : sessionRows) {
            (isRunning(session.status()) ? live : idle).add(session);
        }
        live.sort(Comparator
                .comparingInt((ManagedAgentSession session) ->
                        activityOf.apply(session.id()) == SessionActivity.NEEDS_ATTENTION ? 0 : 1)
                .thenComparing(byRecency));
        idle.sort(byRecency);

        // Open worktrees: main checkout row first, then by branch name
        // (case-insensitive), branch-less last by path.
        openWorktrees.sort(Comparator
                .comparingInt((Worktree worktree) -> worktree.mainCheckout() ? 0 : 1)
                .thenComparingInt(worktree -> worktree.branch().isPresent() ? 0 : 1)
                .thenComparing(worktree -> worktree.branch().map(String::toLowerCase).orElse(""))
                .thenComparing(worktree -> worktree.path().toString()));

        // Locked worktrees: branch-named first, then branch-less by path
        // (sphinx's detached range-* worktrees land here in path order).
        lockedWorktrees.sort(Comparator
                .comparingInt((Worktree worktree) -> worktree.branch().isPresent() ? 0 : 1)
                .thenComparing(worktree -> worktree.branch().map(String::toLowerCase).orElse(""))
                .thenComparing(worktree -> worktree.path().toString()));

        int worktreeCount = (int) openWorktrees.stream().filter(w -> !w.mainCheckout()).count()
                + (int) sessionRows.stream().filter(s -> s.worktreeRoot().isPresent()).count();

        // Stale sessions: the bulk "Delete all stale sessions" target set.
        // A session is stale when it is idle (INACTIVE/EXITED) OR its worktree
        // is stale-by-branch -- non-main, not locked, and merged/prunable/
        // detached. {@link #bucket} applies exactly that test to session-less
        // worktrees; this applies the same test to the worktree a session sits
        // on, so a merged-branch worktree that still owns a session row is no
        // longer invisible to the staleness count. Locked precedes stale
        // (mirroring bucket): a locked worktree is held on purpose, so its
        // session is not stale-by-branch regardless of the branch's state.
        Set<Path> branchStalePaths = new LinkedHashSet<>();
        for (Worktree worktree : worktrees) {
            if (!worktree.mainCheckout() && !worktree.locked()
                    && (worktree.merged() || worktree.prunable() || worktree.detached())) {
                branchStalePaths.add(worktree.path());
            }
        }
        List<ManagedAgentSession> staleSessions = new ArrayList<>();
        for (ManagedAgentSession session : sessionRows) {
            boolean idleStatus = !isRunning(session.status());
            boolean branchStale = session.worktreeRoot()
                    .map(branchStalePaths::contains).orElse(false);
            if (idleStatus || branchStale) {
                staleSessions.add(session);
            }
        }
        staleSessions.sort(byRecency);

        return new SidebarChildren(List.copyOf(live), List.copyOf(idle), List.copyOf(staleSessions),
                List.copyOf(openWorktrees), List.copyOf(staleWorktrees), List.copyOf(lockedWorktrees),
                worktreeCount, staleWorktrees.size(), lockedWorktrees.size());
    }

    /**
     * Three-way classification of a bucketed (session-less) worktree. A
     * <em>locked</em> one is set aside deliberately -- a tool holds it (sphinx
     * locks its {@code .sphinx/worktrees/*} while initializing) -- and folds
     * into its own collapsed group rather than cluttering the open rows or
     * being offered up for an unconfirmed {@code Clean}; the main checkout and
     * an ordinary named worktree stay open; a prunable, detached, or
     * merged-branch one is stale -- its branch's work is already in the base, so
     * it is safe to offer up for the Clean action.
     */
    private static void bucket(Worktree worktree,
            List<Worktree> open, List<Worktree> stale, List<Worktree> locked) {
        if (worktree.mainCheckout()) {
            open.add(worktree);
        } else if (worktree.locked()) {
            locked.add(worktree);
        } else if (worktree.prunable() || worktree.detached() || worktree.merged()) {
            stale.add(worktree);
        } else {
            open.add(worktree);
        }
    }

    private static boolean isRunning(SessionStatus status) {
        return SessionStatusFacet.of(status) == SessionStatusFacet.RUNNING;
    }
}
