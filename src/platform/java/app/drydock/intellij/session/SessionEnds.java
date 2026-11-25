package app.drydock.intellij.session;

import app.drydock.agent.api.AgentRegistry;
import app.drydock.agent.api.SessionIdDiscovery;
import app.drydock.agent.api.SessionIdStrategy;
import app.drydock.agent.spi.AgentProvider;
import app.drydock.app.ApplicationStateStore;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SshRemote;
import app.drydock.domain.WorkspaceBoxBinding;
import app.drydock.intellij.DrydockSessionsListener;
import app.drydock.intellij.agent.AgentExecutionListener;
import app.drydock.intellij.agent.AgentExecutionRegistry;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.agent.LaunchClaims;
import app.drydock.intellij.remote.RemoteReviewService;
import app.drydock.intellij.remote.RemoteReviewService.ReleaseOutcome;
import app.drydock.intellij.review.SessionMcpAccess;
import app.drydock.intellij.workspace.RemoteSessionLiveness;
import app.drydock.sdlc.SessionWorkflowResourceOwner;
import app.drydock.workspace.BoxShell;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
/**
 * The end-of-agent half of {@link DrydockSessionService}: an agent's end
 * persisted as its row going idle, the late binding of a DISCOVERED agent's
 * session id, and the id bookkeeping the launch flow shares with it.
 *
 * <p>Split out so it can outlive the window. {@code AgentExecutionRegistry}
 * keeps one of these for every agent it runs and calls it when the agent
 * ends — possibly long after the window that started it closed with "Keep
 * running". It therefore holds only application-level state (the state store,
 * the agent registry, the plugin executor), never the window's console host or
 * project, which the service around it does hold.</p>
 *
 * <p>For a <em>remote</em> row (one bound to a workspace box) the process
 * that ends is this IDE's ssh client, not the agent: the agent runs on in
 * the box's tmux session. Its end therefore settles the row from one
 * {@code tmux has-session} probe on the executor (AC-8) — never from the
 * client's exit code — and records the detached liveness in {@link
 * RemoteSessionLiveness}; the agent itself is never touched (INV-10: no
 * kill-session, no input, only the probe).</p>
 */
final class SessionEnds implements AgentExecutionListener {

    private static final Logger LOG = System.getLogger(SessionEnds.class.getName());

    private final Supplier<ApplicationStateStore> stateStore;
    private final Supplier<AgentRegistry> registry;
    private final ExecutorService executor;
    private final SessionMcpAccess mcpAccess;
    private final LaunchClaims claims;
    /** The ssh seam the remote settle's one has-session probe runs through. */
    private final BoxShell boxShell;
    /** The detached-remote liveness map the settle writes. */
    private final Supplier<RemoteSessionLiveness> liveness;
    /** The requested-client-end coordination the end settle classifies an end by. */
    private final AgentExecutionRegistry.RemoteClientEnds clientEnds;
    /** The application's retained workflow resources; resolved lazily, may yield null (no IDE application). */
    private final Supplier<SessionWorkflowResourceOwner> workflowResources;
    /** The application's remote review transport owner; resolved lazily, may yield null (none built yet). */
    private final Supplier<RemoteReviewService> remoteReview;

    /**
     * Agent session ids already bound to a persisted session, or claimed by
     * a launch's discovery, so post-launch discovery never re-binds an id
     * that belongs to another session (the {@code claimedAgentSessionIds}
     * pattern from {@code SessionManager}). Seeded from the persisted state
     * once, on the first launch — production construction races the
     * application service's init, so seeding cannot happen in a constructor.
     */
    private final Set<String> claimedAgentSessionIds = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean claimedIdsSeeded = new AtomicBoolean();
    /**
     * When each session's agent was last launched by this IDE run. The late
     * id bind matches the agent's own record against this, not the row's
     * creation: a row started again (Start after a stop) gets a record
     * timestamped at the new launch, minutes or days after the row was made.
     * Memory only; after an IDE restart the bind falls back to the creation
     * time, as before.
     */
    private final Map<ManagedSessionId, Instant> lastLaunchedAt = new ConcurrentHashMap<>();

