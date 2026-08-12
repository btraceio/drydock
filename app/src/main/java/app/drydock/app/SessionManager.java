package app.drydock.app;

import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.agent.api.CreateContext;
import app.drydock.agent.api.LaunchPlan;
import app.drydock.agent.api.McpAccess;
import app.drydock.agent.api.McpDelivery;
import app.drydock.agent.api.ResumeContext;
import app.drydock.agent.api.SessionIdDiscovery;
import app.drydock.agent.api.SessionIdStrategy;
import app.drydock.agent.spi.AgentProvider;
import app.drydock.domain.ApplicationState;
import app.drydock.domain.BranchOwnership;
import app.drydock.domain.HandoffBrief;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PrState;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SshRemote;
import app.drydock.mcp.McpConfigWriter;
import app.drydock.mcp.McpSessionContext;
import app.drydock.mcp.McpSessionContext.RenameKind;
import app.drydock.mcp.McpSessionContext.RenameOutcome;
import app.drydock.mcp.McpSessionRegistry;
import app.drydock.mcp.McpSessionRegistry.Spawn;
import app.drydock.mcp.PromptSafety;
import app.drydock.state.ApplicationStateRepository;
import app.drydock.terminal.api.TerminalHostView;
import app.drydock.terminal.api.TerminalRuntime;
import app.drydock.terminal.api.TerminalSpec;
import app.drydock.terminal.api.TerminalSurface;
import javafx.application.Platform;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/**
 * Orchestrates creating and resuming {@link ManagedAgentSession}s (plan
 * section 11): generates/persists session metadata via {@link
 * ApplicationStateRepository}, launches the real {@code claude} CLI inside a
 * {@link TerminalSurface}, enforces duplicate-open protection (plan section
 * 11.3), and closes sessions using {@link
 * TerminalSurface#closeGracefully(long, long, Runnable)}.
 *
 * <p><b>Threading (plan section 18):</b> {@link #launchSession} and {@link
 * #resumeSession} do their slow work -- {@link AgentProvider} capability
 * probing and persistence I/O -- on a background executor, and only touch
 * {@link TerminalSurface}/{@link TerminalRuntime}/{@link TerminalHostView} via
 * {@link Platform#runLater}, per {@link TerminalHostView}'s own documented
 * "JavaFX Application Thread only" constraint. Callers get back a {@link
 * CompletableFuture}; if the caller needs to touch UI with the result, it is
 * the caller's responsibility to hop back onto the FX thread (this class
 * does not assume the completion thread is the FX thread for anything
 * except the {@link TerminalSurface} calls it makes itself).</p>
 *
 * <p><b>Deviation from a literal reading of plan section 21</b> ("argument
 * list, never a shell string"): {@code TerminalRuntime#openSurface} (Phase 0's
 * already-fixed, narrow terminal API -- not modified here) only accepts a
 * single shell command string, which libghostty always runs through a shell
 * (see {@link app.drydock.terminal.api.TerminalSpec} for the exact macOS
 * {@code login}/{@code bash -c "exec -l ..."} wrapping). There is no
 * argument-list overload to call
 * instead. This class therefore builds the command as a single
 * single-quoted-argument string ({@code ClaudeAgentProvider.shellQuote})
 * rather than an actual
 * {@code String[]}/{@code List<String>} argument vector; every dynamic value
 * placed into it (display name, Claude session id/name) is quoted so it
 * cannot be interpreted as additional shell syntax. Likewise, plan section
 * 11.1's "add only application-specific environment variables that are
 * strictly necessary" is not implemented: {@code TerminalRuntime.openSurface} has
 * no environment-map parameter at all, so the spawned {@code claude}
 * process's environment is simply whatever the embedded shell inherits from
 * this application's own process (which does satisfy "inherit the
 * application environment").</p>
 */
public final class SessionManager implements AutoCloseable {

    private static final Logger LOG = System.getLogger(SessionManager.class.getName());

    /** Default grace period for {@link #closeSession}, matching Gate 0D's verified Ctrl+D-exit timing headroom. */
    private static final long DEFAULT_GRACE_PERIOD_MILLIS = 3000;
    private static final long DEFAULT_POLL_INTERVAL_MILLIS = 100;

    /** How long {@link #close} waits for queued background work (state saves) before giving up. */
    private static final long CLOSE_AWAIT_TERMINATION_SECONDS = 2;

    private final ApplicationStateStore stateStore;
    private final AgentRegistry registry;
    private final ExecutorService backgroundExecutor;
    private final boolean ownsExecutor;

    /**
     * Set once at startup when the MCP server started; empty when it did not
     * (or has not yet). Volatile because launches run on the background
     * executor while startup completes on another thread; a launch that races
     * startup simply omits the flag, which is the intended degradation.
     */
    private volatile Optional<McpWiring> mcpWiring = Optional.empty();

    /** The three things needed to mint a per-session {@code --mcp-config} file. */
    private record McpWiring(McpConfigWriter writer, McpSessionRegistry registry, String endpointUrl) { }

    private final ActiveSessionRegistry activeRegistry = new ActiveSessionRegistry();
    private final Map<ManagedSessionId, TerminalSurface> activeSurfaces = new ConcurrentHashMap<>();

    /**
     * Agent session ids already bound to a {@link ManagedAgentSession}
     * (seeded from persisted state at construction, then grown as DISCOVERED
     * launches claim a fresh id) so post-launch discovery never re-binds an
     * id that already belongs to another session.
     */
    private final Set<String> claimedAgentSessionIds;

    public SessionManager(ApplicationStateRepository stateRepository, AgentRegistry registry) {
        this(stateRepository, registry, Executors.newVirtualThreadPerTaskExecutor(), true);
    }

    /** For callers/tests that want to supply (and own the shutdown of) their own executor. */
    public SessionManager(ApplicationStateRepository stateRepository, AgentRegistry registry,
                           ExecutorService backgroundExecutor) {
        this(stateRepository, registry, backgroundExecutor, false);
    }

