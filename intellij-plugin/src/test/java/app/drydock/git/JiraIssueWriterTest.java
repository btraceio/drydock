package app.drydock.git;

import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Jira ticket writer against a real loopback {@link HttpServer}: requests, outcomes, redaction. */
class JiraIssueWriterTest {

    private static final String SECRET = "tok-SECRET";
    private static final JsonObject PROPERTY = JsonObject.empty().put("id", new JsonString("fix-a1b2"));

    /** One request as the server received it. */
    private record Captured(String method, String path, String authorization, String body) { }

    private HttpServer server;
    private ExecutorService serverExecutor;
    private JiraIssueWriter writer;
    private final List<Captured> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        serverExecutor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(serverExecutor);
        server.start();
        writer = new JiraIssueWriter(HttpClient::newBuilder, Duration.ofMillis(500), true);
    }

    @AfterEach
    void stopServer() {
        writer.close();
        server.stop(0);
        serverExecutor.shutdown();
    }

    private JiraConnection dataCenter() {
        return new JiraConnection(JiraConnection.Deployment.DATA_CENTER,
                "http://127.0.0.1:" + server.getAddress().getPort(), Optional.empty(), "");
    }

    private void answer(int createStatus, String createBody, int propertyStatus) {
        server.createContext("/", exchange -> {
            requests.add(new Captured(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            boolean create = "POST".equals(exchange.getRequestMethod());
            respond(exchange, create ? createStatus : propertyStatus, create ? createBody : "");
        });
    }

    @Test
    void createsTheTicketThenStoresTheMetadataProperty() throws Exception {
        answer(201, "{\"id\":\"10001\",\"key\":\"PROJ-7\",\"self\":\"x\"}", 200);

        JiraIssueWriter.Created created = assertInstanceOf(JiraIssueWriter.Created.class, writer.create(
                dataCenter(), SECRET, "PROJ", "Task", "Fix it", "Body", "drydock.task", PROPERTY));

        assertEquals("PROJ-7", created.key());
        assertEquals(dataCenter().browseUrl("PROJ-7"), created.url());
        assertEquals(Optional.empty(), created.propertyProblem());
        assertEquals(2, requests.size());
        Captured post = requests.get(0);
        assertEquals("POST", post.method());
        assertEquals("/rest/api/2/issue", post.path());
        assertEquals("Bearer " + SECRET, post.authorization());
        JsonObject fields = (JsonObject) ((JsonObject) JsonParser.parse(post.body())).get("fields");
        assertEquals(new JsonString("PROJ"), ((JsonObject) fields.get("project")).get("key"));
        assertEquals(new JsonString("Task"), ((JsonObject) fields.get("issuetype")).get("name"));
        assertEquals(new JsonString("Fix it"), fields.get("summary"));
        assertEquals(new JsonString("Body"), fields.get("description"));
        Captured put = requests.get(1);
        assertEquals("PUT", put.method());
        assertEquals("/rest/api/2/issue/PROJ-7/properties/drydock.task", put.path());
        assertEquals(PROPERTY, JsonParser.parse(put.body()));
    }

    @Test
    void aTicketWhoseMetadataWasRefusedIsStillCreatedAndSaysSo() {
        answer(201, "{\"key\":\"PROJ-8\"}", 403);

        JiraIssueWriter.Created created = assertInstanceOf(JiraIssueWriter.Created.class, writer.create(
                dataCenter(), SECRET, "PROJ", "Task", "Fix it", "Body", "drydock.task", PROPERTY));

        assertEquals("PROJ-8", created.key());
        assertEquals(Optional.of("Jira did not store the task metadata on PROJ-8 (HTTP 403)"),
                created.propertyProblem());
    }

    @Test
    void aRefusedTicketCarriesJirasOwnReasonsAndNeverTheSecret() {
        answer(400, "{\"errorMessages\":[],\"errors\":{\"issuetype\":\"Specify a valid issue type " + SECRET
                + "\"}}", 200);

        JiraIssueWriter.Failed failed = assertInstanceOf(JiraIssueWriter.Failed.class, writer.create(
                dataCenter(), SECRET, "PROJ", "Storyy", "Fix it", "Body", "drydock.task", PROPERTY));

        assertTrue(failed.message().startsWith("Jira refused the ticket: Specify a valid issue type"),
                failed.message());
        assertFalse(failed.message().contains(SECRET), failed.message());
        assertEquals(1, requests.size(), "no property is written for a ticket that does not exist");
    }

    @Test
    void badInputIsRefusedBeforeAnyRequest() {
        answer(201, "{\"key\":\"PROJ-9\"}", 200);

        assertInstanceOf(JiraIssueWriter.Failed.class, writer.create(dataCenter(), " ", "PROJ", "Task",
                "t", "b", "drydock.task", PROPERTY));
        assertInstanceOf(JiraIssueWriter.Failed.class, writer.create(dataCenter(), SECRET, "proj-1", "Task",
                "t", "b", "drydock.task", PROPERTY));
        assertInstanceOf(JiraIssueWriter.Failed.class, writer.create(new JiraConnection(
                JiraConnection.Deployment.CLOUD, dataCenter().baseUrl(), Optional.empty(), ""), SECRET, "PROJ",
                "Task", "t", "b", "drydock.task", PROPERTY));
        try (JiraIssueWriter strict = new JiraIssueWriter(HttpClient::newBuilder)) {
            assertInstanceOf(JiraIssueWriter.Failed.class, strict.create(dataCenter(), SECRET, "PROJ", "Task",
                    "t", "b", "drydock.task", PROPERTY), "plain http is refused outside the loopback test");
        }
        assertEquals(0, requests.size());
    }

    @Test
    void projectKeysFollowJirasShape() {
        assertEquals(Optional.empty(), JiraIssueWriter.projectKeyRefusal("PROJ_2"));
        assertTrue(JiraIssueWriter.projectKeyRefusal("2PROJ").isPresent());
        assertTrue(JiraIssueWriter.projectKeyRefusal("").isPresent());
        assertTrue(JiraIssueWriter.projectKeyRefusal(null).isPresent());
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}