    /**
     * A remote settle that a launch's own claim suppressed while its ssh
     * client was ending — deferred, not discarded, and re-applied when that
     * claim ends (see {@link #settleDeferredRemoteEnd}).
     */
    private record DeferredRemoteSettle(SessionStatus status, RemoteSessionLiveness.State liveness) { }

    /** The deferred remote settles, keyed by session; memory only. */
    private final Map<ManagedSessionId, DeferredRemoteSettle> deferredRemoteSettles = new ConcurrentHashMap<>();

    /**
     * The full seam: the remote settle's three injected components — the box
     * ssh shell behind the has-session probe, the liveness map its answer is
     * recorded in, and the requested-client-end coordination an end settles
     * by. The service hands its own injected shell and liveness map through,
     * so a service test's fakes drive its end settles too; tests inject
     * fakes here directly so no test ever spawns a child or needs an IDE.
     */
    SessionEnds(Supplier<ApplicationStateStore> stateStore, Supplier<AgentRegistry> registry,
                ExecutorService executor, SessionMcpAccess mcpAccess,
                LaunchClaims claims, BoxShell boxShell,
                Supplier<RemoteSessionLiveness> liveness,
                AgentExecutionRegistry.RemoteClientEnds clientEnds) {
        this(stateStore, registry, executor, mcpAccess, claims, boxShell, liveness, clientEnds,
                SessionEnds::pluginWorkflowResources);
    }

    /** As above, with the retained-resource owner a session removal releases through (tests inject one). */
    SessionEnds(Supplier<ApplicationStateStore> stateStore, Supplier<AgentRegistry> registry,
                ExecutorService executor, SessionMcpAccess mcpAccess,
                LaunchClaims claims, BoxShell boxShell,
                Supplier<RemoteSessionLiveness> liveness,
                AgentExecutionRegistry.RemoteClientEnds clientEnds,
                Supplier<SessionWorkflowResourceOwner> workflowResources) {
        this(stateStore, registry, executor, mcpAccess, claims, boxShell, liveness, clientEnds, workflowResources,
                SessionEnds::pluginRemoteReview);
    }

    /** As above, with the remote transport owner each end outcome routes to (tests inject one). */
    SessionEnds(Supplier<ApplicationStateStore> stateStore, Supplier<AgentRegistry> registry,
                ExecutorService executor, SessionMcpAccess mcpAccess,
                LaunchClaims claims, BoxShell boxShell,
                Supplier<RemoteSessionLiveness> liveness,
                AgentExecutionRegistry.RemoteClientEnds clientEnds,
                Supplier<SessionWorkflowResourceOwner> workflowResources,
                Supplier<RemoteReviewService> remoteReview) {
        this.remoteReview = Objects.requireNonNull(remoteReview, "remoteReview");
        this.workflowResources = Objects.requireNonNull(workflowResources, "workflowResources");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.mcpAccess = Objects.requireNonNull(mcpAccess, "mcpAccess");
        this.claims = Objects.requireNonNull(claims, "claims");
        this.boxShell = Objects.requireNonNull(boxShell, "boxShell");
        this.liveness = Objects.requireNonNull(liveness, "liveness");
        this.clientEnds = Objects.requireNonNull(clientEnds, "clientEnds");
    }

    private static SessionWorkflowResourceOwner pluginWorkflowResources() {
        try {
            return DrydockPluginService.getInstance().workflowResources();
        } catch (RuntimeException e) {
            return null;   // no IDE application (plain unit tests): nothing retained to release
        }
    }

    private static RemoteReviewService pluginRemoteReview() {
        try {
            return DrydockPluginService.getInstance().remoteReview().orElse(null);
        } catch (RuntimeException e) {
            return null;   // no IDE application, or the remote transport is not built
        }
    }

