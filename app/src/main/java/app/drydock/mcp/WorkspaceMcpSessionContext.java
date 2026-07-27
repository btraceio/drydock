package app.drydock.mcp;

import app.drydock.config.UserConfig;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.Repository;
import app.drydock.domain.SessionStatus;
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
import java.util.function.UnaryOperator;

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

    private final Supplier<List<ManagedAgentSession>> sessionCatalog;
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
    public WorkspaceMcpSessionContext(Supplier<List<ManagedAgentSession>> sessionCatalog,
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

    private Optional<ManagedAgentSession> sessionOf(ManagedSessionId caller) {
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
    public boolean sessionRunning(ManagedSessionId caller) {
        return sessionOf(caller).map(session -> session.status() == SessionStatus.RUNNING).orElse(false);
    }

    @Override
    public Optional<Path> worktreePath(ManagedSessionId caller) {
        return sessionOf(caller).map(ManagedAgentSession::workingDirectory);
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
        try {
            return statusOf(repository.get().root(), deadlineIn(JOIN_TIMEOUT_SECONDS))
                    .flatMap(WorkspaceMcpSessionContext::branchName);
        } catch (McpToolException e) {
            return Optional.empty();
        }
    }

    // ---- annotations --------------------------------------------------------

    @Override
    public List<ReviewAnnotation> annotations(ManagedSessionId caller) {
        return annotationStore.forSession(caller);
    }

    @Override
    public Optional<ReviewAnnotation> mutateAnnotation(String id, UnaryOperator<ReviewAnnotation> transform) {
        Optional<ReviewAnnotation> updated = annotationStore.mutate(id, transform);
        // The human's Review card refreshes off the store's change listener;
        // the flush is so the note survives a crash before the next autosave.
        updated.ifPresent(annotation -> annotationStore.flushPendingSaves());
        return updated;
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
     *
     * <p>The local repositories share ONE deadline for the whole call rather
     * than each getting its own slice, for the same reason {@code
     * MainWorkspace.findWorktreeOwner} does: N registered repositories would
     * otherwise multiply one plausible per-repository timeout into a total that
     * held the HTTP connection open for N times as long.</p>
     */
    @Override
    public List<RepoSummary> repositories() throws McpToolException {
        return repositories(deadlineIn(JOIN_TIMEOUT_SECONDS));
    }

    /** Package-private for the shared-deadline test, which needs to hand in an expired one. */
    List<RepoSummary> repositories(long deadlineNanos) throws McpToolException {
        List<RepoSummary> summaries = new ArrayList<>();
        for (Repository repository : repositoryCatalog.get()) {
            if (repository.isRemote()) {
                summaries.add(new RepoSummary(repository.displayName(), repository.root(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(), true));
                continue;
            }
            Optional<GitStatus> status = statusOf(repository.root(), deadlineNanos);
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
     * <p>Every one of those calls draws from ONE deadline for the whole tool
     * call, as {@link #repositories()} explains.</p>
     *
     * <p>The lookup is keyed by REAL path on both sides. A session's stored
     * working directory is whatever path it was opened with, while {@code git
     * worktree list} reports realpaths -- and on macOS {@code /var} is a
     * symlink to {@code /private/var}, so a lexical comparison would report a
     * null branch for essentially every session.</p>
     */
    @Override
    public List<SessionSummary> sessions() throws McpToolException {
        return sessions(deadlineIn(JOIN_TIMEOUT_SECONDS));
    }

    /** Package-private for the shared-deadline test, which needs to hand in an expired one. */
    List<SessionSummary> sessions(long deadlineNanos) throws McpToolException {
        List<Repository> repositories = repositoryCatalog.get();
        Map<Path, String> branchByWorktree = new HashMap<>();
        Set<Path> listed = new LinkedHashSet<>();

        List<SessionSummary> summaries = new ArrayList<>();
        for (ManagedAgentSession session : sessionCatalog.get()) {
            Optional<Repository> repository = repositories.stream()
                    .filter(candidate -> candidate.id().equals(session.repositoryId()))
                    .findFirst();
            boolean remote = repository.map(Repository::isRemote).orElse(false);
            if (!remote && repository.isPresent() && listed.add(repository.get().root())) {
                worktreeBranches(repository.get().root(), branchByWorktree, deadlineNanos);
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
     *
     * <p>Best-effort stops at the shared deadline, though: running out of time
     * is not this repository's problem but the whole call's, so it propagates.
     * Unlike {@link #realWorktreesOf}, the main checkout is kept -- a session
     * legitimately runs in it, and its branch name is what this map is for.</p>
     */
    private void worktreeBranches(Path repositoryRoot, Map<Path, String> into, long deadlineNanos)
            throws DeadlineExceededException {
        try {
            for (Worktree worktree : joinBy(worktreeService.list(repositoryRoot), deadlineNanos)) {
                Optional<Path> real = realPathOf(worktree.path());
                if (real.isPresent()) {
                    worktree.branch().ifPresent(branch -> into.put(real.get(), branch));
                }
            }
        } catch (DeadlineExceededException e) {
            throw e;
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
     *
     * <p>The main checkout is dropped. {@code git worktree list --porcelain}'s
     * first stanza IS the main checkout, so reporting it would let {@code
     * session_start} open a second {@code claude} in the tree the human is
     * working in -- unprompted, concurrent with the human's own session, and
     * presented as a worktree session over the main checkout. {@link
     * WorktreeService#remove} refuses the main checkout for the same kind of
     * reason.</p>
     */
    @Override
    public List<Path> realWorktreesOf(ManagedSessionId caller) throws McpToolException {
        Repository repository = requireRepository(caller);
        List<Worktree> worktrees = join(worktreeService.list(repository.root()), JOIN_TIMEOUT_SECONDS);
        List<Path> real = new ArrayList<>();
        for (Worktree worktree : worktrees) {
            if (worktree.mainCheckout()) {
                continue;
            }
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

    /**
     * Git status for a LOCAL root: empty when git itself could not answer,
     * because one unreadable repository must not fail a whole {@code
     * repos_list}. An expired shared deadline is not that case and propagates.
     */
    private Optional<GitStatus> statusOf(Path root, long deadlineNanos) throws DeadlineExceededException {
        try {
            return Optional.of(joinBy(gitStatusService.getStatus(root), deadlineNanos));
        } catch (DeadlineExceededException e) {
            throw e;
        } catch (McpToolException e) {
            return Optional.empty();
        }
    }

    /** A deadline {@code seconds} from now, as a {@link System#nanoTime} value. */
    private static long deadlineIn(long seconds) {
        return System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    }

    /**
     * Awaits {@code future} with a hard bound of its own. For a single wait;
     * anything that waits more than once per tool call shares one deadline via
     * {@link #joinBy} instead.
     */
    private static <T> T join(CompletableFuture<T> future, long timeoutSeconds) throws McpToolException {
        return joinBy(future, deadlineIn(timeoutSeconds));
    }

    /**
     * Awaits {@code future} within whatever is left of {@code deadlineNanos},
     * translating what comes back into a message the agent can act on. Never
     * unbounded: a wedged FX thread or a hung git must fail the call, not hold
     * the HTTP connection.
     *
     * <p>Running out of the shared deadline is reported as its own exception
     * type, so a caller that swallows a per-repository failure can still let
     * the expiry fail the whole call.</p>
     */
    private static <T> T joinBy(CompletableFuture<T> future, long deadlineNanos) throws McpToolException {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            throw new DeadlineExceededException();
        }
        try {
            return future.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpToolException("Interrupted while waiting for Drydock.");
        } catch (TimeoutException e) {
            throw new DeadlineExceededException();
        } catch (ExecutionException e) {
            throw translate(e.getCause() == null ? e : e.getCause());
        }
    }

    /** The shared deadline of one tool call ran out; the call fails rather than continuing. */
    private static final class DeadlineExceededException extends McpToolException {
        DeadlineExceededException() {
            super("Drydock did not respond in time; the app may be busy.");
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
