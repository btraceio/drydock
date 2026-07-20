package app.cpm.ui;

import app.cpm.domain.ManagedClaudeSession;
import app.cpm.domain.ManagedSessionId;
import app.cpm.domain.Repository;
import app.cpm.git.WorktreeService;
import app.cpm.ui.review.ReviewView;

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
    void resumeSession(ManagedClaudeSession session);

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
    void promptRenameSession(ManagedClaudeSession session);

    /** The session backing the currently selected tab, if any (drives the sidebar's active row). */
    Optional<ManagedSessionId> activeSessionId();
}
