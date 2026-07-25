package app.drydock.mcp;

import app.drydock.domain.ManagedSessionId;
import app.drydock.git.DiffScope;
import app.drydock.mcp.McpSessionRegistry.Spawn;
import app.drydock.review.AnnotationStatus;
import app.drydock.review.ReviewAnnotation;
import app.drydock.state.json.JsonValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static app.drydock.mcp.JsonPeek.array;
import static app.drydock.mcp.JsonPeek.args;
import static app.drydock.mcp.JsonPeek.bool;
import static app.drydock.mcp.JsonPeek.noArgs;
import static app.drydock.mcp.JsonPeek.num;
import static app.drydock.mcp.JsonPeek.str;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpToolRouterReadTest {

    private final ManagedSessionId caller = ManagedSessionId.newId();
    private FakeMcpSessionContext context;
    private McpSessionRegistry registry;
    private McpToolRouter router;

    @BeforeEach
    void setUp() {
        context = new FakeMcpSessionContext();
        context.repositoryRoot = Optional.of(Path.of("/repos/drydock"));
        context.worktreePath = Optional.of(Path.of("/repos/drydock"));
        registry = new McpSessionRegistry();
        registry.mint(caller, Spawn.ALLOWED);
        router = new McpToolRouter(context, registry);
    }

    private ReviewAnnotation annotation(String file, String key, AnnotationStatus status) {
        ReviewAnnotation created = ReviewAnnotation.create(caller, DiffScope.BASE, file, key, key,
                new ReviewAnnotation.Message("You", Instant.parse("2026-07-25T10:00:00Z"), "needs a null check"));
        return created.withStatus(status);
    }

    @Test
    void toolDescriptorsCoverEverySupportedTool() {
        List<String> names = router.toolDescriptors().stream()
                .map(descriptor -> str(descriptor, "name"))
                .toList();

        assertEquals(List.of("review_comments", "review_reply", "worktree_create",
                "session_start", "repos_list", "sessions_list"), names);
    }

    @Test
    void everyToolDescriptorCarriesADescriptionAndAnObjectSchema() {
        for (JsonValue descriptor : router.toolDescriptors()) {
            assertEquals("object", str(JsonPeek.field(descriptor, "inputSchema"), "type"),
                    "missing inputSchema on " + str(descriptor, "name"));
            assertTrue(str(descriptor, "description").length() > 0,
                    "missing description on " + str(descriptor, "name"));
        }
    }

    @Test
    void reviewCommentsReportsOpenThreadsWithDecodedLines() throws Exception {
        context.annotations.add(annotation("src/Main.java", "n42", AnnotationStatus.OPEN));
        context.excerpts.put("src/Main.java:42", "  41: prev\n> 42: return cfg.value();\n  43: next");

        JsonValue result = router.call(caller, "review_comments", noArgs());

        List<JsonValue> comments = array(result, "comments");
        assertEquals(1, comments.size());
        assertEquals("src/Main.java", str(comments.get(0), "file"));
        assertEquals(42, num(comments.get(0), "line"));
        assertEquals(false, bool(comments.get(0), "deleted_line"));
        assertEquals("OPEN", str(comments.get(0), "status"));
        assertEquals("  41: prev\n> 42: return cfg.value();\n  43: next", str(comments.get(0), "excerpt"));
        assertEquals("needs a null check", str(array(comments.get(0), "thread").get(0), "text"));
        assertEquals("You", str(array(comments.get(0), "thread").get(0), "author"));
    }

    @Test
    void reviewCommentsCarriesTheBaseBranchSoTheDiffIsReproducible() throws Exception {
        context.baseBranch = Optional.of("develop");
        context.annotations.add(annotation("src/Main.java", "n1", AnnotationStatus.OPEN));

        JsonValue result = router.call(caller, "review_comments", noArgs());

        assertEquals("develop", str(result, "base_branch"));
    }

    @Test
    void anAbsentBaseBranchIsJsonNullNotAMissingField() throws Exception {
        context.baseBranch = Optional.empty();
        context.annotations.add(annotation("src/Main.java", "n1", AnnotationStatus.OPEN));

        JsonValue result = router.call(caller, "review_comments", noArgs());

        assertEquals(JsonValue.JsonNull.INSTANCE, JsonPeek.field(result, "base_branch"));
    }

    @Test
    void aDeletedLineHasNoExcerptAndSaysHowToSeeIt() throws Exception {
        context.annotations.add(annotation("src/Gone.java", "o17", AnnotationStatus.OPEN));

        JsonValue result = router.call(caller, "review_comments", noArgs());

        JsonValue comment = array(result, "comments").get(0);
        assertEquals(17, num(comment, "line"));
        assertEquals(true, bool(comment, "deleted_line"));
        assertEquals(JsonValue.JsonNull.INSTANCE, JsonPeek.field(comment, "excerpt"));
        assertTrue(str(comment, "hint").contains("git show"),
                "a deleted line is not in the working tree; say how to see it: " + str(comment, "hint"));
    }

    @Test
    void aMissingExcerptIsJsonNullNotAnError() throws Exception {
        // The file may have been deleted, or the line may be past its end.
        context.annotations.add(annotation("src/Main.java", "n999", AnnotationStatus.OPEN));

        JsonValue result = router.call(caller, "review_comments", noArgs());

        assertEquals(JsonValue.JsonNull.INSTANCE,
                JsonPeek.field(array(result, "comments").get(0), "excerpt"));
    }

    @Test
    void reviewCommentsIncludesSentButNotResolvedAddressedOrFixed() throws Exception {
        context.annotations.add(annotation("a.java", "n1", AnnotationStatus.OPEN));
        context.annotations.add(annotation("b.java", "n2", AnnotationStatus.SENT));
        context.annotations.add(annotation("c.java", "n3", AnnotationStatus.RESOLVED));
        context.annotations.add(annotation("d.java", "n4", AnnotationStatus.ADDRESSED));
        context.annotations.add(annotation("e.java", "n5", AnnotationStatus.FIXED));

        JsonValue result = router.call(caller, "review_comments", noArgs());

        List<String> files = array(result, "comments").stream()
                .map(comment -> str(comment, "file"))
                .toList();
        assertEquals(List.of("a.java", "b.java"), files);
    }

    @Test
    void reviewCommentsFiltersByScopeWhenAsked() throws Exception {
        ReviewAnnotation working = ReviewAnnotation.create(caller, DiffScope.WORKING_TREE, "w.java", "n1", "n1",
                new ReviewAnnotation.Message("You", Instant.EPOCH, "uncommitted"));
        context.annotations.add(annotation("base.java", "n1", AnnotationStatus.OPEN));
        context.annotations.add(working);

        JsonValue result = router.call(caller, "review_comments", args("scope", "WORKING_TREE"));

        List<JsonValue> comments = array(result, "comments");
        assertEquals(1, comments.size());
        assertEquals("w.java", str(comments.get(0), "file"));
    }

    @Test
    void reviewCommentsRejectsAnUnknownScope() {
        McpToolException failure = assertThrows(McpToolException.class,
                () -> router.call(caller, "review_comments", args("scope", "SIDEWAYS")));

        assertTrue(failure.getMessage().contains("WORKING_TREE"),
                "should list the valid scopes: " + failure.getMessage());
    }

    @Test
    void reviewCommentsSkipsAnnotationsWithUndecodableKeysRatherThanFailingTheCall() throws Exception {
        context.annotations.add(annotation("good.java", "n7", AnnotationStatus.OPEN));
        context.annotations.add(annotation("bad.java", "zzz", AnnotationStatus.OPEN));

        JsonValue result = router.call(caller, "review_comments", noArgs());

        List<JsonValue> comments = array(result, "comments");
        assertEquals(1, comments.size());
        assertEquals("good.java", str(comments.get(0), "file"));
    }

    @Test
    void reposListReportsLocalRepositoriesWithGitState() throws Exception {
        context.repositories.add(new McpSessionContext.RepoSummary("drydock", Path.of("/repos/drydock"),
                Optional.of("feat/mcp"), Optional.of(true), Optional.of(2), Optional.of(0), false));

        JsonValue result = router.call(caller, "repos_list", noArgs());

        JsonValue repo = array(result, "repositories").get(0);
        assertEquals("drydock", str(repo, "name"));
        assertEquals("feat/mcp", str(repo, "branch"));
        assertEquals(true, bool(repo, "dirty"));
        assertEquals(2, num(repo, "ahead"));
        assertEquals(false, bool(repo, "remote"));
    }

    @Test
    void reposListReportsRemoteRepositoriesWithoutGitState() throws Exception {
        // Probing a remote target runs ssh with its own timeout, and
        // GitStatusService has no cache; one tool call must not open N
        // ssh connections.
        context.repositories.add(new McpSessionContext.RepoSummary("far", Path.of("/srv/far"),
                Optional.of("main"), Optional.empty(), Optional.empty(), Optional.empty(), true));

        JsonValue result = router.call(caller, "repos_list", noArgs());

        JsonValue repo = array(result, "repositories").get(0);
        assertEquals(true, bool(repo, "remote"));
        assertEquals(JsonValue.JsonNull.INSTANCE, JsonPeek.field(repo, "dirty"));
        assertEquals(JsonValue.JsonNull.INSTANCE, JsonPeek.field(repo, "ahead"));
    }

    @Test
    void anAbsentBranchIsJsonNullNotAMissingField() throws Exception {
        context.repositories.add(new McpSessionContext.RepoSummary("detached", Path.of("/repos/detached"),
                Optional.empty(), Optional.of(false), Optional.of(0), Optional.of(0), false));

        JsonValue result = router.call(caller, "repos_list", noArgs());

        assertEquals(JsonValue.JsonNull.INSTANCE,
                JsonPeek.field(array(result, "repositories").get(0), "branch"));
    }

    @Test
    void sessionsListFlagsTheCallersOwnSession() throws Exception {
        ManagedSessionId other = ManagedSessionId.newId();
        context.sessions.add(new McpSessionContext.SessionSummary(caller, "mine", "drydock",
                Optional.of("feat/mcp"), Path.of("/repos/drydock"), "RUNNING", false));
        context.sessions.add(new McpSessionContext.SessionSummary(other, "theirs", "consumer",
                Optional.of("main"), Path.of("/repos/consumer"), "INACTIVE", false));

        JsonValue result = router.call(caller, "sessions_list", noArgs());

        List<JsonValue> sessions = array(result, "sessions");
        assertEquals(true, bool(sessions.get(0), "is_caller"));
        assertEquals(false, bool(sessions.get(1), "is_caller"));
        assertEquals("RUNNING", str(sessions.get(0), "status"));
    }

    @Test
    void anEndedSessionFailsWithSessionGoneNotANullPointer() {
        context.repositoryRoot = Optional.empty();
        context.worktreePath = Optional.empty();

        McpToolException failure = assertThrows(McpToolException.class,
                () -> router.call(caller, "review_comments", noArgs()));

        assertTrue(failure.getMessage().toLowerCase().contains("session"), failure.getMessage());
    }

    @Test
    void anUnknownToolNameIsRejected() {
        McpToolException failure = assertThrows(McpToolException.class,
                () -> router.call(caller, "rm_minus_rf", noArgs()));

        assertTrue(failure.getMessage().contains("rm_minus_rf"), failure.getMessage());
    }
}
