package app.drydock.mcp;

import app.drydock.domain.ManagedSessionId;
import app.drydock.mcp.McpSessionRegistry.Spawn;
import app.drydock.review.PendingQuestions;
import app.drydock.state.json.JsonValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static app.drydock.mcp.JsonPeek.args;
import static app.drydock.mcp.JsonPeek.str;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpToolRouterAskAnswerTest {

    private final ManagedSessionId caller = ManagedSessionId.newId();
    private FakeMcpSessionContext context;
    private McpToolRouter router;
    private PendingQuestions.PendingAsk ask;

    /** The one review scope this caller may address. */
    private static final String SCOPE = "rs_ask";

    @BeforeEach
    void setUp() {
        context = new FakeMcpSessionContext();
        context.repositoryRoot = Optional.of(Path.of("/repos/drydock"));
        context.worktreePath = Optional.of(Path.of("/repos/drydock"));
        McpSessionRegistry registry = new McpSessionRegistry();
        registry.mint(caller, Spawn.ALLOWED);
        router = new McpToolRouter(context, registry);
        context.grant(caller, SCOPE);
        ask = context.pendingQuestions.mint(SCOPE, "loadConfig", caller);
    }

    @Test
    void anAnswerClosesTheQuestionAndEchoesItsSymbol() throws Exception {
        JsonValue result = router.call(caller, "review_ask_answer",
                args("questionId", ask.questionId(), "answer", "It memoizes the parser."));

        assertEquals(ask.questionId(), str(result, "questionId"));
        assertEquals("loadConfig", str(result, "symbol"));
        assertEquals(SCOPE, str(result, "scopeId"));
        assertEquals(0, context.pendingQuestions.size(), "answered, so no longer pending");
    }

    @Test
    void anUnknownIdIsRefused() {
        McpToolException e = assertThrows(McpToolException.class, () -> router.call(caller,
                "review_ask_answer", args("questionId", "ask-999", "answer", "guess")));

        assertTrue(e.getMessage().contains("No pending question 'ask-999'"), e.getMessage());
    }

    /**
     * A session that cannot address the ask's scope gets the SAME message as
     * an unknown id: an agent must learn nothing about other sessions'
     * questions from a refusal.
     */
    @Test
    void anotherSessionsQuestionIsRefusedAsUnknown() {
        ManagedSessionId stranger = ManagedSessionId.newId();
        McpSessionRegistry registry = new McpSessionRegistry();
        registry.mint(stranger, Spawn.ALLOWED);
        McpToolRouter strangerRouter = new McpToolRouter(context, registry);

        McpToolException e = assertThrows(McpToolException.class, () -> strangerRouter.call(stranger,
                "review_ask_answer", args("questionId", ask.questionId(), "answer", "mine now")));

        assertTrue(e.getMessage().contains("No pending question"), e.getMessage());
        assertEquals(1, context.pendingQuestions.size(),
                "the refused call must not have consumed the question");
    }

    @Test
    void aSecondAnswerToTheSameQuestionIsRefused() throws Exception {
        router.call(caller, "review_ask_answer",
                args("questionId", ask.questionId(), "answer", "first"));

        McpToolException e = assertThrows(McpToolException.class, () -> router.call(caller,
                "review_ask_answer", args("questionId", ask.questionId(), "answer", "second")));

        assertTrue(e.getMessage().contains("No pending question"), e.getMessage());
    }

    @Test
    void anExitedSessionCannotAnswer() {
        context.sessionRunning = false;

        McpToolException e = assertThrows(McpToolException.class, () -> router.call(caller,
                "review_ask_answer", args("questionId", ask.questionId(), "answer", "late")));

        assertTrue(e.getMessage().toLowerCase().contains("session"), e.getMessage());
    }
}
