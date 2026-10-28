package app.drydock.git;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Jira HTTP client against a real {@link HttpServer} on the loopback
 * address. Every request the client makes is recorded, so the tests check
 * the method, path, credentials, JQL and paging cap exactly as Jira sees
 * them, and every failure status checks the user-facing message.
 */
class JiraServiceTest {

    private static final String SECRET = "tok-SECRET";
    private static final String CLOUD_EMAIL = "me@acme.com";

    /** A Cloud-shaped body one issue wide; the search/jql flavour. */
    private static final String CLOUD_ISSUES = """
            {"issues": [{"key": "PROJ-123",
                         "self": "http://127.0.0.1/rest/api/3/issue/10001",
                         "fields": {"summary": "Fix allocation accounting",
                                    "labels": ["profiling", "bug"],
                                    "updated": "2026-10-20T10:15:30.000+0000"}}],
             "nextPageToken": "1", "isLast": true}
            """;

    /** One recorded request, as the server received it. */
    private record Captured(String method, URI uri, String authorization) { }

    private HttpServer server;
    private ExecutorService serverExecutor;
    private JiraService service;
    private final AtomicInteger hits = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        hits.set(0);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        serverExecutor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(serverExecutor);
        server.start();
        // The loopback http allowance is what makes the loopback server usable
        // without TLS; production keeps it off (see the constructor javadoc).
        service = new JiraService(HttpClient::newBuilder, Duration.ofMillis(500), true);
    }

    @AfterEach
    void stopServer() {
        service.close();
        server.stop(0);
        serverExecutor.shutdown();
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private JiraConnection cloud() {
        return new JiraConnection(JiraConnection.Deployment.CLOUD, base(),
                Optional.of(CLOUD_EMAIL), "");
    }

    @Test
    void cloudSearchesTheCloudEndpointWithBasicAuthAndABoundedQuery() {
        AtomicReference<Captured> captured = new AtomicReference<>();
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            captured.set(new Captured(exchange.getRequestMethod(), exchange.getRequestURI(),
                    exchange.getRequestHeaders().getFirst("Authorization")));
            respond(exchange, 200, CLOUD_ISSUES);
        });

        JiraIssueListing listing = await(service.openIssues(Optional.of(cloud()), () -> Optional.of(SECRET)));

        Captured request = captured.get();
        assertEquals(1, hits.get());
        assertEquals("GET", request.method());
        assertEquals("/rest/api/3/search/jql", request.uri().getPath());
        assertEquals("Basic " + Base64.getEncoder()
                        .encodeToString((CLOUD_EMAIL + ":" + SECRET).getBytes(StandardCharsets.UTF_8)),
                request.authorization());
        Map<String, List<String>> query = query(request.uri());
        assertEquals(JiraConnection.DEFAULT_JQL, query.get("jql").getFirst());
        assertEquals("100", query.get("maxResults").getFirst());
        String fields = query.get("fields").getFirst();
        assertTrue(fields.contains("summary") && fields.contains("labels") && fields.contains("updated"),
                "fields must ask for summary, labels and updated: " + fields);
        JiraIssueListing.Listed listed = assertInstanceOf(JiraIssueListing.Listed.class, listing);
        assertEquals(List.of(new JiraIssue("PROJ-123", "Fix allocation accounting",
                        base() + "/browse/PROJ-123", List.of("profiling", "bug"),
                        Optional.of(Instant.parse("2026-10-20T10:15:30Z")))),
                listed.issues());
    }

    @Test
    void dataCenterSearchesTheDataCenterEndpointWithABearerToken() {
        AtomicReference<Captured> captured = new AtomicReference<>();
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            captured.set(new Captured(exchange.getRequestMethod(), exchange.getRequestURI(),
                    exchange.getRequestHeaders().getFirst("Authorization")));
            respond(exchange, 200, CLOUD_ISSUES);
        });
        JiraConnection connection = new JiraConnection(JiraConnection.Deployment.DATA_CENTER, base(),
                Optional.empty(), "project = OPS ORDER BY updated DESC");

        JiraIssueListing listing = await(service.openIssues(Optional.of(connection), () -> Optional.of("pat-SECRET")));

        Captured request = captured.get();
        assertEquals("GET", request.method());
        assertEquals("/rest/api/2/search", request.uri().getPath());
        assertEquals("Bearer pat-SECRET", request.authorization());
        assertEquals("project = OPS ORDER BY updated DESC", query(request.uri()).get("jql").getFirst());
        assertInstanceOf(JiraIssueListing.Listed.class, listing);
    }

    /** One failure status case: what the server answers and what the message must carry. */
    private record StatusCase(int status, String body, String deniedReason, String fragment) { }

    @Test
    void failureStatusesGiveFailedWithActionableMessagesNeverAnEmptyListing() {
        List<StatusCase> cases = List.of(
                new StatusCase(401, "", null, "refused the credentials"),
                new StatusCase(403, "", null, "refused the credentials"),
                new StatusCase(403, "", "Your login was denied: CAPTCHA required", "CAPTCHA"),
                new StatusCase(404, "", null, "search endpoint not found"),
                new StatusCase(429, "", null, "rate-limited"),
                new StatusCase(500, "", null, "server error"),
                new StatusCase(302, "", null, "redirected"),
                new StatusCase(400, "{\"errorMessages\":[\"Field 'x' does not exist\"]}", null,
                        "Field 'x' does not exist"));
        AtomicInteger served = new AtomicInteger();
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            StatusCase c = cases.get(served.getAndIncrement());
            if (c.deniedReason() != null) {
                exchange.getResponseHeaders().add("X-Authentication-Denied-Reason", c.deniedReason());
            }
            respond(exchange, c.status(), c.body());
        });

        for (StatusCase c : cases) {
            JiraIssueListing listing = await(service.openIssues(Optional.of(cloud()), () -> Optional.of(SECRET)));
            JiraIssueListing.Failed failed = assertInstanceOf(JiraIssueListing.Failed.class, listing,
                    "HTTP " + c.status() + " must be Failed, never Listed(empty)");
            assertTrue(failed.message().contains(c.fragment()),
                    "HTTP " + c.status() + " message must mention \"" + c.fragment() + "\": " + failed.message());
            assertFalse(failed.message().contains(SECRET), "message must not carry the token: " + failed.message());
        }
        assertEquals(cases.size(), hits.get());
    }

    @Test
    void aHangingServerFailsWithinTheRequestBudget() throws IOException {
        // A handler that never answers (5 s); the 500 ms test request budget
        // must cut the wait long before the 3 s bound, and the tree's own
        // wait stays longer than both real budgets together.
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, CLOUD_ISSUES);
        });

        long start = System.nanoTime();
        JiraIssueListing listing = await(service.openIssues(Optional.of(cloud()), () -> Optional.of(SECRET)));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        JiraIssueListing.Failed failed = assertInstanceOf(JiraIssueListing.Failed.class, listing);
        assertTrue(failed.message().contains("did not answer"), failed.message());
        assertTrue(elapsed.toMillis() < 3000, "the request budget must bound the wait, took " + elapsed);
        assertTrue(JiraService.CONNECT_TIMEOUT.plus(JiraService.REQUEST_TIMEOUT).toSeconds() < 20,
                "the tree's 20 s wait must outlast the client's own budgets");
    }

    @Test
    void theSecretNeverReachesAFailureMessageOrALogRecord() {
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            respond(exchange, 401, "{\"errorMessages\":[\"Invalid token\"]}");
        });
        List<LogRecord> published = new ArrayList<>();
        Handler recorder = new Handler() {
            @Override
            public void publish(LogRecord record) {
                published.add(record);
            }

            @Override
            public void flush() { }

            @Override
            public void close() { }
        };
        Logger logger = Logger.getLogger(JiraService.class.getName());
        logger.addHandler(recorder);
        try {
            JiraIssueListing.Failed refused = assertInstanceOf(JiraIssueListing.Failed.class,
                    await(service.openIssues(Optional.of(cloud()), () -> Optional.of(SECRET))));
            assertFalse(refused.message().contains(SECRET), "the 401 message must not carry the token");

            // The same service against a port nothing listens on any more.
            server.stop(0);
            JiraIssueListing unreachable =
                    await(service.openIssues(Optional.of(cloud()), () -> Optional.of(SECRET)));
            JiraIssueListing.Failed failed = assertInstanceOf(JiraIssueListing.Failed.class, unreachable);
            assertTrue(failed.message().contains("Could not reach"), failed.message());
            assertFalse(failed.message().contains(SECRET), "the network-error message must not carry the token");

            assertFalse(published.isEmpty(), "the failure paths must have logged");
            for (LogRecord record : published) {
                assertFalse(String.valueOf(record.getMessage()).contains(SECRET),
                        "no log record may carry the token: " + record.getMessage());
            }
        } finally {
            logger.removeHandler(recorder);
        }
    }

    @Test
    void noConnectionMeansUnsupportedWithNoTrafficAndNoSecretRead() {
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            respond(exchange, 200, CLOUD_ISSUES);
        });
        AtomicBoolean secretRead = new AtomicBoolean();

        JiraIssueListing listing = await(service.openIssues(Optional.empty(), () -> {
            secretRead.set(true);
            return Optional.of(SECRET);
        }));

        assertInstanceOf(JiraIssueListing.Unsupported.class, listing);
        assertEquals(0, hits.get(), "no request may be sent for an unconfigured project");
        assertFalse(secretRead.get(), "the secret supplier must not be touched for an unconfigured project");
    }

    @Test
    void plainHttpIsRefusedBeforeAnyRequestWithoutTheLoopbackFlag() {
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            respond(exchange, 200, CLOUD_ISSUES);
        });
        try (JiraService strict = new JiraService(HttpClient::newBuilder, Duration.ofMillis(500), false)) {
            JiraConnection connection = new JiraConnection(JiraConnection.Deployment.DATA_CENTER, base(),
                    Optional.empty(), "");

            JiraIssueListing listing = await(strict.openIssues(Optional.of(connection), () -> Optional.of("pat-SECRET")));

            JiraIssueListing.Failed failed = assertInstanceOf(JiraIssueListing.Failed.class, listing);
            assertTrue(failed.message().contains("https://"), failed.message());
            assertEquals(0, hits.get(), "a plain-http base URL must be refused before any request");
        }
    }

    private static JiraIssueListing await(CompletableFuture<JiraIssueListing> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            throw new AssertionError("the listing future did not complete normally", e);
        }
    }

    /** The decoded query parameters, in order, as the client sent them. */
    private static Map<String, List<String>> query(URI uri) {
        Map<String, List<String>> params = new LinkedHashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null) {
            return params;
        }
        for (String pair : raw.split("&")) {
            int equals = pair.indexOf('=');
            String key = URLDecoder.decode(equals < 0 ? pair : pair.substring(0, equals), StandardCharsets.UTF_8);
            String value = equals < 0 ? "" : URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            params.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
        }
        return params;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        if (status == 302) {
            exchange.getResponseHeaders().add("Location", "https://acme.atlassian.net/login");
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}