    /**
     * Routes one end outcome to the remote transport owner on the executor (the release closes a process
     * and revokes credentials: blocking). {@code DETACH} keeps the transport; DEATH/STOP/FORGET tear down
     * only this session's. Never fails the caller; a failure is logged. A shut-down executor skips it:
     * the owner's own close settles every transport at application shutdown. Tmux is never touched here.
     */
    private CompletableFuture<Void> releaseRemote(ManagedSessionId id, String host, ReleaseOutcome outcome) {
        RemoteReviewService service = remoteReview.get();
        if (service == null) {
            return CompletableFuture.completedFuture(null);
        }
        RemoteReviewService.Session target = new RemoteReviewService.Session(host, "", id, "", "", 0, "",
                () -> null);
        try {
            return CompletableFuture.runAsync(() -> service.release(target, outcome), executor)
                    .exceptionally(e -> {
                        LOG.log(Level.WARNING, () -> "Releasing the remote transport of " + id + " (" + outcome
                                + ") failed: " + e);
                        return null;
                    });
        } catch (RejectedExecutionException e) {
            LOG.log(Level.DEBUG, "Remote transport of {0} stays until shutdown: the executor is closed", id);
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * The session row was removed (explicit Forget): releases this session's retained workflow resources,
     * local first with remote cleanup debt recorded by the owner, off the calling thread. This is the ONLY
     * release path: terminal detach, console close, {@link #executionTerminated} and Stop never reach the
     * owner (INV-33). Never fails the caller's removal; a failure is logged and the journal keeps the entry.
     */
    CompletableFuture<Void> sessionRemoved(ManagedSessionId id) {
        Objects.requireNonNull(id, "id");
        // The local revocation of the remote transport never depends on the retained-resource cleanup (a
        // box deletion that fails leaves a debt there, not a live token here), and vice versa.
        CompletableFuture<Void> transport = releaseRemote(id, "", ReleaseOutcome.FORGET);
        return transport.thenCompose(done -> releaseWorkflowResources(id));
    }

    private CompletableFuture<Void> releaseWorkflowResources(ManagedSessionId id) {
        SessionWorkflowResourceOwner owner = workflowResources.get();
        if (owner == null) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            return CompletableFuture.supplyAsync(() -> owner.deleteSession(id.toString()), executor)
                    .thenCompose(done -> done)
                    .exceptionally(e -> {
                        LOG.log(Level.WARNING, () -> "Releasing workflow resources for " + id + " failed: " + e);
                        return null;
                    });
        } catch (RejectedExecutionException e) {
            LOG.log(Level.WARNING, "Workflow resources of {0} stay retained: the executor is shutting down", id);
            return CompletableFuture.completedFuture(null);
        }
    }

    Set<String> claimedAgentSessionIds() {
        return claimedAgentSessionIds;
    }

    Map<ManagedSessionId, Instant> lastLaunchedAt() {
        return lastLaunchedAt;
    }

    /** Fills {@link #claimedAgentSessionIds} from the persisted rows, once. */
    void seedClaimedIdsOnce() {
        if (!claimedIdsSeeded.compareAndSet(false, true)) {
            return;
        }
        for (ManagedAgentSession session : stateStore.get().state().sessions()) {
            session.agentSessionId().ifPresent(claimedAgentSessionIds::add);
        }
    }

    /**
     * The session's process ended; the row goes idle and stays. See
     * {@link DrydockSessionService#executionTerminated}.
     *
     * <p>First, unless a live execution or a launch claim holds the session
     * (see below), the session's Drydock MCP access is released, inline on the
     * thread that delivered the process event (which does the credential file
     * deletes). It is not queued: minting is idempotent per session, so a
     * queued release could revoke the token of an immediate Resume.</p>
     *
     * <p>The release is skipped while the session has a running execution or
     * a launch that holds its claim in {@link LaunchClaims}. A launch claims
     * before it mints, and this check and the release run under the claims'
     * lock, so a launch either claims after the release and mints a fresh
     * token, or claimed before the check and keeps its token. A skipped
     * release is done when that launch's claim ends with no live execution,
     * or by the end of the execution that is running.</p>
     */
    @Override
    public void executionTerminated(ManagedSessionId id, boolean stopRequested, Integer exitCode) {
        Objects.requireNonNull(id, "id");
        // A remote row's "process" is this IDE's ssh client to the box, not
        // the agent — the agent runs on in the box's tmux session. Its end
        // settles the row from a probe instead of the exit-code story, so
        // the two halves diverge here, before anything is released. The
        // late id bind is skipped with it: a remote row carries a PRESET
        // id and no local transcript to discover one from (INV-14), so the
        // local half's schedule-late-bind has nothing to do for it.
        ManagedAgentSession row = DrydockSessionService.findIn(stateStore.get().state(), id);
        if (row != null && row.workspaceBox().isPresent()) {
            settleRemoteWhenClientEnds(id, row.workspaceBox().get(), stopRequested);
            return;
        }
        if (row != null && row.workspaceBox().isEmpty()
                && DrydockSessionService.repositoryRemoteOf(stateStore.get().state(), row).isPresent()) {
            // An ssh-remote row's ssh client ended. The exit code is the ssh
            // client's, never the agent's, so the settle records the status
            // alone; a spontaneous end is "session ended — resume to
            // reconnect" (the degraded contract), and a stop or close
            // requested the end itself. No probe: the agent does not outlive
            // its ssh client the way a box-side agent outlives one — the
            // SIGHUP the client's end delivers is the stop.
            settleSshRemoteEnd(id, stopRequested ? SessionStatus.INACTIVE : SessionStatus.EXITED, row);
            return;
        }
        try {
            claims.releaseIfIdle(id, () -> mcpAccess.release(id));
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, () -> "Releasing Drydock MCP access for " + id + " failed: " + e);
        }
        SessionStatus ended = stopRequested ? SessionStatus.INACTIVE : SessionStatus.EXITED;
        stateStore.get().update(state -> {
            ManagedAgentSession existing = DrydockSessionService.findIn(state, id);
            if (existing == null || existing.status() == ended) {
                return state;
            }
            ManagedAgentSession idle = existing.withStatus(ended).withLastOpenedAt(Instant.now());
            if (exitCode != null) {
                idle = idle.withLastExitCode(Optional.of(exitCode));
            }
            return DrydockSessionService.withReplacedSession(state, idle);
        });
        DrydockSessionsListener.publishChanged();
        suspendEngagementsOf(id);
        // The agent is gone; its eval side channel (e.g. Claude's proxy
        // marking) must not outlive it. Best-effort, off this thread — and
        // re-checked at execution time (below), so a resume that re-marked
        // the session in the meantime is never unmarked by a stale end.
        unmarkEvalAsync(row, ended);
        scheduleLateBind(id);
    }

    /**
     * Reverses an eval row's out-of-band marking on the executor; the
     * shared implementation lives in {@link EvalMarks}. The end may race a
     * Resume: the row can be re-claimed and re-marked between this end and
     * the queued task's run, so the task re-reads the row and unmarks only
     * when it still sits in the state this end settled it to — never under
     * a live or starting session.
     */
    private void unmarkEvalAsync(ManagedAgentSession row, SessionStatus ended) {
        if (row == null || !row.evalMode()) {
            return;
        }
        String key = row.agentSessionId().orElse("");
        if (key.isBlank()) {
            return;
        }
        try {
            executor.execute(() -> {
                ManagedAgentSession current = DrydockSessionService.findIn(stateStore.get().state(), row.id());
                if (current == null || current.status() != ended) {
                    return;
                }
                EvalMarks.unmarkAsync(registry, executor, row, key);
            });
        } catch (RejectedExecutionException e) {
            // Shutdown drained the executor; the proxy's mark is in-memory
            // and dies with the proxy's own restart.
            LOG.log(Level.DEBUG, "Skipping eval unmark for " + row.id() + " during shutdown");
        }
    }

    // ---- remote settle (ssh client ended without an explicit Stop) ----------

    /**
     * Settles an ssh-remote row whose ssh client just ended: the status
     * alone, no exit code (the client's exit code is not the agent's), no
     * liveness record and no probe — unlike a box row, the agent does not
     * outlive its ssh client, so there is nothing to probe and the SIGHUP
     * the client's end delivers <em>is</em> the stop. No late bind: the
     * conversation lives on the remote host and no local transcript can
     * name an id for it.
     */
    private void settleSshRemoteEnd(ManagedSessionId id, SessionStatus ended, ManagedAgentSession row) {
        try {
            claims.releaseIfIdle(id, () -> mcpAccess.release(id));
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, () -> "Releasing Drydock MCP access for " + id + " failed: " + e);
        }
        stateStore.get().update(state -> {
            ManagedAgentSession existing = DrydockSessionService.findIn(state, id);
            if (existing == null || existing.status() == ended) {
                return state;
            }
            return DrydockSessionService.withReplacedSession(state,
                    existing.withStatus(ended).withLastOpenedAt(Instant.now()));
        });
        DrydockSessionsListener.publishChanged();
        // Unlike a box row, the agent does not outlive its ssh client: the
        // SIGHUP the client's end delivers is the stop, so this end is the
        // watched session's deactivation however it came about.
        suspendEngagementsOf(id);
        unmarkEvalAsync(row, ended);
    }