    private SessionManager(ApplicationStateRepository stateRepository, AgentRegistry registry,
                            ExecutorService backgroundExecutor, boolean ownsExecutor) {
        // The store is shared with every other manager built against the
        // same repository instance (see ApplicationStateStore.forRepository),
        // so cross-manager read-modify-write cycles serialize on ONE lock.
        this.stateStore = ApplicationStateStore.forRepository(stateRepository);
        this.registry = registry;
        this.backgroundExecutor = backgroundExecutor;
        this.ownsExecutor = ownsExecutor;
        stateStore.update(SessionManager::normalizeLoadedState);
        this.claimedAgentSessionIds = seedClaimedIds(stateStore.state());
    }

    /**
     * Pure helper: every {@code agentSessionId} already assigned to a
     * persisted session, so a fresh {@link SessionManager} (e.g. after a
     * restart) never lets post-launch DISCOVERED-id discovery re-bind an id
     * that some other session already owns.
     */
    static Set<String> seedClaimedIds(ApplicationState state) {
        Set<String> ids = ConcurrentHashMap.newKeySet();
        for (ManagedAgentSession s : state.sessions()) {
            s.agentSessionId().ifPresent(ids::add);
        }
        return ids;
    }

    /**
     * Enables per-session MCP config injection, so launched sessions can call
     * back into this app (see {@code app.drydock.mcp.McpServer}). Empty until
     * called, so a failed MCP startup degrades to sessions without Drydock
     * tools rather than sessions that fail to launch.
     */
    public void useMcpConfig(McpConfigWriter writer, McpSessionRegistry registry, String endpointUrl) {
        this.mcpWiring = Optional.of(new McpWiring(writer, registry, endpointUrl));
    }

    /**
     * Mints this session's token and, for a {@link McpDelivery#CONFIG_FILE}
     * provider, writes its MCP config file. Returns empty when MCP is not
     * wired up, the session's provider cannot reach drydock's tools at all, or
     * the write failed: a session without Drydock tools is strictly better
     * than one that fails to launch. May perform file I/O -- background
     * executor only.
     *
     * <p>The delivery check lives HERE rather than only inside the provider's
     * own flag builder: this method's result is an eagerly evaluated argument
     * to {@link CreateContext}/{@link ResumeContext}, so a check made
     * downstream would still have minted a token -- and written a file -- that
     * the builder then discards. {@link AgentProvider#mcpDelivery()} is the
     * gate because the narrower "does the installed binary advertise the
     * flag" question is provider-internal (Claude's {@code
     * ClaudeCapabilities.supportsMcpConfig}).</p>
     *
     * <p>A {@link McpDelivery#COMMAND_LINE} provider gets a token but no file.
     * Writing one would leave a credential on disk that nothing ever reads.</p>
     *
     * @param spawn whether this session may create worktrees and start further
     *              sessions. {@link Spawn#FORBIDDEN} for a session an agent
     *              started, which is what makes fan-out depth 1.
     */
    private Optional<McpAccess> mcpAccessFor(AgentProvider provider, ManagedSessionId sessionId, Spawn spawn) {
        Optional<McpWiring> wiring = mcpWiring;
        McpDelivery delivery = provider.mcpDelivery();
        if (wiring.isEmpty() || delivery == McpDelivery.NONE) {
            return Optional.empty();
        }
        McpWiring mcp = wiring.get();
        String token = mcp.registry().mint(sessionId, spawn);
        if (delivery == McpDelivery.COMMAND_LINE) {
            return Optional.of(new McpAccess(mcp.endpointUrl(), token, Optional.empty()));
        }
        try {
            Path configFile = mcp.writer().writeFor(sessionId, mcp.endpointUrl(), token);
            return Optional.of(new McpAccess(mcp.endpointUrl(), token, Optional.of(configFile)));
        } catch (IOException e) {
            // Never log the token or the endpoint URL (it carries the port).
            LOG.log(Level.WARNING, "Could not write MCP config for session " + sessionId
                    + "; launching without Drydock tools: " + e.getMessage());
            mcp.registry().revoke(sessionId);
            return Optional.empty();
        }
    }

    /**
     * Drops a finished session's token and its config file, so a stale token
     * cannot be replayed and no file lingers under {@code <base>/mcp/}.
     * Budget charges deliberately survive (see {@link
     * McpSessionRegistry#revoke}). Performs file I/O -- background executor
     * only.
     */
    private void releaseMcpConfig(ManagedSessionId sessionId) {
        mcpWiring.ifPresent(mcp -> {
            mcp.registry().revoke(sessionId);
            mcp.writer().delete(sessionId);
        });
    }

    /**
     * Hands {@link #releaseMcpConfig} to the background executor: both callers
     * run on the FX thread (the exit watcher's tick, {@code
     * closeGracefully}'s callback) and the config-file delete is I/O
     * (AGENTS.md).
     */
    private void releaseMcpConfigAsync(ManagedSessionId sessionId) {
        if (mcpWiring.isEmpty()) {
            return;
        }
        try {
            backgroundExecutor.execute(() -> releaseMcpConfig(sessionId));
        } catch (RejectedExecutionException e) {
            // Shutdown already drained the executor. The next startup's
            // purgeStale() removes the file, and the token dies with the
            // process, so there is nothing left to leak.
            LOG.log(Level.DEBUG, "Skipping MCP config cleanup for " + sessionId + " during shutdown");
        }
    }

    /**
     * Lets a diagnostic override win over a provider-built command, keyed by
     * agent kind first (so multiple agent kinds can be overridden
     * independently) and falling back to the un-keyed property for backward
     * compatibility with existing diagnostic tooling.
     */
    private static String diagOverride(AgentKind kind, String built) {
        return System.getProperty("app.drydock.diag.command." + kind.persistedName(),
                System.getProperty("app.drydock.diag.command", built));
    }

    /**
     * Whether a diagnostic override will replace this kind's built command.
     * Read BEFORE minting an MCP config: computing one for a launch whose
     * command is about to be discarded would mint a token and write a file
     * nothing ever consumes.
     */
    private static boolean hasDiagOverride(AgentKind kind) {
        return diagOverride(kind, null) != null;
    }

