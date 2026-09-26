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
     * Lists every branch -- local and remote-tracking -- plus the
     * repository's remote names, for the create-worktree modal's branch
     * picker, on this service's background executor. Occupancy
     * ({@link BranchRef#checkedOutAt()}) is filled in separately by
     * {@link BranchCatalog#merge}, which composes this with
     * {@link WorktreeService#list}.
     */
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
        for (String candidate : List.of("main", "master", "trunk", "develop")) {
            if (resolves(git, repositoryRoot, "refs/heads/" + candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * The branches a review base is looked for among when no pull request
     * declares one, after the repository's own default. {@code develop}
     * before {@code trunk} because a repository that has both almost always
     * integrates on the former.
     */
    private static final List<String> INTEGRATION_BRANCHES = List.of("main", "master", "develop", "trunk");

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
        List<String> candidates = new ArrayList<>();
        candidates.add(defaultBranch);
        for (String name : INTEGRATION_BRANCHES) {
            resolveBranch(git, checkoutRoot, name)
                    .filter(resolved -> !candidates.contains(resolved))
                    .ifPresent(candidates::add);
        }

        String best = defaultBranch;
        long fewest = Long.MAX_VALUE;
        for (String candidate : candidates) {
            long ahead = commitsAhead(git, checkoutRoot, candidate);
            if (ahead >= 0 && ahead < fewest) {
                fewest = ahead;
                best = candidate;
            }
        }
        if (fewest == Long.MAX_VALUE) {
            // Not a detail to bury: this is the path that renders a whole
            // integration branch as though it were the review.
            LOG.log(Level.WARNING, "No review base candidate could be measured in " + checkoutRoot
                    + " (tried " + candidates + "); falling back to " + defaultBranch);
            return new ReviewBase(defaultBranch, ReviewBase.Origin.DEFAULT_UNMEASURED);
        }
        return new ReviewBase(best, ReviewBase.Origin.FORKED_FROM);
    }

    /**
     * The local branch {@code name} when it exists, otherwise {@code
     * origin/name} when that does, otherwise empty -- the same local-then-
     * remote rule {@link #defaultBranchBlocking} applies, and for the same
     * reason: a PR base branch is often not checked out locally at all.
     */
    private Optional<String> resolveBranch(Path git, Path repositoryRoot, String name) {
        if (name.isBlank() || name.startsWith("-")) {
            return Optional.empty();
        }
        if (resolves(git, repositoryRoot, "refs/heads/" + name)) {
            return Optional.of(name);
        }
        if (resolves(git, repositoryRoot, "refs/remotes/origin/" + name)) {
            return Optional.of("origin/" + name);
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
        List<String> lines = runLinesAllowingFailure(git, checkoutRoot, List.of(
                "rev-list", "--count", "--end-of-options", base + "..HEAD"));
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