    /**
     * The ended session's babysitting engagements pause ({@link
     * BabysitMonitorService#suspendEngagementsOfStopped}): their pairings
     * are marked SUSPENDED and the babysitters' agents are stopped. The
     * quit exclusion rides the static cascade -- the detach handover, not
     * suspension, governs engagements at quit.
     */
    private void suspendEngagementsOf(ManagedSessionId id) {
        BabysitMonitorService.suspendEngagementsOfStopped(id);
    }

    /**
     * Settles a remote row whose ssh client just ended. First the end is
     * classified: one this IDE requested — an explicit Stop or a close path,
     * per the coordination mark — settles by its origin, with no probe; only
     * an end nobody requested runs the AC-8 probe. Nothing is released first
     * — a remote launch never mints Drydock MCP access (INV-15), so unlike
     * the local half there is no token to hand back — and the agent on the
     * box is not touched in any way (INV-10): no kill-session, no input,
     * only the one has-session probe a spontaneous end runs. The probe is a
     * blocking ssh round trip, so it rides the executor (INV-8), never the
     * thread that delivered the process event.
     */
    private void settleRemoteWhenClientEnds(ManagedSessionId id, WorkspaceBoxBinding box,
                                             boolean stopRequested) {
        Optional<AgentExecutionRegistry.RemoteClientEnds.RequestedEnd> requested = clientEnds.requestedEnd(id);
        if (requested.isPresent()) {
            settleRequestedRemoteEnd(id, requested.get());
            return;
        }
        try {
            CompletableFuture.runAsync(() -> probeAndSettleRemote(id, box, stopRequested), executor);
        } catch (RejectedExecutionException e) {
            // Quitting: the executor is drained, so no probe will ever run.
            // The row still settles — IDLE, the status of a session this IDE
            // does not own — but the liveness map is left alone: without a
            // probe there is no ALIVE/DEAD answer to record, and UNKNOWN
            // would be a claim nothing earned.
            LOG.log(Level.DEBUG,
                    "Settling {0} as IDLE without a liveness probe; the executor is shutting down", id);
            settleRemote(id, SessionStatus.IDLE, null);
        }
    }

