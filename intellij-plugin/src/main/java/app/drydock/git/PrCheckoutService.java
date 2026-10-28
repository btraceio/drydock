package app.drydock.git;

import app.drydock.app.LoginShellEnvironment;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.process.ProcessTimeoutException;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Checks a GitHub pull request out into its own worktree, so reviewing it
 * never disturbs the main checkout (Review handoff §6, the checkout gate).
 *
 * <p><strong>The handoff's literal command does not exist.</strong> It gives
 * the sequence as {@code gh pr checkout <n> --worktree}; {@code gh} has no
 * such flag (checked against 2.96: {@code gh pr checkout} takes
 * {@code --branch}, {@code --detach}, {@code --force} and
 * {@code --recurse-submodules}, and always operates on the working tree it
 * is run in). Running it in the repository root would therefore move the
 * <em>main checkout</em> onto the PR branch -- exactly what a reviewer must
 * not have happen to them mid-task.</p>
 *
 * <p>{@code gh} runs with {@code --force} because {@code pr-<n>} is
 * drydock's naming convention for PR checkouts, and an earlier review of the
 * same PR leaves it behind when its worktree is removed; a PR whose head
 * moved by a rebase has diverged from that leftover branch by the time it is
 * re-reviewed. Without {@code --force}, gh syncs the existing branch with
 * {@code git merge --ff-only} and dies with "Not possible to
 * fast-forward" -- a review that can never start again (verified against
 * gh 2.102: the plugin's copy missed this flag while the standalone app's
 * had it, so IDE reviews kept failing on re-reviewed PRs). With it, gh does
 * {@code git reset --hard} to the PR's current head, which is what reviewing
 * "the PR as it is now" means. That reset applies to any existing
 * {@code pr-<n>}, including one a user made by hand: its old tip is then
 * recoverable only from that branch's reflog. Nothing is reset under a live
 * worktree: the path above was just verified absent, and if some other
 * worktree still has {@code pr-<n>} checked out, git refuses the checkout
 * before any reset.</p>
 *
 * <p>Even that is still one gh-version-dependent behaviour too many, so
 * before {@code gh} runs, a leftover {@code pr-<n>} is deleted with git's
 * safe delete ({@code -d}), which removes it only when it is fully merged:
 * with no local {@code pr-<n>} to sync, gh only ever takes its clean
 * {@code checkout -b --track} path, which every gh version has. An unmerged
 * {@code pr-<n>} (a user's own commits, or a leftover a PR rebase diverged)
 * or one checked out in some other live worktree is not deleted; it is
 * handed to gh {@code --force}, which may reset an existing {@code pr-<n>}
 * to the PR head (recoverable via its reflog) or, for a held branch,
 * refuses and names that worktree, which is the actionable answer.</p>
 *
 * <p>What this does instead, to the same end:</p>
 * <ol>
 *   <li>{@code git worktree add --detach <dir>} -- a new working tree at the
 *       current commit, main checkout untouched;</li>
 *   <li>{@code git branch -d pr-<n>} -- a {@code pr-<n>} left by an earlier
 *       review, if one is left and fully merged; deleted rather than synced
 *       so the step below has nothing to fast-forward over. An unmerged or
 *       held {@code pr-<n>} is not deleted here; it is handed to step 3,
 *       whose {@code --force} may reset an existing {@code pr-<n>} to the PR
 *       head (recoverable via its reflog);</li>
 *   <li>{@code gh pr checkout <n> --branch <local> --force} run <em>inside</em> that
 *       worktree, where {@code gh} resolves the repository from the working
 *       directory and checks the PR out there.</li>
 * </ol>
 *
 * <p>A failure after step 1 leaves a detached worktree behind, which is
 * removed before the failure is reported: a half-made checkout is worse
 * than none, because the next attempt would then collide with it.</p>
 *
 * <p>This is deliberately not part of {@link GhCliService}, whose contract
 * is read-only observation. Checking a PR out changes the working tree.</p>
 */
public final class PrCheckoutService implements AutoCloseable {

    private static final Logger LOG = System.getLogger(PrCheckoutService.class.getName());

    /** A network fetch of a whole branch; long, but never unbounded. */
    private static final Duration CHECKOUT_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(60);

    /** Why a checkout could not be made. Each is a distinct, actionable message. */
    public static final class PrCheckoutException extends RuntimeException {
        public PrCheckoutException(String message) {
            super(message);
        }

        public PrCheckoutException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final GitExecutableLocator locator;
    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private volatile Optional<Path> cachedGh;
    /** Test seams: the gh executable to run and the child PATH; null = the production discovery/live login-shell PATH. */
    private final Supplier<Optional<Path>> ghOverride;
    private final Supplier<String> childPathOverride;

    public PrCheckoutService() {
        this(new GitExecutableLocator(), Executors.newVirtualThreadPerTaskExecutor(), true);
    }

    public PrCheckoutService(GitExecutableLocator locator, ExecutorService executor) {
        this(locator, executor, false);
    }

    private PrCheckoutService(GitExecutableLocator locator, ExecutorService executor, boolean ownsExecutor) {
        this(locator, executor, ownsExecutor, null, null);
    }

    /** Package-private seams: pin the gh executable and the child PATH so tests can script a wrapper gh. */
    PrCheckoutService(GitExecutableLocator locator, ExecutorService executor, boolean ownsExecutor,
                      Supplier<Optional<Path>> ghOverride, Supplier<String> childPathOverride) {
        this.locator = locator;
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
        this.ghOverride = ghOverride;
        this.childPathOverride = childPathOverride;
    }

    /** The local branch name a PR is checked out under. */
    public static String localBranchFor(int prNumber) {
        return "pr-" + prNumber;
    }

    /**
     * The PR number {@code branch} was checked out for, if its name follows
     * drydock's {@code pr-<n>} checkout convention. The inverse of {@link
     * #localBranchFor(int)}, so a worktree can be recognised as the pull
     * request it holds rather than as a branch with a non-numeric
     * {@code pr-} name such as {@code pr-fix-login} -- a review queue that
     * could not tell the difference filed a checked-out PR under AGENTS and
     * diffed it against a guessed base.
     *
     * <p>Recognition is by name alone: a hand-made {@code pr-<digits>}
     * branch is also treated as a PR checkout, because {@code pr-<n>} is a
     * naming convention, not a reserved or enforced namespace.</p>
     */
    public static Optional<Integer> pullRequestNumberOf(String branch) {
        if (branch == null || !branch.startsWith("pr-")) {
            return Optional.empty();
        }
        String digits = branch.substring(3);
        // Digits only: a hand-made "pr-fix-login" is somebody's branch, not
        // PR #fix-login, and Integer.parseInt would happily take "+7".
        if (digits.isEmpty() || !digits.chars().allMatch(Character::isDigit)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Integer.parseInt(digits));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Checks PR {@code prNumber} of the repository at {@code repositoryRoot}
     * out into {@code worktreeDirectory}, on this service's background
     * executor.
     *
     * @return the worktree that now holds the PR
     */
    public CompletableFuture<Path> checkout(Path repositoryRoot, Path worktreeDirectory, int prNumber) {
        return CompletableFuture.supplyAsync(
                () -> checkoutBlocking(repositoryRoot, worktreeDirectory, prNumber), executor);
    }

    /** Synchronous form; must never run on the JavaFX application thread. */
    Path checkoutBlocking(Path repositoryRoot, Path worktreeDirectory, int prNumber) {
        if (prNumber <= 0) {
            throw new PrCheckoutException("PR number must be positive: " + prNumber);
        }
        Path gh = locateGh().orElseThrow(() -> new PrCheckoutException(
                "The GitHub CLI (gh) is not installed, so a pull request cannot be checked out. "
                        + "Install it, or use \"Read the patch only\"."));
        Path git = locator.locate()
                .orElseThrow(() -> new GitExecutableNotFoundException(locator.describeSearched()));

        Path worktree = worktreeDirectory.toAbsolutePath().normalize();
        if (Files.exists(worktree)) {
            throw new PrCheckoutException("There is already something at " + worktree
                    + "; remove it or pick another directory.");
        }

        // Step 1: a detached worktree at the current commit. Detached on
        // purpose -- gh assigns the branch in step 3, and creating one here
        // would collide with it.
        ProcessResult added = run(List.of(git.toString(), "-C", repositoryRoot.toString(),
                "worktree", "add", "--detach", worktree.toString()), repositoryRoot, GIT_TIMEOUT);
        if (added.exitCode() != 0) {
            throw new PrCheckoutException("Could not create a worktree at " + worktree + ": "
                    + ProcessRunner.excerpt(added.stderr()));
        }

        // Step 3: gh resolves the repository from its working directory, so
        // running it INSIDE the new worktree checks the PR out there and
        // leaves the main checkout alone.
        //
        // Step 2, before it runs: a pr-<n> left by an earlier review of this
        // PR is removed with git's safe delete, and only when it is fully
        // merged, so gh has no local branch to "sync" -- with one present,
        // gh's behaviour depends on its version and flags (merge --ff-only by
        // default, reset only with --force), and the merge dies on a branch a
        // PR rebase has diverged from. An unmerged pr-<n> (a user's own
        // commits, or a leftover a PR rebase diverged) or one checked out in
        // some other worktree is not deleted here and is handed to gh: its
        // --force resets an existing branch to the PR head, and for a held
        // branch its refusal names that worktree, which is the actionable
        // answer. Inside the same try as gh itself so a failure here cannot
        // strand the worktree of step 1.
        String branch = localBranchFor(prNumber);
        try {
            // -d, never -D: git refuses to delete a branch with unmerged
            // commits. Any non-zero exit (unmerged, held, absent) only means
            // "not deleted" and gh decides; an absent branch goes unlogged.
            // The delete runs under LC_ALL=C: the absent-branch suppression
            // below matches git's C-locale stderr wording ("error: branch
            // 'x' not found"), and under the host's LANG a localized git
            // words it differently ("nicht gefunden", …), which would log a
            // misleading WARNING on every first review. Exit codes cannot
            // discriminate -- absent, unmerged and held all exit 1 -- so the
            // pinned locale is the only deterministic discriminator. LC_ALL
            // outranks every other locale variable, so this holds regardless
            // of the inherited environment; ProcessRunner adds it to the
            // inherited env rather than replacing it.
            ProcessResult deleted = run(List.of(git.toString(), "-C", repositoryRoot.toString(),
                    "branch", "-d", branch), repositoryRoot, GIT_TIMEOUT, Map.of("LC_ALL", "C"));
            if (deleted.exitCode() != 0 && !deleted.stderr().contains("not found")) {
                LOG.log(Level.WARNING, "Left the existing branch " + branch + " in place before checking out PR #"
                        + prNumber + "; gh --force will decide: " + ProcessRunner.excerpt(deleted.stderr()));
            }
            ProcessResult checkedOut = run(List.of(gh.toString(), "pr", "checkout",
                    String.valueOf(prNumber), "--branch", branch, "--force"), worktree, CHECKOUT_TIMEOUT);
            if (checkedOut.exitCode() == 0) {
                return worktree;
            }
            throw new PrCheckoutException("Could not check out PR #" + prNumber + ": "
                    + ProcessRunner.excerpt(checkedOut.stderr()));
        } catch (RuntimeException failure) {
            // Timeouts, interrupts and process-launch failures from run()
            // also happen after step 1 and must not strand that worktree.
            removeQuietly(git, repositoryRoot, worktree);
            throw failure;
        }
    }

    private void removeQuietly(Path git, Path repositoryRoot, Path worktree) {
        try {
            ProcessResult removed = run(List.of(git.toString(), "-C", repositoryRoot.toString(),
                    "worktree", "remove", "--force", worktree.toString()), repositoryRoot, GIT_TIMEOUT);
            if (removed.exitCode() != 0) {
                LOG.log(Level.WARNING, "Could not clean up the worktree at " + worktree
                        + " after a failed PR checkout; remove it by hand: "
                        + ProcessRunner.excerpt(removed.stderr()));
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not clean up the worktree at " + worktree
                    + " after a failed PR checkout; remove it by hand", e);
        }
    }

    private ProcessResult run(List<String> command, Path workingDirectory, Duration timeout) {
        return run(command, workingDirectory, timeout, Map.of());
    }

    /** As {@link #run(List, Path, Duration)} with extra env entries ADDED to the child's inherited environment. */
    private ProcessResult run(List<String> command, Path workingDirectory, Duration timeout,
                              Map<String, String> extraEnv) {
        // The child gets the process PATH (the IDE's own, already repaired by
        // IntelliJ; currentRealPath() is System.getenv("PATH") here) with the
        // known real-install locations appended. Those appended dirs are the
        // fix: without them the Dogbrew gh-shim -- which re-execs a real gh
        // from PATH -- dies with "exec: gh: not found" when no real gh follows
        // it on PATH (the same defect GhCliService.runIn fixed for the
        // read-only calls; the checkout step was missed). The raw PATH is
        // null-checked before the fallbacks are appended: pathWithFallbacks
        // never returns null, so wrapping first would give the child only the
        // fallback dirs (losing /usr/bin) when no PATH is set; null means
        // inherit the environment.
        String childPath;
        if (childPathOverride != null) {
            childPath = childPathOverride.get();
        } else {
            String rawPath = LoginShellEnvironment.currentRealPath();
            childPath = rawPath == null ? null : GhCliService.pathWithFallbacks(rawPath);
        }
        try {
            if (childPath == null) {
                if (extraEnv.isEmpty()) {
                    return ProcessRunner.run(command, workingDirectory, timeout);
                }
                return ProcessRunner.run(command, new ProcessRunner.Options(
                        workingDirectory, timeout, false, extraEnv));
            }
            java.util.Map<String, String> env = new java.util.HashMap<>(extraEnv);
            env.put("PATH", childPath);
            return ProcessRunner.run(command, new ProcessRunner.Options(
                    workingDirectory, timeout, false, env));
        } catch (IOException e) {
            throw new PrCheckoutException("Could not run " + command.get(0) + ": " + e.getMessage(), e);
        } catch (ProcessTimeoutException e) {
            throw new PrCheckoutException(command.get(0) + " timed out after "
                    + timeout.toSeconds() + "s and was killed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PrCheckoutException("Interrupted while running " + command.get(0), e);
        }
    }

    private Optional<Path> locateGh() {
        if (ghOverride != null) {
            return ghOverride.get();
        }
        Optional<Path> cached = cachedGh;
        if (cached != null) {
            return cached;
        }
        Optional<Path> found = discoverGh();
        cachedGh = found;
        return found;
    }

    private static Optional<Path> discoverGh() {
        // Discovery reads the process PATH directly (in the plugin
        // currentRealPath() is System.getenv("PATH"); there is no late merge,
        // and children inherit the same snapshot). It may resolve the Dogbrew
        // gh-shim, which only works because run() appends the fallback dirs it
        // re-execs a real gh from -- discovery alone changes nothing.
        return GhExecutableLocator.locate(LoginShellEnvironment.currentRealPath());
    }

    @Override
    public void close() {
        if (ownsExecutor) {
            executor.shutdown();
        }
    }
}
