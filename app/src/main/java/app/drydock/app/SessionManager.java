package app.drydock.app;

import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.agent.api.CreateContext;
import app.drydock.agent.api.LaunchPlan;
import app.drydock.agent.api.ResumeContext;
import app.drydock.agent.api.SessionIdStrategy;
import app.drydock.agent.spi.AgentProvider;
import app.drydock.domain.ApplicationState;
import app.drydock.domain.BranchOwnership;
import app.drydock.domain.ManagedClaudeSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PrState;
import app.drydock.domain.Repository;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SshRemote;
import app.drydock.state.ApplicationStateRepository;
import app.drydock.terminal.api.TerminalHostView;
import app.drydock.terminal.api.TerminalRuntime;
import app.drydock.terminal.api.TerminalSpec;
import app.drydock.terminal.api.TerminalSurface;
import javafx.application.Platform;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

/**
 * Orchestrates creating and resuming {@link ManagedClaudeSession}s (plan
 * section 11): generates/persists session metadata via {@link
 * ApplicationStateRepository}, launches the real {@code claude} CLI inside a
 * {@link TerminalSurface}, enforces duplicate-open protection (plan section
 * 11.3), and closes sessions using {@link
 * TerminalSurface#closeGracefully(long, long, Runnable)}.
 *
 * <p><b>Threading (plan section 18):</b> {@link #createSession} and {@link
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

    private final ActiveSessionRegistry activeRegistry = new ActiveSessionRegistry();
    private final Map<ManagedSessionId, TerminalSurface> activeSurfaces = new ConcurrentHashMap<>();

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
    }

    /**
     * Plan A ships only {@link AgentKind#CLAUDE}; {@code ManagedClaudeSession}
     * gains a persisted {@code agentKind} field in a follow-up task, at which
     * point every call site below becomes {@code session.agentKind()}. Kept
     * as a single named seam so that rename is a one-line change here instead
     * of a hunt through every call site in this class.
     */
    private static AgentKind agentKindOf(ManagedClaudeSession session) {
        return AgentKind.CLAUDE;
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
     * A freshly loaded state can contain sessions persisted as {@link
     * SessionStatus#RUNNING}/{@link SessionStatus#STARTING} by a previous
     * app run (e.g. the app quit, crashed, or was killed while sessions were
     * open). No terminal process survives an app restart, so presenting
     * those statuses would show "running" indicators for processes that do
     * not exist; normalize them to {@link SessionStatus#INACTIVE} before
     * anything reads them.
     */
    private static ApplicationState normalizeLoadedState(ApplicationState loaded) {
        List<ManagedClaudeSession> normalized = loaded.sessions().stream()
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

    public List<ManagedClaudeSession> sessions() {
        return stateStore.state().sessions();
    }

    // ---- 11.1 Create a new session ----------------------------------------

    /** Creates a new session with a generated default display name (plan section 11.1). */
    public CompletableFuture<SessionOpenResult> createSession(Repository repository, TerminalRuntime app,
                                                               TerminalHostView host, double scaleFactor) {
        return createSession(repository, defaultDisplayName(repository), app, host, scaleFactor);
    }

    /**
     * Mints the metadata for a brand-new session (generated display name)
     * WITHOUT launching anything. Callers that key UI bookkeeping by
     * session id should prepare first and then {@link #launchSession}: the
     * launch persists the session almost immediately (making it visible to
     * e.g. the sidebar), and a placeholder registered under a provisional
     * id would not be found by a concurrent open of the freshly persisted
     * real id -- yielding a duplicate surface and a leaked native pair.
     */
    public ManagedClaudeSession prepareSession(Repository repository) {
        return newSessionMetadata(repository, defaultDisplayName(repository));
    }

    /** As {@link #prepareSession}, for a session living inside an already-created worktree checkout. */
    public ManagedClaudeSession prepareWorktreeSession(Repository repository, String displayName, Path worktreeRoot,
                                                        boolean branchCreatedHere) {
        return newSessionMetadata(repository, displayName, Optional.of(worktreeRoot), branchCreatedHere);
    }

    /** Launches a session minted by {@link #prepareSession}/{@link #prepareWorktreeSession}. */
    public CompletableFuture<SessionOpenResult> launchSession(ManagedClaudeSession prepared, TerminalRuntime app,
                                                              TerminalHostView host, double scaleFactor) {
        return launchNewSession(prepared, prepared.displayName(), app, host, scaleFactor);
    }

    /**
     * Creates a new session with an explicit display name (plan section
     * 11.1's "allow immediate renaming" -- a caller can generate its own
     * name, or rename after the fact via {@link #renameSession}, which this
     * step does not itself provide UI for).
     */
    public CompletableFuture<SessionOpenResult> createSession(Repository repository, String displayName,
                                                               TerminalRuntime app, TerminalHostView host,
                                                               double scaleFactor) {
        return launchNewSession(newSessionMetadata(repository, displayName), displayName, app, host, scaleFactor);
    }

    private CompletableFuture<SessionOpenResult> launchNewSession(ManagedClaudeSession initial, String displayName,
                                                                  TerminalRuntime app, TerminalHostView host,
                                                                  double scaleFactor) {
        AgentKind kind = agentKindOf(initial);
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

        // Metadata persistence is disk I/O; keep it off the (FX) caller thread.
        return CompletableFuture.runAsync(() -> persistNewSession(initial), backgroundExecutor)
                .thenCompose(ignored -> buildAndLaunchCreate(provider, displayName, sessionId,
                        initial.workingDirectory(), remote, app, host, scaleFactor, workingDir))
                .handleAsync((launch, ex) -> finalizeCreate(initial, sessionId, launch, ex), backgroundExecutor);
    }

    /**
     * Builds a provider's create command (on the background executor -- it
     * may block on capability probing) and, once built, opens the resulting
     * {@link TerminalSurface} on the FX thread. Shared by {@link
     * #launchNewSession} and {@link #startFreshConversation}, the two paths
     * that mint a brand-new agent conversation.
     */
    private CompletableFuture<CreateLaunch> buildAndLaunchCreate(AgentProvider provider, String displayName,
                                                                  String sessionId, Path targetWorkingDirectory,
                                                                  Optional<SshRemote> remote, TerminalRuntime app,
                                                                  TerminalHostView host, double scaleFactor,
                                                                  String surfaceWorkingDirectory) {
        return CompletableFuture.supplyAsync(() -> {
                    CreateContext ctx = new CreateContext(displayName, sessionId, targetWorkingDirectory, remote);
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

    private SessionOpenResult finalizeCreate(ManagedClaudeSession initial, String claudeSessionId,
                                              CreateLaunch launch, Throwable ex) {
        if (ex != null) {
            Throwable cause = unwrap(ex);
            LOG.log(Level.WARNING, () -> "Failed to start session " + initial.id() + ": " + cause.getMessage());
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
        ManagedClaudeSession running = initial.withStatus(SessionStatus.RUNNING).withLastOpenedAt(Instant.now());
        // Only persist the Claude session id if the launch command actually
        // used it -- persisting an id claude never saw would make a later
        // resume target a nonexistent conversation.
        if (launch.plan().sessionIdUsed()) {
            running = running.withClaudeSessionId(Optional.of(claudeSessionId));
            activeRegistry.tryMarkActive(claudeSessionId, running.id());
        }
        persistUpdatedSession(running);
        activeSurfaces.put(running.id(), launch.surface());
        return new SessionOpenResult.Opened(running, launch.surface());
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
                    ManagedClaudeSession session = requireSession(sessionId);
                    AgentKind kind = agentKindOf(session);
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
                                ResumeContext ctx = new ResumeContext(session.claudeSessionId(),
                                        session.claudeSessionName(), session.workingDirectory(), remote);
                                return provider.buildResumeCommand(ctx).command();
                            }, backgroundExecutor)
                            .thenCompose(command -> createSurfaceOnFxThread(app, host, scaleFactor, command,
                                    workingDir)
                                    .handleAsync((surface, ex) -> finalizeResume(session, surface, ex),
                                            backgroundExecutor));
                });
    }

    private SessionOpenResult finalizeResume(ManagedClaudeSession session, TerminalSurface surface, Throwable ex) {
        if (ex != null) {
            Throwable cause = unwrap(ex);
            LOG.log(Level.WARNING, () -> "Failed to resume session " + session.id() + ": " + cause.getMessage());
            persistUpdatedSession(session.withStatus(SessionStatus.FAILED));
            throw wrap(cause);
        }
        ManagedClaudeSession running = session.withStatus(SessionStatus.RUNNING).withLastOpenedAt(Instant.now());
        persistUpdatedSession(running);
        activeSurfaces.put(running.id(), surface);
        session.claudeSessionId().ifPresent(claudeId -> activeRegistry.tryMarkActive(claudeId, running.id()));
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
        ManagedClaudeSession session = requireSession(sessionId);

        Optional<String> claudeSessionId = session.claudeSessionId();
        if (claudeSessionId.isPresent()) {
            Optional<ManagedSessionId> active = activeRegistry.activeSessionId(claudeSessionId.get());
            if (active.isPresent() && !active.get().equals(sessionId)) {
                TerminalSurface activeSurface = activeSurfaces.get(active.get());
                if (activeSurface != null) {
                    return Optional.of(new SessionOpenResult.AlreadyOpen(session, active.get(), activeSurface));
                }
            }
        }

        boolean remoteSession = repositoryFor(session).map(Repository::isRemote).orElse(false);

        if (!remoteSession && Files.notExists(session.workingDirectory())) {
            ManagedClaudeSession missing = session.withStatus(SessionStatus.MISSING_WORKING_DIRECTORY);
            persistUpdatedSession(missing);
            return Optional.of(new SessionOpenResult.MissingWorkingDirectory(missing));
        }

        // A pinned conversation id whose transcript the agent no longer has
        // on disk would make a resume-by-id exit immediately with "No
        // conversation found" -- detect it up front via the provider's
        // ConversationSource so the UI can offer a fresh start or deletion
        // instead of presenting a dead terminal.
        if (!remoteSession && claudeSessionId.isPresent()) {
            boolean missing = registry.conversations(agentKindOf(session))
                    .map(cs -> !cs.transcriptExists(session.workingDirectory(), claudeSessionId.get()))
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
                    ManagedClaudeSession cleared = requireSession(sessionId).withClaudeSessionId(Optional.empty());
                    persistUpdatedSession(cleared);
                    return cleared;
                }, backgroundExecutor)
                .thenCompose(cleared -> {
                    AgentKind kind = agentKindOf(cleared);
                    AgentProvider provider = registry.provider(kind)
                            .orElseThrow(() -> new IllegalStateException("No provider for " + kind));
                    Optional<SshRemote> remote = remoteFor(repositoryFor(cleared));
                    String freshSessionId = provider.idStrategy() == SessionIdStrategy.PRESET
                            ? UUID.randomUUID().toString()
                            : "";
                    String workingDir = remote.isPresent()
                            ? System.getProperty("user.home")
                            : cleared.workingDirectory().toString();
                    return buildAndLaunchCreate(provider, cleared.displayName(), freshSessionId,
                            cleared.workingDirectory(), remote, app, host, scaleFactor, workingDir)
                            .handleAsync((launch, ex) -> finalizeCreate(cleared, freshSessionId, launch, ex),
                                    backgroundExecutor);
                });
    }

    /** Explicitly reassigns a session's working directory (plan section 11.2), e.g. after the user picks a replacement. */
    public ManagedClaudeSession reassignWorkingDirectory(ManagedSessionId sessionId, Path newWorkingDirectory) {
        Path normalized = newWorkingDirectory.toAbsolutePath().normalize();
        return updateSession(sessionId,
                session -> session.withWorkingDirectory(normalized).withStatus(SessionStatus.INACTIVE));
    }

    public ManagedClaudeSession renameSession(ManagedSessionId sessionId, String newDisplayName) {
        return updateSession(sessionId, session -> session.withDisplayName(newDisplayName));
    }

    /** Records the observed PR lifecycle state of a worktree session's branch (Finish-panel reconciliation). */
    public ManagedClaudeSession updatePrState(ManagedSessionId sessionId, PrState prState,
                                              Optional<Integer> prNumber) {
        return updateSession(sessionId, session -> session.withPr(prState, prNumber));
    }

    /**
     * Registers an existing on-disk Claude conversation (discovered by the
     * resume picker in {@code ~/.claude/projects}) as a managed session, so
     * the normal {@link #resumeSession} path can reopen that exact
     * conversation via {@code claude --resume '<id>'}. Idempotent per
     * Claude session id: if a managed session already tracks {@code
     * claudeSessionId}, that session is returned unchanged instead of
     * creating a duplicate row.
     */
    public ManagedClaudeSession adoptConversation(Repository repository, String claudeSessionId,
                                                  String displayName) {
        ManagedClaudeSession[] result = new ManagedClaudeSession[1];
        stateStore.update(state -> {
            Optional<ManagedClaudeSession> existing = state.sessions().stream()
                    .filter(session -> session.claudeSessionId().map(claudeSessionId::equals).orElse(false))
                    .findFirst();
            if (existing.isPresent()) {
                result[0] = existing.get();
                return state;
            }
            ManagedClaudeSession adopted = newSessionMetadata(repository, displayName)
                    .withClaudeSessionId(Optional.of(claudeSessionId));
            result[0] = adopted;
            List<ManagedClaudeSession> updated = new ArrayList<>(state.sessions());
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
                () -> stateStore.update(state -> state.withSessions(state.sessions().stream()
                        .filter(session -> !session.id().equals(sessionId))
                        .toList())),
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
     * @return the updated session, or empty if the session no longer exists
     *         or was not RUNNING
     */
    public Optional<ManagedClaudeSession> markSessionExited(ManagedSessionId sessionId) {
        ManagedClaudeSession[] result = new ManagedClaudeSession[1];
        stateStore.update(state -> {
            Optional<ManagedClaudeSession> running = state.sessions().stream()
                    .filter(session -> session.id().equals(sessionId))
                    .filter(session -> session.status() == SessionStatus.RUNNING)
                    .findFirst();
            if (running.isEmpty()) {
                return state;
            }
            ManagedClaudeSession updated = running.get().withStatus(SessionStatus.EXITED);
            result[0] = updated;
            return withReplacedSession(state, updated);
        });
        return Optional.ofNullable(result[0]);
    }

    private void onSurfaceClosed(ManagedSessionId sessionId, TerminalSurface surface) {
        activeSurfaces.remove(sessionId, surface);
        findSession(sessionId).ifPresent(session -> {
            session.claudeSessionId().ifPresent(activeRegistry::release);
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

    private ManagedClaudeSession newSessionMetadata(Repository repository, String displayName) {
        return newSessionMetadata(repository, displayName, Optional.empty(), true);
    }

    /**
     * When {@code worktreeRoot} is present the session lives (and launches
     * claude) inside that worktree checkout rather than the repository's
     * main checkout -- {@code workingDirectory} IS the worktree directory.
     * {@code branchCreatedHere} records whether drydock minted the branch,
     * and so whether it may later delete it; it has no safe default, which
     * is why every worktree caller must state it.
     */
    private ManagedClaudeSession newSessionMetadata(Repository repository, String displayName,
                                                    Optional<Path> worktreeRoot, boolean branchCreatedHere) {
        Instant now = Instant.now();
        return new ManagedClaudeSession(
                ManagedSessionId.newId(),
                repository.id(),
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
                branchCreatedHere);
    }

    /**
     * Whether the branch of the worktree at {@code worktreeRoot} may be
     * force-deleted along with it -- true only when a session records that
     * this application created that branch. See {@link BranchOwnership}.
     */
    public boolean mayDeleteBranchOf(Path worktreeRoot) {
        return BranchOwnership.mayDeleteBranchOf(sessions(), worktreeRoot);
    }

    private void persistNewSession(ManagedClaudeSession session) {
        stateStore.update(state -> {
            List<ManagedClaudeSession> updated = new ArrayList<>(state.sessions());
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
    private void persistUpdatedSession(ManagedClaudeSession updatedSession) {
        stateStore.update(state -> withReplacedSession(state, updatedSession));
    }

    /** Applies the atomic find-and-change-one-session pattern shared by the metadata mutators. */
    private ManagedClaudeSession updateSession(ManagedSessionId sessionId,
                                               UnaryOperator<ManagedClaudeSession> change) {
        ManagedClaudeSession[] result = new ManagedClaudeSession[1];
        stateStore.update(state -> {
            ManagedClaudeSession session = state.sessions().stream()
                    .filter(existing -> existing.id().equals(sessionId))
                    .findFirst()
                    .orElseThrow(() -> new UnknownSessionException(sessionId));
            result[0] = change.apply(session);
            return withReplacedSession(state, result[0]);
        });
        return result[0];
    }

    private static ApplicationState withReplacedSession(ApplicationState state, ManagedClaudeSession updatedSession) {
        return state.withSessions(state.sessions().stream()
                .map(existing -> existing.id().equals(updatedSession.id()) ? updatedSession : existing)
                .toList());
    }

    private ManagedClaudeSession requireSession(ManagedSessionId sessionId) {
        return findSession(sessionId).orElseThrow(() -> new UnknownSessionException(sessionId));
    }

    private Optional<ManagedClaudeSession> findSession(ManagedSessionId sessionId) {
        return stateStore.state().sessions().stream()
                .filter(session -> session.id().equals(sessionId))
                .findFirst();
    }

    private Optional<Repository> repositoryFor(ManagedClaudeSession session) {
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
