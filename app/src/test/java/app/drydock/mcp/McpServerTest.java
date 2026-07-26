package app.drydock.mcp;

import app.drydock.domain.ManagedSessionId;
import app.drydock.mcp.McpSessionRegistry.Spawn;
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
}
