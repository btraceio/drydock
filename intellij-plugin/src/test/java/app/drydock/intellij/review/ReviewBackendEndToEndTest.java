package app.drydock.intellij.review;

import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.agent.api.McpAccess;
import app.drydock.agent.api.McpDelivery;
import app.drydock.app.ApplicationStateStore;
import app.drydock.domain.AgentBinding;
import app.drydock.domain.ManagedAgentSession;
import app.drydock.domain.ManagedSessionId;
import app.drydock.domain.Repository;
import app.drydock.domain.RepositoryId;
import app.drydock.domain.RepositorySettings;
import app.drydock.domain.SessionMode;
import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionWorkspace;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.review.ReviewScope;
import app.drydock.state.JsonApplicationStateRepository;
import app.drydock.state.json.JsonParser;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonArray;
import app.drydock.state.json.JsonValue.JsonBoolean;
import app.drydock.state.json.JsonValue.JsonNumber;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import app.drydock.state.json.JsonWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The review stack end to end: a real git worktree, the real backend and
 * its loopback MCP server, driven over HTTP with the session's own token.
 */
class ReviewBackendEndToEndTest {

    private static final String TOKEN_HEADER = "X-Drydock-Session-Token";
    private static final String FINDING_TEXT = "e2e-finding-marker-text";

    @TempDir
    Path base;

    private ExecutorService executor;
    private ReviewBackend backend;
    private ReviewScope scope;
    private McpAccess access;
    private final HttpClient client = HttpClient.newHttpClient();
    private int nextRequestId = 1;

    @BeforeEach
    void setUp() throws Exception {
        Path repo = Files.createDirectories(base.resolve("fixture").resolve("repo")).toRealPath();
        runGit(repo, "init", "-b", "main");
        runGit(repo, "config", "user.name", "Test");
        runGit(repo, "config", "user.email", "test@example.com");
        Files.createDirectories(repo.resolve("src"));
        Files.writeString(repo.resolve("src/A.java"), "one\ntwo\nthree\n");
        runGit(repo, "add", "src/A.java");
        runGit(repo, "commit", "-m", "initial commit");
        Path wt = base.resolve("fixture").resolve("wt");
        runGit(repo, "worktree", "add", "-b", "feat", wt.toString());
        Files.writeString(wt.resolve("src/A.java"), "one\nTWO\nthree\nfour\n");
        runGit(wt, "add", "src/A.java");
        runGit(wt, "commit", "-m", "change a line and add one");

        RepositoryId repositoryId = RepositoryId.newId();
        Repository repository = new Repository(repositoryId, repo, "repo", Instant.EPOCH,
                Instant.EPOCH, RepositorySettings.DEFAULT);
        ManagedAgentSession session = new ManagedAgentSession.Builder(ManagedSessionId.newId(),
                repositoryId, "Task", AgentBinding.unlaunched(AgentKind.PI, SessionMode.WRITES),
                SessionWorkspace.inRepository(wt.toRealPath()), Instant.EPOCH)
                .status(SessionStatus.RUNNING).build();
        ApplicationStateStore store = ApplicationStateStore.forRepository(
                new JsonApplicationStateRepository(base.resolve("state.json")));
        store.update(state -> state.withRepositories(List.of(repository))
                .withSessions(List.of(session)));

        executor = Executors.newSingleThreadExecutor();
        AgentRegistry registry = new AgentRegistry(List.of(),
                new AgentContext(base, base.resolve("activity"), executor));
        backend = new ReviewBackend(base.resolve("drydock"),
                () -> store.state().sessions(), () -> store.state().repositories(), registry);
        backend.start();
        assertTrue(backend.ready());

        scope = backend.scopeResolver().resolve(session).get(30, TimeUnit.SECONDS);
        access = backend.accessFor(session.id(), McpDelivery.CONFIG_FILE).orElseThrow();
    }

    @AfterEach
    void tearDown() {
        client.close();
        backend.close();
        executor.shutdownNow();
    }

    @Test
    void toolsListReturnsTheTenReviewTools() throws Exception {
        JsonValue result = rpc("tools/list", JsonObject.empty());

        Set<String> names = new HashSet<>();
        for (JsonValue tool : array(field(result, "tools"))) {
            names.add(str(tool, "name"));
        }
        assertEquals(Set.of("review_comments", "review_reply", "review_scope", "review_tour",
                "review_check", "review_finding", "review_answer", "review_ask_answer",
                "review_state", "review_recheck"), names);
    }

