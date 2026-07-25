package app.drydock.mcp;

import app.drydock.domain.ManagedSessionId;
import app.drydock.git.DiffScope;
import app.drydock.mcp.McpSessionRegistry.Spawn;
import app.drydock.review.AnnotationStatus;
import app.drydock.review.ReviewAnnotation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static app.drydock.mcp.JsonPeek.args;
import static app.drydock.mcp.JsonPeek.argsWithFlag;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpToolRouterReplyTest {

    private final ManagedSessionId caller = ManagedSessionId.newId();
    private FakeMcpSessionContext context;
    private McpToolRouter router;
    private ReviewAnnotation open;

    @BeforeEach
    void setUp() {
        context = new FakeMcpSessionContext();
        context.repositoryRoot = Optional.of(Path.of("/repos/drydock"));
        context.worktreePath = Optional.of(Path.of("/repos/drydock"));
        McpSessionRegistry registry = new McpSessionRegistry();
        registry.mint(caller, Spawn.ALLOWED);
        router = new McpToolRouter(context, registry);
        open = ReviewAnnotation.create(caller, DiffScope.BASE, "src/Main.java", "n42", "n42",
                new ReviewAnnotation.Message("You", Instant.parse("2026-07-25T10:00:00Z"), "needs a null check"));
        context.annotations.add(open);
    }

    private ReviewAnnotation reloaded() {
        return context.annotations.stream()
                .filter(annotation -> annotation.id().equals(open.id()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void aReplyAppendsAClaudeAuthoredNoteAndLeavesTheStatusAlone() throws Exception {
        router.call(caller, "review_reply", args("id", open.id(), "note", "Looking at this now."));

        ReviewAnnotation updated = reloaded();
        assertEquals(AnnotationStatus.OPEN, updated.status(), "a bare reply must not claim a fix");
        assertEquals(2, updated.thread().size());
        assertEquals("Claude", updated.thread().get(1).author());
        assertEquals("Looking at this now.", updated.thread().get(1).text());
    }

    @Test
    void addressedTrueSetsTheStatusAndStillAppendsTheNote() throws Exception {
        router.call(caller, "review_reply",
                argsWithFlag("addressed", true, "id", open.id(), "note", "Added the null check in loadConfig()."));

        ReviewAnnotation updated = reloaded();
        assertEquals(AnnotationStatus.ADDRESSED, updated.status());
        assertEquals("Added the null check in loadConfig().", updated.thread().get(1).text());
    }

    @Test
    void theHumansOriginalMessageIsPreserved() throws Exception {
        router.call(caller, "review_reply", args("id", open.id(), "note", "done"));

        assertEquals("needs a null check", reloaded().thread().get(0).text());
        assertEquals("You", reloaded().thread().get(0).author());
    }

    @Test
    void anUnknownAnnotationIdIsRejected() {
        McpToolException failure = assertThrows(McpToolException.class,
                () -> router.call(caller, "review_reply", args("id", "no-such-id", "note", "done")));

        assertTrue(failure.getMessage().contains("no-such-id"), failure.getMessage());
    }

    @Test
    void anotherSessionsAnnotationIsNotAddressable() {
        ManagedSessionId other = ManagedSessionId.newId();
        ReviewAnnotation foreign = ReviewAnnotation.create(other, DiffScope.BASE, "other.java", "n1", "n1",
                new ReviewAnnotation.Message("You", Instant.EPOCH, "not yours"));
        context.annotations.add(foreign);

        McpToolException failure = assertThrows(McpToolException.class,
                () -> router.call(caller, "review_reply", args("id", foreign.id(), "note", "done")));

        assertTrue(failure.getMessage().contains(foreign.id()), failure.getMessage());
        assertEquals(AnnotationStatus.OPEN, context.annotations.stream()
                .filter(annotation -> annotation.id().equals(foreign.id()))
                .findFirst().orElseThrow().status());
    }

    @Test
    void aMissingNoteIsRejectedBecauseTheThreadWouldSayNothing() {
        assertThrows(McpToolException.class,
                () -> router.call(caller, "review_reply", args("id", open.id())));
    }

    @Test
    void aBlankNoteIsRejected() {
        assertThrows(McpToolException.class,
                () -> router.call(caller, "review_reply", args("id", open.id(), "note", "   ")));
    }

    @Test
    void anAlreadyResolvedThreadIsNotTouched() {
        context.updateAnnotation(open.withStatus(AnnotationStatus.RESOLVED));

        McpToolException failure = assertThrows(McpToolException.class,
                () -> router.call(caller, "review_reply", args("id", open.id(), "note", "done")));

        assertTrue(failure.getMessage().contains("RESOLVED"), failure.getMessage());
        assertEquals(AnnotationStatus.RESOLVED, reloaded().status());
        assertEquals(1, reloaded().thread().size(), "not even the note may be appended");
    }

    @Test
    void aLegacyFixedThreadIsNotTouched() {
        context.updateAnnotation(open.withStatus(AnnotationStatus.FIXED));

        assertThrows(McpToolException.class,
                () -> router.call(caller, "review_reply", args("id", open.id(), "note", "done")));
    }

    @Test
    void replyingTwiceIsAllowedAndAppendsBothNotes() throws Exception {
        router.call(caller, "review_reply",
                argsWithFlag("addressed", true, "id", open.id(), "note", "first attempt"));
        router.call(caller, "review_reply",
                argsWithFlag("addressed", true, "id", open.id(), "note", "second attempt"));

        List<ReviewAnnotation.Message> thread = reloaded().thread();
        assertEquals(3, thread.size());
        assertEquals("second attempt", thread.get(2).text());
        assertEquals(AnnotationStatus.ADDRESSED, reloaded().status());
    }

    @Test
    void aSentThreadCanBeAddressed() throws Exception {
        context.updateAnnotation(open.withStatus(AnnotationStatus.SENT));

        router.call(caller, "review_reply",
                argsWithFlag("addressed", true, "id", open.id(), "note", "done"));

        assertEquals(AnnotationStatus.ADDRESSED, reloaded().status());
    }
}
