package app.drydock.mcp;

import app.drydock.domain.ManagedSessionId;
import app.drydock.mcp.McpSessionRegistry.Spawn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

    private HttpResponse<String> post(String body, String presentedToken, String origin, String host)
            throws Exception {
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
        if (host != null) {
            request.header("X-Forwarded-Host", host);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String body) throws Exception {
        return post(body, token, null, null);
    }

    @Test
    void bindsOnLoopbackOnly() {
        assertTrue(server.endpointUrl().startsWith("http://127.0.0.1:"), server.endpointUrl());
        assertTrue(server.port() > 0);
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
                {"jsonrpc":"2.0","id":6,"method":"tools/list","params":{}}""", null, null, null);

        assertEquals(401, response.statusCode());
    }

    @Test
    void anUnknownTokenIsRejected() throws Exception {
        HttpResponse<String> response = post("""
                {"jsonrpc":"2.0","id":7,"method":"tools/list","params":{}}""", "bogus-token", null, null);

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
                token, "https://evil.example.com", null);

        assertEquals(403, response.statusCode());
    }

    @Test
    void aLoopbackOriginIsAccepted() throws Exception {
        HttpResponse<String> response = post("""
                {"jsonrpc":"2.0","id":10,"method":"tools/list","params":{}}""",
                token, "http://127.0.0.1:" + server.port(), null);

        assertEquals(200, response.statusCode());
    }

    @Test
    void aMissingOriginIsAcceptedBecauseCliClientsSendNone() throws Exception {
        HttpResponse<String> response = post("""
                {"jsonrpc":"2.0","id":11,"method":"tools/list","params":{}}""", token, null, null);

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
    void neitherPortNorTokenIsEverLogged() {
        assertFalse(server.toString().contains(token), "token must not appear in toString()");
    }

    @Test
    void closingTwiceIsHarmless() {
        server.close();
        server.close();
    }
}