    /**
     * A freshly loaded state can contain sessions persisted as {@link
     * SessionStatus#RUNNING}/{@link SessionStatus#STARTING} by a previous
     * app run (e.g. the app quit, crashed, or was killed while sessions were
     * open). No terminal process survives an app restart, so presenting
     * those statuses would show "running" indicators for processes that do
     * not exist; normalize them to {@link SessionStatus#INACTIVE} before
     * anything reads them.
     */
    private static ApplicationState normalizeLoadedState(ApplicationState loaded) {
        List<ManagedAgentSession> normalized = loaded.sessions().stream()
                .map(session -> switch (session.status()) {
                    case RUNNING, STARTING -> session.withStatus(SessionStatus.INACTIVE);
                    default -> session;
                })
                .toList();
        return loaded.withSessions(normalized);
    }

    public ApplicationState state() {
        return stateStore.state();
    }

    public List<ManagedAgentSession> sessions() {
        return stateStore.state().sessions();
    }

    // ---- 11.1 Create a new session ----------------------------------------

    /**
     * Mints the metadata for a brand-new session (generated display name)
     * WITHOUT launching anything. Callers that key UI bookkeeping by
     * session id should prepare first and then {@link #launchSession}: the
     * launch persists the session almost immediately (making it visible to
     * e.g. the sidebar), and a placeholder registered under a provisional
     * id would not be found by a concurrent open of the freshly persisted
     * real id -- yielding a duplicate surface and a leaked native pair.
     */
    public ManagedAgentSession prepareSession(Repository repository, AgentKind agentKind) {
        return newSessionMetadata(repository, defaultDisplayName(repository), agentKind);
    }

    /** As {@link #prepareSession}, for a session living inside an already-created worktree checkout. */
    public ManagedAgentSession prepareWorktreeSession(Repository repository, String displayName, Path worktreeRoot,
                                                        boolean branchCreatedHere, AgentKind agentKind) {
        return newSessionMetadata(repository, displayName, agentKind, Optional.of(worktreeRoot), branchCreatedHere);
    }

    /**
     * Launches a session minted by {@link #prepareSession}/{@link
     * #prepareWorktreeSession}, on behalf of the human: it may use the Drydock
     * MCP tools to create worktrees and start further sessions.
     */
    public CompletableFuture<SessionOpenResult> launchSession(ManagedAgentSession prepared, TerminalRuntime app,
                                                              TerminalHostView host, double scaleFactor) {
        return launchSession(prepared, app, host, scaleFactor, Spawn.ALLOWED);
    }

    /**
     * As {@link #launchSession(ManagedAgentSession, TerminalRuntime,
     * TerminalHostView, double)}, stating whether the new session may itself
     * spawn worktrees and sessions through MCP. {@link Spawn#FORBIDDEN} is
     * what keeps agent-driven fan-out at depth 1: without it, one instruction
     * could turn into a dozen agent processes.
     */
    public CompletableFuture<SessionOpenResult> launchSession(ManagedAgentSession prepared, TerminalRuntime app,
                                                              TerminalHostView host, double scaleFactor,
                                                              Spawn spawn) {
        return launchNewSession(prepared, prepared.displayName(), app, host, scaleFactor, spawn);
    }

    private CompletableFuture<SessionOpenResult> launchNewSession(ManagedAgentSession initial, String displayName,
                                                                  TerminalRuntime app, TerminalHostView host,
                                                                  double scaleFactor, Spawn spawn) {
        AgentKind kind = initial.agentKind();
        AgentProvider provider = registry.provider(kind)
                .orElseThrow(() -> new IllegalStateException("No provider for " + kind));
        Optional<SshRemote> remote = remoteFor(repositoryFor(initial));
        // Generated up front (for PRESET providers) so this app -- not the
        // agent CLI -- decides the session id: launching with a pre-supplied
        // id (when the provider supports it) makes it known without having
        // to scrape it from the tool's output or state files, so a later
        // resume can target this EXACT conversation directly instead of
        // dropping the user into an interactive picker. DISCOVERED providers
        // mint their own id, so none is generated here.
        String sessionId = provider.idStrategy() == SessionIdStrategy.PRESET
                ? UUID.randomUUID().toString()
                : "";
        String workingDir = remote.isPresent() ? System.getProperty("user.home") : initial.workingDirectory().toString();

        // DISCOVERED providers mint their own id only after launch: snapshot
        // the id store BEFORE spawning (so discovery can tell "new since
        // launch" from "already there") and remember when we launched. The
        // snapshot is disk I/O (Files.walk over the rollout store), so it
        // must run on the background executor, never on the calling (FX)
        // thread -- captured via holders since it is produced mid-chain (in
        // the very first async stage, before the process spawns) but only
        // consumed by the discovery stage at the end.
        Optional<SessionIdDiscovery> discovery = provider.idStrategy() == SessionIdStrategy.DISCOVERED
                ? registry.idDiscovery(provider.kind())
                : Optional.empty();
        Path discoverCwd = initial.workingDirectory();
        AtomicReference<Object> snapshotRef = new AtomicReference<>();
        AtomicReference<Instant> launchedAtRef = new AtomicReference<>();

        // Metadata persistence is disk I/O; keep it off the (FX) caller thread.
        // The DISCOVERED snapshot/timestamp are captured in this same
        // pre-spawn stage so they still land before buildAndLaunchCreate
        // spawns the process (otherwise the new session's own rollout would
        // already be in the snapshot and discovery could never find it).
        CompletableFuture<SessionOpenResult> createFuture = CompletableFuture.runAsync(() -> {
                    persistNewSession(initial);
                    discovery.ifPresent(d -> snapshotRef.set(d.snapshot(discoverCwd)));
                    launchedAtRef.set(Instant.now());
                }, backgroundExecutor)
                .thenCompose(ignored -> buildAndLaunchCreate(provider, displayName, sessionId,
                        initial.workingDirectory(), remote, app, host, scaleFactor, workingDir,
                        initial.id(), spawn))
                .handleAsync((launch, ex) -> finalizeCreate(initial, sessionId, launch, ex), backgroundExecutor);

        if (discovery.isPresent()) {
            // Detached side effect: discovery polls for ~5s, which must
            // never delay the surface reveal callers are awaiting on
            // createFuture. Never fails the launch: discover() returns
            // empty on failure/ambiguity and resume falls back to the
            // interactive picker; any RuntimeException it throws is caught
            // and logged rather than treated as a launch failure.
            createFuture.thenAcceptAsync(result -> {
                if (result instanceof SessionOpenResult.Opened opened) {
                    try {
                        discovery.get().discover(discoverCwd, launchedAtRef.get(), snapshotRef.get(),
                                        claimedAgentSessionIds)
                                .ifPresent(id -> {
                                    // discover() already atomically claimed
                                    // `id` in claimedAgentSessionIds.
                                    updateSession(opened.session().id(),
                                            s -> s.withAgentSessionId(Optional.of(id)));
                                    activeRegistry.tryMarkActive(id, opened.session().id());
                                });
                    } catch (RuntimeException e) {
                        LOG.log(Level.WARNING, () -> provider.kind() + " id discovery failed for " + opened.session().id()
                                + "; resume will use the picker: " + e);
                    }
                }
            }, backgroundExecutor);
        }
        return createFuture;
    }

