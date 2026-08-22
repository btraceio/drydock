package app.drydock.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The agent has to be able to see the grouping it is being asked to name
 * (spec §5.5). An agent that cannot regroups from scratch and loses the
 * header conventions and the dependency order -- arriving back at prose
 * titles over structurally worse sections.
 */
class McpToolRouterSectionsTest extends McpRouterFixture {

    @Test
    void reviewScopeOmitsSectionsUnlessAsked() {
        String response = callReviewScope(scopeId(), null);

        assertFalse(response.contains("\"sections\""));
    }

    @Test
    void reviewScopeIncludesSectionsWhenAsked() {
        String response = callReviewScope(scopeId(), "sections");

        assertTrue(response.contains("\"sections\""));
        assertTrue(response.contains("\"hunkIds\""));
    }

    /** An unknown include is ignored, not an error: it is an optional read. */
    @Test
    void anUnknownIncludeIsIgnored() {
        String response = callReviewScope(scopeId(), "nonsense");

        assertFalse(response.contains("\"sections\""));
    }
}
