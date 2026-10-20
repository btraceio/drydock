package app.drydock.domain;

import app.drydock.agent.api.AgentKind;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The builder is the single construction path, so adding a component is one
 * edit rather than one per {@code with*} method.
 */
class ManagedAgentSessionBuilderTest {

    private static ManagedAgentSession session() {
        return new ManagedAgentSession.Builder(
                ManagedSessionId.newId(), RepositoryId.newId(), "Session 1",
                AgentBinding.unlaunched(AgentKind.CLAUDE),
                new SessionWorkspace(Path.of("/tmp/wt"), Optional.of(Path.of("/tmp/wt")), true),
                Instant.parse("2026-08-12T10:15:30Z"))
                .build();
    }

    @Test
    void aNewSessionStartsInactiveWithNoPrAndNoLineage() {
        ManagedAgentSession created = session();

        assertEquals(SessionStatus.INACTIVE, created.status());
        assertEquals(PrState.NONE, created.prState());
        assertEquals(Optional.empty(), created.prNumber());
        assertEquals(Optional.empty(), created.forkedFrom());
        assertEquals(false, created.namePinned());
    }

    @Test
    void createdAtAndLastOpenedAtComeFromTheCallersClock() {
        Instant now = Instant.parse("2026-08-12T10:15:30Z");

        ManagedAgentSession created = session();

        assertEquals(now, created.createdAt());
        assertEquals(now, created.lastOpenedAt());
    }

    @Test
    void toBuilderRoundTripsUnchanged() {
        ManagedAgentSession original = session();

        assertEquals(original, original.toBuilder().build());
    }

    @Test
    void aWithMethodChangesOneThingAndLeavesTheGroupsAlone() {
        ManagedAgentSession original = session();

        ManagedAgentSession renamed = original.withDisplayName("Renamed");

        assertEquals("Renamed", renamed.displayName());
        assertSame(original.workspace(), renamed.workspace());
        assertSame(original.binding(), renamed.binding());
    }

    @Test
    void leafAccessorsReadThroughToTheirGroups() {
        ManagedAgentSession created = session();

        assertEquals(AgentKind.CLAUDE, created.agentKind());
        assertEquals(Path.of("/tmp/wt"), created.workingDirectory());
        assertEquals(Optional.of(Path.of("/tmp/wt")), created.worktreeRoot());
        assertEquals(true, created.branchCreatedHere());
    }

    @Test
    void withAgentSessionIdKeepsTheKindAndName() {
        ManagedAgentSession named = session().withAgentSessionName(Optional.of("my-name"));

        ManagedAgentSession bound = named.withAgentSessionId(Optional.of("abc-123"));

        assertEquals(Optional.of("abc-123"), bound.agentSessionId());
        assertEquals(Optional.of("my-name"), bound.agentSessionName());
        assertEquals(AgentKind.CLAUDE, bound.agentKind());
    }
}
