package app.drydock.intellij.agent;

import app.drydock.domain.SessionActivity;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A hosted tab across the end of its agent's turn and its worktree vanishing. */
class HostedLifecycleTest {

    private static final Optional<SessionActivity> BUSY = Optional.of(SessionActivity.BUSY);
    private static final Optional<SessionActivity> IDLE = Optional.of(SessionActivity.IDLE);

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
