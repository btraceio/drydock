package app.drydock.mcp;

import app.drydock.config.UserConfig;
import app.drydock.domain.ManagedClaudeSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.Repository;
import app.drydock.git.GitBranchState;
import app.drydock.git.GitCommandFailedException;
import app.drydock.git.GitExecutableNotFoundException;
import app.drydock.git.GitStatus;
import app.drydock.git.GitStatusService;
import app.drydock.git.SshUnreachableException;
import app.drydock.git.WorktreeLockedException;
import app.drydock.git.WorktreeNaming;
import app.drydock.git.WorktreeNotCleanException;
import app.drydock.git.WorktreeService;
import app.drydock.git.WorktreeService.Worktree;
import app.drydock.review.AnnotationStore;
import app.drydock.review.ReviewAnnotation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * The production {@link McpSessionContext}: the running workspace's answer to
 * every question {@link McpToolRouter} asks.
 *
 * <p>Every method here runs on an {@link McpServer} request thread, never the
 * JavaFX application thread. Slow work (git spawns, file reads, opening a tab)
 * is therefore either done inline on that thread or handed to a service that
 * owns its own executor -- and every wait is bounded: a wedged FX thread must
 * fail the tool call, not hold the HTTP connection open (AGENTS.md).</p>
 *
 * <p>Deliberately holds no JavaFX type and no import from the UI package: the
 * one thing it needs from the UI -- opening a session tab -- arrives as a
 * {@link BiFunction} bound to {@code MainWorkspace.startAgentSession}, which
 * does its own FX-thread hop.</p>
 */
public final class WorkspaceMcpSessionContext implements McpSessionContext {

    /**
     * Bound on every wait. Generous enough for a cold {@code git} spawn on a
     * large repository, short enough that a hung app answers the agent with a
     * message instead of an open connection.
     */
    private static final long JOIN_TIMEOUT_SECONDS = 20;

    /**
     * Bound on {@link #startSession}: resolving which repository owns the
     * worktree, then the FX-thread hop that opens the tab.
     *
     * <p>Public because the whole budget of the work behind {@code
     * startSession} must be provably SMALLER than this. If it were not, this
     * wait could time out -- making {@link McpToolRouter} refund the session
     * charge -- while the tab went on to open anyway, letting an agent exceed
     * the session limit that bounds real spend. The workspace derives its own
     * deadline from this constant rather than restating a number.</p>
     */
    public static final long START_SESSION_TIMEOUT_SECONDS = 30;

    /** Excerpts come from source files; anything this large is not one. */
    private static final long MAX_EXCERPT_FILE_BYTES = 4L * 1024 * 1024;

    private final Supplier<List<ManagedClaudeSession>> sessionCatalog;
    private final Supplier<List<Repository>> repositoryCatalog;
    private final AnnotationStore annotationStore;
    private final GitStatusService gitStatusService;
    private final WorktreeService worktreeService;
    private final Supplier<UserConfig> userConfig;
    private final BiFunction<Path, Optional<String>, CompletableFuture<ManagedSessionId>> sessionStarter;

    /**
     * @param sessionCatalog   every managed session, e.g. {@code SessionManager::sessions}
     * @param repositoryCatalog every registered repository, e.g. {@code RepositoryManager::repositories}
     * @param userConfig       supplier rather than a value because {@link UserConfig#load()}
     *                         reads a file, and the user may edit it while the app runs
     * @param sessionStarter   bound to {@code MainWorkspace.startAgentSession}
     */
    public WorkspaceMcpSessionContext(Supplier<List<ManagedClaudeSession>> sessionCatalog,
                                      Supplier<List<Repository>> repositoryCatalog,
                                      AnnotationStore annotationStore,
                                      GitStatusService gitStatusService,
                                      WorktreeService worktreeService,
                                      Supplier<UserConfig> userConfig,
                                      BiFunction<Path, Optional<String>,
                                              CompletableFuture<ManagedSessionId>> sessionStarter) {
        this.sessionCatalog = Objects.requireNonNull(sessionCatalog, "sessionCatalog");
        this.repositoryCatalog = Objects.requireNonNull(repositoryCatalog, "repositoryCatalog");
        this.annotationStore = Objects.requireNonNull(annotationStore, "annotationStore");
        this.gitStatusService = Objects.requireNonNull(gitStatusService, "gitStatusService");
        this.worktreeService = Objects.requireNonNull(worktreeService, "worktreeService");
        this.userConfig = Objects.requireNonNull(userConfig, "userConfig");
        this.sessionStarter = Objects.requireNonNull(sessionStarter, "sessionStarter");
    }

