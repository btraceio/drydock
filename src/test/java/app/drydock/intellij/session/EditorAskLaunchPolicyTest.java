package app.drydock.intellij.session;

import app.drydock.activity.SessionActivityWatcher;
import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.agent.api.ConversationSource;
import app.drydock.agent.api.McpAccess;
import app.drydock.agent.api.CreateContext;
import app.drydock.agent.api.LaunchPlan;
import app.drydock.agent.api.McpDelivery;
import app.drydock.agent.api.ResumeContext;
import app.drydock.agent.api.SessionIdStrategy;
import app.drydock.agent.spi.AgentProvider;
import app.drydock.agent.spi.AgentProvider.EditorAskConversationProof;
import app.drydock.agent.spi.AgentProvider.EditorAskDelivery;
import app.drydock.agent.spi.AgentProvider.EditorAskLaunch;
import app.drydock.agent.spi.AgentProvider.EditorAskRequest;
import app.drydock.agent.spi.EditorAskTurnObserver;
import app.drydock.app.ApplicationStateStore;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedAgentSession.SessionPurpose;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.RepositorySettings;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.domain.SshRemote;
import app.drydock.editorqa.EditorAskLane;
import app.drydock.editorqa.EditorAskPorts.LaunchOutcome;
import app.drydock.editorqa.EditorAskPorts.LaunchRequest;
import app.drydock.editorqa.EditorAskPorts.LaunchResult;
import app.drydock.editorqa.EditorAskPorts.Resumability;
import app.drydock.editorqa.EditorAskPrompt;
import app.drydock.editorqa.EditorAskRecord;
import app.drydock.editorqa.EditorAskRowBinder;
import app.drydock.intellij.agent.LaunchClaims;
import app.drydock.intellij.editorqa.EditorAskLaunchPolicy;
import app.drydock.intellij.review.SessionMcpAccess;
import app.drydock.intellij.session.DrydockSessionServiceTest.FakeConsoleHost;
import app.drydock.intellij.session.DrydockSessionServiceTest.RecordingDiscovery;
import app.drydock.intellij.session.DrydockSessionServiceTest.RecordingProvider;
import app.drydock.intellij.session.DrydockSessionServiceTest.SameThreadExecutorService;
import app.drydock.state.JsonApplicationStateRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The strict Q&amp;A launch/resume boundary: one positional prompt, no model on
 * resume, ordinary rows unchanged, no snapshot/late binding, remote refused,
 * claim/credential unwinding, and a stale end never clearing a replacement.
 */
class EditorAskLaunchPolicyTest {

    private final List<ApplicationStateStore> stores = new ArrayList<>();

    @AfterEach
    void flush() {
        stores.forEach(ApplicationStateStore::flush);
    }

    private static final class Access implements SessionMcpAccess {
        final List<ManagedSessionId> released = new CopyOnWriteArrayList<>();
        final List<String> calls = new CopyOnWriteArrayList<>();
        final Path file;
        volatile boolean grant = true;

        Access(Path file) {
            this.file = file;
        }

        @Override public Optional<McpAccess> accessFor(ManagedSessionId id, McpDelivery delivery) {
            calls.add("mint");
            return grant ? Optional.of(new McpAccess("http://e.invalid/mcp", "t", Optional.of(file)))
                    : Optional.empty();
        }

        @Override public void release(ManagedSessionId id) {
            calls.add("release");
            released.add(id);
        }
    }

    private static final class Events implements EditorAskLaunchPolicy.Events {
        final List<String> ended = new CopyOnWriteArrayList<>();
        final List<String> proven = new CopyOnWriteArrayList<>();
        final List<EditorAskLane.Event> observed = new CopyOnWriteArrayList<>();
        volatile String forgetReason;

        @Override public void executionEnded(String root, String executionId, long generation) {
            ended.add(executionId + "/" + generation);
        }

        @Override public boolean conversationProven(String root, String executionId, long generation,
                                                    AgentKind kind, String id) {
            proven.add(executionId + "/" + generation + "/" + id);
            return true;
        }

        @Override public void observed(String root, EditorAskLane.Event event) {
            observed.add(event);
        }

        @Override public Optional<String> forgetProtection(ManagedAgentSession session) {
            return Optional.ofNullable(forgetReason);
        }
    }

