package app.drydock.git;

import app.drydock.domain.SshRemote;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.process.ProcessTimeoutException;
import app.drydock.process.SshCommandBuilder;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Determines the branch/dirty/ahead-behind {@link GitStatus} summary for a
 * repository by invoking the installed {@code git} executable as a plain
 * process (plan section 6.7, 21: argument list, never a shell string).
 *
 * <p>Runs entirely on a background executor (plan section 18: "Never block
 * the JavaFX application thread on ... Git"); {@link #getStatus(Path)}
 * returns a {@link CompletableFuture} rather than blocking the caller.</p>
 */
public final class GitStatusService implements AutoCloseable {

    private static final Logger LOG = System.getLogger(GitStatusService.class.getName());

    /** Every command here is a quick read-only query; a hung git must not park futures forever. */
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(15);

    /** ssh exit code reserved for transport failure (everything git returns is < 255). */
    private static final int SSH_TRANSPORT_FAILURE = 255;

    private final GitExecutableLocator locator;
    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private final String sshExecutable;

    public GitStatusService() {
        this(new GitExecutableLocator());
    }

    public GitStatusService(GitExecutableLocator locator) {
        this(locator, Executors.newVirtualThreadPerTaskExecutor(), true);
    }

    /** For tests/callers that want to supply their own executor (and own its shutdown). */
    public GitStatusService(GitExecutableLocator locator, ExecutorService executor) {
        this(locator, executor, false);
    }

    /**
     * Test seam: swaps the {@code ssh} executable for a fake script (mirrors
     * {@link GitExecutableLocator}). Public so callers outside this package
     * (e.g. {@code app.drydock.app.RepositoryManagerTest}) can fake SSH
     * remote resolution too, rather than depending on a real reachable host.
     */
    public GitStatusService(GitExecutableLocator locator, String sshExecutable) {
        this(locator, Executors.newVirtualThreadPerTaskExecutor(), true, sshExecutable);
    }

    private GitStatusService(GitExecutableLocator locator, ExecutorService executor, boolean ownsExecutor) {
        this(locator, executor, ownsExecutor, "ssh");
    }

    private GitStatusService(GitExecutableLocator locator, ExecutorService executor, boolean ownsExecutor,
                              String sshExecutable) {
        this.locator = locator;
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
        this.sshExecutable = sshExecutable;
    }

    /**
     * Computes the Git status of {@code repositoryRoot} on this service's
     * background executor. The returned future completes exceptionally
     * with a {@link GitException} (wrapped, per {@link CompletableFuture}
     * convention, in a {@link java.util.concurrent.CompletionException})
     * on any failure.
     */
    public CompletableFuture<GitStatus> getStatus(Path repositoryRoot) {
        return CompletableFuture.supplyAsync(() -> getStatusBlocking(repositoryRoot), executor);
    }

    /** As {@link #getStatus(Path)}, but dispatching on where the repository actually lives. */
    public CompletableFuture<GitStatus> getStatus(GitTarget target) {
        return switch (target) {
            case GitTarget.Local local -> getStatus(local.root());
            case GitTarget.Remote remote ->
                    CompletableFuture.supplyAsync(() -> getRemoteStatusBlocking(remote.remote()), executor);
        };
    }

    GitStatus getRemoteStatusBlocking(SshRemote remote) {
        ProcessResult result = runSsh(SshCommandBuilder.remoteGitCommand(remote,
                List.of("status", "--porcelain=v2", "--branch", "-z")), remote);
        return parse(result.stdout());
    }

    /**
     * Resolves the toplevel of a candidate remote repo path via
     * {@code git rev-parse --show-toplevel} over ssh — the add flow's
     * validation, mirroring {@link #resolveRepositoryRoot(Path)}.
     */
    public CompletableFuture<String> resolveRemoteRepositoryRoot(SshRemote candidate) {
        return CompletableFuture.supplyAsync(() -> {
            ProcessResult result = runSsh(SshCommandBuilder.remoteGitCommand(candidate,
                    List.of("rev-parse", "--show-toplevel")), candidate);
            String topLevel = result.stdout().strip();
            if (topLevel.isEmpty()) {
                throw new GitCommandFailedException(List.of("ssh", candidate.host(), "git rev-parse"), 0,
                        "git rev-parse --show-toplevel produced no output");
            }
            return topLevel;
        }, executor);
    }

    /** Runs an ssh-wrapped git command, translating exit 255 into {@link SshUnreachableException}. */
    private ProcessResult runSsh(List<String> builtCommand, SshRemote remote) {
        List<String> command = new ArrayList<>(builtCommand);
        command.set(0, sshExecutable);
        ProcessResult result;
        try {
            result = ProcessRunner.run(command, Path.of(System.getProperty("user.home")),
                    SshCommandBuilder.REMOTE_GIT_TIMEOUT);
        } catch (IOException e) {
            throw new GitCommandFailedException(command, -1, e.getMessage() == null ? "" : e.getMessage());
        } catch (ProcessTimeoutException e) {
            throw new SshUnreachableException(remote.host(),
                    "timed out after " + SshCommandBuilder.REMOTE_GIT_TIMEOUT.toSeconds() + "s (killed)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitCommandFailedException(command, -1, "interrupted while waiting for ssh");
        }
        if (result.exitCode() == SSH_TRANSPORT_FAILURE) {
            throw new SshUnreachableException(remote.host(), ProcessRunner.excerpt(result.stderr()));
        }
        if (result.exitCode() != 0) {
            if (result.stderr().toLowerCase(Locale.ROOT).contains("not a git repository")) {
                throw new NotAGitRepositoryException(Path.of(remote.remotePath()));
            }
            throw new GitCommandFailedException(command, result.exitCode(), ProcessRunner.excerpt(result.stderr()));
        }
        return result;
    }

    /**
     * Resolves {@code directory} to its enclosing Git working-tree root via
     * {@code git rev-parse --show-toplevel} (plan section 10.1: "Git root
     * is detected with git rev-parse"), on this service's background
     * executor. Used by the "Add repository" flow to validate a
     * user-chosen directory before it is registered.
     */
    public CompletableFuture<Path> resolveRepositoryRoot(Path directory) {
        return CompletableFuture.supplyAsync(() -> resolveRepositoryRootBlocking(directory), executor);
    }

    /**
     * Synchronous form, exposed package-private so tests can assert on the
     * thrown exception type directly instead of unwrapping a
     * {@code CompletionException}. Must never be called from the JavaFX
     * application thread.
     */
    Path resolveRepositoryRootBlocking(Path directory) {
        Path git = locator.locate()
                .orElseThrow(() -> new GitExecutableNotFoundException(locator.describeSearched()));

        List<String> command = List.of(
                git.toString(), "-C", directory.toString(),
                "rev-parse", "--show-toplevel");

        ProcessResult result = run(command);
        if (result.exitCode() != 0) {
            if (result.stderr().toLowerCase(Locale.ROOT).contains("not a git repository")) {
                throw new NotAGitRepositoryException(directory);
            }
            throw new GitCommandFailedException(command, result.exitCode(), ProcessRunner.excerpt(result.stderr()));
        }
        String topLevel = result.stdout().strip();
        if (topLevel.isEmpty()) {
            throw new GitCommandFailedException(command, result.exitCode(),
                    "git rev-parse --show-toplevel produced no output");
        }
        return Path.of(topLevel).normalize();
    }

    /**
     * Creates a new git worktree at {@code worktreeDirectory} with a new
     * branch {@code branch}, forked from the repository's current HEAD
     * ({@code git worktree add <dir> -b <branch>}), on this service's
     * background executor. Merge and delete
     * ({@link WorktreeService#mergeIntoBase}, {@link WorktreeService#remove})
     * also run directly; only PR creation is handed off to the Claude
     * session in the terminal, since {@code gh pr create} needs the user's
     * own gh auth.
     */
    public CompletableFuture<Path> createWorktree(Path repositoryRoot, Path worktreeDirectory, String branch) {
        return createWorktree(repositoryRoot, worktreeDirectory, branch, Optional.empty());
    }

    /**
     * As {@link #createWorktree(Path, Path, String)}, but forks the new
     * branch from {@code startPoint} (any committish -- branch, tag, SHA)
     * instead of HEAD when present ({@code git worktree add <dir> -b
     * <branch> <startPoint>}).
     */
    public CompletableFuture<Path> createWorktree(Path repositoryRoot, Path worktreeDirectory, String branch,
                                                   Optional<String> startPoint) {
        return CompletableFuture.supplyAsync(
                () -> createWorktreeBlocking(repositoryRoot, worktreeDirectory, branch, startPoint), executor);
    }

    /**
     * Synchronous form, exposed package-private so tests can assert on the
     * thrown exception type directly instead of unwrapping a
     * {@code CompletionException}. Must never be called from the JavaFX
     * application thread.
     */
    Path createWorktreeBlocking(Path repositoryRoot, Path worktreeDirectory, String branch) {
        return createWorktreeBlocking(repositoryRoot, worktreeDirectory, branch, Optional.empty());
    }

    /** As {@link #createWorktreeBlocking(Path, Path, String)}, with an explicit fork-point. */
    Path createWorktreeBlocking(Path repositoryRoot, Path worktreeDirectory, String branch,
                                Optional<String> startPoint) {
        Path git = locator.locate()
                .orElseThrow(() -> new GitExecutableNotFoundException(locator.describeSearched()));

        Path normalizedDir = worktreeDirectory.toAbsolutePath().normalize();
        Path parent = normalizedDir.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new GitCommandFailedException(
                        List.of("mkdir", parent.toString()), -1,
                        e.getMessage() == null ? "could not create parent directory" : e.getMessage());
            }
        }

        List<String> command = new ArrayList<>(List.of(
                git.toString(), "-C", repositoryRoot.toString(),
                "worktree", "add", normalizedDir.toString(), "-b", branch));
        // --end-of-options: a start-point that looks like an option must
        // reach git as a committish, never be parsed as a flag.
        startPoint.filter(s -> !s.isBlank()).ifPresent(s -> command.addAll(List.of("--end-of-options", s)));

        ProcessResult result = run(command);
        if (result.exitCode() != 0) {
            if (result.stderr().toLowerCase(Locale.ROOT).contains("not a git repository")) {
                throw new NotAGitRepositoryException(repositoryRoot);
            }
            throw new GitCommandFailedException(command, result.exitCode(), ProcessRunner.excerpt(result.stderr()));
        }
        return normalizedDir;
    }

    /**
     * Lists local branch names ({@code git for-each-ref refs/heads}) for
     * the base-branch picker in the create-worktree modal, on this
     * service's background executor.
     */
    public CompletableFuture<List<String>> listLocalBranches(Path repositoryRoot) {
        return CompletableFuture.supplyAsync(() -> listLocalBranchesBlocking(repositoryRoot), executor);
    }

    /** Synchronous form of {@link #listLocalBranches}, package-private for tests. */
    List<String> listLocalBranchesBlocking(Path repositoryRoot) {
        Path git = locator.locate()
                .orElseThrow(() -> new GitExecutableNotFoundException(locator.describeSearched()));

        List<String> command = List.of(
                git.toString(), "-C", repositoryRoot.toString(),
                "for-each-ref", "--format=%(refname:short)", "refs/heads/");

        ProcessResult result = run(command);
        if (result.exitCode() != 0) {
            if (result.stderr().toLowerCase(Locale.ROOT).contains("not a git repository")) {
                throw new NotAGitRepositoryException(repositoryRoot);
            }
            throw new GitCommandFailedException(command, result.exitCode(), ProcessRunner.excerpt(result.stderr()));
        }
        return result.stdout().lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    /**
     * Summarizes what a worktree branch changes relative to {@code
     * baseBranch} (Finish-panel change summary): commits ahead plus the
     * per-file kind/insertions/deletions of {@code git diff base...HEAD}.
     * Read-only, like {@link #getStatus}.
     */
    public CompletableFuture<GitChangeSummary> getChangeSummary(Path worktreeRoot, String baseBranch) {
        return CompletableFuture.supplyAsync(() -> getChangeSummaryBlocking(worktreeRoot, baseBranch), executor);
    }

    /** Synchronous form of {@link #getChangeSummary}, package-private for tests. */
    GitChangeSummary getChangeSummaryBlocking(Path worktreeRoot, String baseBranch) {
        Path git = locator.locate()
                .orElseThrow(() -> new GitExecutableNotFoundException(locator.describeSearched()));

        int commitsAhead = 0;
        List<String> countCommand = List.of(
                git.toString(), "-C", worktreeRoot.toString(),
                "rev-list", "--count", "--end-of-options", baseBranch + "..HEAD");
        ProcessResult countResult = run(countCommand);
        if (countResult.exitCode() == 0) {
            commitsAhead = parseCountOrZero(countResult.stdout().strip());
        } else {
            // Cosmetic count only, so keep going -- but never silently.
            LOG.log(Level.WARNING, "git rev-list --count failed (exit " + countResult.exitCode() + ") in "
                    + worktreeRoot + ": " + ProcessRunner.excerpt(countResult.stderr()));
        }

        // Two read-only diffs against the merge base: --numstat for per-file
        // +/- counts, --name-status for the M/A/D kind letter; merged by path.
        List<String> numstatCommand = List.of(
                git.toString(), "-C", worktreeRoot.toString(),
                "diff", "--numstat", "--end-of-options", baseBranch + "...HEAD");
        ProcessResult numstat = run(numstatCommand);
        if (numstat.exitCode() != 0) {
            if (numstat.stderr().toLowerCase(Locale.ROOT).contains("not a git repository")) {
                throw new NotAGitRepositoryException(worktreeRoot);
            }
            throw new GitCommandFailedException(numstatCommand, numstat.exitCode(), ProcessRunner.excerpt(numstat.stderr()));
        }
        List<String> nameStatusCommand = List.of(
                git.toString(), "-C", worktreeRoot.toString(),
                "diff", "--name-status", "--end-of-options", baseBranch + "...HEAD");
        ProcessResult nameStatus = run(nameStatusCommand);
        if (nameStatus.exitCode() != 0) {
            // The kind letter degrades to "M"; the numstat rows still render.
            LOG.log(Level.WARNING, "git diff --name-status failed (exit " + nameStatus.exitCode() + ") in "
                    + worktreeRoot + ": " + ProcessRunner.excerpt(nameStatus.stderr()));
        }

        Map<String, String> kinds = new LinkedHashMap<>();
        if (nameStatus.exitCode() == 0) {
            for (String line : nameStatus.stdout().split("\n")) {
                String[] parts = line.split("\t");
                if (parts.length >= 2 && !parts[0].isBlank()) {
                    // Renames (R100\told\tnew) report the new path last.
                    kinds.put(parts[parts.length - 1], parts[0].substring(0, 1));
                }
            }
        }

        List<GitChangeSummary.ChangedFile> files = new ArrayList<>();
        for (String line : numstat.stdout().split("\n")) {
            String[] parts = line.split("\t");
            if (parts.length < 3 || parts[0].isBlank()) {
                continue;
            }
            // Binary files report "-" for both counts.
            int insertions = parseCountOrZero(parts[0]);
            int deletions = parseCountOrZero(parts[1]);
            String path = parts[parts.length - 1];
            files.add(new GitChangeSummary.ChangedFile(kinds.getOrDefault(path, "M"), path, insertions, deletions));
        }
        return new GitChangeSummary(commitsAhead, List.copyOf(files));
    }

    /**
     * Synchronous form, exposed package-private so tests can assert on the
     * thrown exception type directly instead of unwrapping a
     * {@code CompletionException}. Must never be called from the JavaFX
     * application thread.
     */
    GitStatus getStatusBlocking(Path repositoryRoot) {
        Path git = locator.locate()
                .orElseThrow(() -> new GitExecutableNotFoundException(locator.describeSearched()));

        List<String> command = List.of(
                git.toString(), "-C", repositoryRoot.toString(),
                "status", "--porcelain=v2", "--branch", "-z");

        ProcessResult result = run(command);
        if (result.exitCode() != 0) {
            if (result.stderr().toLowerCase(Locale.ROOT).contains("not a git repository")) {
                throw new NotAGitRepositoryException(repositoryRoot);
            }
            throw new GitCommandFailedException(command, result.exitCode(), ProcessRunner.excerpt(result.stderr()));
        }
        return parse(result.stdout());
    }

    @Override
    public void close() {
        if (ownsExecutor) {
            executor.shutdown();
        }
    }

    // ---- process execution (shared ProcessRunner, git-flavored failure translation) ----

    private static ProcessResult run(List<String> command) {
        try {
            return ProcessRunner.run(command, null, PROCESS_TIMEOUT);
        } catch (IOException e) {
            // The executable existed at locate()-time but could not actually be
            // launched (permissions changed, removed between check and use, etc).
            throw new GitCommandFailedException(command, -1, e.getMessage() == null ? "" : e.getMessage());
        } catch (ProcessTimeoutException e) {
            throw new GitCommandFailedException(command, -1,
                    "timed out after " + PROCESS_TIMEOUT.toSeconds() + "s (killed)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitCommandFailedException(command, -1, "interrupted while waiting for git");
        }
    }

    // ---- parsing: git status --porcelain=v2 --branch -z ----

    /**
     * Parses the NUL-separated output of
     * {@code git status --porcelain=v2 --branch -z}. Only extracts the
     * branch/upstream/ahead-behind header lines and whether any non-header
     * record is present (dirty); the individual file-change records
     * themselves are not modeled (plan section 25 Milestone 4 scope --
     * the full {@code GitFileChange} list belongs to Milestone 7).
     */
    static GitStatus parse(String stdout) {
        Map<String, String> headers = new LinkedHashMap<>();
        boolean dirty = false;

        // -1 limit keeps a trailing empty token (harmless; skipped below) but
        // more importantly preserves any genuinely empty intermediate tokens.
        for (String token : stdout.split("\\u0000", -1)) {
            if (token.isEmpty()) {
                continue;
            }
            if (token.startsWith("# ")) {
                String rest = token.substring(2);
                int sp = rest.indexOf(' ');
                if (sp < 0) {
                    headers.put(rest, "");
                } else {
                    headers.put(rest.substring(0, sp), rest.substring(sp + 1));
                }
            } else {
                // Any non-header record (ordinary "1 ...", rename "2 ...",
                // unmerged "u ...", untracked "? ...", ignored "! ..." -- or
                // the second, orig-path field of a rename record) means the
                // working tree is not clean.
                dirty = true;
            }
        }

        String head = headers.get("branch.head");
        String oid = headers.get("branch.oid");
        GitBranchState branch = (head == null || head.equals("(detached)"))
                ? new GitBranchState.Detached(oid == null || oid.isBlank() ? "unknown" : oid)
                : new GitBranchState.OnBranch(head);

        Optional<GitStatus.UpstreamStatus> upstream = Optional.empty();
        String upstreamRef = headers.get("branch.upstream");
        if (upstreamRef != null && !upstreamRef.isBlank()) {
            int ahead = 0;
            int behind = 0;
            String ab = headers.get("branch.ab");
            if (ab != null) {
                for (String part : ab.trim().split("\\s+")) {
                    if (part.startsWith("+")) {
                        ahead = parseCountOrZero(part.substring(1));
                    } else if (part.startsWith("-")) {
                        behind = parseCountOrZero(part.substring(1));
                    }
                }
            }
            upstream = Optional.of(new GitStatus.UpstreamStatus(upstreamRef, ahead, behind));
        }

        return new GitStatus(branch, dirty, upstream);
    }

    private static int parseCountOrZero(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
