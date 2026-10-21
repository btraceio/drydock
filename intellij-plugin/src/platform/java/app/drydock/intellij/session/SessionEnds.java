package app.drydock.intellij.session;

import app.drydock.agent.api.AgentRegistry;
import app.drydock.agent.api.SessionIdDiscovery;
import app.drydock.agent.api.SessionIdStrategy;
import app.drydock.agent.spi.AgentProvider;
import app.drydock.app.ApplicationStateStore;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.SessionStatus;
import app.drydock.intellij.DrydockSessionsListener;
import app.drydock.intellij.agent.AgentExecutionListener;

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
 */
final class SessionEnds implements AgentExecutionListener {

    private static final Logger LOG = System.getLogger(SessionEnds.class.getName());

    private final Supplier<ApplicationStateStore> stateStore;
    private final Supplier<AgentRegistry> registry;
    private final ExecutorService executor;

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
                ExecutorService executor) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.executor = Objects.requireNonNull(executor, "executor");
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
     */
    @Override
    public void executionTerminated(ManagedSessionId id, boolean stopRequested, Integer exitCode) {
        Objects.requireNonNull(id, "id");
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