    /**
     * Settles an end this IDE requested, by its origin and — for an explicit
     * Stop — by the kill's own verdict, never by a probe:
     * <ul>
     *   <li><strong>Close</strong> (tab-close/window-close/quit/`hostClosed`/
     *   `dispose` ends of the client): the durable row settles IDLE, the
     *   status of a session this IDE does not own, and neither EXITED nor
     *   INACTIVE is persisted for it (AC-7). No liveness is recorded — no
     *   probe ran, so the last known answer stands.</li>
     *   <li><strong>Explicit Stop, kill concluded</strong>: the kill's verdict
     *   settles — confirmed (exit 0, "can't find session") → INACTIVE with
     *   liveness DEAD; anything else → IDLE with UNKNOWN. The Stop flow
     *   settles the same verdict itself when it can; this is the path for
     *   the end that overtook the flow's own settle (suppressed while the
     *   dying client was still the live execution the claim guard sees).</li>
     *   <li><strong>Explicit Stop, kill still in flight</strong>: nothing.
     *   The ssh client ended during the kill round-trip, and the Stop flow
     *   holds the settle — a probe racing the kill's reply is exactly the
     *   competition that could overwrite a confirmed outcome (AC-9).</li>
     * </ul>
     */
    private void settleRequestedRemoteEnd(ManagedSessionId id,
                                           AgentExecutionRegistry.RemoteClientEnds.RequestedEnd requested) {
        if (requested.origin() == AgentExecutionRegistry.RemoteClientEnds.Origin.CLOSE) {
            settleRemote(id, SessionStatus.IDLE, null);
            releaseRemote(id, "", ReleaseOutcome.DETACH);   // console close is a detach, never a death
            return;
        }
        if (requested.killConfirmed().isEmpty()) {
            return;
        }
        boolean killConfirmed = requested.killConfirmed().get();
        releaseRemote(id, "", killConfirmed ? ReleaseOutcome.STOP : ReleaseOutcome.DETACH);
        settleRemote(id, killConfirmed ? SessionStatus.INACTIVE : SessionStatus.IDLE,
                killConfirmed ? RemoteSessionLiveness.State.DEAD : RemoteSessionLiveness.State.UNKNOWN);
    }