    /**
     * Builds a provider's create command (on the background executor -- it
     * may block on capability probing) and, once built, opens the resulting
     * {@link TerminalSurface} on the FX thread. Shared by {@link
     * #launchNewSession} and {@link #startFreshConversation}, the two paths
     * that mint a brand-new agent conversation.
     *
     * <p>The per-session MCP config is minted inside this method's async stage
     * -- writing it is file I/O, and it must never happen on the (FX) caller
     * thread. A remote launch mints nothing at all: {@code claude} would run on
     * the remote host and could not reach this machine's loopback address, so
     * the file would only be a live bearer token sitting unused on disk.</p>
     */
    private CompletableFuture<CreateLaunch> buildAndLaunchCreate(AgentProvider provider, String displayName,
                                                                  String sessionId, Path targetWorkingDirectory,
                                                                  Optional<SshRemote> remote, TerminalRuntime app,
                                                                  TerminalHostView host, double scaleFactor,
                                                                  String surfaceWorkingDirectory,
                                                                  ManagedSessionId managedSessionId, Spawn spawn) {
        return CompletableFuture.supplyAsync(() -> {
                    Optional<McpAccess> mcp = remote.isPresent() || hasDiagOverride(provider.kind())
                            ? Optional.empty()
                            : mcpAccessFor(provider, managedSessionId, spawn);
                    CreateContext ctx = new CreateContext(displayName, sessionId, targetWorkingDirectory, remote,
                            mcp);
                    LaunchPlan plan = provider.buildCreateCommand(ctx);
                    if (!plan.supported()) {
                        throw new IllegalStateException(
                                provider.kind() + " cannot launch this session (remote unsupported)");
                    }
                    // contains() rather than plan.sessionIdUsed() alone: a
                    // diag command override never carries the id even when
                    // the provider's own plan says it used it.
                    String command = diagOverride(provider.kind(), plan.command());
                    return new CreatePlan(command, plan.sessionIdUsed() && command.contains(sessionId));
                }, backgroundExecutor)
                .thenCompose(plan -> createSurfaceOnFxThread(app, host, scaleFactor, plan.command(),
                        surfaceWorkingDirectory)
                        .thenApply(surface -> new CreateLaunch(plan, surface)));
    }

    /** The launch command plus whether it actually carries the pre-generated {@code --session-id}. */
    private record CreatePlan(String command, boolean sessionIdUsed) { }

    private record CreateLaunch(CreatePlan plan, TerminalSurface surface) { }

    private SessionOpenResult finalizeCreate(ManagedAgentSession initial, String agentSessionId,
                                              CreateLaunch launch, Throwable ex) {
        if (ex != null) {
            Throwable cause = unwrap(ex);
            LOG.log(Level.WARNING, () -> "Failed to start session " + initial.id() + ": " + cause.getMessage());
            // A launch that never got a surface never reaches onSurfaceClosed,
            // so its token and config file would otherwise live as long as the
            // app. Already on the background executor (handleAsync), so the
            // file delete needs no further hop.
            releaseMcpConfig(initial.id());
            try {
                persistUpdatedSession(initial.withStatus(SessionStatus.FAILED));
            } catch (RuntimeException persistFailure) {
                // Never mask the original launch failure with a secondary
                // persistence failure; the FAILED status is best-effort.
                LOG.log(Level.WARNING, () -> "Could not persist FAILED status for session " + initial.id()
                        + ": " + persistFailure.getMessage());
            }
            throw wrap(cause);
        }
        ManagedAgentSession running = initial.withStatus(SessionStatus.RUNNING).withLastOpenedAt(Instant.now());
        // Only persist the Claude session id if the launch command actually
        // used it -- persisting an id claude never saw would make a later
        // resume target a nonexistent conversation.
        if (launch.plan().sessionIdUsed()) {
            running = running.withAgentSessionId(Optional.of(agentSessionId));
            activeRegistry.tryMarkActive(agentSessionId, running.id());
        }
        persistUpdatedSession(running);
        // Records the agent kind actually used so the next session opened in
        // this repository defaults to it (AgentSelector's per-repo default).
        RepositoryId createdRepositoryId = running.repositoryId();
        AgentKind createdAgentKind = running.agentKind();
        stateStore.update(s -> repoWithLastUsedAgent(s, createdRepositoryId, createdAgentKind));
        activeSurfaces.put(running.id(), launch.surface());
        return new SessionOpenResult.Opened(running, launch.surface());
    }

    /**
     * Pure transform: returns {@code state} with {@code repositoryId}'s
     * settings updated to record {@code kind} as its last-used agent (a
     * no-op if no repository matches, e.g. it was removed concurrently).
     */
    static ApplicationState repoWithLastUsedAgent(ApplicationState state, RepositoryId repositoryId, AgentKind kind) {
        return state.withRepositories(state.repositories().stream()
                .map(r -> r.id().equals(repositoryId) ? r.withSettings(r.settings().withLastUsedAgent(kind)) : r)
                .toList());
    }

    // ---- 11.2 Resume a session ---------------------------------------------

