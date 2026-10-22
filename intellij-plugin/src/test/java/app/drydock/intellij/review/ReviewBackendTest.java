package app.drydock.intellij.review;

import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.agent.api.McpAccess;
import app.drydock.agent.api.McpDelivery;
import app.drydock.domain.ManagedSessionId;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
