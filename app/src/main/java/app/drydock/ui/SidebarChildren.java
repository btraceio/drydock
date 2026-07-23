package app.drydock.ui;

import app.drydock.domain.ManagedAgentSession;
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
        List<ManagedAgentSession> liveSessions,
        List<ManagedAgentSession> idleSessions,
        List<Worktree> openWorktrees,
        List<Worktree> staleWorktrees,
        int worktreeCount,
        int staleCount) {

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

        // Match sessions to worktrees exactly as childNodesFor did.
        for (Worktree worktree : worktrees) {
            if (worktree.mainCheckout()) {
                if (mainSessions.isEmpty()) {
                    bucket(worktree, openWorktrees, staleWorktrees);
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
                    bucket(worktree, openWorktrees, staleWorktrees);
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

        int worktreeCount = (int) openWorktrees.stream().filter(w -> !w.mainCheckout()).count()
                + (int) sessionRows.stream().filter(s -> s.worktreeRoot().isPresent()).count();

        return new SidebarChildren(List.copyOf(live), List.copyOf(idle),
                List.copyOf(openWorktrees), List.copyOf(staleWorktrees),
                worktreeCount, staleWorktrees.size());
    }

    private static void bucket(Worktree worktree,
            List<Worktree> open, List<Worktree> stale) {
        boolean isStale = !worktree.locked() && !worktree.mainCheckout()
                && (worktree.prunable() || worktree.detached());
        (isStale ? stale : open).add(worktree);
    }

    private static boolean isRunning(SessionStatus status) {
        return status == SessionStatus.RUNNING || status == SessionStatus.STARTING;
    }
}