    /**
     * Resumes an existing session (plan section 11.2): {@code claude
     * --resume '<id>'} if a trusted Claude session id is known, else {@code
     * claude --resume '<name>'} if an assigned name is known, else plain
     * {@code claude --resume} (the official picker). Always launches from
     * the session's stored working directory; never silently substitutes a
     * different one (see {@link #reassignWorkingDirectory}).
     */
    public CompletableFuture<SessionOpenResult> resumeSession(ManagedSessionId sessionId, TerminalRuntime app,
                                                               TerminalHostView host, double scaleFactor) {
        // checkResumeBlocked touches the filesystem (working-directory and
        // transcript existence probes, potentially persistence) -- run it on
        // the background executor, never the calling (FX) thread.
        return CompletableFuture.supplyAsync(() -> checkResumeBlocked(sessionId), backgroundExecutor)
                .thenCompose(blocked -> {
                    if (blocked.isPresent()) {
                        return CompletableFuture.completedFuture(blocked.get());
                    }
                    ManagedAgentSession session = requireSession(sessionId);
                    AgentKind kind = session.agentKind();
                    AgentProvider provider = registry.provider(kind)
                            .orElseThrow(() -> new IllegalStateException("No provider for " + kind));
                    Optional<SshRemote> remote = remoteFor(repositoryFor(session));
                    String workingDir = remote.isPresent()
                            ? System.getProperty("user.home")
                            : session.workingDirectory().toString();
                    // Command construction (including any capability probing
                    // it needs) runs entirely on the background executor; a
                    // probe failure inside the provider degrades to its own
                    // conservative fallback rather than sinking the resume
                    // (see ClaudeAgentProvider.detectCaps).
                    return CompletableFuture.supplyAsync(() -> {
                                // Spawn.ALLOWED: a resume is the human
                                // reopening a session from the UI. A known
                                // limitation: depth 1 is a property of the
                                // LAUNCH, not of the session, so a session an
                                // agent started regains spawn rights if the
                                // human later resumes it themselves.
                                Optional<McpAccess> mcp = remote.isPresent()
                                        ? Optional.empty()
                                        : mcpAccessFor(provider, session.id(), Spawn.ALLOWED);
                                ResumeContext ctx = new ResumeContext(session.agentSessionId(),
                                        session.agentSessionName(), session.workingDirectory(), remote, mcp);
                                return provider.buildResumeCommand(ctx).command();
                            }, backgroundExecutor)
                            .thenCompose(command -> createSurfaceOnFxThread(app, host, scaleFactor, command,
                                    workingDir)
                                    .handleAsync((surface, ex) -> finalizeResume(session, surface, ex),
                                            backgroundExecutor));
                });
    }

    private SessionOpenResult finalizeResume(ManagedAgentSession session, TerminalSurface surface, Throwable ex) {
        if (ex != null) {
            Throwable cause = unwrap(ex);
            LOG.log(Level.WARNING, () -> "Failed to resume session " + session.id() + ": " + cause.getMessage());
            persistUpdatedSession(session.withStatus(SessionStatus.FAILED));
            throw wrap(cause);
        }
        ManagedAgentSession running = session.withStatus(SessionStatus.RUNNING).withLastOpenedAt(Instant.now());
        persistUpdatedSession(running);
        activeSurfaces.put(running.id(), surface);
        session.agentSessionId().ifPresent(claudeId -> activeRegistry.tryMarkActive(claudeId, running.id()));
        return new SessionOpenResult.Opened(running, surface);
    }

    /**
     * Checks the two "do not launch a surface" preconditions from plan
     * section 11.2/11.3 without touching any terminal object -- pure
     * metadata/bookkeeping, so it is directly unit-testable without a real
     * window (see class Javadoc and the accompanying test).
     *
     * @return a present {@link SessionOpenResult.AlreadyOpen} or {@link
     *         SessionOpenResult.MissingWorkingDirectory} if launching should
     *         not proceed, or {@link Optional#empty()} if the session is
     *         clear to launch (working directory exists, and either it has
     *         no Claude session id yet or that id is not already active
     *         elsewhere).
     *
     * <p>Remote sessions skip both probes -- the working directory is a
     * virtual placeholder and the transcript lives on the remote host; a
     * vanished remote conversation surfaces as claude's own "No conversation
     * found" inside the terminal (spec: degraded remote contract).</p>
     */
    Optional<SessionOpenResult> checkResumeBlocked(ManagedSessionId sessionId) {
        // Deliberately holds no lock across the filesystem probes below --
        // they can stall (network volumes, cold disk), and a monitor held
        // here used to block FX-thread callers of this manager's other
        // (then-synchronized) methods. State reads/writes take the store's
        // own short-lived lock only.
        ManagedAgentSession session = requireSession(sessionId);

        // An unrecognized persisted agentKind raw-name decodes to this
        // status with a placeholder agentKind() == CLAUDE (see the
        // ManagedAgentSession decoder); launching it would silently run the
        // wrong agent in that worktree, so it must never reach a launch.
        if (session.status() == SessionStatus.UNSUPPORTED_AGENT) {
            return Optional.of(new SessionOpenResult.UnsupportedAgent(session));
        }

        Optional<String> agentSessionId = session.agentSessionId();
        if (agentSessionId.isPresent()) {
            Optional<ManagedSessionId> active = activeRegistry.activeSessionId(agentSessionId.get());
            if (active.isPresent() && !active.get().equals(sessionId)) {
                TerminalSurface activeSurface = activeSurfaces.get(active.get());
                if (activeSurface != null) {
                    return Optional.of(new SessionOpenResult.AlreadyOpen(session, active.get(), activeSurface));
                }
            }
        }

        boolean remoteSession = repositoryFor(session).map(Repository::isRemote).orElse(false);

        if (!remoteSession && Files.notExists(session.workingDirectory())) {
            ManagedAgentSession missing = session.withStatus(SessionStatus.MISSING_WORKING_DIRECTORY);
            persistUpdatedSession(missing);
            return Optional.of(new SessionOpenResult.MissingWorkingDirectory(missing));
        }

        // A pinned conversation id whose transcript the agent no longer has
        // on disk would make a resume-by-id exit immediately with "No
        // conversation found" -- detect it up front via the provider's
        // ConversationSource so the UI can offer a fresh start or deletion
        // instead of presenting a dead terminal.
        if (!remoteSession && agentSessionId.isPresent()) {
            boolean missing = registry.conversations(session.agentKind())
                    .map(cs -> !cs.transcriptExists(session.workingDirectory(), agentSessionId.get()))
                    .orElse(false); // no catalog → never block on a missing transcript
            if (missing) {
                return Optional.of(new SessionOpenResult.MissingConversation(session));
            }
        }

        return Optional.empty();
    }

