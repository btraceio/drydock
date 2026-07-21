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
 * (see {@link #mayRetryWithForce}).
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
     * bare entry has no {@link #branch()}.
     */
    public record Worktree(Path path, Optional<String> branch, boolean mainCheckout, boolean detached) {
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
            removeBlocking(repositoryRoot, worktreePath, branch);
            return null;
        }, executor);
    }

    /** Synchronous form of {@link #remove}, package-private for tests. */
    void removeBlocking(Path repositoryRoot, Path worktreePath, Optional<String> branch) {
        Path git = locator.locate()
                .orElseThrow(() -> new GitExecutableNotFoundException(locator.describeSearched()));

        // Guard OFF the main checkout: deleting it would destroy the
        // repository. Resolved against the live worktree list rather than
        // trusting the caller's idea of which entry is main.
        Path normalizedTarget = worktreePath.toAbsolutePath().normalize();
        for (Worktree worktree : listBlocking(repositoryRoot)) {
            if (worktree.mainCheckout() && samePath(worktree.path(), normalizedTarget)) {
                throw new IllegalArgumentException(
                        "Refusing to remove the main checkout at " + normalizedTarget);
            }
        }

        List<String> removeCommand = List.of(
                git.toString(), "-C", repositoryRoot.toString(),
                "worktree", "remove", normalizedTarget.toString());
        ProcessResult removed = run(removeCommand);
        if (removed.exitCode() != 0) {
            if (mayRetryWithForce(git, normalizedTarget)) {
                List<String> forceRemoveCommand = List.of(
                        git.toString(), "-C", repositoryRoot.toString(),
                        "worktree", "remove", "--force", normalizedTarget.toString());
                ProcessResult forced = run(forceRemoveCommand);
                if (forced.exitCode() != 0) {
                    throw new GitCommandFailedException(forceRemoveCommand, forced.exitCode(), ProcessRunner.excerpt(forced.stderr()));
                }
            } else {
                throw new GitCommandFailedException(removeCommand, removed.exitCode(), ProcessRunner.excerpt(removed.stderr()));
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

        for (String rawLine : stdout.split("\n", -1)) {
            String line = rawLine.strip();
            if (line.isEmpty()) {
                if (path != null && !bare) {
                    worktrees.add(new Worktree(path, branch, worktrees.isEmpty(), detached));
                }
                path = null;
                branch = Optional.empty();
                detached = false;
                bare = false;
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
            }
        }
        if (path != null && !bare) {
            worktrees.add(new Worktree(path, branch, worktrees.isEmpty(), detached));
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