    // ---- caller lookup ------------------------------------------------------

    private Optional<ManagedClaudeSession> sessionOf(ManagedSessionId caller) {
        return sessionCatalog.get().stream()
                .filter(session -> session.id().equals(caller))
                .findFirst();
    }

    private Optional<Repository> repositoryOf(ManagedSessionId caller) {
        return sessionOf(caller).flatMap(session -> repositoryCatalog.get().stream()
                .filter(repository -> repository.id().equals(session.repositoryId()))
                .findFirst());
    }

    private Repository requireRepository(ManagedSessionId caller) throws McpToolException {
        return repositoryOf(caller).orElseThrow(() -> new McpToolException(
                "Session has ended; its repository is no longer available."));
    }

    @Override
    public Optional<Path> repositoryRoot(ManagedSessionId caller) {
        return repositoryOf(caller).map(Repository::root);
    }

    @Override
    public Optional<Path> worktreePath(ManagedSessionId caller) {
        return sessionOf(caller).map(ManagedClaudeSession::workingDirectory);
    }

    /**
     * The main checkout's current branch -- exactly what the Review view uses
     * as its BASE scope. Empty rather than failing when git cannot be asked:
     * {@code review_comments} is still useful without the base branch name.
     */
    @Override
    public Optional<String> baseBranch(ManagedSessionId caller) {
        Optional<Repository> repository = repositoryOf(caller).filter(repo -> !repo.isRemote());
        if (repository.isEmpty()) {
            return Optional.empty();
        }
        return statusOf(repository.get().root()).flatMap(WorkspaceMcpSessionContext::branchName);
    }

    // ---- annotations --------------------------------------------------------

    @Override
    public List<ReviewAnnotation> annotations(ManagedSessionId caller) {
        return annotationStore.forSession(caller);
    }

    @Override
    public void updateAnnotation(ReviewAnnotation annotation) {
        annotationStore.update(annotation);
        // The human's Review card refreshes off the store's change listener;
        // the flush is so the note survives a crash before the next autosave.
        annotationStore.flushPendingSaves();
    }

    /**
     * Reads a window around {@code line} of {@code file}, resolved <em>under
     * the caller's worktree</em>. Anything that escapes that directory --
     * lexically ({@code ../}, an absolute path) or after symlink resolution --
     * yields empty rather than content: the excerpt is a review aid, not a
     * file-read tool.
     */
    @Override
    public Optional<String> excerpt(ManagedSessionId caller, String file, int line, int context) {
        Optional<Path> worktree = worktreePath(caller);
        if (worktree.isEmpty() || file == null || file.isBlank()) {
            return Optional.empty();
        }
        try {
            Path root = worktree.get().toRealPath();
            Path target = root.resolve(file).normalize();
            if (!target.startsWith(root)) {
                return Optional.empty();
            }
            if (!Files.isRegularFile(target)) {
                return Optional.empty();
            }
            // Re-check AFTER resolving symlinks: a link inside the worktree
            // pointing out of it passes the lexical test above.
            Path real = target.toRealPath();
            if (!real.startsWith(root) || Files.size(real) > MAX_EXCERPT_FILE_BYTES) {
                return Optional.empty();
            }
            List<String> lines = Files.readAllLines(real);
            if (line < 1 || line > lines.size()) {
                return Optional.empty();
            }
            int from = Math.max(1, line - Math.max(0, context));
            int to = Math.min(lines.size(), line + Math.max(0, context));
            return Optional.of(String.join("\n", lines.subList(from - 1, to)));
        } catch (IOException | RuntimeException e) {
            // A missing file, a binary file, an undecodable byte sequence: all
            // simply mean "no excerpt", never a failed tool call.
            return Optional.empty();
        }
    }