    /**
     * Relaunches a session whose pinned conversation vanished (see {@link
     * SessionOpenResult.MissingConversation}) as a BRAND-NEW claude
     * conversation under the same display name and working directory: the
     * managed session row is kept, its stale Claude session id replaced by
     * a freshly pinned one.
     */
    public CompletableFuture<SessionOpenResult> startFreshConversation(ManagedSessionId sessionId, TerminalRuntime app,
                                                                        TerminalHostView host, double scaleFactor) {
        // The stale-id clear persists to disk; keep it off the (FX) caller thread.
        return CompletableFuture.supplyAsync(() -> {
                    ManagedAgentSession cleared = requireSession(sessionId).withAgentSessionId(Optional.empty());
                    persistUpdatedSession(cleared);
                    return cleared;
                }, backgroundExecutor)
                .thenCompose(cleared -> {
                    AgentKind kind = cleared.agentKind();
                    AgentProvider provider = registry.provider(kind)
                            .orElseThrow(() -> new IllegalStateException("No provider for " + kind));
                    Optional<SshRemote> remote = remoteFor(repositoryFor(cleared));
                    String freshSessionId = provider.idStrategy() == SessionIdStrategy.PRESET
                            ? UUID.randomUUID().toString()
                            : "";
                    String workingDir = remote.isPresent()
                            ? System.getProperty("user.home")
                            : cleared.workingDirectory().toString();
                    // Spawn.ALLOWED for the same reason as the resume path: a
                    // fresh start is the human's own action, taken from the UI.
                    return buildAndLaunchCreate(provider, cleared.displayName(), freshSessionId,
                            cleared.workingDirectory(), remote, app, host, scaleFactor, workingDir,
                            cleared.id(), Spawn.ALLOWED)
                            .handleAsync((launch, ex) -> finalizeCreate(cleared, freshSessionId, launch, ex),
                                    backgroundExecutor);
                });
    }

    /** Explicitly reassigns a session's working directory (plan section 11.2), e.g. after the user picks a replacement. */
    public ManagedAgentSession reassignWorkingDirectory(ManagedSessionId sessionId, Path newWorkingDirectory) {
        Path normalized = newWorkingDirectory.toAbsolutePath().normalize();
        return updateSession(sessionId,
                session -> session.withWorkingDirectory(normalized).withStatus(SessionStatus.INACTIVE));
    }

    /**
     * The human's rename. {@code pin} marks the name as theirs, which refuses
     * every later {@link #applyAgentRename}.
     *
     * <p>Pinning is a decision of the caller, not of renaming: {@code pin} is
     * true for an explicit confirm (Enter in the inline editor, OK in the
     * Rename dialog) and false for a focus-loss commit, which an agent can
     * provoke by opening a tab and stealing focus.</p>
     */
    public ManagedAgentSession renameSession(ManagedSessionId sessionId, String newDisplayName, boolean pin) {
        return updateSession(sessionId, session -> pin
                ? session.withDisplayName(newDisplayName).withNamePinned(true)
                : session.withDisplayName(newDisplayName));
    }

    /**
     * The {@code session_handoff} MCP tool's write.
     *
     * <p>One transform, like {@link #applyAgentRename}: the session lookup and
     * the brief replacement happen under {@link ApplicationStateStore}'s single
     * lock, so a concurrent human edit of the same brief cannot be silently
     * overwritten by a read-then-write.</p>
     *
     * <p>Wholesale replacement -- any existing brief for this session is
     * dropped, not merged, so an omitted optional slot is cleared. Every slot
     * must already have been through {@link PromptSafety#checkHandoffSlot}.</p>
     */
    public HandoffBrief applyAgentHandoff(ManagedSessionId sessionId, McpSessionContext.HandoffDraft draft,
                                          Optional<String> headCommit) {
        HandoffBrief[] result = new HandoffBrief[1];
        stateStore.update(state -> {
            boolean known = state.sessions().stream().anyMatch(existing -> existing.id().equals(sessionId));
            if (!known) {
                throw new UnknownSessionException(sessionId);
            }
            HandoffBrief brief = new HandoffBrief(sessionId, draft.goal(), draft.nextStep(), draft.approach(),
                    draft.decisions(), draft.ruledOut(), draft.corrections(), Instant.now(), headCommit,
                    HandoffBrief.Author.AGENT);
            result[0] = brief;
            List<HandoffBrief> briefs = new ArrayList<>(state.handoffBriefs().stream()
                    .filter(existing -> !existing.sessionId().equals(sessionId))
                    .toList());
            briefs.add(brief);
            return state.withHandoffBriefs(List.copyOf(briefs));
        });
        return result[0];
    }

    /** Every session's handoff brief, for the banner and the fork seed. */
    public List<HandoffBrief> handoffBriefs() {
        return stateStore.state().handoffBriefs();
    }

    /**
     * The {@code session_rename} MCP tool's write.
     *
     * <p>One transform, deliberately: the pin test, the unchanged test, the
     * collision test and the write all read the same state under {@link
     * ApplicationStateStore}'s single lock. Reading first and writing after
     * would let a human rename land in between and be silently overwritten --
     * the load-then-save shape AGENTS.md names as a data-loss bug.</p>
     *
     * <p>{@code title} must already have been through {@link
     * PromptSafety#checkSessionTitle}: this compares and stores it verbatim.</p>
     */
    public RenameOutcome applyAgentRename(ManagedSessionId sessionId, String title) {
        RenameOutcome[] result = new RenameOutcome[1];
        stateStore.update(state -> {
            ManagedAgentSession session = state.sessions().stream()
                    .filter(existing -> existing.id().equals(sessionId))
                    .findFirst()
                    .orElseThrow(() -> new UnknownSessionException(sessionId));

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
                    // Both sides folded: a stored name came through the human
                    // path, which applies no checkSessionTitle, so it can
                    // carry a non-breaking space that renders identically.
                    .filter(other -> PromptSafety.foldForComparison(other.displayName()).equalsIgnoreCase(title))
                    .findFirst();
            if (clash.isPresent()) {
                result[0] = new RenameOutcome(RenameKind.COLLIDED, clash.get().displayName());
                return state;
            }

            ManagedAgentSession renamed = session.withDisplayName(title);
            result[0] = new RenameOutcome(RenameKind.RENAMED, title);
            return withReplacedSession(state, renamed);
        });
        return result[0];
    }

