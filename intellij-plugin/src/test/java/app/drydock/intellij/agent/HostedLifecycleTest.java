package app.drydock.intellij.agent;

import app.drydock.domain.SessionActivity;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A hosted tab across a restart, the end of its agent's turn, and its worktree vanishing. */
class HostedLifecycleTest {

    private static final Optional<SessionActivity> BUSY = Optional.of(SessionActivity.BUSY);
    private static final Optional<SessionActivity> IDLE = Optional.of(SessionActivity.IDLE);

    @Test
    void aHostedTabTheIdeRestoredClosesItself() {
        assertTrue(HostedLifecycle.closeRestoredTab(true, false, false));
    }

    @Test
    void aHostedTabDrydockOpenedStaysEvenBeforeItsAgentIsShown() {
        // A new hosted task opens its tab first and starts its agent after.
        assertFalse(HostedLifecycle.closeRestoredTab(true, false, true));
    }

    @Test
    void aTabShowingItsAgentStays() {
        assertFalse(HostedLifecycle.closeRestoredTab(true, true, false));
    }

    @Test
    void aWindowsOwnRestoredTabStays() {
        assertFalse(HostedLifecycle.closeRestoredTab(false, false, false));
    }

    @Test
    void aHostedTurnEndingRefreshes() {
        assertTrue(HostedLifecycle.refreshAfter(BUSY, IDLE, true));
        assertTrue(HostedLifecycle.refreshAfter(BUSY, Optional.of(SessionActivity.NEEDS_ATTENTION), true));
    }

    @Test
    void aLocalTurnOrNoTurnEndingDoesNot() {
        assertFalse(HostedLifecycle.refreshAfter(BUSY, IDLE, false));
        assertFalse(HostedLifecycle.refreshAfter(IDLE, IDLE, true));
        assertFalse(HostedLifecycle.refreshAfter(IDLE, BUSY, true));
    }

    @Test
    void aRunningAgentWhoseFolderIsGoneIsMarked() {
        assertTrue(HostedLifecycle.folderGone(true, false));
        assertFalse(HostedLifecycle.folderGone(true, true));
        assertFalse(HostedLifecycle.folderGone(false, false));
    }
}