    /**
     * The probe, then the settle it dictates (AC-8): ALIVE → the row goes
     * IDLE — the agent runs on, detached; this IDE no longer owns it —
     * with liveness ALIVE; DEAD → today's EXITED settle, or INACTIVE when a
     * Stop was requested, <em>without</em> {@code lastExitCode}: the code
     * this event carries is the ssh client's, not the agent's; anything
     * else → IDLE with liveness UNKNOWN, WARNING logged with the probe's
     * failure text (BoxShell has already logged the probe's own WARNING;
     * this line carries the settle's consequence).
     */
    private void probeAndSettleRemote(ManagedSessionId id, WorkspaceBoxBinding box, boolean stopRequested) {
        BoxShell.Liveness verdict;
        try {
            verdict = boxShell.hasSession(box.sshRemote().host(), id);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.log(Level.WARNING, () -> "Remote settle for " + id + " on " + box.sshRemote().host()
                    + " was interrupted; the row stays IDLE and resumable");
            settleRemote(id, SessionStatus.IDLE, RemoteSessionLiveness.State.UNKNOWN);
            return;
        }
        switch (verdict) {
            case BoxShell.Liveness.Alive ignored -> {
                settleRemote(id, SessionStatus.IDLE, RemoteSessionLiveness.State.ALIVE);
                releaseRemote(id, box.sshRemote().host(), ReleaseOutcome.DETACH);
            }
            case BoxShell.Liveness.Dead ignored -> {
                settleRemote(id, stopRequested ? SessionStatus.INACTIVE : SessionStatus.EXITED,
                        RemoteSessionLiveness.State.DEAD);
                releaseRemote(id, box.sshRemote().host(), ReleaseOutcome.DEATH);
                // The probe says the box-side agent is gone: this really is
                // the watched session's death, however unattended, so its
                // engagements pause like a local end's would.
                suspendEngagementsOf(id);
            }
            case BoxShell.Liveness.Unknown unknown -> {
                releaseRemote(id, box.sshRemote().host(), ReleaseOutcome.DETACH);
                LOG.log(Level.WARNING, () -> "Remote settle for " + id + " on " + box.sshRemote().host()
                        + " could not conclude (" + unknown.reason() + "); the row stays IDLE and resumable");
                settleRemote(id, SessionStatus.IDLE, RemoteSessionLiveness.State.UNKNOWN);
            }
        }
    }