    /** A scripted provider observer: records what is armed, returns what a test staged. */
    private static final class FakeObserver implements EditorAskTurnObserver {
        final List<String> armed = new CopyOnWriteArrayList<>();
        final List<Event> staged = new CopyOnWriteArrayList<>();
        final AtomicBoolean closed = new AtomicBoolean();

        @Override public String launchNonce() {
            return "fake-nonce-1";
        }

        @Override public long arm(String marker) {
            armed.add(marker);
            return 0;
        }

        @Override public Observation read(long cursor) {
            List<Event> after = staged.stream().filter(e -> e.sequence() > cursor).toList();
            long next = after.stream().mapToLong(Event::sequence).max().orElse(cursor);
            return new Observation(after, next, Optional.empty());
        }

        @Override public void close() {
            closed.set(true);
        }
    }

    /** A composed (tab) prompt carrying a fresh turn marker, as the coordinator hands it over. */
    private static String compose(String question) {
        return EditorAskPrompt.compose(new EditorAskRecord(EditorAskRecord.newId(), 1, ManagedSessionId.newId(),
                null, null, null, question, Instant.EPOCH, EditorAskRecord.Status.QUEUED, null, null, null, null));
    }

    private final class Fixture {
        final Path base;
        final Repository repo;
        final ApplicationStateStore store;
        final List<String> events = new CopyOnWriteArrayList<>();
        final FakeConsoleHost consoles = new FakeConsoleHost(events);
        final Access access;
        final Events qaEvents = new Events();
        final DrydockSessionService service;
        final EditorAskLaunchPolicy policy;
        final RecordingProvider provider;
        final RecordingDiscovery discovery = new RecordingDiscovery(events);
        final ManagedAgentSession row;
        final AtomicBoolean transcript = new AtomicBoolean(true);
        final AtomicBoolean live = new AtomicBoolean();
        final Map<String, String> prompts = new HashMap<>();
        final List<FakeObserver> observers = new CopyOnWriteArrayList<>();

        Fixture(Path base, SessionIdStrategy strategy, boolean sessionIdUsed) throws Exception {
            this.base = base;
            this.access = new Access(Files.createFile(base.resolve("cred.json")));
            this.repo = new Repository(RepositoryId.newId(), base, "repo", Instant.EPOCH, Instant.EPOCH,
                    RepositorySettings.DEFAULT);
            this.store = ApplicationStateStore.forRepository(
                    new JsonApplicationStateRepository(base.resolve("state.json")));
            stores.add(store);
            store.update(s -> s.withRepositories(List.of(repo)));
            this.provider = new RecordingProvider(AgentKind.CLAUDE, strategy,
                    strategy == SessionIdStrategy.DISCOVERED ? discovery : null, true, sessionIdUsed, null, events) {
                @Override public EditorAskLaunch prepareEditorAskCreate(CreateContext c, EditorAskRequest request) {
                    CreateContext full = new CreateContext(c.displayName(), c.sessionId(), c.workingDirectory(),
                            c.remote(), c.mcp(), c.model(), c.evalMode(), c.readOnly(),
                            Optional.of(request.firstTurnPrompt()), c.stdioMcpServers(), c.launchResources(),
                            c.persistence(), c.workflowAccess(), c.remoteLaunchSupport());
                    return accepted(c.mcp(), buildCreateCommand(full));
                }

                @Override public EditorAskLaunch prepareEditorAskResume(ResumeContext r, EditorAskRequest request) {
                    ResumeContext full = new ResumeContext(r.agentSessionId(), r.agentSessionName(),
                            r.workingDirectory(), r.remote(), r.mcp(), r.evalMode(), r.readOnly(),
                            r.stdioMcpServers(), Optional.of(request.firstTurnPrompt()), r.launchResources(),
                            r.persistence(), r.workflowAccess(), r.remoteLaunchSupport());
                    return accepted(r.mcp(), buildResumeCommand(full));
                }

                private EditorAskLaunch accepted(Optional<McpAccess> mcp, LaunchPlan plan) {
                    boolean carried = mcp.flatMap(McpAccess::credentialFile)
                            .map(file -> plan.command().contains(file.toString())).orElse(false);
                    if (!carried) {
                        return EditorAskLaunch.refused("not carried");
                    }
                    FakeObserver observer = new FakeObserver();
                    observers.add(observer);
                    return EditorAskLaunch.accepted(plan, new EditorAskDelivery(AgentKind.CLAUDE,
                            EditorAskDelivery.required(AgentKind.CLAUDE),
                            EditorAskConversationProof.RENDERED_PRESET_ID), observer);
                }

                @Override public Optional<ConversationSource> conversations() {
                    return Optional.of(new ConversationSource() {
                        @Override public List<Conversation> listConversations(Path dir) {
                            return List.of();
                        }

                        @Override public boolean transcriptExists(Path dir, String id) {
                            return transcript.get();
                        }
                    });
                }
            };
            provider.delivery = McpDelivery.CONFIG_FILE;
            provider.wiresMcp = true;
            SameThreadExecutorService executor = new SameThreadExecutorService();
            AgentRegistry registry = new AgentRegistry(List.of(provider),
                    new AgentContext(base, base.resolve("activity"), executor));
            SessionActivityWatcher watcher = new SessionActivityWatcher(base.resolve("activity"), executor);
            LaunchClaims claims = new LaunchClaims(id -> live.get() && !consoles.stopped.contains(id));
            this.service = new DrydockSessionService(CompletableFuture.completedFuture(null), () -> store,
                    () -> registry, () -> watcher, p -> CompletableFuture.completedFuture(repo.id()), consoles,
                    executor, p -> List.of(), access, claims);
            this.row = store.ensureMarkedSession(new ManagedAgentSession.Builder(ManagedSessionId.newId(),
                    repo.id(), "Q&A", AgentBinding.unlaunched(AgentKind.CLAUDE, SessionMode.READ_ONLY),
                    SessionWorkspace.inRepository(base), Instant.EPOCH)
                    .status(SessionStatus.INACTIVE).purpose(SessionPurpose.EDITOR_QA).build());
            this.policy = new EditorAskLaunchPolicy(service, () -> registry, new EditorAskRowBinder(store),
                    id -> OptionalLong.of(7), qaEvents, (e, g, p) -> null);
        }

