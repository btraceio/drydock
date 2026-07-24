package app.drydock.ui;

import app.drydock.domain.ManagedClaudeSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.SessionActivity;
import app.drydock.domain.SessionStatus;
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
 */
record SidebarChildren(
        List<ManagedClaudeSession> liveSessions,
        List<ManagedClaudeSession> idleSessions,
        List<Worktree> openWorktrees,
        List<Worktree> staleWorktrees,
        List<Worktree> lockedWorktrees,
        int worktreeCount,
        int staleCount,
        int lockedCount) {

    /** {@code liveSessions} followed by {@code idleSessions}, in display order. */
    List<ManagedClaudeSession> orderedSessions() {
        List<ManagedClaudeSession> all = new ArrayList<>(liveSessions);
        all.addAll(idleSessions);
        return all;
    }

    static SidebarChildren classify(List<Worktree> worktrees,
            List<ManagedClaudeSession> sessions,
            Function<ManagedSessionId, SessionActivity> activityOf) {

        List<ManagedClaudeSession> mainSessions = sessions.stream()
                .filter(session -> session.worktreeRoot().isEmpty()).toList();
        Set<ManagedClaudeSession> placed = new LinkedHashSet<>();

        List<ManagedClaudeSession> sessionRows = new ArrayList<>();
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
                Optional<ManagedClaudeSession> match = sessions.stream()
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
        for (ManagedClaudeSession session : sessions) {
            if (!placed.contains(session) && session.worktreeRoot().isPresent()) {
                sessionRows.add(session);
            }
        }

        // Band the session rows: live first, then idle. NEEDS_ATTENTION pins to
        // the front of the live band; otherwise most-recently-opened first.
        Comparator<ManagedClaudeSession> byRecency =
                Comparator.comparing(ManagedClaudeSession::lastOpenedAt).reversed();
        List<ManagedClaudeSession> live = new ArrayList<>();
        List<ManagedClaudeSession> idle = new ArrayList<>();
        for (ManagedClaudeSession session : sessionRows) {
            (isRunning(session.status()) ? live : idle).add(session);
        }
        live.sort(Comparator
                .comparingInt((ManagedClaudeSession session) ->
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

        return new SidebarChildren(List.copyOf(live), List.copyOf(idle),
                List.copyOf(openWorktrees), List.copyOf(staleWorktrees), List.copyOf(lockedWorktrees),
                worktreeCount, staleWorktrees.size(), lockedWorktrees.size());
    }

    /**
     * Three-way classification of a bucketed (session-less) worktree. A
     * <em>locked</em> one is set aside deliberately -- a tool holds it (sphinx
     * locks its {@code .sphinx/worktrees/*} while initializing) -- and folds
     * into its own collapsed group rather than cluttering the open rows or
     * being offered up for an unconfirmed {@code Clean}; the main checkout and
     * an ordinary named worktree stay open; a prunable or detached one is
     * stale.
     */
    private static void bucket(Worktree worktree,
            List<Worktree> open, List<Worktree> stale, List<Worktree> locked) {
        if (worktree.mainCheckout()) {
            open.add(worktree);
        } else if (worktree.locked()) {
            locked.add(worktree);
        } else if (worktree.prunable() || worktree.detached()) {
            stale.add(worktree);
        } else {
            open.add(worktree);
        }
    }

    private static boolean isRunning(SessionStatus status) {
        return status == SessionStatus.RUNNING || status == SessionStatus.STARTING;
    }
}