    @Test
    void aFindingOnAScopeLineIsStoredAndFlushedToDisk() throws Exception {
        JsonValue scopeResult = callTool("review_scope", JsonObject.empty().put("scopeId", new JsonString(scope.id())));

        List<JsonValue> hunks = array(field(scopeResult, "hunks"));
        assertFalse(hunks.isEmpty());
        for (JsonValue hunk : hunks) {
            assertTrue(str(hunk, "id").matches("h_src/A\\.java_\\d+"), str(hunk, "id"));
        }
        List<String> keys = new ArrayList<>();
        for (JsonValue hunk : hunks) {
            for (JsonValue line : array(field(hunk, "lines"))) {
                keys.add(str(line, "key"));
            }
        }
        assertTrue(keys.stream().allMatch(key -> key.matches("[on]\\d+")), keys.toString());
        assertTrue(keys.stream().anyMatch(key -> key.matches("o\\d+")), "the replaced line is a deleted row: " + keys);
        // Fixture diff (-U12): one hunk "@@ -1,3 +1,4 @@" = ctx one (n1), -two (o2), +TWO (n2), ctx three (n3), +four (n4).
        assertEquals(List.of("h_src/A.java_0"), hunks.stream().map(h -> str(h, "id")).toList());
        assertEquals(List.of("n1", "o2", "n2", "n3", "n4"), keys);
        String anchorKey = keys.stream().filter(key -> key.matches("n\\d+")).findFirst().orElseThrow();

        JsonObject anchor = JsonObject.empty()
                .put("file", new JsonString("src/A.java"))
                .put("startKey", new JsonString(anchorKey));
        JsonObject finding = JsonObject.empty()
                .put("id", new JsonString("f_e2e_1"))
                .put("anchor", anchor)
                .put("severity", new JsonString("nit"))
                .put("confidence", new JsonString("high"))
                .put("body", new JsonString(FINDING_TEXT));
        JsonObject args = JsonObject.empty()
                .put("scopeId", new JsonString(scope.id()))
                .put("findings", new JsonArray(List.of(finding)));

        JsonValue stored = callTool("review_finding", args);

        assertEquals(scope.id(), str(stored, "scopeId"));
        backend.flushPendingSaves();
        Path annotations = base.resolve("drydock").resolve("annotations.json");
        assertTrue(Files.exists(annotations));
        assertTrue(Files.readString(annotations).contains(FINDING_TEXT));
    }

    /** The tool result is JSON text inside {@code result.content[0].text}. */
    private JsonValue callTool(String name, JsonObject arguments) throws Exception {
        JsonValue result = rpc("tools/call", JsonObject.empty()
                .put("name", new JsonString(name)).put("arguments", arguments));
        String text = str(array(field(result, "content")).get(0), "text");
        assertFalse(((JsonBoolean) field(result, "isError")).value(), text);
        return JsonParser.parse(text);
    }

    private JsonValue rpc(String method, JsonObject params) throws Exception {
        JsonObject body = JsonObject.empty()
                .put("jsonrpc", new JsonString("2.0"))
                .put("id", JsonNumber.of(nextRequestId++))
                .put("method", new JsonString(method))
                .put("params", params);
        HttpRequest request = HttpRequest.newBuilder(URI.create(access.endpointUrl()))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header(TOKEN_HEADER, access.token())
                .POST(HttpRequest.BodyPublishers.ofString(JsonWriter.write(body)))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return field(JsonParser.parse(response.body()), "result");
    }

    private static JsonValue field(JsonValue value, String key) {
        return ((JsonObject) value).get(key);
    }

    private static String str(JsonValue value, String key) {
        return ((JsonString) field(value, key)).value();
    }

    private static List<JsonValue> array(JsonValue value) {
        return ((JsonArray) value).elements();
    }

    private static void runGit(Path repo, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", repo.toString()));
        command.addAll(List.of(args));
        ProcessResult result = ProcessRunner.run(command, repo, Duration.ofSeconds(30));
        if (result.exitCode() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + ": " + result.stderr());
        }
    }
}