    /** Records the observed PR lifecycle state of a worktree session's branch (Finish-panel reconciliation). */
    public ManagedAgentSession updatePrState(ManagedSessionId sessionId, PrState prState,
                                              Optional<Integer> prNumber) {
        return updateSession(sessionId, session -> session.withPr(prState, prNumber));
    }

    /**
     * Registers an existing on-disk Claude conversation (discovered by the
     * resume picker in {@code ~/.claude/projects}) as a managed session, so
     * the normal {@link #resumeSession} path can reopen that exact
     * conversation via {@code claude --resume '<id>'}. Idempotent per
     * Claude session id: if a managed session already tracks {@code
     * agentSessionId}, that session is returned unchanged instead of
     * creating a duplicate row.
     */
    public ManagedAgentSession adoptConversation(Repository repository, String agentSessionId,
                                                  String displayName) {
        ManagedAgentSession[] result = new ManagedAgentSession[1];
        stateStore.update(state -> {
            Optional<ManagedAgentSession> existing = state.sessions().stream()
                    .filter(session -> session.agentSessionId().map(agentSessionId::equals).orElse(false))
                    .findFirst();
            if (existing.isPresent()) {
                result[0] = existing.get();
                return state;
            }
            ManagedAgentSession adopted = newSessionMetadata(repository, displayName)
                    .withAgentSessionId(Optional.of(agentSessionId));
            result[0] = adopted;
            List<ManagedAgentSession> updated = new ArrayList<>(state.sessions());
            updated.add(adopted);
            return state.withSessions(updated);
        });
        return result[0];
    }

    /**
     * Deletes a session's metadata entirely (sidebar quick-action "Delete"),
     * first closing its surface gracefully if one is active. Only this
     * manager's metadata is removed; nothing of claude's own on-disk
     * transcript is touched (plan section 21: never destroy user data).
     */
    public CompletableFuture<Void> deleteSession(ManagedSessionId sessionId) {
        // thenRunAsync: closeSession's future completes on the FX thread
        // (closeGracefully's callback); the metadata removal must not run
        // there.
        return closeSession(sessionId).thenRunAsync(
                () -> {
                    // Also covers a session that had no active surface, and so
                    // never went through onSurfaceClosed.
                    releaseMcpConfig(sessionId);
                    stateStore.update(state -> state.withSessions(state.sessions().stream()
                            .filter(session -> !session.id().equals(sessionId))
                            .toList()));
                },
                backgroundExecutor);
    }

    // ---- Close --------------------------------------------------------------

    /** Closes a session's surface (if any is active) using the grace-period defaults. */
    public CompletableFuture<Void> closeSession(ManagedSessionId sessionId) {
        return closeSession(sessionId, DEFAULT_GRACE_PERIOD_MILLIS, DEFAULT_POLL_INTERVAL_MILLIS);
    }

    /**
     * Closes a session's active surface via {@link
     * TerminalSurface#closeGracefully(long, long, Runnable)} -- never {@link
     * TerminalSurface#close()} directly, per the documented live-child-process
     * crash risk -- and updates the persisted session's status/lastOpenedAt
     * afterward. A no-op (completes immediately) if the session has no
     * active surface.
     *
     * <p>{@code lastExitCode} is deliberately left unchanged: {@link
     * TerminalSurface} exposes only {@link TerminalSurface#processExited()}
     * (a boolean), not an actual exit code, so there is nothing more precise
     * to persist here.</p>
     */
    public CompletableFuture<Void> closeSession(ManagedSessionId sessionId, long gracePeriodMillis, long pollIntervalMillis) {
        TerminalSurface surface = activeSurfaces.get(sessionId);
        if (surface == null) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        Platform.runLater(() -> surface.closeGracefully(gracePeriodMillis, pollIntervalMillis, () -> {
            onSurfaceClosed(sessionId, surface);
            future.complete(null);
        }));
        return future;
    }

    /**
     * Records that a session's child process exited on its own (detected by
     * the UI polling {@link TerminalSurface#processExited()}), without
     * closing the surface -- the terminal stays open so the user can read
     * the final output. Only a {@link SessionStatus#RUNNING} session is
     * updated (idempotent; racing with {@link #closeSession}'s own EXITED
     * update is harmless).
     *
     * <p>This is a session-ending path in its own right, and the most common
     * one: the user types {@code exit}, or the agent finishes. Because the
     * surface deliberately stays open, {@link #onSurfaceClosed} does not run,
     * so the MCP token and its config file are released HERE -- otherwise the
     * file under {@code <base>/mcp/} would keep a live bearer token on disk for
     * as long as the tab stayed open. Guarded by the {@code Optional} result,
     * which makes it happen exactly once.</p>
     *
     * @return the updated session, or empty if the session no longer exists
     *         or was not RUNNING
     */
    public Optional<ManagedAgentSession> markSessionExited(ManagedSessionId sessionId) {
        ManagedAgentSession[] result = new ManagedAgentSession[1];
        stateStore.update(state -> {
            Optional<ManagedAgentSession> running = state.sessions().stream()
                    .filter(session -> session.id().equals(sessionId))
                    .filter(session -> session.status() == SessionStatus.RUNNING)
                    .findFirst();
            if (running.isEmpty()) {
                return state;
            }
            ManagedAgentSession updated = running.get().withStatus(SessionStatus.EXITED);
            result[0] = updated;
            return withReplacedSession(state, updated);
        });
        Optional<ManagedAgentSession> exited = Optional.ofNullable(result[0]);
        exited.ifPresent(session -> releaseMcpConfigAsync(session.id()));
        return exited;
    }

    private void onSurfaceClosed(ManagedSessionId sessionId, TerminalSurface surface) {
        activeSurfaces.remove(sessionId, surface);
        releaseMcpConfigAsync(sessionId);
        findSession(sessionId).ifPresent(session -> {
            session.agentSessionId().ifPresent(activeRegistry::release);
            persistUpdatedSession(session.withStatus(SessionStatus.EXITED));
        });
    }

