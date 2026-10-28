package app.drydock.intellij.agent;

import app.drydock.domain.SessionActivity;
import app.drydock.domain.SessionStatus;

import app.drydock.intellij.ui.SessionDots;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The tab icon's status/activity decision ({@code AgentConsoleTabPresentation.iconState}).
 *
 * <p>These are the assertions worth having without an IDE: the mark must agree
 * with the strip beside it. A detached live session's strip says Running ·
 * not shown here, so its tab may not paint the hollow stopped mark the
 * window-local fact alone implies — while a genuinely stopped session keeps
 * the strip's persisted-status correction, and a session running here keeps
 * its real monitor activity. Every assertion goes through the real
 * {@link SessionDots} mapping the strip itself uses, not status constants
 * compared against themselves.</p>
 */
class AgentConsoleTabPresentationTest {

    private static String word(AgentConsoleTabPresentation.TabIcon icon) {
        return SessionDots.statusWord(icon.status(), icon.activity());
    }

    @Test
    void aDetachedLiveSessionPaintsTheLiveMarkEvenFromAStaleIdleRow() {
        // The catalog row says INACTIVE — its idle transition is where the
        // truth stopped — but a process runs for it in another window (or
        // with none at all). The strip beside this tab says Running · not
        // shown here; the tab must not answer that with a hollow dot.
        AgentConsoleTabPresentation.TabIcon icon = AgentConsoleTabPresentation.iconState(
                SessionStatus.INACTIVE, false, true, Optional.empty());

        assertEquals("Running", word(icon));
        assertNotEquals("Not running", word(icon));
        // The same color the strip's own detached rule paints (the strip's
        // word for the same decision), not the idle gray of a stopped one.
        assertEquals(SessionDots.dotColor(SessionStatus.RUNNING, Optional.empty()),
                SessionDots.dotColor(icon.status(), icon.activity()));
        assertNotEquals(SessionDots.IDLE_GRAY, SessionDots.dotColor(icon.status(), icon.activity()));
    }

    @Test
    void aDetachedLiveSessionPaintsTheLiveMarkEvenFromAStaleRunningRow() {
        // The harder stale row: persisted RUNNING with nothing running here.
        // The strip's displayStatus would correct that to INACTIVE — right
        // for a genuinely stopped session, wrong for one that runs elsewhere,
        // which is exactly why the detached rule has to outrank it.
        AgentConsoleTabPresentation.TabIcon icon = AgentConsoleTabPresentation.iconState(
                SessionStatus.RUNNING, false, true, Optional.empty());

        assertEquals("Running", word(icon));
        assertEquals(SessionDots.RUNNING_BLUE, SessionDots.dotColor(icon.status(), icon.activity()));
        assertNotEquals(SessionDots.IDLE_GRAY, SessionDots.dotColor(icon.status(), icon.activity()));
    }

    @Test
    void aDetachedLiveSessionCarriesNoActivityWord() {
        // Activity belongs to the process, and this window hosts none of it:
        // a leftover monitor value must not turn the detached tab's mark into
        // "Working" — the generic live word is the honest one here, the same
        // as the strip's.
        AgentConsoleTabPresentation.TabIcon icon = AgentConsoleTabPresentation.iconState(
                SessionStatus.RUNNING, false, true, Optional.of(SessionActivity.BUSY));

        assertEquals(Optional.empty(), icon.activity());
        assertEquals("Running", word(icon));
        assertNotEquals("Working", word(icon));
    }

    @Test
    void aGenuinelyStoppedSessionKeepsTheStripsPersistedCorrection() {
        // Nothing runs anywhere. A row stuck in the running band is stale and
        // must read as stopped, and an error status must survive the
        // correction rather than being flattened to "not running".
        AgentConsoleTabPresentation.TabIcon stale = AgentConsoleTabPresentation.iconState(
                SessionStatus.RUNNING, false, false, Optional.empty());
        assertEquals("Not running", word(stale));
        assertEquals(SessionDots.IDLE_GRAY, SessionDots.dotColor(stale.status(), stale.activity()));

        AgentConsoleTabPresentation.TabIcon failed = AgentConsoleTabPresentation.iconState(
                SessionStatus.FAILED, false, false, Optional.empty());
        assertEquals("Failed", word(failed));
        assertEquals(SessionDots.ERROR_RED, SessionDots.dotColor(failed.status(), failed.activity()));
    }

    @Test
    void aSessionRunningHereKeepsItsRealMonitorActivity() {
        // The one case with real activity to show: this window's own live
        // process, whose monitor reports into the word beside the dot.
        AgentConsoleTabPresentation.TabIcon busy = AgentConsoleTabPresentation.iconState(
                SessionStatus.RUNNING, true, true, Optional.of(SessionActivity.BUSY));
        assertEquals("Working", word(busy));
        assertEquals(Optional.of(SessionActivity.BUSY), busy.activity());

        AgentConsoleTabPresentation.TabIcon needing = AgentConsoleTabPresentation.iconState(
                SessionStatus.RUNNING, true, true, Optional.of(SessionActivity.NEEDS_ATTENTION));
        assertEquals("Needs you", word(needing));
        assertEquals(SessionDots.ATTENTION_AMBER,
                SessionDots.dotColor(needing.status(), needing.activity()));

        AgentConsoleTabPresentation.TabIcon unknown = AgentConsoleTabPresentation.iconState(
                SessionStatus.RUNNING, true, true, Optional.empty());
        assertEquals("Running", word(unknown));
    }
}
