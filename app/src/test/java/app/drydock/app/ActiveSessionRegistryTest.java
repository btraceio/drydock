package app.drydock.app;

import app.drydock.domain.ManagedSessionId;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure map-logic tests for the plan section 11.3 duplicate-open-protection
 * bookkeeping -- fake ids only, no real {@code GhosttySurface}/window needed
 * (see {@code SessionManager}'s class Javadoc for what still needs a live
 * window and is therefore not covered here).
 */
class ActiveSessionRegistryTest {

    @Test
    void firstRegistrationForAClaudeSessionIdSucceeds() {
        ActiveSessionRegistry registry = new ActiveSessionRegistry();
        ManagedSessionId sessionId = ManagedSessionId.newId();

        Optional<ManagedSessionId> existing = registry.tryMarkActive("claude-session-1", sessionId);

        assertTrue(existing.isEmpty());
        assertEquals(Optional.of(sessionId), registry.activeSessionId("claude-session-1"));
    }

    @Test
    void secondRegistrationForTheSameClaudeSessionIdReturnsTheExistingOwner() {
        ActiveSessionRegistry registry = new ActiveSessionRegistry();
        ManagedSessionId first = ManagedSessionId.newId();
        ManagedSessionId second = ManagedSessionId.newId();
        registry.tryMarkActive("claude-session-1", first);

        Optional<ManagedSessionId> existing = registry.tryMarkActive("claude-session-1", second);

        assertEquals(Optional.of(first), existing);
        // The second registration must not have overwritten the first.
        assertEquals(Optional.of(first), registry.activeSessionId("claude-session-1"));
    }

    @Test
    void differentClaudeSessionIdsDoNotCollide() {
        ActiveSessionRegistry registry = new ActiveSessionRegistry();
        ManagedSessionId first = ManagedSessionId.newId();
        ManagedSessionId second = ManagedSessionId.newId();

        registry.tryMarkActive("claude-session-1", first);
        registry.tryMarkActive("claude-session-2", second);

        assertEquals(Optional.of(first), registry.activeSessionId("claude-session-1"));
        assertEquals(Optional.of(second), registry.activeSessionId("claude-session-2"));
    }

    @Test
    void releaseFreesUpTheClaudeSessionIdForReRegistration() {
        ActiveSessionRegistry registry = new ActiveSessionRegistry();
        ManagedSessionId first = ManagedSessionId.newId();
        ManagedSessionId second = ManagedSessionId.newId();
        registry.tryMarkActive("claude-session-1", first);

        registry.release("claude-session-1");
        Optional<ManagedSessionId> existing = registry.tryMarkActive("claude-session-1", second);

        assertTrue(existing.isEmpty());
        assertEquals(Optional.of(second), registry.activeSessionId("claude-session-1"));
        assertTrue(!registry.isEmpty());
    }

    @Test
    void activeSessionIdIsEmptyForAnUnknownClaudeSessionId() {
        ActiveSessionRegistry registry = new ActiveSessionRegistry();

        assertTrue(registry.activeSessionId("never-registered").isEmpty());
        assertTrue(registry.isEmpty());
    }
}
