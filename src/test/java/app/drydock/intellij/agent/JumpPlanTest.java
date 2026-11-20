package app.drydock.intellij.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The jump's copy (frame F3): while the agent's window opens, once its console has moved, and what it found there. */
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

    @Test
    void aGoneFolderOutranksEverythingElse() {
        assertEquals(JumpPlan.Outcome.FOLDER_GONE, JumpPlan.after(false, true, true));
        assertEquals(JumpPlan.Outcome.FOLDER_GONE, JumpPlan.after(false, false, false));
    }

    @Test
    void aWindowThatDidNotOpenFailsTheJump() {
        assertEquals(JumpPlan.Outcome.OPEN_FAILED, JumpPlan.after(true, false, true));
        assertEquals(JumpPlan.Outcome.OPEN_FAILED, JumpPlan.after(true, false, false));
    }

    @Test
    void liveAtCompletionMovesLiveEvenWithNoExecutionAtClickTime() {
        // The click saw a stopped session — or none at all. Only the registry
        // re-read at completion decides, so an agent that started while its
        // window opened takes the unchanged running move.
        assertEquals(JumpPlan.Outcome.MOVED_LIVE, JumpPlan.after(true, true, true));
    }

    @Test
    void endedAtCompletionShowsStopped() {
        assertEquals(JumpPlan.Outcome.SHOW_STOPPED, JumpPlan.after(true, true, false));
    }

    @Test
    void theGoneFolderDialogNamesThePlaceAndThePath() {
        assertEquals("The folder for dd-jmc · fix/chunk_memory no longer exists on disk: /worktrees/dd-jmc",
                JumpPlan.folderGone(DD_JMC.label(), Path.of("/worktrees/dd-jmc")));
    }

    @Test
    void theHostTabClosesOnlyWhenTheJumpLandsInAnotherWindow() {
        assertTrue(JumpPlan.closesHostTab(false));
        assertFalse(JumpPlan.closesHostTab(true));
    }

    @Test
    void onlyALiveMoveIsAnnouncedWithAWayBack() {
        // The balloon's "Bring back here" undoes a registry move of a live
        // execution. Every other outcome — stopped shown, gone, never opened
        // — must not announce one: with no execution in the registry,
        // showHere silently does nothing.
        assertTrue(JumpPlan.announcesMove(JumpPlan.Outcome.MOVED_LIVE));
        assertFalse(JumpPlan.announcesMove(JumpPlan.Outcome.SHOW_STOPPED));
        assertFalse(JumpPlan.announcesMove(JumpPlan.Outcome.FOLDER_GONE));
        assertFalse(JumpPlan.announcesMove(JumpPlan.Outcome.OPEN_FAILED));
    }
}