    @Override
    public void close() {
        if (ownsExecutor) {
            // shutdown() alone would let queued background work (including
            // state-transform submissions) die at JVM exit; give it a
            // bounded drain first.
            backgroundExecutor.shutdown();
            try {
                if (!backgroundExecutor.awaitTermination(CLOSE_AWAIT_TERMINATION_SECONDS, TimeUnit.SECONDS)) {
                    LOG.log(Level.WARNING, "Background executor did not drain within "
                            + CLOSE_AWAIT_TERMINATION_SECONDS + "s; forcing shutdown");
                    backgroundExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                backgroundExecutor.shutdownNow();
            }
        }
        // The store's writer is asynchronous; make every queued state save
        // durable before shutdown proceeds (AGENTS.md: services writing
        // files from background threads must expose a flush).
        stateStore.flush();
    }

    // ---- Helpers ------------------------------------------------------------

    private CompletableFuture<TerminalSurface> createSurfaceOnFxThread(TerminalRuntime app, TerminalHostView host,
                                                                       double scaleFactor, String command,
                                                                       String workingDirectory) {
        CompletableFuture<TerminalSurface> future = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                future.complete(app.openSurface(host, scaleFactor, new TerminalSpec(command, workingDirectory)));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    private String defaultDisplayName(Repository repository) {
        long existing = stateStore.state().sessions().stream()
                .filter(session -> session.repositoryId().equals(repository.id()))
                .count();
        return "Session " + (existing + 1);
    }

    private ManagedAgentSession newSessionMetadata(Repository repository, String displayName) {
        return newSessionMetadata(repository, displayName, AgentKind.CLAUDE, Optional.empty(), true);
    }

    private ManagedAgentSession newSessionMetadata(Repository repository, String displayName, AgentKind agentKind) {
        return newSessionMetadata(repository, displayName, agentKind, Optional.empty(), true);
    }

    /**
     * When {@code worktreeRoot} is present the session lives (and launches
     * claude) inside that worktree checkout rather than the repository's
     * main checkout -- {@code workingDirectory} IS the worktree directory.
     * {@code branchCreatedHere} records whether drydock minted the branch,
     * and so whether it may later delete it; it has no safe default, which
     * is why every worktree caller must state it.
     */
    private ManagedAgentSession newSessionMetadata(Repository repository, String displayName, AgentKind agentKind,
                                                    Optional<Path> worktreeRoot, boolean branchCreatedHere) {
        Instant now = Instant.now();
        return new ManagedAgentSession(
                ManagedSessionId.newId(),
                repository.id(),
                agentKind,
                displayName,
                Optional.empty(),
                Optional.empty(),
                worktreeRoot.orElse(repository.root()),
                worktreeRoot,
                SessionStatus.INACTIVE,
                now,
                now,
                Optional.empty(),
                PrState.NONE,
                Optional.empty(),
                branchCreatedHere,
                false,
                Optional.empty());   // lineage is set by the fork path, never here
    }

    /**
     * Whether the branch of the worktree at {@code worktreeRoot} may be
     * force-deleted along with it -- true only when a session records that
     * this application created that branch. See {@link BranchOwnership}.
     */
    public boolean mayDeleteBranchOf(Path worktreeRoot) {
        return BranchOwnership.mayDeleteBranchOf(sessions(), worktreeRoot);
    }

    private void persistNewSession(ManagedAgentSession session) {
        stateStore.update(state -> {
            List<ManagedAgentSession> updated = new ArrayList<>(state.sessions());
            updated.add(session);
            return state.withSessions(updated);
        });
    }

    /**
     * Replaces the persisted session with the same id as {@code
     * updatedSession} (a no-op if it was deleted concurrently). The
     * cross-manager lost-update protection this class used to hand-roll
     * (re-reading the freshest disk state and re-applying only the {@code
     * sessions} delta -- see docs/milestone5-report.md for the original
     * data-loss bug) now lives in {@link ApplicationStateStore}: every
     * manager's read-modify-write runs under the store's single lock, and
     * disk writes happen on the store's background writer, never here.
     */
    private void persistUpdatedSession(ManagedAgentSession updatedSession) {
        stateStore.update(state -> withReplacedSession(state, updatedSession));
    }

    /** Applies the atomic find-and-change-one-session pattern shared by the metadata mutators. */
    private ManagedAgentSession updateSession(ManagedSessionId sessionId,
                                               UnaryOperator<ManagedAgentSession> change) {
        ManagedAgentSession[] result = new ManagedAgentSession[1];
        stateStore.update(state -> {
            ManagedAgentSession session = state.sessions().stream()
                    .filter(existing -> existing.id().equals(sessionId))
                    .findFirst()
                    .orElseThrow(() -> new UnknownSessionException(sessionId));
            result[0] = change.apply(session);
            return withReplacedSession(state, result[0]);
        });
        return result[0];
    }

    private static ApplicationState withReplacedSession(ApplicationState state, ManagedAgentSession updatedSession) {
        return state.withSessions(state.sessions().stream()
                .map(existing -> existing.id().equals(updatedSession.id()) ? updatedSession : existing)
                .toList());
    }

    private ManagedAgentSession requireSession(ManagedSessionId sessionId) {
        return findSession(sessionId).orElseThrow(() -> new UnknownSessionException(sessionId));
    }

    private Optional<ManagedAgentSession> findSession(ManagedSessionId sessionId) {
        return stateStore.state().sessions().stream()
                .filter(session -> session.id().equals(sessionId))
                .findFirst();
    }

    private Optional<Repository> repositoryFor(ManagedAgentSession session) {
        return stateStore.state().repositories().stream()
                .filter(repository -> repository.id().equals(session.repositoryId()))
                .findFirst();
    }

    private static Optional<SshRemote> remoteFor(Optional<Repository> repository) {
        return repository.filter(Repository::isRemote).map(Repository::remote);
    }

    private static Throwable unwrap(Throwable t) {
        return (t instanceof CompletionException && t.getCause() != null) ? t.getCause() : t;
    }

    private static CompletionException wrap(Throwable cause) {
        return (cause instanceof CompletionException completionException) ? completionException : new CompletionException(cause);
    }
}
