package app.drydock.domain;

import app.drydock.agent.api.AgentKind;
import app.drydock.testing.TestPaths;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionRelationsTest {

    private static ManagedAgentSession session() {
        return new ManagedAgentSession(
                ManagedSessionId.newId(), RepositoryId.newId(), "session",
                new AgentBinding(AgentKind.CLAUDE, Optional.empty(), Optional.empty()),
                new SessionWorkspace(TestPaths.abs("/tmp"), Optional.empty(), true),
                SessionStatus.INACTIVE, java.time.Instant.now(), java.time.Instant.now(),
                Optional.empty(), PrLink.none(), false, Optional.empty());
    }

    @Test
    void waitingChildrenAreExactlyTheParentWaitsChildrenOfThatParent() {
        ManagedAgentSession parent = session();
        ManagedAgentSession waiting = session().withTaskRelation(
                Optional.of(parent.id()), Optional.of(TaskRelation.PARENT_WAITS));
        ManagedAgentSession followUp = session().withTaskRelation(
                Optional.of(parent.id()), Optional.of(TaskRelation.FOLLOW_UP));
        ManagedAgentSession waitingOfAnother = session().withTaskRelation(
                Optional.of(ManagedSessionId.newId()), Optional.of(TaskRelation.PARENT_WAITS));

        List<ManagedAgentSession> waitingChildren =
                SessionRelations.waitingChildrenOf(List.of(waiting, followUp, waitingOfAnother), parent.id());

        assertEquals(List.of(waiting), waitingChildren);
    }

    @Test
    void anUnrelatedSessionIsNeverAWaitingChild() {
        assertTrue(SessionRelations.waitingChildrenOf(
                List.of(session(), session()), ManagedSessionId.newId()).isEmpty());
    }
}
