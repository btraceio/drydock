package app.drydock.mcp;

import app.drydock.domain.ManagedSessionId;
import app.drydock.git.DiffScope;
import app.drydock.mcp.McpSessionRegistry.Spawn;
import app.drydock.review.AnnotationStatus;
import app.drydock.review.ReviewAnnotation;
import app.drydock.review.Confidence;
import app.drydock.review.Severity;
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
        context.grant(caller, SCOPE);
        open = ReviewAnnotation.human(SCOPE, "src/Main.java", "n42", "n42",
                new ReviewAnnotation.Message("You", Instant.parse("2026-07-25T10:00:00Z"), "needs a null check"));
        context.annotations.add(open);
    }

    /** The one review scope this caller may address. */
    private static final String SCOPE = "rs_test";

    private ReviewAnnotation reloaded() {
        return context.annotations.stream()
                .filter(annotation -> annotation.key().equals(open.key()))
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
        ReviewAnnotation foreign = ReviewAnnotation.human("rs_theirs", "other.java", "n1", "n1",
                new ReviewAnnotation.Message("You", Instant.EPOCH, "not yours"));
        context.annotations.add(foreign);

        McpToolException failure = assertThrows(McpToolException.class,
                () -> router.call(caller, "review_reply", args("id", foreign.id(), "note", "done")));

        assertTrue(failure.getMessage().contains(foreign.id()), failure.getMessage());
        assertEquals(AnnotationStatus.OPEN, context.annotations.stream()
                .filter(annotation -> annotation.key().equals(foreign.key()))
                .findFirst().orElseThrow().status());
    }

    /**
     * The keying bug, at the MCP surface: the same finding id in two scopes
     * the caller can reach is ambiguous, and must be refused rather than
     * resolved by picking one.
     */
    @Test
    void anIdPresentInTwoAddressableScopesIsRefusedRatherThanGuessed() {
        context.grant(caller, "rs_second");
        context.annotations.add(new ReviewAnnotation("rs_second", open.id(), Optional.empty(),
                "other.java", "n1", "n1", Severity.QUESTION, Confidence.HIGH, Optional.empty(),
                "You", Instant.EPOCH, List.of(), Optional.empty(), Optional.empty(), List.of(),
                List.of(), Optional.empty(), AnnotationStatus.OPEN, Optional.empty(), false));

        McpToolException failure = assertThrows(McpToolException.class,
                () -> router.call(caller, "review_reply", args("id", open.id(), "note", "done")));

        assertTrue(failure.getMessage().contains("more than one review scope"), failure.getMessage());
        assertEquals(1, reloaded().thread().size(), "nothing may be written while it is ambiguous");
    }

    @Test
    void anAmbiguousIdIsResolvedByPassingTheScope() throws Exception {
        context.grant(caller, "rs_second");
        context.annotations.add(new ReviewAnnotation("rs_second", open.id(), Optional.empty(),
                "other.java", "n1", "n1", Severity.QUESTION, Confidence.HIGH, Optional.empty(),
                "You", Instant.EPOCH, List.of(), Optional.empty(), Optional.empty(), List.of(),
                List.of(), Optional.empty(), AnnotationStatus.OPEN, Optional.empty(), false));

        router.call(caller, "review_reply",
                args("id", open.id(), "scopeId", SCOPE, "note", "done"));

        assertEquals(2, reloaded().thread().size());
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
        context.store(open.withStatus(AnnotationStatus.RESOLVED));

        McpToolException failure = assertThrows(McpToolException.class,
                () -> router.call(caller, "review_reply", args("id", open.id(), "note", "done")));

        assertTrue(failure.getMessage().contains("RESOLVED"), failure.getMessage());
        assertEquals(AnnotationStatus.RESOLVED, reloaded().status());
        assertEquals(1, reloaded().thread().size(), "not even the note may be appended");
    }

    @Test
    void aLegacyFixedThreadIsNotTouched() {
        context.store(open.withStatus(AnnotationStatus.FIXED));

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

    /**
     * The MCP-versus-FX race the atomic transform exists to close: the human
     * clicks Resolve after the router has read the thread but before it writes.
     * The refusal is decided inside the transform, against the stored value, so
     * the verdict stands instead of being overwritten with ADDRESSED.
     */
    @Test
    void aResolveThatLandsInsideTheWriteWindowIsNotOverwritten() {
        context.beforeMutate = () -> context.store(reloaded()
                .withReply(new ReviewAnnotation.Message("You", Instant.parse("2026-07-25T10:05:00Z"), "resolving"))
                .withStatus(AnnotationStatus.RESOLVED));

        McpToolException failure = assertThrows(McpToolException.class, () -> router.call(caller, "review_reply",
                argsWithFlag("addressed", true, "id", open.id(), "note", "I fixed it")));

        assertTrue(failure.getMessage().contains("RESOLVED"), failure.getMessage());
        assertEquals(AnnotationStatus.RESOLVED, reloaded().status(),
                "the human's verdict is final; the agent must not reopen it");
        assertEquals(List.of("needs a null check", "resolving"), reloaded().thread().stream()
                        .map(ReviewAnnotation.Message::text).toList(),
                "the human's own reply must survive too");
    }

    /** ...and a benign concurrent write is not lost either: the reply lands on top of it. */
    @Test
    void aConcurrentHumanReplyIsKeptUnderTheAgentsNote() throws Exception {
        context.beforeMutate = () -> context.store(reloaded().withReply(
                new ReviewAnnotation.Message("You", Instant.parse("2026-07-25T10:05:00Z"), "one more thing")));

        router.call(caller, "review_reply", args("id", open.id(), "note", "on it"));

        assertEquals(List.of("needs a null check", "one more thing", "on it"),
                reloaded().thread().stream().map(ReviewAnnotation.Message::text).toList());
    }

    /** An ended session says so, rather than the baffling "No such annotation". */
    @Test
    void anEndedSessionIsToldItsSessionIsGone() {
        context.sessionRunning = false;

        McpToolException failure = assertThrows(McpToolException.class,
                () -> router.call(caller, "review_reply", args("id", open.id(), "note", "done")));

        assertTrue(failure.getMessage().contains("Session has ended"), failure.getMessage());
        assertEquals(1, reloaded().thread().size(), "nothing may be appended");
    }

    @Test
    void aSentThreadCanBeAddressed() throws Exception {
        context.store(open.withStatus(AnnotationStatus.SENT));

        router.call(caller, "review_reply",
                argsWithFlag("addressed", true, "id", open.id(), "note", "done"));

        assertEquals(AnnotationStatus.ADDRESSED, reloaded().status());
    }
}