        LaunchResult launch(String exec, Optional<String> resume, Optional<String> model, String prompt) {
            String composed = prompts.computeIfAbsent(prompt, EditorAskLaunchPolicyTest::compose);
            LaunchResult r = policy.launch(new LaunchRequest(row.id(), base.toString(), exec, 1,
                    AgentKind.CLAUDE, model, resume, composed));
            if (r.outcome() == LaunchOutcome.STARTED) {
                key = "k-" + exec;
                policy.started(row.id(), key);
                live.set(true);
            }
            return r;
        }

        ManagedAgentSession stored() {
            return store.state().sessions().stream().filter(s -> s.id().equals(row.id())).findFirst().orElseThrow();
        }

        /** The registry's identity of the running execution: one key per launch, as a real registry stamps. */
        String key = null;

        void exit() {
            live.set(false);
            service.executionTerminated(row.id(), false, 0);
            policy.ended(row.id(), key);
        }
    }

    @Test
    void createCarriesOnePositionalPromptAndModelButNoSnapshotOrTaskMark(@TempDir Path base) throws Exception {
        Fixture f = new Fixture(base, SessionIdStrategy.DISCOVERED, false);
        LaunchResult r = f.launch("e1", Optional.empty(), Optional.of("opus"), "Q1");
        assertEquals(LaunchOutcome.STARTED, r.outcome());
        assertEquals(7, r.credentialGeneration());
        assertEquals(1, f.provider.createContexts.size());
        var ctx = f.provider.createContexts.get(0);
        assertEquals(Optional.of(f.prompts.get("Q1")), ctx.initialPrompt());
        assertEquals(Optional.of("opus"), ctx.model());
        assertTrue(ctx.readOnly());
        assertFalse(ctx.evalMode());
        assertTrue(ctx.launchResources().isEmpty());
        assertTrue(ctx.stdioMcpServers().isEmpty());
        assertTrue(f.discovery.snapshotsTaken.isEmpty(), "no snapshot for a marked row");
        assertFalse(f.events.contains("discover"));
        assertFalse(f.stored().startedWithTask());
        assertEquals(SessionStatus.RUNNING, f.stored().status());
        assertTrue(f.store.state().repositories().get(0).settings().lastUsedAgent().isEmpty(),
                "ordinary last-used settings stay untouched");
    }

    @Test
    void createMintsUnderClaimAfterRevokingThePreviousToken(@TempDir Path base) throws Exception {
        Fixture f = new Fixture(base, SessionIdStrategy.PRESET, true);
        f.launch("e1", Optional.empty(), Optional.empty(), "Q1");
        assertEquals(List.of("release", "mint"), f.access.calls.subList(0, 2));
        assertEquals(1, f.provider.createContexts.size());
    }

