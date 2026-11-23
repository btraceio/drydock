package app.drydock.intellij.session;

import app.drydock.activity.SessionActivityWatcher;
import app.drydock.agent.api.*;
import app.drydock.agent.spi.AgentProvider;
import app.drydock.app.ApplicationStateStore;
import app.drydock.delayedtasks.TaskLaunchResources;
import app.drydock.domain.*;
import app.drydock.intellij.agent.LaunchClaims;
import app.drydock.intellij.review.SessionMcpAccess;
import app.drydock.state.JsonApplicationStateRepository;
import app.drydock.tasks.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The T29 matrix: the launch resources a task session carries (T29a's TaskLaunchResources,
 * resolved by DrydockSessionService before each context is built -- startLaunch :647-657,
 * resumeClaimed :995-1005, launchReplacement :399-408, launchResourcesFor :750-756) across
 * create, resume and transfer, against the REAL task store on disk (real TaskRuns receipts,
 * a real installed TaskGuidance) with the sibling suite's recording fakes at the SPI edges
 * only. PLACEMENT: beside the sibling session suite -- the in-package placement reaching the
 * package-private 12-arg constructor seam directly, no reflection. Still smoke-covered
 * elsewhere (T24a/T26a-accepted): the real spawn, the provider CLIs' rendering, and the
 * production gate wiring (mcpServesTaskTools, FALSE for a degraded backend).
 */
class DrydockSessionTaskResourcesTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final class Harness {
        final Path base, tasksRoot;
        final ApplicationStateStore store;
        final Repository repository;
        final TaskGuidance guidance;
        final DrydockSessionServiceTest.FakeConsoleHost consoles =
                new DrydockSessionServiceTest.FakeConsoleHost(new CopyOnWriteArrayList<>());
        final AtomicReference<Thread> resolveThread = new AtomicReference<>();
        volatile BooleanSupplier taskToolsOnMcp = () -> false;
        private final ExecutorService executor;