    // ---- repos_list / sessions_list -----------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Remote repositories are reported without any git state and without a
     * probe: {@link GitStatusService} has no cache, so one status call per
     * remote repository would run one {@code ssh} -- with its own timeout --
     * while the HTTP handler waits.</p>
     */
    @Override
    public List<RepoSummary> repositories() {
        List<RepoSummary> summaries = new ArrayList<>();
        for (Repository repository : repositoryCatalog.get()) {
            if (repository.isRemote()) {
                summaries.add(new RepoSummary(repository.displayName(), repository.root(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(), true));
                continue;
            }
            Optional<GitStatus> status = statusOf(repository.root());
            summaries.add(new RepoSummary(
                    repository.displayName(),
                    repository.root(),
                    status.flatMap(WorkspaceMcpSessionContext::branchName),
                    status.map(GitStatus::dirty),
                    status.flatMap(s -> s.upstream().map(GitStatus.UpstreamStatus::ahead)),
                    status.flatMap(s -> s.upstream().map(GitStatus.UpstreamStatus::behind)),
                    false));
        }
        return List.copyOf(summaries);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Branch names come from ONE {@code git worktree list} per local
     * repository that actually has sessions, not one git call per session --
     * and never for a remote repository, for the reason {@link
     * #repositories()} documents.</p>
     *
     * <p>The lookup is keyed by REAL path on both sides. A session's stored
     * working directory is whatever path it was opened with, while {@code git
     * worktree list} reports realpaths -- and on macOS {@code /var} is a
     * symlink to {@code /private/var}, so a lexical comparison would report a
     * null branch for essentially every session.</p>
     */
    @Override
    public List<SessionSummary> sessions() {
        List<Repository> repositories = repositoryCatalog.get();
        Map<Path, String> branchByWorktree = new HashMap<>();
        Set<Path> listed = new LinkedHashSet<>();

        List<SessionSummary> summaries = new ArrayList<>();
        for (ManagedClaudeSession session : sessionCatalog.get()) {
            Optional<Repository> repository = repositories.stream()
                    .filter(candidate -> candidate.id().equals(session.repositoryId()))
                    .findFirst();
            boolean remote = repository.map(Repository::isRemote).orElse(false);
            if (!remote && repository.isPresent() && listed.add(repository.get().root())) {
                worktreeBranches(repository.get().root(), branchByWorktree);
            }
            summaries.add(new SessionSummary(
                    session.id(),
                    session.displayName(),
                    repository.map(Repository::displayName).orElse("(unregistered)"),
                    remote ? Optional.empty()
                            : realPathOf(session.workingDirectory()).map(branchByWorktree::get),
                    session.workingDirectory(),
                    session.status().name(),
                    remote));
        }
        return List.copyOf(summaries);
    }

    /**
     * Best-effort: a repository git cannot list simply contributes no branch
     * names. Keyed by real path, skipping entries whose directory is gone,
     * consistent with {@link #realWorktreesOf}.
     */
    private void worktreeBranches(Path repositoryRoot, Map<Path, String> into) {
        try {
            for (Worktree worktree : join(worktreeService.list(repositoryRoot), JOIN_TIMEOUT_SECONDS)) {
                Optional<Path> real = realPathOf(worktree.path());
                if (real.isPresent()) {
                    worktree.branch().ifPresent(branch -> into.put(real.get(), branch));
                }
            }
        } catch (McpToolException e) {
            // Listing branch names is decoration on sessions_list; the session
            // rows themselves must still come back.
        }
    }

    // ---- worktree_create / session_start ------------------------------------

    @Override
    public Set<String> remoteNames(ManagedSessionId caller) throws McpToolException {
        Repository repository = requireRepository(caller);
        return Set.copyOf(join(gitStatusService.listBranches(repository.root()), JOIN_TIMEOUT_SECONDS).remotes());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Each recorded path is resolved with {@link Path#toRealPath}, and an
     * entry whose path no longer exists is skipped rather than reported. Both
     * matter for {@code session_start}'s membership test: {@code git worktree
     * list} reports realpaths, so an honest symlinked argument must compare
     * equal -- and a symlink swapped in <em>under</em> a recorded worktree
     * path must resolve to its new target here (so the swap is visible to the
     * comparison) instead of being echoed back unresolved.</p>
     */
    @Override
    public List<Path> realWorktreesOf(ManagedSessionId caller) throws McpToolException {
        Repository repository = requireRepository(caller);
        List<Worktree> worktrees = join(worktreeService.list(repository.root()), JOIN_TIMEOUT_SECONDS);
        List<Path> real = new ArrayList<>();
        for (Worktree worktree : worktrees) {
            // A pruned or deleted worktree directory is not a member of
            // anything, so it must not appear in the membership test.
            realPathOf(worktree.path()).ifPresent(real::add);
        }
        return List.copyOf(real);
    }

    @Override
    public Path createWorktree(ManagedSessionId caller, String branch, Optional<String> startPoint)
            throws McpToolException {
        Repository repository = requireRepository(caller);
        if (repository.isRemote()) {
            throw new McpToolException("This session's repository is remote; Drydock cannot create worktrees in it.");
        }
        Path home = Path.of(System.getProperty("user.home"));
        Path directory = WorktreeNaming.defaultDirectory(home, userConfig.get().worktreesDirectory(),
                repository.displayName(), branch);
        return join(gitStatusService.createWorktree(repository.root(), directory, branch, startPoint),
                JOIN_TIMEOUT_SECONDS);
    }

    @Override
    public ManagedSessionId startSession(Path worktree, Optional<String> initialPrompt) throws McpToolException {
        return join(sessionStarter.apply(worktree, initialPrompt), START_SESSION_TIMEOUT_SECONDS);
    }

    // ---- shared helpers -----------------------------------------------------

    /** Empty when the path no longer exists -- never a fabricated lexical path. */
    private static Optional<Path> realPathOf(Path path) {
        try {
            return Optional.of(path.toRealPath());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Optional<String> branchName(GitStatus status) {
        return status.branch() instanceof GitBranchState.OnBranch onBranch
                ? Optional.of(onBranch.name())
                : Optional.empty();
    }

    /** Git status for a LOCAL root, or empty when git could not be asked in time. */
    private Optional<GitStatus> statusOf(Path root) {
        try {
            return Optional.of(join(gitStatusService.getStatus(root), JOIN_TIMEOUT_SECONDS));
        } catch (McpToolException e) {
            return Optional.empty();
        }
    }

    /**
     * Awaits {@code future} with a hard bound, translating what comes back
     * into a message the agent can act on. Never unbounded: a wedged FX thread
     * or a hung git must fail the call, not hold the HTTP connection.
     */
    private static <T> T join(CompletableFuture<T> future, long timeoutSeconds) throws McpToolException {
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpToolException("Interrupted while waiting for Drydock.");
        } catch (TimeoutException e) {
            throw new McpToolException("Drydock did not respond in time; the app may be busy.");
        } catch (ExecutionException e) {
            throw translate(e.getCause() == null ? e : e.getCause());
        }
    }

    /** Turns a known service failure into its own message; anything else stays generic. */
    private static McpToolException translate(Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return switch (cause) {
            case WorktreeLockedException locked -> new McpToolException("The worktree at "
                    + locked.worktreePath() + " is locked"
                    + locked.lockReason().map(reason -> " (" + reason + ")").orElse("")
                    + "; the human can unlock it from the UI.");
            case WorktreeNotCleanException notClean -> new McpToolException("The worktree at "
                    + notClean.worktreePath() + " has uncommitted changes; commit or discard them first.");
            case GitExecutableNotFoundException notFound -> new McpToolException(
                    "Drydock could not find a git executable: " + notFound.getMessage());
            case GitCommandFailedException gitFailed -> new McpToolException("git failed: "
                    + gitFailed.stderrExcerpt());
            case SshUnreachableException unreachable -> new McpToolException("Host "
                    + unreachable.host() + " is unreachable.");
            case McpToolException already -> already;
            default -> new McpToolException("Drydock could not complete that: " + cause);
        };
    }
}