    @Test
    void renderedPresetIdIsReportedWithTheLaunchIdentityNotBoundByTheService(@TempDir Path base) throws Exception {
        Fixture f = new Fixture(base, SessionIdStrategy.PRESET, true);
        f.launch("e1", Optional.empty(), Optional.empty(), "Q1");
        assertEquals(1, f.qaEvents.proven.size());
        assertTrue(f.qaEvents.proven.get(0).startsWith("e1/1/"));
        assertTrue(f.stored().agentSessionId().isEmpty(), "bound only by the coordinator's proof path");
    }

    @Test
    void exactResumeUsesTheConfirmedIdWithoutModelAndOnePrompt(@TempDir Path base) throws Exception {
        Fixture f = new Fixture(base, SessionIdStrategy.PRESET, true);
        String id = "11111111-2222-3333-4444-555555555555";
        f.store.update(s -> s.withSessions(List.of(f.row.withAgentSessionId(Optional.of(id)))));
        assertEquals(Resumability.EXACT, f.policy.verifyConversation(f.row.id(), AgentKind.CLAUDE, id));
        LaunchResult r = f.launch("e2", Optional.of(id), Optional.of("ignored"), "Q2");
        assertEquals(LaunchOutcome.STARTED, r.outcome());
        assertTrue(f.provider.createContexts.isEmpty());
        var rc = f.provider.resumeContexts.get(0);
        assertEquals(Optional.of(id), rc.agentSessionId());
        assertEquals(Optional.of(f.prompts.get("Q2")), rc.resumePrompt());
        assertTrue(f.consoles.opens.get(0).plan().command().contains(id));
    }

    @Test
    void unprovableConversationIsLostAndFreshFallbackStillWorks(@TempDir Path base) throws Exception {
        Fixture f = new Fixture(base, SessionIdStrategy.PRESET, true);
        f.transcript.set(false);
        assertEquals(Resumability.LOST, f.policy.verifyConversation(f.row.id(), AgentKind.CLAUDE, "x"));
        assertEquals(LaunchOutcome.STARTED, f.launch("e3", Optional.empty(), Optional.empty(), "Q3").outcome());
    }

    @Test
    void genericEntryPointsRefuseTheMarkedRow(@TempDir Path base) throws Exception {
        Fixture f = new Fixture(base, SessionIdStrategy.PRESET, true);
        f.store.update(s -> s.withSessions(List.of(f.row.withAgentSessionId(Optional.of("abc")))));
        for (CompletableFuture<ManagedAgentSession> attempt : List.of(
                f.service.resumeSession(f.row.id()), f.service.startSession(f.row.id()),
                f.service.launchSession(f.row, Optional.of("task")))) {
            ExecutionException e = assertThrows(ExecutionException.class, attempt::get);
            assertInstanceOf(IllegalStateException.class, e.getCause());
        }
        assertTrue(f.consoles.opens.isEmpty());
        assertTrue(f.provider.createContexts.isEmpty() && f.provider.resumeContexts.isEmpty());
        assertTrue(f.access.calls.isEmpty(), "a refused entry mints nothing");
    }

    @Test
    void sshRemoteRepositoryIsRefusedBeforeAnyLaunch(@TempDir Path base) throws Exception {
        Fixture f = new Fixture(base, SessionIdStrategy.PRESET, true);
        Repository remote = new Repository(f.repo.id(), base, "repo", Instant.EPOCH, Instant.EPOCH,
                RepositorySettings.DEFAULT, new SshRemote("host", "/srv/repo"));
        f.store.update(s -> s.withRepositories(List.of(remote)));
        LaunchResult r = f.launch("e4", Optional.empty(), Optional.empty(), "Q");
        assertEquals(LaunchOutcome.REFUSED, r.outcome());
        assertTrue(f.consoles.opens.isEmpty());
        assertFalse(f.access.calls.contains("mint"), "no credential is minted for a refused remote");
    }

    @Test
    void unreachableToolRefusesBeforeTheConsoleAndRevokes(@TempDir Path base) throws Exception {
        Fixture f = new Fixture(base, SessionIdStrategy.PRESET, true);
        f.provider.wiresMcp = false;
        LaunchResult r = f.launch("e5", Optional.empty(), Optional.empty(), "Q");
        assertEquals(LaunchOutcome.REFUSED, r.outcome());
        assertTrue(f.consoles.opens.isEmpty(), "the prompt never reached the harness");
        assertTrue(f.access.released.size() >= 2, "the unused credential is revoked and the claim end releases");
        assertTrue(f.service.endRecorder() != null);
    }

