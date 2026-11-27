package app.drydock.mcp;

import app.drydock.domain.ManagedSessionId;
import app.drydock.editorqa.EditorAskRecord;
import app.drydock.mcp.McpSessionRegistry.Spawn;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static app.drydock.mcp.JsonPeek.array;
import static app.drydock.mcp.JsonPeek.field;
import static app.drydock.mcp.JsonPeek.str;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Local {@code editor_ask_answer}: the delegate seam, its HTTP surface, and what must not serve it. */
class EditorAskAnswerToolTest {

    private static final String TOOL = EditorAskAnswerDelegate.TOOL_NAME;
    private static final String TOKEN_HEADER = "X-Drydock-Session-Token";

    /** A one-answer-per-ID model of the T10 coordinator: owner, generation and state per ID. */
    private static final class FakeDelegate implements EditorAskAnswerDelegate {
        record Ask(ManagedSessionId owner, long generation, String state) { }

        final Map<String, Ask> asks = new HashMap<>();
        final Map<String, String> answers = new HashMap<>();
        final AtomicInteger commits = new AtomicInteger();
        McpSessionRegistry registry;

        @Override
        public synchronized boolean commit(ManagedSessionId caller, long generation, String id, String answer) {
            commits.incrementAndGet();
            Ask ask = asks.get(id);
            if (ask == null || !ask.owner().equals(caller) || ask.generation() != generation
                    || !"AWAITING".equals(ask.state()) || !registry.isCurrent(caller, generation)) {
                return false;
            }
            answers.put(id, answer);
            asks.put(id, new Ask(ask.owner(), ask.generation(), "ANSWERED"));
            return true;
        }
    }

    private McpSessionRegistry registry;
    private FakeMcpSessionContext context;
    private FakeDelegate delegate;
    private McpToolRouter router;
    private McpServer server;
    private HttpClient client;
    private final ManagedSessionId owner = ManagedSessionId.newId();
    private String token;
    private long generation;