    /** How a remote settle ended: what, if anything, its transform did. */
    private enum RemoteSettleOutcome {
        /** The row's status was written (or already was the settle's). */
        LANDED,
        /** The row is gone or local: nothing to settle. */
        ROW_GONE,
        /** A live execution holds the session. */
        LIVE_EXECUTION,
        /** A launch claim holds the session — the settle is deferred. */
        CLAIM
    }

    /**
     * Persists the remote row's settle status and records its liveness. A
     * null {@code livenessState} (the quit-time settle and the close settle,
     * which have no probe answer) leaves the liveness map untouched. The
     * guard runs inside the transform, so it is atomic with the write against
     * every other writer through the store's single lock: the settle is a
     * no-op when the row is gone (INV-21), and it is <em>deferred</em> — not
     * discarded — when a launch claim holds the session. A claim-held settle
     * is the early end of the launch's own ssh client (a remote command that
     * failed immediately, before the launch's finalization released its
     * claim): deferring it means the claim's end re-applies it, so a
     * completed launch never leaves RUNNING persisted when this IDE owns
     * no live ssh execution (INV-4). A settle suppressed by a live
     * execution — not a claim — is discarded as before: the execution's own
     * end settles when it comes.
     */
    private void settleRemote(ManagedSessionId id, SessionStatus status, RemoteSessionLiveness.State livenessState) {
        RemoteSettleOutcome[] outcome = {RemoteSettleOutcome.LANDED};
        stateStore.get().update(state -> {
            ManagedAgentSession existing = DrydockSessionService.findIn(state, id);
            if (existing == null || existing.workspaceBox().isEmpty()) {
                outcome[0] = RemoteSettleOutcome.ROW_GONE;
                return state;
            }
            if (!sessionIdle(id)) {
                outcome[0] = clientEnds.hasLiveExecution(id)
                        ? RemoteSettleOutcome.LIVE_EXECUTION
                        : RemoteSettleOutcome.CLAIM;
                return state;
            }
            if (existing.status() == status) {
                return state;
            }
            return DrydockSessionService.withReplacedSession(state,
                    existing.withStatus(status).withLastOpenedAt(Instant.now()));
        });
        switch (outcome[0]) {
            case ROW_GONE, LIVE_EXECUTION -> {
                // Nothing landed: no liveness is recorded and nothing is
                // published — the row is gone, or a live execution owns it.
            }
            case CLAIM -> deferredRemoteSettles.put(id, new DeferredRemoteSettle(status, livenessState));
            case LANDED -> {
                if (livenessState != null) {
                    recordLiveness(id, livenessState);
                }
                DrydockSessionsListener.publishChanged();
            }
        }
    }

    /**
     * Re-applies the remote settle a launch's own claim deferred, when that
     * claim ends (the service's {@code endClaim}): the launch's
     * finalization has run by then, so a settle it suppressed can no longer
     * race it — and the settle's guards run again inside {@link #settleRemote},
     * so a <em>new</em> Resume that claimed meanwhile is still never
     * overwritten. One-shot: the deferred verdict is consumed whether or
     * not it lands.
     */
    void settleDeferredRemoteEnd(ManagedSessionId id) {
        DeferredRemoteSettle deferred = deferredRemoteSettles.remove(id);
        if (deferred != null) {
            settleRemote(id, deferred.status(), deferred.liveness());
        }
    }

    /**
     * Whether {@link LaunchClaims} shows neither a live execution nor a claim
     * for {@code id} — the remote settle's guard. {@code releaseIfIdle} with
     * an empty release is the query: it answers exactly that question and
     * runs nothing, so it is safe to call from inside a state transform.
     */
    private boolean sessionIdle(ManagedSessionId id) {
        return claims.releaseIfIdle(id, () -> { });
    }

