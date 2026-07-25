package app.drydock.mcp;

import app.drydock.domain.ManagedSessionId;
import app.drydock.review.ReviewAnnotation;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Everything {@link McpToolRouter} needs from the running application, behind
 * one interface with no JavaFX in its signatures.
 *
 * <p>The seam exists for testability: the build has no mocking library, and a
 * router that reached into {@code MainWorkspace} directly could only be
 * exercised on the FX thread. Implementations that <em>do</em> own FX state are
 * responsible for hopping threads and for timing out (AGENTS.md: a wedged FX
 * thread must fail the call, not hold it open).</p>
 *
 * <p>The context also owns worktree-directory naming and repository lookup.
 * The router never derives a path: the naming recipe needs a {@code Repository}
 * and the user's configured worktree base, neither of which the router has.</p>
 */
public interface McpSessionContext {

    /** Repository root of the calling session, or empty if the session has ended. */
    Optional<Path> repositoryRoot(ManagedSessionId caller);

    /** Working directory (worktree) of the calling session, or empty if it has ended. */
    Optional<Path> worktreePath(ManagedSessionId caller);

    /** Base branch the caller's Review scope diffs against, for {@code review_comments}. */
    Optional<String> baseBranch(ManagedSessionId caller);

    /** The calling session's annotations, unfiltered. */
    List<ReviewAnnotation> annotations(ManagedSessionId caller);

    /** Replaces one annotation and flushes, so the human's view sees it. */
    void updateAnnotation(ReviewAnnotation annotation);

    /**
     * Reads {@code line} of {@code file} in the caller's worktree, with up to
     * {@code context} lines either side, or empty if the file or line is gone.
     * Used to give an annotation an excerpt so the agent can re-locate it after
     * its own edits shift line numbers.
     */
    Optional<String> excerpt(ManagedSessionId caller, String file, int line, int context);

    /** One registered repository, as {@code repos_list} reports it. */
    record RepoSummary(String name, Path path, Optional<String> branch, Optional<Boolean> dirty,
                       Optional<Integer> ahead, Optional<Integer> behind, boolean remote) {
    }

    /** One managed session, as {@code sessions_list} reports it. */
    record SessionSummary(ManagedSessionId id, String displayName, String repositoryName,
                          Optional<String> branch, Path worktree, String status, boolean remote) {
    }

    /**
     * Every registered repository. Remote repositories carry empty git state:
     * {@code GitStatusService} has no cache, so probing them would open one ssh
     * connection per remote repo while the HTTP handler waits.
     */
    List<RepoSummary> repositories();

    /** Every managed session, across the whole workspace. */
    List<SessionSummary> sessions();

    /** Configured remote names of the caller's repository, for branch-name validation. */
    Set<String> remoteNames(ManagedSessionId caller) throws McpToolException;

    /**
     * Worktrees of the caller's repository, as real paths. Implementations must
     * resolve symlinks: {@code git worktree list} reports realpaths, so a
     * lexical comparison both wrongly rejects honest symlinked paths and
     * wrongly accepts a swapped symlink.
     */
    List<Path> realWorktreesOf(ManagedSessionId caller) throws McpToolException;

    /** Creates a worktree for {@code branch} in the caller's repository, naming the directory itself. */
    Path createWorktree(ManagedSessionId caller, String branch, Optional<String> startPoint)
            throws McpToolException;

    /** Opens a session tab in {@code worktree}; returns the new session's id. */
    ManagedSessionId startSession(Path worktree, Optional<String> initialPrompt) throws McpToolException;
}