    @Test
    void consoleStartFailureIsRefusedAndOpenFailureIsFailed(@TempDir Path base) throws Exception {
        Fixture f = new Fixture(base, SessionIdStrategy.PRESET, true);
        f.consoles.startFailure = new IllegalStateException("no host");
        assertEquals(LaunchOutcome.REFUSED, f.launch("e6", Optional.empty(), Optional.empty(), "Q").outcome());
        f.consoles.startFailure = null;
        f.consoles.pendingOpen = CompletableFuture.failedFuture(new IllegalStateException("tab died"));
        assertEquals(LaunchOutcome.FAILED, f.launch("e7", Optional.empty(), Optional.empty(), "Q").outcome());
        // both settled: the claim is free again for the next launch
        assertEquals(LaunchOutcome.STARTED, f.launch("e8", Optional.empty(), Optional.empty(), "Q").outcome());
    }

    @Test
    void endIsReportedWithTheLaunchIdentityOnceAndNeverLateBinds(@TempDir Path base) throws Exception {
        Fixture f = new Fixture(base, SessionIdStrategy.DISCOVERED, false);
        f.discovery.lateCandidates.add("other-cli-conversation");
        f.launch("e9", Optional.empty(), Optional.empty(), "Q");
        f.exit();
        f.exit();
        assertEquals(List.of("e9/1"), f.qaEvents.ended);
        assertTrue(f.discovery.createdAtsReceived.isEmpty(), "no late bind scan for a marked row");
        assertTrue(f.stored().agentSessionId().isEmpty());
        assertEquals(LaunchOutcome.STARTED, f.launch("e10", Optional.empty(), Optional.empty(), "Q").outcome());
        f.exit();
        assertEquals(List.of("e9/1", "e10/1"), f.qaEvents.ended);
    }

    @Test
    void ordinaryRowsKeepTheirTaskMarkDiscoveryAndLastUsed(@TempDir Path base) throws Exception {
        Fixture f = new Fixture(base, SessionIdStrategy.DISCOVERED, false);
        ManagedAgentSession plain = new ManagedAgentSession.Builder(ManagedSessionId.newId(), f.repo.id(), "p",
                AgentBinding.unlaunched(AgentKind.CLAUDE, SessionMode.WRITES),
                SessionWorkspace.inRepository(base), Instant.EPOCH).status(SessionStatus.STARTING).build();
        ManagedAgentSession done = f.service.launchSession(plain, Optional.of("task")).get();
        assertTrue(done.startedWithTask());
        assertEquals(1, f.discovery.snapshotsTaken.size());
        assertEquals(Optional.of(AgentKind.CLAUDE),
                f.store.state().repositories().get(0).settings().lastUsedAgent());
    }

    @Test
    void typedDeliveryEvidenceGatesTheLaunchAndTheObserverIsArmedByTheProvider(@TempDir Path base) throws Exception {
        Fixture f = new Fixture(base, SessionIdStrategy.PRESET, true);
        assertEquals(LaunchOutcome.STARTED, f.launch("e1", Optional.empty(), Optional.empty(), "Q1").outcome());
        assertEquals(1, f.observers.size(), "one observer per launch, prepared before the console opened");
        assertEquals(Optional.of("fake-nonce-1"), f.policy.launchNonce("e1"));
        // a provider that cannot prove delivery refuses before the console, and its observer is not leaked
        f.exit();
        f.provider.wiresMcp = false;
        assertEquals(LaunchOutcome.REFUSED, f.launch("e2", Optional.empty(), Optional.empty(), "Q2").outcome());
        assertEquals(1, f.consoles.opens.size());
    }

