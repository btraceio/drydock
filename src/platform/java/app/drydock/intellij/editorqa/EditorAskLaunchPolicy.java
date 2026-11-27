package app.drydock.intellij.editorqa;

import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.agent.api.CreateContext;
import app.drydock.agent.api.LaunchPlan;
import app.drydock.agent.api.McpAccess;
import app.drydock.agent.api.ResumeContext;
import app.drydock.agent.api.SessionIdDiscovery;
import app.drydock.agent.spi.AgentProvider;
import app.drydock.agent.spi.AgentProvider.EditorAskDelivery;
import app.drydock.agent.spi.AgentProvider.EditorAskLaunch;
import app.drydock.agent.spi.AgentProvider.EditorAskRequest;
import app.drydock.agent.spi.EditorAskTurnObserver;
import app.drydock.agent.spi.EditorAskTurnObserver.Observation;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedAgentSession.SessionPurpose;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SshRemote;
import app.drydock.editorqa.EditorAskLane;
import app.drydock.editorqa.EditorAskLane.EventKind;
import app.drydock.editorqa.EditorAskLane.WriteOutcome;
import app.drydock.editorqa.EditorAskPorts.LaunchOutcome;
import app.drydock.editorqa.EditorAskPorts.LaunchRequest;
import app.drydock.editorqa.EditorAskPorts.LaunchResult;
import app.drydock.editorqa.EditorAskPorts.Launcher;
import app.drydock.editorqa.EditorAskPorts.Resumability;
import app.drydock.editorqa.EditorAskPrompt;
import app.drydock.editorqa.EditorAskRowBinder;
import app.drydock.intellij.session.DrydockSessionService;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The strict launch/resume boundary of the editor Q&amp;A row, the platform
 * implementation of {@link Launcher}. It reuses the one managed launch
 * pipeline ({@link DrydockSessionService}: lane-independent claim, mint,
 * {@code AgentConsoleHost.startConsole}); there is no second process manager.
 * What makes the boundary strict lives here, and the service only calls
 * through the static predicates below:
 * <ul>
 *   <li>a marked row launches only through this policy (a one-shot admission
 *       token the policy sets just before it calls the service): every
 *       generic entry point (resume, start, launch, transfer) is refused;</li>
 *   <li>marked rows are local, read-only, non-eval and carry no task, remote
 *       or IDE resources; a workspace box or an SSH-remote repository is
 *       refused before any remote dispatch;</li>
 *   <li>no snapshot, claim or late binding by cwd/time/name: a conversation
 *       is bound only through {@link Events#conversationProven};</li>
 *   <li>the first turn is the provider's single positional launch prompt,
 *       carried once, with no model flag on a resume;</li>
 *   <li>the access is only usable when the launch command really carries
 *       its credential file; otherwise the launch is refused before the
 *       prompt reaches the harness.</li>
 * </ul>
 * Nothing here persists an endpoint, credential or generation as identity.
 */
public final class EditorAskLaunchPolicy implements Launcher {

    private static final Logger LOG = System.getLogger(EditorAskLaunchPolicy.class.getName());
    private static final long START_TIMEOUT_SECONDS = 30;
    private static final long STOP_TIMEOUT_SECONDS = 10;

    /** The coordinator's side of launch/exit/proof notifications, always with the execution identity. */
    public interface Events {
        void executionEnded(String root, String executionId, long generation);

        boolean conversationProven(String root, String executionId, long generation, AgentKind kind,
                                   String conversationId);

        /** A provider observation of the launch's current turn, already fenced by execution identity. */
        default void observed(String root, EditorAskLane.Event event) {
        }

        /** Why Forget must refuse {@code session} (a retained Q&A history), or empty. Blocking. */
        default Optional<String> forgetProtection(ManagedAgentSession session) {
            return Optional.empty();
        }
    }

    /** The one write into the marked terminal (generation-bound submit, T13). */
    @FunctionalInterface
    public interface Writer {
        WriteOutcome write(String executionId, long generation, String prompt);
    }

    /** The typed delivery proof and the armed provider observer of one launch; the cursor is the pump's. */
    private static final class Evidence {
        volatile EditorAskTurnObserver observer;
        volatile EditorAskDelivery delivery;
        volatile String nonce = "";
        long cursor;
    }

    /** {@code key} is the registry's execution identity, bound when that execution starts. */
    private record Active(ManagedSessionId row, String root, String executionId, long generation,
                          AgentKind kind, boolean[] opened, Evidence evidence, String[] key) {
    }

    private final DrydockSessionService sessions;
    private final Supplier<AgentRegistry> registry;
    private final EditorAskRowBinder rows;
    private final Function<ManagedSessionId, OptionalLong> credentialGeneration;
    private final Events events;
    private final Writer writer;
    private final Map<ManagedSessionId, Active> active = new ConcurrentHashMap<>();
    private final Set<ManagedSessionId> admitted = ConcurrentHashMap.newKeySet();

    public EditorAskLaunchPolicy(DrydockSessionService sessions, Supplier<AgentRegistry> registry,
                                 EditorAskRowBinder rows,
                                 Function<ManagedSessionId, OptionalLong> credentialGeneration,
                                 Events events, Writer writer) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.rows = Objects.requireNonNull(rows, "rows");
        this.credentialGeneration = Objects.requireNonNull(credentialGeneration, "credentialGeneration");
        this.events = Objects.requireNonNull(events, "events");
        this.writer = Objects.requireNonNull(writer, "writer");
        sessions.editorAskPolicy(this);
    }

    // ---- predicates the session pipeline calls through (static, allocation-free) ----

    /** Whether {@code row} is the dedicated Q&amp;A row. */
    public static boolean marked(ManagedAgentSession row) {
        return row.purpose() != SessionPurpose.ORDINARY;
    }

    /** An ordinary row's {@code startedWithTask} is the prompt's presence; a marked row never carries a task. */
    public static boolean startedWithTask(ManagedAgentSession row, Optional<String> initialPrompt) {
        return !marked(row) && initialPrompt.isPresent();
    }

    /** Marked rows never snapshot or discover a conversation id. */
    public static Optional<SessionIdDiscovery> discovery(ManagedAgentSession row, Optional<SessionIdDiscovery> d) {
        return marked(row) ? Optional.empty() : d;
    }

    /**
     * Admission of a launch/resume of {@code row}: ordinary rows pass; a marked row passes only
     * with this policy's one-shot token. Thrown before the claim, so nothing is touched.
     */
    public static void admit(EditorAskLaunchPolicy policy, ManagedAgentSession row) {
        if (marked(row) && (policy == null || !policy.admitted.remove(row.id()))) {
            throw new IllegalStateException("The editor Q&A session can only be started by an editor question.");
        }
    }

    /** Local-only, read-only, non-eval: no box, no SSH remote (checked here, not just {@code workspaceBox}). */
    public static void requireLocal(ManagedAgentSession row, Optional<SshRemote> sshRemote) {
        if (!marked(row)) {
            return;
        }
        if (row.workspaceBox().isPresent() || sshRemote.isPresent()) {
            throw new IllegalStateException("The editor Q&A session runs only on a local checkout.");
        }
        if (row.binding().mode() != SessionMode.READ_ONLY || row.evalMode()) {
            throw new IllegalStateException("The editor Q&A session must be read-only and not eval.");
        }
    }

    /**
     * The create plan of {@code row}: an ordinary row's is the provider's own command. A marked row's
     * comes only from the provider's typed Q&amp;A preparation, which carries the one first turn as the
     * positional launch prompt, proves the answer tool is delivered and arms the turn observer before
     * anything is spawned. Without that evidence the launch is refused before the prompt reaches the harness.
     */
    public static LaunchPlan buildCreate(EditorAskLaunchPolicy policy, AgentProvider provider,
                                         ManagedAgentSession row, CreateContext ctx) {
        if (!marked(row)) {
            return provider.buildCreateCommand(ctx);
        }
        CreateContext bare = new CreateContext(ctx.displayName(), ctx.sessionId(), ctx.workingDirectory(),
                ctx.remote(), ctx.mcp(), ctx.model(), ctx.evalMode(), ctx.readOnly(), Optional.empty(),
                ctx.stdioMcpServers(), ctx.launchResources(), ctx.persistence(), ctx.workflowAccess(),
                ctx.remoteLaunchSupport());
        return policy(policy).prepare(row, ctx.initialPrompt(), ctx.mcp(),
                request -> provider.prepareEditorAskCreate(bare, request));
    }

    /** As {@link #buildCreate} for a pinned resume; no model flag is rendered. */
    public static LaunchPlan buildResume(EditorAskLaunchPolicy policy, AgentProvider provider,
                                         ManagedAgentSession row, ResumeContext ctx) {
        if (!marked(row)) {
            return provider.buildResumeCommand(ctx);
        }
        ResumeContext bare = new ResumeContext(ctx.agentSessionId(), ctx.agentSessionName(),
                ctx.workingDirectory(), ctx.remote(), ctx.mcp(), ctx.evalMode(), ctx.readOnly(),
                ctx.stdioMcpServers(), Optional.empty(), ctx.launchResources(), ctx.persistence(),
                ctx.workflowAccess(), ctx.remoteLaunchSupport());
        return policy(policy).prepare(row, ctx.resumePrompt(), ctx.mcp(),
                request -> provider.prepareEditorAskResume(bare, request));
    }

    private static EditorAskLaunchPolicy policy(EditorAskLaunchPolicy policy) {
        if (policy == null) {
            throw new IllegalStateException("The editor Q&A session can only be started by an editor question.");
        }
        return policy;
    }

    private LaunchPlan prepare(ManagedAgentSession row, Optional<String> prompt, Optional<McpAccess> access,
                               Function<EditorAskRequest, EditorAskLaunch> prepare) {
        Active a = active.get(row.id());
        if (a == null || prompt.isEmpty()) {
            throw new IllegalStateException("The editor Q&A launch carries no question.");
        }
        EditorAskLaunch launch;
        try {
            String marker = EditorAskPrompt.decode(prompt.get()).questionId();
            launch = prepare.apply(new EditorAskRequest(UUID.randomUUID().toString(), marker, prompt.get()));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("The editor question cannot be launched: " + e.getMessage(), e);
        }
        boolean carried = launch.accepted() && launch.observer().isPresent()
                && access.flatMap(McpAccess::credentialFile).isPresent();
        if (!carried) {
            launch.observer().ifPresent(EditorAskTurnObserver::close);
            throw new IllegalStateException(launch.refusal().isBlank()
                    ? "The agent cannot reach the editor answer tool in this launch." : launch.refusal());
        }
        Evidence evidence = a.evidence();
        evidence.delivery = launch.delivery().orElseThrow();
        evidence.nonce = launch.observer().get().launchNonce();
        evidence.observer = launch.observer().get();
        return launch.plan();
    }

    /** The ordinary last-used launch settings are not recorded for the Q&amp;A row. */
    public static boolean recordsLastUsed(ManagedAgentSession row) {
        return !marked(row);
    }

    /** The pipeline reports the console was handed the plan: from here a failure may have reached an agent. */
    public void opened(ManagedSessionId row) {
        Active a = active.get(row);
        if (a != null) {
            a.opened()[0] = true;
        }
    }

    /**
     * A PRESET provider's id that the launch command actually rendered: the one proof the policy can
     * give without a provider observer. Saved only for the current launch, generation and provider.
     */
    public void presetRendered(ManagedAgentSession row, String conversationId) {
        Active a = active.get(row.id());
        if (a == null || conversationId == null || conversationId.isEmpty()) {
            return;
        }
        try {
            events.conversationProven(a.root(), a.executionId(), a.generation(), a.kind(), conversationId);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "reporting a rendered conversation id failed", e);
        }
    }

    /** The registry started the execution {@code key} of {@code row}: the launch's identity is bound once. */
    public void started(ManagedSessionId row, String key) {
        Active a = active.get(row);
        if (a != null && key != null) {
            synchronized (a.key()) {
                if (a.key()[0] == null) {
                    a.key()[0] = key;
                }
            }
        }
    }

    /**
     * The execution {@code key} of {@code row} ended. Only the launch whose execution this is ends: a late
     * exit of a replaced process (another key), or one arriving before this launch's execution started,
     * changes nothing.
     */
    public void ended(ManagedSessionId row, String key) {
        Active a = active.get(row);
        if (a == null) {
            return;
        }
        synchronized (a.key()) {
            if (key == null || !key.equals(a.key()[0])) {
                return;
            }
        }
        if (!active.remove(row, a)) {
            return;
        }
        retire(a);
        try {
            events.executionEnded(a.root(), a.executionId(), a.generation());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "reporting an editor ask execution end failed", e);
        }
    }

    private static void retire(Active a) {
        EditorAskTurnObserver observer = a.evidence().observer;
        if (observer != null) {
            observer.close();
        }
    }

    private void drop(Active a) {
        active.remove(a.row(), a);
        retire(a);
    }

    /** Why Forget must refuse {@code session}, or empty: the coordinator's retained-history rule. */
    public static Optional<String> forgetRefusal(EditorAskLaunchPolicy policy, ManagedAgentSession session) {
        return marked(session) && policy != null ? policy.events.forgetProtection(session) : Optional.empty();
    }

    /**
     * Reads every live launch's observer once and feeds the coordinator, fenced by execution identity:
     * a launch that ended or was replaced while its observer was read is dropped, never fed. BOUND proves
     * the conversation and submits the turn; SETTLED ends it and, as the providers' terminal-and-idle
     * evidence, reports readiness. Lane cursors derive from the observer's strictly increasing sequence.
     * Blocking (observer reads, catalog write): background thread only.
     */
    public void pump() {
        for (Active a : active.values()) {
            Evidence evidence = a.evidence();
            EditorAskTurnObserver observer = evidence.observer;
            if (observer == null) {
                continue;
            }
            try {
                synchronized (evidence) {
                    Observation seen = observer.read(evidence.cursor);
                    if (active.get(a.row()) != a) {
                        continue;
                    }
                    evidence.cursor = Math.max(evidence.cursor, seen.cursor());
                    seen.events().forEach(event -> feed(a, event));
                }
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "reading editor ask turn evidence failed", e);
            }
        }
    }

    private void feed(Active a, EditorAskTurnObserver.Event event) {
        long cursor = event.sequence() * 2;
        if (event.kind() == EditorAskTurnObserver.Kind.BOUND) {
            events.conversationProven(a.root(), a.executionId(), a.generation(), a.kind(), event.conversationId());
            observe(a, EventKind.SUBMITTED, event.marker(), cursor);
            return;
        }
        observe(a, EventKind.END, event.marker(), cursor);
        observe(a, EventKind.READY, event.marker(), cursor + 1);
    }

    private void observe(Active a, EventKind kind, String marker, long cursor) {
        events.observed(a.root(), new EditorAskLane.Event(a.executionId(), a.generation(), kind, marker, cursor));
    }

    /** The observer's own nonsecret launch nonce for {@code executionId}, or empty. */
    public Optional<String> launchNonce(String executionId) {
        return active.values().stream().filter(a -> a.executionId().equals(executionId))
                .map(a -> a.evidence().nonce).filter(n -> !n.isEmpty()).findFirst();
    }

    // ---- Launcher ----

    @Override
    public LaunchResult launch(LaunchRequest r) {
        ManagedAgentSession row = rows.current(r.row()).orElse(null);
        if (row == null || !marked(row)) {
            return refused("the Q&A session is not available.");
        }
        if (row.agentKind() != r.harness() || r.prompt() == null || r.prompt().isBlank()) {
            return refused("the Q&A launch does not match its session.");
        }
        Active a = new Active(row.id(), r.root(), r.executionId(), r.generation(), r.harness(), new boolean[1],
                new Evidence(), new String[1]);
        if (active.putIfAbsent(row.id(), a) != null) {
            return refused("the Q&A agent is still running.");
        }
        admitted.add(row.id());
        try {
            ManagedAgentSession started = (r.resumeConversationId().isPresent()
                    ? sessions.resumeSession(row.id(), Optional.of(r.prompt()))
                    : sessions.startEditorAsk(row.id(), r.prompt(), r.model()))
                    .get(START_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (started.status() != SessionStatus.RUNNING) {
                drop(a);
                return refused("the Q&A agent was not started (" + started.status() + ").");
            }
            return new LaunchResult(LaunchOutcome.STARTED,
                    credentialGeneration.apply(row.id()).orElse(-1), null);
        } catch (TimeoutException e) {
            stop(r.executionId());
            drop(a);
            return new LaunchResult(LaunchOutcome.FAILED, -1, "the agent did not start in time.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stop(r.executionId());
            drop(a);
            return new LaunchResult(LaunchOutcome.FAILED, -1, "interrupted while starting the agent.");
        } catch (ExecutionException | RuntimeException e) {
            drop(a);
            Throwable cause = e;
            while ((cause instanceof ExecutionException || cause instanceof CompletionException)
                    && cause.getCause() != null) {
                cause = cause.getCause();
            }
            Throwable failure = cause;
            LOG.log(Level.WARNING, () -> "Editor ask launch failed: " + failure);
            return a.opened()[0]
                    ? new LaunchResult(LaunchOutcome.FAILED, -1, "the agent failed while starting.")
                    : refused("the agent was not started: " + failure.getMessage());
        } finally {
            admitted.remove(row.id());
        }
    }

    private static LaunchResult refused(String reason) {
        return new LaunchResult(LaunchOutcome.REFUSED, -1, reason);
    }

    @Override
    public WriteOutcome write(String executionId, long generation, String prompt) {
        for (Active a : active.values()) {
            if (a.executionId().equals(executionId)) {
                EditorAskTurnObserver observer = a.evidence().observer;
                try {
                    if (observer == null) {
                        return WriteOutcome.REFUSED;
                    }
                    // Armed before the bytes can reach the agent: the fence excludes every earlier receipt.
                    observer.arm(EditorAskPrompt.decode(prompt).questionId());
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "arming an editor ask turn failed", e);
                    return WriteOutcome.REFUSED;
                }
            }
        }
        return writer.write(executionId, generation, prompt);
    }

    /** Exact only when the provider itself confirms the transcript under this id; unknown is LOST. */
    @Override
    public Resumability verifyConversation(ManagedSessionId row, AgentKind kind, String conversationId) {
        try {
            Optional<ManagedAgentSession> current = rows.current(row);
            if (current.isEmpty() || !marked(current.get())) {
                return Resumability.LOST;
            }
            return registry.get().conversations(kind)
                    .map(source -> source.transcriptExists(current.get().workingDirectory(), conversationId))
                    .orElse(false) ? Resumability.EXACT : Resumability.LOST;
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "verifying an editor ask conversation failed", e);
            return Resumability.LOST;
        }
    }

    @Override
    public void stop(String executionId) {
        for (Active a : active.values()) {
            if (a.executionId().equals(executionId)) {
                try {
                    sessions.stopSession(a.row()).get(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException | TimeoutException | RuntimeException e) {
                    LOG.log(Level.WARNING, "stopping an editor ask execution failed", e);
                }
            }
        }
    }
}
