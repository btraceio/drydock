package app.drydock.intellij.agent;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The jump's copy (frame F3): while the agent's window opens, and once its console has moved. */
class JumpPlanTest {

    private static final SessionPlace DD_JMC = new SessionPlace("dd-jmc", Optional.of("fix/chunk_memory"));

    @Test
    void theHostSaysWhichWindowIsOpening() {
        assertEquals("Opening dd-jmc · fix/chunk_memory…", JumpPlan.opening(DD_JMC));
    }

    @Test
    void theBalloonNamesTheTaskThatMoved() {
        assertEquals("Fix chunk memory growth moved", JumpPlan.movedTitle("Fix chunk memory growth"));
    }

    @Test
    void theBalloonSaysWhereItIsNow() {
        assertEquals("Now in the dd-jmc · fix/chunk_memory window.", JumpPlan.movedBody(DD_JMC));
    }

    @Test
    void aPlaceWithoutABranchNamesTheRepoAlone() {
        assertEquals("Opening dd-jmc…", JumpPlan.opening(new SessionPlace("dd-jmc", Optional.empty())));
    }
}
