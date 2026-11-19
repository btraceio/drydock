package app.drydock.intellij.review;

import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.agent.api.McpAccess;
import app.drydock.agent.api.McpDelivery;
import app.drydock.babysit.BabysitPairing;
import app.drydock.domain.ManagedSessionId;
import app.drydock.git.GhCiRerunService;
import app.drydock.mcp.BabysitToolContext;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The babysitting-enabled backend over HTTP: it lists exactly review, rename
 * and the three babysitting tools when built without a task delegate, and a
 * babysitting call is routed (here refused by the live-session check, since
 * this backend knows no sessions) rather than answered "Unknown tool".
 */
class ReviewBackendBabysitToolsTest {

    @TempDir
    Path base;

    private ExecutorService executor;
    private ReviewBackend backend;
    private final HttpClient client = HttpClient.newHttpClient();

    /** Never reached through the calls below; any reach fails loudly. */
    private static final BabysitToolContext UNREACHED = new BabysitToolContext() {
        @Override
        public CompletableFuture<PullRequestLookup> openPullRequestOf(ManagedSessionId caller) {
            throw new AssertionError("not reached");
        }

        @Override
        public CompletableFuture<StartOutcome> startBabysitter(ManagedSessionId watched, int prNumber,
                                                               String prBranch) {
            throw new AssertionError("not reached");
        }

        @Override
        public CompletableFuture<Optional<BabysitPairing>> activePairingOf(ManagedSessionId babysitter) {
            throw new AssertionError("not reached");
        }

        @Override
        public CompletableFuture<GhCiRerunService.Outcome> rerunFailedJobs(BabysitPairing pairing, long runId) {
            throw new AssertionError("not reached");
        }

        @Override
        public CompletableFuture<GhCiRerunService.RunRead> readRun(BabysitPairing pairing, long runId) {
            throw new AssertionError("not reached");
        }

        @Override
        public CompletableFuture<PullRequestHead> pullRequestHeadOf(BabysitPairing pairing) {
            throw new AssertionError("not reached");
        }

        @Override
        public CompletableFuture<EscalateOutcome> escalate(ManagedSessionId babysitter, String message) {
            throw new AssertionError("not reached");
        }
    };

    @BeforeEach
    void setUp() {
        executor = Executors.newSingleThreadExecutor();
        AgentRegistry registry = new AgentRegistry(List.of(),
                new AgentContext(base, base.resolve("activity"), executor));
        backend = new ReviewBackend(base, List::of, List::of, registry,
                (id, title) -> CompletableFuture.failedFuture(new UnsupportedOperationException()),
                null, UNREACHED);
        backend.start();
    }

    @AfterEach
    void tearDown() {
        backend.close();
        executor.shutdownNow();
    }

    private JsonValue rpc(McpAccess access, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(access.endpointUrl()))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-Drydock-Session-Token", access.token())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return ((JsonObject) JsonParser.parse(response.body())).get("result");
    }

    @Test
    void theBackendListsReviewRenameAndTheThreeBabysittingTools() throws Exception {
        McpAccess access = backend.accessFor(ManagedSessionId.newId(), McpDelivery.CONFIG_FILE).orElseThrow();

        JsonValue result = rpc(access, """
                {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""");

        Set<String> names = new HashSet<>();
        for (JsonValue tool : ((JsonArray) ((JsonObject) result).get("tools")).elements()) {
            names.add(((JsonString) ((JsonObject) tool).get("name")).value());
        }
        assertEquals(Set.of("review_comments", "review_reply", "review_scope", "review_tour",
                "review_check", "review_finding", "review_answer", "review_ask_answer",
                "review_state", "review_recheck", "session_rename",
                "babysit_start", "ci_rerun", "babysit_escalate"), names);
        assertFalse(backend.servesTaskTools(), "no task delegate, no task tools");
    }

    @Test
    void aBabysittingCallIsRoutedNotUnknown() throws Exception {
        McpAccess access = backend.accessFor(ManagedSessionId.newId(), McpDelivery.CONFIG_FILE).orElseThrow();

        JsonValue result = rpc(access, """
                {"jsonrpc":"2.0","id":2,"method":"tools/call",
                 "params":{"name":"ci_rerun","arguments":{"runId":5}}}""");

        String text = result.toString();
        assertTrue(text.contains("Session has ended"), text);
    }
}
