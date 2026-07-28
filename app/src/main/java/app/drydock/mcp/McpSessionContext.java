package app.drydock.mcp;

import app.drydock.domain.ManagedSessionId;
import app.drydock.review.ReviewAnnotation;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

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

    /**
     * Whether the calling session's {@code claude} process is still running.
     * False once it has exited -- which happens without the session's tab
     * closing, since the terminal stays open so the human can read the final
     * output. A token outlives that moment only by however long it takes the
     * exit watcher to notice, and no tool may act on it.
     */
    boolean sessionRunning(ManagedSessionId caller);

    /** Working directory (worktree) of the calling session, or empty if it has ended. */
    Optional<Path> worktreePath(ManagedSessionId caller);

    /** Base branch the caller's Review scope diffs against, for {@code review_comments}. */
    Optional<String> baseBranch(ManagedSessionId caller);

    /**
     * Every finding of every review scope the caller may address: the scopes
     * bound to its session, plus any the human granted it. Unfiltered.
     */
    List<ReviewAnnotation> annotations(ManagedSessionId caller);

    /**
     * Atomically re-reads the finding under {@code key}, applies {@code
     * transform} and stores the result, then flushes so the human's view sees
     * it. Empty when nothing is stored under that key.
     *
     * <p>A transform rather than a plain "replace this value": the human's
     * Review tab writes the same threads from the FX thread, so a caller that
     * read a value, decided, and then wrote it back would overwrite whatever
     * the human did in between. The transform's own view is the stored value,
     * so a decision made inside it (including refusing by throwing) is made
     * against what is actually there.</p>
     */
    Optional<ReviewAnnotation> mutateAnnotation(ReviewAnnotation.Key key,
                                                UnaryOperator<ReviewAnnotation> transform);

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
    List<RepoSummary> repositories() throws McpToolException;

    /** Every managed session, across the whole workspace. */
    List<SessionSummary> sessions() throws McpToolException;

    /** Configured remote names of the caller's repository, for branch-name validation. */
    Set<String> remoteNames(ManagedSessionId caller) throws McpToolException;

    /**
     * Worktrees of the caller's repository, as real paths, <em>excluding the
     * main checkout</em>. Implementations must resolve symlinks: {@code git
     * worktree list} reports realpaths, so a lexical comparison both wrongly
     * rejects honest symlinked paths and wrongly accepts a swapped symlink.
     *
     * <p>The main checkout is excluded because this list is {@code
     * session_start}'s membership test, and {@code session_start} opens a
     * worktree session: starting one in the repository root would put a second
     * {@code claude} process in the tree the human is working in, and present
     * it as a worktree session over the main checkout -- a state no
     * human-driven path can produce.</p>
     */
    List<Path> realWorktreesOf(ManagedSessionId caller) throws McpToolException;

    /** Creates a worktree for {@code branch} in the caller's repository, naming the directory itself. */
    Path createWorktree(ManagedSessionId caller, String branch, Optional<String> startPoint)
            throws McpToolException;

    /** Opens a session tab in {@code worktree}; returns the new session's id. */
    ManagedSessionId startSession(Path worktree, Optional<String> initialPrompt) throws McpToolException;
}
