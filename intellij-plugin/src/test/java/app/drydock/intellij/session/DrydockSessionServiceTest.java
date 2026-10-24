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
import app.drydock.agent.providers.terminal.TerminalAgentProvider;
import app.drydock.agent.spi.AgentProvider;
import app.drydock.app.ApplicationStateStore;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ApplicationState;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.ModelTiers;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.RepositorySettings;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.intellij.agent.AgentConsoleHost;
import app.drydock.intellij.agent.AgentExecutionListener;
import app.drydock.intellij.agent.LaunchClaims;
import app.drydock.intellij.review.SessionMcpAccess;
import app.drydock.mcp.McpSessionContext.RenameKind;
import app.drydock.mcp.McpSessionContext.RenameOutcome;
import app.drydock.state.JsonApplicationStateRepository;


import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.lang.reflect.Field;
import java.nio.file.Path;
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
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        @Override public void execute(Runnable command) { queued.addLast(command); }
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
        /**
         * By default an execution is live once {@code consoles} opened it and
         * has neither stopped nor forgotten it; a forget drops the execution,
         * as {@code AgentExecutionRegistry.forget} does.
         */
        Predicate<ManagedSessionId> liveExecution = id ->
                consoles.opens.stream().anyMatch(open -> open.sessionId().equals(id))
                        && !consoles.stopped.contains(id) && !consoles.forgotten.contains(id);

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
}
