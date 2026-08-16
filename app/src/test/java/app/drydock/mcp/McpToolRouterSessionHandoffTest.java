package app.drydock.mcp;

import app.drydock.domain.HandoffBrief;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code session_handoff}: the tool an agent keeps current so its work can be
 * handed to a different agent at any moment.
 */
class McpToolRouterSessionHandoffTest {

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

    private JsonValue handoff(JsonObject args) throws McpToolException {
        return router.call(caller, "session_handoff", args);
    }

    private static JsonObject minimal() {
        return JsonObject.empty()
                .put("goal", new JsonString("Ship the fork gesture"))
                .put("nextStep", new JsonString("Wire the banner"));
    }

    @Test
    void writesABriefFromTheRequiredSlotsAlone() throws Exception {
        JsonValue result = handoff(minimal());

        assertEquals("written", str(result, "outcome"));
        HandoffBrief stored = context.lastHandoff().orElseThrow();
        assertEquals("Ship the fork gesture", stored.goal());
        assertEquals("Wire the banner", stored.nextStep());
        assertEquals(Optional.empty(), stored.approach());
    }

    @Test
    void theStoredBriefIsAttributedToTheAgentNotTheHuman() throws Exception {
        handoff(minimal());

        assertEquals(HandoffBrief.Author.AGENT, context.lastHandoff().orElseThrow().author());
    }

    @Test
    void anOmittedOptionalSlotClearsIt() throws Exception {
        handoff(minimal().put("ruledOut", new JsonString("tried the API proxy")));
        assertTrue(context.lastHandoff().orElseThrow().ruledOut().isPresent());

        handoff(minimal());

        // Wholesale replacement: an omitted slot is cleared, never preserved.
        assertEquals(Optional.empty(), context.lastHandoff().orElseThrow().ruledOut());
    }

    @Test
    void aBlankOptionalSlotMeansTheSameAsOmittingIt() throws Exception {
        handoff(minimal().put("approach", new JsonString("   ")));

        assertEquals(Optional.empty(), context.lastHandoff().orElseThrow().approach());
    }

    @Test
    void refusesAPresentNonStringSlotRatherThanClearingIt() {
        // The tool replaces the whole brief, so silently dropping a ruledOut
        // sent as an array would answer "written" for a brief that lost it.
        McpToolException e = assertThrows(McpToolException.class,
                () -> handoff(minimal().put("ruledOut", new JsonValue.JsonArray(java.util.List.of()))));

        assertTrue(e.getMessage().contains("ruledOut"), e.getMessage());
        assertEquals(Optional.empty(), context.lastHandoff());
    }

    @Test
    void anExplicitNullSlotStillMeansClearIt() throws Exception {
        handoff(minimal().put("approach", JsonValue.JsonNull.INSTANCE));

        assertEquals(Optional.empty(), context.lastHandoff().orElseThrow().approach());
    }

    @Test
    void refusesAMissingRequiredSlot() {
        McpToolException e = assertThrows(McpToolException.class,
                () -> handoff(JsonObject.empty().put("goal", new JsonString("g"))));

        assertTrue(e.getMessage().contains("nextStep"), e.getMessage());
    }

    @Test
    void refusesABlankRequiredSlot() {
        assertThrows(McpToolException.class,
                () -> handoff(JsonObject.empty()
                        .put("goal", new JsonString("   "))
                        .put("nextStep", new JsonString("n"))));
    }

    @Test
    void refusesAnOversizeSlotWithoutStoringAnything() {
        String tooLong = "x".repeat(PromptSafety.MAX_HANDOFF_SLOT_CHARS + 1);

        assertThrows(McpToolException.class,
                () -> handoff(minimal().put("decisions", new JsonString(tooLong))));

        assertEquals(Optional.empty(), context.lastHandoff());
    }

    @Test
    void refusesSlotsThatFitAloneButBreachTheWholeRecordCap() {
        String slot = "x".repeat(PromptSafety.MAX_HANDOFF_SLOT_CHARS);

        assertThrows(McpToolException.class,
                () -> handoff(minimal()
                        .put("approach", new JsonString(slot))
                        .put("decisions", new JsonString(slot))
                        .put("ruledOut", new JsonString(slot))
                        .put("corrections", new JsonString(slot))));

        assertEquals(Optional.empty(), context.lastHandoff());
    }

    @Test
    void aRefusedCallIsNotChargedAgainstTheBudget() throws Exception {
        // Validation happens before the charge: a malformed brief is the
        // agent's mistake to fix, not a spend.
        for (int i = 0; i < McpSessionRegistry.MAX_HANDOFFS_PER_SESSION; i++) {
            assertThrows(McpToolException.class, () -> handoff(JsonObject.empty()));
        }

        assertEquals("written", str(handoff(minimal()), "outcome"));
    }

    @Test
    void anOutrightFailureIsRefundedSoItDoesNotBurnTheBudget() throws Exception {
        context.failHandoffWith(new McpToolException("drydock was too busy"));
        for (int i = 0; i < McpSessionRegistry.MAX_HANDOFFS_PER_SESSION; i++) {
            assertThrows(McpToolException.class, () -> handoff(minimal()));
        }

        context.failHandoffWith(null);
        assertEquals("written", str(handoff(minimal()), "outcome"));
    }

    @Test
    void refusesOnceTheBudgetIsExhausted() throws Exception {
        for (int i = 0; i < McpSessionRegistry.MAX_HANDOFFS_PER_SESSION; i++) {
            handoff(minimal());
        }

        McpToolException e = assertThrows(McpToolException.class, () -> handoff(minimal()));
        assertTrue(e.getMessage().contains(String.valueOf(McpSessionRegistry.MAX_HANDOFFS_PER_SESSION)),
                e.getMessage());
    }

    @Test
    void refusesWhenTheSessionHasAlreadyEnded() {
        context.sessionRunning = false;

        assertThrows(McpToolException.class, () -> handoff(minimal()));
    }

    @Test
    void isAdvertisedInTheToolList() {
        assertTrue(router.toolDescriptors().stream()
                        .anyMatch(descriptor -> JsonPeek.str(descriptor, "name").equals("session_handoff")),
                "an agent only calls a tool it can see");
    }
}
