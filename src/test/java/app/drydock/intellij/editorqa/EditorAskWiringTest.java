package app.drydock.intellij.editorqa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.agent.api.McpDelivery;
import app.drydock.app.ApplicationStateStore;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ApplicationState;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedAgentSession.SessionPurpose;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PrLink;
import app.drydock.domain.PrState;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.editorqa.EditorAskHarnessPolicy.Choice;
import app.drydock.editorqa.EditorAskLane;
import app.drydock.editorqa.EditorAskRecord.Status;
import app.drydock.editorqa.EditorAskPorts.Result;
import app.drydock.editorqa.EditorAskPorts.Settings;
import app.drydock.intellij.editorqa.EditorAskUiModel.Row;
import app.drydock.intellij.review.ReviewBackend;
import app.drydock.state.ApplicationStateRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Production wiring of the editor Q&A feature: composition, local MCP injection, refusal and shutdown. */
class EditorAskWiringTest {

    private static final String TOKEN_HEADER = "X-Drydock-Session-Token";
    private static final String TOOLS_LIST = """
            {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""";
    private static final String TOOL = "editor_ask_answer";

    @TempDir
    Path base;

    private ExecutorService executor;
    private AgentRegistry agents;
    private ApplicationStateStore catalog;
    private final List<AutoCloseable> closeables = new ArrayList<>();
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        executor = Executors.newSingleThreadExecutor();
        agents = new AgentRegistry(List.of(), new AgentContext(base, base.resolve("activity"), executor));
        catalog = ApplicationStateStore.forRepository(new ApplicationStateRepository() {
            private ApplicationState state = ApplicationState.empty();

            @Override
            public ApplicationState load() {
                return state;
            }

            @Override
            public void save(ApplicationState next) {
                state = next;
            }
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        for (int i = closeables.size() - 1; i >= 0; i--) {
            closeables.get(i).close();
        }
        executor.shutdownNow();
    }

    private ReviewBackend startedBackend(Path dir) {
        ReviewBackend backend = new ReviewBackend(dir, List::of, List::of, agents);
        closeables.add(backend);
        backend.start();
        assertTrue(backend.ready());
        return backend;
    }

    private EditorAskService service(Path dir, ReviewBackend backend) {
        EditorAskService service = new EditorAskService(dir, catalog, backend, () -> agents, executor,
                new Settings() {
                    @Override
                    public Choice choice() {
                        return Choice.AUTO;
                    }

                    @Override
                    public boolean available(AgentKind kind) {
                        return kind == AgentKind.CLAUDE;
                    }

                    @Override
                    public Optional<String> model(AgentKind kind) {
                        return Optional.empty();
                    }
                });
        closeables.add(service);
        return service;
    }

    private String toolsList(ReviewBackend backend, ManagedSessionId row) throws Exception {
        var access = backend.accessFor(row, McpDelivery.CONFIG_FILE).orElseThrow();
        HttpRequest request = HttpRequest.newBuilder(URI.create(access.endpointUrl()))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header(TOKEN_HEADER, access.token())
                .POST(HttpRequest.BodyPublishers.ofString(TOOLS_LIST)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    private ManagedAgentSession marked(Path root) {
        Instant now = Instant.EPOCH;
        return new ManagedAgentSession(ManagedSessionId.newId(), RepositoryId.newId(), "Drydock Q&A — repo",
                new AgentBinding(AgentKind.CLAUDE, Optional.empty(), Optional.empty(), SessionMode.READ_ONLY),
                new SessionWorkspace(root, Optional.empty(), true), SessionStatus.INACTIVE, now, now,
                Optional.empty(), PrLink.of(PrState.NONE, Optional.empty()), false, Optional.empty())
                .toBuilder().purpose(SessionPurpose.EDITOR_QA).build();
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not reached in time");
            }
            Thread.sleep(20);
        }
    }

    // ---- AC-7: the tool is served from production startup, before any review tab ----

    @Test
    void localRouterListsTheToolAfterStartupAndAfterRestart() throws Exception {
        ReviewBackend bare = startedBackend(Files.createDirectory(base.resolve("bare")));
        assertFalse(bare.servesEditorAskAnswer(), "no service: not listed");
        assertFalse(toolsList(bare, ManagedSessionId.newId()).contains(TOOL));

        Path first = Files.createDirectory(base.resolve("first"));
        ReviewBackend backend = startedBackend(first);
        EditorAskService service = service(first, backend);
        assertTrue(backend.servesEditorAskAnswer());
        assertTrue(toolsList(backend, ManagedSessionId.newId()).contains(TOOL));
        service.close();
        backend.close();

        // Restart: a fresh backend and service over the same state directory serve it again.
        ReviewBackend restarted = startedBackend(first);
        service(first, restarted);
        assertTrue(toolsList(restarted, ManagedSessionId.newId()).contains(TOOL));
    }

    @Test
    void aQaRowGetsARotatedGenerationAndAnOrdinaryRowKeepsItsToken() throws Exception {
        ReviewBackend backend = startedBackend(base);
        service(base, backend);
        ManagedAgentSession qa = catalog.ensureMarkedSession(marked(base.resolve("checkout")));
        ManagedSessionId ordinary = ManagedSessionId.newId();

        backend.accessFor(qa.id(), McpDelivery.CONFIG_FILE).orElseThrow();
        long qaFirst = backend.credentialGeneration(qa.id()).orElseThrow();
        backend.accessFor(qa.id(), McpDelivery.CONFIG_FILE).orElseThrow();
        assertNotEquals(qaFirst, backend.credentialGeneration(qa.id()).orElseThrow(), "rotated under a new launch");
        assertFalse(backend.isCurrentCredential(qa.id(), qaFirst), "the old generation can no longer commit");

        backend.accessFor(ordinary, McpDelivery.CONFIG_FILE).orElseThrow();
        long first = backend.credentialGeneration(ordinary).orElseThrow();
        backend.accessFor(ordinary, McpDelivery.CONFIG_FILE).orElseThrow();
        assertEquals(first, backend.credentialGeneration(ordinary).orElseThrow(), "ordinary mint stays idempotent");
    }

    // ---- backend not ready: the launch is refused with a reason ----

    @Test
    void backendNotReadyRefusesTheLaunchWithAReasonAndKeepsTheRecord() throws Exception {
        EditorAskService service = service(base, null);
        Path root = Files.createDirectories(base.resolve("checkout"));
        ManagedAgentSession row = marked(root);
        Result result = service.coordinator().submit(
                EditorAskComposerPanel.tabSubmission(root.toString(), row, "why?")).get(10, TimeUnit.SECONDS);
        assertTrue(result.accepted(), result.refusal());
        String id = result.id();
        await(() -> service.model().rows(root.toString()).stream().anyMatch(r -> r.id().equals(id)
                && r.record().isSettled()));
        Row shown = service.model().rows(root.toString()).stream().filter(r -> r.id().equals(id)).findFirst()
                .orElseThrow();
        assertTrue(shown.record().failureReason().contains("MCP server is not ready"), shown.record().failureReason());
        assertEquals(1, service.coordinator().forgetProtection(catalog.state().sessions().get(0)).stream().count());
    }

    // ---- AC-12: isolated shutdown, flush drained, nothing admitted afterwards ----

    @Test
    void aFailingStepDoesNotSkipTheFlushAndNothingIsAdmittedAfterClose() throws Exception {
        EditorAskService service = service(base, null);
        Path root = Files.createDirectories(base.resolve("checkout"));
        Result result = service.coordinator().submit(
                EditorAskComposerPanel.tabSubmission(root.toString(), marked(root), "kept?")).get(10, TimeUnit.SECONDS);
        assertTrue(result.accepted());
        AtomicBoolean laterStepRan = new AtomicBoolean();
        service.beforeClose(() -> {
            throw new IllegalStateException("boom");
        });
        service.beforeClose(() -> laterStepRan.set(true));
        service.close();
        service.close();   // idempotent

        assertTrue(laterStepRan.get(), "a failing step must not skip the next");
        try (var partitions = Files.list(base.resolve("editor-asks"))) {
            assertEquals(1, partitions.filter(p -> p.toString().endsWith(".json")).count(),
                    "the partition is on disk after close");
        }
        Result late = service.coordinator().submit(
                EditorAskComposerPanel.tabSubmission(root.toString(), marked(root), "late")).get(10, TimeUnit.SECONDS);
        assertFalse(late.accepted(), "closed intake admits nothing");
    }

    // ---- AC-2: Forget protection explains itself; an ordinary row is untouched ----

    @Test
    void forgetIsRefusedForARetainedQaRowWithAnExplanation() throws Exception {
        EditorAskService service = service(base, null);
        Path root = Files.createDirectories(base.resolve("checkout"));
        ManagedAgentSession row = marked(root);
        assertEquals(Optional.empty(), service.forgetProtection(row), "no history yet: nothing to strand");
        assertTrue(service.coordinator().submit(EditorAskComposerPanel.tabSubmission(root.toString(), row, "q"))
                .get(10, TimeUnit.SECONDS).accepted());
        String why = service.forgetProtection(row).orElseThrow();
        assertTrue(why.contains("Q&A session") && why.contains("strand"), why);
        ManagedAgentSession ordinary = row.toBuilder().purpose(SessionPurpose.ORDINARY).build();
        assertEquals(Optional.empty(), service.forgetProtection(ordinary));
    }

    // ---- integration: Forget reaches the coordinator's rule, protection follows the lane, events are fenced ----

    @Test
    void thePolicyEventsCarryTheForgetRuleToTheCoordinator() throws Exception {
        EditorAskService service = service(base, null);
        Path root = Files.createDirectories(base.resolve("checkout"));
        ManagedAgentSession row = marked(root);
        assertEquals(Optional.empty(), service.policyEvents().forgetProtection(row));
        assertTrue(service.coordinator().submit(EditorAskComposerPanel.tabSubmission(root.toString(), row, "q"))
                .get(10, TimeUnit.SECONDS).accepted());
        // this is the hook DrydockSessionService.deleteSession reaches through EditorAskLaunchPolicy.forgetRefusal
        assertTrue(service.policyEvents().forgetProtection(row).orElseThrow().contains("Q&A session"));
    }

    @Test
    void anAnsweredTurnIsProtectedFromEvictionUntilItsAgentFinishes() {
        EditorAskService service = service(base, null);
        String root = base.toString();
        String id = "editor-00000000-0000-0000-0000-000000000001";
        assertFalse(service.protectedFromEviction(id));
        service.progressEvents().progress(root, id, Status.ANSWERED, "Answered", null);
        assertTrue(service.protectedFromEviction(id), "answered but still working: the store must not evict it");
        service.policyEvents().observed(root, new EditorAskLane.Event("x", 1, EditorAskLane.EventKind.SUBMITTED, id, 2));
        assertTrue(service.protectedFromEviction(id), "a submit is not the end of the turn");
        service.policyEvents().observed(root, new EditorAskLane.Event("x", 1, EditorAskLane.EventKind.END, id, 4));
        assertFalse(service.protectedFromEviction(id), "the turn's end releases the protection");

        service.progressEvents().progress(root, id, Status.ANSWERED, "Answered", null);
        service.policyEvents().executionEnded(root, "x", 1);
        assertFalse(service.protectedFromEviction(id), "the execution's end releases every protection");

        service.progressEvents().progress(root, id, Status.ANSWERED, "Answered", null);
        service.progressEvents().progress(root, id, Status.NO_ANSWER, "No answer", "ended");
        assertFalse(service.protectedFromEviction(id), "a settled turn is plain history");
    }

    @Test
    void oneCheckoutsExecutionEndKeepsAnotherCheckoutsProtection() {
        EditorAskService service = service(base, null);
        String rootA = base.toString();
        String rootB = base.resolve("other").toString();
        String idA = "editor-00000000-0000-0000-0000-00000000000a";
        String idB = "editor-00000000-0000-0000-0000-00000000000b";
        service.progressEvents().progress(rootA, idA, Status.ANSWERED, "Answered", null);
        service.progressEvents().progress(rootB, idB, Status.ANSWERED, "Answered", null);
        service.policyEvents().executionEnded(rootA, "x", 1);
        assertFalse(service.protectedFromEviction(idA));
        assertTrue(service.protectedFromEviction(idB), "B's turn is still being worked on");
    }
}