    /**
     * Records the settle's liveness answer in the map. The map is a supplier
     * because the production default resolves the plugin service lazily;
     * a resolve that fails (no application at probe time) is logged and
     * skipped — the row's settle already landed, and a missing liveness
     * entry is the honest "never probed" rather than a failed settle.
     */
    private void recordLiveness(ManagedSessionId id, RemoteSessionLiveness.State state) {
        RemoteSessionLiveness map;
        try {
            map = liveness.get();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, () -> "Recording liveness for " + id + " failed: " + e);
            return;
        }
        if (map != null) {
            map.set(id, state);
        }
    }

    // ---- late id binding (DISCOVERED providers) -------------------------------

    /**
     * Binds the agent session id of a DISCOVERED session that does not have
     * one yet, on the executor and detached: resolving reads the agent's own
     * session store, and a failure must never surface as a failed stop.
     */
    private void scheduleLateBind(ManagedSessionId id) {
        try {
            CompletableFuture.runAsync(() -> lateBindAgentSessionId(id), executor);
        } catch (RejectedExecutionException e) {
            // Shutdown drained the executor. The id stays unknown and the
            // resume path tries again — which is exactly why that backstop
            // exists.
            LOG.log(Level.DEBUG, "Skipping late id binding for {0} during shutdown", id);
        }
    }

    /**
     * Resolves and persists the agent session id for {@code id}, returning the
     * session as it now stands (with the id, if one was found).
     *
     * <p>Blocking: reads the provider's on-disk session store. Runs on the
     * executor, never a caller's thread.</p>
     *
     * <p>Only ever <em>adds</em> an id. A session that already has one is
     * returned untouched — rebinding a live id to a different conversation
     * would silently resume the wrong transcript, which looks like success and
     * is far worse than falling back to the picker.</p>
     */
    ManagedAgentSession lateBindAgentSessionId(ManagedSessionId id) {
        ManagedAgentSession session = DrydockSessionService.findIn(stateStore.get().state(), id);
        if (session == null || session.agentSessionId().isPresent()) {
            return session;
        }
        Optional<SessionIdDiscovery> discovery = registry.get().provider(session.agentKind())
                .filter(provider -> provider.idStrategy() == SessionIdStrategy.DISCOVERED)
                .flatMap(AgentProvider::idDiscovery);
        if (discovery.isEmpty()) {
            return session;   // PRESET, or a provider with no discovery at all
        }
        // Seeded so a resolve can never claim an id another row already owns;
        // the create flow seeds the same set on its first launch.
        seedClaimedIdsOnce();
        Optional<String> resolved;
        try {
            resolved = discovery.get().resolve(session.workingDirectory(),
                    lastLaunchedAt.getOrDefault(id, session.createdAt()), claimedAgentSessionIds);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, () -> "Late id binding failed for " + id + ": " + e);
            return session;
        }
        if (resolved.isEmpty()) {
            // Logged, because "no id" has two very different causes and the
            // difference is invisible otherwise: the agent wrote no session
            // record at all (a conversation that never completed a turn), or
            // it wrote one drydock could not match. Both leave resume on the
            // picker, and only the second is a bug.
            LOG.log(Level.INFO,
                    "No agent session id resolved for {0} in {1}; resume falls back to the provider''s picker",
                    id, session.workingDirectory());
            return session;
        }
        String agentSessionId = resolved.get();
        // resolve() filters by the claimed set but does not add to it (unlike
        // discover(), which claims atomically), so the claim is ours to make.
        if (!claimedAgentSessionIds.add(agentSessionId)) {
            return session;   // another binding won the race
        }
        ManagedAgentSession[] bound = new ManagedAgentSession[]{session};
        stateStore.get().update(state -> {
            // The session may have been forgotten while the store was read.
            ManagedAgentSession existing = DrydockSessionService.findIn(state, id);
            if (existing == null || existing.agentSessionId().isPresent()) {
                bound[0] = existing;
                return state;
            }
            bound[0] = existing.withAgentSessionId(Optional.of(agentSessionId));
            return DrydockSessionService.withReplacedSession(state, bound[0]);
        });
        LOG.log(Level.INFO, "Bound agent session id {0} to session {1}", agentSessionId, id);
        DrydockSessionsListener.publishChanged();
        return bound[0] != null ? bound[0] : session;
    }
}
