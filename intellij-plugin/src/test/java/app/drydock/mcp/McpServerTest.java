package app.drydock.mcp;

import app.drydock.domain.ManagedSessionId;
import app.drydock.mcp.McpSessionRegistry.Spawn;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpServerTest {

    private static final String TOKEN_HEADER = "X-Drydock-Session-Token";

    private McpSessionRegistry registry;
    private FakeMcpSessionContext context;
    private McpServer server;
    private HttpClient client;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        registry = new McpSessionRegistry();
        context = new FakeMcpSessionContext();
        ManagedSessionId session = ManagedSessionId.newId();
        context.repositoryRoot = Optional.of(Path.of("/repos/drydock"));
        context.worktreePath = Optional.of(Path.of("/repos/drydock"));
        token = registry.mint(session, Spawn.ALLOWED);
        server = new McpServer(registry, new McpToolRouter(context, registry));
        server.start();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    private HttpResponse<String> post(String body, String presentedToken, String origin) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(server.endpointUrl()))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (presentedToken != null) {
            request.header(TOKEN_HEADER, presentedToken);
        }
        if (origin != null) {
            request.header("Origin", origin);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String body) throws Exception {
        return post(body, token, null);
    }

    /**
     * Sends a handcrafted request over a raw socket, because {@code HttpClient}
     * refuses to set {@code Host} -- it is a restricted header. Returns the
     * whole response, status line included.
     */
    private String rawPost(String hostHeader, String tokenHeader, String body) throws Exception {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
            String request = "POST /mcp HTTP/1.1\r\n"
                    + "Host: " + hostHeader + "\r\n"
                    + (tokenHeader == null ? "" : TOKEN_HEADER + ": " + tokenHeader + "\r\n")
                    + "Content-Type: application/json\r\n"
                    + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n"
                    + "Connection: close\r\n\r\n"
                    + body;
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void bindsOnLoopbackOnly() throws Exception {
        // Asserting on endpointUrl() would prove nothing: that string is built
        // from a literal, so it reads "127.0.0.1" even if start() bound
        // 0.0.0.0. Ask the socket what it is actually bound to.
        assertTrue(server.boundAddress().getAddress().isLoopbackAddress(),
                "must not be reachable off-host: " + server.boundAddress());
        assertTrue(server.port() > 0);
    }

    @Test
    void aForeignHostHeaderIsRejected() throws Exception {
        String response = rawPost("evil.example.com:" + server.port(), token, """
                {"jsonrpc":"2.0","id":20,"method":"tools/list","params":{}}""");

        assertTrue(response.startsWith("HTTP/1.1 403"), response);
    }

    @Test
    void aLoopbackHostHeaderIsAccepted() throws Exception {
        String response = rawPost("127.0.0.1:" + server.port(), token, """
                {"jsonrpc":"2.0","id":21,"method":"tools/list","params":{}}""");

        assertTrue(response.startsWith("HTTP/1.1 200"), response);
    }

    @Test
    void initializeAdvertisesToolSupport() throws Exception {
        HttpResponse<String> response = post("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("serverInfo"), response.body());
        assertTrue(response.body().contains("drydock"), response.body());
        assertTrue(response.body().contains("protocolVersion"), response.body());
        assertTrue(response.body().contains("tools"), response.body());
    }

    /**
     * A tool description is read only once the agent is already hunting for a
     * tool; nothing prompts it to look for session_rename unbidden. The
     * client injects {@code instructions} into the session's system prompt,
     * so that -- not the tool descriptor -- is what has to name the tool.
     */
    @Test
    void initializeCarriesInstructionsThatNameTheRenameTool() throws Exception {
        HttpResponse<String> response = post("""
                {"jsonrpc":"2.0","id":27,"method":"initialize","params":{}}""");

        JsonValue result = JsonPeek.field(JsonParser.parse(response.body()), "result");
        String instructions = JsonPeek.str(result, "instructions");
        assertFalse(instructions.isBlank(), instructions);
        assertTrue(instructions.contains("session_rename"), instructions);
    }

    /** The text the full router sent before instructions depended on the served tools. */
    private static final String GOLDEN_INSTRUCTIONS = """
            Drydock hosts this session in a tab a human is watching. As soon as you know what \
            the work actually is, call session_rename with a short title naming the work -- not \
            the branch. Re-title it if the work turns out to be something else. Two refusals are \
            normal and both explain themselves: if the human has named the session, leave it \
            alone; if another session here already has that title, pick one that tells them apart.

            Keep session_handoff current as you work. This session may be handed to a different \
            agent at any moment, and that brief is all your successor gets -- write it for them, \
            not for the human, and especially record what you RULED OUT and why, which they \
            cannot recover from the code.\
            """;

    @Test
    void theFullRouterKeepsTodaysInstructions() {
        assertEquals(Optional.of(GOLDEN_INSTRUCTIONS),
                McpServer.instructionsFor(new McpToolRouter(context, registry)));
    }

    @Test
    void aReviewOnlyRouterSendsNoInstructions() throws Exception {
        McpToolRouter reviewOnly = new McpToolRouter(context, registry, McpToolRouter.REVIEW_TOOLS);
        assertEquals(Optional.empty(), McpServer.instructionsFor(reviewOnly));

        try (McpServer reviewServer = new McpServer(registry, reviewOnly)) {
            reviewServer.start();
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(reviewServer.endpointUrl()))
                            .timeout(Duration.ofSeconds(5))
                            .header("Content-Type", "application/json")
                            .header(TOKEN_HEADER, token)
                            .POST(HttpRequest.BodyPublishers.ofString("""
                                    {"jsonrpc":"2.0","id":28,"method":"initialize","params":{}}"""))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertFalse(response.body().contains("instructions"), response.body());
            assertFalse(response.body().contains("session_rename"), response.body());
            assertFalse(response.body().contains("session_handoff"), response.body());
        }
    }

    /**
     * The MCP spec has the server answer with the client's requested version
     * when it supports it -- and has the client disconnect when it does not
     * recognise what the server names. The handshake is the one part of this
     * feature no automated test can check against a real client, so it must not
     * also carry an avoidable inconsistency.
     */
    @Test
    void initializeEchoesTheClientsRequestedProtocolVersion() throws Exception {
        HttpResponse<String> response = post("""
                {"jsonrpc":"2.0","id":24,"method":"initialize",
                 "params":{"protocolVersion":"2025-06-18","capabilities":{}}}""");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"protocolVersion\": \"2025-06-18\""), response.body());
    }

    @Test
    void initializeFallsBackWhenTheRequestedVersionIsUnknown() throws Exception {
        HttpResponse<String> response = post("""
                {"jsonrpc":"2.0","id":25,"method":"initialize",
                 "params":{"protocolVersion":"1999-01-01","capabilities":{}}}""");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"protocolVersion\": \"2024-11-05\""), response.body());
    }

    /** No params at all (as the older test sends) still gets the fallback, never null. */
    @Test
    void initializeWithoutParamsStillNamesAVersion() throws Exception {
        HttpResponse<String> response = post("""
                {"jsonrpc":"2.0","id":26,"method":"initialize"}""");

        assertTrue(response.body().contains("\"protocolVersion\": \"2024-11-05\""), response.body());
    }

    @Test
    void initializedNotificationIsAcceptedWithoutAnError() throws Exception {
        // claude sends this immediately after initialize. Answering a
        // notification with an error object breaks the handshake, which would
        // leave every unit test green and the feature inert.
        HttpResponse<String> response = post("""
                {"jsonrpc":"2.0","method":"notifications/initialized"}""");

        assertEquals(204, response.statusCode());
        assertTrue(response.body().isEmpty(), "a notification gets no body: " + response.body());
    }

    @Test
    void anUnknownNotificationIsAlsoAcceptedSilently() throws Exception {
        HttpResponse<String> response = post("""
                {"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":1}}""");

        assertEquals(204, response.statusCode());
    }

    @Test
    void pingIsAnswered() throws Exception {
        HttpResponse<String> response = post("""
                {"jsonrpc":"2.0","id":2,"method":"ping"}""");

        assertEquals(200, response.statusCode());
        assertFalse(response.body().contains("error"), response.body());
    }

    @Test
    void toolsListReturnsEveryTool() throws Exception {
        HttpResponse<String> response = post("""
                {"jsonrpc":"2.0","id":3,"method":"tools/list","params":{}}""");

        assertEquals(200, response.statusCode());
        for (String tool : new String[] {"review_comments", "review_reply", "worktree_create",
                "session_start", "repos_list", "sessions_list"}) {
            assertTrue(response.body().contains(tool), "missing " + tool + " in: " + response.body());
        }
        // session_reclaim is bridge housekeeping, not a model-facing tool: it
        // must be callable on the wire but absent from tools/list, or the
        // model would learn it can rebind its own conversation id.
        assertFalse(response.body().contains("session_reclaim"),
                "session_reclaim must not be advertised: " + response.body());
    }

    @Test
    void sessionReclaimIsCallableButNotAdvertised() throws Exception {
        HttpResponse<String> response = post("""
                {"jsonrpc":"2.0","id":30,"method":"tools/call",
                 "params":{"name":"session_reclaim","arguments":{"agentSessionId":"new-conv"}}}""");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("reclaimed"), response.body());
        assertTrue(response.body().contains("new-conv"), response.body());
        assertEquals("new-conv", context.reclaimedTo());
    }

    @Test
    void aRefusedReclaimIsAnIsErrorResult() throws Exception {
        context.failReclaimWith(new McpToolException("Conversation new-conv is already open in another session."));

        HttpResponse<String> response = post("""
                {"jsonrpc":"2.0","id":31,"method":"tools/call",
                 "params":{"name":"session_reclaim","arguments":{"agentSessionId":"new-conv"}}}""");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"isError\": true"), response.body());
        assertTrue(response.body().contains("already open"), response.body());
    }

    @Test
    void toolsCallReturnsToolOutput() throws Exception {
        context.repositories.add(new McpSessionContext.RepoSummary("drydock", Path.of("/repos/drydock"),
                Optional.of("feat/mcp"), Optional.of(false), Optional.of(0), Optional.of(0), false));

        HttpResponse<String> response = post("""
                {"jsonrpc":"2.0","id":4,"method":"tools/call",
                 "params":{"name":"repos_list","arguments":{}}}""");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("drydock"), response.body());
        // JsonWriter pretty-prints with ": " after keys.
        assertTrue(response.body().contains("\"isError\": false"), response.body());
    }

    @Test
    void aFailingToolIsAnIsErrorResultNotATransportError() throws Exception {
        HttpResponse<String> response = post("""
                {"jsonrpc":"2.0","id":5,"method":"tools/call",
                 "params":{"name":"worktree_create","arguments":{}}}""");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("isError"), response.body());
        assertTrue(response.body().contains("branch"), response.body());
    }

    @Test
    void aMissingTokenIsRejected() throws Exception {
        HttpResponse<String> response = post("""
                {"jsonrpc":"2.0","id":6,"method":"tools/list","params":{}}""", null, null);

        assertEquals(401, response.statusCode());
    }

    @Test
    void anUnknownTokenIsRejected() throws Exception {
        HttpResponse<String> response = post("""
                {"jsonrpc":"2.0","id":7,"method":"tools/list","params":{}}""", "bogus-token", null);

        assertEquals(401, response.statusCode());
    }

    // ---- Authorization: Bearer, for clients that cannot send a custom header ----

    @Test
    void anAuthorizationBearerTokenIsAccepted() throws Exception {
        // Codex sends only Authorization: Bearer (--bearer-token-env-var); it
        // has no way to set X-Drydock-Session-Token.
        HttpResponse<String> response = postWithAuthorization("""
                {"jsonrpc":"2.0","id":20,"method":"tools/list","params":{}}""", "Bearer " + token);

        assertEquals(200, response.statusCode());
    }

    @Test
    void theBearerSchemeIsMatchedCaseInsensitively() throws Exception {
        // RFC 7235: the auth-scheme token is case-insensitive.
        HttpResponse<String> response = postWithAuthorization("""
                {"jsonrpc":"2.0","id":21,"method":"tools/list","params":{}}""", "bearer " + token);

        assertEquals(200, response.statusCode());
    }

    @Test
    void anUnknownBearerTokenIsRejected() throws Exception {
        HttpResponse<String> response = postWithAuthorization("""
                {"jsonrpc":"2.0","id":22,"method":"tools/list","params":{}}""", "Bearer bogus-token");

        assertEquals(401, response.statusCode());
    }

    @Test
    void aNonBearerAuthorizationSchemeIsRejected() throws Exception {
        HttpResponse<String> response = postWithAuthorization("""
                {"jsonrpc":"2.0","id":23,"method":"tools/list","params":{}}""", "Basic " + token);

        assertEquals(401, response.statusCode());
    }

    @Test
    void aBearerHeaderWithNoTokenIsRejected() throws Exception {
        HttpResponse<String> response = postWithAuthorization("""
                {"jsonrpc":"2.0","id":24,"method":"tools/list","params":{}}""", "Bearer   ");

        assertEquals(401, response.statusCode());
    }

    @Test
    void theDrydockHeaderStillWinsWhenBothAreSent() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(server.endpointUrl()))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header(TOKEN_HEADER, token)
                .header("Authorization", "Bearer bogus-token")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"jsonrpc":"2.0","id":25,"method":"tools/list","params":{}}"""))
                .build();

        assertEquals(200, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void aBearerTokenIsUsedWhenTheDrydockHeaderDoesNotResolve() throws Exception {
        // Attribution, not a secret between sessions (see McpSessionRegistry):
        // falling back costs nothing and spares a client that sets both.
        HttpRequest request = HttpRequest.newBuilder(URI.create(server.endpointUrl()))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header(TOKEN_HEADER, "bogus-token")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"jsonrpc":"2.0","id":26,"method":"tools/list","params":{}}"""))
                .build();

        assertEquals(200, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void aRevokedTokenStopsWorkingOverBearerToo() throws Exception {
        registry.revoke(registry.resolve(token).orElseThrow());

        HttpResponse<String> response = postWithAuthorization("""
                {"jsonrpc":"2.0","id":27,"method":"tools/list","params":{}}""", "Bearer " + token);

        assertEquals(401, response.statusCode());
    }

    private HttpResponse<String> postWithAuthorization(String body, String authorization) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(server.endpointUrl()))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("Authorization", authorization)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aRevokedTokenStopsWorking() throws Exception {
        registry.revoke(registry.resolve(token).orElseThrow());

        HttpResponse<String> response = post("""
                {"jsonrpc":"2.0","id":8,"method":"tools/list","params":{}}""");

        assertEquals(401, response.statusCode());
    }

    // ---- the activity log, for the Review panel ----

    @Test
    void aToolCallRejectedForItsTokenLeavesARow() throws Exception {
        // The panel is the first thing to read when a reviewer is not doing
        // what is expected. A 401 that recorded nothing read as "nothing
        // happened", when what happened is that nothing CAN -- the token
        // named a session that has ended, or resolved to nothing.
        HttpResponse<String> response = post("""
                {"jsonrpc":"2.0","id":60,"method":"tools/call",
                 "params":{"name":"review_scope","arguments":{"scopeId":"sc"}}}""",
                "bogus-token", null);

        assertEquals(401, response.statusCode());
        List<McpActivityLog.Entry> entries = server.activityLog().entries();
        assertEquals(1, entries.size());
        McpActivityLog.Entry entry = entries.getFirst();
        assertEquals("review_scope", entry.tool());
        assertTrue(entry.failed());
        assertEquals(Optional.of("sc"), entry.scopeId());
        assertEquals(McpActivityLog.Direction.OUTBOUND, entry.direction());
        assertTrue(entry.detail().contains("token"), entry.detail());
    }

    @Test
    void aToolCallWithNoTokenAtAllLeavesTheSameRow() throws Exception {
        HttpResponse<String> response = post("""
                {"jsonrpc":"2.0","id":61,"method":"tools/call",
                 "params":{"name":"review_state","arguments":{}}}""",
                null, null);

        assertEquals(401, response.statusCode());
        assertEquals("review_state", server.activityLog().entries().getFirst().tool());
    }

    @Test
    void aRejectedHandshakeLeavesNoRow() throws Exception {
        // initialize and tools/list are housekeeping both sides make without
        // the model asking anything; only the model's calls count as rows.
        HttpResponse<String> response = post("""
                {"jsonrpc":"2.0","id":62,"method":"tools/list","params":{}}""",
                "bogus-token", null);

        assertEquals(401, response.statusCode());
        assertTrue(server.activityLog().entries().isEmpty());
    }

    @Test
    void aFailedToolCallShowsItsRefusalAndNotTheArguments() throws Exception {
        HttpResponse<String> response = post("""
                {"jsonrpc":"2.0","id":63,"method":"tools/call",
                 "params":{"name":"worktree_create","arguments":{}}}""");

        assertEquals(200, response.statusCode());
        McpActivityLog.Entry entry = server.activityLog().entries().getFirst();
        assertTrue(entry.failed());
        assertTrue(entry.detail().contains("branch"),
                "the refusal is what a failed row is about, not the request: " + entry.detail());
    }

    @Test
    void aSuccessfulToolCallStillShowsItsArguments() throws Exception {
        context.repositories.add(new McpSessionContext.RepoSummary("drydock", Path.of("/repos/drydock"),
                Optional.of("feat/mcp"), Optional.of(false), Optional.of(0), Optional.of(0), false));

        HttpResponse<String> response = post("""
                {"jsonrpc":"2.0","id":64,"method":"tools/call",
                 "params":{"name":"repos_list","arguments":{}}}""");

        assertEquals(200, response.statusCode());
        McpActivityLog.Entry entry = server.activityLog().entries().getFirst();
        assertFalse(entry.failed());
        assertEquals("{}", entry.detail().strip(), "the arguments, as the row's detail");
    }

    @Test
    void aForeignOriginIsRejectedEvenWithAValidToken() throws Exception {
        HttpResponse<String> response = post("""
                {"jsonrpc":"2.0","id":9,"method":"tools/list","params":{}}""",
                token, "https://evil.example.com");

        assertEquals(403, response.statusCode());
    }

    @Test
    void aLoopbackOriginIsAccepted() throws Exception {
        HttpResponse<String> response = post("""
                {"jsonrpc":"2.0","id":10,"method":"tools/list","params":{}}""",
                token, "http://127.0.0.1:" + server.port());

        assertEquals(200, response.statusCode());
    }

    @Test
    void aMissingOriginIsAcceptedBecauseCliClientsSendNone() throws Exception {
        HttpResponse<String> response = post("""
                {"jsonrpc":"2.0","id":11,"method":"tools/list","params":{}}""", token, null);

        assertEquals(200, response.statusCode());
    }

    @Test
    void anUnknownMethodGetsJsonRpcMethodNotFound() throws Exception {
        HttpResponse<String> response = post("""
                {"jsonrpc":"2.0","id":12,"method":"resources/list","params":{}}""");

        assertTrue(response.body().contains("-32601"), response.body());
    }

    @Test
    void malformedJsonDoesNotCrashTheServer() throws Exception {
        HttpResponse<String> broken = post("{not json at all");
        assertTrue(broken.statusCode() == 400 || broken.body().contains("-32700"), broken.body());

        HttpResponse<String> after = post("""
                {"jsonrpc":"2.0","id":13,"method":"tools/list","params":{}}""");
        assertEquals(200, after.statusCode(), "server must survive a malformed request");
    }

    @Test
    void getIsNotAccepted() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(server.endpointUrl()))
                        .timeout(Duration.ofSeconds(5))
                        .header(TOKEN_HEADER, token)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(405, response.statusCode());
    }

    @Test
    void aToolCallWithNoNameIsAnActionableToolErrorNotAnInternalError() throws Exception {
        // The router's dispatch is a String switch, which NPEs on a null
        // selector. Left to reach it, a missing "name" surfaces as -32603 with a
        // stack trace in the log -- the catch-all is a last resort, not the
        // handler for a predictable bad argument.
        HttpResponse<String> response = post("""
                {"jsonrpc":"2.0","id":22,"method":"tools/call","params":{"arguments":{}}}""");

        assertEquals(200, response.statusCode());
        assertFalse(response.body().contains("-32603"), response.body());
        assertTrue(response.body().contains("\"isError\": true"), response.body());
    }

    @Test
    void aToolCallWithANonStringNameIsAlsoAToolError() throws Exception {
        HttpResponse<String> response = post("""
                {"jsonrpc":"2.0","id":23,"method":"tools/call","params":{"name":7,"arguments":{}}}""");

        assertEquals(200, response.statusCode());
        assertFalse(response.body().contains("-32603"), response.body());
        assertTrue(response.body().contains("\"isError\": true"), response.body());
    }

    @Test
    void neitherPortNorTokenAppearsInToString() {
        // The port half matters: toString() is the one place a debug log would
        // most plausibly leak it.
        assertFalse(server.toString().contains(token), "token must not appear in toString()");
        assertFalse(server.toString().contains(String.valueOf(server.port())),
                "port must not appear in toString(): " + server.toString());
    }

    @Test
    void closingTwiceIsHarmless() {
        server.close();
        server.close();
    }

    /**
     * The shutdown-racing-startup case. {@code DrydockApplication} publishes
     * this object before {@code start()} runs on a virtual thread, which is not
     * enough on its own: a {@code close()} that wins the race finds nothing
     * bound and no-ops, and the start then leaks a listener socket nobody will
     * ever stop.
     */
    @Test
    void aStartAfterCloseDoesNotBind() throws Exception {
        McpServer racing = new McpServer(registry, new McpToolRouter(context, registry));
        racing.close();

        racing.start();

        assertEquals(0, racing.port(), "close() must permanently prevent binding");
    }

    @Test
    void summarizeReplacesInvisibleCodePoints() {
        // Input has: U+202E (RIGHT-TO-LEFT OVERRIDE), U+200B (ZERO-WIDTH SPACE),
        // U+007F (DEL -- a CONTROL character JsonWriter does not escape, which
        // is why it reaches the panel), and a lone U+D800 (an unpaired
        // SURROGATE, never a member of a valid pair).
        JsonObject args = JsonObject.empty().put("title", new JsonString("a‮b​cd\uD800e"));

        String summary = McpServer.summarize(args);

        // None of these should survive
        assertFalse(summary.contains("‮"), "bidi override survived: " + summary);
        assertFalse(summary.contains("​"), "zero-width space survived: " + summary);
        assertFalse(summary.contains(""), "DEL survived: " + summary);
        assertFalse(summary.contains("\uD800"), "lone surrogate survived: " + summary);
        // But the replacement character should appear for each hidden char
        assertTrue(summary.contains("�"), "nothing was replaced: " + summary);
    }

    @Test
    void summarizeKeepsMultiMemberObjectOnOneLine() {
        JsonObject args = JsonObject.empty()
                .put("scopeId", new JsonString("s1"))
                .put("title", new JsonString("ok"));

        String summary = McpServer.summarize(args);

        assertFalse(summary.contains("\n"), "structural newline survived: " + summary);
        assertFalse(summary.contains("�"), "structural whitespace was replaced: " + summary);
    }

    @Test
    void activityDirectionIsClassifiedByAnExplicitSet() {
        // an agent write
        assertEquals(McpActivityLog.Direction.INBOUND, McpServer.directionOf("review_finding"));
        // a read that the old startsWith("review_") rule got wrong
        assertEquals(McpActivityLog.Direction.OUTBOUND, McpServer.directionOf("review_comments"));
        // the new tool, which the old rule would have called OUTBOUND
        assertEquals(McpActivityLog.Direction.INBOUND, McpServer.directionOf("session_rename"));
        // unknown tools are reads, not writes
        assertEquals(McpActivityLog.Direction.OUTBOUND, McpServer.directionOf("repos_list"));
        // the babysitting tools all write (a pairing, a rerun counter, an escalation)
        for (String babysit : McpToolRouter.BABYSIT_TOOLS) {
            assertEquals(McpActivityLog.Direction.INBOUND, McpServer.directionOf(babysit), babysit);
        }
    }

    @Test
    void summarizeTruncationNeverSplitsASurrogatePair() {
        String emoji = "😀"; // U+1F600, one code point, two chars
        JsonObject args = JsonObject.empty().put("title", new JsonString(emoji.repeat(200)));

        String summary = McpServer.summarize(args);

        assertTrue(summary.codePointCount(0, summary.length()) <= 161,
                "not truncated: " + summary.codePointCount(0, summary.length()));
        for (int i = 0; i < summary.length(); i++) {
            char c = summary.charAt(i);
            if (Character.isHighSurrogate(c)) {
                assertTrue(i + 1 < summary.length() && Character.isLowSurrogate(summary.charAt(i + 1)),
                        "unpaired high surrogate at " + i);
            }
        }
    }

    // ---- remote transport bounds (RemoteMcpTransportPolicy.REMOTE) ----

    private static final String PING = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"%s}";

    private McpServer remoteServer(RemoteMcpTransportPolicy policy, McpSessionContext ctx) throws Exception {
        McpServer remote = new McpServer(registry, new McpToolRouter(ctx, registry), new McpActivityLog(), policy);
        remote.start();
        return remote;
    }

    private HttpResponse<String> postTo(McpServer target, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(target.endpointUrl()))
                .timeout(Duration.ofSeconds(10)).header(TOKEN_HEADER, token)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String paddedTo(int totalBytes) {
        String base = PING.formatted(",\"pad\":\"\"");
        return PING.formatted(",\"pad\":\"" + "a".repeat(totalBytes - base.length()) + "\"");
    }

    @Test
    void remoteBodyAtTheLimitIsServedAndOneByteOverIsRefusedBeforeParsing() throws Exception {
        try (McpServer remote = remoteServer(RemoteMcpTransportPolicy.REMOTE, context)) {
            assertEquals(200, postTo(remote, paddedTo(1024 * 1024)).statusCode());
            HttpResponse<String> over;
            try {
                over = postTo(remote, paddedTo(1024 * 1024 + 1));
                assertEquals(413, over.statusCode());
            } catch (java.io.IOException reset) {
                // the server may close the connection while the client still writes; no 2xx either way
            }
            // an oversize body that is not even JSON is still a size refusal, not a parse error
            try {
                assertEquals(413, postTo(remote, "x".repeat(1024 * 1024 + 1)).statusCode());
            } catch (java.io.IOException reset) {
                // see above
            }
        }
    }

    @Test
    void remoteJsonDepthAtTheLimitParsesAndOneDeeperIsAParseError() throws Exception {
        try (McpServer remote = remoteServer(RemoteMcpTransportPolicy.REMOTE, context)) {
            // the request object is depth 1; params nests the rest
            String atLimit = PING.formatted(",\"params\":" + "[".repeat(31) + "]".repeat(31));
            String over = PING.formatted(",\"params\":" + "[".repeat(32) + "]".repeat(32));
            assertTrue(postTo(remote, atLimit).body().contains("result"));
            assertTrue(postTo(remote, over).body().contains("-32700"));
        }
    }

    @Test
    void remoteRejectsBatchesButLocalKeepsItsParseError() throws Exception {
        String batch = "[" + PING.formatted("") + "]";
        try (McpServer remote = remoteServer(RemoteMcpTransportPolicy.REMOTE, context)) {
            assertTrue(postTo(remote, batch).body().contains("-32600"));
        }
        assertTrue(post(batch).body().contains("-32700"));
    }

    @Test
    void remoteResponseAtTheLimitIsServedAndOneByteOverIsAnExplicitError() throws Exception {
        try (McpServer probe = remoteServer(new RemoteMcpTransportPolicy(true, 1024, 1_000_000, 8, 4, 4,
                Duration.ofSeconds(5), true), context)) {
            int length = postTo(probe, PING.formatted("")).body().getBytes(StandardCharsets.UTF_8).length;
            try (McpServer exact = remoteServer(new RemoteMcpTransportPolicy(true, 1024, length, 8, 4, 4,
                    Duration.ofSeconds(5), true), context);
                 McpServer tight = remoteServer(new RemoteMcpTransportPolicy(true, 1024, length - 1, 8, 4, 4,
                         Duration.ofSeconds(5), true), context)) {
                assertTrue(postTo(exact, PING.formatted("")).body().contains("\"result\""));
                String refused = postTo(tight, PING.formatted("")).body();
                assertTrue(refused.contains("Response too large") && refused.contains("\"id\": 1"), refused);
            }
        }
    }

    /** A context whose repositories() blocks until released, to hold a handler in flight. */
    private McpSessionContext blockingContext(CountDownLatch entered, CountDownLatch release) {
        return (McpSessionContext) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{McpSessionContext.class}, (proxy, method, args) -> {
                    if (method.getName().equals("repositories")) {
                        entered.countDown();
                        release.await();
                        return List.of();
                    }
                    return method.invoke(context, args);
                });
    }

    private static final String REPOS = "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\","
            + "\"params\":{\"name\":\"repos_list\",\"arguments\":{}}}";

    @Test
    void aBlockedHandlerHitsTheDeadlineAndCapsAreEnforcedUntilItReallyReturns() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RemoteMcpTransportPolicy policy = new RemoteMcpTransportPolicy(true, 4096, 4096, 8, 2, 1,
                Duration.ofMillis(400), true);
        try (McpServer remote = remoteServer(policy, blockingContext(entered, release))) {
            long start = System.nanoTime();
            HttpResponse<String> timedOut = postTo(remote, REPOS);
            assertTrue(timedOut.body().contains("-32001"), timedOut.body());
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 5000);
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            release.countDown();
        }
    }

    @Test
    void perSessionAndApplicationWideCapsRefuseTheExcessRequest() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RemoteMcpTransportPolicy policy = new RemoteMcpTransportPolicy(true, 4096, 4096, 8, 2, 1,
                Duration.ofSeconds(20), true);
        try (McpServer remote = remoteServer(policy, blockingContext(entered, release))) {
            var first = client.sendAsync(HttpRequest.newBuilder(URI.create(remote.endpointUrl()))
                    .header(TOKEN_HEADER, token).POST(HttpRequest.BodyPublishers.ofString(REPOS)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(429, postTo(remote, REPOS).statusCode(), "same session, per-session cap 1");
            // a second session fills the app-wide cap of 2; a third request is refused by it
            ManagedSessionId other = ManagedSessionId.newId();
            String otherToken = registry.mint(other, Spawn.ALLOWED);
            var second = client.sendAsync(HttpRequest.newBuilder(URI.create(remote.endpointUrl()))
                    .header(TOKEN_HEADER, otherToken).POST(HttpRequest.BodyPublishers.ofString(REPOS)).build(),
                    HttpResponse.BodyHandlers.ofString());
            Thread.sleep(500);
            assertEquals(503, client.send(HttpRequest.newBuilder(URI.create(remote.endpointUrl()))
                    .POST(HttpRequest.BodyPublishers.ofString(REPOS)).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode(), "app-wide cap 2");
            release.countDown();
            assertEquals(200, first.get(5, TimeUnit.SECONDS).statusCode());
            assertEquals(200, second.get(5, TimeUnit.SECONDS).statusCode());
            // permits came back
            assertEquals(200, postTo(remote, PING.formatted("")).statusCode());
        }
    }

    @Test
    void malformedUnauthenticatedTrafficIsRefusedAndNoTokenReachesTheLog() throws Exception {
        StringBuilder logged = new StringBuilder();
        Handler capture = new Handler() {
            @Override public void publish(LogRecord r) {
                logged.append(r.getMessage()).append(r.getThrown()).append(r.getParameters() == null ? "" : "p");
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        Logger log = Logger.getLogger(McpServer.class.getName());
        log.addHandler(capture);
        try (McpServer remote = remoteServer(RemoteMcpTransportPolicy.REMOTE, context)) {
            assertTrue(postTo(remote, "{\"a\":1,\"a\":2,\"" + token + "\":").body().contains("-32700"));
            assertEquals(401, client.send(HttpRequest.newBuilder(URI.create(remote.endpointUrl()))
                    .header(TOKEN_HEADER, "wrong-" + token)
                    .POST(HttpRequest.BodyPublishers.ofString(PING.formatted(""))).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(404, client.send(HttpRequest.newBuilder(URI.create(remote.endpointUrl().replace("/mcp", "/other")))
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode(), "no redirect, just 404");
        } finally {
            log.removeHandler(capture);
        }
        assertFalse(logged.toString().contains(token), logged.toString());
        assertFalse(logged.toString().contains("Duplicate"), "parser text must not be logged remotely");
    }
}
