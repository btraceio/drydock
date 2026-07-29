package app.drydock.git;

import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.process.ProcessTimeoutException;

import java.io.File;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

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
 * <p>What this does instead, to the same end:</p>
 * <ol>
 *   <li>{@code git worktree add --detach <dir>} -- a new working tree at the
 *       current commit, main checkout untouched;</li>
 *   <li>{@code gh pr checkout <n> --branch <local>} run <em>inside</em> that
 *       worktree, where {@code gh} resolves the repository from the working
 *       directory and checks the PR out there.</li>
 * </ol>
 *
 * <p>A failure at step 2 leaves a detached worktree behind, which is
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

    private static final List<Path> GH_FALLBACKS = List.of(
            Path.of("/usr/local/bin/gh"),
            Path.of("/opt/homebrew/bin/gh"));

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

    public PrCheckoutService() {
        this(new GitExecutableLocator(), Executors.newVirtualThreadPerTaskExecutor(), true);
    }

    public PrCheckoutService(GitExecutableLocator locator, ExecutorService executor) {
        this(locator, executor, false);
    }

    private PrCheckoutService(GitExecutableLocator locator, ExecutorService executor, boolean ownsExecutor) {
        this.locator = locator;
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
    }

    /** The local branch name a PR is checked out under. */
    public static String localBranchFor(int prNumber) {
        return "pr-" + prNumber;
    }

    /**
     * The PR number {@code branch} was checked out for, if it is one of
     * ours. The inverse of {@link #localBranchFor(int)}, so a worktree can
     * be recognised as the pull request it holds rather than as a branch
     * that merely happens to be named {@code pr-something} -- a review
     * queue that could not tell the difference filed a checked-out PR under
     * AGENTS and diffed it against a guessed base.
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
        // purpose -- gh assigns the branch in step 2, and creating one here
        // would collide with it.
        ProcessResult added = run(List.of(git.toString(), "-C", repositoryRoot.toString(),
                "worktree", "add", "--detach", worktree.toString()), repositoryRoot, GIT_TIMEOUT);
        if (added.exitCode() != 0) {
            throw new PrCheckoutException("Could not create a worktree at " + worktree + ": "
                    + ProcessRunner.excerpt(added.stderr()));
        }

        // Step 2: gh resolves the repository from its working directory, so
        // running it INSIDE the new worktree checks the PR out there and
        // leaves the main checkout alone.
        String branch = localBranchFor(prNumber);
        try {
            ProcessResult checkedOut = run(List.of(gh.toString(), "pr", "checkout",
                    String.valueOf(prNumber), "--branch", branch), worktree, CHECKOUT_TIMEOUT);
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
        try {
            return ProcessRunner.run(command, workingDirectory, timeout);
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
        Optional<Path> cached = cachedGh;
        if (cached != null) {
            return cached;
        }
        Optional<Path> found = discoverGh();
        cachedGh = found;
        return found;
    }

    private static Optional<Path> discoverGh() {
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(Pattern.quote(File.pathSeparator))) {
                if (dir.isBlank()) {
                    continue;
                }
                Path candidate = Path.of(dir).resolve("gh");
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return Optional.of(candidate);
                }
            }
        }
        for (Path candidate : GH_FALLBACKS) {
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    @Override
    public void close() {
        if (ownsExecutor) {
            executor.shutdown();
        }
    }
}
