package app.drydock.intellij.session;

import app.drydock.app.ApplicationStateStore;
import app.drydock.babysit.BabysitInstructions;
import app.drydock.babysit.BabysitPairing;
import app.drydock.babysit.BabysitStartRefusal;
import app.drydock.babysit.BabysitStateTransforms;
import app.drydock.babysit.Escalation;
import app.drydock.domain.ApplicationState;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.Repository;
import app.drydock.git.GhCiRerunService;
import app.drydock.git.GhCliService;
import app.drydock.git.GhCliService.BranchPullRequest;
import app.drydock.git.GhCliService.PrInfo.PrLifecycle;
import app.drydock.git.GitExecutableLocator;
import app.drydock.git.PrMonitorRead;
import app.drydock.git.PrMonitorRead.PrSnapshot;
import app.drydock.git.WorktreeService;
import app.drydock.intellij.DrydockSessionsListener;
import app.drydock.intellij.agent.AgentExecution;
import app.drydock.intellij.agent.AgentExecutionRegistry;
import app.drydock.intellij.session.DrydockSessionService.BabysitStartRefusedException;
import app.drydock.intellij.startup.DrydockStartupActivity;
import app.drydock.intellij.tasks.SessionPullRequestLookup;
import app.drydock.mcp.BabysitToolContext;
import app.drydock.projects.SharedProject;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The plugin's {@link BabysitToolContext}: the babysitting MCP tools' reach
 * into the IDE side. Every blocking step (git, gh) runs on the plugin
 * executor; state reads are memory-only; the escalation
 * write is one pure {@link ApplicationStateStore#update} followed by {@link
 * DrydockSessionsListener#publishChanged()}.
 *
 * <p>What it relies on: the caller's PR comes from the session tree's own
 * join ({@link SessionPullRequestLookup}), and the start is {@link
 * DrydockSessionService#startBabysitter} of the project whose console hosts
 * the caller -- the same routing {@code session_rename} uses. The watched
 * session of a rerun or an escalation is always the pairing's.</p>
 */
public final class PluginBabysitToolContext implements BabysitToolContext {

    private static final Logger LOG = System.getLogger(PluginBabysitToolContext.class.getName());

    private static final String REPOSITORY_GONE = "The babysat session's repository is no longer known to Drydock";

    private final Supplier<ApplicationStateStore> stateStore;
    private final ExecutorService executor;
    private final Path baseDir;
    private final Supplier<List<SharedProject>> projects;
    private final Function<ManagedSessionId, Optional<DrydockSessionService>> sessionsHosting;
    private final GhCliService github;
    private final WorktreeService worktrees;
    private final GhCiRerunService reruns;
    private final PrMonitorRead prReads;

    /**
     * Production wiring: the services ride {@code executor} (owned by the
     * plugin service, so nothing here needs closing), and the session
     * service is the one of the project whose console hosts the session.
     */
    public PluginBabysitToolContext(Supplier<ApplicationStateStore> stateStore, ExecutorService executor,
                                    Path baseDir, Supplier<List<SharedProject>> projects) {
        this(stateStore, executor, baseDir, projects, PluginBabysitToolContext::sessionsHosting,
                new GhCliService(executor), new WorktreeService(new GitExecutableLocator(), executor),
                new GhCiRerunService(executor), new PrMonitorRead(executor));
    }

    PluginBabysitToolContext(Supplier<ApplicationStateStore> stateStore, ExecutorService executor, Path baseDir,
                             Supplier<List<SharedProject>> projects,
                             Function<ManagedSessionId, Optional<DrydockSessionService>> sessionsHosting,
                             GhCliService github, WorktreeService worktrees, GhCiRerunService reruns,
                             PrMonitorRead prReads) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.baseDir = Objects.requireNonNull(baseDir, "baseDir");
        this.projects = Objects.requireNonNull(projects, "projects");
        this.sessionsHosting = Objects.requireNonNull(sessionsHosting, "sessionsHosting");
        this.github = Objects.requireNonNull(github, "github");
        this.worktrees = Objects.requireNonNull(worktrees, "worktrees");
        this.reruns = Objects.requireNonNull(reruns, "reruns");
        this.prReads = Objects.requireNonNull(prReads, "prReads");
    }

    private static Optional<DrydockSessionService> sessionsHosting(ManagedSessionId id) {
        return AgentExecutionRegistry.getInstance().execution(id)
                .flatMap(AgentExecution::host)
                .filter(project -> !project.isDisposed())
                .map(DrydockStartupActivity::sessions);
    }

    @Override
    public CompletableFuture<PullRequestLookup> openPullRequestOf(ManagedSessionId caller) {
        return CompletableFuture.supplyAsync(() -> lookup(caller), executor);
    }

    /** Blocking: one git probe and one gh listing. */
    private PullRequestLookup lookup(ManagedSessionId caller) {
        ManagedAgentSession session = stateStore.get().state().sessions().stream()
                .filter(candidate -> candidate.id().equals(caller))
                .findFirst().orElse(null);
        if (session == null) {
            return new PullRequestLookup.NotFound(BabysitStartRefusal.WATCHED_UNKNOWN.message());
        }
        if (session.workspaceBox().isPresent()) {
            return new PullRequestLookup.NotFound(BabysitStartRefusal.WATCHED_REMOTE.message());
        }
        if (session.targetPullRequest().isPresent()) {
            return new PullRequestLookup.NotFound("This session targets " + session.targetPullRequest().get().url()
                    + " explicitly; a babysitter watches the pull request a session's own branch publishes,"
                    + " and the MCP start does not resolve one for a targeted session -- ask the human to start"
                    + " it from the Drydock UI.");
        }
        Optional<BranchPullRequest> found = SessionPullRequestLookup.pullRequestOf(session, projects.get(),
                worktrees, github);
        if (found.isEmpty()) {
            return new PullRequestLookup.NotFound("This session's branch resolves to no pull request Drydock"
                    + " can see: gh lists none for it, gh is unavailable, the branch is detached, or the"
                    + " project's pull requests are switched off. Push the branch and open its PR first.");
        }
        BranchPullRequest pullRequest = found.get();
        if (pullRequest.state() != PrLifecycle.OPEN) {
            return new PullRequestLookup.NotFound("This session's pull request #" + pullRequest.number() + " is "
                    + pullRequest.state().name().toLowerCase(Locale.ROOT)
                    + ", not open; there is nothing to babysit.");
        }
        return new PullRequestLookup.Open(pullRequest.number(), pullRequest.headRefName());
    }

    @Override
    public CompletableFuture<StartOutcome> startBabysitter(ManagedSessionId watched, int prNumber, String prBranch) {
        Optional<DrydockSessionService> sessions = sessionsHosting.apply(watched);
        if (sessions.isEmpty()) {
            return CompletableFuture.completedFuture(new StartOutcome.Refused("No open project hosts this"
                    + " session's console, so Drydock cannot start a babysitter for it."));
        }
        // The session service writes the baseline instructions only once the
        // start has passed every refusal, so a refused start leaves no file.
        Path instructions = BabysitInstructions.fileFor(baseDir, watched, prNumber);
        return sessions.get().startBabysitter(watched, prNumber, prBranch, instructions)
                .<StartOutcome>thenApply(launch -> {
                    launch.started().whenComplete((started, failure) -> {
                        if (failure != null) {
                            LOG.log(Level.WARNING, "The babysitter for " + watched + " (PR #" + prNumber
                                    + ") did not start; its pairing was rolled back", failure);
                        }
                    });
                    return new StartOutcome.Launching(launch.pairing());
                })
                .exceptionally(failure -> {
                    Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                            ? failure.getCause() : failure;
                    if (cause instanceof BabysitStartRefusedException refused) {
                        return new StartOutcome.Refused(refused.reason().message());
                    }
                    throw failure instanceof CompletionException completion ? completion
                            : new CompletionException(cause);
                });
    }

    @Override
    public CompletableFuture<Optional<BabysitPairing>> activePairingOf(ManagedSessionId babysitter) {
        return CompletableFuture.completedFuture(
                BabysitStateTransforms.activePairingOf(stateStore.get().state(), babysitter));
    }

    @Override
    public CompletableFuture<GhCiRerunService.Outcome> rerunFailedJobs(BabysitPairing pairing, long runId) {
        Optional<Path> root = rootOf(pairing);
        if (root.isEmpty()) {
            return CompletableFuture.completedFuture(new GhCiRerunService.Rejected(REPOSITORY_GONE));
        }
        return CompletableFuture.supplyAsync(() -> SessionPullRequestLookup.repositoryOf(root.get(), projects.get()),
                        executor)
                .thenCompose(repository -> reruns.rerunFailedJobs(root.get(), repository, runId));
    }

    @Override
    public CompletableFuture<GhCiRerunService.RunRead> readRun(BabysitPairing pairing, long runId) {
        Optional<Path> root = rootOf(pairing);
        if (root.isEmpty()) {
            return CompletableFuture.completedFuture(new GhCiRerunService.RunUnreadable(REPOSITORY_GONE));
        }
        return CompletableFuture.supplyAsync(() -> SessionPullRequestLookup.repositoryOf(root.get(), projects.get()),
                        executor)
                .thenCompose(repository -> reruns.readRun(root.get(), repository, runId));
    }

    /** The PR's head through the monitor's own read ({@link PrMonitorRead}): a failed read is never a head. */
    @Override
    public CompletableFuture<PullRequestHead> pullRequestHeadOf(BabysitPairing pairing) {
        Optional<Path> root = rootOf(pairing);
        if (root.isEmpty()) {
            return CompletableFuture.completedFuture(new PullRequestHead.Unresolved(REPOSITORY_GONE));
        }
        return CompletableFuture.supplyAsync(() -> SessionPullRequestLookup.repositoryOf(root.get(), projects.get()),
                        executor)
                .thenCompose(repository -> prReads.read(root.get(), repository, pairing.prNumber()))
                .thenApply(read -> switch (read) {
                    case PrSnapshot.Snapshot snapshot -> new PullRequestHead.Known(snapshot.headRefOid(),
                            snapshot.headRefName());
                    case PrSnapshot.Unsupported unsupported -> new PullRequestHead.Unresolved("gh is not installed");
                    case PrSnapshot.Failed failed -> new PullRequestHead.Unresolved(failed.message());
                });
    }

    private Optional<Path> rootOf(BabysitPairing pairing) {
        ApplicationState state = stateStore.get().state();
        return state.repositories().stream()
                .filter(repository -> repository.id().equals(pairing.repositoryId()))
                .map(Repository::root)
                .findFirst();
    }

    @Override
    public CompletableFuture<EscalateOutcome> escalate(ManagedSessionId babysitter, String message) {
        return CompletableFuture.supplyAsync(() -> {
            UUID id = UUID.randomUUID();
            Instant now = Instant.now();
            AtomicReference<Escalation> raised = new AtomicReference<>();
            stateStore.get().update(state -> {
                Optional<BabysitPairing> pairing = BabysitStateTransforms.activePairingOf(state, babysitter);
                if (pairing.isEmpty()) {
                    raised.set(null);
                    return state;
                }
                Escalation escalation = new Escalation(id, pairing.get().watchedId(), babysitter,
                        pairing.get().prNumber(), message, now, false);
                raised.set(escalation);
                return BabysitStateTransforms.withEscalationAppended(state, escalation);
            });
            Escalation escalation = raised.get();
            if (escalation == null) {
                return new EscalateOutcome.Refused("This session's babysitting engagement has stopped;"
                        + " nothing was escalated.");
            }
            DrydockSessionsListener.publishChanged();
            return new EscalateOutcome.Escalated(escalation);
        }, executor);
    }
}
