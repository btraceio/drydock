package app.drydock.intellij.review;

import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.agent.api.McpAccess;
import app.drydock.agent.api.McpDelivery;
import app.drydock.agent.api.AgentKind;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.PrLink;
import app.drydock.domain.PrState;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.RepositorySettings;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.mcp.McpSessionContext.RenameKind;
import app.drydock.mcp.McpSessionContext.RenameOutcome;
import app.drydock.mcp.McpToolRouter;
import app.drydock.mcp.RemoteAuthorizationPermit;
import app.drydock.mcp.RemoteMcpTransportPolicy;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.ReviewAnnotation.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewBackendTest {

    private static final String TOKEN_HEADER = "X-Drydock-Session-Token";
    private static final String TOOLS_LIST = """
            {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""";

    @TempDir
    Path base;

    private ExecutorService executor;
    private ReviewBackend backend;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        executor = Executors.newSingleThreadExecutor();
        AgentRegistry registry = new AgentRegistry(List.of(),
                new AgentContext(base, base.resolve("activity"), executor));
        backend = new ReviewBackend(base, List::of, List::of, registry);
    }

    @AfterEach
    void tearDown() {
        backend.close();
        executor.shutdownNow();
    }

    private HttpResponse<String> post(String url, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(TOOLS_LIST));
        if (token != null) {
            request.header(TOKEN_HEADER, token);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void startBindsLoopbackAndWritesNothing() throws Exception {
        backend.start();

        assertTrue(backend.ready());
        assertTrue(backend.boundAddress().getAddress().isLoopbackAddress());
        try (Stream<Path> files = Files.list(base)) {
            assertEquals(List.of(), files.toList());
        }
    }

    @Test
    void startPurgesStaleConfigFiles() throws Exception {
        Path stale = base.resolve("mcp").resolve("stale.json");
        Files.createDirectories(stale.getParent());
        Files.writeString(stale, "{}");

        backend.start();

        assertFalse(Files.exists(stale));
    }

    @Test
    void accessForWritesTheFileMatchingTheDelivery() {
        backend.start();
        ManagedSessionId id = ManagedSessionId.newId();

        McpAccess config = backend.accessFor(id, McpDelivery.CONFIG_FILE).orElseThrow();
        assertEquals(base.resolve("mcp").resolve(id + ".json"), config.credentialFile().orElseThrow());

        ManagedSessionId other = ManagedSessionId.newId();
        McpAccess commandLine = backend.accessFor(other, McpDelivery.COMMAND_LINE).orElseThrow();
        assertEquals(base.resolve("mcp").resolve(other + ".token"), commandLine.credentialFile().orElseThrow());

        assertTrue(backend.accessFor(ManagedSessionId.newId(), McpDelivery.NONE).isEmpty());
    }

    @Test
    void aBackendThatIsNotStartedServesNothing() {
        assertFalse(backend.ready());
        assertTrue(backend.accessFor(ManagedSessionId.newId(), McpDelivery.CONFIG_FILE).isEmpty());
    }

    @Test
    void releaseRevokesTheTokenAndDeletesTheFiles() throws Exception {
        backend.start();
        ManagedSessionId id = ManagedSessionId.newId();
        McpAccess access = backend.accessFor(id, McpDelivery.CONFIG_FILE).orElseThrow();
        assertEquals(200, post(access.endpointUrl(), access.token()).statusCode());

        backend.release(id);

        assertFalse(Files.exists(base.resolve("mcp").resolve(id + ".json")));
        assertFalse(Files.exists(base.resolve("mcp").resolve(id + ".token")));
        assertEquals(401, post(access.endpointUrl(), access.token()).statusCode());
    }

    @Test
    void holdsAccessFollowsTheMintedTokenAndTheBackendLifecycle() {
        ManagedSessionId id = ManagedSessionId.newId();
        assertFalse(backend.holdsAccess(id));

        backend.start();
        assertFalse(backend.holdsAccess(id));
        backend.accessFor(id, McpDelivery.CONFIG_FILE).orElseThrow();
        assertTrue(backend.holdsAccess(id));

        backend.release(id);
        assertFalse(backend.holdsAccess(id));

        ManagedSessionId none = ManagedSessionId.newId();
        assertTrue(backend.accessFor(none, McpDelivery.NONE).isEmpty());
        assertFalse(backend.holdsAccess(none));

        ManagedSessionId open = ManagedSessionId.newId();
        backend.accessFor(open, McpDelivery.CONFIG_FILE).orElseThrow();
        backend.close();
        assertFalse(backend.holdsAccess(open));
    }

    @Test
    void aFailedCredentialWriteRevokesTheTokenAndReturnsEmpty() throws Exception {
        backend.start();
        // An IOException: mcp/ is a regular file, so the directory cannot be created.
        Files.writeString(base.resolve("mcp"), "occupied");
        ManagedSessionId ioFailure = ManagedSessionId.newId();

        assertTrue(backend.accessFor(ioFailure, McpDelivery.CONFIG_FILE).isEmpty());
        assertFalse(backend.holdsAccess(ioFailure));

        // A RuntimeException from the writer after the token was minted.
        backend.credentialWriter = (id, delivery, endpointUrl, token) -> {
            throw new IllegalStateException("boom");
        };
        ManagedSessionId runtimeFailure = ManagedSessionId.newId();

        assertTrue(backend.accessFor(runtimeFailure, McpDelivery.COMMAND_LINE).isEmpty());
        assertFalse(backend.holdsAccess(runtimeFailure));
    }

    @Test
    void releaseOfAnUnknownSessionIsSafe() {
        backend.release(ManagedSessionId.newId());
    }

    @Test
    void closeEachContinuesPastAFailingStep() {
        List<String> ran = new ArrayList<>();
        AutoCloseable throwing = () -> {
            throw new IOException("boom");
        };

        ReviewBackend.closeEach(List.of(() -> ran.add("ok1"), throwing, () -> ran.add("ok2")));

        assertEquals(List.of("ok1", "ok2"), ran);
    }

    @Test
    void closeStopsTheServer() throws Exception {
        backend.start();
        McpAccess access = backend.accessFor(ManagedSessionId.newId(), McpDelivery.CONFIG_FILE).orElseThrow();

        backend.close();

        assertThrows(IOException.class, () -> post(access.endpointUrl(), access.token()));
    }

    @Test
    void aSecondCloseIsANoOp() {
        backend.start();

        backend.close();

        assertDoesNotThrow(backend::close);
    }

    @Test
    void afterCloseTheBackendServesNothingAndStaysNotReady() throws Exception {
        backend.start();
        backend.close();
        assertFalse(backend.ready());

        backend.start();

        assertFalse(backend.ready());
        assertTrue(backend.accessFor(ManagedSessionId.newId(), McpDelivery.CONFIG_FILE).isEmpty());
        assertTrue(backend.accessFor(ManagedSessionId.newId(), McpDelivery.COMMAND_LINE).isEmpty());
        Path mcp = base.resolve("mcp");
        if (Files.exists(mcp)) {
            try (Stream<Path> files = Files.list(mcp)) {
                assertEquals(List.of(), files.toList());
            }
        }
    }

    @Test
    void closeFlushesTheAnnotationStoreToDisk() throws Exception {
        ReviewAnnotation finding = ReviewAnnotation.human("scope-1", "A.java", "n1", "n1",
                new Message("You", Instant.parse("2026-01-01T00:00:00Z"), "flush-marker-text"));
        backend.annotationStore().upsert(finding);

        backend.close();

        Path annotations = base.resolve("annotations.json");
        assertTrue(Files.exists(annotations));
        assertTrue(Files.readString(annotations).contains("flush-marker-text"));
    }

    // ---- remote composition (T55) ------------------------------------------------

    private static final class Fence implements RemoteAuthorizationPermit.Fence {
        private final Object lock = new Object();

        @Override
        public Object lock() {
            return lock;
        }

        @Override
        public boolean revoked() {
            return false;
        }
    }

    private static ManagedAgentSession runningSession(Path root) {
        Instant now = Instant.now();
        return new ManagedAgentSession(ManagedSessionId.newId(), REPO.id(), "example session",
                new AgentBinding(AgentKind.CLAUDE, Optional.empty(), Optional.empty()),
                new SessionWorkspace(root, Optional.empty(), true), SessionStatus.RUNNING, now, now,
                Optional.empty(), PrLink.of(PrState.NONE, Optional.empty()), false, Optional.empty());
    }

    private static final Repository REPO = new Repository(RepositoryId.newId(), Path.of("/"), "example",
            Instant.now(), Instant.now(), RepositorySettings.DEFAULT);

    private HttpResponse<String> call(String url, String token, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json").header(TOKEN_HEADER, token)
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static Set<String> toolNames(String listing) {
        Set<String> names = new TreeSet<>();
        Matcher matcher = Pattern.compile("\"name\":\\s*\"([a-z_]+)\"").matcher(listing);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private RemoteMcpComposition remote(ReviewBackend owner, List<ManagedAgentSession> sessions,
                                        Map<ManagedSessionId, RemoteAuthorizationPermit> permits,
                                        RemoteMcpComposition.RemoteRenamer renamer) {
        AgentRegistry registry = new AgentRegistry(List.of(), new AgentContext(base, base.resolve("activity"), executor));
        return owner.remoteComposition(() -> sessions, () -> List.of(REPO), registry, renamer,
                RemoteMcpTransportPolicy.REMOTE,
                new RemoteAuthorizationPermit.Seams(id -> Optional.ofNullable(permits.get(id)), null));
    }

    private static RemoteAuthorizationPermit permitFor(ManagedAgentSession session) {
        return new RemoteAuthorizationPermit(session.id(), 1, new Fence());
    }

    @Test
    void theRemoteEndpointServesOnlyReviewAndRenameAndTheLocalSurfaceIsUnchanged() throws Exception {
        ManagedAgentSession session = runningSession(base);
        AtomicInteger renames = new AtomicInteger();
        try (RemoteMcpComposition remote = remote(backend, List.of(session),
                Map.of(session.id(), permitFor(session)), (id, title, permit) -> {
                    renames.incrementAndGet();
                    return CompletableFuture.completedFuture(new RenameOutcome(RenameKind.RENAMED, title));
                })) {
            remote.server().start();
            String token = remote.mint(session.id());
            String url = remote.server().endpointUrl();

            HttpResponse<String> listing = call(url, token, TOOLS_LIST);
            assertEquals(McpToolRouter.REVIEW_TOOLS_AND_RENAME, toolNames(listing.body()),
                    listing.statusCode() + " " + listing.body());
            String initialize = call(url, token,
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\",\"params\":{}}").body();
            assertFalse(initialize.contains("task_"), initialize);
            List<String> forbidden = new ArrayList<>(McpToolRouter.TASK_TOOLS);
            forbidden.addAll(List.of("worktree_create", "session_start"));
            for (String tool : forbidden) {
                assertFalse(remote.serves(tool), tool);
                String refused = call(url, token, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"" + tool + "\",\"arguments\":{}}}").body();
                assertTrue(refused.contains("\"error\"") || refused.contains("isError"), refused);
            }
            assertEquals(0, renames.get());

            backend.start();
            ManagedSessionId local = ManagedSessionId.newId();
            McpAccess access = backend.accessFor(local, McpDelivery.CONFIG_FILE).orElseThrow();
            assertEquals(McpToolRouter.REVIEW_TOOLS_AND_RENAME,
                    toolNames(post(access.endpointUrl(), access.token()).body()));
        }
    }

    @Test
    void theRemoteEndpointSharesTheBackendsStores() {
        try (RemoteMcpComposition remote = remote(backend, List.of(), Map.of(),
                (id, title, permit) -> CompletableFuture.failedFuture(new IllegalStateException()))) {
            assertSame(backend.annotationStore(), remote.annotationStore());
            assertSame(backend.tourStore(), remote.tourStore());
            assertSame(backend.pendingQuestions(), remote.pendingQuestions());
            assertSame(backend.selectionAsks(), remote.selectionAsks());
            assertSame(backend.scopeRegistry(), remote.scopeRegistry());
        }
    }

    @Test
    void theRenameAdapterPassesEachRequestsDispatchTimePermitAndNeverTheLocalRenamer() throws Exception {
        AtomicInteger localRenames = new AtomicInteger();
        Path other = Files.createDirectories(base.resolve("other"));
        AgentRegistry registry = new AgentRegistry(List.of(), new AgentContext(other, other.resolve("activity"), executor));
        ManagedAgentSession first = runningSession(base);
        ManagedAgentSession second = runningSession(base);
        Map<ManagedSessionId, RemoteAuthorizationPermit> permits = Map.of(
                first.id(), permitFor(first), second.id(), permitFor(second));
        Map<ManagedSessionId, RemoteAuthorizationPermit> received = new ConcurrentHashMap<>();
        Map<ManagedSessionId, Boolean> threadLocalAfterHop = new ConcurrentHashMap<>();
        CountDownLatch bothArrived = new CountDownLatch(2);
        ExecutorService hop = Executors.newCachedThreadPool();
        try (ReviewBackend owner = new ReviewBackend(other, List::of, List::of, registry,
                (id, title) -> {
                    localRenames.incrementAndGet();
                    return CompletableFuture.failedFuture(new IllegalStateException("local renamer"));
                });
             RemoteMcpComposition remote = remote(owner, List.of(first, second), permits, (id, title, permit) -> {
                 received.put(id, permit);
                 bothArrived.countDown();
                 return CompletableFuture.supplyAsync(() -> {
                     await(bothArrived);
                     threadLocalAfterHop.put(id, RemoteAuthorizationPermit.Bound.current().isPresent());
                     return new RenameOutcome(RenameKind.RENAMED, title);
                 }, hop);
             })) {
            remote.server().start();
            String url = remote.server().endpointUrl();
            String body = "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"session_rename\",\"arguments\":{\"title\":\"Review plan\"}}}";
            String firstToken = remote.mint(first.id());
            String secondToken = remote.mint(second.id());
            CompletableFuture<HttpResponse<String>> a = CompletableFuture.supplyAsync(() -> unchecked(url, firstToken, body));
            CompletableFuture<HttpResponse<String>> b = CompletableFuture.supplyAsync(() -> unchecked(url, secondToken, body));
            assertTrue(a.get(15, TimeUnit.SECONDS).body().contains("renamed"));
            assertTrue(b.get(15, TimeUnit.SECONDS).body().contains("renamed"));
        } finally {
            hop.shutdownNow();
        }
        assertSame(permits.get(first.id()), received.get(first.id()));
        assertSame(permits.get(second.id()), received.get(second.id()));
        assertNotSame(received.get(first.id()), received.get(second.id()));
        assertEquals(Map.of(first.id(), false, second.id(), false), threadLocalAfterHop);
        assertEquals(0, localRenames.get());
    }

    @Test
    void theRenameAdapterFailsClosedWithoutABoundPermitForTheSession() {
        AtomicInteger calls = new AtomicInteger();
        var adapter = RemoteMcpComposition.renameAdapter((id, title, permit) -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(new RenameOutcome(RenameKind.RENAMED, title));
        });
        ManagedAgentSession session = runningSession(base);
        assertTrue(adapter.apply(session.id(), "t").isCompletedExceptionally());
        try (var bound = RemoteAuthorizationPermit.Bound.bind(permitFor(runningSession(base)))) {
            assertTrue(adapter.apply(session.id(), "t").isCompletedExceptionally());
        }
        assertEquals(0, calls.get());
    }

    private HttpResponse<String> unchecked(String url, String token, String body) {
        try {
            return call(url, token, body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
