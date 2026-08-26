package app.drydock.mcp;

import app.drydock.domain.ManagedSessionId;
import app.drydock.mcp.McpSessionRegistry.Spawn;
import app.drydock.state.json.JsonValue;
import app.drydock.state.json.JsonValue.JsonObject;
import app.drydock.state.json.JsonValue.JsonString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static app.drydock.mcp.JsonPeek.str;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpToolRouterSessionReclaimTest {

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

    private JsonValue reclaim(String agentSessionId) throws McpToolException {
        return router.call(caller, "session_reclaim",
                JsonObject.empty().put("agentSessionId", new JsonString(agentSessionId)));
    }

    @Test
    void isNotAdvertisedInToolsList() {
        // session_reclaim is bridge housekeeping, not a model-facing tool, so
        // it must never appear in tools/list -- only the bridge calls it.
        boolean advertised = router.toolDescriptors().stream()
                .anyMatch(d -> "session_reclaim".equals(str(d, "name")));
        assertFalse(advertised, "session_reclaim must not be advertised to the model");
    }

    @Test
    void rebindsToTheNewConversationId() throws Exception {
        JsonValue result = reclaim("new-conv-id");

        assertEquals("reclaimed", str(result, "outcome"));
        assertEquals("new-conv-id", str(result, "agentSessionId"));
        assertEquals("new-conv-id", context.reclaimedTo());
    }

    @Test
    void refusesAMissingAgentSessionId() {
        assertThrows(McpToolException.class, () ->
                router.call(caller, "session_reclaim", JsonObject.empty()));
    }

    @Test
    void refusesADeadSession() {
        context.sessionRunning = false;
        McpToolException refused = assertThrows(McpToolException.class, () -> reclaim("new-conv-id"));
        assertTrue(refused.getMessage().contains("Session has ended"), refused.getMessage());
    }

    @Test
    void surfacesARefusedRebindAsAnError() {
        context.failReclaimWith(new McpToolException("Conversation new-conv-id is already open in another session."));

        McpToolException refused = assertThrows(McpToolException.class, () -> reclaim("new-conv-id"));

        assertTrue(refused.getMessage().contains("already open"), refused.getMessage());
    }
}