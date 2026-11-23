package app.drydock.delayedtasks;

import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.git.GhCliService;
import app.drydock.git.GitStatusService;
import app.drydock.github.GitHubStackPrService;
import app.drydock.github.GitHubStackPrService.Created;
import app.drydock.github.GitHubStackPrService.Rejected;
import app.drydock.github.GitHubStackPrService.Unavailable;
import app.drydock.tasks.StackBranches;
import app.drydock.tasks.TaskDocument;
import app.drydock.tasks.TaskGraph;
import app.drydock.tasks.TaskId;
import app.drydock.tasks.TaskSnapshot;
import app.drydock.tasks.TaskStatus;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * The stacked pull request lifecycle (S3 of docs/StackedPrExecution.md),
 * driven by the store watcher's snapshots: when a task of a stack finishes
 * (status done or review -- the branch's work is complete either way, S2)
 * and its {@code dd/<task-id>} branch is PUSHED (an upstream ref exists,
 * the prompt's push-per-task contract), exactly one DRAFT pull request is
 * opened for it, based on its prerequisite task's branch -- or the
 * repository's default branch when the task has no in-repository
 * prerequisite (a stack root). A task with no pushed {@code dd/} branch is
 * not a stack task or is not finished pushing: it is skipped without being
 * marked, and reconsidered on a later snapshot. A task whose PR attempt
 * was created, refused, unavailable, or whose branch already has a pull
 * request stays marked -- a snapshot storm never retries a GitHub write
 * whose outcome may already have landed; a refusal, a deferred attempt
 * (a base branch not pushed yet), and a probe failure are reconsidered
 * with an exponential backoff (30 s doubling to the 10-minute cap), so a
 * not-yet-openable task is probed at most once per backoff window. Every
 * outcome is logged visibly and never worked around silently.
 *
 * <p>Threading: candidate selection is synchronous and cheap (map reads);
 * the whole attempt -- root resolution, git probes, the graph, base
 * resolution, the GitHub write and its bounded join -- runs on the
 * service's own executor, never the caller's thread and never the JVM-wide
 * common pool. The service owns its gh/git clients and closes them.</p>
 */
final class StackPrService implements AutoCloseable {

    private static final Logger LOG = System.getLogger(StackPrService.class.getName());

    /** Bounded waits for the read-side futures this service joins. */
    private static final long JOIN_SECONDS = 20;

    /** The first reconsideration delay after a failed or deferred attempt. */
    static final long BACKOFF_INITIAL_MS = 30_000;

    /** The reconsideration delay never grows beyond this cap. */
    static final long BACKOFF_MAX_MS = 600_000;

    private final Function<RepositoryId, OptionalRoot> roots;
    private final GhCliService gh;
    private final GitStatusService git;
    private final GitHubStackPrService pulls;
    private final Optional<String> repositorySetting;
    private final Set<TaskId> attempted = ConcurrentHashMap.newKeySet();
    /** Reconsider-not-before deadlines, epoch millis, keyed by task. */
    private final ConcurrentHashMap<TaskId, Long> retryAfter = new ConcurrentHashMap<>();
    /** The next reconsideration delay per task (doubling toward the cap). */
    private final ConcurrentHashMap<TaskId, Long> backoffDelays = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private volatile boolean closed;

    // Collaborator hooks: a test supplies recording fakes through the
    // injection constructor; every null hook keeps the real service call.

    private final ViewPrProbe viewPrProbe;
    private final RefProbe refProbe;
    private final DefaultBranchLookup defaultBranchLookup;
    private final CreateCall createCall;
    private final long joinTimeoutSeconds;
    private final long backoffInitialMs;

    /** The repository main root, or empty when the repository is unknown. */
    record OptionalRoot(Path root) {
    }

    /** Test seam: the {@code gh viewPr} probe. */
    @FunctionalInterface
    interface ViewPrProbe {
        CompletableFuture<Optional<GhCliService.PrInfo>> viewPr(Path root, String head);
    }

    /** Test seam: the blocking ref lookup (head, upstream, base probes). */
    @FunctionalInterface
    interface RefProbe {
        Optional<String> commitForRef(Path root, String ref);
    }

    /** Test seam: the repository default-branch lookup. */
    @FunctionalInterface
    interface DefaultBranchLookup {
        CompletableFuture<Optional<String>> defaultBranch(Path root);
    }

    /** Test seam: the draft pull request POST. */
    @FunctionalInterface
    interface CreateCall {
        CompletableFuture<GitHubStackPrService.Outcome> create(Path root, Optional<String> repository,
                String head, String base, String title, String body);
    }

    StackPrService(Function<RepositoryId, OptionalRoot> roots, GhCliService gh, GitStatusService git,
            GitHubStackPrService pulls, Optional<String> repositorySetting) {
        this(roots, gh, git, pulls, repositorySetting, Executors.newVirtualThreadPerTaskExecutor());
    }

    /** The injection constructor: the attempts' executor is the caller's. */
    StackPrService(Function<RepositoryId, OptionalRoot> roots, GhCliService gh, GitStatusService git,
            GitHubStackPrService pulls, Optional<String> repositorySetting, ExecutorService executor) {
        this(roots, gh, git, pulls, repositorySetting, executor, null, null, null, null, 0, 0);
    }

    /** The test-injection constructor: collaborator hooks replace the live
      * service calls; a {@code null} hook keeps the real service behavior,
      * a non-positive join timeout or backoff delay keeps the production
      * constant. The collaborators are still owned and closed by the
      * service -- they are only bypassed when a hook is present. */
    StackPrService(Function<RepositoryId, OptionalRoot> roots, GhCliService gh, GitStatusService git,
            GitHubStackPrService pulls, Optional<String> repositorySetting, ExecutorService executor,
            ViewPrProbe viewPrProbe, RefProbe refProbe, DefaultBranchLookup defaultBranchLookup,
            CreateCall createCall, long joinTimeoutSeconds, long backoffInitialMs) {
        this.roots = Objects.requireNonNull(roots, "roots");
        this.gh = Objects.requireNonNull(gh, "gh");
        this.git = Objects.requireNonNull(git, "git");
        this.pulls = Objects.requireNonNull(pulls, "pulls");
        this.repositorySetting = Objects.requireNonNull(repositorySetting, "repositorySetting");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.viewPrProbe = viewPrProbe != null ? viewPrProbe : gh::viewPr;
        this.refProbe = refProbe != null ? refProbe : git::commitForRefBlocking;
        this.defaultBranchLookup = defaultBranchLookup != null
                ? defaultBranchLookup : git::defaultBranch;
        this.createCall = createCall != null ? createCall : pulls::create;
        this.joinTimeoutSeconds = joinTimeoutSeconds > 0 ? joinTimeoutSeconds : JOIN_SECONDS;
        this.backoffInitialMs = backoffInitialMs > 0 ? backoffInitialMs : BACKOFF_INITIAL_MS;
    }

    /** Production composition over the live repository catalog. */
    static StackPrService over(Function<RepositoryId, List<Repository>> repositories,
            Optional<String> repositorySetting) {
        return new StackPrService(
                id -> repositories.apply(id).stream().findFirst()
                        .map(repository -> new OptionalRoot(repository.root()))
                        .orElse(new OptionalRoot(null)),
                new GhCliService(), new GitStatusService(), new GitHubStackPrService(), repositorySetting);
    }

    /**
     * One snapshot pass: every finished task of a stack that is not marked
     * and not backed off gets its PR attempt dispatched. Never throws,
     * never blocks on a subprocess, never builds the task graph.
     */
    void observe(TaskSnapshot snapshot, Function<RepositoryId, String> slugs) {
        if (closed || snapshot == null) {
            return;
        }
        for (TaskDocument document : snapshot.documents()) {
            if (document.status() != TaskStatus.DONE && document.status() != TaskStatus.REVIEW) {
                continue;
            }
            Long deadline = retryAfter.get(document.id());
            if (deadline != null && deadline > System.currentTimeMillis()) {
                continue;
            }
            if (!attempted.add(document.id())) {
                continue;
            }
            attempt(document, snapshot, slugs);
        }
    }

    /** One task's PR attempt, dispatched off the caller's thread. */
    private void attempt(TaskDocument task, TaskSnapshot snapshot, Function<RepositoryId, String> slugs) {
        try {
            CompletableFuture.runAsync(() -> attemptBody(task, snapshot, slugs), executor);
        } catch (RejectedExecutionException e) {
            attempted.remove(task.id()); // the task stays reconsiderable by a later service
            LOG.log(Level.WARNING, "The stacked pull request attempt of task '" + task.title()
                    + "' was rejected by the shutting-down executor", e);
        }
    }

    /** The whole attempt body: root resolution, probes, graph, base, POST. */
    private void attemptBody(TaskDocument task, TaskSnapshot snapshot, Function<RepositoryId, String> slugs) {
        OptionalRoot resolved = roots.apply(task.repository());
        if (resolved.root() == null) {
            attempted.remove(task.id()); // an unregistered repository may register later
            return;
        }
        Path root = resolved.root();
        try {
            String head = StackBranches.name(task.id());
            if (refProbe.commitForRef(root, head).isEmpty()) {
                retryLater(task.id()); // no branch yet: not a stack task (or not started)
                return;
            }
            if (refProbe.commitForRef(root, head + "@{upstream}").isEmpty()) {
                retryLater(task.id()); // not pushed yet; the prompt's push-per-task is pending
                return;
            }
            if (hasPullRequest(root, head)) {
                LOG.log(Level.DEBUG, "The branch " + head + " already has a pull request");
                retryAfter.remove(task.id()); // a prior reconsideration already succeeded
                return; // attempted stays: never open a second PR for one task
            }
            TaskGraph graph = TaskGraph.of(snapshot, slugs);
            Optional<String> prerequisiteBase = prerequisiteBaseOf(task, graph);
            String base;
            if (prerequisiteBase.isPresent()) {
                if (refProbe.commitForRef(root, prerequisiteBase.get() + "@{upstream}").isEmpty()) {
                    LOG.log(Level.INFO, "The stacked pull request of task '" + task.title()
                            + "' waits for its base branch " + prerequisiteBase.get());
                    retryLater(task.id()); // never a wrong-base PR; reopen once the base is pushed
                    return;
                }
                base = prerequisiteBase.get();
            } else {
                base = defaultBranchOf(root);
            }
            GitHubStackPrService.Outcome outcome = postPullRequest(task, root, head, base);
            if (outcome instanceof Created created) {
                LOG.log(Level.INFO, "The stacked pull request of task '" + task.title() + "' is "
                        + created.url() + " (" + head + " -> " + base + ")");
            } else if (outcome instanceof Rejected rejected) {
                LOG.log(Level.WARNING, "The stacked pull request of '" + head + "' was refused: "
                        + rejected.message());
            } else if (outcome instanceof Unavailable unavailable) {
                LOG.log(Level.WARNING, "The stacked pull request of '" + head
                        + "' was not attempted: " + unavailable.message());
            }
            switch (dispositionOf(outcome)) {
                case SETTLED -> retryAfter.remove(task.id());
                case RETRY_LATER -> retryLater(task.id());
                case KEEP_MARK -> { }
            }
        } catch (PostJoinUnknownOutcome e) {
            // the POST may have reached GitHub: the mark stays; the human is
            // told to check the repository's pull requests (already logged)
        } catch (RuntimeException e) {
            retryLater(task.id()); // nothing was POSTed: the attempt may succeed on a later snapshot
            LOG.log(Level.WARNING, "The stacked pull request of task '" + task.title()
                    + "' could not be ensured", e);
        }
    }

    /** The POST join: the one unknown-outcome surface. A timeout,
      * interruption or execution failure keeps the task marked (the POST
      * may have reached GitHub) and tells the human to check the
      * repository's pull requests before any retry. */
    private GitHubStackPrService.Outcome postPullRequest(TaskDocument task, Path root, String head,
            String base) {
        try {
            return createCall.create(root, repositorySetting, head, base, task.title(), task.rawBody())
                    .get(joinTimeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.log(Level.WARNING, "The stacked pull request join of task '" + task.title()
                    + "' was interrupted; check the repository's pull requests before it is retried", e);
            throw new PostJoinUnknownOutcome(e);
        } catch (TimeoutException | ExecutionException e) {
            LOG.log(Level.WARNING, "The stacked pull request join of task '" + task.title()
                    + "' did not complete; check the repository's pull requests before it is retried", e);
            throw new PostJoinUnknownOutcome(e);
        }
    }

    /** Marks an unknown-outcome POST join; caught only by the attempt
      * body's boundary, which keeps the mark. Never a retry signal. */
    private static final class PostJoinUnknownOutcome extends RuntimeException {
        private PostJoinUnknownOutcome(Throwable cause) {
            super(cause);
        }
    }

    /** The pre-POST {@code gh viewPr} guard, joined with the bounded wait;
      * any failure surfaces as a runtime exception (a pre-POST failure the
      * attempt body defers via the backoff). */
    private boolean hasPullRequest(Path root, String head) {
        try {
            return !viewPrProbe.viewPr(root, head).get(joinTimeoutSeconds, TimeUnit.SECONDS).isEmpty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The pull-request probe of " + head + " was interrupted", e);
        } catch (TimeoutException | ExecutionException e) {
            throw new IllegalStateException("The pull-request probe of " + head + " failed", e);
        }
    }

    /** The repository default branch, joined with the bounded wait; any
      * failure surfaces as a runtime exception (deferred via the backoff). */
    private String defaultBranchOf(Path root) {
        try {
            return defaultBranchLookup.defaultBranch(root)
                    .get(joinTimeoutSeconds, TimeUnit.SECONDS).orElse("main");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The default-branch lookup was interrupted", e);
        } catch (TimeoutException | ExecutionException e) {
            throw new IllegalStateException("The default-branch lookup failed", e);
        }
    }

    /** Releases the task for reconsideration and arms its next backoff
      * deadline: 30 s, doubling to the 10-minute cap. */
    void retryLater(TaskId id) {
        attempted.remove(id);
        long previous = backoffDelays.getOrDefault(id, 0L);
        long delay = nextBackoffDelayMs(previous, backoffInitialMs);
        backoffDelays.put(id, delay);
        retryAfter.put(id, System.currentTimeMillis() + delay);
    }

    /** The next reconsideration delay: the initial delay, then the
      * previous delay doubled, capped at 10 minutes (pure decision point). */
    static long nextBackoffDelayMs(long previousDelayMs, long initialMs) {
        if (previousDelayMs <= 0) {
            return initialMs;
        }
        return Math.min(previousDelayMs * 2, BACKOFF_MAX_MS);
    }

    /** The pull request base candidate (S3): the FIRST in-repository
      * prerequisite that is neither DONE nor DROPPED (mirroring
      * {@code StackUnits.inSegmentEdges}, D13: a satisfied prerequisite
      * starts nothing); DONE/DROPPED and cross-repository prerequisites
      * are never bases. Empty when none qualifies -- the caller then uses
      * the repository's default branch. */
    Optional<String> prerequisiteBaseOf(TaskDocument task, TaskGraph graph) {
        return graph.diagnosticsOf(task.id()).orElseThrow().resolved().stream()
                .map(TaskGraph.ResolvedReference::target)
                .filter(prerequisite -> prerequisite.repository().equals(task.repository()))
                .filter(prerequisite -> prerequisite.status() != TaskStatus.DONE
                        && prerequisite.status() != TaskStatus.DROPPED)
                .map(prerequisite -> StackBranches.name(prerequisite.id()))
                .findFirst();
    }

    /** What a completed POST outcome does to the task's mark (pure
      * decision point): SETTLED clears the backoff (created or already
      * open), RETRY_LATER releases the mark into the backoff (refused),
      * KEEP_MARK holds the mark (unavailable: gh missing or unauthenticated
      * is a decided condition). */
    enum PostDisposition {
        SETTLED, RETRY_LATER, KEEP_MARK
    }

    static PostDisposition dispositionOf(GitHubStackPrService.Outcome outcome) {
        if (outcome instanceof Created) {
            return PostDisposition.SETTLED;
        }
        if (outcome instanceof Rejected) {
            return PostDisposition.RETRY_LATER;
        }
        if (outcome instanceof Unavailable) {
            return PostDisposition.KEEP_MARK;
        }
        throw new IllegalStateException("Unknown pull request outcome: " + outcome);
    }

    @Override
    public void close() {
        closed = true;
        executor.shutdownNow();
        gh.close();
        git.close();
        pulls.close();
    }
}
