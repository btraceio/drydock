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
import java.util.Optional;

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
}
