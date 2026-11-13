package app.drydock.intellij.session;

import app.drydock.activity.SessionActivityWatcher;
import app.drydock.agent.api.AgentCapabilities;
import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.agent.api.ConversationSource;
import app.drydock.agent.api.CreateContext;
import app.drydock.agent.api.LaunchPlan;
import app.drydock.agent.api.ActivityReporter;
import app.drydock.agent.api.McpAccess;
import app.drydock.agent.api.McpDelivery;
import app.drydock.agent.api.ResumeContext;
import app.drydock.agent.api.SessionIdDiscovery;
import app.drydock.agent.api.SessionIdStrategy;
import app.drydock.agent.api.StdioMcpServer;
import app.drydock.agent.providers.AgentCommands;
import app.drydock.agent.providers.HostBinaryLocator;
import app.drydock.agent.providers.claude.ClaudeAgentProvider;
import app.drydock.agent.providers.claude.internal.ClaudeExecutableLocator;
import app.drydock.agent.providers.codex.CodexAgentProvider;
import app.drydock.agent.providers.codex.internal.CodexExecutableLocator;
import app.drydock.agent.providers.pi.PiAgentProvider;
import app.drydock.agent.providers.pi.internal.PiExecutableLocator;
import app.drydock.agent.providers.terminal.TerminalAgentProvider;
import app.drydock.agent.spi.AgentProvider;
import app.drydock.app.ApplicationStateStore;
import app.drydock.app.LoginShellEnvironment;
import app.drydock.delayedtasks.TaskLaunchResources;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ApplicationState;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.ModelTiers;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.RepositorySettings;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionWorkspace;
import app.drydock.domain.WorkspaceBoxBinding;
import app.drydock.intellij.agent.AgentConsoleHost;
import app.drydock.intellij.agent.AgentExecutionListener;
import app.drydock.intellij.agent.AgentExecutionRegistry;
import app.drydock.intellij.agent.LaunchClaims;
import app.drydock.intellij.review.SessionMcpAccess;
import app.drydock.intellij.workspace.RemoteSessionLiveness;
import app.drydock.mcp.McpSessionContext.RenameKind;
import app.drydock.mcp.McpSessionContext.RenameOutcome;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.process.ProcessTimeoutException;
import app.drydock.state.JsonApplicationStateRepository;
import app.drydock.tasks.TaskGuidance;
import app.drydock.tasks.TaskId;
import app.drydock.tasks.TaskRuns;
import app.drydock.workspace.BoxShell;
import app.drydock.workspace.CommandRunner;
import app.drydock.workspace.WorkspacesCli;


import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The create-flow contract of the IDE session orchestrator: PRESET vs
 * DISCOVERED id strategies, snapshot-before-spawn ordering, the AC11
 * unsupported-plan refusal, lastUsedAgent, and delete. Everything runs
 * against fakes (the {@code FakeAgentProviderTest} pattern) plus the real
 * single writer over a temporary state file — no IDE, no agent CLIs.
 */
class DrydockSessionServiceTest {

    private static final long TIMEOUT_SECONDS = 5;

    /** A recording fake over the whole {@link AgentProvider} SPI. */
    static class RecordingProvider implements AgentProvider {

        private final AgentKind kind;
        private final SessionIdStrategy strategy;
        private final SessionIdDiscovery discovery;
        private final boolean supported;
        private final boolean sessionIdUsed;
        /** Exact command when non-null; otherwise a default embedding the context's session id. */
        private final String command;
        private final List<String> events;
        private final CountDownLatch arrived;
        private final CountDownLatch gate;

        final List<CreateContext> createContexts = new CopyOnWriteArrayList<>();
        final List<ResumeContext> resumeContexts = new CopyOnWriteArrayList<>();
        final List<Thread> threads = new CopyOnWriteArrayList<>();
        /** What {@link #mcpDelivery()} reports; NONE unless a test sets it. */
        volatile McpDelivery delivery = McpDelivery.NONE;
        /** When set, the command carries the access's credential file the way the real providers do. */
        volatile boolean wiresMcp;
        /** When set, both command builders throw it. */
        volatile Error buildError;

        RecordingProvider(AgentKind kind, SessionIdStrategy strategy, SessionIdDiscovery discovery,
                boolean supported, boolean sessionIdUsed, String command, List<String> events) {
            this(kind, strategy, discovery, supported, sessionIdUsed, command, events, null, null);
        }

        RecordingProvider(AgentKind kind, SessionIdStrategy strategy, SessionIdDiscovery discovery,
                boolean supported, boolean sessionIdUsed, String command, List<String> events,
                CountDownLatch arrived, CountDownLatch gate) {
            this.kind = kind;
            this.strategy = strategy;
            this.discovery = discovery;
            this.supported = supported;
            this.sessionIdUsed = sessionIdUsed;
            this.command = command;
            this.events = events;
            this.arrived = arrived;
            this.gate = gate;
        }

        /** Signals {@code arrived}, then blocks until {@code gate} opens; both are optional. */
        private void awaitGate() {
            if (arrived != null) {
                arrived.countDown();
            }
            if (gate != null) {
                try {
                    assertTrue(gate.await(10, TimeUnit.SECONDS), "gate was never opened");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while gated", e);
                }
            }
        }

        @Override public AgentKind kind() { return kind; }
        @Override public String displayName() { return "Fake " + kind; }
        @Override public void init(AgentContext ctx) { }
        @Override public Optional<Path> locateExecutable() { return Optional.of(Path.of("/bin/true")); }
        @Override public String describeSearched() { return "PATH"; }
        @Override public AgentCapabilities probeCapabilities() { return new AgentCapabilities(true, true, "1.0"); }
        @Override public boolean supportsRemote() { return false; }
        @Override public McpDelivery mcpDelivery() { return delivery; }

        @Override public LaunchPlan buildCreateCommand(CreateContext c) {
            threads.add(Thread.currentThread());
            createContexts.add(c);
            awaitGate();
            if (buildError != null) {
                throw buildError;
            }
            if (!supported) {
                return LaunchPlan.unsupported();
            }
            String built = command != null ? command
                    : "fake-agent --session '" + c.sessionId() + "' --name '" + c.displayName() + "'";
            return LaunchPlan.of(built + mcpFlag(c.mcp()), sessionIdUsed);
        }

        private String mcpFlag(Optional<McpAccess> mcp) {
            return wiresMcp
                    ? mcp.flatMap(McpAccess::credentialFile)
                            .map(file -> " --mcp-config " + AgentCommands.shellQuote(file.toString())).orElse("")
                    : "";
        }

        /**
         * Mirrors the Pi resume contract (C8/AC21): a known id resumes by
         * id, an unknown one falls to the picker — never --continue/--last.
         */
        @Override public LaunchPlan buildResumeCommand(ResumeContext r) {
            resumeContexts.add(r);
            awaitGate();
            if (buildError != null) {
                throw buildError;
            }
            if (r.agentSessionId().isPresent()) {
                return LaunchPlan.of("fake --session '" + r.agentSessionId().get() + "'" + mcpFlag(r.mcp()), false);
            }
            return LaunchPlan.of("fake --resume" + mcpFlag(r.mcp()), false);
        }
        @Override public SessionIdStrategy idStrategy() { return strategy; }
        @Override public Optional<ConversationSource> conversations() { return Optional.empty(); }
        @Override public Optional<ActivityReporter> activity() { return Optional.empty(); }
        @Override public Optional<SessionIdDiscovery> idDiscovery() {
            return discovery == null ? Optional.empty() : Optional.of(discovery);
        }
    }

    /** The DISCOVERED id capture, recording what it was asked and claiming like {@code SnapshotClaimDiscovery}. */
    static final class RecordingDiscovery implements SessionIdDiscovery {

        private final List<String> events;
        private final Queue<String> candidates = new ConcurrentLinkedQueue<>();

        final List<Object> snapshotsTaken = new CopyOnWriteArrayList<>();
        final List<Object> snapshotsReceived = new CopyOnWriteArrayList<>();
        final List<Instant> launchedAtsReceived = new CopyOnWriteArrayList<>();
        final List<Set<String>> claimedSetsReceived = new CopyOnWriteArrayList<>();

        /** What {@link #resolve} hands back, and what it was asked. */
        final Queue<String> lateCandidates = new ConcurrentLinkedQueue<>();
        final List<Instant> createdAtsReceived = new CopyOnWriteArrayList<>();
        final List<Set<String>> resolveClaimedSetsReceived = new CopyOnWriteArrayList<>();

        RecordingDiscovery(List<String> events) {
            this.events = events;
        }

        @Override public Object snapshot(Path workingDirectory) {
            events.add("snapshot");
            Object snapshot = new Object();
            snapshotsTaken.add(snapshot);
            return snapshot;
        }

        @Override public Optional<String> discover(Path workingDirectory, Instant launchedAt,
                Object snapshot, Set<String> claimedIds) {
            events.add("discover");
            snapshotsReceived.add(snapshot);
            launchedAtsReceived.add(launchedAt);
            claimedSetsReceived.add(claimedIds);
            String candidate = candidates.poll();
            if (candidate == null) {
                return Optional.empty();
            }
            // The real discovery implementations claim atomically; the fake
            // mirrors that so the claimed-set guard is actually exercised.
            return claimedIds.add(candidate) ? Optional.of(candidate) : Optional.empty();
        }

        /**
         * Mirrors {@code SnapshotClaimDiscovery.resolve}: it filters by the
         * claimed set but, unlike {@code discover}, does NOT add to it — the
         * caller claims. The fake keeps that asymmetry so the service's own
         * claim is what the test exercises.
         */
        @Override public Optional<String> resolve(Path workingDirectory, Instant createdAt,
                Set<String> claimedIds) {
            events.add("resolve");
            createdAtsReceived.add(createdAt);
            resolveClaimedSetsReceived.add(claimedIds);
            String candidate = lateCandidates.poll();
            return candidate == null || claimedIds.contains(candidate)
                    ? Optional.empty()
                    : Optional.of(candidate);
        }
    }

    /**
     * An execution host that never touches the IDE: records what it was asked
     * to start, stop and forget, and reports success without spawning.
     */
    static final class FakeConsoleHost implements AgentConsoleHost {

        private final List<String> events;
        final List<Open> opens = new CopyOnWriteArrayList<>();
        final List<ManagedSessionId> stopped = new CopyOnWriteArrayList<>();
        final List<ManagedSessionId> forgotten = new CopyOnWriteArrayList<>();
        /** When set, {@link #startConsole} throws it synchronously instead of returning a future. */
        volatile RuntimeException startFailure;
        /**
         * When set, the next {@link #startConsole} returns this future
         * instead of a completed one — a launch whose console-open is still
         * pending, so a test can deliver events inside the launch's claim
         * window. Taken and cleared on use.
         */
        volatile CompletableFuture<Void> pendingOpen;

        record Open(ManagedSessionId sessionId, Path workingDirectory, LaunchPlan plan) { }

        FakeConsoleHost(List<String> events) {
            this.events = events;
        }