    @BeforeEach
    void setUp() throws Exception {
        registry = new McpSessionRegistry();
        context = new FakeMcpSessionContext();
        context.repositoryRoot = Optional.of(Path.of("/repos/drydock"));
        context.worktreePath = Optional.of(Path.of("/repos/drydock"));
        delegate = new FakeDelegate();
        delegate.registry = registry;
        router = new McpToolRouter(context, registry);
        router.serveEditorAskAnswer(delegate);
        server = new McpServer(registry, router);
        server.start();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        token = registry.rotateAndMint(owner, Spawn.FORBIDDEN);
        generation = registry.authenticate(token).orElseThrow().generation();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private String pending() {
        String id = EditorAskRecord.newId();
        delegate.asks.put(id, new FakeDelegate.Ask(owner, generation, "AWAITING"));
        return id;
    }

    private HttpResponse<String> post(String body, String presentedToken) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(server.endpointUrl()))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header(TOKEN_HEADER, presentedToken)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String callBody(String id, String answer) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" + TOOL
                + "\",\"arguments\":{\"questionId\":" + JsonWriter.write(new JsonValue.JsonString(id))
                + ",\"answer\":" + JsonWriter.write(new JsonValue.JsonString(answer)) + "}}}";
    }

    private JsonValue result(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode());
        return field(JsonParser.parse(response.body()), "result");
    }

    private boolean isError(String id, String answer) throws Exception {
        return JsonPeek.bool(result(post(callBody(id, answer), token)), "isError");
    }

    private static Set<String> names(List<JsonValue> descriptors) {
        return descriptors.stream().map(d -> str(d, "name")).collect(Collectors.toSet());
    }

    // ---- served surface --------------------------------------------------

    @Test
    void initializeListAndCallRunOverLocalHttpAndCommitOnce() throws Exception {
        String init = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";
        assertEquals(200, post(init, token).statusCode());
        String list = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}";
        assertTrue(names(array(result(post(list, token)), "tools")).contains(TOOL));

        String id = pending();
        assertFalse(isError(id, "the full answer\nwith lines"));
        assertEquals("the full answer\nwith lines", delegate.answers.get(id));
        assertTrue(isError(id, "a second answer"), "duplicate refused");
        assertEquals("the full answer\nwith lines", delegate.answers.get(id), "accepted answer immutable");
    }

    @Test
    void foreignSessionAndStaleCredentialsMutateNothing() throws Exception {
        String id = pending();
        ManagedSessionId other = ManagedSessionId.newId();
        String otherToken = registry.rotateAndMint(other, Spawn.FORBIDDEN);
        assertTrue(JsonPeek.bool(result(post(callBody(id, "x"), otherToken)), "isError"));
        assertTrue(delegate.answers.isEmpty());

        String rotated = registry.rotateAndMint(owner, Spawn.FORBIDDEN);
        assertEquals(401, post(callBody(id, "x"), token).statusCode(), "old token is dead");
        assertTrue(delegate.answers.isEmpty());
        assertTrue(isErrorWith(rotated, id), "the new generation does not own the old attempt");
        assertTrue(delegate.answers.isEmpty());
    }

    private boolean isErrorWith(String presentedToken, String id) throws Exception {
        return JsonPeek.bool(result(post(callBody(id, "x"), presentedToken)), "isError");
    }

    @Test
    void inFlightCallFrozenAtAnOldGenerationIsRefusedAfterRotation() throws Exception {
        String id = pending();
        long frozen = generation;
        registry.rotateAndMint(owner, Spawn.FORBIDDEN);
        McpToolException refused = assertThrows(McpToolException.class,
                () -> router.call(owner, frozen, TOOL, JsonPeek.args("questionId", id, "answer", "late")));
        assertEquals(EditorAskAnswerDelegate.REFUSAL, refused.getMessage());
        assertTrue(delegate.answers.isEmpty());
    }

    @Test
    void unknownReviewQueuedExpiredAndMalformedIdsAreRefusedWithoutEcho() throws Exception {
        String queued = EditorAskRecord.newId();
        delegate.asks.put(queued, new FakeDelegate.Ask(owner, generation, "QUEUED"));
        String expired = EditorAskRecord.newId();
        delegate.asks.put(expired, new FakeDelegate.Ask(owner, generation, "EXPIRED"));
        for (String id : List.of(EditorAskRecord.newId(), "q_review_1", queued, expired,
                "editor-" + EditorAskRecord.newId().substring(7).toUpperCase(), "", "../x")) {
            HttpResponse<String> response = post(callBody(id, "secret answer text"), token);
            assertTrue(JsonPeek.bool(result(response), "isError"), id);
            assertFalse(response.body().contains("secret answer text"));
            if (!id.isEmpty()) {
                assertFalse(response.body().contains(id), "refusal must not echo the ID");
            }
        }
        assertTrue(delegate.answers.isEmpty());
    }

    @Test
    void invalidOversizeAndBlankAnswersNeverReachTheDelegate() throws Exception {
        String id = pending();
        assertTrue(isError(id, "   \n "));
        assertTrue(isError(id, ""));
        assertTrue(isError(id, "x".repeat(8001)));
        assertTrue(isError(id, "bad\u001b[31m"));
        String lone = callBody(id, "x").replace("\"answer\":\"x\"", "\"answer\":\"lone \\ud800 surrogate\"");
        assertTrue(lone.contains("\\ud800"));
        JsonValue loneReply = JsonParser.parse(post(lone, token).body());
        assertTrue(field(loneReply, "error") != null || JsonPeek.bool(field(loneReply, "result"), "isError"),
                "an escaped unpaired surrogate is refused at the parser or the validator");
        assertTrue(isError(id, "bidi \u202e text"));
        String missing = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" + TOOL
                + "\",\"arguments\":{\"questionId\":\"" + id + "\"}}}";
        assertTrue(JsonPeek.bool(result(post(missing, token)), "isError"));
        assertEquals(0, delegate.commits.get(), "validation happens before any delegate call");
        assertFalse(isError(id, "x".repeat(8000)), "the bound itself is accepted");
    }

    // ---- bounded Q&A reads -----------------------------------------------

    @Test
    void qaCredentialBodyAndDepthAreBoundedButOrdinaryRequestsAreNot() throws Exception {
        String id = pending();
        String huge = callBody(id, "x").replace("\"jsonrpc\"", "\"pad\":\"" + "p".repeat(300 * 1024)
                + "\",\"jsonrpc\"");
        assertEquals(413, post(huge, token).statusCode());
        String deep = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":"
                + "[".repeat(40) + "]".repeat(40) + "}";
        assertEquals(-32700, JsonPeek.num(field(JsonParser.parse(post(deep, token).body()), "error"), "code"));

        ManagedSessionId ordinary = ManagedSessionId.newId();
        String ordinaryToken = registry.mint(ordinary, Spawn.ALLOWED);
        assertEquals(200, post(huge, ordinaryToken).statusCode(), "ordinary local budget unchanged");
        assertNull(field(JsonParser.parse(post(deep, ordinaryToken).body()), "error"));
    }

    // ---- logging ---------------------------------------------------------

    @Test
    void successAndErrorLeaveNoActivityRowOrExcerpt() throws Exception {
        String id = pending();
        isError(id, "needle-answer");
        isError(id, "needle-answer again");
        isError(EditorAskRecord.newId(), "needle-answer");
        assertTrue(server.activityLog().entries().stream().noneMatch(e -> e.tool().equals(TOOL)
                || e.detail().contains("needle")), "no row, no excerpt");
    }

    // ---- surfaces that must not carry the tool ---------------------------

    @Test
    void absentFromLegacyNoDelegateAndRemoteSurfaces() throws Exception {
        assertFalse(McpToolRouter.REVIEW_TOOLS.contains(TOOL));
        assertFalse(McpToolRouter.REVIEW_TOOLS_AND_RENAME.contains(TOOL));
        assertFalse(McpToolRouter.TASK_TOOLS.contains(TOOL));
        assertFalse(McpToolRouter.BABYSIT_TOOLS.contains(TOOL));
        for (McpToolRouter plain : List.of(new McpToolRouter(context, registry),
                new McpToolRouter(context, registry, McpToolRouter.REVIEW_TOOLS_AND_RENAME))) {
            assertFalse(plain.serves(TOOL));
            assertFalse(names(plain.toolDescriptors()).contains(TOOL));
            assertThrows(McpToolException.class,
                    () -> plain.call(owner, generation, TOOL, JsonPeek.args("questionId", "x", "answer", "y")));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new McpToolRouter(context, registry, Set.of(TOOL)));
        assertTrue(router.serves(TOOL));
        assertEquals(1, names(router.toolDescriptors()).stream().filter(TOOL::equals).count());
        assertThrows(IllegalStateException.class, () -> router.serveEditorAskAnswer(delegate));
    }

    @Test
    void reviewAskAnswerIsUnchangedAndStillScoped() throws Exception {
        assertTrue(names(router.toolDescriptors()).contains("review_ask_answer"));
        McpToolException e = assertThrows(McpToolException.class, () -> router.call(owner, generation,
                "review_ask_answer", JsonPeek.args("questionId", "q_1", "answer", "a")));
        assertFalse(e.getMessage().contains(EditorAskAnswerDelegate.REFUSAL));
        assertEquals(0, delegate.commits.get());
    }

    // ---- registry ---------------------------------------------------------

    @Test
    void rotateAndMintReplacesTheTokenAndAdvancesTheGeneration() {
        String next = registry.rotateAndMint(owner, Spawn.FORBIDDEN);
        assertTrue(registry.resolve(token).isEmpty());
        long nextGeneration = registry.authenticate(next).orElseThrow().generation();
        assertTrue(nextGeneration > generation);
        assertFalse(registry.isCurrent(owner, generation));
        assertTrue(registry.isCurrent(owner, nextGeneration));
        assertTrue(registry.authenticate(next).orElseThrow().boundedReads());
        assertFalse(registry.authenticate(registry.mint(ManagedSessionId.newId(), Spawn.ALLOWED))
                .orElseThrow().boundedReads());
        registry.revoke(owner);
        assertFalse(registry.isCurrent(owner, nextGeneration));
    }
}
