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
import app.drydock.agent.api.McpDelivery;
import app.drydock.agent.api.ResumeContext;
import app.drydock.agent.api.SessionIdDiscovery;
import app.drydock.agent.api.SessionIdStrategy;
import app.drydock.agent.providers.terminal.TerminalAgentProvider;
import app.drydock.agent.spi.AgentProvider;
import app.drydock.app.ApplicationStateStore;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.RepositorySettings;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.intellij.terminal.SessionTabRegistry;
import app.drydock.intellij.terminal.TerminalSessionLauncher;
import app.drydock.state.JsonApplicationStateRepository;

import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

        @Override public AgentKind kind() { return kind; }
        @Override public String displayName() { return "Fake " + kind; }
        @Override public void init(AgentContext ctx) { }
        @Override public Optional<Path> locateExecutable() { return Optional.of(Path.of("/bin/true")); }
        @Override public String describeSearched() { return "PATH"; }
        @Override public AgentCapabilities probeCapabilities() { return new AgentCapabilities(true, true, "1.0"); }
        @Override public boolean supportsRemote() { return false; }
        @Override public McpDelivery mcpDelivery() { return McpDelivery.NONE; }

        @Override public LaunchPlan buildCreateCommand(CreateContext c) {
            threads.add(Thread.currentThread());
            createContexts.add(c);
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
            if (!supported) {
                return LaunchPlan.unsupported();
            }
            String built = command != null ? command
                    : "fake-agent --session '" + c.sessionId() + "' --name '" + c.displayName() + "'";
            return LaunchPlan.of(built, sessionIdUsed);
        }

        /**
         * Mirrors the Pi resume contract (C8/AC21): a known id resumes by
         * id, an unknown one falls to the picker — never --continue/--last.
         */
        @Override public LaunchPlan buildResumeCommand(ResumeContext r) {
            resumeContexts.add(r);
            if (r.agentSessionId().isPresent()) {
                return LaunchPlan.of("fake --session '" + r.agentSessionId().get() + "'", false);
            }
            return LaunchPlan.of("fake --resume", false);
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
    }

    /** A launcher that never touches the IDE: records the open, hands back a stub tab. */
    static final class FakeLauncher implements TerminalSessionLauncher {

        private final List<String> events;
        final List<Open> opens = new CopyOnWriteArrayList<>();

        record Open(ManagedSessionId sessionId, String tabName, Path workingDirectory, LaunchPlan plan) { }

        FakeLauncher(List<String> events) {
            this.events = events;
        }

        @Override public CompletableFuture<TerminalSessionLauncher.TerminalSessionTab> open(
                ManagedSessionId sessionId, String tabName, Path workingDirectory, LaunchPlan plan) {
            events.add("open");
            opens.add(new Open(sessionId, tabName, workingDirectory, plan));
            TerminalSessionLauncher.TerminalSessionTab tab = new TerminalSessionLauncher.TerminalSessionTab() {
                @Override public ManagedSessionId sessionId() { return sessionId; }
                @Override public Object tabKey() { return this; }
                @Override public TerminalToolWindowTab tab() { return null; }
                @Override public void close() { }
            };
            return CompletableFuture.completedFuture(tab);
        }

        @Override public SessionTabRegistry tabRegistry() { return new SessionTabRegistry(); }
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
        final FakeLauncher launcher = new FakeLauncher(events);
        final ExecutorService executor;

        private Harness(Path base, Repository repository, ExecutorService executor) {
            this.base = base;
            this.store = ApplicationStateStore.forRepository(
                    new JsonApplicationStateRepository(base.resolve("state.json")));
            this.repository = repository;
            this.executor = executor;
        }

        DrydockSessionService service(AgentProvider... providers) {
            AgentRegistry registry = new AgentRegistry(List.of(providers),
                    new AgentContext(base, base.resolve("activity"), executor));
            SessionActivityWatcher watcher = new SessionActivityWatcher(base.resolve("activity"), executor);
            Function<Path, CompletableFuture<RepositoryId>> resolver =
                    path -> CompletableFuture.completedFuture(repository.id());
            return new DrydockSessionService(CompletableFuture.completedFuture(null),
                    () -> store, () -> registry, () -> watcher, resolver, launcher, executor);
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
        assertEquals(1, harness.launcher.opens.size());
        assertEquals("My session", harness.launcher.opens.get(0).tabName());
        assertEquals(base.toAbsolutePath().normalize(), harness.launcher.opens.get(0).workingDirectory());
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
        assertTrue(harness.launcher.opens.isEmpty());
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
        assertEquals(1, harness.launcher.opens.size());
        assertEquals(TerminalAgentProvider.SHELL_COMMAND, harness.launcher.opens.get(0).plan().command());
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
        assertEquals(1, harness.launcher.opens.size());
        String command = harness.launcher.opens.get(0).plan().command();
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
        assertEquals(1, harness.launcher.opens.size());
        // The persisted id is what the resume command carried (AC20).
        assertTrue(harness.launcher.opens.get(0).plan().command().contains("pi-conv-42"),
                harness.launcher.opens.get(0).plan().command());
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
        assertTrue(harness.launcher.opens.isEmpty());
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
        assertTrue(harness.launcher.opens.isEmpty());
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
        assertTrue(harness.launcher.opens.isEmpty());
        assertEquals(SessionStatus.FAILED, persisted(harness, stored.id()).status());
        harness.store.flush();
    }

    // ---- lifecycle events -------------------------------------------------------

    @Test
    void sessionExitedPersistsExitedOrInactiveByVariant(@TempDir Path base) throws Exception {
        Harness harness = harness(base);
        DrydockSessionService service = harness.service(new RecordingProvider(
                AgentKind.PI, SessionIdStrategy.DISCOVERED, null, true, false, null, harness.events));
        Instant createdAt = Instant.parse("2026-08-12T10:00:00Z");
        ManagedAgentSession natural = persistSession(harness, AgentKind.PI, "Ended itself",
                AgentBinding.unlaunched(AgentKind.PI), base, SessionStatus.RUNNING, createdAt);
        ManagedAgentSession closedWhileRunning = persistSession(harness, AgentKind.PI, "Closed early",
                AgentBinding.unlaunched(AgentKind.PI), base, SessionStatus.RUNNING, createdAt);

        // Natural exit -> EXITED; tab closed while running -> INACTIVE (AC13
        // persistence half); both bump lastOpenedAt.
        service.sessionExited(natural.id(), false);
        service.sessionExited(closedWhileRunning.id(), true);

        ManagedAgentSession exited = persisted(harness, natural.id());
        ManagedAgentSession inactive = persisted(harness, closedWhileRunning.id());
        assertEquals(SessionStatus.EXITED, exited.status());
        assertEquals(SessionStatus.INACTIVE, inactive.status());
        assertTrue(exited.lastOpenedAt().isAfter(createdAt));
        assertTrue(inactive.lastOpenedAt().isAfter(createdAt));

        // A later tabClosed variant after a natural exit must not rewrite
        // the EXITED record (the monitor's ordering, guarded here too).
        service.sessionExited(natural.id(), true);
        assertEquals(SessionStatus.EXITED, persisted(harness, natural.id()).status());

        // A session deleted in the meantime is a no-op, never a throw.
        service.sessionExited(ManagedSessionId.newId(), false);
        harness.store.flush();
    }

    // ---- delete ----------------------------------------------------------------

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
}
