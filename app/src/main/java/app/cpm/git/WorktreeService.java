package app.cpm.git;

import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
 * {@code git branch -D}), guarded off the main checkout.
 *
 * <p>Mirrors {@link GitStatusService}'s process/executor style: argument
 * lists (never a shell string), all work on a background virtual-thread
 * executor, {@link CompletableFuture} results, package-private blocking
 * forms for tests.</p>
 */
public final class WorktreeService implements AutoCloseable {

    private static final Logger LOG = System.getLogger(WorktreeService.class.getName());

    /** Excerpt length cap for stderr embedded in error messages (plan section 20). */
    private static final int STDERR_EXCERPT_LIMIT = 2000;

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
            throw new GitCommandFailedException(command, result.exitCode(), excerpt(result.stderr()));
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
            // Directory already gone/corrupted on disk (e.g. rm -rf'd
            // outside the app): git refuses the plain remove with
            // "validation failed, cannot remove working tree: '<path>/.git'
            // does not exist". Safe to retry with --force here since the
            // worktree's files are already missing -- there's no working
            // copy left to lose.
            if (!java.nio.file.Files.exists(normalizedTarget.resolve(".git"))) {
                List<String> forceRemoveCommand = List.of(
                        git.toString(), "-C", repositoryRoot.toString(),
                        "worktree", "remove", "--force", normalizedTarget.toString());
                ProcessResult forced = run(forceRemoveCommand);
                if (forced.exitCode() != 0) {
                    throw new GitCommandFailedException(forceRemoveCommand, forced.exitCode(), excerpt(forced.stderr()));
                }
            } else {
                throw new GitCommandFailedException(removeCommand, removed.exitCode(), excerpt(removed.stderr()));
            }
        }

        if (branch.isPresent() && !branch.get().isBlank()) {
            List<String> branchCommand = List.of(
                    git.toString(), "-C", repositoryRoot.toString(),
                    "branch", "-D", branch.get());
            ProcessResult deleted = run(branchCommand);
            if (deleted.exitCode() != 0) {
                throw new GitCommandFailedException(branchCommand, deleted.exitCode(), excerpt(deleted.stderr()));
            }
        }
    }

    private static boolean samePath(Path a, Path b) {
        try {
            return java.nio.file.Files.isSameFile(a, b);
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

    // ---- process execution (mirrors GitStatusService.run) ----

    private record ProcessResult(int exitCode, String stdout, String stderr) {
    }

    private static ProcessResult run(List<String> command) {
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(false).start();
        } catch (IOException e) {
            throw new GitCommandFailedException(command, -1, e.getMessage() == null ? "" : e.getMessage());
        }

        // Drain stdout and stderr concurrently: reading one stream fully
        // before the other risks a deadlock if the unread pipe's OS buffer
        // fills first.
        StreamReader stdoutReader = new StreamReader(process.getInputStream());
        StreamReader stderrReader = new StreamReader(process.getErrorStream());
        Thread stdoutThread = Thread.ofVirtual().start(stdoutReader);
        Thread stderrThread = Thread.ofVirtual().start(stderrReader);

        int exitCode;
        try {
            exitCode = process.waitFor();
            stdoutThread.join();
            stderrThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new GitCommandFailedException(command, -1, "interrupted while waiting for git");
        }

        return new ProcessResult(exitCode, stdoutReader.result(), stderrReader.result());
    }

    private static final class StreamReader implements Runnable {
        private final InputStream stream;
        private volatile String result = "";

        StreamReader(InputStream stream) {
            this.stream = stream;
        }

        @Override
        public void run() {
            try {
                result = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                result = "";
                LOG.log(Level.DEBUG, "Failed reading git process stream", e);
            }
        }

        String result() {
            return result;
        }
    }

    private static String excerpt(String text) {
        String trimmed = text.strip();
        return trimmed.length() <= STDERR_EXCERPT_LIMIT ? trimmed : trimmed.substring(0, STDERR_EXCERPT_LIMIT) + "...";
    }
}
