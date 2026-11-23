package app.drydock.mcp;

import app.drydock.domain.ManagedSessionId;
import app.drydock.mcp.McpSessionRegistry.Spawn;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpSessionRegistryTest {

    private final McpSessionRegistry registry = new McpSessionRegistry();

    @Test
    void mintedTokenResolvesBackToItsSession() {
        ManagedSessionId session = ManagedSessionId.newId();

        String token = registry.mint(session, Spawn.ALLOWED);

        assertEquals(Optional.of(session), registry.resolve(token));
    }

    @Test
    void distinctSessionsGetDistinctTokens() {
        assertNotEquals(registry.mint(ManagedSessionId.newId(), Spawn.ALLOWED),
                registry.mint(ManagedSessionId.newId(), Spawn.ALLOWED));
    }

    @Test
    void mintingTwiceForOneSessionReusesTheSameToken() {
        ManagedSessionId session = ManagedSessionId.newId();

        assertEquals(registry.mint(session, Spawn.ALLOWED), registry.mint(session, Spawn.ALLOWED));
    }

    @Test
    void unknownTokenDoesNotResolve() {
        registry.mint(ManagedSessionId.newId(), Spawn.ALLOWED);

        assertTrue(registry.resolve("not-a-real-token").isEmpty());
    }

    @Test
    void revokedTokenStopsResolving() {
        ManagedSessionId session = ManagedSessionId.newId();
        String token = registry.mint(session, Spawn.ALLOWED);

        registry.revoke(session);

        assertTrue(registry.resolve(token).isEmpty());
        assertTrue(registry.tokenFor(session).isEmpty());
    }

    @Test
    void revokingAnUnknownSessionIsSilent() {
        registry.revoke(ManagedSessionId.newId());
    }

    @Test
    void tokenIsLongEnoughToResistGuessing() {
        String token = registry.mint(ManagedSessionId.newId(), Spawn.ALLOWED);

        assertTrue(token.length() >= 32, "token too short: " + token.length());
        assertFalse(token.contains("="), "token must be URL-safe and unpadded");
    }

    @Test
    void anAgentStartedSessionMayNotSpawn() {
        ManagedSessionId child = ManagedSessionId.newId();
        registry.mint(child, Spawn.FORBIDDEN);

        assertFalse(registry.maySpawn(child));
    }

    @Test
    void aHumanStartedSessionMaySpawn() {
        ManagedSessionId session = ManagedSessionId.newId();
        registry.mint(session, Spawn.ALLOWED);

        assertTrue(registry.maySpawn(session));
    }

    @Test
    void anUnknownSessionMayNotSpawn() {
        assertFalse(registry.maySpawn(ManagedSessionId.newId()));
    }

    @Test
    void worktreeBudgetIsExhaustedAfterTheLimit() throws Exception {
        ManagedSessionId session = ManagedSessionId.newId();
        registry.mint(session, Spawn.ALLOWED);

        for (int i = 0; i < McpSessionRegistry.MAX_WORKTREES_PER_SESSION; i++) {
            registry.chargeWorktree(session);
        }

        McpBudgetExhaustedException failure =
                assertThrows(McpBudgetExhaustedException.class, () -> registry.chargeWorktree(session));
        assertTrue(failure.getMessage().contains(String.valueOf(McpSessionRegistry.MAX_WORKTREES_PER_SESSION)),
                "the message must name the limit: " + failure.getMessage());
    }

    @Test
    void sessionBudgetIsExhaustedAfterTheLimit() throws Exception {
        ManagedSessionId session = ManagedSessionId.newId();
        registry.mint(session, Spawn.ALLOWED);

        for (int i = 0; i < McpSessionRegistry.MAX_SESSIONS_PER_SESSION; i++) {
            registry.chargeSession(session);
        }

        assertThrows(McpBudgetExhaustedException.class, () -> registry.chargeSession(session));
    }

    @Test
    void theTwoBudgetsAreIndependent() throws Exception {
        ManagedSessionId session = ManagedSessionId.newId();
        registry.mint(session, Spawn.ALLOWED);

        for (int i = 0; i < McpSessionRegistry.MAX_WORKTREES_PER_SESSION; i++) {
            registry.chargeWorktree(session);
        }

        registry.chargeSession(session);
    }

    @Test
    void aRefundReleasesTheCharge() throws Exception {
        // Callers charge before the operation so the limit can never be
        // exceeded, then refund if the operation fails, so a failure is free.
        ManagedSessionId session = ManagedSessionId.newId();
        registry.mint(session, Spawn.ALLOWED);

        registry.chargeWorktree(session);
        registry.refundWorktree(session);

        for (int i = 0; i < McpSessionRegistry.MAX_WORKTREES_PER_SESSION; i++) {
            registry.chargeWorktree(session);
        }
        assertThrows(McpBudgetExhaustedException.class, () -> registry.chargeWorktree(session));
    }

    @Test
    void aRefundNeverDropsTheCounterBelowZero() throws Exception {
        ManagedSessionId session = ManagedSessionId.newId();
        registry.mint(session, Spawn.ALLOWED);

        // Charge once, then refund twice. The second refund has nothing left to
        // release. Without the floor the counter would reach -1, buying this
        // session a fifth worktree: the loop below would end at 4 rather than 5
        // and the final charge would not throw. Refunding with no prior charge
        // would NOT exercise this -- refund() returns early on an absent
        // counter, so the clamp is never reached.
        registry.chargeWorktree(session);
        registry.refundWorktree(session);
        registry.refundWorktree(session);

        for (int i = 0; i < McpSessionRegistry.MAX_WORKTREES_PER_SESSION; i++) {
            registry.chargeWorktree(session);
        }
        assertThrows(McpBudgetExhaustedException.class, () -> registry.chargeWorktree(session));
    }

    @Test
    void refundingWithNoPriorChargeIsSilent() throws Exception {
        // Separate from the floor test above: this covers refund()'s absent-counter
        // early return, which is a different branch from the clamp.
        ManagedSessionId session = ManagedSessionId.newId();
        registry.mint(session, Spawn.ALLOWED);

        registry.refundWorktree(session);
        registry.refundSession(session);

        for (int i = 0; i < McpSessionRegistry.MAX_WORKTREES_PER_SESSION; i++) {
            registry.chargeWorktree(session);
        }
        assertThrows(McpBudgetExhaustedException.class, () -> registry.chargeWorktree(session));
    }

    @Test
    void revokingAndReminntingDoesNotRefillTheBudget() throws Exception {
        // A budget that resets on reconnect is not a budget. Charges are keyed
        // to the session, and a session that ended cannot spend again anyway.
        ManagedSessionId session = ManagedSessionId.newId();
        registry.mint(session, Spawn.ALLOWED);
        registry.chargeWorktree(session);
        registry.revoke(session);
        registry.mint(session, Spawn.ALLOWED);

        for (int i = 0; i < McpSessionRegistry.MAX_WORKTREES_PER_SESSION - 1; i++) {
            registry.chargeWorktree(session);
        }

        assertThrows(McpBudgetExhaustedException.class, () -> registry.chargeWorktree(session));
    }

    @Test
    void aSessionMayRenameItselfTwentyTimes() throws Exception {
        ManagedSessionId session = ManagedSessionId.newId();

        for (int i = 0; i < McpSessionRegistry.MAX_RENAMES_PER_SESSION; i++) {
            registry.chargeRename(session);
        }

        McpBudgetExhaustedException refused =
                assertThrows(McpBudgetExhaustedException.class, () -> registry.chargeRename(session));
        assertTrue(refused.getMessage().contains("renamed itself"),
                "message reads as a creation limit: " + refused.getMessage());
    }

    @Test
    void aRefundedRenameCanBeRetried() throws Exception {
        ManagedSessionId session = ManagedSessionId.newId();
        for (int i = 0; i < McpSessionRegistry.MAX_RENAMES_PER_SESSION; i++) {
            registry.chargeRename(session);
        }

        registry.refundRename(session);

        assertDoesNotThrow(() -> registry.chargeRename(session));
    }
}
