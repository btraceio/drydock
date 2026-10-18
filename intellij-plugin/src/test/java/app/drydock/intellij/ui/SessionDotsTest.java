package app.drydock.intellij.ui;

import app.drydock.domain.SessionActivity;
import app.drydock.domain.SessionStatus;

import org.junit.jupiter.api.Test;

import javax.swing.Icon;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The status vocabulary the UX handoff specifies, pinned as a table.
 *
 * <p>Every drydock surface resolves its status word through
 * {@link SessionDots}, so these assertions are what keeps the strip, the tab
 * dot and the popup rows from drifting apart.</p>
 */
class SessionDotsTest {

    private static final Optional<SessionActivity> NONE = Optional.empty();

    @Test
    void theRunningBandFoldsActivityIntoOneWord() {
        assertEquals("Working",
                SessionDots.statusWord(SessionStatus.RUNNING, Optional.of(SessionActivity.BUSY)));
        assertEquals("At prompt",
                SessionDots.statusWord(SessionStatus.RUNNING, Optional.of(SessionActivity.IDLE)));
        assertEquals("Needs you",
                SessionDots.statusWord(SessionStatus.RUNNING, Optional.of(SessionActivity.NEEDS_ATTENTION)));
    }

    @Test
    void aRunningSessionWithNoReadableActivityIsNotCalledWorking() {
        // Codex and pi have no activity reporter, so most live sessions land
        // here. Claiming "Working" would assert the agent is busy when the
        // user may be looking at an idle prompt; claiming "At prompt" asserts
        // the reverse.
        assertEquals("Running", SessionDots.statusWord(SessionStatus.RUNNING, NONE));
        assertEquals("Running",
                SessionDots.statusWord(SessionStatus.RUNNING, Optional.of(SessionActivity.UNKNOWN)));
    }

    @Test
    void bothIdleStatusesReadAsNotRunning() {
        // The user's question is "is an agent working on this", and the
        // answer is the same either way; which of the two it was is carried
        // by the strip's stopped line.
        assertEquals("Not running", SessionDots.statusWord(SessionStatus.INACTIVE, NONE));
        assertEquals("Not running", SessionDots.statusWord(SessionStatus.EXITED, NONE));
    }

    @Test
    void errorStatusesKeepTheirOwnWords() {
        assertEquals("Failed", SessionDots.statusWord(SessionStatus.FAILED, NONE));
        assertEquals("Missing directory", SessionDots.statusWord(SessionStatus.MISSING_WORKING_DIRECTORY, NONE));
        assertEquals("Unsupported agent", SessionDots.statusWord(SessionStatus.UNSUPPORTED_AGENT, NONE));
    }

    @Test
    void activityNeverRelabelsASessionOutsideTheRunningBand() {
        // A leftover activity state file must not label an exited session
        // "working" — activity is a property of a live process.
        assertEquals("Not running",
                SessionDots.statusWord(SessionStatus.EXITED, Optional.of(SessionActivity.BUSY)));
        assertEquals("Failed",
                SessionDots.statusWord(SessionStatus.FAILED, Optional.of(SessionActivity.NEEDS_ATTENTION)));
    }

    @Test
    void theDotPaletteIsTheHandoffsTokenTable() {
        assertEquals(SessionDots.RUNNING_BLUE,
                SessionDots.dotColor(SessionStatus.RUNNING, Optional.of(SessionActivity.BUSY)));
        assertEquals(SessionDots.ATTENTION_AMBER,
                SessionDots.dotColor(SessionStatus.RUNNING, Optional.of(SessionActivity.NEEDS_ATTENTION)));
        assertEquals(SessionDots.ERROR_RED, SessionDots.dotColor(SessionStatus.FAILED, NONE));
        assertEquals(SessionDots.IDLE_GRAY, SessionDots.dotColor(SessionStatus.INACTIVE, NONE));
    }

    @Test
    void iconsAreCachedPerStateSoRepaintsAllocateNothing() {
        Icon first = SessionDots.dotIcon(SessionStatus.RUNNING, NONE);
        Icon again = SessionDots.dotIcon(SessionStatus.RUNNING, NONE);

        assertSame(first, again);
        assertNotEquals(first, SessionDots.dotIcon(SessionStatus.INACTIVE, NONE));
    }
}
