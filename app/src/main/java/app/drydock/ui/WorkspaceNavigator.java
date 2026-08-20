package app.drydock.ui;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.Repository;
import app.drydock.git.GhCliService;
import app.drydock.git.WorktreeService;
import app.drydock.review.SessionReviewScopes;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The session/worktree actions the sidebar can ask the main workspace to
 * perform, plus the one query it renders from ({@link #activeSessionId()}).
 * Modeled on a narrow bridge rather than the full surface: the
 * consumer ({@link RepositorySidebar}) depends on this narrow bridge, and
 * {@link MainWorkspace} implements it — the sidebar never sees the
 * workspace's full surface (tab bookkeeping, native terminal wiring, …).
 *
 * <p>All methods must be called on the JavaFX Application Thread.</p>
 */
public interface WorkspaceNavigator {

    /** Opens (or focuses, if already open) a tab for {@code session}. */
    void resumeSession(ManagedAgentSession session);

    /** Closes the session's tab/surface (a no-op future if it is not open). */
    CompletableFuture<Void> closeSession(ManagedSessionId sessionId);

    /** Called after a session's metadata was deleted so any open tab disappears too. */
    void noteSessionDeleted(ManagedSessionId sessionId);

    /** Creates a brand-new session on {@code repository}'s main checkout and opens it. */
    void openNewSession(Repository repository);

    /** Opens the Start-session modal for an existing worktree checkout. */
    void promptStartWorktreeSession(Repository repository, WorktreeService.Worktree worktree);

    /** Shows the main-pane empty state for a discovered worktree that has no session yet. */
    void showUnopenedWorktree(Repository repository, WorktreeService.Worktree worktree);

    /** Prompts for a new display name and renames {@code session}. */
    void promptRenameSession(ManagedAgentSession session);

    /** The session backing the currently selected tab, if any (drives the sidebar's active row). */
    Optional<ManagedSessionId> activeSessionId();

    /**
     * Lands on {@code sessionId}'s own Review sub-tab, showing {@code
     * choice}'s scope: opens or focuses the session's tab, selects Review,
     * selects the scope. Every gesture that invokes review on an existing
     * session -- the row's context menu, its {@code PR #n} chip, its {@code
     * ◨n} findings badge, {@code ⌘4} -- comes through here, so there is
     * exactly one destination to get right.
     */
    void showReviewForSession(ManagedSessionId sessionId, SessionReviewScopes.Choice choice);

    /**
     * Reviews a discovered worktree that has no session yet: the Start-session
     * modal first, then that new session's Review sub-tab on {@code choice}'s
     * scope. A separate call from {@link #showReviewForSession} because there
     * is no session to name -- review is something a session HAS, so one has
     * to exist before there is anywhere to land.
     */
    void startReviewForWorktree(Repository repository, WorktreeService.Worktree worktree,
                                SessionReviewScopes.Choice choice);

    /**
     * Reviews an open pull request with nothing local behind it: check it out,
     * start a session on it, land on its Review sub-tab.
     */
    void startReviewForPullRequest(Repository repository, GhCliService.OpenPullRequest pullRequest);

    /**
     * As above, running {@code onSettled} when the gesture is over however it
     * ends -- cancelled at the modal, failed at the checkout, failed at the
     * session, or landed on the review board.
     *
     * <p>The caller needs that hook because materializing a pull request is
     * the one sidebar gesture that takes a whole-branch network fetch to
     * finish: the row disables itself for the duration so a second click
     * cannot start a second {@code git worktree add} at the same path, and
     * only the workspace knows when that duration is over. The default
     * implementation settles immediately, which is right for any navigator
     * that does not actually materialize anything.</p>
     */
    default void startReviewForPullRequest(Repository repository, GhCliService.OpenPullRequest pullRequest,
                                           Runnable onSettled) {
        startReviewForPullRequest(repository, pullRequest);
        onSettled.run();
    }
}
