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
import app.drydock.git.DiffService;
import app.drydock.git.GitStatusService;
import app.drydock.git.UnifiedDiff;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.review.HunkIds;
import app.drydock.review.ReviewScope;
import app.drydock.review.tour.TourRecord;
import app.drydock.review.tour.TourStore;
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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The review stack end to end: a real git worktree, the real backend and
 * its loopback MCP server, driven over HTTP with the session's own token.
 */
class ReviewBackendEndToEndTest {

    private static final String TOKEN_HEADER = "X-Drydock-Session-Token";
    private static final String FINDING_TEXT = "e2e-finding-marker-text";
    private static final String TOUR_TEXT = "e2e-tour-marker-text";

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
    void toolsListReturnsTheReviewToolsPlusSessionRename() throws Exception {
        JsonValue result = rpc("tools/list", JsonObject.empty());

        Set<String> names = new HashSet<>();
        for (JsonValue tool : array(field(result, "tools"))) {
            names.add(str(tool, "name"));
        }
        // session_rename rides the review set: an agent reviewing here may
        // relabel its own session, as the desktop app's MCP allows.
        assertEquals(Set.of("review_comments", "review_reply", "review_scope", "review_tour",
                "review_check", "review_finding", "review_answer", "review_ask_answer",
                "review_state", "review_recheck", "session_rename"), names);
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

    @Test
    void closeFlushesAPostedTourToDisk() throws Exception {
        JsonObject anchor = JsonObject.empty()
                .put("file", new JsonString("src/A.java"))
                .put("startKey", new JsonString("n1"))
                .put("endKey", new JsonString("n4"));
        JsonObject step = JsonObject.empty()
                .put("id", new JsonString("s1"))
                .put("title", new JsonString("Uppercase two, add four"))
                .put("narrative", new JsonString(TOUR_TEXT))
                .put("anchors", new JsonArray(List.of(anchor)))
                .put("checks", new JsonArray(List.of(traceCheck("c1", "c1b"))));
        callTool("review_tour", JsonObject.empty()
                .put("scopeId", new JsonString(scope.id()))
                .put("steps", new JsonArray(List.of(step))));

        TourRecord posted = backend.tourStore().forScope(scope.id()).orElseThrow();

        // No flushPendingSaves: close alone must write the tour.
        backend.close();

        // The background save is usually done by now even without a flush, so
        // the file check alone cannot tell; a rejected write shows close()
        // closed the store, and TourStore.close() flushes before it shuts down.
        assertThrows(RejectedExecutionException.class, () -> backend.tourStore().put(posted));
        Path tours = base.resolve("drydock").resolve("review-tours.json");
        assertTrue(Files.exists(tours));
        try (TourStore reopened = new TourStore(tours)) {
            assertTrue(reopened.forScope(scope.id()).isPresent());
        }
        assertTrue(Files.readString(tours).contains(TOUR_TEXT));
    }

    /** AC10: an agent's {@code review_recheck} finds the verdict the plugin's mark-reviewed command wrote. */
    @Test
    void anAgentsRecheckFindsAPluginMarkedVerdictWhereBeforeItRefused() throws Exception {
        // The mark-reviewed command over the backend's own diff service and
        // annotation store; the git status service dates the verdict with
        // real commits. T13 injects these from the backend in production.
        try (GitStatusService git = new GitStatusService()) {
            ReviewVerdictCommands commands =
                    new ReviewVerdictCommands(backend.diffService(), git, backend.annotationStore(), executor);
            UnifiedDiff.FileDiff file = backend.diffService()
                    .diff(scope.diffRoot(), scope.diffScope(), scope.base(), DiffService.REVIEW_CONTEXT_LINES)
                    .get(30, TimeUnit.SECONDS).files().stream()
                    .filter(candidate -> candidate.path().equals("src/A.java"))
                    .findFirst().orElseThrow();
            String hunkId = HunkIds.hunkId("src/A.java", 0);

            // Before the mark: the hunk carries no verdict, so a recheck of it
            // is refused -- the wire contract an agent sees.
            String before = callToolRefused("review_recheck", recheckArgs(hunkId));
            assertTrue(before.contains("no verdict"), before);

            assertEquals(new ReviewVerdictCommands.Result.Ok(),
                    commands.markReviewed(scope, file).get(30, TimeUnit.SECONDS));

            // After the mark: the same call over the same wire finds the
            // verdict and records the assessment.
            JsonValue stored = callTool("review_recheck", recheckArgs(hunkId));
            assertEquals(scope.id(), str(stored, "scopeId"));
            assertEquals(1, ((JsonNumber) field(stored, "assessments")).asInt());
        }
    }

    private static JsonObject traceCheck(String id, String alternateId) {
        JsonObject alternate = traceCheckBody(alternateId, "Which line was added at the end?", "four");
        return traceCheckBody(id, "What does line two read now?", "TWO")
                .put("alternates", new JsonArray(List.of(alternate)));
    }

    private static JsonObject traceCheckBody(String id, String prompt, String right) {
        return JsonObject.empty()
                .put("id", new JsonString(id))
                .put("kind", new JsonString("trace"))
                .put("prompt", new JsonString(prompt))
                .put("choices", new JsonArray(List.of(
                        JsonObject.empty().put("text", new JsonString(right)),
                        JsonObject.empty().put("text", new JsonString("unchanged")))))
                .put("answer", JsonNumber.of(0))
                .put("explanation", new JsonString("The diff shows it."));
    }

    /** The tool result is JSON text inside {@code result.content[0].text}. */
    private JsonValue callTool(String name, JsonObject arguments) throws Exception {
        JsonValue result = rpc("tools/call", JsonObject.empty()
                .put("name", new JsonString(name)).put("arguments", arguments));
        String text = str(array(field(result, "content")).get(0), "text");
        assertFalse(((JsonBoolean) field(result, "isError")).value(), text);
        return JsonParser.parse(text);
    }

    /** A tool call that is refused: asserts the refusal and returns its message. */
    private String callToolRefused(String name, JsonObject arguments) throws Exception {
        JsonValue result = rpc("tools/call", JsonObject.empty()
                .put("name", new JsonString(name)).put("arguments", arguments));
        assertTrue(((JsonBoolean) field(result, "isError")).value(), "the call should have been refused");
        return str(array(field(result, "content")).get(0), "text");
    }

    /** One {@code review_recheck} assessment of {@code hunkId}, unaffected. */
    private JsonObject recheckArgs(String hunkId) {
        JsonObject assessment = JsonObject.empty()
                .put("hunkId", new JsonString(hunkId))
                .put("affected", new JsonBoolean(false))
                .put("why", new JsonString("re-checked"));
        return JsonObject.empty()
                .put("scopeId", new JsonString(scope.id()))
                .put("assessments", new JsonArray(List.of(assessment)));
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
