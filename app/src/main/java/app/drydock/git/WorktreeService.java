package app.drydock.git;

import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.process.ProcessTimeoutException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Discovers and removes git worktrees for a repository by invoking the
 * installed {@code git} executable (worktree handoff, section B
 * "Discovering worktrees"): {@link #list(Path)} wraps
 * {@code git worktree list --porcelain} so the sidebar can show every
 * worktree on disk -- including ones created outside this application --
 * and {@link #remove(Path, Path, Optional)} performs the one-click delete
 * of an <em>unopened</em> worktree ({@code git worktree remove} +
 * {@code git branch -D}), guarded off the main checkout and falling back
 * to {@code --force} only for refusals that cannot cost the user work
 * (see {@link #mayRetryWithForce}). {@link #merge(Path, String, MergeTarget)}
 * runs {@code git merge --no-ff} and then {@link #verifyMerge} inspects the
 * repository to establish what actually happened, rather than inferring
 * success from git's exit code: an exit code cannot distinguish a real
 * merge from an agent's {@code checkout <branch>} or {@code reset --hard}
 * while resolving a conflict, and getting that wrong is what would let the
 * caller delete a branch that still held the only copy of the work.
 *
 * <p>Mirrors {@link GitStatusService}'s process/executor style: argument
 * lists (never a shell string), all work on a background virtual-thread
 * executor, {@link CompletableFuture} results, package-private blocking
 * forms for tests.</p>
 */
public final class WorktreeService implements AutoCloseable {

    /** List/remove are quick local operations; a hung git must not park futures forever. */
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(15);

    /**
     * A merge is not a status query: a slow {@code pre-merge-commit} hook, a
     * large tree, or submodule checkouts can all run well past
     * {@link #PROCESS_TIMEOUT} (AGENTS.md: "short for status/query commands,
     * long for clone-scale work"). Matches {@code GitStatusService}'s
     * {@code FETCH_TIMEOUT}.
     */
    private static final Duration MERGE_PROCESS_TIMEOUT = Duration.ofMinutes(2);

    private final GitExecutableLocator locator;
    private final ExecutorService executor;
    private final boolean ownsExecutor;

    /**
     * One worktree of a repository as reported by
     * {@code git worktree list --porcelain}. The first entry git prints is
     * always the main checkout ({@link #mainCheckout()}); a detached or
     * bare entry has no {@link #branch()}. A {@link #prunable()} entry's
     * directory is gone from disk but still owns its branch, and a
     * {@link #locked()} one refuses removal (its {@link #lockReason()} is
     * git's recorded explanation, when it gave one) -- both still block
     * {@code git worktree add} on that branch.
     */
    public record Worktree(Path path, Optional<String> branch, boolean mainCheckout, boolean detached,
                           boolean prunable, boolean locked, Optional<String> lockReason) {
    }

    /**
     * The main checkout's merge-relevant state, captured in one pass
     * immediately before a merge is attempted, and again afterwards to
     * decide what actually happened.
     *
     * <p>{@link #baseBranch()} is empty when HEAD is detached -- a merge
     * committed there is reachable only from the reflog once the source
     * branch is deleted, so it is a refusal, not a label. {@link
     * #branchTipOid()} is empty when the branch is gone. {@link
     * #inProgress()} is what keeps a merge the <em>user</em> left open from
     * being mistaken for one of ours: our own {@code git merge} exits 128
     * ("Merging is not possible because you have unmerged files") while
     * {@code MERGE_HEAD} sits there from their merge.</p>
     */
    public record MergeTarget(Optional<String> baseBranch, String baseHeadOid,
                              Optional<String> branchTipOid, InProgress inProgress) {

        /** A sequencer operation already running in the main checkout. */
        public enum InProgress { NONE, MERGE, REBASE, CHERRY_PICK, REVERT, BISECT }
    }

    public WorktreeService() {
        this(new GitExecutableLocator());
    }

    public WorktreeService(GitExecutableLocator locator) {
        this(locator, Executors.newVirtualThreadPerTaskExecutor(), true);
    }

    /** For tests/callers that want to supply their own executor (and own its shutdown). */
    public WorktreeService(GitExecutableLocator locator, ExecutorService executor) {
        this(locator, executor, false);
    }

    private WorktreeService(GitExecutableLocator locator, ExecutorService executor, boolean ownsExecutor) {
        this.locator = locator;
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
    }

    /**
     * Lists every worktree of the repository at {@code repositoryRoot} on
     * this service's background executor. The returned future completes
     * exceptionally with a {@link GitException} (wrapped in a
     * {@link java.util.concurrent.CompletionException}) on any failure.
     */
    public CompletableFuture<List<Worktree>> list(Path repositoryRoot) {
        return CompletableFuture.supplyAsync(() -> listBlocking(repositoryRoot), executor);
    }

    /**
     * Synchronous form, exposed package-private so tests can assert on the
     * thrown exception type directly instead of unwrapping a
     * {@code CompletionException}. Must never be called from the JavaFX
     * application thread.
     */
    List<Worktree> listBlocking(Path repositoryRoot) {
        Path git = locator.locate()
                .orElseThrow(() -> new GitExecutableNotFoundException(locator.describeSearched()));

        List<String> command = List.of(
                git.toString(), "-C", repositoryRoot.toString(),
                "worktree", "list", "--porcelain");

        ProcessResult result = run(command);
        if (result.exitCode() != 0) {
            if (result.stderr().toLowerCase(Locale.ROOT).contains("not a git repository")) {
                throw new NotAGitRepositoryException(repositoryRoot);
            }
            throw new GitCommandFailedException(command, result.exitCode(), ProcessRunner.excerpt(result.stderr()));
        }
        return parse(result.stdout());
    }

    /**
     * Deletes the worktree at {@code worktreePath} and (when known) its
     * branch in one step: {@code git worktree remove <path>} followed by
     * {@code git branch -D <branch>} (worktree handoff: the one-click 🗑
     * on an <em>unopened</em> row -- the only bare worktree operations the
     * application runs directly besides creation; a worktree with a live
     * session is instead cleaned up by Claude via the Finish hand-off).
     *
     * <p>Refuses the repository's main checkout: the future completes
     * exceptionally with an {@link IllegalArgumentException} when
     * {@code worktreePath} is the main checkout itself.</p>
     */
    public CompletableFuture<Void> remove(Path repositoryRoot, Path worktreePath, Optional<String> branch) {
        return CompletableFuture.supplyAsync(() -> {
            removeBlocking(repositoryRoot, worktreePath, branch, false);
            return null;
        }, executor);
    }

    /**
     * As {@link #remove}, but removes with {@code --force} up front,
     * discarding any uncommitted work in the worktree. Only for a
     * user-confirmed retry after {@link #remove} failed with a
     * {@link WorktreeNotCleanException}; still refuses the main checkout.
     */
    public CompletableFuture<Void> removeForced(Path repositoryRoot, Path worktreePath, Optional<String> branch) {
        return CompletableFuture.supplyAsync(() -> {
            removeBlocking(repositoryRoot, worktreePath, branch, true);
            return null;
        }, executor);
    }

    /** Synchronous form of {@link #remove}/{@link #removeForced}, package-private for tests. */
    void removeBlocking(Path repositoryRoot, Path worktreePath, Optional<String> branch, boolean force) {
        Path git = locator.locate()
                .orElseThrow(() -> new GitExecutableNotFoundException(locator.describeSearched()));

        // Guard OFF the main checkout: deleting it would destroy the
        // repository. Resolved against the live worktree list rather than
        // trusting the caller's idea of which entry is main -- and the same
        // pass captures the target's own entry, whose lock state decides
        // below whether a refusal is a force-able lock or real dirt.
        Path normalizedTarget = worktreePath.toAbsolutePath().normalize();
        Optional<Worktree> targetEntry = Optional.empty();
        for (Worktree worktree : listBlocking(repositoryRoot)) {
            if (samePath(worktree.path(), normalizedTarget)) {
                if (worktree.mainCheckout()) {
                    throw new IllegalArgumentException(
                            "Refusing to remove the main checkout at " + normalizedTarget);
                }
                targetEntry = Optional.of(worktree);
            }
        }

        if (force) {
            // User-confirmed destructive delete: double-force overrides both
            // uncommitted work and a lock -- a single --force discards the
            // former but git still refuses a locked worktree without the second.
            forceRemove(git, repositoryRoot, normalizedTarget);
        } else {
            List<String> removeCommand = List.of(
                    git.toString(), "-C", repositoryRoot.toString(),
                    "worktree", "remove", normalizedTarget.toString());
            ProcessResult removed = run(removeCommand);
            if (removed.exitCode() != 0) {
                if (targetEntry.map(Worktree::locked).orElse(false)) {
                    // A lock is a deliberate "do not remove" marker, not lost
                    // work, and no plain --force overrides it: surface it (with
                    // git's reason) so the UI can ask before double-forcing.
                    throw new WorktreeLockedException(normalizedTarget, targetEntry.flatMap(Worktree::lockReason));
                } else if (mayRetryWithForce(git, normalizedTarget)) {
                    forceRemove(git, repositoryRoot, normalizedTarget);
                } else if (Files.exists(normalizedTarget) && !isClean(git, normalizedTarget)) {
                    // The refusal protects real uncommitted work: report it as
                    // such so the UI can offer a confirmed forced delete.
                    throw new WorktreeNotCleanException(normalizedTarget);
                } else {
                    throw new GitCommandFailedException(removeCommand, removed.exitCode(), ProcessRunner.excerpt(removed.stderr()));
                }
            }
        }

        if (branch.isPresent() && !branch.get().isBlank()) {
            // --end-of-options: a branch name that looks like an option must
            // reach git as a branch name, never be parsed as a flag.
            List<String> branchCommand = List.of(
                    git.toString(), "-C", repositoryRoot.toString(),
                    "branch", "-D", "--end-of-options", branch.get());
            ProcessResult deleted = run(branchCommand);
            if (deleted.exitCode() != 0) {
                throw new GitCommandFailedException(branchCommand, deleted.exitCode(), ProcessRunner.excerpt(deleted.stderr()));
            }
        }
    }

    /**
     * Captures the main checkout's merge-relevant state on this service's
     * background executor. Never throws for "not on a branch" or "branch
     * missing" -- those are reported in the result, because the caller has
     * distinct user-facing copy for each.
     */
    public CompletableFuture<MergeTarget> inspectMergeTarget(Path mainCheckout, String branch) {
        return CompletableFuture.supplyAsync(() -> inspectMergeTargetBlocking(mainCheckout, branch), executor);
    }

    /** Synchronous form of {@link #inspectMergeTarget}, package-private for tests. */
    MergeTarget inspectMergeTargetBlocking(Path mainCheckout, String branch) {
        Path git = locator.locate()
                .orElseThrow(() -> new GitExecutableNotFoundException(locator.describeSearched()));
        return inspectMergeTarget(git, mainCheckout, branch);
    }

    private static MergeTarget inspectMergeTarget(Path git, Path mainCheckout, String branch) {
        BaseState base = baseStateOf(git, mainCheckout);
        // refs/heads/ so a tag or a file of the same name can never answer
        // for the branch, and so the argument cannot start with '-'.
        Optional<String> tip = firstLine(run(List.of(
                git.toString(), "-C", mainCheckout.toString(),
                "rev-parse", "--verify", "--quiet", "--end-of-options", "refs/heads/" + branch)));
        return new MergeTarget(base.branch(), base.headOid(), tip, base.inProgress());
    }

    /**
     * Which sequencer operation the main checkout is in the middle of.
     * Rebase is checked first: a conflicted rebase records {@code
     * REBASE_HEAD} plus a {@code rebase-merge}/{@code rebase-apply}
     * directory rather than {@code MERGE_HEAD}, and the directory is the
     * signal that survives every git version we support.
     */
    private static MergeTarget.InProgress inProgressIn(Path git, Path mainCheckout) {
        Path gitDir = absoluteGitDir(git, mainCheckout);
        if (Files.isDirectory(gitDir.resolve("rebase-merge")) || Files.isDirectory(gitDir.resolve("rebase-apply"))) {
            return MergeTarget.InProgress.REBASE;
        }
        if (Files.exists(gitDir.resolve("MERGE_HEAD"))) {
            return MergeTarget.InProgress.MERGE;
        }
        if (Files.exists(gitDir.resolve("CHERRY_PICK_HEAD"))) {
            return MergeTarget.InProgress.CHERRY_PICK;
        }
        if (Files.exists(gitDir.resolve("REVERT_HEAD"))) {
            return MergeTarget.InProgress.REVERT;
        }
        if (Files.exists(gitDir.resolve("BISECT_LOG"))) {
            return MergeTarget.InProgress.BISECT;
        }
        return MergeTarget.InProgress.NONE;
    }

    private static Path absoluteGitDir(Path git, Path checkout) {
        List<String> command = List.of(
                git.toString(), "-C", checkout.toString(), "rev-parse", "--absolute-git-dir");
        ProcessResult result = run(command);
        if (result.exitCode() != 0) {
            throw new GitCommandFailedException(command, result.exitCode(), ProcessRunner.excerpt(result.stderr()));
        }
        return Path.of(result.stdout().strip());
    }

    /** The command's first output line, or empty when it failed or printed nothing. */
    private static Optional<String> firstLine(ProcessResult result) {
        if (result.exitCode() != 0) {
            return Optional.empty();
        }
        String value = result.stdout().strip();
        return value.isEmpty() ? Optional.empty() : Optional.of(value.lines().findFirst().orElse(value));
    }

    /**
     * Whether the worktree at {@code worktree} holds uncommitted work,
     * asked with the same predicate {@link #remove} uses -- see {@link
     * #isClean(Path, Path)} for why {@code --ignore-submodules=dirty}
     * matters here: gating the merge on {@code GitStatus.dirty()} instead
     * would leave the action permanently disabled in any repository with a
     * build-patched submodule, Drydock's own included.
     */
    public CompletableFuture<Boolean> isWorktreeClean(Path worktree) {
        return CompletableFuture.supplyAsync(() -> {
            Path git = locator.locate()
                    .orElseThrow(() -> new GitExecutableNotFoundException(locator.describeSearched()));
            return isClean(git, worktree);
        }, executor);
    }

    /**
     * What a merge attempt actually did, established by inspecting the
     * repository rather than by trusting an exit code or parsing stderr.
     *
     * <p>Ancestry ({@code merge-base --is-ancestor}) is deliberately NOT
     * the success oracle: a bare {@code checkout <branch>} or a {@code
     * reset --hard} in the main checkout makes it true, and both are
     * reachable by the agent that resolves conflicts -- after which the
     * caller would delete the branch that holds the only copy of the work.
     * {@link Merged} requires a commit on the expected base branch whose
     * parents are the recorded pre-merge HEAD and the recorded branch
     * tip.</p>
     */
    public sealed interface MergeVerdict {

        /** A real merge commit of the recorded tip now sits on the base branch. */
        record Merged(String mergeCommitOid) implements MergeVerdict { }

        /** The branch was already merged before the attempt; nothing to do but clean up. */
        record AlreadyMerged() implements MergeVerdict { }

        /** The merge stopped with unmerged index entries; {@code unmergedPaths} is never empty. */
        record Conflicted(List<String> unmergedPaths) implements MergeVerdict { }

        /** Nothing in progress and the branch is not merged -- from a poll, an aborted merge. */
        record NotMerged() implements MergeVerdict { }

        /** Git declined: a hook veto, a dirty base checkout, an unknown revision. */
        record Refused(String detail) implements MergeVerdict { }

        /** A repository state we did not predict; never treated as success. */
        record Indeterminate(String detail) implements MergeVerdict { }
    }

    /**
     * Runs {@code git merge --no-ff <branch>} in the main checkout and then
     * {@linkplain #verifyMerge verifies} the result. {@code target} must be
     * the {@link MergeTarget} captured immediately before this call: its
     * recorded HEAD and branch tip are what the verification is against.
     *
     * <p>The future completes exceptionally only for infrastructure
     * failures (no git executable, an unreadable repository). A merge that
     * did not happen is a {@link MergeVerdict}, not an exception, because
     * every outcome has its own user-facing copy and its own next step.</p>
     */
    public CompletableFuture<MergeVerdict> merge(Path mainCheckout, String branch, MergeTarget target) {
        return CompletableFuture.supplyAsync(() -> mergeBlocking(mainCheckout, branch, target), executor);
    }

    /**
     * Synchronous form of {@link #merge}, package-private so a test can
     * drive it on a thread of its own and interrupt that thread directly --
     * {@link CompletableFuture#cancel} does not interrupt a running task, so
     * exercising the interrupt path requires calling this directly.
     */
    MergeVerdict mergeBlocking(Path mainCheckout, String branch, MergeTarget target) {
        Path git = locator.locate()
                .orElseThrow(() -> new GitExecutableNotFoundException(locator.describeSearched()));
        // --end-of-options: a branch name that looks like an option must
        // reach git as a branch name, never be parsed as a flag.
        List<String> command = List.of(
                git.toString(), "-C", mainCheckout.toString(),
                "merge", "--no-ff", "--end-of-options", branch);
        ProcessResult result = null;
        Optional<String> spawnFailureDetail = Optional.empty();
        try {
            // Deliberately catches only GitCommandFailedException, not its
            // sibling GitCommandInterruptedException: a timeout or IO
            // failure still leaves a verdict to establish (git may have left
            // the repository mid-merge -- a real conflict, or even a
            // completed commit right before the timeout fired -- and
            // verify() below inspects that state directly instead of
            // trusting this outcome either way), but an interrupt means the
            // caller (future cancellation, executor shutdown) no longer
            // wants any more work done, including the read-only git queries
            // verify() would run. So an interrupt is left uncaught here and
            // propagates with the thread's interrupt status still set (see
            // run()) -- never silently swallowed, and never turned into a
            // verdict.
            result = run(command, MERGE_PROCESS_TIMEOUT);
        } catch (GitCommandFailedException e) {
            spawnFailureDetail = Optional.of(e.stderrExcerpt());
        }
        MergeVerdict verdict = verify(git, mainCheckout, target);
        if (verdict instanceof MergeVerdict.NotMerged) {
            if (spawnFailureDetail.isPresent()) {
                return new MergeVerdict.Refused(spawnFailureDetail.get());
            }
            return result.exitCode() == 0
                    ? new MergeVerdict.Indeterminate(
                            "git reported success but " + branch + " is not merged")
                    : new MergeVerdict.Refused(ProcessRunner.excerpt(result.stderr()));
        }
        return verdict;
    }

    /**
     * Re-verifies {@code target} without touching the repository -- the poll
     * used while an agent resolves conflicts in the main checkout.
     */
    public CompletableFuture<MergeVerdict> verifyMerge(Path mainCheckout, MergeTarget target) {
        return CompletableFuture.supplyAsync(() -> {
            Path git = locator.locate()
                    .orElseThrow(() -> new GitExecutableNotFoundException(locator.describeSearched()));
            return verify(git, mainCheckout, target);
        }, executor);
    }

    private static MergeVerdict verify(Path git, Path mainCheckout, MergeTarget target) {
        String tip = target.branchTipOid().orElseThrow(() -> new IllegalArgumentException(
                "inspectMergeTarget found no branch tip; the merge should never have been attempted"));
        if (target.baseBranch().isEmpty()) {
            // Not relied upon: a later task gates the merge action off a
            // detached main checkout before this is ever called. But this is
            // the boundary the destructive branch-delete step is gated on, so
            // it must not depend on a caller upstream getting that right --
            // MergeTarget's own javadoc calls a detached HEAD "a refusal, not
            // a label", and Optional.empty().equals(Optional.empty()) would
            // otherwise let the branch-equality check below wave it through.
            return new MergeVerdict.Indeterminate("the main checkout was on a detached HEAD, not a branch");
        }
        BaseState now = baseStateOf(git, mainCheckout);
        if (!now.branch().equals(target.baseBranch())) {
            return new MergeVerdict.Indeterminate("the main checkout is no longer on "
                    + target.baseBranch().orElse("the branch it was on"));
        }
        if (!now.headOid().equals(target.baseHeadOid())) {
            List<String> parents = parentsOf(git, mainCheckout, now.headOid());
            if (parents.contains(target.baseHeadOid()) && parents.contains(tip)) {
                return new MergeVerdict.Merged(now.headOid());
            }
            return new MergeVerdict.Indeterminate(target.baseBranch().orElse("the base branch")
                    + " moved to a commit that is not a merge of the branch");
        }
        List<String> diffCommand = List.of(
                git.toString(), "-C", mainCheckout.toString(),
                "diff", "--name-only", "--diff-filter=U");
        List<String> unmerged = linesOf(diffCommand, run(diffCommand));
        if (!unmerged.isEmpty()) {
            return new MergeVerdict.Conflicted(unmerged);
        }
        if (now.inProgress() == MergeTarget.InProgress.MERGE) {
            // (now.inProgress() comes from BaseState -- see baseStateOf below.)
            // MERGE_HEAD with no unmerged paths left: either a
            // pre-merge-commit hook vetoed the commit (git commit would not
            // re-run it), or an agent has staged every conflict's resolution
            // but not yet committed. A poll cannot tell which apart, and
            // "keep waiting" is the right next step either way.
            return new MergeVerdict.Refused("a merge is open in the main checkout but not committed");
        }
        if (isAncestor(git, mainCheckout, tip)) {
            return new MergeVerdict.AlreadyMerged();
        }
        return new MergeVerdict.NotMerged();
    }

    /** The base branch, its HEAD oid, and any sequencer operation in progress. */
    private record BaseState(Optional<String> branch, String headOid, MergeTarget.InProgress inProgress) { }

    /**
     * The main checkout's own state, without the branch-tip lookup: what
     * verification reads. Split out so {@code verify} never has to name a
     * branch it does not care about.
     */
    private static BaseState baseStateOf(Path git, Path mainCheckout) {
        // --quiet: a detached HEAD is an empty result, not an error.
        Optional<String> branch = firstLine(run(List.of(
                git.toString(), "-C", mainCheckout.toString(), "symbolic-ref", "--quiet", "--short", "HEAD")));
        List<String> headCommand = List.of(
                git.toString(), "-C", mainCheckout.toString(), "rev-parse", "HEAD");
        ProcessResult head = run(headCommand);
        if (head.exitCode() != 0) {
            throw new GitCommandFailedException(headCommand, head.exitCode(), ProcessRunner.excerpt(head.stderr()));
        }
        return new BaseState(branch, head.stdout().strip(), inProgressIn(git, mainCheckout));
    }

    /**
     * {@code merge-base --is-ancestor} uses exit 1 for a real "no" and any
     * other non-zero code for a failure (bad revision, corrupt repository) --
     * collapsing both to {@code false} would report {@link MergeVerdict#NotMerged()}
     * for an error the user never saw.
     */
    private static boolean isAncestor(Path git, Path mainCheckout, String oid) {
        List<String> command = List.of(git.toString(), "-C", mainCheckout.toString(),
                "merge-base", "--is-ancestor", "--end-of-options", oid, "HEAD");
        ProcessResult result = run(command);
        if (result.exitCode() == 0) {
            return true;
        }
        if (result.exitCode() == 1) {
            return false;
        }
        throw new GitCommandFailedException(command, result.exitCode(), ProcessRunner.excerpt(result.stderr()));
    }

    /** The parent oids of {@code oid}, from {@code rev-list --parents -n1}. */
    private static List<String> parentsOf(Path git, Path mainCheckout, String oid) {
        List<String> command = List.of(git.toString(), "-C", mainCheckout.toString(),
                "rev-list", "--parents", "-n", "1", "--end-of-options", oid);
        ProcessResult result = run(command);
        if (result.exitCode() != 0) {
            throw new GitCommandFailedException(command, result.exitCode(), ProcessRunner.excerpt(result.stderr()));
        }
        List<String> tokens = List.of(result.stdout().strip().split("\\s+"));
        return tokens.size() <= 1 ? List.of() : tokens.subList(1, tokens.size());
    }

    /**
     * A non-zero exit (locked or corrupt index, unreadable worktree) is never
     * silently equal to "no conflicted paths" -- verification would proceed
     * to {@code Refused}/{@code NotMerged} and the user would be told
     * something untrue about a real error.
     */
    private static List<String> linesOf(List<String> command, ProcessResult result) {
        if (result.exitCode() != 0) {
            throw new GitCommandFailedException(command, result.exitCode(), ProcessRunner.excerpt(result.stderr()));
        }
        if (result.stdout().isBlank()) {
            return List.of();
        }
        return result.stdout().strip().lines().toList();
    }

    /**
     * Removes {@code worktree} with a doubled {@code --force}. The second
     * {@code --force} is what lets git remove a <em>locked</em> worktree; a
     * single one clears only uncommitted work. Reached solely after the
     * caller has decided the removal is safe -- a user-confirmed override of
     * a lock or dirt, or one of {@link #mayRetryWithForce}'s no-work-to-lose
     * refusals (never a lock: those are diverted to confirmation upstream).
     */
    private static void forceRemove(Path git, Path repositoryRoot, Path worktree) {
        List<String> command = List.of(
                git.toString(), "-C", repositoryRoot.toString(),
                "worktree", "remove", "--force", "--force", worktree.toString());
        ProcessResult forced = run(command);
        if (forced.exitCode() != 0) {
            throw new GitCommandFailedException(command, forced.exitCode(), ProcessRunner.excerpt(forced.stderr()));
        }
    }

    /**
     * Whether a refused plain {@code git worktree remove} may be retried
     * with {@code --force}. Only two refusals qualify, and neither can cost
     * the user work:
     *
     * <ul>
     *   <li>The worktree's files are already gone (rm -rf'd outside the
     *       app): git refuses with "validation failed, cannot remove
     *       working tree: '&lt;path&gt;/.git' does not exist" and there is no
     *       working copy left to lose.</li>
     *   <li>The worktree has submodules checked out into it. Git's
     *       {@code validate_no_submodules} guard runs only on the non-force
     *       path and refuses <em>unconditionally</em> -- "working trees
     *       containing submodules cannot be moved or removed" -- however
     *       clean the worktree is, so a repository with a vendored
     *       submodule (Drydock's own {@code third_party/ghostty}) could
     *       otherwise never be deleted from the sidebar. We re-run the
     *       cleanliness check git skipped and force only when it passes.</li>
     * </ul>
     */
    private static boolean mayRetryWithForce(Path git, Path worktree) {
        if (!Files.exists(worktree.resolve(".git"))) {
            return true;
        }
        return hasSubmodulesCheckedOut(git, worktree) && isClean(git, worktree);
    }

    /**
     * Mirrors git's own condition: a submodule counts as present only once
     * its git dir has been created under the worktree's
     * {@code modules/} directory ({@code git submodule update --init}),
     * which is why an uninitialized submodule does not block a plain
     * remove. Detected from the directory rather than git's message, whose
     * wording is localized.
     */
    private static boolean hasSubmodulesCheckedOut(Path git, Path worktree) {
        List<String> command = List.of(git.toString(), "-C", worktree.toString(), "rev-parse", "--absolute-git-dir");
        ProcessResult result = run(command);
        if (result.exitCode() != 0) {
            return false;
        }
        return Files.isDirectory(Path.of(result.stdout().strip()).resolve("modules"));
    }

    /**
     * Whether {@code worktree} holds uncommitted work of its own.
     *
     * <p>{@code --ignore-submodules=dirty} draws the line exactly where it
     * belongs: modified <em>content</em> inside a vendored submodule is
     * ignored, because the build leaves it that way on every run (Drydock
     * patches ghostty via {@code scripts/build-ghostty.sh}) and blocking on
     * it would make such a worktree undeletable -- but a changed submodule
     * <em>commit</em> is still reported, because bumping the vendored
     * revision is real uncommitted work in this worktree's index. Plain
     * {@code =all} would hide that bump and force it away.</p>
     */
    private static boolean isClean(Path git, Path worktree) {
        List<String> command = List.of(
                git.toString(), "-C", worktree.toString(),
                "status", "--porcelain", "--ignore-submodules=dirty");
        ProcessResult result = run(command);
        return result.exitCode() == 0 && result.stdout().isBlank();
    }

    private static boolean samePath(Path a, Path b) {
        try {
            return Files.isSameFile(a, b);
        } catch (IOException e) {
            return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
        }
    }

    @Override
    public void close() {
        if (ownsExecutor) {
            executor.shutdown();
        }
    }

    // ---- parsing: git worktree list --porcelain ----

    /**
     * Parses the porcelain worktree listing: stanzas separated by blank
     * lines, each starting with {@code worktree <path>} followed by
     * attribute lines ({@code HEAD <oid>}, {@code branch refs/heads/<name>},
     * {@code detached}, {@code bare}, {@code locked ...}, ...). The first
     * stanza is the main checkout.
     */
    static List<Worktree> parse(String stdout) {
        List<Worktree> worktrees = new ArrayList<>();
        Path path = null;
        Optional<String> branch = Optional.empty();
        boolean detached = false;
        boolean bare = false;
        boolean prunable = false;
        boolean locked = false;
        Optional<String> lockReason = Optional.empty();

        for (String rawLine : stdout.split("\n", -1)) {
            String line = rawLine.strip();
            if (line.isEmpty()) {
                if (path != null && !bare) {
                    worktrees.add(new Worktree(path, branch, worktrees.isEmpty(), detached, prunable, locked, lockReason));
                }
                path = null;
                branch = Optional.empty();
                detached = false;
                bare = false;
                prunable = false;
                locked = false;
                lockReason = Optional.empty();
            } else if (line.startsWith("worktree ")) {
                path = Path.of(line.substring("worktree ".length())).normalize();
            } else if (line.startsWith("branch ")) {
                String ref = line.substring("branch ".length());
                String prefix = "refs/heads/";
                branch = Optional.of(ref.startsWith(prefix) ? ref.substring(prefix.length()) : ref);
            } else if (line.equals("detached")) {
                detached = true;
            } else if (line.equals("bare")) {
                bare = true;
            } else if (line.equals("prunable") || line.startsWith("prunable ")) {
                prunable = true;
            } else if (line.equals("locked") || line.startsWith("locked ")) {
                locked = true;
                String reason = line.equals("locked") ? "" : line.substring("locked ".length()).strip();
                lockReason = reason.isEmpty() ? Optional.empty() : Optional.of(reason);
            }
        }
        if (path != null && !bare) {
            worktrees.add(new Worktree(path, branch, worktrees.isEmpty(), detached, prunable, locked, lockReason));
        }
        return List.copyOf(worktrees);
    }

    // ---- process execution (shared ProcessRunner, git-flavored failure translation) ----

    private static ProcessResult run(List<String> command) {
        return run(command, PROCESS_TIMEOUT);
    }

    private static ProcessResult run(List<String> command, Duration timeout) {
        try {
            return ProcessRunner.run(command, null, timeout);
        } catch (IOException e) {
            throw new GitCommandFailedException(command, -1, e.getMessage() == null ? "" : e.getMessage());
        } catch (ProcessTimeoutException e) {
            throw new GitCommandFailedException(command, -1,
                    "timed out after " + timeout.toSeconds() + "s (killed)");
        } catch (InterruptedException e) {
            // Distinct type from the timeout/IO cases above: an interrupt
            // means the caller no longer wants any git command run at all
            // (see merge()'s catch block), where a timeout still permits one
            // more read-only inspection. The flag is restored, never
            // cleared, so it survives to whichever frame is prepared to act
            // on it.
            Thread.currentThread().interrupt();
            throw new GitCommandInterruptedException(command);
        }
    }
}
