package app.drydock.ui;

import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.Repository;
import app.drydock.git.WorktreeService;
import app.drydock.ui.review.ReviewView;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The session/worktree actions the sidebar can ask the main workspace to
 * perform, plus the one query it renders from ({@link #activeSessionId()}).
 * Modeled on {@link ReviewView.ExplorerBridge}: the
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
}
