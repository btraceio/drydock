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
import app.drydock.git.UnifiedDiff;
import app.drydock.process.ProcessResult;
import app.drydock.process.ProcessRunner;
import app.drydock.review.HunkDigest;
import app.drydock.review.HunkIds;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AC4, end to end: the Review tab's snapshot and the agent-facing {@code
 * review_scope} are two views of ONE diff. A real git repository with a
 * linked worktree, a real {@link ReviewBackend} (started, serving its
 * loopback MCP server), and the tab snapshot assembled from the exact diff
 * call {@code review_scope} itself makes -- so a human and an agent can
 * never be shown different code under the same scope id.
 *
 * <p>The fixture carries the rows AC4's comparison has to subtract: an
 * untracked {@code .idea/workspace.xml} and an untracked {@code *.iml}
 * (both in the tool's file list, both hidden by the snapshot) next to a
 * tracked, modified {@code .idea/codeStyles/Project.xml} (in both lists),
 * so the equality proves the filter drops exactly the untracked IDE noise
 * and nothing else. No {@code sections}/{@code impact} includes are
 * requested: those parse every changed file and would load tree-sitter
 * natives into this JVM, which this page does not need.</p>
 */
class ReviewTabEndToEndTest {

    private static final String TOKEN_HEADER = "X-Drydock-Session-Token";

    /** The fixture's untracked IDE noise rows: what the snapshot must drop. */
    private static final Set<String> UNTRACKED_NOISE = Set.of(".idea/workspace.xml", "drydock-scratch.iml");

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
        // Tracked IDE metadata, so the fixture separates "untracked .idea
        // noise" (hidden) from "tracked, modified .idea content" (review
        // material) -- the filter AC4's subtraction has to be exact about.
        Files.createDirectories(repo.resolve(".idea").resolve("codeStyles"));
        Files.writeString(repo.resolve(".idea").resolve("codeStyles").resolve("Project.xml"),
                "<component name=\"ProjectCodeStyleConfiguration\">\n"
                        + "  <state>\n"
                        + "    <option name=\"USE_PER_PROJECT_SETTINGS\" value=\"false\" />\n"
                        + "  </state>\n"
                        + "</component>\n");
        runGit(repo, "add", "src/A.java", ".idea/codeStyles/Project.xml");
        runGit(repo, "commit", "-m", "initial commit");

        Path wt = base.resolve("fixture").resolve("wt");
        runGit(repo, "worktree", "add", "-b", "feat", wt.toString());
        Files.writeString(wt.resolve("src/A.java"), "one\nTWO\nthree\nfour\n");
        Files.writeString(wt.resolve(".idea").resolve("codeStyles").resolve("Project.xml"),
                "<component name=\"ProjectCodeStyleConfiguration\">\n"
                        + "  <state>\n"
                        + "    <option name=\"USE_PER_PROJECT_SETTINGS\" value=\"true\" />\n"
                        + "  </state>\n"
                        + "</component>\n");
        runGit(wt, "add", "src/A.java", ".idea/codeStyles/Project.xml");
        runGit(wt, "commit", "-m", "change a line and flip a code-style option");
        // Untracked, never added: IDE noise rows the tool reports and the
        // snapshot must hide.
        Files.createDirectories(wt.resolve(".idea"));
        Files.writeString(wt.resolve(".idea").resolve("workspace.xml"),
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<project version=\"4\" />\n");
        Files.writeString(wt.resolve("drydock-scratch.iml"), "<module type=\"JAVA_MODULE\" />\n");

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
    void snapshotFileListHunkIdsAndDigestsEqualReviewScope() throws Exception {
        // The exact call review_scope makes (WorkspaceMcpSessionContext
        // .reviewDiff): the backend's own DiffService, the scope's
        // diffRoot/diffScope/base, and Review's context window.
        UnifiedDiff diff = backend.diffService()
                .diff(scope.diffRoot(), scope.diffScope(), scope.base(),
                        DiffService.REVIEW_CONTEXT_LINES)
                .get(30, TimeUnit.SECONDS);

        ReviewTabModel.Snapshot snapshot = ReviewTabModel.snapshot(new ReviewTabModel.Inputs(
                diff, List.of(), List.of(), Optional.empty()));

        JsonValue result = callTool("review_scope",
                JsonObject.empty().put("scopeId", new JsonString(scope.id())));

        assertEquals(scope.id(), str(field(result, "scope"), "id"), "same scope id");
        // The whole fixture diff fits one page, so this one call carries
        // every hunk the agent can read -- comparing against it is complete.
        assertTrue(field(result, "cursor") instanceof JsonValue.JsonNull,
                "the fixture diff must fit a single review_scope page");

        List<String> toolFiles = new ArrayList<>();
        for (JsonValue file : array(field(result, "files"))) {
            toolFiles.add(str(file, "path"));
        }
        // The fixture exercises both sides of the filter before the
        // equality is worth anything: the noise rows are in the tool's list,
        // and the tracked .idea edit is review material.
        assertTrue(toolFiles.contains(".idea/workspace.xml"), toolFiles.toString());
        assertTrue(toolFiles.contains("drydock-scratch.iml"), toolFiles.toString());
        assertTrue(toolFiles.contains(".idea/codeStyles/Project.xml"), toolFiles.toString());

        List<String> snapshotFiles = snapshot.files().stream()
                .map(ReviewTabModel.FileRow::path).toList();
        assertEquals(toolFiles.stream().filter(path -> !UNTRACKED_NOISE.contains(path))
                        .sorted().toList(),
                snapshotFiles,
                "the snapshot's files are the tool's files minus the untracked IDE noise");

        // Per-file hunk ids: the wire's flatten order is file by file, hunk
        // by hunk, which is exactly the snapshot's per-row list order.
        Map<String, List<String>> toolHunkIds = new LinkedHashMap<>();
        for (JsonValue hunk : array(field(result, "hunks"))) {
            toolHunkIds.computeIfAbsent(str(hunk, "file"), file -> new ArrayList<>())
                    .add(str(hunk, "id"));
        }
        // The noise rows carry hunks the tool happily reports; the snapshot
        // dropped the whole files, so their ids must not leak in anywhere.
        assertTrue(toolHunkIds.containsKey(".idea/workspace.xml"), toolHunkIds.toString());
        assertTrue(toolHunkIds.containsKey("drydock-scratch.iml"), toolHunkIds.toString());

        for (ReviewTabModel.FileRow row : snapshot.files()) {
            List<String> toolIds = toolHunkIds.get(row.path());
            assertEquals(toolIds, row.hunkIds(), "hunk ids for " + row.path());

            // And per hunk: the positional id round-trips to the same
            // (file, index), and the digest the snapshot carries -- the key
            // an approval is stored under (AC9) and an agent's recheck
            // resolves a hunkId to -- equals HunkDigest.of over the very
            // hunk that id addresses in this one diff.
            UnifiedDiff.FileDiff file = diffFile(diff, row.path());
            assertEquals(file.hunks().size(), row.hunkIds().size(), "hunk count for " + row.path());
            for (int i = 0; i < file.hunks().size(); i++) {
                HunkIds.Anchor anchor = HunkIds.parseHunkId(row.hunkIds().get(i)).orElseThrow();
                assertEquals(row.path(), anchor.file(), "hunk id " + row.hunkIds().get(i));
                assertEquals(i, anchor.hunkIndex(), "hunk id " + row.hunkIds().get(i));
                assertEquals(HunkDigest.of(row.path(), file.hunks().get(i)), row.hunkDigests().get(i),
                        "digest of hunk " + row.hunkIds().get(i));
            }
        }
    }

    private static UnifiedDiff.FileDiff diffFile(UnifiedDiff diff, String path) {
        return diff.files().stream()
                .filter(file -> file.path().equals(path))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no such file in the diff: " + path));
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
