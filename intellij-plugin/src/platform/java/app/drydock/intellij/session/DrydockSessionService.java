package app.drydock.intellij.session;

import app.drydock.activity.SessionActivityWatcher;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.agent.api.ConversationSource;
import app.drydock.agent.api.CreateContext;
import app.drydock.agent.api.LaunchPlan;
import app.drydock.agent.api.LaunchResources;
import app.drydock.agent.api.McpAccess;
import app.drydock.agent.api.RemotePersistence;
import app.drydock.agent.api.ResumeContext;
import app.drydock.agent.api.SessionIdDiscovery;
import app.drydock.agent.api.SessionIdStrategy;
import app.drydock.agent.api.StdioMcpServer;
import app.drydock.agent.providers.AgentCommands;
import app.drydock.agent.spi.AgentProvider;
import app.drydock.app.ApplicationStateStore;
import app.drydock.delayedtasks.TaskLaunchResources;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ApplicationState;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.ModelTiers;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.RepositorySettings;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionStatusFacet;
import app.drydock.domain.SessionWorkspace;
import app.drydock.domain.WorkspaceBoxBinding;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.DrydockSessionsListener;
import app.drydock.intellij.agent.AgentConsoleHost;
import app.drydock.intellij.agent.AgentExecutionListener;
import app.drydock.intellij.agent.AgentExecutionRegistry;
import app.drydock.intellij.agent.LaunchClaims;
import app.drydock.intellij.mcp.IdeMcpServer;
import app.drydock.intellij.project.ProjectRepositoryService;
import app.drydock.intellij.review.SessionMcpAccess;
import app.drydock.intellij.workspace.RemoteSessionLiveness;
import app.drydock.intellij.workspace.WorkspaceSettings;
import app.drydock.mcp.McpSessionContext;
import app.drydock.mcp.McpSessionContext.RenameKind;
import app.drydock.mcp.McpSessionContext.RenameOutcome;
import app.drydock.mcp.McpToolException;
import app.drydock.mcp.PromptSafety;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.tasks.TaskGuidance;
import app.drydock.workspace.BoxShell;
import app.drydock.workspace.CommandRunner;
import app.drydock.workspace.WorkspacesCli;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.ui.Messages;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The IDE session orchestrator: the create flow plus the resume flows, a
 * lean port of the app tree's {@code SessionManager.launchNewSession} and
 * {@code SessionManager.resumeSession}/{@code adoptConversation} (R6 — the
 * 1615-LOC FX-bound class is deliberately NOT extracted), against the same
 * core SPI. Drydock MCP access is minted per launch through
 * {@link SessionMcpAccess} and released when the agent ends
 * ({@link SessionEnds}), when a launch fails before reaching a process, and
 * when the session is forgotten. Remote launches run on workspace boxes
 * ({@link WorkspaceBoxBinding}): create and resume attach to a box-side tmux
 * session, and the explicit Stop ({@link #stopSession}) and the explicit
 * Forget ({@link #deleteSession}) are the only paths that may issue
 * {@code tmux kill-session} on it (D1) — Start, transfer and the Done flow
 * refuse a remote row, each with a named reason. Diagnostic command
 * overrides, eval marking and the handoff paths are absent by design.
 *
 * <p>Threading (AC7/AC12): every blocking step — the pre-spawn id-store
 * snapshot (disk I/O), {@code buildCreateCommand} (may probe capabilities),
 * state persistence — runs on the supplied {@link ExecutorService}; callers
 * get futures and never have their thread blocked. Progress UI is the
 * caller's concern. The launch future completes on the executor, after the
 * EDT hop {@link AgentConsoleHost#startConsole} makes to build the console.</p>
 *
 * <p>State discipline (C4): every mutation goes through the plugin's single
 * writer ({@link ApplicationStateStore#update}); this class never loads or
 * saves the state file on its own.</p>
 *
 * <p>Id capture (AC14): PRESET providers (Claude) get a UUID generated by
 * this service up front; DISCOVERED providers (Pi, Codex) are snapshot-first
 * — {@code idDiscovery().snapshot(cwd)} runs BEFORE the spawn so the new
 * session's own rollout cannot already be in the snapshot — and the id is
 * discovered after the tab opens, detached: a discovery failure never fails
 * the launch (resume falls back to the picker). A claimed-id set keeps two
 * DISCOVERED launches from binding the same id.</p>
 *
 * <p>Resume (AC20/AC21): a session with a known agent session id resumes by
 * id; a session without one resumes through the provider's no-id resume (Pi:
 * the interactive {@code pi --resume} picker — the provider itself never
 * emits {@code --continue}/{@code --last}, and the resume context this
 * service builds carries an <em>empty</em> optional so exactly that branch
 * runs). Picking an on-disk conversation records a fresh managed session
 * bound to the conversation id first (idempotently — one row per
 * conversation), then takes the same resume-with-id path.</p>
 *
 * <p>This class is also the persistence half of
 * {@link AgentExecutionListener}: a process that exited on its own persists
 * {@code EXITED}, one drydock stopped persists {@code INACTIVE}, and
 * <strong>neither removes the row</strong>. Only an explicit Forget does.
 * That is the single biggest correction to the v2 runtime, where a closed tab
 * deleted the session it belonged to — the display tier now has no way to
 * destroy durable state, because it no longer reports anything that could.</p>
 *
 * <p>Every mutation also publishes {@link DrydockSessionsListener}: the
 * surfaces that show sessions live in other windows (and on the Welcome
 * screen, in no window at all), so a change has to be announced rather than
 * pushed to a panel this service knows about.</p>
 */
public final class DrydockSessionService implements AgentExecutionListener {

    private static final Logger LOG = System.getLogger(DrydockSessionService.class.getName());
    private static final Duration TRANSFER_SUMMARY_TIMEOUT = Duration.ofMinutes(5);
    private static final int MAX_TRANSFER_OUTPUT = 256 * 1024;
    private static final String TRANSFER_BEGIN = "DRYDOCK_TRANSFER_V1_BEGIN_";
    private static final String TRANSFER_END = "DRYDOCK_TRANSFER_V1_END_";

    /**
     * The fixed prompt carried on a resumed task session's command line.
     * The conversation history carries what the task was; the prompt only
     * has to tell the agent where it is and what to do next.
     */
    private static final String RESUME_PROMPT = "You are resuming this task in a fresh session. "
            + "Re-orient from the conversation history and continue the work where you left off.";

    /** How long a remote Forget waits for the user's answer to its confirm dialog. */
    private static final long CONFIRM_TIMEOUT_MINUTES = 30;

    private final CompletableFuture<Void> initialization;
    private final Supplier<ApplicationStateStore> stateStore;
    private final Supplier<AgentRegistry> registry;
    private final Supplier<SessionActivityWatcher> watcher;
    private final Function<Path, CompletableFuture<RepositoryId>> repositoryIdFor;
    private final AgentConsoleHost consoles;
    private final ExecutorService executor;
    /**
     * The stdio MCP servers a session in a given worktree starts: the IDE's
     * bundled server routed to that worktree, when it is enabled. Never fails
     * a launch; a broken lookup degrades to no IDE tools.
     */
    private final Function<Path, List<StdioMcpServer>> stdioMcpServers;
    /** Mints and revokes each launch's Drydock MCP credentials. */
    private final SessionMcpAccess mcpAccess;

    /**
     * The application-wide launch claims; every launch holds its session's
     * claim from before it mints until its future completes.
     */
    private final LaunchClaims claims;

    /** The end-of-agent half: persistence, late id binding, and the id bookkeeping both halves share. */
    private final SessionEnds ends;

    /**
     * The launch-resources resolver (T29a): null is every pre-T29
     * caller's path -- nothing resolves and every launch is
     * byte-identical to before.
     */
    private final TaskLaunchResources taskLaunchResources;

    /** Whether workspace-box sessions are opted in (Settings › Tools › Drydock). */
    private final BooleanSupplier workspaceSessionsEnabled;

    /** The batch ssh seam a remote resume probes the box through. */
    private final BoxShell boxShell;

    /** The read-only `workspaces list` seam that classifies a failed probe. */
    private final WorkspacesCli workspacesCli;

    /**
     * The requested-client-end coordination for remote rows: which end this
     * IDE asked for — an explicit Stop or a close — so the end recorder
     * settles by origin instead of by the bare stop-requested flag. Shared
     * through the registry because the window that requests an end and the
     * window whose recorder delivers it can differ.
     */
    private final AgentExecutionRegistry.RemoteClientEnds clientEnds;

    /** The detached-remote liveness map a refused resume records its verdict in. */
    private final RemoteSessionLiveness remoteLiveness;

    /**
     * Asks the user one Yes/No question about a remote row — the Forget
     * confirm a kill-session could not settle. Production hops to the EDT
     * and shows a {@code Messages} dialog; tests inject a fake through the
     * full seam.
     */
    private final Function<String, CompletableFuture<Boolean>> remoteConfirm;

    /**
     * The one environment variable every box-side agent carries: all LLM
     * traffic goes to the box-local gateway-only proxy (OQ8, the contract's
     * `RemotePersistence(id, {ANTHROPIC_BASE_URL})`).
     */
    private static final String BOX_PROXY_BASE_URL = "http://127.0.0.1:4000";

    /** The immutable agent environment every remote launch renders, in sorted key order. */
    private static final Map<String, String> BOX_AGENT_ENV = Map.of("ANTHROPIC_BASE_URL", BOX_PROXY_BASE_URL);

    public DrydockSessionService(DrydockPluginService plugin, AgentConsoleHost consoles,
            ProjectRepositoryService repos, ExecutorService executor) {
        this(plugin.initialization(), plugin::stateStore, plugin::registry, plugin::watcher,
                repos::repositoryIdFor, consoles, executor, IdeMcpServer::forWorktree, plugin.reviewAccess(),
                AgentExecutionRegistry.getInstance().launchClaims(), taskLaunchResources(plugin),
                WorkspaceSettings::enabled, new BoxShell(CommandRunner.PRODUCTION),
                new WorkspacesCli(CommandRunner.PRODUCTION), plugin.remoteSessionLiveness(),
                DrydockSessionService::askOnEdt, AgentExecutionRegistry.getInstance().remoteClientEnds());
    }

    /**
     * The production launch-resources resolver (T29a): the per-user task
     * store's retained receipts plus the guidance installed under that
     * same root, gated on the live MCP surface actually serving the task
     * tools (the router's own serving set, upstream of every provider).
     */
    private static TaskLaunchResources taskLaunchResources(DrydockPluginService plugin) {
        Path tasksRoot = plugin.taskService().root();
        return new TaskLaunchResources(tasksRoot, new TaskGuidance(tasksRoot), plugin::mcpServesTaskTools);
    }

    /**
     * Test seam: the init gate and the resolved components directly, without
     * the IDE (the application service's own test constructor is
     * package-private in another package and its real registry probes real
     * executables). Mirrors the seam pattern of {@code ProjectRepositoryService}.
     */
    DrydockSessionService(CompletableFuture<Void> initialization, Supplier<ApplicationStateStore> stateStore,
            Supplier<AgentRegistry> registry, Supplier<SessionActivityWatcher> watcher,
            Function<Path, CompletableFuture<RepositoryId>> repositoryIdFor,
            AgentConsoleHost consoles, ExecutorService executor,
            Function<Path, List<StdioMcpServer>> stdioMcpServers, SessionMcpAccess mcpAccess,
            LaunchClaims claims) {
        this(initialization, stateStore, registry, watcher, repositoryIdFor, consoles, executor,
                stdioMcpServers, mcpAccess, claims, null);
    }

    /**
     * As above, with the launch-resources resolver (T29a): the resolver's
     * blocking receipt and directory I/O runs on this service's executor
     * stages, never the EDT. A null resolver is every pre-T29 caller's
     * path, byte-identical.
     */
    DrydockSessionService(CompletableFuture<Void> initialization, Supplier<ApplicationStateStore> stateStore,
            Supplier<AgentRegistry> registry, Supplier<SessionActivityWatcher> watcher,
            Function<Path, CompletableFuture<RepositoryId>> repositoryIdFor,
            AgentConsoleHost consoles, ExecutorService executor,
            Function<Path, List<StdioMcpServer>> stdioMcpServers, SessionMcpAccess mcpAccess,
            LaunchClaims claims, TaskLaunchResources taskLaunchResources) {
        this(initialization, stateStore, registry, watcher, repositoryIdFor, consoles, executor,
                stdioMcpServers, mcpAccess, claims, taskLaunchResources, () -> false,
                new BoxShell(CommandRunner.PRODUCTION), new WorkspacesCli(CommandRunner.PRODUCTION),
                new RemoteSessionLiveness(), DrydockSessionService::askOnEdt,
                AgentExecutionRegistry.RemoteClientEnds.INERT);
    }

    /**
     * The full seam, with the workspace-box components a remote row's create
     * and resume need: the opt-in flag (a seam so plain unit tests answer it
     * without the IDE's application; production wires {@code
     * WorkspaceSettings::enabled}), the batch ssh and {@code workspaces list}
     * seams, the detached-remote liveness map, the remote Forget's
     * user-confirm seam, and the requested-client-end coordination the
     * Stop and close paths mark and the end settle settles by. For a local
     * row none of these is ever consulted, so every pre-workspace-box
     * construction site is byte-identical to before (INV-1).
     */
    DrydockSessionService(CompletableFuture<Void> initialization, Supplier<ApplicationStateStore> stateStore,
            Supplier<AgentRegistry> registry, Supplier<SessionActivityWatcher> watcher,
            Function<Path, CompletableFuture<RepositoryId>> repositoryIdFor,
            AgentConsoleHost consoles, ExecutorService executor,
            Function<Path, List<StdioMcpServer>> stdioMcpServers, SessionMcpAccess mcpAccess,
            LaunchClaims claims, TaskLaunchResources taskLaunchResources,
            BooleanSupplier workspaceSessionsEnabled, BoxShell boxShell, WorkspacesCli workspacesCli,
            RemoteSessionLiveness remoteLiveness,
            Function<String, CompletableFuture<Boolean>> remoteConfirm,
            AgentExecutionRegistry.RemoteClientEnds clientEnds) {
        this.initialization = Objects.requireNonNull(initialization, "initialization");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.watcher = Objects.requireNonNull(watcher, "watcher");
        this.repositoryIdFor = Objects.requireNonNull(repositoryIdFor, "repositoryIdFor");
        this.consoles = Objects.requireNonNull(consoles, "consoles");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.stdioMcpServers = Objects.requireNonNull(stdioMcpServers, "stdioMcpServers");
        this.mcpAccess = Objects.requireNonNull(mcpAccess, "mcpAccess");
        this.claims = Objects.requireNonNull(claims, "claims");
        this.taskLaunchResources = taskLaunchResources;
        this.workspaceSessionsEnabled = Objects.requireNonNull(workspaceSessionsEnabled, "workspaceSessionsEnabled");
        this.boxShell = Objects.requireNonNull(boxShell, "boxShell");
        this.workspacesCli = Objects.requireNonNull(workspacesCli, "workspacesCli");
        this.remoteLiveness = Objects.requireNonNull(remoteLiveness, "remoteLiveness");
        this.remoteConfirm = Objects.requireNonNull(remoteConfirm, "remoteConfirm");
        this.clientEnds = Objects.requireNonNull(clientEnds, "clientEnds");
        this.ends = new SessionEnds(stateStore, registry, executor, mcpAccess, claims, boxShell,
                () -> remoteLiveness, clientEnds);
    }

    // ---- create flow ---------------------------------------------------------

    /**
     * Mints the metadata for a brand-new session in {@code repoRoot} WITHOUT
     * launching anything (the app tree's prepare-then-launch split: the launch
     * persists the session almost immediately, so UI bookkeeping must not
     * register placeholders under a provisional id). Off the EDT; the repo
     * resolution is R7-keyed on the working directory itself, not on the
     * project that started it.
     */
    public CompletableFuture<ManagedAgentSession> prepareSession(Path repoRoot, AgentKind kind, String displayName) {
        return prepareSession(repoRoot, kind, displayName, SessionMode.WRITES);
    }

    /** As {@link #prepareSession(Path, AgentKind, String)} in an explicit mode. */
    public CompletableFuture<ManagedAgentSession> prepareSession(Path repoRoot, AgentKind kind, String displayName,
            SessionMode mode) {
        return prepareSession(repoRoot, repoRoot, kind, displayName, mode);
    }

    /**
     * As {@link #prepareSession(Path, AgentKind, String, SessionMode)}, but
     * recorded under the repository at {@code repositoryRoot} instead of the
     * working directory's own. A task in a linked worktree records its main
     * checkout, so it can still be placed under its project once git no
     * longer knows the worktree (removed outside Drydock).
     */
    public CompletableFuture<ManagedAgentSession> prepareSession(Path repoRoot, Path repositoryRoot, AgentKind kind,
            String displayName, SessionMode mode) {
        return prepareSession(repoRoot, repositoryRoot, kind, displayName, mode, Optional.empty());
    }

    /**
     * As above, but the session runs on the workspace box {@code workspaceBox}
     * names: a <em>remote</em> row. The binding is the row's single source of
     * remote truth -- it derives the working directory the row stores (the
     * binding's deterministic placeholder root), the {@code SshRemote} every
     * launch context carries, and the tmux session the agent lives in. An
     * empty binding is every local launch, exactly as before (INV-1). The
     * launch itself still happens through {@link #launchSession}, which keys
     * on the row's binding the same way.
     */
    public CompletableFuture<ManagedAgentSession> prepareSession(Path repoRoot, Path repositoryRoot, AgentKind kind,
            String displayName, SessionMode mode, Optional<WorkspaceBoxBinding> workspaceBox) {
        Objects.requireNonNull(repoRoot, "repoRoot");
        Objects.requireNonNull(repositoryRoot, "repositoryRoot");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(workspaceBox, "workspaceBox");
        Path workingDirectory = repoRoot.toAbsolutePath().normalize();
        return repositoryIdFor.apply(repositoryRoot.toAbsolutePath().normalize())
                .thenApplyAsync(
                        repositoryId -> newSessionMetadata(repositoryId, workingDirectory, kind, displayName,
                                mode, workspaceBox),
                        executor);
    }

    /**
     * Launches a session minted by {@link #prepareSession}. On the executor:
     * persist STARTING, snapshot the id store (DISCOVERED providers, before
     * the spawn), build the create command, reject an unsupported plan
     * (AC11 — an error naming the agent, no tab opened), open the terminal
     * tab, then persist RUNNING. The returned future completes with the
     * RUNNING session or exceptionally after the FAILED status is persisted.
     * It also completes exceptionally, persisting nothing, when the session
     * has a running agent or another launch in flight.
     */
    public CompletableFuture<ManagedAgentSession> launchSession(ManagedAgentSession prepared) {
        return launchSession(prepared, Optional.empty());
    }

    /**
     * As {@link #launchSession(ManagedAgentSession)}, handing the agent
     * {@code initialPrompt} — the task typed into the New Agent Session
     * dialog — as its first message, for providers whose CLI accepts one on
     * the command line (see {@code CreateContext.initialPrompt}).
     */
    public CompletableFuture<ManagedAgentSession> launchSession(ManagedAgentSession prepared,
                                                                Optional<String> initialPrompt) {
        return launchSession(prepared, initialPrompt, Optional.empty(), Optional.empty());
    }

    /**
     * As {@link #launchSession(ManagedAgentSession, Optional)}, handing the
     * launch {@code model} — the starting model the human picked — which the
     * provider renders as its CLI's model flag (see {@code
     * CreateContext.model}), and the subagent model {@code tiers}, which
     * reach the agent only through the prompt. Empty {@code model} means the
     * harness's own default. Both are launch-time only: a resume never
     * carries them. When the session reaches RUNNING (see {@code
     * finalizeCreate}) the model is recorded as the repository's last-used
     * model for this harness, and the tiers too when present; empty {@code
     * tiers} means the launch showed no tier pickers, so the remembered
     * tiers stay.
     */
    public CompletableFuture<ManagedAgentSession> launchSession(ManagedAgentSession prepared,
                                                                Optional<String> initialPrompt,
                                                                Optional<String> model,
                                                                Optional<ModelTiers> tiers) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(tiers, "tiers");
        return initialization
                .thenApplyAsync(unused -> {
                    ends.seedClaimedIdsOnce();
                    return prepared;
                }, executor)
                // Async, or the whole pre-spawn stage would run on the CALLING
                // thread whenever the gate is already complete (the common
                // production case): thenCompose on a completed source runs
                // its function inline.
                .thenComposeAsync(session -> launchPrepared(session, initialPrompt, model, tiers, false), executor);
    }

    /**
     * Replaces a stopped session's provider while retaining its Drydock
     * identity and UI placement.  The old binding is not changed until its
     * exact provider conversation has produced a bounded, validated one-shot
     * summary.  If the target cannot start, the old binding is restored so
     * the original conversation remains resumable.
     *
     * <p>The transfer claims the session for its whole run, summary
     * included: the summary runs the source conversation, and a resume at
     * the same time would run it twice. It completes exceptionally, doing
     * nothing, while the session has a running agent or another launch in
     * flight.</p>
     */
    public CompletableFuture<ManagedAgentSession> transferStoppedSession(ManagedSessionId sessionId,
                                                                          AgentKind targetKind) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(targetKind, "targetKind");
        return initialization.thenComposeAsync(unused -> {
            if (!claims.tryClaim(sessionId)) {
                throw launchRefused(sessionId);
            }
            CompletableFuture<ManagedAgentSession> transferred;
            try {
                transferred = transferClaimed(sessionId, targetKind);
            } catch (RuntimeException | Error e) {
                endClaim(sessionId);
                throw e;
            }
            return transferred.whenComplete((ignored, ex) -> endClaim(sessionId));
        }, executor);
    }

    private CompletableFuture<ManagedAgentSession> transferClaimed(ManagedSessionId sessionId,
                                                                   AgentKind targetKind) {
        ManagedAgentSession source = requireSession(sessionId);
        // A remote row's conversation lives on its box; a transfer would
        // summarize it into a local agent that cannot see the box's files.
        if (source.workspaceBox().isPresent()) {
            throw new IllegalStateException("Session '" + source.displayName()
                    + "' runs on workspace box '" + source.workspaceBox().orElseThrow().boxName()
                    + "'; its conversation lives there and cannot be transferred to a local agent");
        }
        if (source.agentKind() == AgentKind.TERMINAL || targetKind == AgentKind.TERMINAL) {
            throw new IllegalStateException("Terminal sessions cannot be transferred");
        }
        if (source.agentKind() == targetKind) {
            throw new IllegalStateException("Choose a different agent");
        }
        String sourceId = source.agentSessionId().orElseThrow(() ->
                new IllegalStateException("The source agent has no exact conversation id"));
        AgentProvider sourceProvider = registry.get().provider(source.agentKind()).orElseThrow(() ->
                new IllegalStateException("The source agent provider is not installed"));
        if (!registry.get().isAvailable(targetKind)) {
            throw new IllegalStateException("The target agent is not installed");
        }
        String summary = requestTransferSummary(source, sourceProvider, sourceId);
        ManagedAgentSession switched = source.toBuilder()
                .binding(AgentBinding.unlaunched(targetKind, source.mode()))
                .status(SessionStatus.STARTING)
                .lastExitCode(Optional.empty())
                .build();
        String initialPrompt = "You are taking over an existing Drydock task from "
                + kindName(source.agentKind()) + ". Continue from this transfer record:\n\n" + summary;
        return launchReplacement(switched, Optional.of(initialPrompt));
    }

    /** Starts a replacement without persisting its binding until the console exists. */
    private CompletableFuture<ManagedAgentSession> launchReplacement(ManagedAgentSession candidate,
                                                                      Optional<String> initialPrompt) {
        AgentProvider provider = registry.get().provider(candidate.agentKind()).orElseThrow(() ->
                new IllegalStateException("The target agent provider is not installed"));
        String generatedId = provider.idStrategy() == SessionIdStrategy.PRESET
                ? UUID.randomUUID().toString() : "";
        Optional<SessionIdDiscovery> discovery = provider.idStrategy() == SessionIdStrategy.DISCOVERED
                ? provider.idDiscovery() : Optional.empty();
        Object snapshot = discovery.map(value -> value.snapshot(candidate.workingDirectory())).orElse(null);
        Instant launchedAt = Instant.now();
        Optional<McpAccess> access = mcpAccessFor(candidate.id(), provider);
        // T29a: the replacement create resolves the SAME resources a direct
        // create of this session would, before its context is built -- a
        // transfer must not drop the session's task context.
        Optional<LaunchResources> resources = launchResourcesFor(candidate.id(), access);
        CreateContext context = new CreateContext(candidate.displayName(), generatedId,
                candidate.workingDirectory(), Optional.empty(), access, false,
                candidate.readOnly(), initialPrompt).withStdioMcpServers(stdioMcpServersFor(candidate.workingDirectory()));
        if (resources.isPresent()) {
            context = context.withLaunchResources(resources.get());
        }
        LaunchPlan plan = provider.buildCreateCommand(context);
        releaseIfUnused(candidate.id(), access, plan);
        if (!plan.supported()) {
            throw new IllegalStateException(kindName(provider.kind())
                    + " cannot launch this session (unsupported context)");
        }
        boolean sessionIdUsed = !generatedId.isEmpty() && plan.sessionIdUsed()
                && plan.command().contains(generatedId);
        StartedLaunch started = new StartedLaunch(candidate, discovery, snapshot, launchedAt,
                generatedId, sessionIdUsed, Optional.empty(), Optional.empty(),
                consoles.startConsole(candidate.id(), candidate.workingDirectory(), plan));
        return started.openFuture().handleAsync((ignored, failure) -> {
            if (failure != null) {
                throw wrap(unwrap(failure));
            }
            ManagedAgentSession running = candidate.withStatus(SessionStatus.RUNNING)
                    .withLastOpenedAt(Instant.now());
            if (started.sessionIdUsed()) {
                running = running.withAgentSessionId(Optional.of(started.generatedId()));
            }
            persistUpdatedSession(running);
            ends.lastLaunchedAt().put(candidate.id(), launchedAt);
            scheduleDetachedDiscovery(started);
            return running;
        }, executor);
    }

    private String requestTransferSummary(ManagedAgentSession source, AgentProvider provider, String sourceId) {
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String beginMarker = TRANSFER_BEGIN + nonce;
        String endMarker = TRANSFER_END + nonce;
        try {
            String prompt = transferSummaryPrompt(beginMarker, endMarker, Optional.empty());
            ProcessResult result = runTransferSummary(source, provider, sourceId, prompt);
            try {
                return parseTransferSummary(result.stdout(), beginMarker, endMarker);
            } catch (IllegalStateException firstFailure) {
                LOG.log(Level.INFO, () -> "Retrying transfer summary for " + source.id()
                        + " after validation failure: " + firstFailure.getMessage());
                String retry = transferSummaryPrompt(beginMarker, endMarker,
                        Optional.of(firstFailure.getMessage()));
                ProcessResult retried = runTransferSummary(source, provider, sourceId, retry);
                return parseTransferSummary(retried.stdout(), beginMarker, endMarker);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException(new IllegalStateException("Transfer was cancelled", e));
        } catch (IOException e) {
            throw new CompletionException(new IllegalStateException("Could not run the source agent", e));
        }
    }

    private ProcessResult runTransferSummary(ManagedAgentSession source, AgentProvider provider,
                                             String sourceId, String prompt)
            throws IOException, InterruptedException {
        List<String> command = provider.buildTransferSummaryCommand(source.workingDirectory(), sourceId, prompt)
                .orElseThrow(() -> new IllegalStateException(
                        kindName(source.agentKind()) + " does not support non-interactive transfer"));
        ProcessResult result = ProcessRunner.run(command, new ProcessRunner.Options(
                source.workingDirectory(), TRANSFER_SUMMARY_TIMEOUT, true, Map.of()), MAX_TRANSFER_OUTPUT);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("The source agent could not create a transfer summary: "
                    + ProcessRunner.excerpt(result.stderr()));
        }
        return result;
    }

    private static String transferSummaryPrompt(String beginMarker, String endMarker,
                                                Optional<String> previousFailure) {
        String correction = previousFailure.map(failure -> "The previous response was rejected because: "
                + failure + " Rewrite it more concisely; do not repeat it verbatim. ").orElse("");
        return correction
                + "Prepare a concise handoff for another coding agent. Do not modify files or run tools. "
                + "Every field value must be at most 1800 Unicode characters, including multiline bullets, "
                + "and the whole record must be at most 7000 characters. "
                + "Return only this exact structure (every field is required):\n"
                + beginMarker + "\nGOAL: <current goal>\nCOMPLETED: <what is done>\n"
                + "NEXT: <concrete next steps>\nRISKS: <constraints, failures, or none>\n" + endMarker;
    }

    static String parseTransferSummary(String output) {
        return parseTransferSummary(output, TRANSFER_BEGIN, TRANSFER_END);
    }

    static String parseTransferSummary(String output, String beginMarker, String endMarker) {
        if (output == null || output.isBlank()) {
            throw new IllegalStateException("The source agent returned an empty transfer summary");
        }
        if (output.length() > MAX_TRANSFER_OUTPUT) {
            throw new IllegalStateException("The source agent returned too much transfer output");
        }
        // JSON output modes escape line breaks. Unescaping these two common
        // sequences is enough to expose the deliberately unique envelope;
        // the payload remains plain text and is never interpreted as JSON.
        String normalized = output.replace("\\r\\n", "\n").replace("\\n", "\n");
        int begin = normalized.lastIndexOf(beginMarker);
        int end = begin < 0 ? -1 : normalized.indexOf(endMarker, begin);
        if (begin < 0 || end < 0) {
            throw new IllegalStateException("The source agent returned an invalid transfer summary");
        }
        String summary = normalized.substring(begin, end + endMarker.length()).strip();
        List<String> requiredFields = List.of("GOAL:", "COMPLETED:", "NEXT:", "RISKS:");
        List<Integer> fieldOffsets = new ArrayList<>();
        int searchFrom = 0;
        for (String required : requiredFields) {
            int field = summary.indexOf("\n" + required, searchFrom);
            if (field < 0) {
                throw new IllegalStateException("The transfer summary is missing " + required);
            }
            fieldOffsets.add(field);
            searchFrom = field + required.length() + 1;
        }
        List<String> values = new ArrayList<>();
        for (int index = 0; index < requiredFields.size(); index++) {
            String required = requiredFields.get(index);
            int field = fieldOffsets.get(index);
            int valueStart = field + required.length() + 1;
            int valueEnd = index + 1 < fieldOffsets.size()
                    ? fieldOffsets.get(index + 1)
                    : summary.indexOf("\n" + endMarker, valueStart);
            if (valueEnd < 0) {
                valueEnd = summary.length();
            }
            String value = summary.substring(valueStart, valueEnd).strip();
            if (value.isBlank() || (value.startsWith("<") && value.endsWith(">"))) {
                throw new IllegalStateException("The transfer summary has no value for " + required);
            }
            try {
                values.add(PromptSafety.checkHandoffSlot(required, value));
            } catch (McpToolException e) {
                throw new IllegalStateException("Unsafe transfer summary: " + e.getMessage(), e);
            }
        }
        try {
            PromptSafety.checkHandoffRecordSize(values);
        } catch (McpToolException e) {
            throw new IllegalStateException("Unsafe transfer summary: " + e.getMessage(), e);
        }
        if (summary.length() > 32 * 1024) {
            throw new IllegalStateException("The transfer summary is too large");
        }
        return summary;
    }

    /**
     * @param rowMustExist true for a fresh start on an existing row ({@link
     *                     #startSession}, {@link #resumeOrStartSession}): the
     *                     row was read before the claim, so a delete may have
     *                     removed it since, and the launch must not add it back
     */
    private CompletableFuture<ManagedAgentSession> launchPrepared(ManagedAgentSession pending,
                                                                  Optional<String> initialPrompt,
                                                                  Optional<String> model,
                                                                  Optional<ModelTiers> tiers,
                                                                  boolean rowMustExist) {
        // Stamped before the persist: the marker rides the row's first save,
        // like the initialPrompt it mirrors, and earns the session the fixed
        // resume prompt on its next resume.
        ManagedAgentSession prepared = pending.withStartedWithTask(initialPrompt.isPresent());
        // Claimed before anything is persisted or minted: a refused launch
        // must not touch the row, the token or the credential file of the
        // agent that already holds the session.
        if (!claims.tryClaim(prepared.id())) {
            throw launchRefused(prepared.id());
        }
        CompletableFuture<ManagedAgentSession> launched;
        try {
            AgentKind kind = prepared.agentKind();
            AgentProvider provider = registry.get().provider(kind)
                    .orElseThrow(() -> new IllegalStateException("No " + kind + " (" + kind.persistedName()
                            + ") provider is registered; cannot launch session"));
            StartedLaunch started;
            try {
                started = startLaunch(prepared, provider, initialPrompt, model, tiers, rowMustExist);
            } catch (RuntimeException failure) {
                // The session row exists from here on for a local launch
                // (its persist ran first), so the failure must be visible in
                // the persisted state too. A refused REMOTE launch threw
                // before its row ever persisted (INV-16) -- its refusal type
                // says so -- and persisting FAILED then would write the row
                // the refusal refused to create.
                if (!(failure instanceof RemoteLaunchRefusal)) {
                    persistFailedBestEffort(prepared);
                }
                throw failure;
            }
            launched = started.openFuture().handleAsync((ignored, ex) -> finalizeCreate(started, ex), executor);
        } catch (RuntimeException | Error e) {
            endClaim(prepared.id());
            throw e;
        }
        // The claim ends when the console open-future completes, with no
        // timeout. AgentExecutionRegistry.start completes that future on every
        // path of its invokeLater runnable (disposed host, RuntimeException,
        // success); the only way it stays pending is an invokeLater that never
        // runs, which happens at application shutdown, when no further launch
        // can start.
        return launched.whenComplete((ignored, ex) -> endClaim(prepared.id()));
    }

    /**
     * Ends {@code id}'s launch claim and releases its MCP access unless the
     * session has a live execution; see {@link LaunchClaims#finish}. With
     * the claim gone, a remote settle the claim deferred — the early end of
     * the launch's own ssh client — is re-applied: a completed launch must
     * not leave RUNNING persisted when this IDE owns no live ssh execution
     * (INV-4).
     */
    private void endClaim(ManagedSessionId id) {
        claims.finish(id, () -> releaseMcpAccess(id));
        ends.settleDeferredRemoteEnd(id);
    }

    private static IllegalStateException launchRefused(ManagedSessionId id) {
        return new IllegalStateException("Session " + id + " already has a running agent or a launch in progress");
    }

    /**
     * A remote launch's pre-persist refusal (INV-16): thrown at a point
     * where the row was never persisted, so the launch's failure handling
     * must not write the FAILED row the refusal refused to create. Carries
     * IllegalStateException semantics like every other launch refusal; the
     * type exists only to tell {@code launchPrepared}'s catch apart the
     * paths that persisted nothing.
     */
    static final class RemoteLaunchRefusal extends IllegalStateException {
        RemoteLaunchRefusal(String message) {
            super(message);
        }
    }

    /** Everything that must happen before the process spawns. */
    private StartedLaunch startLaunch(ManagedAgentSession prepared, AgentProvider provider,
                                      Optional<String> initialPrompt, Optional<String> model,
                                      Optional<ModelTiers> tiers,
                                      boolean rowMustExist) {
        Optional<WorkspaceBoxBinding> box = prepared.workspaceBox();
        if (box.isPresent()) {
            return startRemoteLaunch(prepared, box.get(), provider, initialPrompt, model, tiers, rowMustExist);
        }
        if (rowMustExist) {
            // A fresh start read the row before it claimed the session; a
            // delete that finished in between removed it, and an upsert here
            // would bring the forgotten task back.
            if (!replaceExistingSession(prepared)) {
                throw new IllegalStateException("Session " + prepared.id() + " was forgotten before it started");
            }
        } else {
            // Upsert, not insert: a relaunch of the same prepared session
            // finds its row already there.
            persistSession(prepared);
        }
        String generatedId = provider.idStrategy() == SessionIdStrategy.PRESET
                ? UUID.randomUUID().toString()
                : "";
        Optional<SessionIdDiscovery> discovery = provider.idStrategy() == SessionIdStrategy.DISCOVERED
                ? provider.idDiscovery()
                : Optional.empty();
        Path cwd = prepared.workingDirectory();
        // Snapshot BEFORE the spawn and BEFORE the launch timestamp, so a
        // record the new session itself writes cannot already be in the
        // snapshot (order is what lets discovery tell "new since launch"
        // from "already there").
        Object snapshot = discovery.map(d -> d.snapshot(cwd)).orElse(null);
        Instant launchedAt = Instant.now();
        ends.lastLaunchedAt().put(prepared.id(), launchedAt);
        Optional<McpAccess> access = mcpAccessFor(prepared.id(), provider);
        // Resolved BEFORE the context is built (T29a): the receipt and
        // directory I/O is blocking, and this whole pre-spawn stage runs on
        // the executor, never the EDT (AGENTS.md). A receipt directory that
        // cannot be resolved refuses the launch visibly here; no resources
        // resolve to empty and the launch is byte-identical to before.
        Optional<LaunchResources> resources = launchResourcesFor(prepared.id(), access);
        CreateContext ctx = new CreateContext(prepared.displayName(), generatedId, cwd,
                Optional.empty(), access, model, false, prepared.readOnly(), initialPrompt, List.of())
                .withStdioMcpServers(stdioMcpServersFor(cwd));
        if (resources.isPresent()) {
            ctx = ctx.withLaunchResources(resources.get());
        }
        LaunchPlan plan = provider.buildCreateCommand(ctx);
        releaseIfUnused(prepared.id(), access, plan);
        if (!plan.supported()) {
            // AC11: name the agent, open nothing.
            throw new IllegalStateException(
                    kindName(provider.kind()) + " cannot launch this session (unsupported context)");
        }
        boolean sessionIdUsed = !generatedId.isEmpty()
                && plan.sessionIdUsed() && plan.command().contains(generatedId);
        CompletableFuture<Void> open = consoles.startConsole(prepared.id(), cwd, plan);
        return new StartedLaunch(prepared, discovery, snapshot, launchedAt, generatedId, sessionIdUsed, model,
                tiers, open);
    }

    /**
     * The workspace-box create, keyed on the row's binding: everything a
     * local {@link #startLaunch} does, reordered so that <em>every</em>
     * refusal happens before the row is persisted, anything is minted, or
     * anything is spawned (INV-16).
     *
     * <p>Only Claude runs on a box, and the refusal names the agent. The
     * launch resources are resolved with an <em>empty</em> MCP access — no
     * mint ever happens for a remote launch (INV-15) — and anything that
     * resolves is a host path the box cannot see, so a resource-bound launch
     * is refused, naming the agent, rather than silently dropping the
     * session's task context. The context carries the binding-derived remote,
     * the {@link RemotePersistence} with the box-local proxy base URL, an
     * empty MCP access and no stdio MCP servers. Only after the provider
     * accepts the context does the row persist and the console open; its cwd
     * is a directory that exists on <em>this</em> Mac (the repository's local
     * root, else the user's home), never the box placeholder the row stores
     * (REJ-18). {@code finalizeCreate} is unchanged: the generated UUID rides
     * the plan, so the row persists its {@code agentSessionId} on success.</p>
     */
    private StartedLaunch startRemoteLaunch(ManagedAgentSession prepared, WorkspaceBoxBinding box,
                                             AgentProvider provider, Optional<String> initialPrompt,
                                             Optional<String> model, Optional<ModelTiers> tiers,
                                             boolean rowMustExist) {
        if (prepared.agentKind() != AgentKind.CLAUDE) {
            throw new RemoteLaunchRefusal(kindName(prepared.agentKind())
                    + " cannot run on a workspace box; only Claude sessions can");
        }
        Optional<LaunchResources> resources = launchResourcesFor(prepared.id(), Optional.empty());
        if (resources.isPresent()) {
            throw new RemoteLaunchRefusal(kindName(prepared.agentKind())
                    + " cannot take this session's task resources onto a workspace box");
        }
        String generatedId = UUID.randomUUID().toString();
        CreateContext ctx = new CreateContext(prepared.displayName(), generatedId,
                prepared.workingDirectory(), Optional.of(box.sshRemote()), Optional.empty(), model,
                false, prepared.readOnly(), initialPrompt, List.of())
                .withPersistence(new RemotePersistence(prepared.id(), BOX_AGENT_ENV));
        LaunchPlan plan = provider.buildCreateCommand(ctx);
        if (!plan.supported()) {
            throw new RemoteLaunchRefusal(kindName(provider.kind())
                    + " cannot launch this session (unsupported context)");
        }
        boolean sessionIdUsed = plan.sessionIdUsed() && plan.command().contains(generatedId);
        if (rowMustExist) {
            // The same forgotten-row guard as the local path: a delete that
            // finished between the read and the claim must not resurrect.
            if (!replaceExistingSession(prepared)) {
                throw new IllegalStateException("Session " + prepared.id() + " was forgotten before it started");
            }
        } else {
            persistSession(prepared);
        }
        Instant launchedAt = Instant.now();
        ends.lastLaunchedAt().put(prepared.id(), launchedAt);
        // A new client is about to exist: any requested-client-end mark still
        // pending for the id belongs to an earlier client's end and must not
        // steer this launch's.
        clientEnds.clear(prepared.id());
        CompletableFuture<Void> open = consoles.startConsole(prepared.id(), consoleCwdFor(prepared), plan);
        return new StartedLaunch(prepared, Optional.empty(), null, launchedAt, generatedId, sessionIdUsed,
                model, tiers, open);
    }

    /**
     * The directory a remote row's console opens in: the session repository's
     * local root when one is recorded and still exists on this Mac, else the
     * user's home. The execution host needs a real local directory for the
     * ssh client's PTY; the row's own working directory is the box-side
     * placeholder, which does not exist here.
     */
    private Path consoleCwdFor(ManagedAgentSession session) {
        return stateStore.get().state().repositories().stream()
                .filter(repository -> repository.id().equals(session.repositoryId()))
                .findFirst()
                .map(Repository::root)
                .filter(Files::isDirectory)
                .orElseGet(() -> Path.of(System.getProperty("user.home", "/")));
    }

    private ManagedAgentSession finalizeCreate(StartedLaunch started, Throwable failure) {
        if (failure != null) {
            Throwable cause = unwrap(failure);
            LOG.log(Level.WARNING, () -> "Failed to start session " + started.prepared().id() + ": " + cause);
            persistFailedBestEffort(started.prepared());
            throw wrap(cause);
        }
        ManagedAgentSession running = started.prepared().withStatus(SessionStatus.RUNNING).withLastOpenedAt(Instant.now());
        // Only persist the generated id when the launch command actually
        // carried it — an id the agent never saw would make a later resume
        // target a nonexistent conversation.
        if (started.sessionIdUsed()) {
            running = running.withAgentSessionId(Optional.of(started.generatedId()));
        }
        persistUpdatedSession(running);
        // Records the agent kind actually used, so the next session in this
        // repository defaults to it (AC8's data half), and the starting model
        // with it -- keyed per harness, since the model spaces are disjoint.
        // An empty model means the human went back to the harness's own
        // default, and the picker must say so next time. The subagent model
        // tiers ride the same lifecycle, but only when the launch carried
        // them: a launch that showed no tier pickers leaves them as they were.
        ManagedAgentSession recorded = running;
        stateStore.get().update(state -> repoWithLastUsedLaunch(state, recorded.repositoryId(),
                recorded.agentKind(), started.model(), started.tiers()));
        scheduleDetachedDiscovery(started);
        return running;
    }

    /**
     * The DISCOVERED id capture, detached: discovery may poll for a while,
     * and its failure must never fail or delay the launch (the tab is already
     * open; resume falls back to the picker when the id stays unknown).
     */
    private void scheduleDetachedDiscovery(StartedLaunch started) {
        if (started.discovery().isEmpty() || started.snapshot() == null) {
            return;
        }
        try {
            CompletableFuture.runAsync(() -> runDiscovery(started), executor);
        } catch (RejectedExecutionException e) {
            // Shutdown already drained the executor (IDE closing): the id
            // stays unknown and a later resolve can late-bind it.
            LOG.log(Level.DEBUG, "Skipping id discovery for {0} during shutdown", started.prepared().id());
        }
    }

    private void runDiscovery(StartedLaunch started) {
        try {
            started.discovery().get()
                    .discover(started.prepared().workingDirectory(), started.launchedAt(),
                            started.snapshot(), ends.claimedAgentSessionIds())
                    .ifPresent(id -> {
                        stateStore.get().update(state -> {
                            // The session may have been forgotten while
                            // discovery polled; a no-op beats resurrecting a
                            // removed row.
                            ManagedAgentSession existing = findIn(state, started.prepared().id());
                            return existing == null
                                    ? state
                                    : withReplacedSession(state,
                                            existing.withAgentSessionId(Optional.of(id)));
                        });
                        DrydockSessionsListener.publishChanged();
                    });
        } catch (RuntimeException e) {
            // Never fails the launch (AC14): an empty id degrades resume to
            // the interactive picker.
            LOG.log(Level.WARNING, () -> started.prepared().agentKind() + " id discovery failed for "
                    + started.prepared().id() + "; resume will use the picker: " + e);
        }
    }

    /**
     * The resources this launch carries (T29a), resolved on this executor
     * stage BEFORE any context is built, never the EDT: the session's
     * retained receipt directories plus the installed guidance when this
     * launch's MCP access reaches a task-serving surface. A receipt
     * directory that cannot be resolved is a visible refusal, never a
     * silent empty-resources launch; a session carrying neither resolves
     * to empty and its launch is byte-identical to the pre-task one. A
     * null resolver (the pre-T29 seam) resolves nothing, byte-identically.
     */
    private Optional<LaunchResources> launchResourcesFor(ManagedSessionId id, Optional<McpAccess> access) {
        TaskLaunchResources resolver = taskLaunchResources;
        return resolver == null ? Optional.empty() : resolver.resolve(id, access);
    }

    /**
     * The Drydock MCP access for a launch of {@code id}, or empty when the
     * access service declines or throws. A failure is logged without the
     * credentials and never fails the launch.
     */
    private Optional<McpAccess> mcpAccessFor(ManagedSessionId id, AgentProvider provider) {
        try {
            return mcpAccess.accessFor(id, provider.mcpDelivery());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, () -> "Launching " + id + " without Drydock MCP access: " + e);
            return Optional.empty();
        }
    }

    /**
     * Revokes {@code id}'s MCP access when {@code plan} does not carry its
     * credential file. A provider can drop the Drydock server from the
     * command, for example when Claude's probe finds no {@code --mcp-config}
     * or Pi's MCP extension fails to install. Every provider puts the file
     * into the command as {@link AgentCommands#shellQuote}, so its absence
     * means the agent never reaches the server, and the session must not
     * report that it holds access.
     */
    private void releaseIfUnused(ManagedSessionId id, Optional<McpAccess> access, LaunchPlan plan) {
        Optional<Path> file = access.flatMap(McpAccess::credentialFile);
        if (file.isPresent() && !plan.command().contains(AgentCommands.shellQuote(file.get().toString()))) {
            LOG.log(Level.INFO, () -> "Launch of " + id + " does not use Drydock MCP access; releasing it");
            releaseMcpAccess(id);
        }
    }

    /** Revokes {@code id}'s MCP access; a failure is logged and swallowed. */
    private void releaseMcpAccess(ManagedSessionId id) {
        try {
            mcpAccess.release(id);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, () -> "Releasing Drydock MCP access for " + id + " failed: " + e);
        }
    }

    private List<StdioMcpServer> stdioMcpServersFor(Path workingDirectory) {
        try {
            return stdioMcpServers.apply(workingDirectory);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, () -> "Launching without the IDE's MCP server for " + workingDirectory
                    + ": " + e);
            return List.of();
        }
    }

    // ---- resume flow ----------------------------------------------------------

    /**
     * Resumes a persisted session (the app tree's {@code resumeSession} minus
     * MCP/remote/eval): a known agent session id resumes by id; an unknown
     * one resumes through the provider's no-id resume (Pi: the interactive
     * {@code pi --resume} picker, AC21 — the provider enforces that shape,
     * this service only passes an empty optional). Always launches from the
     * session's own stored working directory, never a substitute.
     *
     * <p>Completion contract: the future completes with the RUNNING session,
     * or — without launching anything — with the session carrying
     * {@link SessionStatus#MISSING_WORKING_DIRECTORY} when its working
     * directory is gone, or exceptionally (after persisting FAILED) when the
     * provider refuses the resume (AC11 naming the agent) or the tab cannot
     * open. It also completes exceptionally, persisting nothing, when the
     * session has a running agent or another launch in flight.</p>
     */
    public CompletableFuture<ManagedAgentSession> resumeSession(ManagedSessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        return initialization
                .thenApplyAsync(unused -> requireSession(sessionId), executor)
                .thenComposeAsync(this::resumePrepared, executor);
    }

    /**
     * Restores an idle row to the conversation the provider recorded, or
     * starts a fresh conversation only when there genuinely is no transcript.
     *
     * <p>The late bind must happen before this choice. IDE shutdown can stop
     * the process and drain the executor before the termination listener has
     * persisted a DISCOVERED provider's id. Deciding from the stale catalog
     * row in the UI therefore turned a resumable Codex/Pi row into a fresh
     * create. Keeping the choice here gives every caller the same restore
     * backstop as {@link #resumeSession}.</p>
     */
    public CompletableFuture<ManagedAgentSession> resumeOrStartSession(ManagedSessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        return initialization
                .thenApplyAsync(unused -> {
                    ends.seedClaimedIdsOnce();
                    ManagedAgentSession stored = requireSession(sessionId);
                    // A remote row ALWAYS resumes (AC-11): its conversation
                    // lives on the box, not in any local transcript store,
                    // and its working directory is a placeholder this Mac
                    // never had. The local-only probes below -- the late bind
                    // and the transcript check -- must not decide anything
                    // for it (INV-14), and a fresh create would strand the
                    // box-side conversation the row names.
                    if (stored.workspaceBox().isPresent()) {
                        return stored;
                    }
                    return stored.agentSessionId().isPresent()
                            ? stored
                            : Optional.ofNullable(ends.lateBindAgentSessionId(sessionId)).orElse(stored);
                }, executor)
                .thenComposeAsync(session -> {
                    if (session.workspaceBox().isPresent()) {
                        return resumePrepared(session);
                    }
                    return session.agentSessionId().isPresent() && !nothingRecordedUnder(session)
                            ? resumePrepared(session)
                            : launchFreshOnExistingRow(session);
                }, executor);
    }

    /**
     * Whether the provider confirms that no transcript exists for the
     * session's id. A provider that cannot tell answers false: an unknown is
     * treated as a conversation, never as permission to strand one. Blocking
     * (reads the transcript store): executor only.
     */
    private boolean nothingRecordedUnder(ManagedAgentSession session) {
        return session.agentSessionId()
                .flatMap(id -> registry.get().conversations(session.agentKind())
                        .map(source -> !source.transcriptExists(session.workingDirectory(), id)))
                .orElse(false);
    }

    /**
     * Starts a <em>fresh conversation</em> on an existing session row.
     *
     * <p>For a row with no agent session id there is nothing to resume into:
     * the agent never recorded a conversation (a session stopped before it
     * completed a turn is the common case), so resuming could only drop the
     * user into the provider's own picker. This runs the provider's
     * <em>create</em> command instead, keeping the row, its worktree, its
     * title and its mode, and binding whatever id the new conversation
     * produces.</p>
     *
     * <p>Deliberately not offered for a row that already has an id — that one
     * resumes, and starting fresh would strand a conversation the user can no
     * longer reach from drydock.</p>
     */
    public CompletableFuture<ManagedAgentSession> startSession(ManagedSessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        return initialization
                .thenApplyAsync(unused -> {
                    ends.seedClaimedIdsOnce();
                    ManagedAgentSession stored = requireSession(sessionId);
                    // A remote row never starts fresh: its conversation lives
                    // in the box's tmux session, and a fresh local conversation
                    // would strand it. It always resumes (AC-11's other side).
                    if (stored.workspaceBox().isPresent()) {
                        throw new IllegalStateException("Session '" + stored.displayName()
                                + "' runs on workspace box '" + stored.workspaceBox().orElseThrow().boxName()
                                + "'; it can only be resumed there, never started fresh here");
                    }
                    return prepareFreshOnExistingRow(stored);
                }, executor)
                // No task: it was only ever the create dialog's input, and is
                // not kept on the row.
                .thenComposeAsync(session -> launchPrepared(session, Optional.empty(), Optional.empty(),
                        Optional.empty(), true),
                        executor);
    }

    private CompletableFuture<ManagedAgentSession> launchFreshOnExistingRow(ManagedAgentSession session) {
        return launchPrepared(prepareFreshOnExistingRow(session), Optional.empty(), Optional.empty(),
                Optional.empty(), true);
    }

    private ManagedAgentSession prepareFreshOnExistingRow(ManagedAgentSession session) {
        if (session.agentSessionId().isPresent()) {
            if (!nothingRecordedUnder(session)) {
                throw new IllegalStateException(
                        "Session " + session.id() + " has a conversation to resume; use resumeSession");
            }
            // An id with no transcript behind it (Claude is given its id at
            // launch; stopped before its first turn, nothing was ever
            // written). There is nothing to strand, and the fresh
            // conversation gets its own id.
            session = session.toBuilder()
                    .binding(session.binding().withSessionId(Optional.empty()))
                    .build();
        }
        return session.withStatus(SessionStatus.STARTING);
    }

    /**
     * Adopts an on-disk conversation picked from a provider's
     * {@link ConversationSource} (AC20's record half): records a fresh
     * managed session bound to the conversation's agent session id — unless
     * one already tracks it, which is then reused (one row per
     * conversation, the app's {@code adoptConversation}) — and takes the
     * same resume-with-id path. The working directory is the repository's
     * root: the conversation was listed for that directory.
     */
    public CompletableFuture<ManagedAgentSession> resumeConversation(Repository repository, AgentKind kind,
            ConversationSource.Conversation conversation, String displayName) {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(conversation, "conversation");
        Objects.requireNonNull(displayName, "displayName");
        return initialization
                .thenApplyAsync(unused -> adoptConversation(repository, kind, conversation, displayName), executor)
                .thenComposeAsync(this::resumePrepared, executor);
    }

    private CompletableFuture<ManagedAgentSession> resumePrepared(ManagedAgentSession prepared) {
        // A persisted agent kind the plugin's providers do not know decodes
        // to this status with a placeholder kind; launching it would silently
        // run the wrong agent (the app's checkResumeBlocked guard).
        if (prepared.status() == SessionStatus.UNSUPPORTED_AGENT) {
            return CompletableFuture.completedFuture(prepared);
        }
        // A remote row attaches to its box-side tmux session; the local
        // resume below -- the working-directory probe, the late bind, the
        // MCP mint -- never runs for it.
        if (prepared.workspaceBox().isPresent()) {
            return resumeRemotePrepared(prepared);
        }
        // Claimed before the late bind, the persist and the mint: a refused
        // resume must not touch the row, the token or the credential file of
        // the agent that already holds the session.
        if (!claims.tryClaim(prepared.id())) {
            throw launchRefused(prepared.id());
        }
        CompletableFuture<ManagedAgentSession> resumed;
        try {
            resumed = resumeClaimed(prepared);
        } catch (RuntimeException | Error e) {
            endClaim(prepared.id());
            throw e;
        }
        // Same claim lifetime as in launchPrepared: ends when the open-future
        // completes; pending only if invokeLater is dropped at shutdown.
        return resumed.whenComplete((ignored, ex) -> endClaim(prepared.id()));
    }

    private CompletableFuture<ManagedAgentSession> resumeClaimed(ManagedAgentSession prepared) {
        // Backstop for the binding that normally happens at termination: an
        // IDE killed outright, or a crash, never ran that, and without this
        // the session would resume into the provider's picker forever. Only
        // ever adds an id, never replaces one.
        ManagedAgentSession session = prepared.agentSessionId().isPresent()
                ? prepared
                : Optional.ofNullable(ends.lateBindAgentSessionId(prepared.id())).orElse(prepared);
        // The working directory is probed on disk (possibly a network
        // volume): this runs on the executor, never the caller's thread.
        if (Files.notExists(session.workingDirectory())) {
            ManagedAgentSession missing = session.withStatus(SessionStatus.MISSING_WORKING_DIRECTORY);
            persistUpdatedSession(missing);
            return CompletableFuture.completedFuture(missing);
        }
        AgentProvider provider = registry.get().provider(session.agentKind())
                .orElseThrow(() -> new IllegalStateException("No " + session.agentKind() + " ("
                        + session.agentKind().persistedName() + ") provider is registered; cannot resume session"));
        LaunchPlan plan;
        try {
            // No remote and never eval; Drydock MCP access is minted for this
            // launch when available. The provider's own no-id branch is what a
            // session without an id gets (AC21).
            Optional<McpAccess> access = mcpAccessFor(session.id(), provider);
            // T29a: resolved before the context, on this executor stage
            // (never the EDT); a resumed task session re-receives exactly
            // what its retained receipts and the MCP surface resolve now.
            Optional<LaunchResources> resources = launchResourcesFor(session.id(), access);
            ResumeContext ctx = new ResumeContext(session.agentSessionId(), session.agentSessionName(),
                    session.workingDirectory(), Optional.empty(), access, false, session.readOnly(),
                    List.of(),
                    session.startedWithTask() ? Optional.of(RESUME_PROMPT) : Optional.empty())
                    .withStdioMcpServers(stdioMcpServersFor(session.workingDirectory()));
            if (resources.isPresent()) {
                ctx = ctx.withLaunchResources(resources.get());
            }
            plan = provider.buildResumeCommand(ctx);
            releaseIfUnused(session.id(), access, plan);
        } catch (RuntimeException failure) {
            persistFailedBestEffort(session);
            throw failure;
        }
        if (!plan.supported()) {
            // AC11: name the agent, open nothing.
            persistFailedBestEffort(session);
            throw new IllegalStateException(
                    kindName(provider.kind()) + " cannot resume this session (unsupported context)");
        }
        CompletableFuture<Void> open;
        try {
            open = consoles.startConsole(session.id(), session.workingDirectory(), plan);
        } catch (RuntimeException failure) {
            // A synchronous throw never reaches finalizeResume; the claim's
            // end, when resumePrepared rethrows, releases the access minted above.
            persistFailedBestEffort(session);
            throw failure;
        }
        return open
                .handleAsync((ignored, ex) -> finalizeResume(session, ex), executor);
    }

    /**
     * The remote resume's claim wrapper: the flag-off refusal (INV-31) happens
     * before the claim -- it is a cheap local answer that must never open a
     * box or fall through to a local launch -- and everything else mirrors
     * {@link #resumePrepared}'s claim lifetime.
     */
    private CompletableFuture<ManagedAgentSession> resumeRemotePrepared(ManagedAgentSession prepared) {
        if (!workspaceSessionsEnabled.getAsBoolean()) {
            throw new IllegalStateException(
                    "Workspace sessions are turned off in Settings › Tools › Drydock");
        }
        if (!claims.tryClaim(prepared.id())) {
            throw launchRefused(prepared.id());
        }
        CompletableFuture<ManagedAgentSession> resumed;
        try {
            resumed = resumeRemoteClaimed(prepared);
        } catch (RuntimeException | Error e) {
            endClaim(prepared.id());
            throw e;
        }
        return resumed.whenComplete((ignored, ex) -> endClaim(prepared.id()));
    }

    /**
     * The workspace-box resume: attach to the row's box-side tmux session.
     *
     * <p>None of the local-only probes that guard a local resume runs here
     * (INV-14): no {@code Files.notExists} on the placeholder working
     * directory, no transcript look-up, no late bind, no MCP mint (INV-15),
     * no stdio MCP servers, no launch-resources resolution. One batch ssh
     * probe proves the box reachable first; a failed probe classifies the
     * box through {@code workspaces list} and refuses <em>without persisting
     * FAILED</em> (INV-24: the row's binding never changes, so the refusal
     * names the stopped or gone box and records the liveness verdict):
     * status 8 is stopped, an unlisted name is gone, and anything else --
     * a listed running status, a failed or missing or unparseable listing --
     * is a transient "try again" with UNKNOWN liveness. On success the
     * provider's persistent resume plan opens the console in a local
     * directory (as on create, never the placeholder), and {@code
     * finalizeResume} persists RUNNING and {@code lastOpenedAt} only: RUNNING
     * is legal because this IDE owns the live ssh execution (INV-4), and an
     * attach re-runs no create side effect (INV-23).</p>
     */
    private CompletableFuture<ManagedAgentSession> resumeRemoteClaimed(ManagedAgentSession session) {
        // The row was read before this claim was taken (the resume's first
        // executor stage); a Forget that finished in between removed it, and
        // attaching here would resurrect the forgotten row's box session
        // under a row the catalog no longer has — the same deletion gap the
        // create path's rowMustExist guard closes.
        if (findIn(stateStore.get().state(), session.id()) == null) {
            throw new IllegalStateException("Session " + session.id() + " was forgotten before it resumed");
        }
        WorkspaceBoxBinding box = session.workspaceBox().orElseThrow();
        BoxShell.BoxResult probe;
        try {
            probe = boxShell.probe(box.sshRemote().host());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException(
                    new IllegalStateException("The resume of session " + session.id() + " was cancelled", e));
        }
        if (!(probe instanceof BoxShell.BoxResult.Ok)) {
            throw remoteBoxRefusal(session, box);
        }
        AgentProvider provider = registry.get().provider(session.agentKind())
                .orElseThrow(() -> new IllegalStateException("No " + session.agentKind() + " ("
                        + session.agentKind().persistedName() + ") provider is registered; cannot resume session"));
        LaunchPlan plan;
        try {
            ResumeContext ctx = new ResumeContext(session.agentSessionId(), session.agentSessionName(),
                    session.workingDirectory(), Optional.of(box.sshRemote()), Optional.empty(), false,
                    session.readOnly(), List.of(),
                    session.startedWithTask() ? Optional.of(RESUME_PROMPT) : Optional.empty())
                    .withPersistence(new RemotePersistence(session.id(), BOX_AGENT_ENV));
            plan = provider.buildResumeCommand(ctx);
        } catch (RuntimeException failure) {
            persistFailedBestEffort(session);
            throw failure;
        }
        if (!plan.supported()) {
            // AC11: name the agent, open nothing.
            persistFailedBestEffort(session);
            throw new IllegalStateException(
                    kindName(provider.kind()) + " cannot resume this session (unsupported context)");
        }
        CompletableFuture<Void> open;
        try {
            // A new client is about to exist: any requested-client-end mark
            // still pending for the id belongs to an earlier client's end
            // and must not steer this attach's.
            clientEnds.clear(session.id());
            open = consoles.startConsole(session.id(), consoleCwdFor(session), plan);
        } catch (RuntimeException failure) {
            persistFailedBestEffort(session);
            throw failure;
        }
        return open
                .handleAsync((ignored, ex) -> finalizeResume(session, ex), executor);
    }

    /**
     * The refusal for a probe that could not reach the box, classified by
     * {@code workspaces list}: stopped (status 8), gone (unlisted), or
     * anything else -- a listed non-8 status, a failed, timed-out,
     * too-large or unparseable listing, a missing CLI -- which is always a
     * transient "try again", never a re-create (INV-24). The liveness
     * verdict is recorded for the surfaces; no row is persisted.
     */
    private IllegalStateException remoteBoxRefusal(ManagedAgentSession session, WorkspaceBoxBinding box) {
        String boxName = box.boxName();
        RemoteSessionLiveness.State liveness;
        String reason;
        WorkspacesCli.BoxStatus status = boxStatusOf(boxName);
        if (status == WorkspacesCli.BoxStatus.STOPPED) {
            liveness = RemoteSessionLiveness.State.BOX_STOPPED;
            reason = "Box " + boxName + " is stopped; its conversations are unreachable";
        } else if (status == WorkspacesCli.BoxStatus.ABSENT) {
            liveness = RemoteSessionLiveness.State.BOX_GONE;
            reason = "Box " + boxName + " no longer exists";
        } else {
            liveness = RemoteSessionLiveness.State.UNKNOWN;
            reason = "Box " + boxName + " is unreachable; try again";
        }
        remoteLiveness.set(session.id(), liveness);
        return new IllegalStateException(reason);
    }

    /**
     * The box's classified status, or {@code OTHER} whenever the listing
     * itself could not be trusted: a failure mode of {@code workspaces
     * list} is "no classification", which the caller's catch-all treats as
     * transient (INV-13).
     */
    private WorkspacesCli.BoxStatus boxStatusOf(String boxName) {
        WorkspacesCli.ListResult listing;
        try {
            listing = workspacesCli.list();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return WorkspacesCli.BoxStatus.OTHER;
        }
        return listing instanceof WorkspacesCli.Listed table
                ? table.statusOf(boxName)
                : WorkspacesCli.BoxStatus.OTHER;
    }

    private ManagedAgentSession finalizeResume(ManagedAgentSession session, Throwable failure) {
        if (failure != null) {
            Throwable cause = unwrap(failure);
            LOG.log(Level.WARNING, () -> "Failed to resume session " + session.id() + ": " + cause);
            persistFailedBestEffort(session);
            throw wrap(cause);
        }
        ManagedAgentSession running = session.withStatus(SessionStatus.RUNNING).withLastOpenedAt(Instant.now());
        persistUpdatedSession(running);
        return running;
    }

    /**
     * Records (or finds) the managed row for an on-disk conversation. The
     * conversation id is claimed immediately so a later DISCOVERED create
     * cannot bind the same id to a different session.
     */
    private ManagedAgentSession adoptConversation(Repository repository, AgentKind kind,
            ConversationSource.Conversation conversation, String displayName) {
        // Persisting before resumePrepared's tryClaim is safe: an existing row
        // is returned unchanged, and a new row has a freshly minted id, so it
        // cannot yet have a live execution or a claim.
        String agentSessionId = conversation.sessionId();
        ManagedAgentSession[] result = new ManagedAgentSession[1];
        stateStore.get().update(state -> {
            for (ManagedAgentSession existing : state.sessions()) {
                if (existing.agentSessionId().map(agentSessionId::equals).orElse(false)) {
                    result[0] = existing;
                    return state;
                }
            }
            // A conversation adopted from disk was written by a writing
            // agent: nothing in a transcript records a read-only launch, and
            // assuming read-only would mislabel every adopted row.
            ManagedAgentSession adopted =
                    newSessionMetadata(repository.id(), repository.root(), kind, displayName, SessionMode.WRITES)
                            .withAgentSessionId(Optional.of(agentSessionId));
            result[0] = adopted;
            List<ManagedAgentSession> updated = new ArrayList<>(state.sessions());
            updated.add(adopted);
            return state.withSessions(updated);
        });
        ends.claimedAgentSessionIds().add(agentSessionId);
        return result[0];
    }

    // ---- lifecycle events (the persistence half of AgentExecutionListener) ---

    /**
     * The session's process ended; the row goes idle and <strong>stays</strong>.
     *
     * <p>Which idle status depends on who ended it. A natural exit is
     * {@code EXITED}; a stop drydock asked for — the Stop button, the window
     * closing, the IDE quitting — is {@code INACTIVE}. Both are "Not running"
     * to the user; the distinction exists so the strip's stopped line can say
     * which happened, and so a crash is not reported as a deliberate stop.</p>
     *
     * <p>{@code lastOpenedAt} is bumped either way, so the catalog keeps
     * sorting by when a session was last live. {@code lastExitCode} is
     * recorded only when the platform reported one.</p>
     *
     * <p>A DISCOVERED session that still has no agent session id gets one
     * bound here, off this thread — see {@link SessionEnds}. Termination
     * is the best moment there is: the create-time poll has to give up while
     * the agent may not have written its record yet, but a process that has
     * <em>ended</em> certainly has.</p>
     *
     * <p>Called on whichever thread delivered the process event. The single
     * writer serializes the transform and backgrounds the save, so no hop is
     * needed; a session already forgotten is a no-op rather than a
     * resurrection.</p>
     */
    @Override
    public void executionTerminated(ManagedSessionId id, boolean stopRequested, Integer exitCode) {
        ends.executionTerminated(id, stopRequested, exitCode);
    }

    /**
     * What the registry keeps to report this window's agents' ends: the
     * persistence half without the window. An agent can outlive the window
     * that started it (closed with "Keep running"), and holding this service
     * would keep that window's project alive as long as the agent runs.
     */
    public AgentExecutionListener endRecorder() {
        return ends;
    }

    // ---- delete ---------------------------------------------------------------

    /**
     * Forgets a session: its execution is stopped and discarded, the activity
     * watcher drops its state, and the row leaves the catalog. This is the
     * <em>only</em> path that removes a row — a stopped process, a closed tab,
     * a closed window and an IDE restart all leave it. The future fails while
     * a launch of the session is in progress.
     *
     * <p>For a remote row this is the explicit Forget (D1): the box-side agent
     * is killed <em>first</em> — one of the two paths that may issue
     * kill-session (INV-10) — and the row is removed once the kill confirms
     * the agent gone (exit 0, or tmux's "can't find session"). When the kill
     * cannot conclude — the box unreachable, stopped or gone, a refused or
     * hung kill — the row is removed only after the user confirms "The agent
     * may still be running on box &lt;name&gt;. Forget anyway?"; a declined
     * confirm removes nothing and completes normally. The detached liveness
     * entry leaves with the row.</p>
     *
     * <p>The agent's own conversation history on disk is never touched. The
     * row is drydock's bookkeeping; the transcript is the user's record of
     * what the agent did, and deleting that is not something a session row's
     * removal should imply.</p>
     */
    public CompletableFuture<Void> deleteSession(ManagedSessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        return initialization
                .thenApplyAsync(unused -> requireSession(sessionId), executor)
                .thenComposeAsync(session -> {
                    // A launch in flight may not have registered its
                    // execution yet, so forgetConsole could not stop it.
                    // Refuse instead; the claim also keeps a launch from
                    // starting while the row is removed.
                    if (!claims.tryClaimForRemoval(sessionId)) {
                        throw new IllegalStateException(
                                "a launch of this task is in progress; try again when it ends");
                    }
                    CompletableFuture<Void> removed;
                    try {
                        // A remote row's agent is killed before anything is
                        // removed; an unconclusive kill asks first (D1).
                        if (session.workspaceBox().isPresent() && !confirmForgetRemoteAgent(session)) {
                            endClaim(sessionId);
                            return CompletableFuture.completedFuture(null);
                        }
                        consoles.forgetConsole(sessionId);
                        CompletableFuture<Void> forgotten = session.agentSessionId()
                                .map(id -> watcher.get().forget(id))
                                .orElseGet(() -> CompletableFuture.completedFuture(null));
                        // watcher.forget runs on the watcher's own executor; hop
                        // back here before mutating the state.
                        removed = forgotten.thenRunAsync(() -> {
                            stateStore.get().update(state ->
                                    state.withSessions(state.sessions().stream()
                                            .filter(existing -> !existing.id().equals(sessionId))
                                            .toList()));
                            // A remote row's liveness entry must not outlive
                            // its row; remove() publishes only when one was
                            // actually there.
                            remoteLiveness.remove(sessionId);
                            DrydockSessionsListener.publishChanged();
                        }, executor);
                    } catch (RuntimeException | Error e) {
                        endClaim(sessionId);
                        throw e;
                    }
                    // forgetConsole dropped the execution, so ending the
                    // claim releases the access.
                    return removed.whenComplete((ignored, ex) -> endClaim(sessionId));
                }, executor);
    }

    /**
     * A remote row's Forget kills its box-side agent and answers whether the
     * row may be removed. A kill that confirms the agent gone — exit 0, or
     * tmux's "can't find session" — answers yes outright; anything else (the
     * box unreachable, stopped or gone, a refused or hung kill) asks the
     * user "The agent may still be running on box &lt;name&gt;. Forget anyway?"
     * and answers what the user answered. Blocking — it runs on the plugin
     * executor (the ssh round trip and the wait for the dialog), never the
     * EDT.
     */
    private boolean confirmForgetRemoteAgent(ManagedAgentSession session) {
        WorkspaceBoxBinding box = session.workspaceBox().orElseThrow();
        BoxShell.BoxResult kill;
        try {
            kill = boxShell.killSession(box.sshRemote().host(), session.id());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException(new IllegalStateException(
                    "Forgetting session '" + session.displayName() + "' on box " + box.boxName()
                            + " was cancelled", e));
        }
        if (killSettled(kill)) {
            return true;
        }
        LOG.log(Level.WARNING, () -> "kill-session for " + session.id() + " on box " + box.boxName()
                + " did not conclude (" + kill.describe() + "); asking before forgetting");
        String question = "The agent may still be running on box " + box.boxName() + ". Forget anyway?";
        try {
            return remoteConfirm.apply(question).get(CONFIRM_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException(new IllegalStateException(
                    "Forgetting session '" + session.displayName() + "' on box " + box.boxName()
                            + " was cancelled", e));
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Could not ask whether to forget session '"
                    + session.displayName() + "' on box " + box.boxName() + ": " + e, e);
        }
    }

    /**
     * The human's explicit rename. Validation, sibling collision detection,
     * pinning and replacement happen as one state transform on the plugin's
     * background executor; the EDT only owns the prompt and progress UI.
     */
    public CompletableFuture<ManagedAgentSession> renameSession(ManagedSessionId sessionId, String requestedName) {
        return renameSession(sessionId, requestedName, true);
    }

    /**
     * The human's rename with the pin decided by the caller: {@code pin} is
     * true for an explicit confirm (Enter in the inline editor, OK in the
     * Rename dialog) and false for a focus-loss commit, which an agent can
     * provoke by opening a tab and stealing focus. A false pin never CLEARS
     * an earlier pin -- {@code withDisplayName} preserves the flag -- so a
     * blur over a human-named session leaves the claim intact.
     */
    public CompletableFuture<ManagedAgentSession> renameSession(ManagedSessionId sessionId, String requestedName,
                                                                 boolean pin) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(requestedName, "requestedName");
        return initialization.thenApplyAsync(unused -> {
            String title;
            try {
                title = PromptSafety.checkSessionTitle(requestedName);
            } catch (McpToolException e) {
                throw new IllegalArgumentException(e.getMessage(), e);
            }
            ManagedAgentSession[] renamed = new ManagedAgentSession[1];
            stateStore.get().update(state -> {
                ManagedAgentSession session = state.sessions().stream()
                        .filter(existing -> existing.id().equals(sessionId))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("The session no longer exists"));
                String comparable = PromptSafety.foldForComparison(title);
                Optional<ManagedAgentSession> clash = state.sessions().stream()
                        .filter(other -> !other.id().equals(sessionId))
                        .filter(other -> other.repositoryId().equals(session.repositoryId()))
                        .filter(other -> PromptSafety.foldForComparison(other.displayName())
                                .equalsIgnoreCase(comparable))
                        .findFirst();
                if (clash.isPresent()) {
                    throw new IllegalArgumentException("Another session in this project is already named \""
                            + clash.get().displayName() + "\"");
                }
                renamed[0] = pin
                        ? session.withDisplayName(title).withNamePinned(true)
                        : session.withDisplayName(title);
                return state.withSessions(state.sessions().stream()
                        .map(existing -> existing.id().equals(sessionId) ? renamed[0] : existing)
                        .toList());
            });
            DrydockSessionsListener.publishChanged();
            return renamed[0];
        }, executor);
    }

    /**
     * The human's follow-up flag: sets or clears it as one state transform on
     * the plugin's background executor, then republishes so the task tree
     * redraws. Completes exceptionally when the session no longer exists.
     */
    public CompletableFuture<ManagedAgentSession> setFlagged(ManagedSessionId sessionId, boolean flagged) {
        Objects.requireNonNull(sessionId, "sessionId");
        return initialization.thenApplyAsync(unused -> {
            ManagedAgentSession[] updated = new ManagedAgentSession[1];
            stateStore.get().update(state -> {
                updated[0] = state.sessions().stream()
                        .filter(existing -> existing.id().equals(sessionId))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("The session no longer exists"))
                        .withFlagged(flagged);
                return state.withSessions(state.sessions().stream()
                        .map(existing -> existing.id().equals(sessionId) ? updated[0] : existing)
                        .toList());
            });
            DrydockSessionsListener.publishChanged();
            return updated[0];
        }, executor);
    }

    /**
     * The agent's {@code session_rename}: refuses a human-pinned name,
     * refuses an unchanged title and a sibling collision as outcomes (the
     * router turns each into its own message), applies anything else, and
     * republishes so the task tree and console titles relabel. One state
     * transform, like the human rename: the lookup and the comparison happen
     * under the single writer's lock, so a concurrent human edit of the same
     * name cannot be silently overwritten.
     */
    public CompletableFuture<RenameOutcome> applyAgentRename(ManagedSessionId sessionId, String title) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(title, "title");
        return initialization.thenApplyAsync(unused -> {
            RenameOutcome[] result = new RenameOutcome[1];
            stateStore.get().update(state -> {
                ManagedAgentSession session = state.sessions().stream()
                        .filter(existing -> existing.id().equals(sessionId))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("The session no longer exists"));
                if (session.namePinned()) {
                    result[0] = new RenameOutcome(RenameKind.PINNED, session.displayName());
                    return state;
                }
                if (session.displayName().equals(title)) {
                    result[0] = new RenameOutcome(RenameKind.UNCHANGED, session.displayName());
                    return state;
                }
                Optional<ManagedAgentSession> clash = state.sessions().stream()
                        .filter(other -> !other.id().equals(sessionId))
                        .filter(other -> other.repositoryId().equals(session.repositoryId()))
                        // Both sides folded: a stored name came through the
                        // human path, which applies no checkSessionTitle, so
                        // it can carry a non-breaking space that renders
                        // identically.
                        .filter(other -> PromptSafety.foldForComparison(other.displayName()).equalsIgnoreCase(title))
                        .findFirst();
                if (clash.isPresent()) {
                    result[0] = new RenameOutcome(RenameKind.COLLIDED, clash.get().displayName());
                    return state;
                }
                ManagedAgentSession renamed = session.withDisplayName(title);
                result[0] = new RenameOutcome(RenameKind.RENAMED, title);
                return state.withSessions(state.sessions().stream()
                        .map(existing -> existing.id().equals(sessionId) ? renamed : existing)
                        .toList());
            });
            // Only a real change is worth a republish; a refused attempt must
            // not buy the agent a task-tree rebuild.
            if (result[0].kind() == RenameKind.RENAMED) {
                DrydockSessionsListener.publishChanged();
            }
            return result[0];
        }, executor);
    }

    /**
     * Whether a writing session is already live in {@code workingDirectory} —
     * the one-writer-per-worktree rule the new-session dialog warns about.
     *
     * <p>"Live" means the running band, not merely recorded: a stopped writer
     * cannot overwrite anything, and warning about one would train the user to
     * dismiss the warning that matters. Reads the in-memory catalog snapshot,
     * so it is safe on the EDT, but it needs {@link #initialization} to have
     * completed — callers resolve it on a background thread with the rest of
     * the dialog's inputs.</p>
     */
    public boolean hasActiveWriterIn(Path workingDirectory) {
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Path normalized = workingDirectory.toAbsolutePath().normalize();
        for (ManagedAgentSession session : stateStore.get().state().sessions()) {
            if (session.mode() == SessionMode.WRITES
                    && SessionStatusFacet.of(session.status()) == SessionStatusFacet.RUNNING
                    && session.workingDirectory().equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Stops a session's agent without removing its row. A local row ends its
     * process here; a remote row ends its <em>box-side</em> agent — this is
     * the explicit Stop, one of the two paths that may issue {@code tmux
     * kill-session} (D1, INV-10).
     *
     * <p>The remote path is async on the plugin executor (INV-8): the busy
     * state shows immediately — the row goes STOPPING, legal exactly while
     * this IDE owns its live ssh execution (INV-4); a detached row (no
     * execution here) keeps its status and relies on the caller's click
     * surface — then the Stop is identified as such before the kill runs
     * (so the ssh client's end, whenever it comes, settles by the kill's
     * verdict and never probes against it), then the kill runs with a
     * 10 s timeout through the box seam, then the ssh client ends. Exit 0
     * or tmux's "can't find session" settles the row INACTIVE with
     * liveness DEAD; any other outcome surfaces an error naming the box and
     * settles the row IDLE with liveness UNKNOWN — never INACTIVE, so a
     * failed Stop is never reported as a stopped agent. The returned future
     * completes on every path, so the caller's progress clears on success,
     * failure, timeout and cancel alike (AC-9).</p>
     */
    public CompletableFuture<Void> stopSession(ManagedSessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        ManagedAgentSession row = findIn(stateStore.get().state(), sessionId);
        if (row != null && row.workspaceBox().isPresent()) {
            return stopRemoteSession(row);
        }
        consoles.stopConsole(sessionId);
        return CompletableFuture.completedFuture(null);
    }

    /**
     * The remote Stop, split across two executor tasks so the busy state is
     * up before the blocking kill begins: the first task marks and schedules
     * the second, which kills, ends the ssh client, and settles by the kill's
     * outcome. Every completion path of the returned future clears the busy
     * mark (INV-8): a cancellation between the stages keeps the queued kill
     * from ever running, a rejected kill submission (shutdown raced it) and
     * a failed mark stage complete the future without the kill stage — and
     * in each of those no client was stopped, so no termination callback
     * will ever repair the mark; this chain is the only thing that can clear
     * it. When the kill stage did run, its own paths settle the row, and a
     * settle already landed leaves nothing STOPPING for the cleanup to undo.
     */
    private CompletableFuture<Void> stopRemoteSession(ManagedAgentSession row) {
        CompletableFuture<Void> stopped = new CompletableFuture<>();
        AtomicBoolean killBegan = new AtomicBoolean();
        stopped.whenComplete((ignored, failure) -> {
            if (!killBegan.get()) {
                clearRemoteStopping(row.id());
            }
        });
        CompletableFuture<Void> marked = CompletableFuture.runAsync(() -> {
            markRemoteStopping(row.id());
            try {
                executor.execute(() -> {
                    if (stopped.isCancelled()) {
                        return;   // a cancel arrived before the kill could begin
                    }
                    killBegan.set(true);
                    try {
                        stopMarkedRemoteSession(row);
                        stopped.complete(null);
                    } catch (RuntimeException | Error failure) {
                        stopped.completeExceptionally(failure);
                    }
                });
            } catch (RejectedExecutionException submissionRefused) {
                // The executor refused the kill task (shutdown raced its
                // submission); the chain ends without the kill stage of its
                // own, so the every-path cleanup above runs instead.
                stopped.completeExceptionally(submissionRefused);
            }
        }, executor);
        marked.whenComplete((ignored, markFailure) -> {
            if (markFailure != null) {
                stopped.completeExceptionally(markFailure);
            }
        });
        return stopped;
    }

    /**
     * The kill, the client end, and the settle the kill dictates (D1's
     * table): exit 0 or "can't find session" → INACTIVE with liveness DEAD;
     * anything else → an error naming the box and an IDLE row with UNKNOWN
     * liveness — the row is never reported stopped by a kill that did not
     * conclude.
     *
     * <p>The Stop is identified <em>before</em> the kill, through the
     * requested-client-end coordination: the ssh client can terminate
     * during the kill round-trip — before {@code stopConsole} could mark
     * the execution's own stop-requested flag — and its end must not run
     * the ordinary client-end probe against the Stop's authoritative
     * outcome. The mark also carries the kill's verdict for the end that
     * arrives after this method's settle was suppressed by the still-dying
     * client (the claim guard sees a live execution until the process
     * actually terminates): that end settles the verdict itself.</p>
     */
    private void stopMarkedRemoteSession(ManagedAgentSession row) {
        WorkspaceBoxBinding box = row.workspaceBox().orElseThrow();
        boolean executionLive = clientEnds.hasLiveExecution(row.id());
        clientEnds.beginExplicitStop(row.id());
        BoxShell.BoxResult kill;
        try {
            kill = boxShell.killSession(box.sshRemote().host(), row.id());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            clearRemoteStopping(row.id());
            // The Stop is over without a verdict; the client was never asked
            // to end, so its later end — if any — is an ordinary one and must
            // not settle as this Stop's.
            clientEnds.endExplicitStop(row.id());
            throw new CompletionException(new IllegalStateException(
                    "Stopping session '" + row.displayName() + "' on box " + box.boxName()
                            + " was cancelled", e));
        }
        boolean settled = killSettled(kill);
        clientEnds.stopVerdict(row.id(), settled);
        // Steps (2) and (4) in one call: stopConsole marks the execution
        // stop-requested and ends this IDE's ssh client to the box.
        consoles.stopConsole(row.id());
        try {
            if (settled) {
                settleRemoteRow(row.id(), SessionStatus.INACTIVE, RemoteSessionLiveness.State.DEAD);
            } else {
                LOG.log(Level.WARNING, () -> "kill-session for " + row.id() + " on box " + box.boxName()
                        + " did not conclude (" + kill.describe() + ")");
                settleRemoteRow(row.id(), SessionStatus.IDLE, RemoteSessionLiveness.State.UNKNOWN);
                throw new CompletionException(new IllegalStateException(
                        "Could not stop the agent of session '" + row.displayName() + "' on box "
                                + box.boxName() + ": " + kill.describe()));
            }
        } finally {
            if (!executionLive) {
                // No ssh client of this IDE exists (a detached row): no end
                // will consume the mark — end it here, so it cannot steer a
                // later end. When one was live, the dying client's end is
                // imminent and settles by the verdict recorded above if this
                // method's own settle could not land.
                clientEnds.endExplicitStop(row.id());
            }
        }
    }

    /**
     * tmux's own "the agent is gone" answers to a kill-session: exit 0, or
     * exit 1 carrying the "can't find session" marker. Deliberately not the
     * wider no-server forms {@code BoxShell} recognizes for probes — the
     * contract's Stop and Forget tables name exactly this one, and an
     * unrecognized form must fail safe (an error, a confirm), never silently
     * read as a kill that succeeded.
     */
    private static boolean killSettled(BoxShell.BoxResult kill) {
        if (kill instanceof BoxShell.BoxResult.Ok) {
            return true;
        }
        return kill instanceof BoxShell.BoxResult.RemoteFailed(int exitCode, String excerpt)
                && exitCode == 1 && excerpt.contains("can't find session");
    }

    /**
     * Shows the remote Stop's busy state: the row goes STOPPING — legal only
     * while this IDE owns its live ssh execution (INV-4), which is exactly
     * what the row's RUNNING status records — so a detached row (IDLE in
     * its row, ALIVE in the liveness map) is left alone; its Stop click's
     * immediate feedback is the caller's surface. Publishing wakes the
     * session surfaces, which render STOPPING as "Deactivating…".
     */
    private void markRemoteStopping(ManagedSessionId id) {
        boolean[] marked = {false};
        stateStore.get().update(state -> {
            ManagedAgentSession existing = findIn(state, id);
            if (existing == null || existing.workspaceBox().isEmpty()
                    || existing.status() != SessionStatus.RUNNING) {
                return state;
            }
            marked[0] = true;
            return withReplacedSession(state, existing.withStatus(SessionStatus.STOPPING));
        });
        if (marked[0]) {
            DrydockSessionsListener.publishChanged();
        }
    }

    /** Clears the STOPPING busy state back to RUNNING — a Stop cancelled before anything ended. */
    private void clearRemoteStopping(ManagedSessionId id) {
        boolean[] cleared = {false};
        stateStore.get().update(state -> {
            ManagedAgentSession existing = findIn(state, id);
            if (existing == null || existing.status() != SessionStatus.STOPPING) {
                return state;
            }
            cleared[0] = true;
            return withReplacedSession(state, existing.withStatus(SessionStatus.RUNNING));
        });
        if (cleared[0]) {
            DrydockSessionsListener.publishChanged();
        }
    }

    /**
     * The remote Stop's settle, with the guard every settle through the
     * single writer carries (INV-21): a no-op when the row is gone or when
     * {@link LaunchClaims} shows a live execution or claim for it — a Resume
     * that claimed while the kill was in flight owns the row now, and its
     * RUNNING is never overwritten. A row that already carries the settle's
     * status still settles: the liveness answer is recorded and the change
     * published (an inconclusive Stop on a detached IDLE row must record
     * UNKNOWN even though no status change is necessary), while the row-gone
     * and claim guards still apply. The liveness answer is recorded after
     * the transform, only when the settle landed.
     */
    private void settleRemoteRow(ManagedSessionId id, SessionStatus status,
                                  RemoteSessionLiveness.State liveness) {
        boolean[] settled = {false};
        stateStore.get().update(state -> {
            ManagedAgentSession existing = findIn(state, id);
            if (existing == null || existing.workspaceBox().isEmpty()
                    || !claims.releaseIfIdle(id, () -> { })) {
                return state;
            }
            settled[0] = true;
            if (existing.status() == status) {
                return state;
            }
            return withReplacedSession(state, existing.withStatus(status).withLastOpenedAt(Instant.now()));
        });
        if (!settled[0]) {
            return;
        }
        remoteLiveness.set(id, liveness);
        DrydockSessionsListener.publishChanged();
    }

    /**
     * The Done-with-task flow's gate (the strip's and the task tree's
     * "Done…"): that flow — stop the agent, inspect the worktree, complete
     * the tasks, remove the worktree, forget the row — is local-worktree
     * semantics throughout, and a remote row has none of it: no worktree on
     * this Mac, no local task store, no branch to remove. Such a row is
     * refused with the returned named reason; the endings a remote row
     * offers are the explicit {@link #stopSession Stop} and the explicit
     * {@link #deleteSession Forget}, the only two paths that may issue
     * kill-session (INV-10). Empty for a local row: the flow runs exactly as
     * before.
     *
     * <p>The gate is a service method because that is where the invariant
     * is enforceable: everything that could stop or remove a remote row
     * passes through this class, whose remote branches are exactly Stop and
     * Forget. The caller (on the EDT) shows the reason and returns without
     * opening the flow.</p>
     *
     * @return the named refusal reason for a row bound to a workspace box;
     *         empty when the Done flow may run
     */
    public Optional<String> doneFlowRefusal(ManagedSessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        ManagedAgentSession row = findIn(stateStore.get().state(), sessionId);
        if (row == null || row.workspaceBox().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("Session '" + row.displayName() + "' runs on workspace box '"
                + row.workspaceBox().orElseThrow().boxName() + "'; the Done flow is local-only — "
                + "use Stop or Forget instead");
    }

    /**
     * Window close and IDE quit: marks each local session idle
     * <strong>first</strong>, then stops its agent. A remote row is never
     * marked — its agent is box-side, and close paths must not touch it
     * (INV-10) — only its ssh client ends (INV-4: its detached liveness is
     * the probe settle's business, never a persisted close status).
     *
     * <p>The order is the point. Everywhere else the idle status comes from
     * {@link #executionTerminated}, once the process has ended — which can be
     * after the window is gone and reopened. A row still {@code RUNNING} then
     * tells the reopened window that its stopped agent "is running", and
     * {@code normalizeOnLoad}'s RUNNING→INACTIVE sweep does not help because it
     * runs once per <em>IDE</em> start, not per project open.</p>
     *
     * <p>Marking first means a crash between the mark and the kill leaves a
     * row claiming to be stopped while its process lingers. That is the
     * harmless direction: the next IDE start reaps it, and drydock asked for
     * the stop in the first place.</p>
     */
    public void stopSessionsForClose(List<ManagedSessionId> sessionIds) {
        Objects.requireNonNull(sessionIds, "sessionIds");
        if (sessionIds.isEmpty()) {
            return;
        }
        Set<ManagedSessionId> closing = Set.copyOf(sessionIds);
        // The remote rows among them, whose ssh clients end below: the end
        // settle must know the end is close-originated — a close settles the
        // durable row IDLE, never EXITED/INACTIVE (AC-7) — before the client
        // is asked to end. The local transform below already skipped them;
        // without this mark their ends would reach the recorder as ordinary
        // stops and persist INACTIVE (or EXITED) through the probe settle.
        Set<ManagedSessionId> remoteClosing = new HashSet<>();
        for (ManagedAgentSession session : stateStore.get().state().sessions()) {
            if (closing.contains(session.id()) && session.workspaceBox().isPresent()) {
                remoteClosing.add(session.id());
            }
        }
        stateStore.get().update(state -> state.withSessions(state.sessions().stream()
                .map(session -> closing.contains(session.id())
                        && session.workspaceBox().isEmpty()
                        && SessionStatusFacet.of(session.status()) == SessionStatusFacet.RUNNING
                        ? session.withStatus(SessionStatus.INACTIVE).withLastOpenedAt(Instant.now())
                        : session)
                .toList()));
        DrydockSessionsListener.publishChanged();
        for (ManagedSessionId sessionId : sessionIds) {
            boolean remote = remoteClosing.contains(sessionId);
            if (remote) {
                clientEnds.beginClose(sessionId);
            }
            consoles.stopConsole(sessionId);
            if (remote && !clientEnds.hasLiveExecution(sessionId)) {
                // The client was already gone, so its end was delivered
                // before the mark: nothing will consume it — end it here so
                // it cannot steer a later end.
                clientEnds.endClose(sessionId);
            }
        }
    }

    // ---- persistence helpers (all through the single writer, C4) -------------

    private ManagedAgentSession requireSession(ManagedSessionId sessionId) {
        ManagedAgentSession session = findIn(stateStore.get().state(), sessionId);
        if (session == null) {
            throw new IllegalStateException("Unknown session " + sessionId);
        }
        return session;
    }

    static ManagedAgentSession findIn(ApplicationState state, ManagedSessionId sessionId) {
        for (ManagedAgentSession session : state.sessions()) {
            if (session.id().equals(sessionId)) {
                return session;
            }
        }
        return null;
    }

    /** Inserts {@code session}, or replaces the row that already carries its id. */
    private void persistSession(ManagedAgentSession session) {
        stateStore.get().update(state -> {
            if (findIn(state, session.id()) != null) {
                return withReplacedSession(state, session);
            }
            List<ManagedAgentSession> updated = new ArrayList<>(state.sessions());
            updated.add(session);
            return state.withSessions(updated);
        });
        DrydockSessionsListener.publishChanged();
    }

    /** Replaces {@code session}'s row; false, changing nothing, when the row is gone. */
    private boolean replaceExistingSession(ManagedAgentSession session) {
        AtomicBoolean replaced = new AtomicBoolean();
        stateStore.get().update(state -> {
            if (findIn(state, session.id()) == null) {
                return state;
            }
            replaced.set(true);
            return withReplacedSession(state, session);
        });
        if (replaced.get()) {
            DrydockSessionsListener.publishChanged();
        }
        return replaced.get();
    }

    private void persistUpdatedSession(ManagedAgentSession session) {
        stateStore.get().update(state -> withReplacedSession(state, session));
        DrydockSessionsListener.publishChanged();
    }

    private void persistFailedBestEffort(ManagedAgentSession session) {
        try {
            persistUpdatedSession(session.withStatus(SessionStatus.FAILED));
        } catch (RuntimeException persistFailure) {
            // Never mask the original launch failure with a secondary
            // persistence failure; the FAILED status is best-effort.
            LOG.log(Level.WARNING, () -> "Could not persist FAILED status for session " + session.id()
                    + ": " + persistFailure);
        }
    }

    /** Pure transform: the state with {@code session}'s row replaced (no-op when its id is gone). */
    static ApplicationState withReplacedSession(ApplicationState state, ManagedAgentSession session) {
        return state.withSessions(state.sessions().stream()
                .map(existing -> existing.id().equals(session.id()) ? session : existing)
                .toList());
    }

    /**
     * Pure transform: {@code state} with {@code repositoryId}'s settings recording {@code kind} as its
     * last-used agent, {@code model} as that agent's last starting model and, when present,
     * {@code tiers} as its last subagent model tiers (empty leaves any stored tiers untouched), in ONE
     * pass over the repositories (a no-op when no repository matches). Ported from {@code SessionManager}.
     */
    static ApplicationState repoWithLastUsedLaunch(ApplicationState state, RepositoryId repositoryId,
            AgentKind kind, Optional<String> model, Optional<ModelTiers> tiers) {
        return state.withRepositories(state.repositories().stream()
                .map(r -> r.id().equals(repositoryId)
                        ? r.withSettings(launchRecorded(r.settings(), kind, model, tiers))
                        : r)
                .toList());
    }

    private static RepositorySettings launchRecorded(RepositorySettings settings, AgentKind kind,
            Optional<String> model, Optional<ModelTiers> tiers) {
        RepositorySettings recorded = settings.withLastUsedAgent(kind).withLastUsedModel(kind, model);
        return tiers.map(t -> recorded.withLastUsedTiers(kind, t)).orElse(recorded);
    }

    private static ManagedAgentSession newSessionMetadata(RepositoryId repositoryId, Path workingDirectory,
            AgentKind kind, String displayName, SessionMode mode) {
        return newSessionMetadata(repositoryId, workingDirectory, kind, displayName, mode, Optional.empty());
    }

    private static ManagedAgentSession newSessionMetadata(RepositoryId repositoryId, Path workingDirectory,
            AgentKind kind, String displayName, SessionMode mode, Optional<WorkspaceBoxBinding> workspaceBox) {
        return new ManagedAgentSession.Builder(
                ManagedSessionId.newId(), repositoryId, displayName,
                AgentBinding.unlaunched(kind, mode),
                SessionWorkspace.inRepository(workingDirectory),
                Instant.now())
                .status(SessionStatus.STARTING)
                .workspaceBox(workspaceBox)
                .build();
    }

    /**
     * The production remote confirm: the question becomes a Yes/No dialog
     * on the EDT ({@code ModalityState.anyModal()}, because the ask comes
     * from a background flow that may itself sit under a modal), and the
     * future completes with the answer from there. Never called by tests —
     * they inject a fake through the full seam.
     */
    private static CompletableFuture<Boolean> askOnEdt(String question) {
        CompletableFuture<Boolean> answer = new CompletableFuture<>();
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                answer.complete(Messages.showYesNoDialog(question, "Drydock",
                        Messages.getQuestionIcon()) == Messages.YES);
            } catch (RuntimeException | Error e) {
                answer.completeExceptionally(e);
            }
        }, ModalityState.any());
        return answer;
    }

    private static String kindName(AgentKind kind) {
        return kind + " (" + kind.persistedName() + ")";
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException layered && layered.getCause() != null
                ? unwrap(layered.getCause())
                : failure;
    }

    private static RuntimeException wrap(Throwable cause) {
        return cause instanceof RuntimeException runtime ? runtime : new CompletionException(cause);
    }

    // ---- value types -----------------------------------------------------------

    /**
     * Everything a launch captured before the tab opened, consumed by the
     * post-open stage: the session, the discovery wiring, and the open
     * future (kept in one place so the detached discovery cannot observe a
     * half-built launch).
     */
    private record StartedLaunch(
            ManagedAgentSession prepared,
            Optional<SessionIdDiscovery> discovery,
            Object snapshot,
            Instant launchedAt,
            String generatedId,
            boolean sessionIdUsed,
            Optional<String> model,
            Optional<ModelTiers> tiers,
            CompletableFuture<Void> openFuture) {
    }
}