        Harness(Path base, ExecutorService executor) {
            this.base = base;
            this.tasksRoot = base.resolve("task-store");
            this.store = ApplicationStateStore.forRepository(
                    new JsonApplicationStateRepository(base.resolve("state.json")));
            this.repository = new Repository(RepositoryId.newId(), base, "repo",
                    Instant.EPOCH, Instant.EPOCH, RepositorySettings.DEFAULT);
            this.executor = executor;
            this.guidance = new TaskGuidance(tasksRoot);
            store.update(state -> state.withRepositories(List.of(repository)));
        }
        DrydockSessionService service(boolean resolve, SessionMcpAccess access, AgentProvider... providers) {
            AgentRegistry registry = new AgentRegistry(List.of(providers),
                    new AgentContext(base, base.resolve("activity"), executor));
            TaskLaunchResources resources = new TaskLaunchResources(tasksRoot, guidance, () -> {
                resolveThread.set(Thread.currentThread());
                return taskToolsOnMcp.getAsBoolean();
            });
            return new DrydockSessionService(CompletableFuture.completedFuture(null), () -> store,
                    () -> registry, () -> new SessionActivityWatcher(base.resolve("activity"), executor),
                    path -> CompletableFuture.completedFuture(repository.id()), consoles, executor,
                    path -> List.of(), access,
                    new LaunchClaims(id -> consoles.opens.stream().anyMatch(open -> open.sessionId().equals(id))
                            && !consoles.stopped.contains(id) && !consoles.forgotten.contains(id)),
                    resolve ? resources : null);
        }
    }

    private static final class FixedAccess implements SessionMcpAccess {
        private final Optional<McpAccess> answer;

        FixedAccess(Path base) throws IOException {
            Path file = base.resolve("cred.json");
            Files.writeString(file, "{}");
            this.answer = Optional.of(new McpAccess("http://endpoint.invalid/mcp", "tok", Optional.of(file)));
        }
        @Override public Optional<McpAccess> accessFor(ManagedSessionId id, McpDelivery d) { return answer; }
        @Override public void release(ManagedSessionId id) { }
    }

    private static void reserveReceipt(Harness harness, TaskId task, ManagedSessionId session, Path consumer)
            throws Exception {
        Path ownerCatalog = harness.tasksRoot.resolve("catalog.json");
        try (TaskRuns runs = new TaskRuns(harness.tasksRoot)) {
            runs.reserve(task, session.toString(), harness.tasksRoot, ownerCatalog, List.of(consumer));
            runs.flush();
        }
        Files.writeString(ownerCatalog, "{}");
    }

    private static ManagedAgentSession persistSession(Harness harness, AgentBinding binding, String name,
            Path workingDirectory) {
        ManagedAgentSession session = new ManagedAgentSession.Builder(ManagedSessionId.newId(),
                harness.repository.id(), name, binding,
                SessionWorkspace.inRepository(workingDirectory.toAbsolutePath().normalize()), Instant.now())
                .status(SessionStatus.INACTIVE).build();
        harness.store.update(state -> state.withSessions(Stream.concat(
                state.sessions().stream(), Stream.of(session)).toList()));
        return session;
    }

    private static ManagedAgentSession persisted(Harness h, ManagedSessionId id) {
        return h.store.state().sessions().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    private static DrydockSessionServiceTest.RecordingProvider target(AgentKind kind) {
        DrydockSessionServiceTest.RecordingProvider target = new DrydockSessionServiceTest.RecordingProvider(
                kind, SessionIdStrategy.DISCOVERED, null, true, false, null, new CopyOnWriteArrayList<>());
        return target;
    }

    private static DrydockSessionServiceTest.RecordingProvider transferSource() {
        DrydockSessionServiceTest.RecordingProvider provider = new DrydockSessionServiceTest.RecordingProvider(
                AgentKind.CLAUDE, SessionIdStrategy.PRESET, null, true, true, null, new CopyOnWriteArrayList<>()) {
            @Override public Optional<List<String>> buildTransferSummaryCommand(Path wd, String id, String prompt) {
                String answer = prompt.replace("<current goal>", "goal").replace("<what is done>", "completed")
                        .replace("<concrete next steps>", "next").replace("<constraints, failures, or none>", "none");
                return Optional.of(List.of("/usr/bin/printf", "%s", answer));
            }
        };
        provider.delivery = McpDelivery.CONFIG_FILE;
        return provider;
    }
    private static AgentBinding piBinding() {
        return new AgentBinding(AgentKind.PI, Optional.of("pi-conv-1"), Optional.empty());
    }
    private static AgentBinding claudeBinding() {
        return AgentBinding.unlaunched(AgentKind.CLAUDE).withSessionId(Optional.of("source-42"));
    }
    private static String normalized(Optional<String> prompt) {
        return prompt.orElseThrow().replaceAll("DRYDOCK_TRANSFER_V1_(?:BEGIN|END)_[0-9a-f]+", "MARK");
    }
    // Matrix 1 (create): the resolved receipt directories and the installed guidance reach the
    // CreateContext BEFORE it is built: the gate is provider.createContexts::isEmpty, so a late
    // resolution would answer false and drop the guidance.
    @Test
    void createResolvesReceiptDirectoriesAndGuidanceBeforeConstructionOffTheCallingThread(
            @TempDir Path base) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor(
                run -> { Thread t = new Thread(run); t.setDaemon(true); return t; });
        Harness harness = new Harness(base, executor);
        Path consumer = Files.createDirectories(base.resolve("consumer-wt"));
        harness.guidance.install();
        DrydockSessionServiceTest.RecordingProvider provider = target(AgentKind.PI);
        harness.taskToolsOnMcp = provider.createContexts::isEmpty;
        DrydockSessionService service = harness.service(true, new FixedAccess(base), provider);
        ManagedAgentSession prepared = service.prepareSession(base, AgentKind.PI, "Dependent")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        reserveReceipt(harness, TaskId.of("prereq-a"), prepared.id(), consumer);
        service.launchSession(prepared).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        LaunchResources resources = provider.createContexts.get(0).launchResources().orElseThrow();
        assertEquals(List.of(consumer), resources.extraDirectories());
        assertEquals(Optional.of(harness.guidance.guidanceDirectory()), resources.taskGuidance());
        // Off-EDT (matrix 6): resolution answered on the executor's own thread -- the same
        // stage that builds the command; the caller only parked on the future.
        assertNotEquals(Thread.currentThread(), harness.resolveThread.get(),
                "resolution ran on the calling thread");
        assertEquals(provider.threads.get(0), harness.resolveThread.get(),
                "resolution shares the executor stage that builds the command");
        harness.store.flush();
    }

    // Matrix 2 (resume): the resume path carries the same resolved directories and the guidance
    // gate is RE-EVALUATED at every resume -- false, true, false over three resumes of one row.
    @Test
    void resumeCarriesTheSameResourcesAndReevaluatesTheGuidanceGate(@TempDir Path base) throws Exception {
        Harness harness = new Harness(base, new DrydockSessionServiceTest.SameThreadExecutorService());
        Path consumer = Files.createDirectories(base.resolve("consumer-wt"));
        harness.guidance.install();
        AtomicBoolean gate = new AtomicBoolean(); harness.taskToolsOnMcp = gate::get;
        DrydockSessionServiceTest.RecordingProvider provider = target(AgentKind.PI);
        DrydockSessionService service = harness.service(true, new FixedAccess(base), provider);
        ManagedAgentSession stored = persistSession(harness, piBinding(), "Dependent", base);
        reserveReceipt(harness, TaskId.of("prereq-a"), stored.id(), consumer);
        for (boolean answer : new boolean[] {false, true, false}) {
            gate.set(answer);
            service.resumeSession(stored.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            harness.consoles.stopped.add(stored.id());
        }
        assertEquals(List.of(Optional.empty(), Optional.of(harness.guidance.guidanceDirectory()),
                Optional.empty()), provider.resumeContexts.stream()
                .map(ctx -> ctx.launchResources().orElseThrow().taskGuidance()).toList());
        provider.resumeContexts.forEach(ctx -> assertEquals(List.of(consumer),
                ctx.launchResources().orElseThrow().extraDirectories()));
        harness.store.flush();
    }

    // Matrix 3 (transfer): every transferable provider's replacement context resolves the same
    // resources (the source runs a real printf summary, as the sibling suite's transfers); an
    // ordinary no-resources transfer is byte-identical to the pre-T29 null seam.
    @Test
    void transferResolvesTheSameResourcesForEveryProviderAndOrdinaryOnesStayByteIdentical(
            @TempDir Path base) throws Exception {
        Harness harness = new Harness(base, new DrydockSessionServiceTest.SameThreadExecutorService());
        Path consumer = Files.createDirectories(base.resolve("consumer-wt"));
        harness.guidance.install();
        harness.taskToolsOnMcp = () -> true;
        FixedAccess access = new FixedAccess(base);
        DrydockSessionServiceTest.RecordingProvider codex = target(AgentKind.CODEX);
        DrydockSessionServiceTest.RecordingProvider pi = target(AgentKind.PI);
        DrydockSessionServiceTest.RecordingProvider source = transferSource();
        DrydockSessionService service = harness.service(true, access, source, codex, pi);
        for (DrydockSessionServiceTest.RecordingProvider target : List.of(codex, pi)) {
            ManagedAgentSession row = persistSession(harness, claudeBinding(), "Same task", base);
            reserveReceipt(harness, TaskId.of("prereq-" + target.kind().persistedName()), row.id(), consumer);
            service.transferStoppedSession(row.id(), target.kind()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            LaunchResources resources = target.createContexts.getLast().launchResources().orElseThrow();
            assertEquals(List.of(consumer), resources.extraDirectories());
            assertEquals(Optional.of(harness.guidance.guidanceDirectory()), resources.taskGuidance());
        }
        // Ordinary session (no receipt, gate false): the resolver resolves empty and the
        // replacement matches the null-seam transfer byte for byte.
        harness.taskToolsOnMcp = () -> false;
        DrydockSessionServiceTest.RecordingProvider shared = target(AgentKind.CODEX);
        ManagedAgentSession resourcedRow = persistSession(harness, claudeBinding(), "Ordinary", base);
        ManagedAgentSession plainRow = persistSession(harness, claudeBinding(), "Ordinary", base);
        harness.service(true, access, source, shared).transferStoppedSession(resourcedRow.id(),
                AgentKind.CODEX).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        harness.service(false, access, source, shared).transferStoppedSession(plainRow.id(),
                AgentKind.CODEX).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        CreateContext resourced = shared.createContexts.get(0);
        CreateContext plain = shared.createContexts.get(1);
        assertEquals(normalized(plain.initialPrompt()), normalized(resourced.initialPrompt()));
        assertEquals(Optional.empty(), resourced.launchResources());
        assertEquals(harness.consoles.opens.get(2).plan().command(), harness.consoles.opens.get(3).plan().command());
        harness.store.flush();
    }

    // Matrix 4 (retained context): forgetting the prerequisite task changes nothing retroactively --
    // the dependent row is untouched and its next resume resolves the same retained directories
    // (D13; resolution happens only at launch/resume/transfer time).
    @Test
    void aForgottenPrerequisiteLeavesTheDependentRowAndItsResolvedContextUnchanged(
            @TempDir Path base) throws Exception {
        Harness harness = new Harness(base, new DrydockSessionServiceTest.SameThreadExecutorService());
        Path consumer = Files.createDirectories(base.resolve("consumer-wt"));
        DrydockSessionServiceTest.RecordingProvider provider = target(AgentKind.PI);
        DrydockSessionService service = harness.service(true, SessionMcpAccess.none(), provider);
        ManagedAgentSession dependent = persistSession(harness, piBinding(), "Dependent", base);
        TaskId prerequisite = TaskId.of("prereq-a");
        reserveReceipt(harness, prerequisite, dependent.id(), consumer);
        service.resumeSession(dependent.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        ManagedAgentSession afterFirstResume = persisted(harness, dependent.id());
        harness.consoles.stopped.add(dependent.id());
        try (TaskRuns runs = new TaskRuns(harness.tasksRoot)) {
            runs.forget(prerequisite); runs.flush();
        }
        assertEquals(afterFirstResume, persisted(harness, dependent.id()));
        service.resumeSession(dependent.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(provider.resumeContexts.get(0).launchResources(),
                provider.resumeContexts.get(1).launchResources());
        assertEquals(List.of(consumer),
                provider.resumeContexts.get(1).launchResources().orElseThrow().extraDirectories());
        harness.store.flush();
    }

    // Matrix 5 (missing required context): an unresolvable receipt directory and an unreadable
    // registry each refuse the launch VISIBLY (ResourcesRefusal, :92-103): no console, row FAILED.
    @Test
    void missingOrUnreadableRequiredContextRefusesTheLaunchVisibly(@TempDir Path base) throws Exception {
        Harness harness = new Harness(base, new DrydockSessionServiceTest.SameThreadExecutorService());
        DrydockSessionServiceTest.RecordingProvider provider = target(AgentKind.PI);
        DrydockSessionService service = harness.service(true, SessionMcpAccess.none(), provider);
        ManagedAgentSession prepared = service.prepareSession(base, AgentKind.PI, "Dependent")
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        reserveReceipt(harness, TaskId.of("prereq-a"), prepared.id(), base.resolve("gone-wt"));
        ExecutionException missing = assertThrows(ExecutionException.class,
                () -> service.launchSession(prepared).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue(missing.getCause() instanceof TaskLaunchResources.ResourcesRefusal, "got " + missing.getCause());
        assertTrue(missing.getCause().getMessage().contains("gone-wt"));
        assertEquals(SessionStatus.FAILED, persisted(harness, prepared.id()).status());
        // Scenario b: no receipt for this session, but the registry holds one torn file,
        // so "no rows" cannot be proven and the lookup must refuse.
        ManagedAgentSession stored = persistSession(harness, piBinding(), "Dependent", base);
        Files.createDirectories(harness.tasksRoot.resolve("runs"));
        Files.writeString(harness.tasksRoot.resolve("runs").resolve("torn-other.run"), "garbage\n");
        ExecutionException unreadable = assertThrows(ExecutionException.class,
                () -> service.resumeSession(stored.id()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue(unreadable.getCause() instanceof TaskLaunchResources.ResourcesRefusal,
                "got " + unreadable.getCause());
        assertTrue(unreadable.getCause().getMessage().contains("could not be read"));
        assertTrue(harness.consoles.opens.isEmpty() && provider.createContexts.isEmpty()
                && provider.resumeContexts.isEmpty());
        assertEquals(SessionStatus.FAILED, persisted(harness, stored.id()).status());
        harness.store.flush();
    }
}
