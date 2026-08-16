package app.drydock.mcp;

import app.drydock.domain.HandoffBrief;
import app.drydock.domain.ManagedSessionId;
import app.drydock.git.UnifiedDiff;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.ReviewIntent;
import app.drydock.review.ReviewScope;
import app.drydock.review.ReviewVerdict;

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

    // ---- review scopes (Review MCP schema) ----------------------------------

    /**
     * The review scope {@code scopeId} names, if the caller may address it:
     * its own session's scopes plus any the human granted with "Run review".
     * Empty for an unknown scope <em>and</em> for one the caller may not
     * touch -- the two are deliberately indistinguishable, so probing scope
     * ids tells an agent nothing.
     */
    Optional<ReviewScope> reviewScope(String scopeId, ManagedSessionId caller);

    /**
     * The display name of the agent behind {@code caller}, for attributing
     * what it writes. A finding says who found it, and a Codex session's
     * findings must not be signed "Claude".
     */
    String reviewerName(ManagedSessionId caller);

    /** The diff of a scope, already parsed. Runs git, so never on the FX thread. */
    UnifiedDiff reviewDiff(ReviewScope scope) throws McpToolException;

    /** Replaces a scope's intent grouping ({@code review_intents}). */
    void putIntents(String scopeId, List<ReviewIntent> intents);

    /** Upserts findings on {@code finding.id}, so a re-run keeps existing threads. */
    void upsertFindings(List<ReviewAnnotation> findings);

    /** Every finding of one scope, whatever its state. */
    List<ReviewAnnotation> findingsOf(String scopeId);

    /** The verdicts recorded on one scope's intents. */
    List<ReviewVerdict> verdictsOf(String scopeId);

    /** Whether the human has submitted this scope's review. */
    boolean reviewSubmitted(String scopeId);

    /** The agent's own turns in the bound session, for the step timeline. */
    List<PromptStep> promptHistory(ReviewScope scope);

    /** One turn of the bound session's transcript ({@code review_scope.promptHistory}). */
    record PromptStep(int step, java.time.Instant at, String prompt, String tool, List<String> files) {
    }

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

    /** How a {@code session_rename} call ended. */
    enum RenameKind {
        /** The name changed and the workspace republished. */
        RENAMED,
        /** The validated title was already this session's name; nothing was written. */
        UNCHANGED,
        /** A human explicitly named this session, so drydock will not rename it. */
        PINNED,
        /** Another session in the same repository already answers to that name. */
        COLLIDED
    }

    /**
     * The result of a rename attempt.
     *
     * <p>{@code currentName} is the name in force after the call -- except on
     * {@link RenameKind#COLLIDED}, where it is the colliding sibling's name,
     * which is what the refusal has to quote.</p>
     */
    record RenameOutcome(RenameKind kind, String currentName) {
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

    /**
     * A worktree opened on a branch that already existed.
     *
     * @param branch   the local branch now checked out there, which is the
     *                 <em>resolved</em> name: adopting {@code origin/feat/x}
     *                 produces {@code feat/x}. It is what the caller must use
     *                 afterwards, so it is what gets reported.
     * @param tracking the remote-tracking ref the branch was minted to follow,
     *                 empty for a branch that was already local.
     */
    record ExistingBranchWorktree(Path path, String branch, Optional<String> tracking) { }

    /**
     * Opens a worktree on a branch that already exists, local or
     * remote-tracking; the directory is named the same way
     * {@link #createWorktree} names one.
     *
     * <p>{@code branch} is a lookup key, not a name being created, so the
     * new-branch refname rules do not apply to it -- what constrains it is
     * that it must resolve against the repository's own ref list.
     * Implementations refuse a branch checked out elsewhere, and refuse
     * adopting a remote ref whose derived local name could not be minted.</p>
     */
    ExistingBranchWorktree createWorktreeOnExistingBranch(ManagedSessionId caller, String branch)
            throws McpToolException;

    /** Opens a session tab in {@code worktree}; returns the new session's id. */
    ManagedSessionId startSession(Path worktree, Optional<String> initialPrompt) throws McpToolException;

    /**
     * What an agent supplies to {@code session_handoff}. The stamps are
     * drydock's: {@code writtenAt}, {@code writtenAtCommit} and {@code author}
     * are set by the implementation, never by the caller, so an agent cannot
     * backdate its own brief or claim the human wrote it.
     */
    record HandoffDraft(String goal, String nextStep, Optional<String> approach, Optional<String> decisions,
                        Optional<String> ruledOut, Optional<String> corrections) {
    }

    /**
     * Replaces the caller's handoff brief with {@code draft} and persists it,
     * stamping {@code writtenAt}, {@code writtenAtCommit} (the caller's current
     * HEAD, empty when its branch has no commits) and {@code author = AGENT}.
     *
     * <p>Wholesale replacement: an absent optional slot in {@code draft} clears
     * whatever was stored there. There is no refusal outcome, unlike {@link
     * #renameSession} -- a brief cannot collide with a sibling or be pinned by
     * a human, so the only failures are a timeout or a session that vanished
     * mid-call, and those throw.</p>
     */
    HandoffBrief writeHandoff(ManagedSessionId caller, HandoffDraft draft) throws McpToolException;

    /**
     * Renames the caller's own session to an already-validated title.
     *
     * <p>Refusal is an outcome, not an exception: {@link
     * RenameKind#PINNED} and {@link RenameKind#COLLIDED} are legal answers
     * to a legal call, and the router owns the wording. Only a timeout or a
     * session that vanished mid-call throws.</p>
     */
    RenameOutcome renameSession(ManagedSessionId caller, String title) throws McpToolException;
}