    @Test
    void observerEventsFeedTheCoordinatorFencedByExecutionIdentity(@TempDir Path base) throws Exception {
        Fixture f = new Fixture(base, SessionIdStrategy.PRESET, true);
        f.launch("e1", Optional.empty(), Optional.empty(), "Q1");
        String marker = EditorAskPrompt.decode(f.prompts.get("Q1")).questionId();
        FakeObserver observer = f.observers.get(0);
        observer.staged.add(new EditorAskTurnObserver.Event(1, EditorAskTurnObserver.Kind.BOUND, marker, "conv-1"));
        f.policy.pump();
        assertEquals(List.of(EditorAskLane.EventKind.SUBMITTED),
                f.qaEvents.observed.stream().map(EditorAskLane.Event::kind).toList());
        assertEquals(2, f.qaEvents.observed.get(0).cursor());
        assertEquals("e1", f.qaEvents.observed.get(0).executionId());
        assertEquals(marker, f.qaEvents.observed.get(0).marker());
        assertTrue(f.qaEvents.proven.contains("e1/1/conv-1"), "BOUND proves the conversation of this launch");
        observer.staged.add(new EditorAskTurnObserver.Event(2, EditorAskTurnObserver.Kind.SETTLED, marker, "conv-1"));
        f.policy.pump();
        f.policy.pump(); // reading again feeds nothing twice
        assertEquals(List.of(EditorAskLane.EventKind.SUBMITTED, EditorAskLane.EventKind.END,
                        EditorAskLane.EventKind.READY),
                f.qaEvents.observed.stream().map(EditorAskLane.Event::kind).toList());
        assertEquals(List.of(2L, 4L, 5L), f.qaEvents.observed.stream().map(EditorAskLane.Event::cursor).toList());
    }

    @Test
    void aFollowUpIsArmedBeforeTheWriteAndAnUnarmableOneIsNeverWritten(@TempDir Path base) throws Exception {
        Fixture f = new Fixture(base, SessionIdStrategy.PRESET, true);
        f.launch("e1", Optional.empty(), Optional.empty(), "Q1");
        FakeObserver observer = f.observers.get(0);
        String next = compose("Q2");
        f.policy.write("e1", 1, next);
        assertEquals(List.of(EditorAskPrompt.decode(next).questionId()), observer.armed.subList(
                observer.armed.size() - 1, observer.armed.size()));
        assertEquals(EditorAskLane.WriteOutcome.REFUSED, f.policy.write("e1", 1, "not a prompt"));
    }

    @Test
    void aLateEndOfAReplacedExecutionNeverEndsTheCurrentLaunch(@TempDir Path base) throws Exception {
        Fixture f = new Fixture(base, SessionIdStrategy.PRESET, true);
        f.launch("e1", Optional.empty(), Optional.empty(), "Q1");
        f.policy.ended(f.row.id(), "some-other-execution");
        assertTrue(f.qaEvents.ended.isEmpty(), "another execution's end changes nothing");
        f.policy.ended(f.row.id(), null);
        assertTrue(f.qaEvents.ended.isEmpty());
        f.policy.ended(f.row.id(), f.key);
        assertEquals(List.of("e1/1"), f.qaEvents.ended);
        assertTrue(f.observers.get(0).closed.get(), "the launch's observer is released with it");
        // after the end the observer is no longer read
        f.policy.pump();
        assertTrue(f.qaEvents.observed.isEmpty());
    }

    @Test
    void forgetRefusalIsTheCoordinatorsAndOnlyForTheMarkedRow(@TempDir Path base) throws Exception {
        Fixture f = new Fixture(base, SessionIdStrategy.PRESET, true);
        assertTrue(EditorAskLaunchPolicy.forgetRefusal(f.policy, f.row).isEmpty(), "no protection source by default");
        assertTrue(EditorAskLaunchPolicy.forgetRefusal(null, f.row).isEmpty());
    }

    @Test
    void deleteSessionRefusesAProtectedQaRowEndToEndAndForgetsAnUnprotectedOne(@TempDir Path base) throws Exception {
        Fixture f = new Fixture(base, SessionIdStrategy.PRESET, true);
        f.qaEvents.forgetReason = "This Q&A session keeps answers; Forget would strand them.";
        ExecutionException refused = assertThrows(ExecutionException.class,
                () -> f.service.deleteSession(f.row.id()).get());
        assertInstanceOf(IllegalStateException.class, refused.getCause());
        assertTrue(refused.getCause().getMessage().contains("strand"), refused.getCause().getMessage());
        assertTrue(f.store.state().sessions().stream().anyMatch(x -> x.id().equals(f.row.id())),
                "the row is still in the catalog");
        f.qaEvents.forgetReason = null;
        f.service.deleteSession(f.row.id()).get();
        assertTrue(f.store.state().sessions().stream().noneMatch(x -> x.id().equals(f.row.id())));
    }
}
