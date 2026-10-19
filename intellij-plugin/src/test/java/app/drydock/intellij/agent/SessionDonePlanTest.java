package app.drydock.intellij.agent;

import app.drydock.intellij.agent.SessionDonePlan.Choice;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Done with this session…" as the handoff states it, plus the guards that
 * keep "Forget + remove worktree" from removing something it must not.
 */
class SessionDonePlanTest {

    @Test
    void theDataLossClauseAppearsOnlyWhenThereIsDataToLose() {
        assertEquals("Closes this window. The branch stays.", SessionDonePlan.removeDetail(0));
        assertEquals("Closes this window. The branch stays. 1 uncommitted file will be discarded.",
                SessionDonePlan.removeDetail(1));
        assertEquals("Closes this window. The branch stays. 3 uncommitted files will be discarded.",
                SessionDonePlan.removeDetail(3));
    }

    @Test
    void theMainCheckoutIsNeverRemovable() {
        Optional<String> blocker = SessionDonePlan.removalBlocker(true, false, 0, true);
        assertTrue(blocker.isPresent());
        assertTrue(blocker.get().contains("main checkout"), blocker.get());
    }

    @Test
    void aWorktreeOtherSessionsStillUseIsNotRemovable() {
        assertEquals(Optional.of("Another session uses this worktree; forget it first."),
                SessionDonePlan.removalBlocker(false, true, 1, true));
        assertEquals(Optional.of("2 other sessions use this worktree; forget them first."),
                SessionDonePlan.removalBlocker(false, true, 2, true));
    }

    @Test
    void onlyThisWindowsOwnLinkedWorktreeIsRemovable() {
        // "Closes this window" is only true of the window's own worktree.
        assertTrue(SessionDonePlan.removalBlocker(false, true, 0, false).isPresent());
        // Not a worktree at all (a plain directory, or git could not say).
        assertTrue(SessionDonePlan.removalBlocker(false, false, 0, true).isPresent());
        assertFalse(SessionDonePlan.removalBlocker(false, true, 0, true).isPresent());
    }

    @Test
    void thePrimaryButtonNamesTheChoice() {
        assertEquals("Forget", SessionDonePlan.primaryLabel(Choice.FORGET));
        assertEquals("Forget & Remove", SessionDonePlan.primaryLabel(Choice.FORGET_AND_REMOVE));
    }
}
