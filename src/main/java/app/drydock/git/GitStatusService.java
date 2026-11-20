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
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

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

    /** git fetch reaches the network; it needs far longer than a local status query. */
    private static final Duration FETCH_TIMEOUT = Duration.ofMinutes(2);

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
     * background executor. The counterparts that end a worktree's life are
     * {@link WorktreeService#merge} -- which the UI runs as merge-and-finish,
     * handing conflicts off to the session's Claude and then polling until the
     * merge is confirmed -- and {@link WorktreeService#remove}, the destructive
     * worktree + branch step both that flow and the Finish panel's Delete
     * report step by step. Only PR creation is a blind hand-off to the Claude
     * session in the terminal, since {@code gh pr create} needs the user's own
     * gh auth.
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

        Path normalizedDir = prepareWorktreeParent(worktreeDirectory);

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

    /** Normalizes the target and creates its parent, as {@code git worktree add} will not. */
    private static Path prepareWorktreeParent(Path worktreeDirectory) {
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
        return normalizedDir;
    }

    /**
     * Creates a worktree at {@code worktreeDirectory} on an <em>existing</em>
     * branch, on this service's background executor. A local branch is
     * checked out as-is; a remote-tracking one gets a local branch named
     * {@code localName} that tracks it ({@code -b <localName> --track}) --
     * never a detached checkout. {@code localName} comes from
     * {@link BranchCatalog#localName}, so remote-name splitting stays in one
     * place.
     */
    public CompletableFuture<Path> addWorktreeForBranch(Path repositoryRoot, Path worktreeDirectory,
                                                        BranchRef branch, String localName) {
        return CompletableFuture.supplyAsync(
                () -> addWorktreeForBranchBlocking(repositoryRoot, worktreeDirectory, branch, localName), executor);
    }

    /** Synchronous form of {@link #addWorktreeForBranch}, package-private for tests. */
    Path addWorktreeForBranchBlocking(Path repositoryRoot, Path worktreeDirectory, BranchRef branch,
                                      String localName) {
        Path git = locator.locate()
                .orElseThrow(() -> new GitExecutableNotFoundException(locator.describeSearched()));

        Path normalizedDir = prepareWorktreeParent(worktreeDirectory);

        List<String> command = new ArrayList<>(List.of(
                git.toString(), "-C", repositoryRoot.toString(),
                "worktree", "add", normalizedDir.toString()));
        if (branch.remote()) {
            command.addAll(List.of("-b", localName, "--track"));
        }
        // --end-of-options: a ref that looks like an option must reach git as
        // a ref, never be parsed as a flag.
        command.addAll(List.of("--end-of-options", branch.name()));

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
     * Checks out an existing local branch {@code branch} in the repository at
     * {@code workingDirectory} <em>in place</em> ({@code git checkout
     * --end-of-options <branch>}), on this service's background executor.
     *
     * <p>Refuses a blank or dash-prefixed name <em>before</em> any git process
     * is spawned, so no {@link GitExecutableNotFoundException} can mask the
     * refusal and no argument can be parsed as an option. Every git-side
     * refusal (a branch already checked out in another worktree, local changes
     * a switch would overwrite) surfaces as a {@link GitCommandFailedException}
     * carrying git's own stderr excerpt, verbatim for the caller's refusal
     * sentences.</p>
     *
     * <p>Completes with the branch actually checked out -- verified via
     * {@link #currentBranchBlocking} rather than a bare exit code, because a
     * checkout can report success without leaving HEAD where the caller
     * asked. Must never be called from the JavaFX application thread.</p>
     */
    public CompletableFuture<Optional<String>> checkoutBranch(Path workingDirectory, String branch) {
        return CompletableFuture.supplyAsync(() -> checkoutBranchBlocking(workingDirectory, branch), executor);
    }

    /**
     * Synchronous form of {@link #checkoutBranch}, package-private for tests.
     * Neither git's exit code nor its stdout alone says the switch happened:
     * a symbolic read of HEAD is the authority.
     */
    Optional<String> checkoutBranchBlocking(Path workingDirectory, String branch) {
        // Pre-spawn refusals: a blank name selects nothing, and a dash-prefixed
        // one reaches git as an option no matter how the command is assembled.
        // Both are refused before locator.locate(), so a missing executable
        // cannot mask the refusal with GitExecutableNotFoundException.
        if (branch == null || branch.isBlank()) {
            throw new GitCommandFailedException(
                    List.of("git", "-C", workingDirectory.toString(), "checkout"),
                    -1, "branch name is blank");
        }
        if (branch.startsWith("-")) {
            throw new GitCommandFailedException(
                    List.of("git", "-C", workingDirectory.toString(), "checkout", "--end-of-options"),
                    -1, "branch name must not start with '-': " + branch);
        }

        Path git = locator.locate()
                .orElseThrow(() -> new GitExecutableNotFoundException(locator.describeSearched()));

        // --end-of-options: the branch must reach git as a ref, never as a
        // flag -- the belt to the pre-spawn dash refusal's braces.
        List<String> command = List.of(
                git.toString(), "-C", workingDirectory.toString(),
                "checkout", "--end-of-options", branch);

        ProcessResult result = run(command);
        if (result.exitCode() != 0) {
            if (result.stderr().toLowerCase(Locale.ROOT).contains("not a git repository")) {
                throw new NotAGitRepositoryException(workingDirectory);
            }
            throw new GitCommandFailedException(command, result.exitCode(), ProcessRunner.excerpt(result.stderr()));
        }

        // Verify rather than trust the exit code: HEAD must name the requested
        // branch after the switch. currentBranchBlocking (symbolic-ref on HEAD,
        // refs/heads/ stripped here) rather than symbolic-ref --short, whose
        // tag disambiguation answers "heads/<name>" for a branch that shares
        // its name with a tag -- a successful checkout misread as a refusal.
        Optional<String> checkedOut = currentBranchBlocking(workingDirectory);
        if (checkedOut.isEmpty() || !checkedOut.get().equals(branch)) {
            throw new GitCommandFailedException(command, result.exitCode(),
                    "checkout reported success but HEAD is "
                            + (checkedOut.isEmpty() ? "detached" : "on '" + checkedOut.get() + "'"));
        }
        return checkedOut;
    }

    /**
     * Updates every remote and drops stale remote-tracking refs
     * ({@code git fetch --all --prune}) so the branch picker can show
     * newly pushed branches, on this service's background executor.
     */
    public CompletableFuture<Void> fetchAll(Path repositoryRoot) {
        return CompletableFuture.supplyAsync(() -> {
            fetchAllBlocking(repositoryRoot);
            return null;
        }, executor);
    }

    /** Synchronous form of {@link #fetchAll}, package-private for tests. */
    void fetchAllBlocking(Path repositoryRoot) {
        Path git = locator.locate()
                .orElseThrow(() -> new GitExecutableNotFoundException(locator.describeSearched()));

        List<String> command = List.of(
                git.toString(), "-C", repositoryRoot.toString(), "fetch", "--all", "--prune");

        // Credentials must never be prompted for: stdin is discarded and
        // GIT_TERMINAL_PROMPT=0 makes an auth-needing remote fail fast with a
        // real message instead of parking on a prompt until FETCH_TIMEOUT.
        ProcessResult result = run(command, new ProcessRunner.Options(
                null, FETCH_TIMEOUT, true, Map.of("GIT_TERMINAL_PROMPT", "0")));
        if (result.exitCode() != 0) {
            if (result.stderr().toLowerCase(Locale.ROOT).contains("not a git repository")) {
                throw new NotAGitRepositoryException(repositoryRoot);
            }
            throw new GitCommandFailedException(command, result.exitCode(), ProcessRunner.excerpt(result.stderr()));
        }
    }

    /**
     * Where a branch that forks the repository's DEFAULT branch should
     * start from — the fork point for a task branch cut from the default.
     *
     * <p>{@link #ref()} is the ref to hand to {@code git worktree add
     * -- <ref>}: {@code origin/<name>} once a fetch has refreshed it (or
     * whenever it already exists and no fetch was asked for), the local
     * {@code <name>} only in a repository with no such remote-tracking
     * ref, and {@code "main"} when nothing resolves at all (the historical
     * fallback; the worktree add then fails visibly, which is better than
     * silently picking an arbitrary ref). {@link #name()} is the bare
     * default-branch name the resolution worked from, empty when nothing
     * resolved; callers use it to recognize a default-branch fork point
     * however the user spelled it (bare or {@code origin/}-qualified).</p>
     *
     * <p>{@link #fetched()} reports whether this call refreshed
     * {@code origin/<name>} ({@code git fetch origin <name>}, terminal
     * prompts disabled, bounded by {@link #FETCH_TIMEOUT});
     * {@link #fetchFailed()} reports that the fetch ran and failed, in
     * which case {@code ref()} is the best-known ref — a stale
     * {@code origin/<name>}, or the local branch — and {@link #fetchError()}
     * carries git's excerpt for the caller to surface as a warning. A
     * repository without an {@code origin} remote is not an error: the
     * fetch is simply skipped and {@link #fetched()} stays false.</p>
     */
    public record DefaultBranchFork(String ref, Optional<String> name, boolean fetched,
                                    boolean fetchFailed, Optional<String> fetchError) {
        public DefaultBranchFork {
            Objects.requireNonNull(ref, "ref");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(fetchError, "fetchError");
        }
    }

    /**
     * Resolves where a branch that forks the repository's DEFAULT branch
     * should start from. {@code fetch} controls whether {@code origin/<name>}
     * is refreshed first; pass false for a local probe (the prompt) and true
     * at the point the fork is actually made. Best-effort: a failed fetch
     * never fails the future — it is reported on the result ({@link
     * DefaultBranchFork#fetchFailed()}) so the caller can fork from the
     * last-known ref and say so.
     */
    public CompletableFuture<DefaultBranchFork> defaultBranchFork(Path repositoryRoot, boolean fetch) {
        return CompletableFuture.supplyAsync(() -> defaultBranchForkBlocking(repositoryRoot, fetch), executor);
    }

    /**
     * Serializes {@code git fetch origin <default>} per repository. Two
     * task launches starting in the same repository at the same moment
     * would otherwise race the same remote-tracking ref and one fetch
     * would lose with "cannot lock ref" -- spurious, and the loser is the
     * launch whose fork would then warn about a stale base it actually
     * got from its sibling's fetch. Unbounded on purpose: one small entry
     * per distinct repository the app has forked in, a handful on any real
     * desktop; a bounded map would evict a lock while its holder is
     * mid-fetch, which is worse than the memory it saves.
     */
    private static final ConcurrentHashMap<String, Object> FETCH_LOCKS = new ConcurrentHashMap<>();

    /** The repository identity the per-repo fetch lock is keyed on. */
    private static String fetchLockKey(Path repositoryRoot) {
        return repositoryRoot.toAbsolutePath().normalize().toString();
    }

    /**
     * {@code git fetch origin +<branch>:refs/remotes/origin/<branch>}: one
     * branch, its destination pinned by the explicit refspec regardless of
     * the remote's configured refspecs.
     */
    static List<String> fetchBranchCommand(Path git, Path repositoryRoot, String branch) {
        return List.of(git.toString(), "-C", repositoryRoot.toString(), "fetch", "origin",
                "+" + branch + ":refs/remotes/origin/" + branch);
    }

    /**
     * Runs {@link #fetchBranchCommand} under the repository's
     * {@link #FETCH_LOCKS} monitor, credentials never prompted for (exactly
     * as in {@link #fetchAllBlocking}). Best-effort: the answer is git's
     * failure excerpt, empty when the fetch succeeded; a timeout or a spawn
     * failure is reported the same way as a non-zero exit, never thrown.
     * {@code repositoryRoot} must be the main checkout every caller keys on,
     * not a linked worktree of it, or two fetches of the same ref would take
     * different locks.
     */
    private static Optional<String> fetchBranchLocked(Path git, Path repositoryRoot, String branch) {
        List<String> command = fetchBranchCommand(git, repositoryRoot, branch);
        synchronized (FETCH_LOCKS.computeIfAbsent(fetchLockKey(repositoryRoot), k -> new Object())) {
            try {
                ProcessResult result = run(command, new ProcessRunner.Options(
                        null, FETCH_TIMEOUT, true, Map.of("GIT_TERMINAL_PROMPT", "0")));
                return result.exitCode() == 0
                        ? Optional.empty()
                        : Optional.of(ProcessRunner.excerpt(result.stderr()));
            } catch (GitException e) {
                // Timeout or spawn failure: same treatment as a non-zero exit.
                return Optional.of(e.getMessage() == null ? e.toString() : e.getMessage());
            }
        }
    }

    // ---- babysitter worktrees: a detached checkout at a PR branch's remote tip ----

    /** {@code git worktree add} checks out a whole tree; a large repository needs more than a query's budget. */
    private static final Duration WORKTREE_ADD_TIMEOUT = Duration.ofSeconds(60);

    /**
     * A PR branch's remote tip after a one-branch fetch. {@link #commit()}
     * is what {@code refs/remotes/origin/<branch>} resolved to afterwards --
     * possibly a stale value when {@link #fetchError()} is present (the
     * fetch failed and the ref is whatever an earlier fetch left), empty
     * when it does not resolve at all.
     */
    public record RemoteBranchTip(Optional<String> commit, Optional<String> fetchError) {
        public RemoteBranchTip {
            Objects.requireNonNull(commit, "commit");
            Objects.requireNonNull(fetchError, "fetchError");
        }

        public boolean fetchFailed() {
            return fetchError.isPresent();
        }
    }

    /**
     * A detached worktree created by {@link #addDetachedWorktreeAtRemoteBranchBlocking}:
     * {@link #baseCommit()} is the exact commit it was checked out at, and
     * {@link #fetchError()} is present when the fetch before it failed, so
     * that commit is the last-known remote tip rather than a fresh one.
     */
    public record DetachedWorktree(Path directory, String baseCommit, Optional<String> fetchError) {
        public DetachedWorktree {
            Objects.requireNonNull(directory, "directory");
            Objects.requireNonNull(baseCommit, "baseCommit");
            Objects.requireNonNull(fetchError, "fetchError");
        }
    }

    /**
     * {@code git -C <root> worktree add --detach <dir> --end-of-options <commit>}.
     * The checkout is at a commit, not a ref: the caller resolved it from
     * {@code refs/remotes/origin/<branch>} and records it as the worktree's
     * base, and a fetch landing between the resolve and the add must not
     * make the two disagree.
     */
    static List<String> addDetachedWorktreeCommand(Path git, Path repositoryRoot, Path directory, String commit) {
        return List.of(git.toString(), "-C", repositoryRoot.toString(), "worktree", "add", "--detach",
                directory.toString(), "--end-of-options", commit);
    }

    /**
     * Fetches {@code branch} from origin (under the per-repository fetch
     * lock) and resolves {@code refs/remotes/origin/<branch>}. A failed fetch
     * is reported on the result, never thrown. Blocking; never on the EDT.
     *
     * @throws IllegalArgumentException when {@code branch} is not a plain ref name
     * @throws GitExecutableNotFoundException when there is no git
     */
    public RemoteBranchTip remoteBranchTipBlocking(Path repositoryRoot, String branch) {
        requireBranchName(branch);
        Path git = locator.locate()
                .orElseThrow(() -> new GitExecutableNotFoundException(locator.describeSearched()));
        Optional<String> fetchError = fetchBranchLocked(git, repositoryRoot, branch);
        Optional<String> commit = commitForRefBlocking(repositoryRoot, "refs/remotes/origin/" + branch);
        return new RemoteBranchTip(commit, fetchError);
    }

    /**
     * Creates a <em>detached</em> worktree at {@code directory} checked out
     * at {@code branch}'s remote tip: fetch the one branch (a failure is
     * reported on the result, and the last-known remote-tracking ref is used),
     * resolve {@code refs/remotes/origin/<branch>} -- fully qualified, so a
     * local branch that happens to be called {@code origin/<branch>} cannot
     * shadow it -- then {@link #addDetachedWorktreeCommand}. No branch is
     * created: the PR branch stays owned by whichever worktree has it
     * checked out. Blocking; never on the EDT.
     *
     * @throws IllegalArgumentException when {@code branch} is not a plain ref name
     * @throws WorktreeDirectoryExistsException when {@code directory} exists; nothing ran
     * @throws GitCommandFailedException when the remote-tracking ref does not
     *         resolve, or the add fails ({@link GitCommandFailedException.Outcome#UNKNOWN}
     *         when it was killed mid-way and may have left a directory behind)
     */
    public DetachedWorktree addDetachedWorktreeAtRemoteBranchBlocking(Path repositoryRoot, Path directory,
                                                                      String branch) {
        requireBranchName(branch);
        Path normalizedDir = directory.toAbsolutePath().normalize();
        if (Files.exists(normalizedDir)) {
            throw new WorktreeDirectoryExistsException(normalizedDir);
        }
        RemoteBranchTip tip = remoteBranchTipBlocking(repositoryRoot, branch);
        if (tip.commit().isEmpty()) {
            throw new GitCommandFailedException(
                    List.of("git", "rev-parse", "--verify", "refs/remotes/origin/" + branch), 128,
                    "refs/remotes/origin/" + branch + " does not resolve"
                            + tip.fetchError().map(error -> "; the fetch failed: " + error).orElse(""));
        }
        Path git = locator.locate()
                .orElseThrow(() -> new GitExecutableNotFoundException(locator.describeSearched()));
        prepareWorktreeParent(normalizedDir);
        List<String> command = addDetachedWorktreeCommand(git, repositoryRoot, normalizedDir, tip.commit().get());
        ProcessResult result = run(command, new ProcessRunner.Options(null, WORKTREE_ADD_TIMEOUT, true, Map.of()));
        if (result.exitCode() != 0) {
            if (result.stderr().toLowerCase(Locale.ROOT).contains("not a git repository")) {
                throw new NotAGitRepositoryException(repositoryRoot);
            }
            throw new GitCommandFailedException(command, result.exitCode(), ProcessRunner.excerpt(result.stderr()));
        }
        return new DetachedWorktree(normalizedDir, tip.commit().get(), tip.fetchError());
    }

    /**
     * {@code git worktree remove --force <directory>}, for undoing a
     * worktree this process has just created and nobody has worked in yet.
     * Never throws: a failure is logged at WARNING with git's excerpt.
     * Blocking; never on the EDT.
     */
    public void removeWorktreeQuietly(Path repositoryRoot, Path directory) {
        Optional<Path> git = locator.locate();
        if (git.isEmpty()) {
            LOG.log(Level.WARNING, "Could not remove the worktree at " + directory + ": no git executable");
            return;
        }
        List<String> command = List.of(git.get().toString(), "-C", repositoryRoot.toString(),
                "worktree", "remove", "--force", directory.toAbsolutePath().normalize().toString());
        try {
            ProcessResult result = run(command, new ProcessRunner.Options(null, WORKTREE_ADD_TIMEOUT, true, Map.of()));
            if (result.exitCode() != 0) {
                LOG.log(Level.WARNING, "Could not remove the worktree at " + directory + "; remove it by hand: "
                        + ProcessRunner.excerpt(result.stderr()));
            }
        } catch (GitException e) {
            LOG.log(Level.WARNING, "Could not remove the worktree at " + directory + "; remove it by hand", e);
        }
    }

    /**
     * {@code git worktree prune}, after a {@link #removeWorktreeQuietly}
     * that followed an add killed mid-way: the add may have registered the
     * worktree before its directory was complete, and a stale entry for a
     * directory that is gone would make the next add at that path fail.
     * Prune touches git's metadata only, and only entries whose directory
     * is gone. Never throws: a failure is logged at WARNING with git's
     * excerpt. Blocking; never on the EDT.
     */
    public void pruneWorktreesQuietly(Path repositoryRoot) {
        Optional<Path> git = locator.locate();
        if (git.isEmpty()) {
            LOG.log(Level.WARNING, "Could not prune the worktrees of " + repositoryRoot + ": no git executable");
            return;
        }
        try {
            ProcessResult result = run(pruneWorktreesCommand(git.get(), repositoryRoot),
                    new ProcessRunner.Options(null, WORKTREE_ADD_TIMEOUT, true, Map.of()));
            if (result.exitCode() != 0) {
                LOG.log(Level.WARNING, "Could not prune the worktrees of " + repositoryRoot + ": "
                        + ProcessRunner.excerpt(result.stderr()));
            }
        } catch (GitException e) {
            LOG.log(Level.WARNING, "Could not prune the worktrees of " + repositoryRoot, e);
        }
    }

    /** {@code git -C <root> worktree prune}. Pure. */
    static List<String> pruneWorktreesCommand(Path git, Path repositoryRoot) {
        return List.of(git.toString(), "-C", repositoryRoot.toString(), "worktree", "prune");
    }

    private static void requireBranchName(String branch) {
        if (branch == null || !isRefComponent(branch)) {
            throw new IllegalArgumentException("Not a plain branch name: " + branch);
        }
    }

    /** Synchronous form of {@link #defaultBranchFork(Path, boolean)}, package-private for tests. */
    DefaultBranchFork defaultBranchForkBlocking(Path repositoryRoot, boolean fetch) {
        Path git = locator.locate()
                .orElseThrow(() -> new GitExecutableNotFoundException(locator.describeSearched()));
        Optional<String> name = defaultBranchName(git, repositoryRoot);
        boolean hasOrigin = !runLinesAllowingFailure(git, repositoryRoot,
                List.of("remote", "get-url", "origin")).isEmpty();
        boolean fetched = false;
        boolean fetchFailed = false;
        Optional<String> fetchError = Optional.empty();
        if (fetch && hasOrigin && name.isPresent()) {
            // One branch only: a task fork needs origin/<default> and
            // nothing else; a whole --all fetch would drag every stale
            // branch in the remote into every launch. The explicit refspec
            // pins the destination regardless of the remote's configured
            // refspecs (single-branch clones, hand-edited fetch refspecs)
            // instead of relying on opportunistic updates. Credentials must
            // never be prompted for, exactly as in fetchAll.
            fetchError = fetchBranchLocked(git, repositoryRoot, name.get());
            fetchFailed = fetchError.isPresent();
            fetched = !fetchFailed;
        }
        String ref;
        if (name.isEmpty()) {
            ref = "main"; // nothing resolvable anywhere: the historical fallback
        } else if (resolves(git, repositoryRoot, "refs/remotes/origin/" + name.get())) {
            ref = "origin/" + name.get();
        } else if (resolves(git, repositoryRoot, "refs/heads/" + name.get())) {
            ref = name.get();
        } else {
            ref = hasOrigin ? "origin/" + name.get() : "main";
        }
        return new DefaultBranchFork(ref, name, fetched, fetchFailed, fetchError);
    }

    /**
     * The default branch's bare name. Resolution mirrors {@link
     * #defaultBranchBlocking} with two deliberate differences: a candidate
     * counts as existing when EITHER the local or the remote-tracking ref
     * does (the fork must see a remote-only default, e.g. a fresh clone
     * whose HEAD sits on a local branch the remote deleted), and the
     * {@code origin/HEAD} guess is gated by {@link GitReviewQueryRules#isRefComponent}. It
     * never looks at the checkout's current branch, like {@link
     * #defaultBranchBlocking}.
     */
    private Optional<String> defaultBranchName(Path git, Path repositoryRoot) {
        for (String line : runLinesAllowingFailure(git, repositoryRoot, List.of(
                "symbolic-ref", "--quiet", "--short", "refs/remotes/origin/HEAD"))) {
            String head = line.strip();
            if (!head.startsWith("origin/")) {
                continue;
            }
            String name = head.substring("origin/".length());
            if (GitReviewQueryRules.isRefComponent(name)) {
                return Optional.of(name);
            }
        }
        for (String candidate : GitReviewQueryRules.DEFAULT_BRANCH_CANDIDATES) {
            if (resolves(git, repositoryRoot, "refs/heads/" + candidate)
                    || resolves(git, repositoryRoot, "refs/remotes/origin/" + candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * A branch-name-shaped string: no leading dash, no refspec tricks, no
     * {@code ..} -- a name drydock is willing to put in a refspec or after
     * {@code refs/remotes/origin/}. Stricter than git's own rules.
     */
    public static boolean isRefComponent(String name) {
        if (name.isEmpty() || name.length() > 200 || name.startsWith("-") || name.startsWith(".")
                || name.contains("..") || name.endsWith("/") || name.endsWith(".") || name.contains("//")) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = Character.isLetterOrDigit(c) || c == '/' || c == '-' || c == '_' || c == '.';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /**
     * The repository's default branch -- what a review diffs against.
     *
     * <p>Resolved from {@code refs/remotes/origin/HEAD} when it exists,
     * otherwise the first of {@code main} / {@code master} / {@code trunk}
     * / {@code develop} that does, otherwise empty.
     *
     * <p>Deliberately <em>not</em> the main checkout's current branch. That
     * is what Review used to use, and it made the base -- and therefore every
     * queue item's diff -- follow whatever branch the user happened to have
     * checked out, so a {@code git switch} in another terminal silently
     * recomputed every review against the wrong thing.</p>
     */
    public CompletableFuture<Optional<String>> defaultBranch(Path repositoryRoot) {
        return CompletableFuture.supplyAsync(() -> defaultBranchBlocking(repositoryRoot), executor);
    }

    /**
     * The effective {@code user.name} git would stamp commits with in
     * {@code repositoryRoot} (repository config overriding global/system),
     * or empty when git has none. An unset name is an ANSWER, not a
     * failure -- callers derive an attribution token from it and have
     * their own fallback -- so this probe tolerates the non-zero exit
     * {@code git config --get} returns when the key is missing.
     */
    public CompletableFuture<Optional<String>> userName(Path repositoryRoot) {
        return CompletableFuture.supplyAsync(() -> userNameBlocking(repositoryRoot), executor);
    }

    /** Synchronous form of {@link #userName}, package-private for tests. */
    Optional<String> userNameBlocking(Path repositoryRoot) {
        Path git = locator.locate()
                .orElseThrow(() -> new GitExecutableNotFoundException(locator.describeSearched()));
        return runLinesAllowingFailure(git, repositoryRoot, List.of("config", "--get", "user.name"))
                .stream().findFirst();
    }

    /** Synchronous form of {@link #defaultBranch}, package-private for tests. */
    Optional<String> defaultBranchBlocking(Path repositoryRoot) {
        Path git = locator.locate()
                .orElseThrow(() -> new GitExecutableNotFoundException(locator.describeSearched()));

        // origin/HEAD is the repository's own statement of its default. A
        // missing one is an ANSWER, not a failure -- a local-only repository
        // simply has none -- so this probe tolerates the non-zero exit that
        // runLines would otherwise throw on.
        for (String line : runLinesAllowingFailure(git, repositoryRoot, List.of(
                "symbolic-ref", "--quiet", "--short", "refs/remotes/origin/HEAD"))) {
            String head = line.strip();
            if (!head.startsWith("origin/")) {
                continue;
            }
            String name = head.substring("origin/".length());
            // The LOCAL branch when there is one, otherwise the
            // remote-tracking ref. `git clone -b feat/x` (and deleting a
            // local main after a merge) leaves origin/HEAD pointing at a
            // branch with no local counterpart, and returning the bare name
            // there would hand every diff a revision git cannot resolve.
            return Optional.of(resolves(git, repositoryRoot, "refs/heads/" + name) ? name : head);
        }
        // No origin/HEAD (a local-only repository, or one never cloned): fall
        // back to the conventional names, and only to ones that exist.
        for (String candidate : GitReviewQueryRules.DEFAULT_BRANCH_CANDIDATES) {
            if (resolves(git, repositoryRoot, "refs/heads/" + candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * The branch {@code checkoutRoot}'s work should be diffed against.
     *
     * <p>The repository's default branch is the <em>last</em> answer, not the
     * first. A repository whose {@code origin/HEAD} is {@code master} while
     * every branch is cut from {@code develop} (btrace is one) diffed every
     * review against {@code master}, so a six-file branch rendered as
     * fifteen hundred files -- the whole of {@code develop} plus the branch.
     * The base is therefore resolved per checkout, in descending order of
     * authority:</p>
     *
     * <ol>
     *   <li>{@code pullRequestBase} -- the PR's own {@code baseRefName}. If
     *       GitHub says what this branch merges into, nothing local can know
     *       better.</li>
     *   <li>the integration branch that already contains the most of this
     *       checkout's history -- the one it was forked from.</li>
     *   <li>{@code defaultBranch}, when neither of those resolves.</li>
     * </ol>
     *
     * <p>Every answer is a revision git can resolve here: a name with no
     * local branch falls back to its {@code origin/} counterpart, because a
     * bare name that resolves to nothing fails the diff outright.</p>
     */
    public CompletableFuture<ReviewBase> reviewBase(Path checkoutRoot, Optional<String> pullRequestBase,
                                                    String defaultBranch) {
        return CompletableFuture.supplyAsync(
                () -> reviewBaseBlocking(checkoutRoot, pullRequestBase, defaultBranch), executor);
    }

    /** Synchronous form of {@link #reviewBase}, package-private for tests. */
    ReviewBase reviewBaseBlocking(Path checkoutRoot, Optional<String> pullRequestBase, String defaultBranch) {
        Path git = locator.locate()
                .orElseThrow(() -> new GitExecutableNotFoundException(locator.describeSearched()));

        Optional<String> declared = pullRequestBase.flatMap(name -> resolveBranch(git, checkoutRoot, name));
        if (declared.isPresent()) {
            return new ReviewBase(declared.get(), ReviewBase.Origin.PULL_REQUEST);
        }

        // Preference order doubles as the tie-break: a branch cut from the
        // default branch scores identically against every integration branch
        // that has not moved since, and the default is then the right answer.
        // The default arrives as a bare local name or as origin/<name>
        // (defaultBranch's own local-first rule); the rules resolve it through
        // the same remote-first rule as every other candidate.
        List<String> candidates = GitReviewQueryRules.reviewBaseCandidates(
                defaultBranch, name -> resolveBranch(git, checkoutRoot, name));
        ReviewBase chosen = GitReviewQueryRules.chooseBase(
                candidates, candidate -> commitsAhead(git, checkoutRoot, candidate), defaultBranch);
        if (chosen.origin() == ReviewBase.Origin.DEFAULT_UNMEASURED) {
            // Not a detail to bury: this is the path that renders a whole
            // integration branch as though it were the review.
            LOG.log(Level.WARNING, "No review base candidate could be measured in " + checkoutRoot
                    + " (tried " + candidates + "); falling back to " + defaultBranch);
        }
        return chosen;
    }

    /**
     * {@code origin/name} when it exists, otherwise the local branch {@code
     * name}, otherwise empty. Deliberately the REVERSE of {@link
     * #defaultBranchBlocking}'s local-first rule: that one names a branch,
     * this one picks the revision a review diffs against, where a stale
     * local copy is wrong rather than merely out of date.
     */
    private Optional<String> resolveBranch(Path git, Path repositoryRoot, String name) {
        if (name.isBlank() || name.startsWith("-")) {
            return Optional.empty();
        }
        // Remote-tracking FIRST. The remote branch is what GitHub diffs a
        // pull request against, and a local integration branch goes stale
        // as a matter of course in a worktree workflow -- nobody pulls it.
        // Resolved local-first, a develop 32 commits behind origin/develop
        // put all 32 already-merged commits into the review, which then
        // matched neither the working tree nor the PR. The local branch is
        // still the answer for a repository with no remote copy of it.
        if (resolves(git, repositoryRoot, "refs/remotes/origin/" + name)) {
            return Optional.of("origin/" + name);
        }
        if (resolves(git, repositoryRoot, "refs/heads/" + name)) {
            return Optional.of(name);
        }
        return Optional.empty();
    }

    /**
     * How many commits {@code HEAD} has that {@code base} does not -- the
     * size of the {@code base...HEAD} range the diff would render. Negative
     * when the range cannot be resolved (an unborn or detached HEAD, a base
     * that is not a ref here), which the caller reads as "not a candidate".
     */
    private long commitsAhead(Path git, Path checkoutRoot, String base) {
        List<String> lines = runLinesAllowingFailure(git, checkoutRoot, GitReviewQueryRules.commitsAheadArguments(base));
        if (lines.isEmpty()) {
            return -1;
        }
        try {
            return Long.parseLong(lines.get(0));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Whether {@code ref} exists in {@code repositoryRoot}. */
    private boolean resolves(Path git, Path repositoryRoot, String ref) {
        return !runLines(git, repositoryRoot, List.of(
                "for-each-ref", "--format=%(refname:short)", ref)).isEmpty();
    }

    /**
     * Lists every branch -- local and remote-tracking -- plus the
     * repository's remote names, for the create-worktree modal's branch
     * picker, on this service's background executor. Occupancy
     * ({@link BranchRef#checkedOutAt()}) is filled in separately by
     * {@link BranchCatalog#merge}, which composes this with
     * {@link WorktreeService#list}.
     */
    public CompletableFuture<BranchListing> listBranches(Path repositoryRoot) {
        return CompletableFuture.supplyAsync(() -> listBranchesBlocking(repositoryRoot), executor);
    }

    /** Synchronous form of {@link #listBranches}, package-private for tests. */
    BranchListing listBranchesBlocking(Path repositoryRoot) {
        Path git = locator.locate()
                .orElseThrow(() -> new GitExecutableNotFoundException(locator.describeSearched()));

        List<String> remotes = runLines(git, repositoryRoot, List.of("remote"));

        // %(symref) is the only reliable way to spot refs/remotes/origin/HEAD:
        // %(refname:short) renders it as "origin", so a name-shaped filter
        // would miss it and leave a phantom branch named after the remote.
        List<String> refLines = runLines(git, repositoryRoot, List.of(
                "for-each-ref", "--format=%(refname)%09%(symref)", "refs/heads/", "refs/remotes/"));

        List<BranchRef> branches = new ArrayList<>();
        for (String line : refLines) {
            String[] parts = line.split("\t", -1);
            if (parts.length > 1 && !parts[1].isBlank()) {
                continue; // symbolic ref (origin/HEAD), not a branch
            }
            String refName = parts[0];
            if (refName.startsWith("refs/heads/")) {
                branches.add(BranchRef.local(refName.substring("refs/heads/".length())));
            } else if (refName.startsWith("refs/remotes/")) {
                branches.add(BranchRef.remote(refName.substring("refs/remotes/".length())));
            }
        }
        return new BranchListing(List.copyOf(branches), remotes);
    }

    /**
     * As {@link #runLines}, but a non-zero exit yields no lines instead of
     * throwing. Only for probes where "the thing is not there" is a normal
     * answer the caller has a plan for.
     */
    private List<String> runLinesAllowingFailure(Path git, Path repositoryRoot, List<String> arguments) {
        List<String> command = new ArrayList<>(List.of(git.toString(), "-C", repositoryRoot.toString()));
        command.addAll(arguments);
        ProcessResult result = run(command);
        if (result.exitCode() != 0) {
            return List.of();
        }
        return result.stdout().lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    /** Runs a read-only git subcommand in {@code repositoryRoot}, returning its non-blank stdout lines. */
    private List<String> runLines(Path git, Path repositoryRoot, List<String> arguments) {
        List<String> command = new ArrayList<>(List.of(git.toString(), "-C", repositoryRoot.toString()));
        command.addAll(arguments);

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

    /**
     * The commit {@code HEAD} names in {@code workingDirectory}, or empty when
     * there is none -- an unborn branch with no commits yet, or a directory
     * that is not a repository.
     *
     * <p>Empty rather than throwing, because every caller so far is stamping
     * or comparing metadata (a handoff brief's {@code writtenAtCommit}, and
     * the staleness that reads it). A session on a branch with no commits is
     * an ordinary state, not a failure, and must not cost the caller its
     * operation. Blocking; never call on the FX thread.</p>
     */
    public Optional<String> headCommitBlocking(Path workingDirectory) {
        Optional<Path> git = locator.locate();
        if (git.isEmpty()) {
            return Optional.empty();
        }
        ProcessResult result = run(List.of(
                git.get().toString(), "-C", workingDirectory.toString(), "rev-parse", "--verify", "HEAD"));
        if (result.exitCode() != 0) {
            return Optional.empty();
        }
        String sha = result.stdout().strip();
        return sha.isEmpty() ? Optional.empty() : Optional.of(sha);
    }

    /**
     * The commit {@code ref} names in {@code workingDirectory}, or empty when
     * it names none -- a branch that does not exist here, a tag that was
     * never fetched, or a directory that is not a repository.
     *
     * <p>Empty rather than throwing, for {@link #headCommitBlocking}'s
     * reason: the caller is stamping or comparing metadata, and a base branch
     * that cannot be resolved right now is an ordinary state of a fresh
     * worktree, not a failure worth costing the caller its operation. What
     * the caller must NOT do is fall back to the ref name -- a verdict
     * recorded against {@code "main"} and compared against {@code "main"}
     * would never read as stale, which is the inert no-op this method
     * exists to end.</p>
     *
     * <p>{@code --end-of-options} precedes the ref because a ref may begin
     * with {@code -} and would otherwise be read as a flag. Blocking; never
     * call on the FX thread.</p>
     */
    public Optional<String> commitForRefBlocking(Path workingDirectory, String ref) {
        Optional<Path> git = locator.locate();
        if (git.isEmpty() || ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        ProcessResult result = run(List.of(git.get().toString(), "-C", workingDirectory.toString(),
                "rev-parse", "--verify", "--end-of-options", ref + "^{commit}"));
        if (result.exitCode() != 0) {
            // Logged rather than folded silently into the empty result: an
            // unresolvable base is what makes every verdict on the scope read
            // as stale, and a reader asking why must be able to find out.
            LOG.log(Level.WARNING, "git rev-parse --verify " + ref + " failed (exit "
                    + result.exitCode() + ") in " + workingDirectory + ": "
                    + ProcessRunner.excerpt(result.stderr()));
            return Optional.empty();
        }
        String sha = result.stdout().strip();
        return sha.isEmpty() ? Optional.empty() : Optional.of(sha);
    }

    /**
     * Whether {@code commit} is contained in {@code of}: {@code git merge-base
     * --is-ancestor}. Exit 1 is a plain "no"; any other failure (an unknown
     * ref) is a {@link GitCommandFailedException}, never a silent false —
     * "not merged" decides whether a removal warns.
     */
    public CompletableFuture<Boolean> isAncestor(Path repository, String commit, String of) {
        try {
            return CompletableFuture.supplyAsync(() -> isAncestorBlocking(repository, commit, of), executor);
        } catch (RejectedExecutionException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    boolean isAncestorBlocking(Path repository, String commit, String of) {
        Path git = locator.locate()
                .orElseThrow(() -> new GitExecutableNotFoundException(locator.describeSearched()));
        List<String> command = List.of(git.toString(), "-C", repository.toString(),
                "merge-base", "--is-ancestor", "--end-of-options", commit, of);
        ProcessResult result = run(command);
        if (result.exitCode() == 0) {
            return true;
        }
        if (result.exitCode() == 1 && result.stderr().isBlank()) {
            return false;
        }
        throw new GitCommandFailedException(command, result.exitCode(), ProcessRunner.excerpt(result.stderr()));
    }

    /**
     * The branch {@code workingDirectory} has checked out, on this service's
     * background executor; empty for a detached HEAD.
     *
     * <p>{@code symbolic-ref} rather than a full {@link #getStatus}: this is
     * asked for one fact, and {@code git status} in a JDK-sized checkout takes
     * seconds. With {@code -q}, exit 1 and no stderr is git's own answer for
     * "HEAD is not a branch" -- an ordinary state, so empty. Anything else
     * (not a repository, git missing, a timeout) completes exceptionally with
     * a {@link GitException}: a failure must not read as "detached".</p>
     *
     * <p>Never throws: the Review board calls this on the FX thread, and a
     * submit the executor rejects (it is shut down -- app shutdown racing a
     * reveal) arrives as a failed future the caller's handler sees, rather
     * than an exception out of the event handler that strands the board on
     * "Resolving…".</p>
     */
    public CompletableFuture<Optional<String>> currentBranch(Path workingDirectory) {
        try {
            return CompletableFuture.supplyAsync(() -> currentBranchBlocking(workingDirectory), executor);
        } catch (RejectedExecutionException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * The full ref is read and {@code refs/heads/} stripped here, rather than
     * asking for {@code --short}: that disambiguates against tags, and on a
     * branch that shares its name with a tag answers {@code heads/<name>} --
     * not the branch name, and never a match for a {@code pr-<n>} alias or a
     * PR's head.
     */
    Optional<String> currentBranchBlocking(Path workingDirectory) {
        Path git = locator.locate()
                .orElseThrow(() -> new GitExecutableNotFoundException(locator.describeSearched()));
        List<String> command = List.of(git.toString(), "-C", workingDirectory.toString(),
                "symbolic-ref", "-q", "HEAD");
        ProcessResult result = run(command);
        if (result.exitCode() == 1 && result.stderr().isBlank()) {
            return Optional.empty();
        }
        if (result.exitCode() != 0) {
            throw new GitCommandFailedException(command, result.exitCode(),
                    ProcessRunner.excerpt(result.stderr()));
        }
        String ref = result.stdout().strip();
        if (!ref.startsWith(BRANCH_REF_PREFIX) || ref.length() == BRANCH_REF_PREFIX.length()) {
            // HEAD points somewhere other than a local branch: no branch name to give.
            return Optional.empty();
        }
        return Optional.of(ref.substring(BRANCH_REF_PREFIX.length()));
    }

    private static final String BRANCH_REF_PREFIX = "refs/heads/";

    /** Async form of {@link #headCommitBlocking}, on this service's background executor. */
    public CompletableFuture<Optional<String>> headCommit(Path workingDirectory) {
        return CompletableFuture.supplyAsync(() -> headCommitBlocking(workingDirectory), executor);
    }

    // ---- process execution (shared ProcessRunner, git-flavored failure translation) ----

    private static ProcessResult run(List<String> command) {
        return run(command, new ProcessRunner.Options(null, PROCESS_TIMEOUT, false, Map.of()));
    }

    /**
     * Package-private rather than private so the three arms below can be
     * pinned directly: which {@link GitCommandFailedException.Outcome} each
     * one carries decides whether MCP refunds a charged worktree, and all
     * three report the same exit code.
     */
    static ProcessResult run(List<String> command, ProcessRunner.Options options) {
        try {
            return ProcessRunner.run(command, options);
        } catch (IOException e) {
            // The executable existed at locate()-time but could not actually be
            // launched (permissions changed, removed between check and use, etc).
            // builder.start() failed, so git never ran: nothing was touched.
            throw new GitCommandFailedException(command, -1, e.getMessage() == null ? "" : e.getMessage(),
                    GitCommandFailedException.Outcome.KNOWN_FAILED);
        } catch (ProcessTimeoutException e) {
            // The child was alive and doing work when we killed it.
            throw new GitCommandFailedException(command, -1,
                    "timed out after " + options.timeout().toSeconds() + "s (killed)",
                    GitCommandFailedException.Outcome.UNKNOWN);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // Likewise: the wait was cut short, not the child's work.
            throw new GitCommandFailedException(command, -1, "interrupted while waiting for git",
                    GitCommandFailedException.Outcome.UNKNOWN);
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
