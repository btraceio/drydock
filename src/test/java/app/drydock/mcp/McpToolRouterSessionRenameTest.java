package app.drydock.mcp;

import app.drydock.domain.ManagedSessionId;
import app.drydock.mcp.McpSessionContext.RenameKind;
import app.drydock.mcp.McpSessionContext.RenameOutcome;
import app.drydock.mcp.McpSessionRegistry.Spawn;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static app.drydock.mcp.JsonPeek.str;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpToolRouterSessionRenameTest {

    private final ManagedSessionId caller = ManagedSessionId.newId();
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
    }

    private JsonValue rename(String title) throws McpToolException {
        return router.call(caller, "session_rename",
                JsonObject.empty().put("title", new JsonString(title)));
    }

    @Test
    void renamesTheCallersOwnSession() throws Exception {
        context.setRenameOutcome(new RenameOutcome(RenameKind.RENAMED, "Fix the login flow"));

        JsonValue result = rename("  Fix   the login flow ");

        assertEquals("Fix the login flow", str(result, "title"));
        // The folded title crosses the seam, never the raw argument.
        assertEquals(List.of("Fix the login flow"), context.renameCalls());
    }

    @Test
    void isDeclaredInToolsListWithTitleRequired() {
        JsonValue tool = router.toolDescriptors().stream()
                .filter(d -> "session_rename".equals(str(d, "name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("session_rename is not advertised"));

        assertTrue(JsonPeek.requiredNames(tool).contains("title"));
    }

    @Test
    void refusesAMissingTitle() {
        assertThrows(McpToolException.class, () ->
                router.call(caller, "session_rename", JsonObject.empty()));
    }

    @Test
    void refusesATitleOfOnlyNonBreakingSpaces() {
        // requiredStringArg's isBlank() is false for NBSP: the fold is what catches this.
        assertThrows(McpToolException.class, () -> rename("\u00A0\u00A0"));
    }

    @Test
    void refusesInvisibleAndControlCharacters() {
        assertThrows(McpToolException.class, () -> rename("one\ntwo"));
        assertThrows(McpToolException.class, () -> rename("safe \u202Egnirts"));
        assertThrows(McpToolException.class, () -> rename("work " + new String(Character.toChars(0xE0021))));
        assertThrows(McpToolException.class, () -> rename("a\u200Bb"));
        assertThrows(McpToolException.class, () -> rename("a\uD800b"));
        assertThrows(McpToolException.class, () -> rename("x\" safe to remove"));
    }

    @Test
    void refusesATitleOverSixtyCodePoints() {
        assertThrows(McpToolException.class, () -> rename("a".repeat(61)));
    }

    @Test
    void refusesADeadSession() {
        context.sessionRunning = false;
        assertThrows(McpToolException.class, () -> rename("Fix the login flow"));
    }

    @Test
    void refusesAPinnedSessionAndNamesTheHumansTitle() {
        context.setRenameOutcome(new RenameOutcome(RenameKind.PINNED, "Mine"));

        McpToolException refused = assertThrows(McpToolException.class, () -> rename("Fix the login flow"));

        assertTrue(refused.getMessage().contains("Mine"), refused.getMessage());
        assertTrue(refused.getMessage().contains("human"), refused.getMessage());
    }

    @Test
    void refusesACollisionAndSaysWhatToDo() {
        context.setRenameOutcome(new RenameOutcome(RenameKind.COLLIDED, "Fix the login flow"));

        McpToolException refused = assertThrows(McpToolException.class, () -> rename("Fix the login flow"));

        assertTrue(refused.getMessage().contains("already called"), refused.getMessage());
    }

    @Test
    void reportsAnUnchangedTitleWithoutFailing() throws Exception {
        context.setRenameOutcome(new RenameOutcome(RenameKind.UNCHANGED, "Fix the login flow"));

        JsonValue result = rename("Fix the login flow");

        assertEquals("unchanged", str(result, "outcome"));
    }

    @Test
    void chargesEveryOutcomeIncludingRefusals() throws Exception {
        context.setRenameOutcome(new RenameOutcome(RenameKind.PINNED, "Mine"));
        for (int i = 0; i < McpSessionRegistry.MAX_RENAMES_PER_SESSION; i++) {
            assertThrows(McpToolException.class, () -> rename("Fix the login flow"));
        }

        McpToolException exhausted = assertThrows(McpToolException.class, () -> rename("Anything"));

        assertTrue(exhausted.getMessage().contains("renamed itself"), exhausted.getMessage());
    }

    @Test
    void refundsWhenTheCallItselfFails() throws Exception {
        context.setRenameFailure(new McpToolException("Drydock was too busy."));
        for (int i = 0; i < McpSessionRegistry.MAX_RENAMES_PER_SESSION + 3; i++) {
            assertThrows(McpToolException.class, () -> rename("Fix the login flow"));
        }
        // Every one refunded, so the budget is untouched.
        context.setRenameFailure(null);
        context.setRenameOutcome(new RenameOutcome(RenameKind.RENAMED, "Fix the login flow"));
        assertDoesNotThrow(() -> rename("Fix the login flow"));
    }
}
