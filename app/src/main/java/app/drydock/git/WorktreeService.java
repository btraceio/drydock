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
 * (see {@link #mayRetryWithForce}). {@link #mergeIntoBase(Path, String)}
 * merges a worktree's branch into whatever base branch is checked out in
 * the main checkout ({@code git merge --no-ff}).
 *
 * <p>Mirrors {@link GitStatusService}'s process/executor style: argument
 * lists (never a shell string), all work on a background virtual-thread
 * executor, {@link CompletableFuture} results, package-private blocking
 * forms for tests.</p>
 */
public final class WorktreeService implements AutoCloseable {

    /** List/remove are quick local operations; a hung git must not park futures forever. */
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(15);

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
        // --quiet: a detached HEAD is an empty result, not an error.
        Optional<String> baseBranch = firstLine(run(List.of(
                git.toString(), "-C", mainCheckout.toString(), "symbolic-ref", "--quiet", "--short", "HEAD")));
        List<String> headCommand = List.of(
                git.toString(), "-C", mainCheckout.toString(), "rev-parse", "HEAD");
        ProcessResult head = run(headCommand);
        if (head.exitCode() != 0) {
            throw new GitCommandFailedException(headCommand, head.exitCode(), ProcessRunner.excerpt(head.stderr()));
        }
        // refs/heads/ so a tag or a file of the same name can never answer
        // for the branch, and so the argument cannot start with '-'.
        Optional<String> tip = firstLine(run(List.of(
                git.toString(), "-C", mainCheckout.toString(),
                "rev-parse", "--verify", "--quiet", "--end-of-options", "refs/heads/" + branch)));
        return new MergeTarget(baseBranch, head.stdout().strip(), tip, inProgressIn(git, mainCheckout));
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
     * Merges {@code branch} into whatever base branch is currently checked
     * out in the main checkout at {@code mainCheckout}
     * ({@code git merge --no-ff <branch>}), on this service's background
     * executor. The future completes exceptionally with a
     * {@link GitCommandFailedException} on conflict or any other failure --
     * merge conflicts are surfaced to the user rather than resolved
     * automatically, since there is no agent in the loop to reason about
     * them.
     */
    public CompletableFuture<Void> mergeIntoBase(Path mainCheckout, String branch) {
        return CompletableFuture.supplyAsync(() -> {
            mergeIntoBaseBlocking(mainCheckout, branch);
            return null;
        }, executor);
    }

    /** Synchronous form of {@link #mergeIntoBase}, package-private for tests. */
    void mergeIntoBaseBlocking(Path mainCheckout, String branch) {
        Path git = locator.locate()
                .orElseThrow(() -> new GitExecutableNotFoundException(locator.describeSearched()));

        // --end-of-options: a branch name that looks like an option must
        // reach git as a branch name, never be parsed as a flag.
        List<String> command = List.of(
                git.toString(), "-C", mainCheckout.toString(),
                "merge", "--no-ff", "--end-of-options", branch);
        ProcessResult result = run(command);
        if (result.exitCode() != 0) {
            throw new GitCommandFailedException(command, result.exitCode(), ProcessRunner.excerpt(result.stderr()));
        }
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
        try {
            return ProcessRunner.run(command, null, PROCESS_TIMEOUT);
        } catch (IOException e) {
            throw new GitCommandFailedException(command, -1, e.getMessage() == null ? "" : e.getMessage());
        } catch (ProcessTimeoutException e) {
            throw new GitCommandFailedException(command, -1,
                    "timed out after " + PROCESS_TIMEOUT.toSeconds() + "s (killed)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitCommandFailedException(command, -1, "interrupted while waiting for git");
        }
    }
}
