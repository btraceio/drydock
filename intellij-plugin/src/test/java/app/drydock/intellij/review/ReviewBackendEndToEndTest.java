package app.drydock.intellij.review;

import app.drydock.agent.api.AgentContext;
import app.drydock.agent.api.AgentKind;
import app.drydock.agent.api.AgentRegistry;
import app.drydock.agent.api.McpAccess;
import app.drydock.agent.api.McpDelivery;
import app.drydock.app.ApplicationStateStore;
import app.drydock.config.UserConfig;
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
import app.drydock.git.ReviewBase;
import app.drydock.git.UnifiedDiff;
import app.drydock.git.WorktreeService;
import app.drydock.mcp.McpActivityLog;
import app.drydock.mcp.McpServer;
import app.drydock.mcp.McpSessionRegistry;
import app.drydock.mcp.McpToolRouter;
import app.drydock.mcp.RemoteAuthorizationPermit;
import app.drydock.mcp.RemoteMcpTransportPolicy;
import app.drydock.mcp.RemoteReviewContextDelegates;
import app.drydock.mcp.WorkspaceMcpSessionContext;
import app.drydock.review.AnnotationStore;
import app.drydock.review.PendingQuestions;
import app.drydock.review.ReviewDataSnapshot;
import app.drydock.review.ReviewDataSource;
import app.drydock.review.ReviewDataSource.Backend;
import app.drydock.review.ReviewDataSource.Result;
import app.drydock.review.ReviewDataSources;
import app.drydock.review.ReviewScopeRegistry;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
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
    void theAgentsReviewDiffLeavesOutUntrackedIdeFilesAsTheWorkbenchDoes() throws Exception {
        Path wt = base.resolve("fixture").resolve("wt");
        Files.createDirectories(wt.resolve(".idea"));
        Files.writeString(wt.resolve(".idea/workspace.xml"), "<project/>\n");
        Files.writeString(wt.resolve("src/B.java"), "class B {}\n");

        JsonValue scopeResult = callTool("review_scope", JsonObject.empty().put("scopeId", new JsonString(scope.id())));

        List<String> files = array(field(scopeResult, "hunks")).stream()
                .map(hunk -> str(hunk, "id").replaceFirst("^h_", "").replaceFirst("_\\d+$", ""))
                .distinct().toList();
        assertTrue(files.contains("src/B.java"), "an untracked source file is review material: " + files);
        assertFalse(files.stream().anyMatch(file -> file.startsWith(".idea/")),
                "an untracked IDE file is not: " + files);
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

    // ---- remote variants: the same router over a remote context (T56) --------

    /** Revocation fence of one remote session/generation; commits hold {@link #lock()}. */
    private static final class Fence implements RemoteAuthorizationPermit.Fence {
        private final Object lock = new Object();
        private volatile boolean revoked;

        @Override
        public Object lock() {
            return lock;
        }

        @Override
        public boolean revoked() {
            return revoked;
        }

        void revoke() {
            synchronized (lock) {
                revoked = true;
            }
        }
    }

    /** A REMOTE-only data source over a diff captured earlier; counts reads, never opens a path. */
    private static final class FakeRemoteSource implements ReviewDataSource {
        final AtomicInteger refreshes = new AtomicInteger();
        final UnifiedDiff diff;
        Runnable onRefresh = () -> { };

        FakeRemoteSource(UnifiedDiff diff) {
            this.diff = diff;
        }

        @Override
        public Backend backend() {
            return Backend.REMOTE;
        }

        @Override
        public CompletableFuture<Result<ReviewScope>> resolve(ManagedSessionId session, String selection) {
            return CompletableFuture.completedFuture(new Result.Refused<>("not used"));
        }

        @Override
        public CompletableFuture<Result<ReviewDataSnapshot>> refresh(ReviewScope scope, BooleanSupplier cancelled) {
            refreshes.incrementAndGet();
            onRefresh.run();
            return CompletableFuture.completedFuture(new Result.Ok<>(new ReviewDataSnapshot(scope.id(),
                    Backend.REMOTE, "box-1:checkout", 1, scope.diffScope(),
                    new ReviewBase("main", ReviewBase.Origin.FORKED_FROM), "b".repeat(40), "h".repeat(40),
                    "b".repeat(40), "fp", diff, Map.of(), Instant.EPOCH)));
        }

        @Override
        public CompletableFuture<Result<ReviewDataSnapshot.Content>> contents(
                ReviewDataSnapshot snapshot, String file, Side side) {
            return CompletableFuture.completedFuture(new Result.Unavailable<>("not used"));
        }

        @Override
        public CompletableFuture<Result<Excerpt>> excerpt(ManagedSessionId session, String file, int line, int context) {
            return CompletableFuture.completedFuture(new Result.Ok<>(new Excerpt(file, line, List.of("remote line"))));
        }

        @Override
        public CompletableFuture<Result<ReviewDataSnapshot>> validateCurrent(ReviewDataSnapshot snapshot) {
            return CompletableFuture.completedFuture(new Result.Ok<>(snapshot));
        }
    }

    /** One remote-serving router/server whose repository and checkout are nonexistent placeholders. */
    private final class RemoteStack implements AutoCloseable {
        final Path placeholder = base.resolve("no-such-host-path");
        final ManagedSessionId sessionId = ManagedSessionId.newId();
        final Fence fence = new Fence();
        final AnnotationStore annotations = new AnnotationStore(base.resolve("remote-annotations.json"));
        final TourStore tours = new TourStore(base.resolve("remote-tours.json"));
        final ReviewScopeRegistry scopes = new ReviewScopeRegistry();
        final McpSessionRegistry tokens = new McpSessionRegistry();
        final DiffService diffs = new DiffService();
        final GitStatusService git = new GitStatusService();
        final WorktreeService worktrees = new WorktreeService();
        final FakeRemoteSource source;
        final ReviewScope remoteScope;
        final McpServer server;
        volatile RemoteReviewContextDelegates.Evidence evidence = RemoteReviewContextDelegates.Evidence.ALIVE;

        RemoteStack(SessionStatus status) throws Exception {
            source = new FakeRemoteSource(backend.diffService()
                    .diff(scope.diffRoot(), scope.diffScope(), scope.base(), DiffService.REVIEW_CONTEXT_LINES)
                    .get(30, TimeUnit.SECONDS));
            RepositoryId repositoryId = RepositoryId.newId();
            Repository repository = new Repository(repositoryId, placeholder, "remote-repo", Instant.EPOCH,
                    Instant.EPOCH, RepositorySettings.DEFAULT);
            ManagedAgentSession session = new ManagedAgentSession.Builder(sessionId, repositoryId, "Remote",
                    AgentBinding.unlaunched(AgentKind.PI, SessionMode.WRITES),
                    SessionWorkspace.inRepository(placeholder.resolve("wt")), Instant.EPOCH).status(status).build();
            remoteScope = scopes.mint(ReviewScopeRegistry.spec(ReviewScope.Kind.WORKTREE, placeholder,
                    Optional.of(placeholder.resolve("wt")), "main", "feat", Optional.empty(), Optional.of(sessionId)));
            ReviewDataSources.Verifier verifier = new ReviewDataSources.Verifier() {
                public Optional<Backend> backendOfSession(ManagedSessionId id) {
                    return Optional.of(Backend.REMOTE);
                }

                public Optional<Backend> backendOfScope(ReviewScope s) {
                    return Optional.of(Backend.REMOTE);
                }

                public Optional<Backend> backendOfScopeId(String id) {
                    return Optional.of(Backend.REMOTE);
                }
            };
            RemoteReviewContextDelegates delegates = new RemoteReviewContextDelegates(
                    new ReviewDataSources(verifier, Map.of(Backend.REMOTE, source)),
                    id -> evidence, id -> id.equals(sessionId), System::nanoTime);
            WorkspaceMcpSessionContext context = new WorkspaceMcpSessionContext(() -> List.of(session),
                    () -> List.of(repository), annotations, tours, scopes,
                    new AgentRegistry(List.of(), new AgentContext(base, base.resolve("activity"), executor)),
                    diffs, git, worktrees, UserConfig::load,
                    (wt, prompt) -> CompletableFuture.failedFuture(new IllegalStateException("remote")),
                    (id, title) -> CompletableFuture.failedFuture(new IllegalStateException("remote")),
                    (id, conversation) -> CompletableFuture.failedFuture(new IllegalStateException("remote")),
                    (id, draft) -> CompletableFuture.failedFuture(new IllegalStateException("remote")),
                    (id, draft) -> CompletableFuture.failedFuture(new IllegalStateException("remote")),
                    new PendingQuestions(null)).remoteDelegates(delegates);
            String token = tokens.mint(sessionId, McpSessionRegistry.Spawn.FORBIDDEN);
            RemoteAuthorizationPermit.Source permits = id -> Optional.of(
                    new RemoteAuthorizationPermit(sessionId, 1, fence)).filter(p -> p.sessionId().equals(id));
            server = new McpServer(tokens, new McpToolRouter(context, tokens, McpToolRouter.REVIEW_TOOLS_AND_RENAME),
                    new McpActivityLog(), RemoteMcpTransportPolicy.LOCAL,
                    new RemoteAuthorizationPermit.Seams(permits, null));
            server.start();
            access = new McpAccess(server.endpointUrl(), token, Optional.empty());
        }

        JsonObject finding(String id) {
            return JsonObject.empty().put("id", new JsonString(id))
                    .put("anchor", JsonObject.empty().put("file", new JsonString("src/A.java"))
                            .put("startKey", new JsonString("n1")))
                    .put("severity", new JsonString("nit")).put("confidence", new JsonString("high"))
                    .put("body", new JsonString("remote-" + id));
        }

        JsonObject findingArgs(String id) {
            return JsonObject.empty().put("scopeId", new JsonString(remoteScope.id()))
                    .put("findings", new JsonArray(List.of(finding(id))));
        }

        @Override
        public void close() {
            server.close();
            annotations.close();
            tours.close();
            git.close();
            worktrees.close();
        }
    }

    @Test
    void aRemoteFindingIsReadFromTheTypedSourceAndCommittedExactlyOnce() throws Exception {
        try (RemoteStack remote = new RemoteStack(SessionStatus.RUNNING)) {
            assertFalse(Files.exists(remote.placeholder), "the remote checkout is not a host path");

            JsonValue stored = callTool("review_finding", remote.findingArgs("f_remote_1"));

            assertEquals(remote.remoteScope.id(), str(stored, "scopeId"));
            assertEquals(1, remote.annotations.forScope(remote.remoteScope.id()).size());
            assertFalse(Files.exists(remote.placeholder), "nothing materialised a host path");
        }
    }

    @Test
    void aRevokedGenerationCannotCommitAFindingEvenWhenItWasCurrentAtDispatch() throws Exception {
        try (RemoteStack remote = new RemoteStack(SessionStatus.RUNNING)) {
            // Revocation lands after dispatch checked the permit and while the data is read.
            remote.source.onRefresh = remote.fence::revoke;

            JsonObject anchor = JsonObject.empty().put("file", new JsonString("src/A.java"))
                    .put("startKey", new JsonString("n1")).put("endKey", new JsonString("n4"));
            JsonObject step = JsonObject.empty().put("id", new JsonString("s1")).put("title", new JsonString("t"))
                    .put("narrative", new JsonString(TOUR_TEXT)).put("anchors", new JsonArray(List.of(anchor)))
                    .put("checks", new JsonArray(List.of(traceCheck("c1", "c1b"))));

            JsonValue response = rpcResponse("tools/call", JsonObject.empty().put("name", new JsonString("review_tour"))
                    .put("arguments", JsonObject.empty().put("scopeId", new JsonString(remote.remoteScope.id()))
                            .put("steps", new JsonArray(List.of(step)))));

            assertTrue(remote.source.refreshes.get() >= 1, "the diff came from the remote source");
            assertTrue(field(response, "result") == null && field(response, "error") != null,
                    "the stale commit is refused, not reported as success: " + response);
            assertTrue(remote.tours.forScope(remote.remoteScope.id()).isEmpty(), "nothing was committed");
        }
    }

    @Test
    void aForeignAndAnUnknownScopeAreRefusedIdentically() throws Exception {
        try (RemoteStack remote = new RemoteStack(SessionStatus.RUNNING)) {
            ReviewScope foreign = remote.scopes.mint(ReviewScopeRegistry.spec(ReviewScope.Kind.WORKTREE,
                    remote.placeholder, Optional.of(remote.placeholder.resolve("other")), "main", "other",
                    Optional.empty(), Optional.of(ManagedSessionId.newId())));

            String foreignRefusal = callToolRefused("review_scope",
                    JsonObject.empty().put("scopeId", new JsonString(foreign.id())));
            String unknownRefusal = callToolRefused("review_scope",
                    JsonObject.empty().put("scopeId", new JsonString(foreign.id() + "x")));

            assertEquals(unknownRefusal.replace(foreign.id() + "x", "ID"), foreignRefusal.replace(foreign.id(), "ID"));
            assertEquals(0, remote.source.refreshes.get(), "a refused scope reads nothing");
        }
    }

    @Test
    void aDetachedEnrolledAgentKeepsItsToolsButConfirmedDeathFailsClosed() throws Exception {
        // The catalog says IDLE (no attached console); the protocol evidence says ALIVE.
        try (RemoteStack remote = new RemoteStack(SessionStatus.IDLE)) {
            JsonValue scopeResult = callTool("review_scope",
                    JsonObject.empty().put("scopeId", new JsonString(remote.remoteScope.id())));
            assertFalse(array(field(scopeResult, "hunks")).isEmpty());
            assertTrue(remote.source.refreshes.get() >= 1, "served by the typed source, not a host path");
        }
        try (RemoteStack dead = new RemoteStack(SessionStatus.RUNNING)) {
            dead.evidence = RemoteReviewContextDelegates.Evidence.DEAD;
            String refusal = callToolRefused("review_scope",
                    JsonObject.empty().put("scopeId", new JsonString(dead.remoteScope.id())));
            assertTrue(refusal.contains("no longer running"), refusal);
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
        return field(rpcResponse(method, params), "result");
    }

    private JsonValue rpcResponse(String method, JsonObject params) throws Exception {
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
        return JsonParser.parse(response.body());
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
