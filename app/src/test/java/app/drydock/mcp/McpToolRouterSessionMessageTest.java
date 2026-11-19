package app.drydock.mcp;

import app.drydock.domain.ManagedSessionId;
import app.drydock.mcp.McpSessionContext.MessageOutcome;
import app.drydock.mcp.McpSessionContext.SessionSummary;
import app.drydock.mcp.McpSessionRegistry.Spawn;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static app.drydock.mcp.JsonPeek.str;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpToolRouterSessionMessageTest {

    private final ManagedSessionId caller = ManagedSessionId.newId();
    private final ManagedSessionId target = ManagedSessionId.newId();
    private final Path repo = Path.of("/repos/drydock");
    private FakeMcpSessionContext context;
    private McpSessionRegistry registry;
    private McpToolRouter router;

    @BeforeEach
    void setUp() {
        context = new FakeMcpSessionContext();
        context.repositoryRoot = Optional.of(repo);
        context.worktreePath = Optional.of(repo);
        context.sessionRunning = true;
        registry = new McpSessionRegistry();
        registry.mint(caller, Spawn.ALLOWED);
        router = new McpToolRouter(context, registry);
        context.sessions.add(summary(caller, "sender"));
        context.sessions.add(summary(target, "sibling"));
    }

    private static SessionSummary summary(ManagedSessionId id, String name) {
        return new SessionSummary(id, name, "drydock", Optional.of("main"),
                Path.of("/repos/drydock"), "RUNNING", false);
    }

    private JsonValue message(String message) throws McpToolException {
        return router.call(caller, "session_message",
                JsonObject.empty()
                        .put("to", new JsonString(target.toString()))
                        .put("message", new JsonString(message)));
    }

    @Test
    void isDeclaredInToolsListWithToAndMessageRequired() {
        JsonValue tool = router.toolDescriptors().stream()
                .filter(d -> "session_message".equals(str(d, "name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("session_message is not advertised"));

        assertTrue(JsonPeek.requiredNames(tool).contains("to"));
        assertTrue(JsonPeek.requiredNames(tool).contains("message"));
    }

    @Test
    void deliversToTheTargetSession() throws Exception {
        JsonValue result = message("Please read notes/fix-request.md");

        assertEquals("delivered", str(result, "outcome"));
        assertEquals(java.util.List.of(target), context.messageTargets());
        assertEquals(java.util.List.of("Please read notes/fix-request.md"), context.deliveredMessages());
    }

    @Test
    void refusesAMissingToOrMessage() {
        assertThrows(McpToolException.class, () ->
                router.call(caller, "session_message",
                        JsonObject.empty().put("to", new JsonString(target.toString()))));
        assertThrows(McpToolException.class, () ->
                router.call(caller, "session_message",
                        JsonObject.empty().put("message", new JsonString("hello"))));
    }

    @Test
    void refusesATargetThatIsNotASessionId() {
        McpToolException refused = assertThrows(McpToolException.class, () ->
                router.call(caller, "session_message",
                        JsonObject.empty()
                                .put("to", new JsonString("sibling"))
                                .put("message", new JsonString("hello"))));

        assertTrue(refused.getMessage().contains("sessions_list"), refused.getMessage());
    }

    @Test
    void refusesMessagingItself() {
        McpToolException refused = assertThrows(McpToolException.class, () ->
                router.call(caller, "session_message",
                        JsonObject.empty()
                                .put("to", new JsonString(caller.toString()))
                                .put("message", new JsonString("hello"))));

        assertTrue(refused.getMessage().contains("this session"), refused.getMessage());
    }

    @Test
    void refusesAMultilineMessage() {
        // The terminal submits at the first newline, so a second line would
        // be typed as an unintended prompt.
        assertThrows(McpToolException.class, () -> message("one\ntwo"));
    }

    @Test
    void refusesTuiModes() {
        // Typed as real keystrokes: a leading '!' is bash mode, '/' a slash
        // command, '#' a memory directive -- none of them a model turn.
        assertThrows(McpToolException.class, () -> message("!ls"));
        assertThrows(McpToolException.class, () -> message("/compact"));
        assertThrows(McpToolException.class, () -> message("# note"));
    }

    @Test
    void refusesAWhitespaceOnlyMessage() {
        // isBlank() is blind to non-breaking spaces, so the fold is what
        // catches these; both would otherwise reach the target as an empty
        // prompt that burns one of its turns.
        assertThrows(McpToolException.class, () -> message("   "));
        assertThrows(McpToolException.class, () -> message("\u00A0\u00A0"));
    }

    @Test
    void refusesAnOverlongMessage() {
        // The cap bounds FX-thread keystroke synthesis, not payload size:
        // every character is typed as a separate native key event.
        assertThrows(McpToolException.class, () -> message("a".repeat(1001)));
    }

    @Test
    void refusesAnUnwritableTarget() {
        context.setMessageOutcome(MessageOutcome.UNWRITABLE);

        McpToolException refused = assertThrows(McpToolException.class, () -> message("hello"));

        assertTrue(refused.getMessage().contains("closing"), refused.getMessage());
    }

    @Test
    void refusesADeadCaller() {
        context.sessionRunning = false;
        assertThrows(McpToolException.class, () -> message("hello"));
    }

    @Test
    void refusesATerminalTargetAndSaysWhy() {
        context.setMessageOutcome(MessageOutcome.TERMINAL_TARGET);

        McpToolException refused = assertThrows(McpToolException.class, () -> message("hello"));

        assertTrue(refused.getMessage().contains("shell"), refused.getMessage());
    }

    @Test
    void refusesATargetWithNoTab() {
        context.setMessageOutcome(MessageOutcome.NO_TAB);

        McpToolException refused = assertThrows(McpToolException.class, () -> message("hello"));

        assertTrue(refused.getMessage().contains("no open tab"), refused.getMessage());
    }

    @Test
    void chargesEveryOutcomeIncludingRefusals() {
        context.setMessageOutcome(MessageOutcome.NO_TAB);
        for (int i = 0; i < McpSessionRegistry.MAX_MESSAGES_PER_SESSION; i++) {
            assertThrows(McpToolException.class, () -> message("hello"));
        }

        McpToolException exhausted = assertThrows(McpToolException.class, () -> message("hello"));

        assertTrue(exhausted.getMessage().contains("sent its limit"), exhausted.getMessage());
    }

    @Test
    void refundsWhenTheCallItselfFails() throws Exception {
        context.setMessageFailure(new McpToolException("Drydock was too busy."));
        for (int i = 0; i < McpSessionRegistry.MAX_MESSAGES_PER_SESSION + 3; i++) {
            assertThrows(McpToolException.class, () -> message("hello"));
        }
        // Every one refunded, so the budget is untouched.
        context.setMessageFailure(null);
        context.setMessageOutcome(MessageOutcome.DELIVERED);
        assertDoesNotThrow(() -> message("hello"));
    }
}
