package app.drydock.intellij.session;

import app.drydock.agent.api.AgentRegistry;
import app.drydock.agent.api.SessionIdDiscovery;
import app.drydock.agent.api.SessionIdStrategy;
import app.drydock.agent.spi.AgentProvider;
import app.drydock.app.ApplicationStateStore;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.WorkspaceBoxBinding;
import app.drydock.intellij.DrydockPluginService;
import app.drydock.intellij.DrydockSessionsListener;
import app.drydock.intellij.agent.AgentExecutionListener;
import app.drydock.intellij.agent.LaunchClaims;
import app.drydock.intellij.review.SessionMcpAccess;
import app.drydock.intellij.workspace.RemoteSessionLiveness;
import app.drydock.workspace.BoxShell;
import app.drydock.workspace.CommandRunner;

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
    /** The detached-remote liveness map the settle writes; resolved lazily in production. */
    private final Supplier<RemoteSessionLiveness> liveness;

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

    SessionEnds(Supplier<ApplicationStateStore> stateStore, Supplier<AgentRegistry> registry,
                ExecutorService executor, SessionMcpAccess mcpAccess,
                LaunchClaims claims) {
        this(stateStore, registry, executor, mcpAccess, claims,
                CommandRunner.PRODUCTION, SessionEnds::pluginLiveness);
    }

    /**
     * As above, with the remote settle's two seams injected (T17): the box
     * command runner behind the has-session probe, and the liveness map
     * its answer is recorded in. The five-argument form keeps today's
     * construction sites compiling, with the production runner and the
     * plugin service's liveness map as its defaults; tests inject fakes
     * here so no test ever spawns a child or needs an IDE.
     */
    SessionEnds(Supplier<ApplicationStateStore> stateStore, Supplier<AgentRegistry> registry,
                ExecutorService executor, SessionMcpAccess mcpAccess,
                LaunchClaims claims, CommandRunner runner,
                Supplier<RemoteSessionLiveness> liveness) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.mcpAccess = Objects.requireNonNull(mcpAccess, "mcpAccess");
        this.claims = Objects.requireNonNull(claims, "claims");
        this.boxShell = new BoxShell(Objects.requireNonNull(runner, "runner"));
        this.liveness = Objects.requireNonNull(liveness, "liveness");
    }

    /**
     * The production liveness map, resolved lazily: this class is built
     * before the plugin service's init completes, and a settle runs long
     * after it, so the map is fetched when a probe's answer is ready, never
     * at construction. Never called by tests (they inject their own map).
     */
    private static RemoteSessionLiveness pluginLiveness() {
        return DrydockPluginService.getInstance().remoteSessionLiveness();
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
        scheduleLateBind(id);
    }

    // ---- remote settle (ssh client ended without an explicit Stop) ----------

    /**
     * Settles a remote row whose ssh client just ended. Nothing is released
     * first — a remote launch never mints Drydock MCP access (INV-15), so
     * unlike the local half there is no token to hand back — and the agent
     * on the box is not touched in any way (INV-10): no kill-session, no
     * input, only the one has-session probe that decides the settle. The
     * probe is a blocking ssh round trip, so it rides the executor (INV-8),
     * never the thread that delivered the process event.
     */
    private void settleRemoteWhenClientEnds(ManagedSessionId id, WorkspaceBoxBinding box,
                                             boolean stopRequested) {
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
            case BoxShell.Liveness.Alive ignored ->
                    settleRemote(id, SessionStatus.IDLE, RemoteSessionLiveness.State.ALIVE);
            case BoxShell.Liveness.Dead ignored ->
                    settleRemote(id, stopRequested ? SessionStatus.INACTIVE : SessionStatus.EXITED,
                            RemoteSessionLiveness.State.DEAD);
            case BoxShell.Liveness.Unknown unknown -> {
                LOG.log(Level.WARNING, () -> "Remote settle for " + id + " on " + box.sshRemote().host()
                        + " could not conclude (" + unknown.reason() + "); the row stays IDLE and resumable");
                settleRemote(id, SessionStatus.IDLE, RemoteSessionLiveness.State.UNKNOWN);
            }
        }
    }

    /**
     * Persists the remote row's settle status and records its liveness. A
     * null {@code livenessState} (the quit-time settle, which has no probe
     * answer) leaves the liveness map untouched. Both halves are a no-op
     * when the row is gone or when {@link LaunchClaims} shows a live
     * execution or claim for the id (INV-21): a Resume that claimed the
     * session while the probe was in flight owns the row now, and its
     * RUNNING is never overwritten. The guard runs inside the transform,
     * so it is atomic with the write against every other writer through
     * the store's single lock.
     */
    private void settleRemote(ManagedSessionId id, SessionStatus status, RemoteSessionLiveness.State livenessState) {
        boolean[] suppressed = {false};
        stateStore.get().update(state -> {
            ManagedAgentSession existing = DrydockSessionService.findIn(state, id);
            if (existing == null || existing.workspaceBox().isEmpty() || !sessionIdle(id)) {
                suppressed[0] = true;
                return state;
            }
            if (existing.status() == status) {
                return state;
            }
            return DrydockSessionService.withReplacedSession(state,
                    existing.withStatus(status).withLastOpenedAt(Instant.now()));
        });
        if (suppressed[0]) {
            return;
        }
        if (livenessState != null) {
            recordLiveness(id, livenessState);
        }
        DrydockSessionsListener.publishChanged();
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