        @Override public CompletableFuture<Void> startConsole(
                ManagedSessionId sessionId, Path workingDirectory, LaunchPlan plan) {
            events.add("open");
            if (startFailure != null) {
                throw startFailure;
            }
            opens.add(new Open(sessionId, workingDirectory, plan));
            CompletableFuture<Void> open = pendingOpen;
            if (open != null) {
                pendingOpen = null;
                return open;
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override public void stopConsole(ManagedSessionId sessionId) {
            events.add("stop");
            stopped.add(sessionId);
        }

        @Override public void forgetConsole(ManagedSessionId sessionId) {
            events.add("forget");
            forgotten.add(sessionId);
        }
    }

    /** Runs submitted tasks only when the test steps it, one at a time, in submission order. */
    static final class SteppedExecutorService extends AbstractExecutorService {

        private final Deque<Runnable> queued = new ConcurrentLinkedDeque<>();
        private volatile boolean terminated;

        /** Runs the oldest queued task; false when none is queued. */
        boolean runNext() {
            Runnable next = queued.pollFirst();
            if (next == null) {
                return false;
            }
            next.run();
            return true;
        }

        /** Runs queued tasks, including ones they queue, until none is left. */
        void runAll() {
            while (runNext()) {
                // keep stepping
            }
        }


        /**
         * A submission to a shut-down executor is rejected, as a drained
         * plugin executor rejects at IDE shutdown: the dependent stage the
         * submission would have run never starts.
         */
        @Override public void execute(Runnable command) {
            if (terminated) {
                throw new RejectedExecutionException("shut down");
            }
            queued.addLast(command);
        }
        @Override public void shutdown() { terminated = true; }
        @Override public List<Runnable> shutdownNow() { terminated = true; return List.of(); }
        @Override public boolean isShutdown() { return terminated; }
        @Override public boolean isTerminated() { return terminated; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return terminated; }
    }

    /** Determinism: every submitted task runs inline on the submitting thread. */
    static final class SameThreadExecutorService extends AbstractExecutorService {

        private volatile boolean terminated;

        @Override public void execute(Runnable command) { command.run(); }
        @Override public void shutdown() { terminated = true; }
        @Override public List<Runnable> shutdownNow() { terminated = true; return List.of(); }
        @Override public boolean isShutdown() { return terminated; }
        @Override public boolean isTerminated() { return terminated; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return terminated; }
    }

    /** The service wired to fakes and the real single writer over a temporary state file. */
    private static final class Harness {

        final Path base;
        final ApplicationStateStore store;
        final Repository repository;
        final List<String> events = new CopyOnWriteArrayList<>();
        final FakeConsoleHost consoles = new FakeConsoleHost(events);
        final ExecutorService executor;
        /** The working directories a remote-aware service was asked for stdio MCP servers, proving it never was. */
        final List<Path> stdioMcpAsked = new CopyOnWriteArrayList<>();
        /**
         * By default an execution is live once {@code consoles} opened it and
         * has neither stopped nor forgotten it; a forget drops the execution,
         * as {@code AgentExecutionRegistry.forget} does.
         */
        Predicate<ManagedSessionId> liveExecution = id ->
                consoles.opens.stream().anyMatch(open -> open.sessionId().equals(id))
                        && !consoles.stopped.contains(id) && !consoles.forgotten.contains(id);

        /**
         * The requested-client-end coordination the service's Stop and close
         * paths mark and its end settle settles by. Mirrors the registry's
         * mark semantics, with the live-execution answer delegated to this
         * harness's (mutable) predicate.
         */
        final RecordingClientEnds clientEnds = new RecordingClientEnds();

        /**
         * A fake of the registry's requested-client-end coordination: the
         * same mark semantics, the live-execution answer delegated to the
         * harness's predicate, and the marks readable so a test can assert
         * what the Stop and close paths marked. Never consults the IDE.
         */
        final class RecordingClientEnds implements AgentExecutionRegistry.RemoteClientEnds {

            private final Map<ManagedSessionId, AgentExecutionRegistry.RemoteClientEnds.RequestedEnd> marks =
                    new ConcurrentHashMap<>();

            @Override public void beginExplicitStop(ManagedSessionId id) {
                marks.put(id, AgentExecutionRegistry.RemoteClientEnds.RequestedEnd.explicitStopPending());
            }

            @Override public void stopVerdict(ManagedSessionId id, boolean killConfirmed) {
                marks.compute(id, (session, existing) ->
                        existing != null
                                && existing.origin() == AgentExecutionRegistry.RemoteClientEnds.Origin.EXPLICIT_STOP
                                ? AgentExecutionRegistry.RemoteClientEnds.RequestedEnd.explicitStopVerdict(killConfirmed)
                                : existing);
            }

            @Override public void endExplicitStop(ManagedSessionId id) {
                marks.compute(id, (session, existing) ->
                        existing != null
                                && existing.origin() == AgentExecutionRegistry.RemoteClientEnds.Origin.EXPLICIT_STOP
                                ? null : existing);
            }

            @Override public void beginClose(ManagedSessionId id) {
                marks.put(id, AgentExecutionRegistry.RemoteClientEnds.RequestedEnd.close());
            }

            @Override public void endClose(ManagedSessionId id) {
                marks.compute(id, (session, existing) ->
                        existing != null && existing.origin() == AgentExecutionRegistry.RemoteClientEnds.Origin.CLOSE
                                ? null : existing);
            }

            @Override public void clear(ManagedSessionId id) {
                marks.remove(id);
            }

            @Override public Optional<AgentExecutionRegistry.RemoteClientEnds.RequestedEnd> requestedEnd(ManagedSessionId id) {
                return Optional.ofNullable(marks.get(id));
            }

            @Override public boolean hasLiveExecution(ManagedSessionId id) {
                return liveExecution.test(id);
            }
        }

        private Harness(Path base, Repository repository, ExecutorService executor) {
            this.base = base;
            this.store = ApplicationStateStore.forRepository(
                    new JsonApplicationStateRepository(base.resolve("state.json")));
            this.repository = repository;
            this.executor = executor;
        }

        DrydockSessionService service(AgentProvider... providers) {
            return service(path -> List.of(), providers);
        }

        DrydockSessionService service(Function<Path, List<StdioMcpServer>> stdioMcpServers,
                                      AgentProvider... providers) {
            return service(stdioMcpServers, SessionMcpAccess.none(), providers);
        }

        DrydockSessionService service(SessionMcpAccess mcpAccess, AgentProvider... providers) {
            return service(path -> List.of(), mcpAccess, providers);
        }

        DrydockSessionService service(Function<Path, List<StdioMcpServer>> stdioMcpServers,
                                      SessionMcpAccess mcpAccess, AgentProvider... providers) {
            AgentRegistry registry = new AgentRegistry(List.of(providers),
                    new AgentContext(base, base.resolve("activity"), executor));
            SessionActivityWatcher watcher = new SessionActivityWatcher(base.resolve("activity"), executor);
            Function<Path, CompletableFuture<RepositoryId>> resolver =
                    path -> CompletableFuture.completedFuture(repository.id());
            return new DrydockSessionService(CompletableFuture.completedFuture(null),
                    () -> store, () -> registry, () -> watcher, resolver, consoles, executor, stdioMcpServers,
                    mcpAccess, new LaunchClaims(id -> liveExecution.test(id)));
        }

        /**
         * The remote seam: the full constructor with the workspace-box
         * components -- the opt-in flag, the box ssh/list runner, and the
         * detached liveness map -- wired to the recording fakes. The stdio
         * MCP function records every ask, so a remote launch's "no stdio
         * MCP servers" is observable.
         */
        DrydockSessionService remoteService(BooleanSupplier flag, CommandRunner runner,
                RemoteSessionLiveness liveness, SessionMcpAccess mcpAccess,
                TaskLaunchResources resources, AgentProvider... providers) {
            return remoteService(flag, runner, liveness, mcpAccess, resources,
                    question -> CompletableFuture.completedFuture(false), providers);
        }

        /**
         * The full remote seam with the Forget's confirm dialog wired to a
         * fake too: the kill/probe/list runner, the liveness map, and the
         * question the Forget asks when its kill could not conclude.
         */
        DrydockSessionService remoteService(BooleanSupplier flag, CommandRunner runner,
                RemoteSessionLiveness liveness, SessionMcpAccess mcpAccess,
                TaskLaunchResources resources, Function<String, CompletableFuture<Boolean>> confirm,
                AgentProvider... providers) {
            AgentRegistry registry = new AgentRegistry(List.of(providers),
                    new AgentContext(base, base.resolve("activity"), executor));
            SessionActivityWatcher watcher = new SessionActivityWatcher(base.resolve("activity"), executor);
            Function<Path, CompletableFuture<RepositoryId>> resolver =
                    path -> CompletableFuture.completedFuture(repository.id());
            return new DrydockSessionService(CompletableFuture.completedFuture(null),
                    () -> store, () -> registry, () -> watcher, resolver, consoles, executor,
                    path -> {
                        stdioMcpAsked.add(path);
                        return List.of();
                    },
                    mcpAccess, new LaunchClaims(id -> liveExecution.test(id)), resources, flag,
                    new BoxShell(runner), new WorkspacesCli(boxBinary(), runner), liveness, confirm, clientEnds);
        }
    }

    /** Records every call; answers {@code accessFor} with {@code answer}, or throws when {@code failure} is set. */
    private static final class RecordingAccess implements SessionMcpAccess {

        final List<McpDelivery> requested = new CopyOnWriteArrayList<>();
        final List<ManagedSessionId> released = new CopyOnWriteArrayList<>();
        private final Optional<McpAccess> answer;
        private final RuntimeException failure;

        RecordingAccess(Optional<McpAccess> answer, RuntimeException failure) {
            this.answer = answer;
            this.failure = failure;
        }

        static RecordingAccess granting() {
            return new RecordingAccess(Optional.of(new McpAccess("http://endpoint.invalid/mcp", "tok", Optional.empty())),
                    null);
        }

        /** Grants access with a credential file, as {@code ReviewBackend} does. */
        static RecordingAccess grantingWithFile(Path file) {
            return new RecordingAccess(Optional.of(new McpAccess("http://endpoint.invalid/mcp", "tok",
                    Optional.of(file))), null);
        }

        static RecordingAccess declining() {
            return new RecordingAccess(Optional.empty(), null);
        }

        static RecordingAccess throwing() {
            return new RecordingAccess(Optional.empty(), new IllegalStateException("boom"));
        }

        @Override
        public Optional<McpAccess> accessFor(ManagedSessionId sessionId, McpDelivery delivery) {
            requested.add(delivery);
            if (failure != null) {
                throw failure;
            }
            return answer;
        }

        @Override
        public void release(ManagedSessionId sessionId) {
            released.add(sessionId);
        }
    }

    private static Harness harness(@TempDir Path base) {
        Repository repository = new Repository(RepositoryId.newId(), base, "repo",
                Instant.parse("2026-08-12T10:00:00Z"), Instant.parse("2026-08-12T10:00:00Z"),
                RepositorySettings.DEFAULT);
        Harness harness = new Harness(base, repository, new SameThreadExecutorService());
        harness.store.update(state -> state.withRepositories(List.of(repository)));
        return harness;
    }

    private static ManagedAgentSession persisted(Harness harness, ManagedSessionId id) {
        return harness.store.state().sessions().stream()
                .filter(session -> session.id().equals(id))
                .findFirst().orElseThrow();
    }

    /** The persisted session with {@code id}, or null when it is gone. */
    private static ManagedAgentSession persistedOrNull(Harness harness, ManagedSessionId id) {
        return harness.store.state().sessions().stream()
                .filter(session -> session.id().equals(id))
                .findFirst().orElse(null);
    }

    /**
     * A session as a restart would have loaded it: already persisted, with
     * the given binding and status — the resume flow's input, without
     * running a create first.
     */
    private static ManagedAgentSession persistSession(Harness harness, AgentKind kind, String displayName,
            AgentBinding binding, Path workingDirectory, SessionStatus status, Instant createdAt) {
        ManagedAgentSession session = new ManagedAgentSession.Builder(
                ManagedSessionId.newId(), harness.repository.id(), displayName, binding,
                SessionWorkspace.inRepository(workingDirectory.toAbsolutePath().normalize()), createdAt)
                .status(status)
                .build();
        harness.store.update(state -> {
            List<ManagedAgentSession> sessions = new ArrayList<>(state.sessions());
            sessions.add(session);
            return state.withSessions(sessions);
        });
        return session;
    }

    // ---- create flow ---------------------------------------------------------

    @Test
    void presetSessionIdIsPersistedWhenTheCommandCarriesIt(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingProvider provider = new RecordingProvider(
                AgentKind.CLAUDE, SessionIdStrategy.PRESET, null, true, true, null, harness.events);
        DrydockSessionService service = harness.service(provider);

        ManagedAgentSession prepared = service.prepareSession(base, AgentKind.CLAUDE, "My session")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(SessionStatus.STARTING, prepared.status());
        assertEquals(harness.repository.id(), prepared.repositoryId());
        assertEquals(base.toAbsolutePath().normalize(), prepared.workingDirectory());
        assertEquals(AgentBinding.unlaunched(AgentKind.CLAUDE), prepared.binding());

        ManagedAgentSession running = service.launchSession(prepared).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(SessionStatus.RUNNING, running.status());
        // The generated id is the one the provider was handed...
        assertEquals(1, provider.createContexts.size());
        String generated = provider.createContexts.get(0).sessionId();
        assertFalse(generated.isBlank(), "PRESET launches generate the id up front");
        // ...and it is persisted on the session only because the command used it.
        assertEquals(Optional.of(generated), persisted(harness, running.id()).agentSessionId());
        assertEquals(1, harness.consoles.opens.size());
        // The host is handed the session id and a directory, never a title:
        // the tab's label is derived from the catalog row, so an agent
        // renaming itself mid-session needs no second source to update.
        assertEquals(running.id(), harness.consoles.opens.get(0).sessionId());
        assertEquals(base.toAbsolutePath().normalize(), harness.consoles.opens.get(0).workingDirectory());
        harness.store.flush();
    }

    @Test
    void createHandsTheAcceptedTaskToTheProviderVerbatim(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingProvider provider = new RecordingProvider(
                AgentKind.PI, SessionIdStrategy.DISCOVERED, null, true, false, null, harness.events);
        DrydockSessionService service = harness.service(provider);
        String task = "Implement issue #42.\n\nReview the issue's acceptance criteria and `Closes #42`.";

        ManagedAgentSession prepared = service.prepareSession(base, AgentKind.PI, "Issue #42")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        service.launchSession(prepared, Optional.of(task)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(1, provider.createContexts.size());
        assertEquals(Optional.of(task), provider.createContexts.get(0).initialPrompt());
        harness.store.flush();
    }

    @Test
    void discoveredSessionSnapshotsBeforeTheSpawnAndDiscoversAfter(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingDiscovery discovery = new RecordingDiscovery(harness.events);
        discovery.candidates.add("pi-session-1");
        RecordingProvider provider = new RecordingProvider(
                AgentKind.PI, SessionIdStrategy.DISCOVERED, discovery, true, false, null, harness.events);
        DrydockSessionService service = harness.service(provider);

        ManagedAgentSession prepared = service.prepareSession(base, AgentKind.PI, "Pi work")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        ManagedAgentSession running = service.launchSession(prepared).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // The snapshot precedes the spawn: the new session's own rollout
        // must not already be in the snapshot it is discovered against.
        int snapshotAt = harness.events.indexOf("snapshot");
        int openAt = harness.events.indexOf("open");
        int discoverAt = harness.events.indexOf("discover");
        assertTrue(snapshotAt >= 0 && openAt >= 0 && discoverAt >= 0,
                "expected snapshot/open/discover, got " + harness.events);
        assertTrue(snapshotAt < openAt, "snapshot must run before the spawn, got " + harness.events);
        assertTrue(openAt < discoverAt, "discovery runs after the tab opens, got " + harness.events);
        // discover received the pre-spawn snapshot, a launch timestamp, and
        // the shared claimed-id set.
        assertEquals(1, discovery.snapshotsReceived.size());
        assertEquals(discovery.snapshotsTaken.get(0), discovery.snapshotsReceived.get(0));
        assertNotNull(discovery.launchedAtsReceived.get(0));
        assertEquals(1, discovery.claimedSetsReceived.size());
        // No id is generated for a DISCOVERED launch, and the discovered id is persisted.
        assertEquals("", provider.createContexts.get(0).sessionId());
        assertEquals(Optional.of("pi-session-1"), persisted(harness, running.id()).agentSessionId());
        harness.store.flush();
    }

    @Test
    void twoDiscoveredLaunchesNeverClaimTheSameId(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        // One candidate id, offered to both launches: the second must find
        // it claimed and come away empty rather than bind the same id twice.
        RecordingDiscovery discovery = new RecordingDiscovery(harness.events);
        discovery.candidates.add("shared-id");
        discovery.candidates.add("shared-id");
        RecordingProvider provider = new RecordingProvider(
                AgentKind.CODEX, SessionIdStrategy.DISCOVERED, discovery, true, false, null, harness.events);
        DrydockSessionService service = harness.service(provider);

        ManagedAgentSession first = service.launchSession(
                service.prepareSession(base, AgentKind.CODEX, "One").get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        ManagedAgentSession second = service.launchSession(
                service.prepareSession(base, AgentKind.CODEX, "Two").get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(Optional.of("shared-id"), persisted(harness, first.id()).agentSessionId());
        assertEquals(Optional.empty(), persisted(harness, second.id()).agentSessionId(),
                "an id claimed by one launch must not be claimed again");
        harness.store.flush();
    }

    @Test
    void unsupportedPlanIsNeverLaunchedAndNamesTheAgentKind(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingProvider provider = new RecordingProvider(
                AgentKind.CODEX, SessionIdStrategy.PRESET, null, false, false, null, harness.events);
        DrydockSessionService service = harness.service(provider);

        ManagedAgentSession prepared = service.prepareSession(base, AgentKind.CODEX, "Nope")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        CompletableFuture<ManagedAgentSession> launched = service.launchSession(prepared);

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> launched.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof IllegalStateException, "got " + failure.getCause());
        // AC11: the error names the agent, both ways it is spelled.
        assertTrue(failure.getCause().getMessage().contains("CODEX"), failure.getCause().getMessage());
        assertTrue(failure.getCause().getMessage().contains("codex"), failure.getCause().getMessage());
        // No tab was opened and the failure is visible in the persisted state.
        assertTrue(harness.consoles.opens.isEmpty());
        assertFalse(harness.events.contains("open"));
        assertEquals(SessionStatus.FAILED, persisted(harness, prepared.id()).status());
        harness.store.flush();
    }

    @Test
    void successfulLaunchRecordsTheRepositorysLastUsedAgent(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingProvider provider = new RecordingProvider(
                AgentKind.PI, SessionIdStrategy.PRESET, null, true, true, null, harness.events);
        DrydockSessionService service = harness.service(provider);

        service.launchSession(service.prepareSession(base, AgentKind.PI, "Pi")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(Optional.of(AgentKind.PI),
                harness.store.state().repositories().get(0).settings().lastUsedAgent());
        harness.store.flush();
    }

    @Test
    void terminalKindRidesTheSameFlowWithTheShellPlan(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingProvider provider = new RecordingProvider(
                AgentKind.TERMINAL, SessionIdStrategy.PRESET, null, true, false,
                TerminalAgentProvider.SHELL_COMMAND, harness.events);
        DrydockSessionService service = harness.service(provider);

        ManagedAgentSession running = service.launchSession(
                service.prepareSession(base, AgentKind.TERMINAL, "Shell").get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(SessionStatus.RUNNING, running.status());
        assertEquals(1, harness.consoles.opens.size());
        assertEquals(TerminalAgentProvider.SHELL_COMMAND, harness.consoles.opens.get(0).plan().command());
        // The terminal command never carries an id, so none is fabricated.
        assertEquals(Optional.empty(), persisted(harness, running.id()).agentSessionId());
        harness.store.flush();
    }

    // ---- resume flow -----------------------------------------------------------

    @Test
    void noIdSessionResumesThroughTheProvidersPickerNeverContinueOrLast(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingProvider provider = new RecordingProvider(
                AgentKind.PI, SessionIdStrategy.DISCOVERED, null, true, false, null, harness.events);
        DrydockSessionService service = harness.service(provider);
        Instant createdAt = Instant.parse("2026-08-12T10:00:00Z");
        ManagedAgentSession stored = persistSession(harness, AgentKind.PI, "Old pi work",
                AgentBinding.unlaunched(AgentKind.PI), base, SessionStatus.INACTIVE, createdAt);

        ManagedAgentSession resumed = service.resumeSession(stored.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(SessionStatus.RUNNING, resumed.status());
        // The service passed an EMPTY agentSessionId (AC21's service half) so
        // exactly the provider's no-id branch ran: the picker, never
        // --continue/--last.
        assertEquals(1, provider.resumeContexts.size());
        ResumeContext ctx = provider.resumeContexts.get(0);
        assertEquals(Optional.empty(), ctx.agentSessionId());
        assertEquals(Optional.empty(), ctx.agentSessionName());
        assertEquals(base.toAbsolutePath().normalize(), ctx.workingDirectory());
        assertFalse(ctx.evalMode());
        assertTrue(ctx.remote().isEmpty() && ctx.mcp().isEmpty(), "MVP resumes carry no remote/mcp");
        assertEquals(1, harness.consoles.opens.size());
        String command = harness.consoles.opens.get(0).plan().command();
        assertEquals("fake --resume", command);
        assertFalse(command.contains("--continue"), "no-id resume must never use --continue: " + command);
        assertFalse(command.contains("--last"), "no-id resume must never use --last: " + command);
        // RUNNING + lastOpenedAt landed in the persisted state (AC13-adjacent).
        ManagedAgentSession row = persisted(harness, stored.id());
        assertEquals(SessionStatus.RUNNING, row.status());
        assertTrue(row.lastOpenedAt().isAfter(createdAt), "resume bumps lastOpenedAt");
        harness.store.flush();
    }

    @Test
    void resumeWithAKnownIdPassesThePersistedIdAndName(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingProvider provider = new RecordingProvider(
                AgentKind.PI, SessionIdStrategy.DISCOVERED, null, true, false, null, harness.events);
        DrydockSessionService service = harness.service(provider);
        ManagedAgentSession stored = persistSession(harness, AgentKind.PI, "Named session",
                new AgentBinding(AgentKind.PI, Optional.of("pi-conv-42"), Optional.of("My name")),
                base, SessionStatus.EXITED, Instant.parse("2026-08-12T10:00:00Z"));

        ManagedAgentSession resumed = service.resumeSession(stored.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(SessionStatus.RUNNING, resumed.status());
        assertEquals(1, provider.resumeContexts.size());
        assertEquals(Optional.of("pi-conv-42"), provider.resumeContexts.get(0).agentSessionId());
        assertEquals(Optional.of("My name"), provider.resumeContexts.get(0).agentSessionName());
        assertEquals(1, harness.consoles.opens.size());
        // The persisted id is what the resume command carried (AC20).
        assertTrue(harness.consoles.opens.get(0).plan().command().contains("pi-conv-42"),
                harness.consoles.opens.get(0).plan().command());
        assertEquals(SessionStatus.RUNNING, persisted(harness, stored.id()).status());
        harness.store.flush();
    }

    @Test
    void conversationResumeRecordsAndReusesASessionBoundToTheConversationId(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingProvider provider = new RecordingProvider(
                AgentKind.PI, SessionIdStrategy.DISCOVERED, null, true, false, null, harness.events);
        DrydockSessionService service = harness.service(provider);
        ConversationSource.Conversation conversation = new ConversationSource.Conversation(
                "conv-7", "A talk", 12, Instant.parse("2026-08-12T09:00:00Z"));

        ManagedAgentSession resumed = service.resumeConversation(harness.repository, AgentKind.PI,
                conversation, "Picked chat").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(SessionStatus.RUNNING, resumed.status());
        // AC20's record half: a session row exists, bound to the conversation
        // id, under the picked repository, for the right agent.
        List<ManagedAgentSession> tracking = harness.store.state().sessions().stream()
                .filter(s -> s.agentSessionId().map("conv-7"::equals).orElse(false))
                .toList();
        assertEquals(1, tracking.size());
        ManagedAgentSession recorded = tracking.get(0);
        assertEquals(resumed.id(), recorded.id());
        assertEquals(harness.repository.id(), recorded.repositoryId());
        assertEquals(AgentKind.PI, recorded.agentKind());
        assertEquals("Picked chat", recorded.displayName());
        assertEquals(SessionStatus.RUNNING, recorded.status());
        // The resume went through the with-id path.
        assertEquals(Optional.of("conv-7"), provider.resumeContexts.get(0).agentSessionId());
        // The first execution ends before the second pick: a session whose
        // console is still live refuses a second start.
        harness.consoles.stopped.add(resumed.id());
        // Picking the same conversation again reuses the row instead of
        // creating a duplicate (one row per conversation).
        ManagedAgentSession again = service.resumeConversation(harness.repository, AgentKind.PI,
                conversation, "Picked chat").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(recorded.id(), again.id());
        assertEquals(1, harness.store.state().sessions().stream()
                .filter(s -> s.agentSessionId().map("conv-7"::equals).orElse(false))
                .count());
        harness.store.flush();
    }

    @Test
    void missingWorkingDirectoryStopsTheResumeWithAStatus(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingProvider provider = new RecordingProvider(
                AgentKind.PI, SessionIdStrategy.DISCOVERED, null, true, false, null, harness.events);
        DrydockSessionService service = harness.service(provider);
        Path gone = base.resolve("gone");
        ManagedAgentSession stored = persistSession(harness, AgentKind.PI, "Elsewhere",
                AgentBinding.unlaunched(AgentKind.PI), gone, SessionStatus.INACTIVE,
                Instant.parse("2026-08-12T10:00:00Z"));

        ManagedAgentSession resumed = service.resumeSession(stored.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(SessionStatus.MISSING_WORKING_DIRECTORY, resumed.status());
        assertEquals(SessionStatus.MISSING_WORKING_DIRECTORY, persisted(harness, stored.id()).status());
        // Stopped: no provider was consulted, no tab was opened.
        assertTrue(provider.resumeContexts.isEmpty());
        assertTrue(harness.consoles.opens.isEmpty());
        harness.store.flush();
    }

    @Test
    void anUnsupportedAgentSessionIsNeverResumedByAPlaceholderKind(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingProvider provider = new RecordingProvider(
                AgentKind.CLAUDE, SessionIdStrategy.PRESET, null, true, true, null, harness.events);
        DrydockSessionService service = harness.service(provider);
        // An unrecognized persisted kind decodes with the CLAUDE placeholder;
        // resuming it would silently run the wrong agent.
        ManagedAgentSession stored = persistSession(harness, AgentKind.CLAUDE, "From before",
                AgentBinding.unlaunched(AgentKind.CLAUDE), base, SessionStatus.UNSUPPORTED_AGENT,
                Instant.parse("2026-08-12T10:00:00Z"));

        ManagedAgentSession resumed = service.resumeSession(stored.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(SessionStatus.UNSUPPORTED_AGENT, resumed.status());
        assertTrue(provider.resumeContexts.isEmpty());
        assertTrue(harness.consoles.opens.isEmpty());
        harness.store.flush();
    }

    @Test
    void anUnsupportedResumePlanIsNeverLaunchedAndNamesTheAgentKind(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingProvider provider = new RecordingProvider(
                AgentKind.CLAUDE, SessionIdStrategy.PRESET, null, true, true, null, harness.events) {
            @Override public LaunchPlan buildResumeCommand(ResumeContext r) {
                resumeContexts.add(r);
                return LaunchPlan.unsupported();
            }
        };
        DrydockSessionService service = harness.service(provider);
        ManagedAgentSession stored = persistSession(harness, AgentKind.CLAUDE, "No remote for you",
                new AgentBinding(AgentKind.CLAUDE, Optional.of("claude-1"), Optional.empty()),
                base, SessionStatus.INACTIVE, Instant.parse("2026-08-12T10:00:00Z"));

        CompletableFuture<ManagedAgentSession> resumed = service.resumeSession(stored.id());
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> resumed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof IllegalStateException, "got " + failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("CLAUDE"), failure.getCause().getMessage());
        assertTrue(harness.consoles.opens.isEmpty());
        assertEquals(SessionStatus.FAILED, persisted(harness, stored.id()).status());
        harness.store.flush();
    }

    // ---- lifecycle events -------------------------------------------------------

    @Test
    void theEndRecorderPersistsAnEndAndHoldsNoWindow(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        DrydockSessionService service = harness.service(new RecordingProvider(
                AgentKind.PI, SessionIdStrategy.DISCOVERED, null, true, false, null, harness.events));
        ManagedAgentSession running = persistSession(harness, AgentKind.PI, "Kept running",
                AgentBinding.unlaunched(AgentKind.PI), base, SessionStatus.RUNNING,
                Instant.parse("2026-08-12T10:00:00Z"));

        // The registry keeps this for as long as the agent lives, which can
        // be long after the window that started it closed: it must persist
        // the end on its own, and must not keep that window alive.
        AgentExecutionListener recorder = service.endRecorder();
        recorder.executionTerminated(running.id(), true, null);

        assertEquals(SessionStatus.INACTIVE, persisted(harness, running.id()).status());
        for (Field field : recorder.getClass().getDeclaredFields()) {
            assertFalse(AgentConsoleHost.class.isAssignableFrom(field.getType()),
                    "the recorder holds the window's console host: " + field);
            assertFalse(Project.class.isAssignableFrom(field.getType()),
                    "the recorder holds a project: " + field);
            assertFalse(DrydockSessionService.class.isAssignableFrom(field.getType()),
                    "the recorder holds the window's session service: " + field);
        }
        harness.store.flush();
    }

    @Test
    void aTerminatedExecutionGoesIdleAndTheRowSurvives(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        DrydockSessionService service = harness.service(new RecordingProvider(
                AgentKind.PI, SessionIdStrategy.DISCOVERED, null, true, false, null, harness.events));
        Instant createdAt = Instant.parse("2026-08-12T10:00:00Z");
        ManagedAgentSession natural = persistSession(harness, AgentKind.PI, "Ended itself",
                AgentBinding.unlaunched(AgentKind.PI), base, SessionStatus.RUNNING, createdAt);
        ManagedAgentSession stoppedByUs = persistSession(harness, AgentKind.PI, "Stopped",
                AgentBinding.unlaunched(AgentKind.PI), base, SessionStatus.RUNNING, createdAt);

        // Natural exit -> EXITED; a stop drydock asked for -> INACTIVE.
        // Neither removes the row: that is the whole correction to v2, where
        // the tab going away took the durable session with it.
        service.executionTerminated(natural.id(), false, 0);
        service.executionTerminated(stoppedByUs.id(), true, null);

        ManagedAgentSession exited = persisted(harness, natural.id());
        assertEquals(SessionStatus.EXITED, exited.status());
        assertTrue(exited.lastOpenedAt().isAfter(createdAt));
        assertEquals(Optional.of(0), exited.lastExitCode());

        ManagedAgentSession inactive = persisted(harness, stoppedByUs.id());
        assertEquals(SessionStatus.INACTIVE, inactive.status());
        assertTrue(inactive.lastExitCode().isEmpty(),
                "no exit code was reported, so none must be invented");

        // Idempotent: a second report of the same end is a no-op, not a
        // rewrite (two listeners can see one termination).
        service.executionTerminated(natural.id(), false, 7);
        assertEquals(Optional.of(0), persisted(harness, natural.id()).lastExitCode());

        // A session already forgotten is a no-op, never a throw and never a
        // resurrection.
        service.executionTerminated(ManagedSessionId.newId(), false, 0);
        service.executionTerminated(ManagedSessionId.newId(), true, null);
        harness.store.flush();
    }

    // ---- window close -----------------------------------------------------------

    /**
     * Window close marks the rows idle BEFORE stopping, because the
     * terminated event it would otherwise rely on travels the closing
     * project's message bus and does not arrive. A row left RUNNING with
     * nothing running tells the reopened window that its stopped agent is
     * running, and the startup RUNNING-sweep does not fix it — that runs once
     * per IDE start, not per project open.
     */
    @Test
    void closingTheWindowMarksRowsIdleBeforeStoppingThem(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        DrydockSessionService service = harness.service(new RecordingProvider(
                AgentKind.PI, SessionIdStrategy.DISCOVERED, null, true, false, null, harness.events));
        Instant createdAt = Instant.parse("2026-08-12T10:00:00Z");
        ManagedAgentSession running = persistSession(harness, AgentKind.PI, "Live",
                AgentBinding.unlaunched(AgentKind.PI), base, SessionStatus.RUNNING, createdAt);
        ManagedAgentSession starting = persistSession(harness, AgentKind.PI, "Starting",
                AgentBinding.unlaunched(AgentKind.PI), base, SessionStatus.STARTING, createdAt);
        ManagedAgentSession alreadyIdle = persistSession(harness, AgentKind.PI, "Idle",
                AgentBinding.unlaunched(AgentKind.PI), base, SessionStatus.EXITED, createdAt);

        service.stopSessionsForClose(List.of(running.id(), starting.id(), alreadyIdle.id()));

        assertEquals(SessionStatus.INACTIVE, persisted(harness, running.id()).status());
        assertEquals(SessionStatus.INACTIVE, persisted(harness, starting.id()).status());
        // An already-idle row keeps the status it had: EXITED records that the
        // agent ended on its own, which a close must not overwrite.
        assertEquals(SessionStatus.EXITED, persisted(harness, alreadyIdle.id()).status());
        // The mark lands before the stop, so a bus that dies mid-close cannot
        // lose it.
        assertEquals(List.of("stop", "stop", "stop"), harness.events);
        assertEquals(List.of(running.id(), starting.id(), alreadyIdle.id()), harness.consoles.stopped);
        harness.store.flush();
    }

    @Test
    void closingAWindowWithNoAgentsTouchesNothing(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        DrydockSessionService service = harness.service(new RecordingProvider(
                AgentKind.PI, SessionIdStrategy.DISCOVERED, null, true, false, null, harness.events));

        service.stopSessionsForClose(List.of());

        assertTrue(harness.events.isEmpty());
        harness.store.flush();
    }

    // ---- late id binding --------------------------------------------------------

    /**
     * The create-time poll has to give up while the agent may still not have
     * written its session record (pi has taken ~42 s). Termination is the
     * moment the record certainly exists, so that is where the id is bound —
     * and a later resume then resumes that conversation by id instead of
     * dropping the user into the provider's picker.
     */
    @Test
    void stoppingADiscoveredSessionBindsTheAgentSessionIdItCouldNotDiscover(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingDiscovery discovery = new RecordingDiscovery(harness.events);
        // No create-time candidate: discover() comes back empty, as it does
        // when the agent has not written its record yet.
        discovery.lateCandidates.add("pi-session-late");
        RecordingProvider provider = new RecordingProvider(
                AgentKind.PI, SessionIdStrategy.DISCOVERED, discovery, true, false, null, harness.events);
        DrydockSessionService service = harness.service(provider);

        ManagedAgentSession running = service.launchSession(
                service.prepareSession(base, AgentKind.PI, "Late").get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(persisted(harness, running.id()).agentSessionId().isEmpty(),
                "discovery found nothing at create time");

        service.executionTerminated(running.id(), true, null);

        assertEquals(Optional.of("pi-session-late"), persisted(harness, running.id()).agentSessionId());
        // Resolve is asked about this launch, not the row's creation: the
        // record the agent writes carries its own launch time.
        assertEquals(1, discovery.createdAtsReceived.size());
        assertFalse(discovery.createdAtsReceived.get(0).isBefore(running.createdAt()));
        harness.store.flush();
    }

    /**
     * A row started again later (Start after a stop) launches a new agent
     * session, minutes or days after the row was created. The record it
     * writes has this launch's time, so that is what resolve must match
     * against; the row's creation time is far outside resolve's tolerance.
     * Seen live with Codex: row created 13:10, relaunched agent's thread
     * 13:13:29, nothing bound at Stop.
     */
    @Test
    void aRelaunchedRowIsMatchedAgainstThisLaunchNotTheRowsCreation(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingDiscovery discovery = new RecordingDiscovery(harness.events);
        discovery.lateCandidates.add("codex-thread-2");
        RecordingProvider provider = new RecordingProvider(
                AgentKind.CODEX, SessionIdStrategy.DISCOVERED, discovery, true, false, null, harness.events);
        DrydockSessionService service = harness.service(provider);
        ManagedAgentSession old = persistSession(harness, AgentKind.CODEX, "Old row",
                AgentBinding.unlaunched(AgentKind.CODEX), base, SessionStatus.INACTIVE,
                Instant.parse("2026-08-12T10:00:00Z"));
        Instant beforeLaunch = Instant.now();

        service.startSession(old.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        service.executionTerminated(old.id(), true, null);

        assertEquals(1, discovery.createdAtsReceived.size());
        assertFalse(discovery.createdAtsReceived.get(0).isBefore(beforeLaunch),
                "resolve must be anchored at this launch, got " + discovery.createdAtsReceived.get(0));
        assertEquals(Optional.of("codex-thread-2"), persisted(harness, old.id()).agentSessionId());
        harness.store.flush();
    }

    @Test
    void aBoundIdIsNeverReplacedByALaterResolve(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingDiscovery discovery = new RecordingDiscovery(harness.events);
        discovery.candidates.add("pi-session-1");
        // Would be bound if anything asked; nothing must.
        discovery.lateCandidates.add("pi-session-WRONG");
        RecordingProvider provider = new RecordingProvider(
                AgentKind.PI, SessionIdStrategy.DISCOVERED, discovery, true, false, null, harness.events);
        DrydockSessionService service = harness.service(provider);

        ManagedAgentSession running = service.launchSession(
                service.prepareSession(base, AgentKind.PI, "Early").get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(Optional.of("pi-session-1"), persisted(harness, running.id()).agentSessionId());

        service.executionTerminated(running.id(), false, 0);

        // Rebinding a known id to a different conversation would resume the
        // wrong transcript — which looks like success, and is worse than the
        // picker.
        assertEquals(Optional.of("pi-session-1"), persisted(harness, running.id()).agentSessionId());
        assertTrue(discovery.createdAtsReceived.isEmpty(), "resolve must not even be asked");
        harness.store.flush();
    }

    @Test
    void aPresetSessionIsNeverLateBound(@TempDir Path base) throws Exception {
        // Claude mints its own id up front; there is no store to resolve
        // against, and asking would be meaningless.
        Harness harness = harness(base);
        RecordingProvider provider = new RecordingProvider(
                AgentKind.CLAUDE, SessionIdStrategy.PRESET, null, true, false, null, harness.events);
        DrydockSessionService service = harness.service(provider);
        ManagedAgentSession running = service.launchSession(
                service.prepareSession(base, AgentKind.CLAUDE, "Preset").get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        service.executionTerminated(running.id(), true, null);

        assertFalse(harness.events.contains("resolve"));
        harness.store.flush();
    }

    /**
     * An IDE killed outright never runs the termination binding, so resume
     * tries once more before falling back to the picker — otherwise such a
     * session would resume into the picker forever.
     */
    @Test
    void resumeLateBindsBeforeFallingBackToThePicker(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingDiscovery discovery = new RecordingDiscovery(harness.events);
        discovery.lateCandidates.add("pi-session-recovered");
        RecordingProvider provider = new RecordingProvider(
                AgentKind.PI, SessionIdStrategy.DISCOVERED, discovery, true, false, null, harness.events);
        DrydockSessionService service = harness.service(provider);
        // A row as an interrupted run leaves it: idle, no agent session id.
        ManagedAgentSession idle = persistSession(harness, AgentKind.PI, "Recovered",
                AgentBinding.unlaunched(AgentKind.PI), base, SessionStatus.INACTIVE,
                Instant.parse("2026-08-12T10:00:00Z"));

        service.resumeSession(idle.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(Optional.of("pi-session-recovered"), persisted(harness, idle.id()).agentSessionId());
        // ...and the resume command targets it, rather than the picker.
        assertEquals(1, provider.resumeContexts.size());
        assertEquals(Optional.of("pi-session-recovered"), provider.resumeContexts.get(0).agentSessionId());
        assertEquals(1, harness.consoles.opens.size());
        assertTrue(harness.consoles.opens.get(0).plan().command().contains("pi-session-recovered"),
                harness.consoles.opens.get(0).plan().command());
        harness.store.flush();
    }

    @Test
    void restoreLateBindsBeforeChoosingResumeOrFreshStart(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingDiscovery discovery = new RecordingDiscovery(harness.events);
        discovery.lateCandidates.add("codex-thread-recovered");
        RecordingProvider provider = new RecordingProvider(
                AgentKind.CODEX, SessionIdStrategy.DISCOVERED, discovery, true, false, null, harness.events);
        DrydockSessionService service = harness.service(provider);
        ManagedAgentSession idle = persistSession(harness, AgentKind.CODEX, "Recovered after quit",
                AgentBinding.unlaunched(AgentKind.CODEX), base, SessionStatus.INACTIVE,
                Instant.parse("2026-08-12T10:00:00Z"));

        service.resumeOrStartSession(idle.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(Optional.of("codex-thread-recovered"),
                persisted(harness, idle.id()).agentSessionId());
        assertEquals(1, provider.resumeContexts.size(), "the recovered conversation must be resumed");
        assertTrue(provider.createContexts.isEmpty(), "restore must not create a replacement conversation");
        assertEquals(Optional.of("codex-thread-recovered"), provider.resumeContexts.getFirst().agentSessionId());
        harness.store.flush();
    }

    // ---- starting fresh on an existing row --------------------------------------

    /**
     * A session the agent never recorded a conversation for has nothing to
     * resume into, so the strip offers Start instead — which runs the
     * provider's create command on the row that already exists, rather than
     * minting a second row for the same worktree.
     */
    @Test
    void startingFreshRunsTheCreateCommandOnTheExistingRow(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingProvider provider = new RecordingProvider(
                AgentKind.CLAUDE, SessionIdStrategy.PRESET, null, true, true, null, harness.events);
        DrydockSessionService service = harness.service(provider);
        ManagedAgentSession idle = persistSession(harness, AgentKind.CLAUDE, "Nothing recorded",
                AgentBinding.unlaunched(AgentKind.CLAUDE), base, SessionStatus.INACTIVE,
                Instant.parse("2026-08-12T10:00:00Z"));
        int rowsBefore = harness.store.state().sessions().size();

        ManagedAgentSession started = service.startSession(idle.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(SessionStatus.RUNNING, started.status());
        assertEquals(idle.id(), started.id(), "the row keeps its identity");
        assertEquals(rowsBefore, harness.store.state().sessions().size(),
                "starting fresh must not mint a second row for the same worktree");
        // The CREATE command, not a resume: a fresh conversation is the point.
        assertEquals(1, provider.createContexts.size());
        assertTrue(provider.resumeContexts.isEmpty());
        // ...and the new conversation's id is bound to the row.
        assertEquals(Optional.of(provider.createContexts.get(0).sessionId()),
                persisted(harness, idle.id()).agentSessionId());
        harness.store.flush();
    }

    @Test
    void startingFreshIsRefusedWhenThereIsAConversationToResume(@TempDir Path base) throws Exception {
        // Starting fresh over a recorded conversation would strand it: the row
        // is the only way back to it, so rebinding would lose it for good.
        Harness harness = harness(base);
        DrydockSessionService service = harness.service(new RecordingProvider(
                AgentKind.CLAUDE, SessionIdStrategy.PRESET, null, true, true, null, harness.events));
        ManagedAgentSession withConversation = persistSession(harness, AgentKind.CLAUDE, "Has one",
                AgentBinding.unlaunched(AgentKind.CLAUDE).withSessionId(Optional.of("claude-abc")),
                base, SessionStatus.INACTIVE, Instant.parse("2026-08-12T10:00:00Z"));

        assertThrows(ExecutionException.class, () ->
                service.startSession(withConversation.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue(harness.consoles.opens.isEmpty(), "nothing is launched");
        assertEquals(Optional.of("claude-abc"),
                persisted(harness, withConversation.id()).agentSessionId());
        harness.store.flush();
    }

    @Test
    void startingFreshReplacesAnIdWithNothingRecordedUnderIt(@TempDir Path base) throws Exception {
        // Claude is given its id at launch. A task stopped before its first
        // turn keeps that id with no transcript behind it; resuming it fails
        // ("No conversation found"), so starting fresh must be allowed and
        // must bind the new conversation's id instead.
        Harness harness = harness(base);
        RecordingProvider provider = new RecordingProvider(
                AgentKind.CLAUDE, SessionIdStrategy.PRESET, null, true, true, null, harness.events) {
            @Override
            public Optional<ConversationSource> conversations() {
                return Optional.of(new ConversationSource() {
                    @Override
                    public List<Conversation> listConversations(Path workingDirectory) {
                        return List.of();
                    }

                    @Override
                    public boolean transcriptExists(Path workingDirectory, String agentSessionId) {
                        return false;
                    }
                });
            }
        };
        DrydockSessionService service = harness.service(provider);
        ManagedAgentSession neverPrompted = persistSession(harness, AgentKind.CLAUDE, "Never prompted",
                AgentBinding.unlaunched(AgentKind.CLAUDE).withSessionId(Optional.of("claude-empty")),
                base, SessionStatus.INACTIVE, Instant.parse("2026-08-12T10:00:00Z"));

        ManagedAgentSession started = service.startSession(neverPrompted.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(SessionStatus.RUNNING, started.status());
        assertEquals(1, provider.createContexts.size());
        assertEquals(Optional.of(provider.createContexts.get(0).sessionId()),
                persisted(harness, neverPrompted.id()).agentSessionId());
        harness.store.flush();
    }

    // ---- rename and delete -----------------------------------------------------

    @Test
    void humanRenameNormalizesAndPinsTheSessionName(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        DrydockSessionService service = harness.service(new RecordingProvider(
                AgentKind.CLAUDE, SessionIdStrategy.PRESET, null, true, true, null, harness.events));
        ManagedAgentSession session = persistSession(harness, AgentKind.CLAUDE, "Generated name",
                AgentBinding.unlaunched(AgentKind.CLAUDE), base, SessionStatus.INACTIVE,
                Instant.parse("2026-08-12T10:00:00Z"));

        ManagedAgentSession renamed = service.renameSession(session.id(), "  Human\u2003name  ")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals("Human name", renamed.displayName());
        assertTrue(renamed.namePinned(), "a later transcript refresh must not replace a human rename");
        assertEquals("Human name", persisted(harness, session.id()).displayName());
        assertTrue(persisted(harness, session.id()).namePinned());
        harness.store.flush();
    }

    @Test
    void humanRenameRefusesASiblingNameWithoutChangingEitherSession(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        DrydockSessionService service = harness.service(new RecordingProvider(
                AgentKind.CLAUDE, SessionIdStrategy.PRESET, null, true, true, null, harness.events));
        ManagedAgentSession first = persistSession(harness, AgentKind.CLAUDE, "First",
                AgentBinding.unlaunched(AgentKind.CLAUDE), base, SessionStatus.INACTIVE,
                Instant.parse("2026-08-12T10:00:00Z"));
        persistSession(harness, AgentKind.PI, "Existing name",
                AgentBinding.unlaunched(AgentKind.PI), base, SessionStatus.INACTIVE,
                Instant.parse("2026-08-12T10:00:01Z"));

        ExecutionException failure = assertThrows(ExecutionException.class, () ->
                service.renameSession(first.id(), " existing   NAME ")
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        assertTrue(failure.getCause() instanceof IllegalArgumentException);
        assertTrue(failure.getCause().getMessage().contains("already named"));
        assertEquals("First", persisted(harness, first.id()).displayName());
        assertFalse(persisted(harness, first.id()).namePinned());
        harness.store.flush();
    }

    @Test
    void agentRenameRefusesAPinnedNameWithoutChangingIt(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        DrydockSessionService service = harness.service(new RecordingProvider(
                AgentKind.CLAUDE, SessionIdStrategy.PRESET, null, true, true, null, harness.events));
        ManagedAgentSession session = persistSession(harness, AgentKind.CLAUDE, "Generated name",
                AgentBinding.unlaunched(AgentKind.CLAUDE), base, SessionStatus.INACTIVE,
                Instant.parse("2026-08-12T10:00:00Z"));
        service.renameSession(session.id(), "Human name").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        RenameOutcome outcome = service.applyAgentRename(session.id(), "Agent title")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(RenameKind.PINNED, outcome.kind());
        assertEquals("Human name", outcome.currentName());
        assertEquals("Human name", persisted(harness, session.id()).displayName());
        harness.store.flush();
    }

    @Test
    void agentRenameAppliesAndLeavesTheNameUnpinned(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        DrydockSessionService service = harness.service(new RecordingProvider(
                AgentKind.CLAUDE, SessionIdStrategy.PRESET, null, true, true, null, harness.events));
        ManagedAgentSession session = persistSession(harness, AgentKind.CLAUDE, "Generated name",
                AgentBinding.unlaunched(AgentKind.CLAUDE), base, SessionStatus.INACTIVE,
                Instant.parse("2026-08-12T10:00:00Z"));

        RenameOutcome outcome = service.applyAgentRename(session.id(), "Fix the login flow")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(RenameKind.RENAMED, outcome.kind());
        assertEquals("Fix the login flow", outcome.currentName());
        assertFalse(persisted(harness, session.id()).namePinned());
        harness.store.flush();
    }

    @Test
    void agentRenameReportsUnchangedAndCollided(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        DrydockSessionService service = harness.service(new RecordingProvider(
                AgentKind.CLAUDE, SessionIdStrategy.PRESET, null, true, true, null, harness.events));
        ManagedAgentSession first = persistSession(harness, AgentKind.CLAUDE, "First",
                AgentBinding.unlaunched(AgentKind.CLAUDE), base, SessionStatus.INACTIVE,
                Instant.parse("2026-08-12T10:00:00Z"));
        persistSession(harness, AgentKind.PI, "Existing name",
                AgentBinding.unlaunched(AgentKind.PI), base, SessionStatus.INACTIVE,
                Instant.parse("2026-08-12T10:00:01Z"));

        assertEquals(RenameKind.UNCHANGED,
                service.applyAgentRename(first.id(), "First").get(TIMEOUT_SECONDS, TimeUnit.SECONDS).kind());
        assertEquals(RenameKind.COLLIDED,
                service.applyAgentRename(first.id(), "existing name").get(TIMEOUT_SECONDS, TimeUnit.SECONDS).kind());
        assertEquals("First", persisted(harness, first.id()).displayName());
        harness.store.flush();
    }

    @Test
    void aBlurRenameAppliesButNeverClearsAnEarlierPin(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        DrydockSessionService service = harness.service(new RecordingProvider(
                AgentKind.CLAUDE, SessionIdStrategy.PRESET, null, true, true, null, harness.events));
        ManagedAgentSession session = persistSession(harness, AgentKind.CLAUDE, "Generated name",
                AgentBinding.unlaunched(AgentKind.CLAUDE), base, SessionStatus.INACTIVE,
                Instant.parse("2026-08-12T10:00:00Z"));
        service.renameSession(session.id(), "Human name").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        ManagedAgentSession renamed = service.renameSession(session.id(), "Human name 2", false)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals("Human name 2", renamed.displayName());
        assertTrue(renamed.namePinned(), "a focus-loss commit must not unclaim a human-named session");
        harness.store.flush();
    }

    @Test
    void deleteForgetsActivityStateAndRemovesTheSession(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingProvider provider = new RecordingProvider(
                AgentKind.CLAUDE, SessionIdStrategy.PRESET, null, true, true, null, harness.events);
        DrydockSessionService service = harness.service(provider);

        ManagedAgentSession running = service.launchSession(
                service.prepareSession(base, AgentKind.CLAUDE, "Bye").get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        String agentSessionId = running.agentSessionId().orElseThrow();
        // Activity state left behind for the session, as the Claude hooks write it.
        Path activityDir = harness.base.resolve("activity");
        Files.createDirectories(activityDir);
        Path stateFile = activityDir.resolve(agentSessionId);
        Files.writeString(stateFile, "busy");

        service.deleteSession(running.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // Forgetting a session takes its running agent with it: the row is
        // gone, so nothing would ever show or stop that process again.
        assertEquals(List.of(running.id()), harness.consoles.forgotten,
                "forgetting a session must discard its execution");
        assertTrue(Files.notExists(stateFile), "delete must make the watcher forget the activity state");
        assertTrue(harness.store.state().sessions().stream()
                .noneMatch(session -> session.id().equals(running.id())));
        harness.store.flush();
    }

    @Test
    void deletingAnUnknownSessionFailsLoudly(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        DrydockSessionService service = harness.service(new RecordingProvider(
                AgentKind.CLAUDE, SessionIdStrategy.PRESET, null, true, true, null, harness.events));

        assertThrows(ExecutionException.class, () ->
                service.deleteSession(ManagedSessionId.newId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        harness.store.flush();
    }

    // ---- threading ---------------------------------------------------------------

    @Test
    void theLaunchWorkNeverRunsOnTheCallerThread(@TempDir Path base) throws Exception {
        Repository repository = new Repository(RepositoryId.newId(), base, "repo",
                Instant.parse("2026-08-12T10:00:00Z"), Instant.parse("2026-08-12T10:00:00Z"),
                RepositorySettings.DEFAULT);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Harness harness = new Harness(base, repository, executor);
            harness.store.update(state -> state.withRepositories(List.of(repository)));
            CountDownLatch arrived = new CountDownLatch(1);
            CountDownLatch gate = new CountDownLatch(1);
            RecordingProvider provider = new RecordingProvider(
                    AgentKind.CLAUDE, SessionIdStrategy.PRESET, null, true, true, null,
                    harness.events, arrived, gate);
            DrydockSessionService service = harness.service(provider);

            ManagedAgentSession prepared = service.prepareSession(base, AgentKind.CLAUDE, "Async")
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            CompletableFuture<ManagedAgentSession> launched = service.launchSession(prepared);

            // The command build blocks on the executor until released; the
            // caller's thread is free the whole time (AC12/AC7 shape).
            assertTrue(arrived.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertFalse(launched.isDone(), "launchSession must not block its caller");
            gate.countDown();
            assertEquals(SessionStatus.RUNNING,
                    launched.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).status());
            assertNotEquals(Thread.currentThread(), provider.threads.get(0),
                    "the launch work runs on the executor, not the caller");
            harness.store.flush();
        } finally {
            executor.shutdown();
        }
    }

    // ---- IDE MCP routing ---------------------------------------------------------

    private static Function<Path, List<StdioMcpServer>> ideServerRoutedTo(List<Path> askedFor) {
        return path -> {
            askedFor.add(path);
            return List.of(new StdioMcpServer("jetbrains", "/jbr/bin/java", List.of("Main"),
                    Map.of("IJ_MCP_SERVER_PROJECT_PATH", path.toString())));
        };
    }

    @Test
    void aCreatedSessionGetsTheIdeServerRoutedToItsOwnWorktree(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingProvider provider = new RecordingProvider(
                AgentKind.CLAUDE, SessionIdStrategy.PRESET, null, true, true, null, harness.events);
        List<Path> askedFor = new CopyOnWriteArrayList<>();
        DrydockSessionService service = harness.service(ideServerRoutedTo(askedFor), provider);

        ManagedAgentSession prepared = service.prepareSession(base, AgentKind.CLAUDE, "My session")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        service.launchSession(prepared).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(List.of(prepared.workingDirectory()), askedFor);
        assertEquals(prepared.workingDirectory().toString(), provider.createContexts.get(0).stdioMcpServers()
                .get(0).env().get("IJ_MCP_SERVER_PROJECT_PATH"));
        harness.store.flush();
    }

    @Test
    void aResumedSessionGetsTheIdeServerRoutedToItsOwnWorktree(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingProvider provider = new RecordingProvider(
                AgentKind.PI, SessionIdStrategy.DISCOVERED, null, true, false, null, harness.events);
        List<Path> askedFor = new CopyOnWriteArrayList<>();
        DrydockSessionService service = harness.service(ideServerRoutedTo(askedFor), provider);
        ManagedAgentSession stored = persistSession(harness, AgentKind.PI, "Named session",
                new AgentBinding(AgentKind.PI, Optional.of("pi-conv-42"), Optional.empty()),
                base, SessionStatus.EXITED, Instant.parse("2026-08-12T10:00:00Z"));

        service.resumeSession(stored.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(List.of(stored.workingDirectory()), askedFor);
        assertEquals(stored.workingDirectory().toString(), provider.resumeContexts.get(0).stdioMcpServers()
                .get(0).env().get("IJ_MCP_SERVER_PROJECT_PATH"));
        harness.store.flush();
    }

    @Test
    void transferSummaryParserAcceptsJsonEscapedEnvelope() {
        String output = "{\"result\":\"noise\\nDRYDOCK_TRANSFER_V1_BEGIN_\\n"
                + "GOAL: finish it\\nCOMPLETED: tests\\nNEXT: build\\nRISKS: none\\n"
                + "DRYDOCK_TRANSFER_V1_END_\"}";

        String parsed = DrydockSessionService.parseTransferSummary(output);

        assertTrue(parsed.startsWith("DRYDOCK_TRANSFER_V1_BEGIN_\nGOAL:"));
        assertTrue(parsed.endsWith("DRYDOCK_TRANSFER_V1_END_"));
    }

    @Test
    void transferSummaryParserRejectsMissingFields() {
        assertThrows(IllegalStateException.class, () -> DrydockSessionService.parseTransferSummary(
                "DRYDOCK_TRANSFER_V1_BEGIN_\nGOAL: x\nDRYDOCK_TRANSFER_V1_END_"));
    }

    @Test
    void transferSummaryParserAcceptsMultilineFieldValues() {
        String output = "DRYDOCK_TRANSFER_V1_BEGIN_\n"
                + "GOAL: finish it\n"
                + "COMPLETED:\n- implementation\n- tests\n"
                + "NEXT:\n1. build the plugin\n2. reinstall it\n"
                + "RISKS:\n- none\n"
                + "DRYDOCK_TRANSFER_V1_END_";

        String parsed = DrydockSessionService.parseTransferSummary(output);

        assertTrue(parsed.contains("NEXT:\n1. build the plugin\n2. reinstall it"));
    }

    @Test
    void transferKeepsTheManagedIdentityAndCommitsTheTargetOnlyAfterOpen(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingProvider sourceProvider = transferSource(harness.events);
        RecordingProvider targetProvider = new RecordingProvider(
                AgentKind.CODEX, SessionIdStrategy.DISCOVERED, null, true, false, null, harness.events);
        DrydockSessionService service = harness.service(sourceProvider, targetProvider);
        ManagedAgentSession source = persistSession(harness, AgentKind.CLAUDE, "Same task",
                AgentBinding.unlaunched(AgentKind.CLAUDE).withSessionId(Optional.of("source-42")),
                base, SessionStatus.INACTIVE, Instant.now());

        ManagedAgentSession transferred = service.transferStoppedSession(source.id(), AgentKind.CODEX)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(source.id(), transferred.id());
        assertEquals(source.displayName(), transferred.displayName());
        assertEquals(source.workspace(), transferred.workspace());
        assertEquals(AgentKind.CODEX, persisted(harness, source.id()).agentKind());
        assertTrue(persisted(harness, source.id()).agentSessionId().isEmpty());
        assertEquals(source.id(), harness.consoles.opens.getFirst().sessionId());
        assertTrue(targetProvider.createContexts.getFirst().initialPrompt().orElseThrow()
                .contains("GOAL: goal"));
        harness.store.flush();
    }

    @Test
    void transferRetriesAnOversizedSummaryWithTheValidationFeedback(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        AtomicInteger attempts = new AtomicInteger();
        RecordingProvider sourceProvider = new RecordingProvider(
                AgentKind.CLAUDE, SessionIdStrategy.PRESET, null, true, true, null, harness.events) {
            @Override
            public Optional<List<String>> buildTransferSummaryCommand(Path workingDirectory,
                                                                       String agentSessionId,
                                                                       String prompt) {
                String completed = attempts.getAndIncrement() == 0 ? "x".repeat(2001) : "concise";
                String answer = prompt.replace("<current goal>", "goal")
                        .replace("<what is done>", completed)
                        .replace("<concrete next steps>", "next")
                        .replace("<constraints, failures, or none>", "none");
                return Optional.of(List.of("/usr/bin/printf", "%s", answer));
            }
        };
        RecordingProvider targetProvider = new RecordingProvider(
                AgentKind.CODEX, SessionIdStrategy.DISCOVERED, null, true, false, null, harness.events);
        DrydockSessionService service = harness.service(sourceProvider, targetProvider);
        ManagedAgentSession source = persistSession(harness, AgentKind.CLAUDE, "Same task",
                AgentBinding.unlaunched(AgentKind.CLAUDE).withSessionId(Optional.of("source-42")),
                base, SessionStatus.INACTIVE, Instant.now());

        ManagedAgentSession transferred = service.transferStoppedSession(source.id(), AgentKind.CODEX)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(2, attempts.get());
        assertEquals(AgentKind.CODEX, transferred.agentKind());
        assertTrue(targetProvider.createContexts.getFirst().initialPrompt().orElseThrow()
                .contains("COMPLETED: concise"));
        harness.store.flush();
    }

    @Test
    void failedTargetLaunchLeavesTheSourceBindingResumable(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingProvider sourceProvider = transferSource(harness.events);
        RecordingProvider targetProvider = new RecordingProvider(
                AgentKind.CODEX, SessionIdStrategy.DISCOVERED, null, false, false, null, harness.events);
        DrydockSessionService service = harness.service(sourceProvider, targetProvider);
        AgentBinding sourceBinding = AgentBinding.unlaunched(AgentKind.CLAUDE)
                .withSessionId(Optional.of("source-42"));
        ManagedAgentSession source = persistSession(harness, AgentKind.CLAUDE, "Same task",
                sourceBinding, base, SessionStatus.INACTIVE, Instant.now());

        assertThrows(ExecutionException.class, () -> service.transferStoppedSession(source.id(), AgentKind.CODEX)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        assertEquals(sourceBinding, persisted(harness, source.id()).binding());
        assertTrue(harness.consoles.opens.isEmpty());
        harness.store.flush();
    }

    private static RecordingProvider transferSource(List<String> events) {
        return new RecordingProvider(
                AgentKind.CLAUDE, SessionIdStrategy.PRESET, null, true, true, null, events) {
            @Override
            public Optional<List<String>> buildTransferSummaryCommand(Path workingDirectory,
                                                                       String agentSessionId,
                                                                       String prompt) {
                String answer = prompt.replace("<current goal>", "goal")
                        .replace("<what is done>", "completed")
                        .replace("<concrete next steps>", "next")
                        .replace("<constraints, failures, or none>", "none");
                return Optional.of(List.of("/usr/bin/printf", "%s", answer));
            }
        };
    }

    // ---- Drydock MCP access (AC6, release half of AC7) -------------------------

    private static RecordingProvider claudeProvider(Harness harness, boolean supported) {
        RecordingProvider provider = new RecordingProvider(
                AgentKind.CLAUDE, SessionIdStrategy.PRESET, null, supported, true, null, harness.events);
        provider.delivery = McpDelivery.CONFIG_FILE;
        return provider;
    }

    private static ManagedAgentSession exitedClaudeRow(Harness harness, Path base) {
        return persistSession(harness, AgentKind.CLAUDE, "Row",
                new AgentBinding(AgentKind.CLAUDE, Optional.of("claude-conv-1"), Optional.empty()),
                base, SessionStatus.EXITED, Instant.parse("2026-08-12T10:00:00Z"));
    }

    @Test
    void createPassesTheGrantedMcpAccessToTheProvider(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingProvider provider = claudeProvider(harness, true);
        RecordingAccess access = RecordingAccess.granting();
        DrydockSessionService service = harness.service(access, provider);

        ManagedAgentSession prepared = service.prepareSession(base, AgentKind.CLAUDE, "S")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        service.launchSession(prepared).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(List.of(McpDelivery.CONFIG_FILE), access.requested);
        assertTrue(provider.createContexts.get(0).mcp().isPresent());
        assertTrue(access.released.isEmpty());
        harness.store.flush();
    }

    @Test
    void createAsksForTheProvidersDeliveryAsIs(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingProvider provider = claudeProvider(harness, true);
        provider.delivery = McpDelivery.NONE;
        RecordingAccess access = RecordingAccess.declining();
        DrydockSessionService service = harness.service(access, provider);

        ManagedAgentSession prepared = service.prepareSession(base, AgentKind.CLAUDE, "S")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        service.launchSession(prepared).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(List.of(McpDelivery.NONE), access.requested);
        assertTrue(provider.createContexts.get(0).mcp().isEmpty());
        harness.store.flush();
    }

    @Test
    void createStillRunsWhenMcpAccessIsDeclinedOrThrows(@TempDir Path base) throws Exception {
        for (RecordingAccess access : List.of(RecordingAccess.declining(), RecordingAccess.throwing())) {
            Harness harness = harness(Files.createDirectories(base.resolve("h" + access.hashCode())));
            RecordingProvider provider = claudeProvider(harness, true);
            DrydockSessionService service = harness.service(access, provider);

            ManagedAgentSession prepared = service.prepareSession(base, AgentKind.CLAUDE, "S")
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            ManagedAgentSession running = service.launchSession(prepared).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertEquals(SessionStatus.RUNNING, running.status());
            assertTrue(provider.createContexts.get(0).mcp().isEmpty());
            harness.store.flush();
        }
    }

    @Test
    void resumePassesTheGrantedMcpAccessToTheProvider(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingProvider provider = claudeProvider(harness, true);
        RecordingAccess access = RecordingAccess.granting();
        DrydockSessionService service = harness.service(access, provider);
        ManagedAgentSession stored = exitedClaudeRow(harness, base);

        service.resumeSession(stored.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(List.of(McpDelivery.CONFIG_FILE), access.requested);
        assertTrue(provider.resumeContexts.get(0).mcp().isPresent());
        assertTrue(access.released.isEmpty());
        harness.store.flush();
    }

    @Test
    void resumeStillRunsWhenMcpAccessIsDeclinedOrThrows(@TempDir Path base) throws Exception {
        for (RecordingAccess access : List.of(RecordingAccess.declining(), RecordingAccess.throwing())) {
            Harness harness = harness(Files.createDirectories(base.resolve("h" + access.hashCode())));
            RecordingProvider provider = claudeProvider(harness, true);
            DrydockSessionService service = harness.service(access, provider);
            ManagedAgentSession stored = exitedClaudeRow(harness, base);

            ManagedAgentSession resumed = service.resumeSession(stored.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertEquals(SessionStatus.RUNNING, resumed.status());
            assertTrue(provider.resumeContexts.get(0).mcp().isEmpty());
            harness.store.flush();
        }
    }

    @Test
    void theProductionEndPathReleasesTheSessionsMcpAccess(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingProvider provider = claudeProvider(harness, true);
        RecordingAccess access = RecordingAccess.granting();
        DrydockSessionService service = harness.service(access, provider);
        ManagedAgentSession stored = exitedClaudeRow(harness, base);

        service.endRecorder().executionTerminated(stored.id(), true, null);

        assertEquals(List.of(stored.id()), access.released);
        harness.store.flush();
    }

    @Test
    void aTerminationIsNotReleasedWhileANewExecutionOfTheSessionIsRunning(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingProvider provider = claudeProvider(harness, true);
        RecordingAccess access = RecordingAccess.granting();
        ManagedAgentSession stored = exitedClaudeRow(harness, base);
        harness.liveExecution = id -> id.equals(stored.id());
        DrydockSessionService service = harness.service(access, provider);

        service.endRecorder().executionTerminated(stored.id(), false, 0);

        assertTrue(access.released.isEmpty());
        harness.store.flush();
    }

    @Test
    void aResumeWhoseConsoleStartThrowsSynchronouslyReleasesTheMintedAccess(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingProvider provider = claudeProvider(harness, true);
        RecordingAccess access = RecordingAccess.granting();
        DrydockSessionService service = harness.service(access, provider);
        ManagedAgentSession stored = exitedClaudeRow(harness, base);
        harness.consoles.startFailure = new IllegalStateException("console start failed");

        assertThrows(Exception.class, () -> service.resumeSession(stored.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        assertEquals(List.of(stored.id()), access.released);
        harness.consoles.startFailure = null;
        assertEquals(SessionStatus.RUNNING,
                service.resumeSession(stored.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).status(),
                "the failed resume ended its claim");
        harness.store.flush();
    }

    @Test
    void forgettingASessionReleasesItsMcpAccess(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingProvider provider = claudeProvider(harness, true);
        RecordingAccess access = RecordingAccess.granting();
        DrydockSessionService service = harness.service(access, provider);
        ManagedAgentSession stored = exitedClaudeRow(harness, base);

        service.deleteSession(stored.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(List.of(stored.id()), access.released);
        harness.store.flush();
    }

    @Test
    void anUnsupportedCreatePlanReleasesTheMintedAccess(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingProvider provider = claudeProvider(harness, false);
        RecordingAccess access = RecordingAccess.granting();
        DrydockSessionService service = harness.service(access, provider);

        ManagedAgentSession prepared = service.prepareSession(base, AgentKind.CLAUDE, "S")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThrows(ExecutionException.class,
                () -> service.launchSession(prepared).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        assertEquals(List.of(prepared.id()), access.released);
        harness.store.flush();
    }

    private static Harness concurrentHarness(Path base, ExecutorService executor) {
        Repository repository = new Repository(RepositoryId.newId(), base, "repo",
                Instant.parse("2026-08-12T10:00:00Z"), Instant.parse("2026-08-12T10:00:00Z"),
                RepositorySettings.DEFAULT);
        Harness harness = new Harness(base, repository, executor);
        harness.store.update(state -> state.withRepositories(List.of(repository)));
        return harness;
    }

    private static RecordingProvider gatedClaudeProvider(Harness harness, CountDownLatch arrived,
                                                         CountDownLatch gate) {
        RecordingProvider provider = new RecordingProvider(
                AgentKind.CLAUDE, SessionIdStrategy.PRESET, null, true, true, null, harness.events, arrived, gate);
        provider.delivery = McpDelivery.CONFIG_FILE;
        return provider;
    }

    @Test
    void aResumeWhileTheSessionHasALiveExecutionIsRefusedBeforeAnythingHappens(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingProvider provider = claudeProvider(harness, true);
        RecordingAccess access = RecordingAccess.granting();
        DrydockSessionService service = harness.service(access, provider);
        ManagedAgentSession stored = exitedClaudeRow(harness, base);
        harness.liveExecution = id -> id.equals(stored.id());

        ExecutionException refused = assertThrows(ExecutionException.class,
                () -> service.resumeSession(stored.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        assertTrue(refused.getCause() instanceof IllegalStateException, String.valueOf(refused.getCause()));
        assertTrue(access.requested.isEmpty());
        assertTrue(access.released.isEmpty());
        assertTrue(harness.consoles.opens.isEmpty());
        assertEquals(SessionStatus.EXITED, persisted(harness, stored.id()).status());
        harness.store.flush();
    }

    @Test
    void aSecondResumeWhileTheFirstIsInFlightIsRefused(@TempDir Path base) throws Exception {
        ExecutorService executor = Executors.newCachedThreadPool();
        try {
            Harness harness = concurrentHarness(base, executor);
            CountDownLatch arrived = new CountDownLatch(1);
            CountDownLatch gate = new CountDownLatch(1);
            RecordingProvider provider = gatedClaudeProvider(harness, arrived, gate);
            RecordingAccess access = RecordingAccess.granting();
            DrydockSessionService service = harness.service(access, provider);
            ManagedAgentSession stored = exitedClaudeRow(harness, base);

            CompletableFuture<ManagedAgentSession> first = service.resumeSession(stored.id());
            assertTrue(arrived.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            ExecutionException refused = assertThrows(ExecutionException.class,
                    () -> service.resumeSession(stored.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            gate.countDown();

            assertTrue(refused.getCause() instanceof IllegalStateException, String.valueOf(refused.getCause()));
            assertEquals(SessionStatus.RUNNING, first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).status());
            assertEquals(1, harness.consoles.opens.size());
            assertEquals(1, access.requested.size());
            assertTrue(access.released.isEmpty());
            harness.store.flush();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void aTerminationRacingAnInFlightResumeDoesNotRevokeItsToken(@TempDir Path base) throws Exception {
        ExecutorService executor = Executors.newCachedThreadPool();
        try {
            Harness harness = concurrentHarness(base, executor);
            CountDownLatch arrived = new CountDownLatch(1);
            CountDownLatch gate = new CountDownLatch(1);
            RecordingProvider provider = gatedClaudeProvider(harness, arrived, gate);
            RecordingAccess access = RecordingAccess.granting();
            DrydockSessionService service = harness.service(access, provider);
            ManagedAgentSession stored = exitedClaudeRow(harness, base);

            CompletableFuture<ManagedAgentSession> resumed = service.resumeSession(stored.id());
            assertTrue(arrived.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertEquals(1, access.requested.size(), "the token is minted before the command build");
            service.endRecorder().executionTerminated(stored.id(), false, 0);
            gate.countDown();
            resumed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertTrue(access.released.isEmpty());
            harness.store.flush();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void aResumeWhoseExecutionIsNotLiveAtCompletionReleasesExactlyOnce(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingProvider provider = claudeProvider(harness, true);
        RecordingAccess access = RecordingAccess.granting();
        DrydockSessionService service = harness.service(access, provider);
        ManagedAgentSession stored = exitedClaudeRow(harness, base);
        harness.liveExecution = id -> false;

        ManagedAgentSession resumed = service.resumeSession(stored.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(SessionStatus.RUNNING, resumed.status());
        assertEquals(List.of(stored.id()), access.released);
        harness.store.flush();
    }

    @Test
    void aSecondStartWhileTheFirstIsInFlightIsRefusedWithoutPersistingFailure(@TempDir Path base) throws Exception {
        ExecutorService executor = Executors.newCachedThreadPool();
        try {
            Harness harness = concurrentHarness(base, executor);
            CountDownLatch arrived = new CountDownLatch(1);
            CountDownLatch gate = new CountDownLatch(1);
            RecordingProvider provider = gatedClaudeProvider(harness, arrived, gate);
            RecordingAccess access = RecordingAccess.granting();
            DrydockSessionService service = harness.service(access, provider);
            ManagedAgentSession idle = persistSession(harness, AgentKind.CLAUDE, "Row",
                    new AgentBinding(AgentKind.CLAUDE, Optional.empty(), Optional.empty()),
                    base, SessionStatus.EXITED, Instant.parse("2026-08-12T10:00:00Z"));

            CompletableFuture<ManagedAgentSession> first = service.startSession(idle.id());
            assertTrue(arrived.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            ExecutionException refused = assertThrows(ExecutionException.class,
                    () -> service.startSession(idle.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            assertTrue(refused.getCause() instanceof IllegalStateException, String.valueOf(refused.getCause()));
            assertEquals(SessionStatus.STARTING, persisted(harness, idle.id()).status(),
                    "the loser leaves the winner's STARTING row alone");
            gate.countDown();
            assertEquals(SessionStatus.RUNNING, first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).status());
            assertEquals(1, provider.createContexts.size());
            assertTrue(access.released.isEmpty());
            harness.store.flush();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void aCompletedCreateEndsItsClaim(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        // No id recorded, so the row stays startable rather than resumable.
        RecordingProvider provider = new RecordingProvider(
                AgentKind.CLAUDE, SessionIdStrategy.PRESET, null, true, false, null, harness.events);
        provider.delivery = McpDelivery.CONFIG_FILE;
        DrydockSessionService service = harness.service(RecordingAccess.granting(), provider);
        ManagedAgentSession prepared = service.prepareSession(base, AgentKind.CLAUDE, "S")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        service.launchSession(prepared).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        harness.consoles.stopped.add(prepared.id());

        assertEquals(SessionStatus.RUNNING,
                service.startSession(prepared.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).status());
        assertEquals(2, provider.createContexts.size());
        harness.store.flush();
    }

    @Test
    void anErrorWhileBuildingACreateCommandEndsItsClaim(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingProvider provider = claudeProvider(harness, true);
        RecordingAccess access = RecordingAccess.granting();
        DrydockSessionService service = harness.service(access, provider);
        ManagedAgentSession prepared = service.prepareSession(base, AgentKind.CLAUDE, "S")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        provider.buildError = new AssertionError("provider broke");

        ExecutionException failed = assertThrows(ExecutionException.class,
                () -> service.launchSession(prepared).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        assertTrue(failed.getCause() instanceof AssertionError, String.valueOf(failed.getCause()));
        assertEquals(List.of(prepared.id()), access.released);
        provider.buildError = null;
        assertEquals(SessionStatus.RUNNING,
                service.launchSession(prepared).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).status());
        harness.store.flush();
    }

    @Test
    void anErrorWhileBuildingAResumeCommandEndsItsClaim(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingProvider provider = claudeProvider(harness, true);
        RecordingAccess access = RecordingAccess.granting();
        DrydockSessionService service = harness.service(access, provider);
        ManagedAgentSession stored = exitedClaudeRow(harness, base);
        provider.buildError = new AssertionError("provider broke");

        assertThrows(ExecutionException.class,
                () -> service.resumeSession(stored.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        assertEquals(List.of(stored.id()), access.released);
        provider.buildError = null;
        assertEquals(SessionStatus.RUNNING,
                service.resumeSession(stored.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).status());
        harness.store.flush();
    }

    @Test
    void aCreateCommandWithoutTheCredentialFileReleasesTheAccess(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingProvider provider = claudeProvider(harness, true);
        RecordingAccess access = RecordingAccess.grantingWithFile(base.resolve("mcp").resolve("s.json"));
        DrydockSessionService service = harness.service(access, provider);
        ManagedAgentSession prepared = service.prepareSession(base, AgentKind.CLAUDE, "S")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        ManagedAgentSession running = service.launchSession(prepared).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(SessionStatus.RUNNING, running.status());
        assertEquals(List.of(prepared.id()), access.released);
        harness.store.flush();
    }

    @Test
    void aCreateCommandCarryingTheCredentialFileKeepsTheAccess(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingProvider provider = claudeProvider(harness, true);
        provider.wiresMcp = true;
        RecordingAccess access = RecordingAccess.grantingWithFile(base.resolve("mcp dir").resolve("s.json"));
        DrydockSessionService service = harness.service(access, provider);
        ManagedAgentSession prepared = service.prepareSession(base, AgentKind.CLAUDE, "S")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        service.launchSession(prepared).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue(access.released.isEmpty());
        harness.store.flush();
    }

    @Test
    void aResumeCommandWithoutTheCredentialFileReleasesTheAccess(@TempDir Path base) throws Exception {
        for (boolean wires : List.of(false, true)) {
            Harness harness = harness(Files.createDirectories(base.resolve("wires-" + wires)));
            RecordingProvider provider = claudeProvider(harness, true);
            provider.wiresMcp = wires;
            RecordingAccess access = RecordingAccess.grantingWithFile(base.resolve("mcp").resolve("s.json"));
            DrydockSessionService service = harness.service(access, provider);
            ManagedAgentSession stored = exitedClaudeRow(harness, base);

            service.resumeSession(stored.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertEquals(wires ? List.of() : List.of(stored.id()), access.released, "wires=" + wires);
            harness.store.flush();
        }
    }

    @Test
    void forgettingARunningSessionStopsItAndReleasesItsMcpAccess(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingProvider provider = claudeProvider(harness, true);
        RecordingAccess access = RecordingAccess.granting();
        DrydockSessionService service = harness.service(access, provider);
        ManagedAgentSession stored = exitedClaudeRow(harness, base);
        service.resumeSession(stored.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(access.released.isEmpty());

        service.deleteSession(stored.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(List.of(stored.id()), harness.consoles.forgotten);
        assertEquals(List.of(stored.id()), access.released);
        assertNull(persistedOrNull(harness, stored.id()));
        harness.store.flush();
    }

    @Test
    void forgettingASessionWhileItsLaunchIsInFlightIsRefused(@TempDir Path base) throws Exception {
        ExecutorService executor = Executors.newCachedThreadPool();
        try {
            Harness harness = concurrentHarness(base, executor);
            CountDownLatch arrived = new CountDownLatch(1);
            CountDownLatch gate = new CountDownLatch(1);
            RecordingProvider provider = gatedClaudeProvider(harness, arrived, gate);
            RecordingAccess access = RecordingAccess.granting();
            DrydockSessionService service = harness.service(access, provider);
            ManagedAgentSession stored = exitedClaudeRow(harness, base);

            CompletableFuture<ManagedAgentSession> resumed = service.resumeSession(stored.id());
            assertTrue(arrived.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            ExecutionException refused = assertThrows(ExecutionException.class,
                    () -> service.deleteSession(stored.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            gate.countDown();

            assertTrue(refused.getCause() instanceof IllegalStateException, String.valueOf(refused.getCause()));
            assertEquals(SessionStatus.RUNNING, resumed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).status());
            assertTrue(harness.consoles.forgotten.isEmpty());
            assertTrue(access.released.isEmpty());
            assertEquals(SessionStatus.RUNNING, persisted(harness, stored.id()).status());
            harness.store.flush();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void aStartWhoseRowIsDeletedBeforeItsClaimFailsAndLeavesTheRowDeleted(@TempDir Path base) throws Exception {
        SteppedExecutorService executor = new SteppedExecutorService();
        Harness harness = concurrentHarness(base, executor);
        RecordingProvider provider = new RecordingProvider(
                AgentKind.CLAUDE, SessionIdStrategy.PRESET, null, true, false, null, harness.events);
        provider.delivery = McpDelivery.CONFIG_FILE;
        RecordingAccess access = RecordingAccess.granting();
        DrydockSessionService service = harness.service(access, provider);
        ManagedAgentSession idle = persistSession(harness, AgentKind.CLAUDE, "Row",
                new AgentBinding(AgentKind.CLAUDE, Optional.empty(), Optional.empty()),
                base, SessionStatus.EXITED, Instant.parse("2026-08-12T10:00:00Z"));

        CompletableFuture<ManagedAgentSession> started = service.startSession(idle.id());
        assertTrue(executor.runNext(), "the stage that reads the row");
        // What a delete that finished in this window leaves: the row gone, no claim held.
        harness.store.update(state -> state.withSessions(List.of()));
        executor.runAll();

        ExecutionException failed = assertThrows(ExecutionException.class,
                () -> started.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue(failed.getCause() instanceof IllegalStateException, String.valueOf(failed.getCause()));
        assertNull(persistedOrNull(harness, idle.id()), "the forgotten row stays forgotten");
        assertTrue(provider.createContexts.isEmpty());
        assertTrue(access.requested.isEmpty(), "nothing was minted");
        assertTrue(harness.consoles.opens.isEmpty());
        harness.store.flush();
    }

    private static ManagedAgentSession transferableClaudeRow(Harness harness, Path base) {
        return persistSession(harness, AgentKind.CLAUDE, "Same task",
                AgentBinding.unlaunched(AgentKind.CLAUDE).withSessionId(Optional.of("source-42")),
                base, SessionStatus.INACTIVE, Instant.now());
    }

    private static RecordingProvider codexTarget(Harness harness) {
        RecordingProvider target = new RecordingProvider(
                AgentKind.CODEX, SessionIdStrategy.DISCOVERED, null, true, false, null, harness.events);
        target.delivery = McpDelivery.COMMAND_LINE;
        return target;
    }

    @Test
    void aTransferPassesTheGrantedMcpAccessToTheTarget(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingProvider target = codexTarget(harness);
        RecordingAccess access = RecordingAccess.granting();
        DrydockSessionService service = harness.service(access, transferSource(harness.events), target);
        ManagedAgentSession source = transferableClaudeRow(harness, base);

        service.transferStoppedSession(source.id(), AgentKind.CODEX).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(List.of(McpDelivery.COMMAND_LINE), access.requested);
        assertTrue(target.createContexts.getFirst().mcp().isPresent());
        assertTrue(access.released.isEmpty());
        harness.store.flush();
    }

    @Test
    void aTransferWhoseTargetCommandDoesNotUseTheCredentialReleasesIt(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingProvider target = codexTarget(harness);
        RecordingAccess access = RecordingAccess.grantingWithFile(base.resolve("mcp").resolve("s.token"));
        DrydockSessionService service = harness.service(access, transferSource(harness.events), target);
        ManagedAgentSession source = transferableClaudeRow(harness, base);

        service.transferStoppedSession(source.id(), AgentKind.CODEX).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(List.of(source.id()), access.released);
        harness.store.flush();
    }

    @Test
    void aTransferWhileTheSessionHasALiveExecutionIsRefusedBeforeAnythingRuns(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingProvider target = codexTarget(harness);
        RecordingAccess access = RecordingAccess.granting();
        DrydockSessionService service = harness.service(access, transferSource(harness.events), target);
        ManagedAgentSession source = transferableClaudeRow(harness, base);
        harness.liveExecution = id -> id.equals(source.id());

        ExecutionException refused = assertThrows(ExecutionException.class,
                () -> service.transferStoppedSession(source.id(), AgentKind.CODEX).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        assertTrue(refused.getCause() instanceof IllegalStateException, String.valueOf(refused.getCause()));
        assertTrue(access.requested.isEmpty());
        assertTrue(target.createContexts.isEmpty());
        assertEquals(AgentKind.CLAUDE, persisted(harness, source.id()).agentKind());
        harness.store.flush();
    }

    @Test
    void aCompletedTransferEndsItsClaim(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingProvider target = codexTarget(harness);
        DrydockSessionService service = harness.service(RecordingAccess.granting(), transferSource(harness.events),
                target);
        ManagedAgentSession source = transferableClaudeRow(harness, base);
        service.transferStoppedSession(source.id(), AgentKind.CODEX).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        harness.consoles.stopped.add(source.id());

        assertEquals(SessionStatus.RUNNING,
                service.startSession(source.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).status());
        assertEquals(2, target.createContexts.size());
        harness.store.flush();
    }

    // --- repoWithLastUsedLaunch: the launch record, in one pass -----------------------------------

    private static Repository launchRecordRepo(String root, RepositorySettings settings) {
        return new Repository(RepositoryId.newId(), Path.of(root), Path.of(root).getFileName().toString(),
                Instant.EPOCH, Instant.EPOCH, settings);
    }

    @Test
    void launchRecordStoresAgentAndModelOnTheMatchingRepositoryOnly() {
        Repository a = launchRecordRepo("/r/a", RepositorySettings.DEFAULT);
        Repository b = launchRecordRepo("/r/b", RepositorySettings.DEFAULT);
        ApplicationState state = ApplicationState.empty().withRepositories(List.of(a, b));

        ApplicationState updated = DrydockSessionService.repoWithLastUsedLaunch(state, a.id(), AgentKind.PI,
                Optional.of("m"), Optional.empty());

        RepositorySettings aSettings = updated.repositories().get(0).settings();
        assertEquals(Optional.of(AgentKind.PI), aSettings.lastUsedAgent());
        assertEquals(Optional.of("m"), aSettings.lastUsedModel(AgentKind.PI));
        assertEquals(RepositorySettings.DEFAULT, updated.repositories().get(1).settings());
    }

    @Test
    void launchRecordStoresTheTiersWithTheModelOnTheMatchingRepositoryOnly() {
        Repository a = launchRecordRepo("/r/a", RepositorySettings.DEFAULT);
        ModelTiers tiers = new ModelTiers(Optional.of("opus"), Optional.of("sonnet"), Optional.of("haiku"));

        ApplicationState updated = DrydockSessionService.repoWithLastUsedLaunch(
                ApplicationState.empty().withRepositories(List.of(a)), a.id(), AgentKind.CLAUDE,
                Optional.of("opus"), Optional.of(tiers));

        RepositorySettings settings = updated.repositories().get(0).settings();
        assertEquals(Optional.of(tiers), settings.lastUsedTiers(AgentKind.CLAUDE));
        // Pickers shown and every tier cleared: the entry goes, like an empty model's does.
        ApplicationState cleared = DrydockSessionService.repoWithLastUsedLaunch(updated, a.id(), AgentKind.CLAUDE,
                Optional.of("opus"), Optional.of(ModelTiers.EMPTY));
        assertEquals(Optional.empty(), cleared.repositories().get(0).settings().lastUsedTiers(AgentKind.CLAUDE));
    }

    @Test
    void launchRecordWithoutTierInputKeepsTheStoredTiers() {
        ModelTiers stored = new ModelTiers(Optional.of("opus"), Optional.of("sonnet"), Optional.of("haiku"));
        Repository a = launchRecordRepo("/r/a",
                RepositorySettings.DEFAULT.withLastUsedTiers(AgentKind.CLAUDE, stored));

        ApplicationState updated = DrydockSessionService.repoWithLastUsedLaunch(
                ApplicationState.empty().withRepositories(List.of(a)), a.id(), AgentKind.CLAUDE,
                Optional.of("sonnet"), Optional.empty());

        RepositorySettings settings = updated.repositories().get(0).settings();
        assertEquals(Optional.of(stored), settings.lastUsedTiers(AgentKind.CLAUDE));
        assertEquals(Optional.of("sonnet"), settings.lastUsedModel(AgentKind.CLAUDE));
        assertEquals(Optional.of(AgentKind.CLAUDE), settings.lastUsedAgent());
    }

    @Test
    void launchRecordWithAnEmptyModelClearsItAndStillRecordsTheAgent() {
        Repository a = launchRecordRepo("/r/a",
                RepositorySettings.DEFAULT.withLastUsedModel(AgentKind.PI, Optional.of("old")));
        ApplicationState state = ApplicationState.empty().withRepositories(List.of(a));

        ApplicationState updated = DrydockSessionService.repoWithLastUsedLaunch(state, a.id(), AgentKind.PI,
                Optional.empty(), Optional.empty());

        RepositorySettings settings = updated.repositories().get(0).settings();
        assertEquals(Optional.empty(), settings.lastUsedModel(AgentKind.PI));
        assertEquals(Optional.of(AgentKind.PI), settings.lastUsedAgent());
    }

    @Test
    void launchRecordForAnUnknownRepositoryChangesNothing() {
        Repository a = launchRecordRepo("/r/a", RepositorySettings.DEFAULT);
        Repository b = launchRecordRepo("/r/b", RepositorySettings.DEFAULT);
        ApplicationState state = ApplicationState.empty().withRepositories(List.of(a, b));

        ApplicationState updated = DrydockSessionService.repoWithLastUsedLaunch(state, RepositoryId.newId(),
                AgentKind.PI, Optional.of("m"), Optional.empty());

        assertEquals(state.repositories(), updated.repositories());
    }

    @Test
    void launchRecordKeepsTheRepositoryOrder() {
        Repository a = launchRecordRepo("/r/a", RepositorySettings.DEFAULT);
        Repository b = launchRecordRepo("/r/b", RepositorySettings.DEFAULT);
        ApplicationState state = ApplicationState.empty().withRepositories(List.of(a, b));

        ApplicationState updated = DrydockSessionService.repoWithLastUsedLaunch(state, b.id(), AgentKind.CLAUDE,
                Optional.of("opus"), Optional.empty());

        assertEquals(List.of(a.id(), b.id()), updated.repositories().stream().map(Repository::id).toList());
    }

    // ---- characterization: today's local plans through the service (INV-1 oracle) ------

    /** The claude env cleanup prefix, as today's local launches render it. */
    private static final String CLAUDE_ENV = "env -u CLAUDECODE -u CLAUDE_CODE_ENTRYPOINT"
            + " -u CLAUDE_CODE_EXECPATH -u CLAUDE_CODE_SESSION_ID -u CLAUDE_CODE_CHILD_SESSION"
            + " -u CLAUDE_EFFORT LANG=en_US.UTF-8 CLAUDE_CODE_NO_FLICKER=1 ";

    /** The codex env scrub prefix, as today's local launches render it. */
    private static final String CODEX_ENV = "env -u CODEX_SANDBOX -u CODEX_SANDBOX_NETWORK_DISABLED ";

    /** The pi env scrub prefix, as today's local launches render it. */
    private static final String PI_ENV = "env -u PI_CODING_AGENT ";

    /** The plan the console host was handed for launch number {@code index}. */
    private static LaunchPlan openedPlan(Harness harness, int index) {
        return harness.consoles.opens.get(index).plan();
    }

    /**
     * INV-1 oracle, service half: a local Claude launch through the real
     * service and the real provider opens a console with exactly today's
     * plan, on create and on resume. The provider's executable is forced to
     * "not found", so the conservative capabilities render the bare
     * binary and no flags, and the PATH the ddtool locator probes is pinned
     * to a directory holding a fake ddtool, so no host ddtool can splice a
     * {@code PATH=} override into the pinned string (the injection's guard
     * compares against that same PATH, so a ddtool on it never injects).
     */
    @Test
    void claudeLocalCreateAndResumeKeepTodaysPlans(@TempDir Path base) throws Exception {
        Path pinnedPath = Files.createDirectories(base.resolve("pinned-path"));
        Path fakeDdtool = Files.createFile(pinnedPath.resolve("ddtool"));
        assertTrue(fakeDdtool.toFile().setExecutable(true));
        String originalPath = LoginShellEnvironment.currentRealPath();
        assumeTrue(originalPath != null, "a test JVM without PATH cannot pin the ddtool probe");
        LoginShellEnvironment.useShellPath(pinnedPath + ":" + originalPath);
        try {
            Harness harness = harness(base);
            ClaudeAgentProvider provider = new ClaudeAgentProvider(
                    new ClaudeExecutableLocator(Path.of("/nonexistent/claude")));
            DrydockSessionService service = harness.service(provider);

            service.launchSession(service.prepareSession(base, AgentKind.CLAUDE, "My session")
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals(CLAUDE_ENV + "claude", openedPlan(harness, 0).command());

            ManagedAgentSession second = service.prepareSession(base, AgentKind.CLAUDE, "With a task")
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            service.launchSession(second, Optional.of("Do the thing"), Optional.of("sonnet"), Optional.empty())
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals(CLAUDE_ENV
                    + "claude --model 'sonnet' -- 'Do the thing'", openedPlan(harness, 1).command());

            ManagedAgentSession stored = persistSession(harness, AgentKind.CLAUDE, "Old work",
                    new AgentBinding(AgentKind.CLAUDE, Optional.of("conv-42"), Optional.empty()),
                    base, SessionStatus.INACTIVE, Instant.parse("2026-08-12T10:00:00Z"));
            service.resumeSession(stored.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals(CLAUDE_ENV + "claude --resume 'conv-42'", openedPlan(harness, 2).command());
            harness.store.flush();
        } finally {
            LoginShellEnvironment.useShellPath(originalPath);
        }
    }

    /**
     * INV-1 oracle, service half, Codex: the local create opens today's
     * sandboxed picker-less command and a resume by id re-renders it with
     * the {@code resume} subcommand. DISCOVERED ids mean neither plan
     * carries one.
     */
    @Test
    void codexLocalCreateAndResumeKeepTodaysPlans(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        CodexAgentProvider provider = new CodexAgentProvider(
                new CodexExecutableLocator(Path.of("/nonexistent/codex")));
        DrydockSessionService service = harness.service(provider);

        service.launchSession(service.prepareSession(base, AgentKind.CODEX, "Codex work")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(CODEX_ENV + "codex --sandbox danger-full-access", openedPlan(harness, 0).command());

        ManagedAgentSession stored = persistSession(harness, AgentKind.CODEX, "Old codex",
                new AgentBinding(AgentKind.CODEX, Optional.of("conv-7"), Optional.empty()),
                base, SessionStatus.INACTIVE, Instant.parse("2026-08-12T10:00:00Z"));
        service.resumeSession(stored.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(CODEX_ENV + "codex --sandbox danger-full-access resume 'conv-7'",
                openedPlan(harness, 1).command());
        harness.store.flush();
    }

    /**
     * INV-1 oracle, service half, Pi: every launch scrubs the inherited
     * agent marker, carries the activity dir and the activity extension,
     * and mints a fresh {@code PI_SESSION_ID} per invocation — normalized
     * here, since it is per-launch by design. The resume pins the
     * {@code --session} form.
     */
    @Test
    void piLocalCreateAndResumeKeepTodaysPlans(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        PiAgentProvider provider = new PiAgentProvider(new PiExecutableLocator(Path.of("/nonexistent/pi")));
        DrydockSessionService service = harness.service(provider);

        service.launchSession(service.prepareSession(base, AgentKind.PI, "Pi work")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(PI_ENV + "DRYDOCK_ACTIVITY_DIR='" + base.resolve("activity")
                        + "' PI_SESSION_ID='<id>' pi -e '" + base.resolve("pi").resolve("drydock-activity.ts") + "'",
                normalizedPiSessionId(openedPlan(harness, 0).command()));

        ManagedAgentSession stored = persistSession(harness, AgentKind.PI, "Old pi",
                new AgentBinding(AgentKind.PI, Optional.of("conv-7"), Optional.empty()),
                base, SessionStatus.INACTIVE, Instant.parse("2026-08-12T10:00:00Z"));
        service.resumeSession(stored.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(PI_ENV + "DRYDOCK_ACTIVITY_DIR='" + base.resolve("activity")
                        + "' PI_SESSION_ID='<id>' pi -e '"
                        + base.resolve("pi").resolve("drydock-activity.ts") + "' --session 'conv-7'",
                normalizedPiSessionId(openedPlan(harness, 1).command()));
        harness.store.flush();
    }

    /** {@code PI_SESSION_ID} is minted fresh per launch; everything else in the plan is exact. */
    private static String normalizedPiSessionId(String command) {
        return command.replaceAll("PI_SESSION_ID='[^']*'", "PI_SESSION_ID='<id>'");
    }

    /**
     * INV-1 oracle, service half, Terminal: create and resume both open
     * the login-shell literal — a terminal has no conversation to resume,
     * so reopening is the same command.
     */
    @Test
    void terminalLocalCreateAndResumeKeepTodaysPlans(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        DrydockSessionService service = harness.service(new TerminalAgentProvider());

        service.launchSession(service.prepareSession(base, AgentKind.TERMINAL, "Shell")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(TerminalAgentProvider.SHELL_COMMAND, openedPlan(harness, 0).command());

        ManagedAgentSession stored = persistSession(harness, AgentKind.TERMINAL, "Old shell",
                AgentBinding.unlaunched(AgentKind.TERMINAL), base, SessionStatus.INACTIVE,
                Instant.parse("2026-08-12T10:00:00Z"));
        service.resumeSession(stored.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(TerminalAgentProvider.SHELL_COMMAND, openedPlan(harness, 1).command());
        harness.store.flush();
    }

    // ---- workspace-box remote rows: create and resume (T15) -------------------------

    /** The box a test row is bound to: one binding, one box name, one clone path. */
    private static final WorkspaceBoxBinding BOX =
            new WorkspaceBoxBinding("drydock-repo", "/home/dev/repo");

    /** The box agent environment as the persistent plan renders it. */
    private static final String BOX_PROXY_ENTRY = "ANTHROPIC_BASE_URL='http://127.0.0.1:4000'";

    /**
     * The remote shell string inside an ssh plan for the test box, with its
     * POSIX single-quote escapes resolved through both quoting layers below
     * the outer one: the layer tmux's shell-command receives, where the
     * agent exec's own quotes are plain.
     */
    private static String remoteShell(String sshCommand) {
        String marker = "-- 'workspace-drydock-repo' ";
        int at = sshCommand.indexOf(marker);
        assertTrue(at >= 0, "not the box plan: " + sshCommand);
        return sshCommand.substring(at + marker.length())
                .replace("'\\''", "'")
                .replace("'\\''", "'");
    }

    /**
     * A locator that always resolves the `workspaces` binary, whatever the
     * host has installed, so the {@code workspaces list} calls are decided
     * by the recording runner's replies alone.
     */
    private static HostBinaryLocator boxBinary() {
        return new HostBinaryLocator("workspaces", List.of()) {
            @Override protected Optional<Path> discover() {
                return Optional.of(Path.of("/opt/homebrew/bin/workspaces"));
            }
        };
    }

    /**
     * Records every box command the service issues and answers the four
     * shapes a stop, forget, resume or client-end settle can run -- the batch
     * ssh probe, the kill-session, the has-session probe, and `workspaces
     * list` -- from canned replies. Any other box command fails the test
     * loudly.
     */
    static final class RecordingBoxRunner implements CommandRunner {

        final List<List<String>> argv = new CopyOnWriteArrayList<>();
        volatile ProcessResult probe = new ProcessResult(0, "", "");
        volatile ProcessResult kill = new ProcessResult(0, "", "");
        /** The has-session probe's reply — what a client-end settle would conclude from. */
        volatile ProcessResult hasSession = new ProcessResult(0, "", "");
        /** When set, runs while the kill-session round trip is in flight (an ssh client can end during it). */
        volatile Runnable duringKill;
        /** When set, a kill-session throws it (the runner's way to simulate a hung kill). */
        volatile RuntimeException killFailure;
        volatile ProcessResult list = new ProcessResult(0, "", "");
        volatile IOException listFailure;

        @Override
        public ProcessResult run(List<String> argv, ProcessRunner.Options options, int maxOutputBytes)
                throws IOException, InterruptedException {
            this.argv.add(List.copyOf(argv));
            if (isKillSession(argv)) {
                if (duringKill != null) {
                    duringKill.run();
                }
                if (killFailure != null) {
                    throw killFailure;
                }
                return kill;
            }
            if (argv.contains("has-session")) {
                return hasSession;
            }
            if ("ssh".equals(argv.get(0))) {
                return probe;
            }
            if (argv.contains("list")) {
                if (listFailure != null) {
                    throw listFailure;
                }
                return list;
            }
            throw new IllegalStateException("unexpected box command: " + argv);
        }

        /** The kill-session argv shape: the batch ssh form whose remote command names the kill. */
        static boolean isKillSession(List<String> argv) {
            return "ssh".equals(argv.get(0))
                    && argv.getLast() != null && argv.getLast().contains("kill-session");
        }
    }

    /**
     * A remote row as a restart would have loaded it: persisted with the
     * binding, so the constructor derives the placeholder working directory
     * (a path that does not exist on this Mac -- the point of the resume
     * tests), and the given binding, status and task framing.
     */
    private static ManagedAgentSession persistRemoteSession(Harness harness, RepositoryId repositoryId,
            AgentBinding binding, SessionStatus status, boolean startedWithTask) {
        ManagedAgentSession session = new ManagedAgentSession.Builder(
                ManagedSessionId.newId(), repositoryId, "Box work", binding,
                SessionWorkspace.inRepository(harness.base.toAbsolutePath().normalize()),
                Instant.parse("2026-08-12T10:00:00Z"))
                .status(status)
                .startedWithTask(startedWithTask)
                .workspaceBox(Optional.of(BOX))
                .build();
        harness.store.update(state -> {
            List<ManagedAgentSession> sessions = new ArrayList<>(state.sessions());
            sessions.add(session);
            return state.withSessions(sessions);
        });
        return session;
    }

    private static ManagedAgentSession persistRemoteSession(Harness harness, AgentBinding binding,
            SessionStatus status, boolean startedWithTask) {
        return persistRemoteSession(harness, harness.repository.id(), binding, status, startedWithTask);
    }

    /**
     * The exact batch probe argv a remote resume issues for the box alias:
     * the four batch options, `--` before the host, and the remote no-op.
     */
    private static final List<String> PROBE_ARGV = List.of(
            "ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=5",
            "-o", "ServerAliveInterval=3", "-o", "ServerAliveCountMax=2",
            "--", "workspace-drydock-repo", "'true'");

    /**
     * AC-3/AC-6, service half, create: a remote Claude launch through the
     * real provider opens the console with the persistent plan -- the ssh
     * attach-or-create form naming the tmux session after the row's managed
     * id, the generated UUID as `--session-id` (persisted as the row's
     * `agentSessionId`), the display name as `-n`, plan mode for a read-only
     * session, and the initial prompt after `--` -- with the console in a
     * directory that exists on this Mac (the repository root, never the
     * row's box placeholder). No MCP access is minted, no stdio MCP servers
     * are asked for, and no box command runs: the create probes nothing
     * (the box lifecycle is the launcher's, not the service's).
     */
    @Test
    void remoteCreateRendersTheAttachOrCreatePlanAndPersistsTheGeneratedId(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingAccess access = RecordingAccess.granting();
        RecordingBoxRunner runner = new RecordingBoxRunner();
        ClaudeAgentProvider provider = new ClaudeAgentProvider(
                new ClaudeExecutableLocator(Path.of("/nonexistent/claude")));
        DrydockSessionService service = harness.remoteService(() -> true, runner,
                new RemoteSessionLiveness(), access, null, provider);

        ManagedAgentSession prepared = service.prepareSession(base, base, AgentKind.CLAUDE,
                "My session", SessionMode.READ_ONLY, Optional.of(BOX))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        // The row is remote by construction: the binding derives the
        // placeholder workspace, not the local checkout.
        assertEquals(Optional.of(BOX), prepared.workspaceBox());
        assertEquals(BOX.sshRemote().placeholderRoot(), prepared.workingDirectory());

        ManagedAgentSession running = service.launchSession(prepared, Optional.of("Fix the parser"))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(SessionStatus.RUNNING, running.status());

        assertEquals(1, harness.consoles.opens.size());
        FakeConsoleHost.Open open = harness.consoles.opens.get(0);
        assertEquals(running.id(), open.sessionId());
        // The console cwd is the repository's local root, never the placeholder.
        assertEquals(base.toAbsolutePath().normalize(), open.workingDirectory());
        String command = open.plan().command();
        assertTrue(command.startsWith("ssh -t "), command);
        assertTrue(command.contains("-- 'workspace-drydock-repo'"), command);
        String remote = remoteShell(command);
        assertTrue(remote.contains("new-session -A -s drydock-" + running.id()),
                "AC-3: the tmux session is named after the managed id: " + remote);
        assertTrue(remote.contains("-n 'My session'"), remote);
        assertTrue(remote.contains(" --permission-mode plan"), remote);
        assertTrue(remote.contains("-- 'Fix the parser'"), remote);
        assertTrue(remote.contains(BOX_PROXY_ENTRY), remote);
        // The generated UUID appears literally, so the row persists it.
        String generated = persisted(harness, running.id()).agentSessionId().orElseThrow();
        assertTrue(remote.contains("--session-id '" + generated + "'"), remote);

        // INV-15/16: nothing was minted, nothing was spawned on the box, no
        // stdio MCP servers were resolved for the remote context.
        assertTrue(access.requested.isEmpty(), "a remote launch mints no MCP access");
        assertTrue(harness.stdioMcpAsked.isEmpty(), "a remote launch resolves no stdio MCP servers");
        assertTrue(runner.argv.isEmpty(), "a remote create issues no box command");
        harness.store.flush();
    }

    /**
     * AC-3/AC-6/AC-14, service half, resume: a remote row with a recorded
     * conversation id attaches with the same `new-session -A -s drydock-<id>`
     * plan, resumes the exact conversation, adds plan mode for a read-only
     * row, and delivers the fixed re-orientation prompt the row's task
     * framing earned. Exactly one batch ssh probe runs and `workspaces list`
     * never does. The state after the resume is the state before it, apart
     * from the two settle fields: RUNNING (legal -- this IDE owns the live
     * ssh execution) and `lastOpenedAt`. No MCP access is minted, no stdio
     * servers are asked for, no `agentSessionId` is rewritten.
     */
    @Test
    void remoteResumeAttachesByIdAndSettlesOnlyTheRowStatusAndLastOpenedAt(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingAccess access = RecordingAccess.granting();
        RecordingBoxRunner runner = new RecordingBoxRunner();
        ClaudeAgentProvider provider = new ClaudeAgentProvider(
                new ClaudeExecutableLocator(Path.of("/nonexistent/claude")));
        DrydockSessionService service = harness.remoteService(() -> true, runner,
                new RemoteSessionLiveness(), access, null, provider);

        ManagedAgentSession row = persistRemoteSession(harness,
                new AgentBinding(AgentKind.CLAUDE, Optional.of("conv-7"), Optional.empty(),
                        SessionMode.READ_ONLY),
                SessionStatus.INACTIVE, true);
        ApplicationState before = harness.store.state();

        ManagedAgentSession running = service.resumeSession(row.id())
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(SessionStatus.RUNNING, running.status());

        // One probe, no listing: the box was reachable.
        assertEquals(List.of(PROBE_ARGV), runner.argv);
        assertEquals(1, harness.consoles.opens.size());
        FakeConsoleHost.Open open = harness.consoles.opens.get(0);
        assertEquals(row.id(), open.sessionId());
        assertEquals(base.toAbsolutePath().normalize(), open.workingDirectory());
        String command = open.plan().command();
        String remote = remoteShell(command);
        assertTrue(remote.contains("new-session -A -s drydock-" + row.id()),
                "AC-3: resume runs the identical attach-or-create command: " + remote);
        assertTrue(remote.contains("claude --resume 'conv-7' --permission-mode plan"), remote);
        assertTrue(remote.contains("-- 'You are resuming this task in a fresh session."), remote);
        assertTrue(remote.contains(BOX_PROXY_ENTRY), remote);

        // AC-14: identical state apart from the settle fields. No repository
        // settings changed, no binding was rebound, no id was rewritten.
        ApplicationState after = harness.store.state();
        assertEquals(before.repositories(), after.repositories());
        assertEquals(1, after.sessions().size());
        ManagedAgentSession settled = after.sessions().get(0);
        assertEquals(Optional.of(BOX), settled.workspaceBox());
        assertEquals(row.binding(), settled.binding());
        assertEquals(row.displayName(), settled.displayName());
        assertEquals(row.createdAt(), settled.createdAt());
        assertEquals(row.lastExitCode(), settled.lastExitCode());
        assertEquals(row.agentSessionId(), settled.agentSessionId());
        assertTrue(settled.lastOpenedAt().compareTo(row.lastOpenedAt()) >= 0,
                "the settle bumps lastOpenedAt, never rewinds it");
        assertTrue(access.requested.isEmpty(), "a remote resume mints no MCP access");
        assertTrue(harness.stdioMcpAsked.isEmpty(), "a remote resume resolves no stdio MCP servers");
        harness.store.flush();
    }

    /**
     * AC-11: `resumeOrStartSession` on a remote row ALWAYS resumes. The
     * provider's conversation source answers `transcriptExists` with false
     * ("nothing is recorded locally") and the row's working directory is the
     * box placeholder this Mac never had — both of which would send today's
     * local logic to a fresh create or a MISSING_WORKING_DIRECTORY — yet the
     * remote branch consults neither: it builds a resume context, opens the
     * console, and settles the row RUNNING.
     */
    @Test
    void restoreAlwaysResumesARemoteRowEvenWithNoLocalTranscript(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        List<String> events = harness.events;
        RecordingProvider provider = new RecordingProvider(
                AgentKind.CLAUDE, SessionIdStrategy.PRESET, null, true, false, null, events) {
            @Override public Optional<ConversationSource> conversations() {
                return Optional.of(new ConversationSource() {
                    @Override public List<Conversation> listConversations(Path workingDirectory) {
                        return List.of();
                    }
                    @Override public boolean transcriptExists(Path workingDirectory, String agentSessionId) {
                        events.add("transcript-probe");
                        return false;
                    }
                });
            }
        };
        RecordingBoxRunner runner = new RecordingBoxRunner();
        DrydockSessionService service = harness.remoteService(() -> true, runner,
                new RemoteSessionLiveness(), SessionMcpAccess.none(), null, provider);

        ManagedAgentSession row = persistRemoteSession(harness,
                new AgentBinding(AgentKind.CLAUDE, Optional.of("conv-9"), Optional.empty()),
                SessionStatus.INACTIVE, true);
        ManagedAgentSession running = service.resumeOrStartSession(row.id())
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(SessionStatus.RUNNING, running.status());
        assertEquals(SessionStatus.RUNNING, persisted(harness, row.id()).status());
        assertEquals(1, provider.resumeContexts.size(), "the row resumes, never starts fresh");
        assertTrue(provider.createContexts.isEmpty(), "no fresh create ran for the remote row");
        assertEquals(Optional.of("conv-9"), provider.resumeContexts.get(0).agentSessionId());
        assertEquals(1, harness.consoles.opens.size());
        assertFalse(events.contains("transcript-probe"),
                "the local-only transcript probe must not decide anything for a remote row");
        harness.store.flush();
    }

    /**
     * R1 defect 3: the remote Resume's deletion gap. The row is read in one
     * executor stage and attached in another; a Forget that completes in
     * between removes the row and frees the claim, and the attach must not
     * recreate the just-forgotten box session from its stale snapshot — the
     * same guard the create-on-existing-row path carries.
     */
    @Test
    void aRemoteResumeWhoseRowWasForgottenMidFlightIsRefusedBeforeAttaching(@TempDir Path base) throws Exception {
        SteppedExecutorService executor = new SteppedExecutorService();
        Harness harness = concurrentHarness(base, executor);
        RecordingBoxRunner runner = new RecordingBoxRunner();
        // The recording provider queues nothing on the executor at init, so
        // the first stepped task is the resume's own read stage.
        RecordingProvider provider = new RecordingProvider(
                AgentKind.CLAUDE, SessionIdStrategy.PRESET, null, true, false, null, harness.events);
        DrydockSessionService service = harness.remoteService(() -> true, runner,
                new RemoteSessionLiveness(), SessionMcpAccess.none(), null, provider);

        ManagedAgentSession row = persistRemoteSession(harness,
                new AgentBinding(AgentKind.CLAUDE, Optional.of("conv-7"), Optional.empty()),
                SessionStatus.IDLE, false);
        CompletableFuture<ManagedAgentSession> resumed = service.resumeSession(row.id());
        assertTrue(executor.runNext(), "the stage that reads the row");
        // What a Forget that finished in this window leaves: the row gone,
        // no claim held.
        harness.store.update(state -> state.withSessions(List.of()));
        executor.runAll();

        ExecutionException refused = assertThrows(ExecutionException.class,
                () -> resumed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue(refused.getCause() instanceof IllegalStateException,
                String.valueOf(refused.getCause()));
        assertNull(persistedOrNull(harness, row.id()), "the forgotten row stays forgotten");
        assertTrue(runner.argv.isEmpty(), "the refusal probes nothing — the row is gone");
        assertTrue(harness.consoles.opens.isEmpty(), "the refusal attaches to nothing");
        harness.store.flush();
    }

    /**
     * INV-31: with the flag off, resuming a remote row is refused with the
     * settings reason — before any probe, any console, any state write — and
     * never falls through to a local launch of the same row.
     */
    @Test
    void flagOffRemoteResumeIsRefusedAndNeverFallsBackToLocal(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingBoxRunner runner = new RecordingBoxRunner();
        ClaudeAgentProvider provider = new ClaudeAgentProvider(
                new ClaudeExecutableLocator(Path.of("/nonexistent/claude")));
        DrydockSessionService service = harness.remoteService(() -> false, runner,
                new RemoteSessionLiveness(), SessionMcpAccess.none(), null, provider);

        ManagedAgentSession row = persistRemoteSession(harness,
                new AgentBinding(AgentKind.CLAUDE, Optional.of("conv-7"), Optional.empty()),
                SessionStatus.INACTIVE, false);
        ExecutionException refused = assertThrows(ExecutionException.class,
                () -> service.resumeSession(row.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals("Workspace sessions are turned off in Settings › Tools › Drydock",
                refused.getCause().getMessage());
        // And the restore gesture refuses the same way, rather than
        // quietly launching a local session in the row's place.
        ExecutionException restoreRefused = assertThrows(ExecutionException.class,
                () -> service.resumeOrStartSession(row.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals("Workspace sessions are turned off in Settings › Tools › Drydock",
                restoreRefused.getCause().getMessage());

        assertTrue(runner.argv.isEmpty(), "the flag-off refusal probes nothing");
        assertTrue(harness.consoles.opens.isEmpty(), "the flag-off refusal never launches");
        ManagedAgentSession stored = persisted(harness, row.id());
        assertEquals(SessionStatus.INACTIVE, stored.status());
        assertEquals(Optional.of(BOX), stored.workspaceBox());
        harness.store.flush();
    }

    /**
     * AC-2, non-Claude half: a remote launch of Codex, Pi or Terminal is
     * refused with a message naming the agent, before any row persist, MCP
     * mint, box command or console — for every kind, in isolation.
     */
    @Test
    void nonClaudeRemoteLaunchesAreRefusedBeforeAnythingHappens(@TempDir Path base) throws Exception {
        for (AgentKind kind : List.of(AgentKind.CODEX, AgentKind.PI, AgentKind.TERMINAL)) {
            Harness harness = harness(Files.createDirectories(base.resolve(kind.persistedName())));
            RecordingProvider provider = new RecordingProvider(
                    kind, SessionIdStrategy.DISCOVERED, null, true, false, null, harness.events);
            RecordingAccess access = RecordingAccess.granting();
            RecordingBoxRunner runner = new RecordingBoxRunner();
            DrydockSessionService service = harness.remoteService(() -> true, runner,
                    new RemoteSessionLiveness(), access, null, provider);

            ManagedAgentSession prepared = service.prepareSession(harness.base, harness.base, kind,
                    "No box for " + kind, SessionMode.WRITES, Optional.of(BOX))
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            ExecutionException refused = assertThrows(ExecutionException.class,
                    () -> service.launchSession(prepared).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            String message = refused.getCause().getMessage();
            assertTrue(message.contains(kind.toString()) && message.contains(kind.persistedName()),
                    "the refusal must name the agent: " + message);

            assertTrue(runner.argv.isEmpty(), kind + ": no box command may run");
            assertTrue(harness.consoles.opens.isEmpty(), kind + ": no console may open");
            assertTrue(harness.store.state().sessions().isEmpty(), kind + ": no row may be persisted");
            assertTrue(access.requested.isEmpty(), kind + ": no MCP access may be minted");
            assertTrue(provider.createContexts.isEmpty(), kind + ": the provider was never asked");
            harness.store.flush();
        }
    }

    /**
     * AC-2, resource half: a remote launch whose task resources resolve — a
     * retained receipt naming an existing prerequisite directory on THIS
     * Mac, resolved with an empty MCP access so no mint ever happened — is
     * refused naming Claude, before any row, box command or console.
     */
    @Test
    void resourceBoundRemoteLaunchIsRefusedNamingTheAgent(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        Path tasksRoot = base.resolve("task-store");
        TaskLaunchResources resources = new TaskLaunchResources(tasksRoot,
                new TaskGuidance(tasksRoot), () -> false);
        RecordingAccess access = RecordingAccess.granting();
        RecordingBoxRunner runner = new RecordingBoxRunner();
        ClaudeAgentProvider provider = new ClaudeAgentProvider(
                new ClaudeExecutableLocator(Path.of("/nonexistent/claude")));
        DrydockSessionService service = harness.remoteService(() -> true, runner,
                new RemoteSessionLiveness(), access, resources, provider);

        ManagedAgentSession prepared = service.prepareSession(base, base, AgentKind.CLAUDE,
                "Dependent work", SessionMode.WRITES, Optional.of(BOX))
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Path prerequisite = Files.createDirectories(base.resolve("prerequisite"));
        Path ownerCatalog = tasksRoot.resolve("catalog.json");
        try (TaskRuns runs = new TaskRuns(tasksRoot)) {
            runs.reserve(new TaskId("t15-receipt"), prepared.id().toString(), tasksRoot,
                    ownerCatalog, List.of(prerequisite));
            runs.flush();
        }
        Files.writeString(ownerCatalog, "{}");

        ExecutionException refused = assertThrows(ExecutionException.class,
                () -> service.launchSession(prepared).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        String message = refused.getCause().getMessage();
        assertTrue(message.contains("CLAUDE") && message.contains("claude"),
                "the refusal must name the agent: " + message);

        assertTrue(runner.argv.isEmpty(), "no box command may run");
        assertTrue(harness.consoles.opens.isEmpty(), "no console may open");
        assertTrue(harness.store.state().sessions().isEmpty(), "no row may be persisted");
        assertTrue(access.requested.isEmpty(), "the resource check mints no MCP access");
        harness.store.flush();
    }

    /**
     * The `workspaces list` table shape observed from the real CLI
     * (find-box-provisioning-facts), with the test box in the given status.
     */
    private static String boxTable(String status) {
        return """
                NAME                  REGION      STATUS   DEVCONTAINER REPO   AGE    TTL*
                drydock-repo          us-east-1   %-8s ddoghq/dd-source    20d    159d
                * Maximum time-to-live for the workspace. Garbage collector may delete sooner if unused.
                """.formatted(status);
    }

    /** A table that does not list the test box at all: the box is gone. */
    private static final String TABLE_WITHOUT_THE_BOX = """
            NAME                  REGION      STATUS   DEVCONTAINER REPO   AGE    TTL*
            jb-tester             us-east-1   2        ddoghq/dd-source    28d    151d
            * Maximum time-to-live for the workspace. Garbage collector may delete sooner if unused.
            """;

    /**
     * AC-18's resume-refusal half, stopped box: a probe that cannot reach the
     * box, classified by `workspaces list` as status 8, refuses the resume
     * with the box-stopped reason, records BOX_STOPPED liveness, and changes
     * nothing else — the row keeps its binding (no rebind), its idle status
     * (no FAILED), and no console opens. Only the probe and the listing run:
     * no restart, no delete, no kill.
     */
    @Test
    void aStoppedBoxRefusesTheResumeWithItsReasonAndRecordsBoxStopped(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingBoxRunner runner = new RecordingBoxRunner();
        runner.probe = new ProcessResult(255, "", "ssh: connect to host: Connection refused");
        runner.list = new ProcessResult(0, boxTable("8"), "");
        RemoteSessionLiveness liveness = new RemoteSessionLiveness();
        ClaudeAgentProvider provider = new ClaudeAgentProvider(
                new ClaudeExecutableLocator(Path.of("/nonexistent/claude")));
        DrydockSessionService service = harness.remoteService(() -> true, runner,
                liveness, SessionMcpAccess.none(), null, provider);

        ManagedAgentSession row = persistRemoteSession(harness,
                new AgentBinding(AgentKind.CLAUDE, Optional.of("conv-7"), Optional.empty()),
                SessionStatus.INACTIVE, true);
        ExecutionException refused = assertThrows(ExecutionException.class,
                () -> service.resumeSession(row.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals("Box drydock-repo is stopped; its conversations are unreachable",
                refused.getCause().getMessage());
        assertEquals(Optional.of(RemoteSessionLiveness.State.BOX_STOPPED), liveness.state(row.id()));

        assertEquals(List.of(PROBE_ARGV, List.of("/opt/homebrew/bin/workspaces", "list", "--no-color")),
                runner.argv);
        assertTrue(harness.consoles.opens.isEmpty());
        ManagedAgentSession stored = persisted(harness, row.id());
        assertEquals(Optional.of(BOX), stored.workspaceBox(), "the binding never changes");
        assertEquals(SessionStatus.INACTIVE, stored.status(), "no FAILED is persisted for a box refusal");
        assertEquals(row.agentSessionId(), stored.agentSessionId());
        harness.store.flush();
    }

    /**
     * AC-18's resume-refusal half, gone box: an unlisted name refuses with the
     * box-gone reason and records BOX_GONE. Same shape as the stopped case:
     * no rebind, no FAILED, no console.
     */
    @Test
    void aGoneBoxRefusesTheResumeWithItsReasonAndRecordsBoxGone(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingBoxRunner runner = new RecordingBoxRunner();
        runner.probe = new ProcessResult(255, "", "ssh: connect to host: Connection refused");
        runner.list = new ProcessResult(0, TABLE_WITHOUT_THE_BOX, "");
        RemoteSessionLiveness liveness = new RemoteSessionLiveness();
        ClaudeAgentProvider provider = new ClaudeAgentProvider(
                new ClaudeExecutableLocator(Path.of("/nonexistent/claude")));
        DrydockSessionService service = harness.remoteService(() -> true, runner,
                liveness, SessionMcpAccess.none(), null, provider);

        ManagedAgentSession row = persistRemoteSession(harness,
                new AgentBinding(AgentKind.CLAUDE, Optional.of("conv-7"), Optional.empty()),
                SessionStatus.INACTIVE, false);
        ExecutionException refused = assertThrows(ExecutionException.class,
                () -> service.resumeOrStartSession(row.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals("Box drydock-repo no longer exists", refused.getCause().getMessage());
        assertEquals(Optional.of(RemoteSessionLiveness.State.BOX_GONE), liveness.state(row.id()));
        assertTrue(harness.consoles.opens.isEmpty());
        ManagedAgentSession stored = persisted(harness, row.id());
        assertEquals(Optional.of(BOX), stored.workspaceBox(), "the binding never changes");
        assertEquals(SessionStatus.INACTIVE, stored.status());
        harness.store.flush();
    }

    /**
     * The transient class of refusals: a probe failure the listing cannot
     * pin on a stopped or gone box — the box is listed but not stopped, or
     * the listing itself failed, timed out or was unparseable — refuses with
     * the try-again reason and UNKNOWN liveness (INV-13: ambiguity never
     * becomes a box verdict).
     */
    @Test
    void anUnclassifiableBoxRefusesTheResumeAsTransientUnknown(@TempDir Path base) throws Exception {
        for (RecordingBoxRunner runner : transientRunners()) {
            Harness harness = harness(Files.createDirectories(
                    base.resolve("transient-" + runner.list.stdout().hashCode() + runner.list.exitCode())));
            RemoteSessionLiveness liveness = new RemoteSessionLiveness();
            ClaudeAgentProvider provider = new ClaudeAgentProvider(
                    new ClaudeExecutableLocator(Path.of("/nonexistent/claude")));
            DrydockSessionService service = harness.remoteService(() -> true, runner,
                    liveness, SessionMcpAccess.none(), null, provider);

            ManagedAgentSession row = persistRemoteSession(harness,
                    new AgentBinding(AgentKind.CLAUDE, Optional.of("conv-7"), Optional.empty()),
                    SessionStatus.INACTIVE, false);
            ExecutionException refused = assertThrows(ExecutionException.class,
                    () -> service.resumeSession(row.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertEquals("Box drydock-repo is unreachable; try again", refused.getCause().getMessage());
            assertEquals(Optional.of(RemoteSessionLiveness.State.UNKNOWN), liveness.state(row.id()));
            assertTrue(harness.consoles.opens.isEmpty());
            assertEquals(SessionStatus.INACTIVE, persisted(harness, row.id()).status());
            harness.store.flush();
        }
    }

    /** The transient probe/list reply pairs: listed-but-running, and a listing that failed outright. */
    private static List<RecordingBoxRunner> transientRunners() {
        RecordingBoxRunner listedRunning = new RecordingBoxRunner();
        listedRunning.probe = new ProcessResult(255, "", "ssh: connect to host: Connection refused");
        listedRunning.list = new ProcessResult(0, boxTable("2"), "");
        RecordingBoxRunner listingFailed = new RecordingBoxRunner();
        listingFailed.probe = new ProcessResult(255, "", "ssh: connect to host: Connection refused");
        listingFailed.list = new ProcessResult(3, "", "workspaces: boom");
        return List.of(listedRunning, listingFailed);
    }

    /**
     * OQ7's fallback: a remote row whose repository is no longer recorded
     * opens its console in the user's home — a directory that exists on
     * this Mac — never in the box placeholder the row stores.
     */
    @Test
    void aRowWithoutARepositoryRecordOpensItsConsoleAtHome(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingBoxRunner runner = new RecordingBoxRunner();
        ClaudeAgentProvider provider = new ClaudeAgentProvider(
                new ClaudeExecutableLocator(Path.of("/nonexistent/claude")));
        DrydockSessionService service = harness.remoteService(() -> true, runner,
                new RemoteSessionLiveness(), SessionMcpAccess.none(), null, provider);

        // A repository id nothing in this state records — the row was
        // created against a project this install no longer knows.
        ManagedAgentSession row = persistRemoteSession(harness, RepositoryId.newId(),
                new AgentBinding(AgentKind.CLAUDE, Optional.of("conv-7"), Optional.empty()),
                SessionStatus.INACTIVE, false);
        service.resumeSession(row.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(1, harness.consoles.opens.size());
        assertEquals(Path.of(System.getProperty("user.home")),
                harness.consoles.opens.get(0).workingDirectory());
        harness.store.flush();
    }

    // ---- workspace-box remote rows: stop, close, forget, refusals (T16) -------------

    /**
     * The exact kill-session argv the explicit Stop and the Forget issue
     * for the box alias: the four batch options, `--` before the host, and
     * the remote kill naming the tmux session after the row's managed id.
     */
    private static List<String> killArgv(ManagedSessionId id) {
        return List.of("ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=5",
                "-o", "ServerAliveInterval=3", "-o", "ServerAliveCountMax=2",
                "--", "workspace-drydock-repo", "'tmux' 'kill-session' '-t' 'drydock-" + id + "'");
    }

    /** The stderr tmux itself prints when asked to kill a session it does not have. */
    private static final String NO_SUCH_SESSION = "can't find session: drydock-";

    /**
     * Records every question asked and answers from a canned answer, so a
     * Forget that asks is observable and one that must not ask is provable.
     */
    private static final class RecordingConfirm implements Function<String, CompletableFuture<Boolean>> {

        final List<String> questions = new CopyOnWriteArrayList<>();
        volatile Boolean answer;

        @Override
        public CompletableFuture<Boolean> apply(String question) {
            questions.add(question);
            return CompletableFuture.completedFuture(answer != null && answer);
        }
    }

    /**
     * AC-9, success: the explicit Stop on an attached remote row (resumed
     * first, so this IDE owns its live ssh execution) issues the
     * kill-session argv, ends the ssh client, and settles the row INACTIVE
     * with liveness DEAD.
     */
    @Test
    void stoppingARemoteRowKillsTheBoxSessionAndSettlesItInactiveAndDead(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingBoxRunner runner = new RecordingBoxRunner();
        RemoteSessionLiveness liveness = new RemoteSessionLiveness();
        ClaudeAgentProvider provider = new ClaudeAgentProvider(
                new ClaudeExecutableLocator(Path.of("/nonexistent/claude")));
        DrydockSessionService service = harness.remoteService(() -> true, runner,
                liveness, SessionMcpAccess.none(), null, provider);

        ManagedAgentSession row = persistRemoteSession(harness,
                new AgentBinding(AgentKind.CLAUDE, Optional.of("conv-7"), Optional.empty()),
                SessionStatus.INACTIVE, false);
        service.resumeSession(row.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(SessionStatus.RUNNING, persisted(harness, row.id()).status());

        service.stopSession(row.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(List.of(PROBE_ARGV, killArgv(row.id())), runner.argv,
                "AC-9: the Stop runs exactly the probe of the resume and then the kill");
        assertTrue(harness.consoles.stopped.contains(row.id()), "the ssh client ends with the Stop");
        assertEquals(SessionStatus.INACTIVE, persisted(harness, row.id()).status(),
                "AC-9: a successful kill settles the row INACTIVE");
        assertEquals(Optional.of(RemoteSessionLiveness.State.DEAD), liveness.state(row.id()));
        harness.store.flush();
    }

    /**
     * AC-9, the other settled form: a kill that reports "can't find session"
     * (the agent was already gone on the box) settles the row INACTIVE
     * with liveness DEAD too, exactly like exit 0 — the Stop's goal state
     * was already true.
     */
    @Test
    void stoppingARemoteRowSettlesInactiveWhenTheBoxSessionIsAlreadyGone(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingBoxRunner runner = new RecordingBoxRunner();
        runner.kill = new ProcessResult(1, "", NO_SUCH_SESSION + "<id>");
        RemoteSessionLiveness liveness = new RemoteSessionLiveness();
        DrydockSessionService service = harness.remoteService(() -> true, runner,
                liveness, SessionMcpAccess.none(), null);

        ManagedAgentSession row = persistRemoteSession(harness,
                new AgentBinding(AgentKind.CLAUDE, Optional.of("conv-7"), Optional.empty()),
                SessionStatus.RUNNING, false);

        service.stopSession(row.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(SessionStatus.INACTIVE, persisted(harness, row.id()).status());
        assertEquals(Optional.of(RemoteSessionLiveness.State.DEAD), liveness.state(row.id()));
        harness.store.flush();
    }

    /**
     * AC-9, failure: a kill that cannot conclude — the box is unreachable —
     * surfaces an error naming the box, settles the row IDLE with liveness
     * UNKNOWN, and never reports the row stopped: no INACTIVE anywhere.
     */
    @Test
    void aFailedRemoteStopSurfacesAnErrorNamingTheBoxAndNeverReportsTheRowStopped(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingBoxRunner runner = new RecordingBoxRunner();
        runner.kill = new ProcessResult(255, "", "ssh: connect to host: Connection refused");
        RemoteSessionLiveness liveness = new RemoteSessionLiveness();
        DrydockSessionService service = harness.remoteService(() -> true, runner,
                liveness, SessionMcpAccess.none(), null);

        ManagedAgentSession row = persistRemoteSession(harness,
                new AgentBinding(AgentKind.CLAUDE, Optional.of("conv-7"), Optional.empty()),
                SessionStatus.RUNNING, false);

        ExecutionException failed = assertThrows(ExecutionException.class,
                () -> service.stopSession(row.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue(failed.getCause().getMessage().contains("drydock-repo"),
                "the error names the box: " + failed.getCause().getMessage());

        ManagedAgentSession stored = persisted(harness, row.id());
        assertNotEquals(SessionStatus.INACTIVE, stored.status(),
                "AC-9: a failed Stop is never reported as a stopped agent");
        assertNotEquals(SessionStatus.EXITED, stored.status());
        assertEquals(Optional.of(RemoteSessionLiveness.State.UNKNOWN), liveness.state(row.id()));
        assertTrue(harness.consoles.stopped.contains(row.id()), "the ssh client still ends");
        harness.store.flush();
    }

    /**
     * AC-9, timeout: a kill that hangs past its 10 s budget (the runner
     * throws the timeout the real seam would) is the failure table's third
     * row — the same error, IDLE, UNKNOWN, never INACTIVE.
     */
    @Test
    void aHungRemoteStopIsTheFailureTableNotAStoppedRow(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingBoxRunner runner = new RecordingBoxRunner();
        runner.killFailure = new ProcessTimeoutException(List.of("ssh"), Duration.ofSeconds(10));
        RemoteSessionLiveness liveness = new RemoteSessionLiveness();
        DrydockSessionService service = harness.remoteService(() -> true, runner,
                liveness, SessionMcpAccess.none(), null);

        ManagedAgentSession row = persistRemoteSession(harness,
                new AgentBinding(AgentKind.CLAUDE, Optional.of("conv-7"), Optional.empty()),
                SessionStatus.RUNNING, false);

        ExecutionException failed = assertThrows(ExecutionException.class,
                () -> service.stopSession(row.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue(failed.getCause().getMessage().contains("drydock-repo"),
                "the timeout error names the box: " + failed.getCause().getMessage());
        assertEquals(SessionStatus.IDLE, persisted(harness, row.id()).status());
        assertEquals(Optional.of(RemoteSessionLiveness.State.UNKNOWN), liveness.state(row.id()));
        harness.store.flush();
    }

    /**
     * INV-8/AC-9's busy half, on a stepped executor: the STOPPING busy state
     * is up before the blocking kill begins — the first executor task marks
     * the row, the second kills — so the click's window never shows a
     * running agent while its stop is already in flight. The mark is legal
     * exactly because this IDE owns the row's live ssh execution (INV-4).
     */
    @Test
    void theRemoteStopShowsTheStoppingBusyStateBeforeTheKillRuns(@TempDir Path base) throws Exception {
        SteppedExecutorService executor = new SteppedExecutorService();
        Harness harness = concurrentHarness(base, executor);
        RecordingBoxRunner runner = new RecordingBoxRunner();
        RemoteSessionLiveness liveness = new RemoteSessionLiveness();
        ClaudeAgentProvider provider = new ClaudeAgentProvider(
                new ClaudeExecutableLocator(Path.of("/nonexistent/claude")));
        DrydockSessionService service = harness.remoteService(() -> true, runner,
                liveness, SessionMcpAccess.none(), null, provider);

        ManagedAgentSession row = persistRemoteSession(harness,
                new AgentBinding(AgentKind.CLAUDE, Optional.of("conv-7"), Optional.empty()),
                SessionStatus.INACTIVE, false);
        CompletableFuture<ManagedAgentSession> resumed = service.resumeSession(row.id());
        executor.runAll();
        resumed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(SessionStatus.RUNNING, persisted(harness, row.id()).status());

        CompletableFuture<Void> stopping = service.stopSession(row.id());
        assertFalse(stopping.isDone());
        executor.runNext();  // the mark task, and only it
        assertEquals(SessionStatus.STOPPING, persisted(harness, row.id()).status(),
                "the busy state is up before the kill lands");
        assertTrue(runner.argv.stream().noneMatch(RecordingBoxRunner::isKillSession),
                "the kill has not run yet");
        executor.runAll();  // the kill task
        stopping.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(SessionStatus.INACTIVE, persisted(harness, row.id()).status());
        assertEquals(Optional.of(RemoteSessionLiveness.State.DEAD), liveness.state(row.id()));
        harness.store.flush();
    }

    /**
     * R1 defect 1: an inconclusive Stop on an already-IDLE detached row (the
     * normal state of a detached-alive session) still records the UNKNOWN
     * liveness — the row needs no status change, but the map must not keep
     * claiming ALIVE for a kill that could not conclude. With no local
     * execution, no termination callback ever repairs this: the Stop's own
     * settle is the only writer.
     */
    @Test
    void aFailedStopOnADetachedIdleRowStillRecordsUnknownLiveness(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingBoxRunner runner = new RecordingBoxRunner();
        runner.kill = new ProcessResult(255, "", "ssh: connect to host: Connection refused");
        RemoteSessionLiveness liveness = new RemoteSessionLiveness();
        DrydockSessionService service = harness.remoteService(() -> true, runner,
                liveness, SessionMcpAccess.none(), null);

        ManagedAgentSession row = persistRemoteSession(harness,
                new AgentBinding(AgentKind.CLAUDE, Optional.of("conv-7"), Optional.empty()),
                SessionStatus.IDLE, false);
        liveness.set(row.id(), RemoteSessionLiveness.State.ALIVE);

        ExecutionException failed = assertThrows(ExecutionException.class,
                () -> service.stopSession(row.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue(failed.getCause().getMessage().contains("drydock-repo"),
                "the error names the box: " + failed.getCause().getMessage());

        assertEquals(SessionStatus.IDLE, persisted(harness, row.id()).status(),
                "the row stays resumable-idle");
        assertEquals(Optional.of(RemoteSessionLiveness.State.UNKNOWN), liveness.state(row.id()),
                "the inconclusive kill records UNKNOWN even though no status change was needed");
        harness.store.flush();
    }

    /**
     * R1 defect 2, first ordering: the ssh client terminates during the Stop's
     * kill round-trip — before `stopConsole` could mark the execution's
     * stop-requested flag — so the end reaches the recorder as an ordinary
     * one (stopRequested=false) and, pre-fix, its DEAD probe would persist
     * EXITED over the Stop's later confirmed INACTIVE. The Stop is
     * identified before the kill, so the end settles nothing and probes
     * nothing: the kill's verdict is the only settle.
     */
    @Test
    void anExplicitStopSettlesByTheKillEvenWhenTheClientEndsDuringIt(@TempDir Path base) throws Exception {
        SteppedExecutorService executor = new SteppedExecutorService();
        Harness harness = concurrentHarness(base, executor);
        RecordingBoxRunner runner = new RecordingBoxRunner();
        // A probe would say DEAD — and settle EXITED over the Stop's verdict.
        runner.hasSession = new ProcessResult(1, "", "can't find session: drydock-<id>");
        RemoteSessionLiveness liveness = new RemoteSessionLiveness();
        ClaudeAgentProvider provider = new ClaudeAgentProvider(
                new ClaudeExecutableLocator(Path.of("/nonexistent/claude")));
        DrydockSessionService service = harness.remoteService(() -> true, runner,
                liveness, SessionMcpAccess.none(), null, provider);

        ManagedAgentSession row = persistRemoteSession(harness,
                new AgentBinding(AgentKind.CLAUDE, Optional.of("conv-7"), Optional.empty()),
                SessionStatus.INACTIVE, false);
        CompletableFuture<ManagedAgentSession> resumed = service.resumeSession(row.id());
        executor.runAll();
        resumed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(SessionStatus.RUNNING, persisted(harness, row.id()).status());

        runner.duringKill = () -> service.endRecorder().executionTerminated(row.id(), false, 0);
        CompletableFuture<Void> stopping = service.stopSession(row.id());
        executor.runAll();
        stopping.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(SessionStatus.INACTIVE, persisted(harness, row.id()).status(),
                "the kill's confirmed verdict settles, unoverwritten by the client's end");
        assertEquals(Optional.of(RemoteSessionLiveness.State.DEAD), liveness.state(row.id()));
        assertEquals(List.of(PROBE_ARGV, killArgv(row.id())), runner.argv,
                "the client's end issued no has-session probe against the in-flight Stop");
        harness.store.flush();
    }

    /**
     * R1 defect 2, second ordering: the client remains live briefly after
     * `stopConsole` — `destroyProcess` does not wait for termination — so the
     * Stop's own settle is suppressed by the claim guard while the dying
     * client is still the live execution it sees. Pre-fix, the eventual
     * probe overwrote the confirmed kill (an UNKNOWN probe settled
     * IDLE/UNKNOWN over INACTIVE). Post-fix the dying client's end settles
     * by the verdict the Stop recorded.
     */
    @Test
    void anExplicitStopSettlesByTheKillEvenWhenItsOwnSettleWasSuppressedByTheDyingClient(
            @TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingBoxRunner runner = new RecordingBoxRunner();
        // A probe would be UNREACHABLE — and settle IDLE/UNKNOWN over the verdict.
        runner.hasSession = new ProcessResult(255, "", "ssh: connect to host: Connection timed out");
        RemoteSessionLiveness liveness = new RemoteSessionLiveness();
        ClaudeAgentProvider provider = new ClaudeAgentProvider(
                new ClaudeExecutableLocator(Path.of("/nonexistent/claude")));
        DrydockSessionService service = harness.remoteService(() -> true, runner,
                liveness, SessionMcpAccess.none(), null, provider);

        ManagedAgentSession row = persistRemoteSession(harness,
                new AgentBinding(AgentKind.CLAUDE, Optional.of("conv-7"), Optional.empty()),
                SessionStatus.INACTIVE, false);
        service.resumeSession(row.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(SessionStatus.RUNNING, persisted(harness, row.id()).status());

        // The dying client: still the live execution the claim guard sees,
        // until the test says it terminated.
        AtomicBoolean clientEnded = new AtomicBoolean();
        harness.liveExecution = id -> !clientEnded.get();

        service.stopSession(row.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(SessionStatus.STOPPING, persisted(harness, row.id()).status(),
                "the Stop's own settle was suppressed by the still-dying client");

        clientEnded.set(true);
        service.endRecorder().executionTerminated(row.id(), true, 0);

        assertEquals(SessionStatus.INACTIVE, persisted(harness, row.id()).status(),
                "the end settles the kill's recorded verdict, never a probe's guess");
        assertEquals(Optional.of(RemoteSessionLiveness.State.DEAD), liveness.state(row.id()));
        assertEquals(List.of(PROBE_ARGV, killArgv(row.id())), runner.argv,
                "no has-session probe ran against the Stop's verdict");
        harness.store.flush();
    }

    /**
     * R1 defect 5: a cancellation of the Stop's returned future between the
     * two executor stages — after the mark task wrote STOPPING, before the
     * kill task starts — must clear the busy mark: no client was stopped, so
     * no termination callback ever repairs it (INV-8's every-path rule).
     */
    @Test
    void aCancelledRemoteStopClearsTheStoppingMark(@TempDir Path base) throws Exception {
        SteppedExecutorService executor = new SteppedExecutorService();
        Harness harness = concurrentHarness(base, executor);
        RecordingBoxRunner runner = new RecordingBoxRunner();
        RemoteSessionLiveness liveness = new RemoteSessionLiveness();
        ClaudeAgentProvider provider = new ClaudeAgentProvider(
                new ClaudeExecutableLocator(Path.of("/nonexistent/claude")));
        DrydockSessionService service = harness.remoteService(() -> true, runner,
                liveness, SessionMcpAccess.none(), null, provider);

        ManagedAgentSession row = persistRemoteSession(harness,
                new AgentBinding(AgentKind.CLAUDE, Optional.of("conv-7"), Optional.empty()),
                SessionStatus.INACTIVE, false);
        CompletableFuture<ManagedAgentSession> resumed = service.resumeSession(row.id());
        executor.runAll();
        resumed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(SessionStatus.RUNNING, persisted(harness, row.id()).status());

        CompletableFuture<Void> stopping = service.stopSession(row.id());
        executor.runNext();   // the mark task, and only it
        assertEquals(SessionStatus.STOPPING, persisted(harness, row.id()).status());
        stopping.cancel(true);

        assertEquals(SessionStatus.RUNNING, persisted(harness, row.id()).status(),
                "a cancelled Stop leaves no stranded STOPPING mark");
        assertTrue(runner.argv.stream().noneMatch(RecordingBoxRunner::isKillSession),
                "the kill never ran");
        harness.store.flush();
    }

    /**
     * R1 defect 5, the rejected-submission hole: a second-stage submission
     * the executor refuses (shutdown raced it) completes the chain
     * exceptionally without ever invoking the kill — the same stranded
     * STOPPING, repaired the same way.
     */
    @Test
    void aRejectedRemoteStopStageClearsTheStoppingMark(@TempDir Path base) throws Exception {
        SteppedExecutorService executor = new SteppedExecutorService();
        Harness harness = concurrentHarness(base, executor);
        RecordingBoxRunner runner = new RecordingBoxRunner();
        RemoteSessionLiveness liveness = new RemoteSessionLiveness();
        ClaudeAgentProvider provider = new ClaudeAgentProvider(
                new ClaudeExecutableLocator(Path.of("/nonexistent/claude")));
        DrydockSessionService service = harness.remoteService(() -> true, runner,
                liveness, SessionMcpAccess.none(), null, provider);

        ManagedAgentSession row = persistRemoteSession(harness,
                new AgentBinding(AgentKind.CLAUDE, Optional.of("conv-7"), Optional.empty()),
                SessionStatus.INACTIVE, false);
        CompletableFuture<ManagedAgentSession> resumed = service.resumeSession(row.id());
        executor.runAll();
        resumed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(SessionStatus.RUNNING, persisted(harness, row.id()).status());

        CompletableFuture<Void> stopping = service.stopSession(row.id());   // the mark task is queued
        executor.shutdown();   // its kill submission, inside the mark task, is refused
        executor.runNext();    // the mark task runs; the kill stage never starts

        assertEquals(SessionStatus.RUNNING, persisted(harness, row.id()).status(),
                "a rejected kill submission leaves no stranded STOPPING mark");
        assertTrue(runner.argv.stream().noneMatch(RecordingBoxRunner::isKillSession));
        assertTrue(stopping.isCompletedExceptionally(), "the chain failed, and completed");
        harness.store.flush();
    }

    /**
     * R1 defect 4: a remote resume's ssh client ends early — a fast remote
     * failure — while the launch still holds its claim, so the end's settle
     * is suppressed under that claim. Pre-fix the suppression discarded the
     * result and the finalize persisted RUNNING over a dead client, forever.
     * Post-fix the settle is deferred and re-applied when the claim ends:
     * the launch completes and the row settles by the probe's verdict.
     */
    @Test
    void anEarlyRemoteClientEndUnderTheLaunchsOwnClaimSettlesWhenTheClaimEnds(@TempDir Path base) throws Exception {
        SteppedExecutorService executor = new SteppedExecutorService();
        Harness harness = concurrentHarness(base, executor);
        RecordingBoxRunner runner = new RecordingBoxRunner();
        runner.hasSession = new ProcessResult(0, "", "");   // the probe says ALIVE → IDLE
        RemoteSessionLiveness liveness = new RemoteSessionLiveness();
        // The recording provider queues nothing at init, so the stepped
        // stages below are the resume's own.
        RecordingProvider provider = new RecordingProvider(
                AgentKind.CLAUDE, SessionIdStrategy.PRESET, null, true, false, null, harness.events);
        DrydockSessionService service = harness.remoteService(() -> true, runner,
                liveness, SessionMcpAccess.none(), null, provider);
        harness.liveExecution = id -> false;   // the client ended: no execution is live
        CompletableFuture<Void> open = new CompletableFuture<>();
        harness.consoles.pendingOpen = open;

        ManagedAgentSession row = persistRemoteSession(harness,
                new AgentBinding(AgentKind.CLAUDE, Optional.of("conv-7"), Optional.empty()),
                SessionStatus.IDLE, false);
        CompletableFuture<ManagedAgentSession> resumed = service.resumeSession(row.id());
        executor.runNext();   // the read stage
        executor.runNext();   // the claim, the probe, the console open — the future is pending

        // The ssh client ends immediately, under the launch's own claim.
        service.endRecorder().executionTerminated(row.id(), false, 0);

        open.complete(null);   // the console opened
        // The probe (suppressed under the claim, deferred), the finalize
        // (persisting RUNNING), and the claim's end (re-applying the
        // deferred settle) all run here, in that order.
        executor.runAll();
        resumed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        ManagedAgentSession settled = persisted(harness, row.id());
        assertEquals(SessionStatus.IDLE, settled.status(),
                "the early end's deferred settle lands once the claim ends — never a stranded RUNNING");
        assertEquals(Optional.of(RemoteSessionLiveness.State.ALIVE), liveness.state(row.id()));
        harness.store.flush();
    }

    /**
     * The detached half of the Stop offer: a row with no ssh execution here
     * (IDLE, ALIVE in the liveness map) takes the same path — kill, settle
     * INACTIVE with DEAD — because Stop is offered on ALIVE-detached rows
     * even though no local execution exists to stop.
     */
    @Test
    void stoppingADetachedRemoteRowKillsTheBoxSessionToo(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingBoxRunner runner = new RecordingBoxRunner();
        RemoteSessionLiveness liveness = new RemoteSessionLiveness();
        DrydockSessionService service = harness.remoteService(() -> true, runner,
                liveness, SessionMcpAccess.none(), null);

        ManagedAgentSession row = persistRemoteSession(harness,
                new AgentBinding(AgentKind.CLAUDE, Optional.of("conv-7"), Optional.empty()),
                SessionStatus.IDLE, false);
        liveness.set(row.id(), RemoteSessionLiveness.State.ALIVE);

        service.stopSession(row.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(List.of(killArgv(row.id())), runner.argv);
        assertEquals(SessionStatus.INACTIVE, persisted(harness, row.id()).status());
        assertEquals(Optional.of(RemoteSessionLiveness.State.DEAD), liveness.state(row.id()));
        harness.store.flush();
    }

    /**
     * AC-7's service half: window close on a remote row issues no
     * kill-session and persists neither EXITED nor INACTIVE — the row keeps
     * the status it was decoded with, and only its ssh client ends (INV-10:
     * the close paths touch the box never).
     */
    @Test
    void closingTheWindowEndsARemoteRowsClientWithoutKillingOrSettlingIt(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingBoxRunner runner = new RecordingBoxRunner();
        RemoteSessionLiveness liveness = new RemoteSessionLiveness();
        DrydockSessionService service = harness.remoteService(() -> true, runner,
                liveness, SessionMcpAccess.none(), null);

        ManagedAgentSession row = persistRemoteSession(harness,
                new AgentBinding(AgentKind.CLAUDE, Optional.of("conv-7"), Optional.empty()),
                SessionStatus.RUNNING, false);
        service.stopSessionsForClose(List.of(row.id()));

        assertTrue(runner.argv.isEmpty(), "AC-7: close never issues kill-session");
        ManagedAgentSession stored = persisted(harness, row.id());
        assertNotEquals(SessionStatus.INACTIVE, stored.status(),
                "AC-7: close persists no INACTIVE for a remote row");
        assertNotEquals(SessionStatus.EXITED, stored.status(),
                "AC-7: close persists no EXITED for a remote row");
        assertTrue(harness.consoles.stopped.contains(row.id()),
                "the ssh client ends with the close");
        harness.store.flush();
    }

    /**
     * R1 defect 6: the close's indirect termination. The close path ends
     * the ssh client (the box-side agent is never touched); the client's
     * eventual end used to reach the recorder with the stop flag set and
     * settle INACTIVE through the DEAD probe branch — or EXITED, when the
     * client had already ended before the flag could be set. The close is
     * identified before the client is asked to end, so the end settles the
     * durable row IDLE (AC-7): never EXITED, never INACTIVE, and no probe
     * runs against the box.
     */
    @Test
    void aCloseOriginatedClientEndSettlesTheRemoteRowIdleNeverInactiveOrExited(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingBoxRunner runner = new RecordingBoxRunner();
        // A probe would say DEAD — the box-side agent had independently
        // exited — and, pre-fix, settle INACTIVE through the requested-stop
        // branch.
        runner.hasSession = new ProcessResult(1, "", "can't find session: drydock-<id>");
        RemoteSessionLiveness liveness = new RemoteSessionLiveness();
        DrydockSessionService service = harness.remoteService(() -> true, runner,
                liveness, SessionMcpAccess.none(), null);

        ManagedAgentSession row = persistRemoteSession(harness,
                new AgentBinding(AgentKind.CLAUDE, Optional.of("conv-7"), Optional.empty()),
                SessionStatus.RUNNING, false);
        // The client is live until the close ends it; the end is delivered below.
        AtomicBoolean clientEnded = new AtomicBoolean();
        harness.liveExecution = id -> !clientEnded.get();

        service.stopSessionsForClose(List.of(row.id()));
        assertTrue(harness.consoles.stopped.contains(row.id()), "the ssh client ends with the close");

        clientEnded.set(true);
        service.endRecorder().executionTerminated(row.id(), true, 0);

        ManagedAgentSession stored = persisted(harness, row.id());
        assertNotEquals(SessionStatus.INACTIVE, stored.status(), "AC-7: a close never persists INACTIVE");
        assertNotEquals(SessionStatus.EXITED, stored.status(), "AC-7: a close never persists EXITED");
        assertEquals(SessionStatus.IDLE, stored.status(), "the close-originated end settles the row IDLE");
        assertEquals(Optional.empty(), liveness.state(row.id()),
                "no probe ran, so no liveness answer was earned");
        assertTrue(runner.argv.isEmpty(),
                "no kill-session, no has-session: the close touches the box never (INV-10)");
        harness.store.flush();
    }

    /**
     * AC-21, kill-ok: the Forget on a remote row kills the box session first
     * and then removes the row — no confirmation needed, because the kill
     * itself confirmed the agent gone. The liveness entry leaves with the
     * row.
     */
    @Test
    void forgettingARemoteRowKillsTheBoxSessionThenRemovesTheRow(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingBoxRunner runner = new RecordingBoxRunner();
        RecordingConfirm confirm = new RecordingConfirm();
        RemoteSessionLiveness liveness = new RemoteSessionLiveness();
        DrydockSessionService service = harness.remoteService(() -> true, runner,
                liveness, SessionMcpAccess.none(), null, confirm);

        ManagedAgentSession row = persistRemoteSession(harness,
                new AgentBinding(AgentKind.CLAUDE, Optional.of("conv-7"), Optional.empty()),
                SessionStatus.IDLE, false);
        liveness.set(row.id(), RemoteSessionLiveness.State.ALIVE);

        service.deleteSession(row.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(List.of(killArgv(row.id())), runner.argv,
                "AC-21: the Forget kills the tmux session first");
        assertTrue(confirm.questions.isEmpty(), "a kill that confirmed the agent gone asks nothing");
        assertNull(persistedOrNull(harness, row.id()), "the row is removed");
        assertTrue(harness.consoles.forgotten.contains(row.id()));
        assertEquals(Optional.empty(), liveness.state(row.id()),
                "the liveness entry leaves with the row");
        harness.store.flush();
    }

    /**
     * AC-21, kill-no-session: a kill answering "can't find session" — the
     * agent was already gone on the box — removes the row without asking,
     * exactly like a confirmed kill.
     */
    @Test
    void forgettingARemoteRowRemovesItWhenTheBoxSessionIsAlreadyGone(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingBoxRunner runner = new RecordingBoxRunner();
        runner.kill = new ProcessResult(1, "", NO_SUCH_SESSION + "<id>");
        RecordingConfirm confirm = new RecordingConfirm();
        DrydockSessionService service = harness.remoteService(() -> true, runner,
                new RemoteSessionLiveness(), SessionMcpAccess.none(), null, confirm);

        ManagedAgentSession row = persistRemoteSession(harness,
                new AgentBinding(AgentKind.CLAUDE, Optional.of("conv-7"), Optional.empty()),
                SessionStatus.IDLE, false);

        service.deleteSession(row.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue(confirm.questions.isEmpty(), "no confirm is asked for an already-gone session");
        assertNull(persistedOrNull(harness, row.id()), "the row is removed");
        harness.store.flush();
    }

    /**
     * AC-21, unreachable-confirmed: a kill that cannot reach the box asks
     * "The agent may still be running on box <name>. Forget anyway?" and
     * removes the row only on the user's Yes.
     */
    @Test
    void anUnreachableBoxAsksBeforeForgettingAndRemovesTheRowOnConfirm(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingBoxRunner runner = new RecordingBoxRunner();
        runner.kill = new ProcessResult(255, "", "ssh: connect to host: Connection refused");
        RecordingConfirm confirm = new RecordingConfirm();
        confirm.answer = true;
        DrydockSessionService service = harness.remoteService(() -> true, runner,
                new RemoteSessionLiveness(), SessionMcpAccess.none(), null, confirm);

        ManagedAgentSession row = persistRemoteSession(harness,
                new AgentBinding(AgentKind.CLAUDE, Optional.of("conv-7"), Optional.empty()),
                SessionStatus.IDLE, false);

        service.deleteSession(row.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(List.of("The agent may still be running on box drydock-repo. Forget anyway?"),
                confirm.questions);
        assertNull(persistedOrNull(harness, row.id()), "a confirmed forget removes the row");
        harness.store.flush();
    }

    /**
     * AC-21, unreachable-cancelled: a declined confirm removes nothing — the
     * row, its binding and its ssh client all stay exactly as they were.
     */
    @Test
    void aDeclinedForgetConfirmRemovesNothing(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingBoxRunner runner = new RecordingBoxRunner();
        runner.kill = new ProcessResult(255, "", "ssh: connect to host: Connection refused");
        RecordingConfirm confirm = new RecordingConfirm();
        confirm.answer = false;
        DrydockSessionService service = harness.remoteService(() -> true, runner,
                new RemoteSessionLiveness(), SessionMcpAccess.none(), null, confirm);

        ManagedAgentSession row = persistRemoteSession(harness,
                new AgentBinding(AgentKind.CLAUDE, Optional.of("conv-7"), Optional.empty()),
                SessionStatus.IDLE, false);

        service.deleteSession(row.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(1, confirm.questions.size());
        ManagedAgentSession stored = persisted(harness, row.id());
        assertEquals(Optional.of(BOX), stored.workspaceBox(), "the row keeps its binding");
        assertEquals(SessionStatus.IDLE, stored.status());
        assertTrue(harness.consoles.forgotten.isEmpty(), "nothing was forgotten");
        harness.store.flush();
    }

    /**
     * The fresh-start refusal, remote half: {@code startSession} on a remote
     * row is refused with a reason naming the box, before any box command,
     * console or state change.
     */
    @Test
    void aRemoteRowIsNeverStartedFreshButRefusedNamingItsBox(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingBoxRunner runner = new RecordingBoxRunner();
        DrydockSessionService service = harness.remoteService(() -> true, runner,
                new RemoteSessionLiveness(), SessionMcpAccess.none(), null);

        ManagedAgentSession row = persistRemoteSession(harness,
                new AgentBinding(AgentKind.CLAUDE, Optional.empty(), Optional.empty()),
                SessionStatus.IDLE, false);

        ExecutionException refused = assertThrows(ExecutionException.class,
                () -> service.startSession(row.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue(refused.getCause().getMessage().contains("drydock-repo"),
                "the refusal names the box: " + refused.getCause().getMessage());
        assertTrue(runner.argv.isEmpty(), "the refusal runs no box command");
        assertTrue(harness.consoles.opens.isEmpty(), "the refusal opens no console");
        assertEquals(SessionStatus.IDLE, persisted(harness, row.id()).status());
        harness.store.flush();
    }

    /**
     * The transfer refusal, remote half: a remote row's conversation lives on
     * its box and cannot be transferred to a local agent; the refusal names
     * the box and changes nothing.
     */
    @Test
    void aRemoteRowIsNeverTransferredButRefusedNamingItsBox(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        RecordingBoxRunner runner = new RecordingBoxRunner();
        DrydockSessionService service = harness.remoteService(() -> true, runner,
                new RemoteSessionLiveness(), SessionMcpAccess.none(), null);

        ManagedAgentSession row = persistRemoteSession(harness,
                new AgentBinding(AgentKind.CLAUDE, Optional.of("conv-7"), Optional.empty()),
                SessionStatus.INACTIVE, false);

        ExecutionException refused = assertThrows(ExecutionException.class,
                () -> service.transferStoppedSession(row.id(), AgentKind.CODEX)
                        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue(refused.getCause().getMessage().contains("drydock-repo"),
                "the refusal names the box: " + refused.getCause().getMessage());
        assertTrue(runner.argv.isEmpty());
        assertTrue(harness.consoles.opens.isEmpty());
        ManagedAgentSession stored = persisted(harness, row.id());
        assertEquals(AgentKind.CLAUDE, stored.agentKind(), "the binding is untouched");
        assertEquals(SessionStatus.INACTIVE, stored.status());
        harness.store.flush();
    }

    /**
     * The Done flow's gate: a remote row is refused with a named reason (the
     * flow is local-worktree semantics throughout), and a local row passes
     * the gate untouched — the flow runs exactly as before for it.
     */
    @Test
    void theDoneFlowRefusesARemoteRowAndPassesALocalRow(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        DrydockSessionService service = harness.service(new TerminalAgentProvider());

        ManagedAgentSession remote = persistRemoteSession(harness,
                new AgentBinding(AgentKind.CLAUDE, Optional.of("conv-7"), Optional.empty()),
                SessionStatus.IDLE, false);
        Optional<String> refusal = service.doneFlowRefusal(remote.id());
        assertTrue(refusal.isPresent());
        assertTrue(refusal.get().contains("drydock-repo"),
                "the refusal names the box: " + refusal.get());
        assertTrue(refusal.get().contains("Stop or Forget"),
                "the refusal names the row's real endings: " + refusal.get());

        ManagedAgentSession local = persistSession(harness, AgentKind.CLAUDE, "Local work",
                new AgentBinding(AgentKind.CLAUDE, Optional.of("conv-1"), Optional.empty()),
                base, SessionStatus.INACTIVE, Instant.parse("2026-08-12T10:00:00Z"));
        assertEquals(Optional.empty(), service.doneFlowRefusal(local.id()),
                "a local row passes the gate untouched");
        harness.store.flush();
    }
}
