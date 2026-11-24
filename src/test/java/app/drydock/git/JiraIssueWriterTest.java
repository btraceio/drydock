package app.drydock.git;

import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue.JsonArray;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    /** The task's priority name rides the create payload as fields.priority; absent names none. */
    @Test
    void createSendsThePriorityNameWhenOneIsGiven() throws Exception {
        answer(201, "{\"id\":\"10001\",\"key\":\"PROJ-9\",\"self\":\"x\"}", 200);

        assertInstanceOf(JiraIssueWriter.Created.class, writer.create(
                dataCenter(), SECRET, "PROJ", "Task", "Fix it", "Body", "drydock.task", PROPERTY,
                Optional.of("Top")));
        assertInstanceOf(JiraIssueWriter.Created.class, writer.createChecked(
                dataCenter(), SECRET, "PROJ", "Task", "Fix it", "Body", "drydock.task", PROPERTY,
                Optional.empty()));

        assertEquals(4, requests.size(), "each create is a POST plus the property PUT");
        JsonObject withPriority = (JsonObject) ((JsonObject) JsonParser.parse(requests.get(0).body())).get("fields");
        assertEquals(new JsonString("Top"),
                ((JsonObject) withPriority.get("priority")).get("name"),
                "the stored Jira name travels verbatim");
        JsonObject without = (JsonObject) ((JsonObject) JsonParser.parse(requests.get(2).body())).get("fields");
        assertFalse(without.has("priority"), "an unprioritized task lets Jira's default apply");
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

    private void answerAll(int status, String body) {
        server.createContext("/", exchange -> {
            requests.add(new Captured(exchange.getRequestMethod(), exchange.getRequestURI().getPath()
                    + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery()),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            respond(exchange, status, body);
        });
    }

    private static String links(String... entries) {
        return "{\"fields\":{\"issuelinks\":[" + String.join(",", entries) + "]}}";
    }

    private static String entry(String type, String direction, String key) {
        return "{\"type\":{\"name\":\"" + type + "\"},\"" + direction + "\":{\"key\":\"" + key + "\"}}";
    }

    private JiraIssueWriter.LinkLookup lookup(String key) {
        return writer.lookupLinks(dataCenter(), SECRET, key);
    }

    @Test
    void lookupReadsTheLinksOfTheEncodedKeyWithItsOwnCredentials() {
        answerAll(200, links(entry("Blocks", "outwardIssue", "B-2"), entry("Relates", "inwardIssue", "C-3")));

        JiraIssueWriter.Links found = assertInstanceOf(JiraIssueWriter.Links.class, lookup("A-1"));

        assertEquals(List.of(
                new JiraIssueWriter.LinkEntry("Blocks", Optional.empty(), Optional.of("B-2")),
                new JiraIssueWriter.LinkEntry("Relates", Optional.of("C-3"), Optional.empty())), found.entries());
        assertEquals(1, requests.size());
        assertEquals("GET", requests.get(0).method());
        assertEquals("/rest/api/2/issue/A-1?fields=issuelinks", requests.get(0).path());
        assertEquals("Bearer " + SECRET, requests.get(0).authorization());
    }

    @Test
    void anEmptyArrayIsConfirmedAbsence() {
        answerAll(200, links());

        assertEquals(new JiraIssueWriter.Links(List.of()), lookup("A-1"));
    }

    @Test
    void missingOrIllTypedLinksAndEntriesAreFailuresNotAbsence() {
        for (String body : List.of("{}", "{\"fields\":{}}", "{\"fields\":{\"issuelinks\":{}}}",
                "{\"fields\":{\"issuelinks\":null}}", "not json", "[]",
                links("5"),
                links("{\"outwardIssue\":{\"key\":\"B-2\"}}"),
                links("{\"type\":{},\"outwardIssue\":{\"key\":\"B-2\"}}"),
                links("{\"type\":{\"name\":\"Blocks\"}}"),
                links("{\"type\":{\"name\":\"Blocks\"},\"outwardIssue\":{\"key\":\"B-2\"},"
                        + "\"inwardIssue\":{\"key\":\"C-3\"}}"),
                links("{\"type\":{\"name\":\"Blocks\"},\"outwardIssue\":{}}"))) {
            assertInstanceOf(JiraIssueWriter.LookupFailed.class, JiraIssueWriter.parseLinks(body, "A-1"), body);
        }
    }

    @Test
    void aForbiddenReadIsAnExplicitFailure() {
        answerAll(403, "{}");

        JiraIssueWriter.LookupFailed failed = assertInstanceOf(JiraIssueWriter.LookupFailed.class, lookup("A-1"));

        assertTrue(failed.message().contains("403"), failed.message());
    }

    @Test
    void aSlowReadIsAFailureNotAbsence() {
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });

        assertInstanceOf(JiraIssueWriter.LookupFailed.class, lookup("A-1"));
    }

    @Test
    void presenceFollowsTheTypeAndTheDirectionOfTheQueriedEnd() {
        var blocksOut = List.of(new JiraIssueWriter.LinkEntry("Blocks", Optional.empty(), Optional.of("D-2")));
        var blocksIn = List.of(new JiraIssueWriter.LinkEntry("Blocks", Optional.of("P-1"), Optional.empty()));
        var relatesOut = List.of(new JiraIssueWriter.LinkEntry("Relates", Optional.empty(), Optional.of("D-2")));

        assertTrue(JiraIssueWriter.blocksPresent(blocksOut, JiraIssueWriter.Queried.PREREQUISITE, "P-1", "D-2"));
        assertTrue(JiraIssueWriter.blocksPresent(blocksIn, JiraIssueWriter.Queried.DEPENDENT, "P-1", "D-2"));
        assertFalse(JiraIssueWriter.blocksPresent(blocksIn, JiraIssueWriter.Queried.PREREQUISITE, "P-1", "D-2"));
        assertFalse(JiraIssueWriter.blocksPresent(blocksOut, JiraIssueWriter.Queried.DEPENDENT, "P-1", "D-2"));
        assertFalse(JiraIssueWriter.blocksPresent(relatesOut, JiraIssueWriter.Queried.PREREQUISITE, "P-1", "D-2"));
        assertFalse(JiraIssueWriter.blocksPresent(blocksOut, JiraIssueWriter.Queried.PREREQUISITE, "P-1", "D-9"));
        assertFalse(JiraIssueWriter.blocksPresent(List.of(), JiraIssueWriter.Queried.PREREQUISITE, "P-1", "D-2"));
    }

    @Test
    void theBlocksLinkIsPostedWithPrerequisiteInwardAndDependentOutward() {
        answerAll(201, "");

        assertEquals(new JiraIssueWriter.Linked(), writer.createBlocksLink(dataCenter(), SECRET, "P-1", "D-2"));

        Captured post = requests.get(0);
        assertEquals("POST", post.method());
        assertEquals("/rest/api/2/issueLink", post.path());
        assertEquals("Bearer " + SECRET, post.authorization());
        JsonObject body = (JsonObject) JsonParser.parse(post.body());
        assertEquals(new JsonString("Blocks"), ((JsonObject) body.get("type")).get("name"));
        assertEquals(new JsonString("P-1"), ((JsonObject) body.get("inwardIssue")).get("key"));
        assertEquals(new JsonString("D-2"), ((JsonObject) body.get("outwardIssue")).get("key"));
    }

    @Test
    void linkRefusalsAreConfirmedFailuresAndATimeoutIsUncertain() {
        answerAll(404, "{}");
        assertInstanceOf(JiraIssueWriter.LinkFailed.class, writer.createBlocksLink(dataCenter(), SECRET, "P-1", "D-2"));
        server.removeContext("/");
        answerAll(400, "{\"errorMessages\":[\"No issue link type with name 'Blocks' found.\"]}");
        JiraIssueWriter.LinkFailed missing = assertInstanceOf(JiraIssueWriter.LinkFailed.class,
                writer.createBlocksLink(dataCenter(), SECRET, "P-1", "D-2"));
        assertTrue(missing.message().contains("Blocks"), missing.message());
        server.removeContext("/");
        answerAll(403, "{}");
        JiraIssueWriter.LinkFailed forbidden = assertInstanceOf(JiraIssueWriter.LinkFailed.class,
                writer.createBlocksLink(dataCenter(), SECRET, "P-1", "D-2"));
        assertTrue(forbidden.message().contains("403"), forbidden.message());
        server.removeContext("/");
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        assertInstanceOf(JiraIssueWriter.LinkUncertain.class,
                writer.createBlocksLink(dataCenter(), SECRET, "P-1", "D-2"));
    }

    @Test
    void aTimedOutOrKeylessCreateIsUncertainNotFailed() {
        answer(201, "{\"id\":\"1\"}", 200);
        assertInstanceOf(JiraIssueWriter.Uncertain.class, writer.createChecked(
                dataCenter(), SECRET, "PROJ", "Task", "t", "b", "drydock.task", PROPERTY));
        server.removeContext("/");
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        assertInstanceOf(JiraIssueWriter.Uncertain.class, writer.createChecked(
                dataCenter(), SECRET, "PROJ", "Task", "t", "b", "drydock.task", PROPERTY));
    }

    @Test
    void theExistingCreateFoldsAnUncertainResultIntoAFailureWithTheSameAdvice() {
        answer(201, "{\"id\":\"1\"}", 200);

        JiraIssueWriter.Failed failed = assertInstanceOf(JiraIssueWriter.Failed.class, writer.create(
                dataCenter(), SECRET, "PROJ", "Task", "t", "b", "drydock.task", PROPERTY));

        assertTrue(failed.message().contains("before retrying"), failed.message());
    }

    @Test
    void identicalKeysOnTwoSitesReachOnlyTheirOwnSiteWithTheirOwnToken() throws Exception {
        HttpServer other = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        other.setExecutor(serverExecutor);
        List<Captured> otherRequests = new CopyOnWriteArrayList<>();
        AtomicReference<String> unused = new AtomicReference<>();
        other.createContext("/", exchange -> {
            otherRequests.add(new Captured(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"), ""));
            respond(exchange, 200, links());
        });
        other.start();
        try {
            answerAll(200, links());
            JiraConnection second = new JiraConnection(JiraConnection.Deployment.DATA_CENTER,
                    "http://127.0.0.1:" + other.getAddress().getPort(), Optional.empty(), "");

            writer.lookupLinks(dataCenter(), "tok-ONE", "SAME-1");
            writer.lookupLinks(second, "tok-TWO", "SAME-1");

            assertEquals(1, requests.size());
            assertEquals("Bearer tok-ONE", requests.get(0).authorization());
            assertEquals(1, otherRequests.size());
            assertEquals("Bearer tok-TWO", otherRequests.get(0).authorization());
            assertNotNull(unused);
        } finally {
            other.stop(0);
        }
    }

    @Test
    void addsALabelWithoutTouchingTheExistingOnes() {
        answerAll(204, "");

        assertInstanceOf(JiraIssueWriter.Labeled.class,
                writer.addLabel(dataCenter(), SECRET, "PROJ-7", "triaged"));

        assertEquals(1, requests.size());
        Captured put = requests.get(0);
        assertEquals("PUT", put.method());
        assertEquals("/rest/api/2/issue/PROJ-7", put.path());
        assertEquals("Bearer " + SECRET, put.authorization());
        // Jira's edit shape: update is an OBJECT keyed by field name; add
        // holds the plain label string. There is no /labels sub-resource.
        JsonObject root = (JsonObject) JsonParser.parse(put.body());
        JsonObject update = (JsonObject) root.get("update");
        JsonArray ops = (JsonArray) update.get("labels");
        assertEquals(new JsonString("triaged"), ((JsonObject) ops.elements().get(0)).get("add"));
    }

    @Test
    void aRefusedLabelCarriesJirasOwnReasonsAndNeverTheSecret() {
        answerAll(403, "{\"errorMessages\":[\"no permission to edit " + SECRET + "\"]}");

        JiraIssueWriter.LabelFailed failed = assertInstanceOf(JiraIssueWriter.LabelFailed.class,
                writer.addLabel(dataCenter(), SECRET, "PROJ-7", "triaged"));

        assertTrue(failed.message().startsWith("Jira refused the request"), failed.message());
        assertFalse(failed.message().contains(SECRET), failed.message());
    }

    @Test
    void badLabelInputIsRefusedBeforeAnyRequest() {
        answerAll(204, "");

        assertInstanceOf(JiraIssueWriter.LabelFailed.class,
                writer.addLabel(dataCenter(), SECRET, "", "triaged"), "a blank issue key is refused");
        assertInstanceOf(JiraIssueWriter.LabelFailed.class,
                writer.addLabel(dataCenter(), SECRET, "PROJ-7", " "), "a blank label is refused");
        assertEquals(0, requests.size());
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
