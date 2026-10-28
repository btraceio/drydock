package app.drydock.intellij.agent;

import com.intellij.openapi.actionSystem.ActionPlaces;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tab-command decisions, headless and pure: they are functions of a
 * {@link SessionCommands.State} snapshot and a name, so any drift between
 * the header and the popup starts here, in a table a test can pin.
 * {@link AgentTabCommands#resolve} reads the platform (the catalog index,
 * the data-context walk) and is covered by the platform action tests; what
 * needs pinning here is what each state <em>means</em>. Nothing here is
 * rendered on screen.
 */
class AgentTabCommandsTest {

    private static final String NAME = "Fix the flaky test";

    /** An idle state: nothing in flight, nothing blocking. */
    private static SessionCommands.State idle(boolean running, boolean resumable) {
        return new SessionCommands.State(running, resumable, false, false, false, false, false);
    }

    /** One busy flag set, all others clear. */
    private static SessionCommands.State busy(String flag) {
        return switch (flag) {
            case "starting" -> new SessionCommands.State(false, false, true, false, false, false, false);
            case "stopping" -> new SessionCommands.State(true, false, false, true, false, false, false);
            case "moving" -> new SessionCommands.State(true, false, false, false, true, false, false);
            case "transferring" -> new SessionCommands.State(true, false, false, false, false, true, false);
            default -> throw new IllegalArgumentException(flag);
        };
    }

    /** The state a caller with no editor-derived answer passes: cold, no flags. */
    private static SessionCommands.State cold(boolean hasConversationFallback) {
        return new SessionCommands.State(false,
                SessionCommands.resumable(Optional.empty(), hasConversationFallback),
                false, false, false, false, false);
    }

    @Test
    void onlyTheTwoTabPlacesAreSupported() {
        assertTrue(AgentTabCommands.placeSupported(ActionPlaces.EDITOR_TAB));
        assertTrue(AgentTabCommands.placeSupported(ActionPlaces.EDITOR_TAB_POPUP));
        assertFalse(AgentTabCommands.placeSupported(ActionPlaces.EDITOR_POPUP));
        assertFalse(AgentTabCommands.placeSupported(ActionPlaces.ACTION_SEARCH));
        assertFalse(AgentTabCommands.placeSupported(""));
        assertTrue(AgentTabCommands.isPopup(ActionPlaces.EDITOR_TAB_POPUP));
        assertFalse(AgentTabCommands.isPopup(ActionPlaces.EDITOR_TAB));
    }

    @Test
    void everyBusyFlagDisablesEveryCommandAndNamesItself() {
        for (String flag : new String[]{"starting", "stopping", "moving", "transferring"}) {
            SessionCommands.State state = busy(flag);
            AgentTabCommands.Shown own =
                    AgentTabCommands.ownProject(true, state, NAME, true);
            assertFalse(own.enabled(), flag + " must disable own-project");
            assertTrue(own.visible(), "a hosted session keeps its own-project command visible");
            AgentTabCommands.Shown lifecycle = AgentTabCommands.lifecycle(state, NAME, true);
            assertFalse(lifecycle.enabled(), flag + " must disable the lifecycle command");
            assertEquals(SessionCommands.busyLabel(state).orElseThrow(), lifecycle.text(),
                    flag + " must say what is in flight rather than the command it refuses");
            assertEquals(SessionCommands.busyLabel(state).orElseThrow(), lifecycle.tooltip());
            AgentTabCommands.Shown done = AgentTabCommands.done(state, NAME, true);
            assertFalse(done.enabled(), flag + " must disable Done");
            assertTrue(done.visible());
            if ("moving".equals(flag)) {
                // The strip's Changes link is gated by a move and a gone
                // checkout only (AgentConsoleStrip) — it stays clickable
                // while a stop or a transfer is in flight, and so does the
                // popup.
                assertFalse(AgentTabCommands.changes(Optional.of(false), false, state, NAME, true)
                                .enabled(),
                        flag + " must disable Changes");
            }
        }
    }

    @Test
    void anIdleSessionEnablesEverythingApplicable() {
        SessionCommands.State state = idle(true, false);
        assertTrue(AgentTabCommands.ownProject(true, state, NAME, true).enabled());
        assertTrue(AgentTabCommands.ownProject(true, state, NAME, false).enabled());
        assertTrue(AgentTabCommands.lifecycle(state, NAME, false).enabled());
        assertTrue(AgentTabCommands.done(state, NAME, false).enabled());
        assertTrue(AgentTabCommands.changes(Optional.of(false), false, state, NAME, true).enabled());
        assertTrue(AgentTabCommands.transfer(true, state, NAME, true).enabled());
        assertTrue(AgentTabCommands.rename(state, NAME, true).enabled());
    }

    @Test
    void tooltipsNameTheSession() {
        assertEquals("Open " + NAME + " in its own project window",
                AgentTabCommands.ownProject(true, idle(true, false), NAME, true).tooltip());
        assertEquals("Deactivate " + NAME,
                AgentTabCommands.lifecycle(idle(true, false), NAME, true).tooltip());
        assertEquals("Resume " + NAME,
                AgentTabCommands.lifecycle(idle(false, true), NAME, true).tooltip());
        assertEquals("Start " + NAME,
                AgentTabCommands.lifecycle(idle(false, false), NAME, true).tooltip());
        assertEquals("Show " + NAME + "'s changes",
                AgentTabCommands.changes(Optional.of(false), false, idle(true, false), NAME, true).tooltip());
        assertEquals("Show " + NAME + "'s changes in its own project window",
                AgentTabCommands.changes(Optional.of(false), true, idle(true, false), NAME, true).tooltip());
        assertEquals("Done with " + NAME + "…",
                AgentTabCommands.done(idle(true, false), NAME, true).tooltip());
    }

    @Test
    void aColdSessionLabelsThroughTheStripFallback() {
        assertEquals("Resume", AgentTabCommands.lifecycle(cold(true), NAME, false).text());
        assertEquals("Resume " + NAME, AgentTabCommands.lifecycle(cold(true), NAME, false).tooltip());
        assertEquals("Start", AgentTabCommands.lifecycle(cold(false), NAME, false).text());
        assertEquals("Start " + NAME, AgentTabCommands.lifecycle(cold(false), NAME, false).tooltip());
    }

    @Test
    void aWindowDetachedLiveExecutionIsShownNotDeactivatedOrStarted() {
        // A6, reconnect first: the tab is open here but the execution runs
        // elsewhere or without a window, so the primary offers Show agent
        // in BOTH surfaces — never Deactivate from a detached view, never a
        // start — even when a conversation exists to resume into: the agent
        // runs, the question is showing it.
        for (boolean popup : new boolean[]{false, true}) {
            SessionCommands.State detached = new SessionCommands.State(true, true,
                    false, false, false, false, false, true);
            AgentTabCommands.Shown shown = AgentTabCommands.lifecycle(detached, NAME, popup);
            assertTrue(shown.visible());
            assertTrue(shown.enabled(), "a detached live agent can always be shown");
            assertEquals("Show agent", shown.text());
            assertEquals("Show agent " + NAME, shown.tooltip());
            assertEquals(SessionCommands.Lifecycle.SHOW_AGENT.icon(), shown.icon());
        }
        // The busy rule still wins over Show agent: the session belongs to
        // the in-flight operation, wherever it runs.
        SessionCommands.State moving = new SessionCommands.State(true, false,
                false, false, true, false, false, true);
        AgentTabCommands.Shown busy = AgentTabCommands.lifecycle(moving, NAME, true);
        assertFalse(busy.enabled(), "a move in flight must disable the lifecycle");
        assertEquals("Opening project window…", busy.text());
    }

    @Test
    void changesKeepsTheStripSemantics() {
        SessionCommands.State stopped = idle(false, true);
        assertFalse(AgentTabCommands.changes(Optional.of(false), false, stopped, NAME, true).visible(),
                "a stopped session has no Changes in the popup");
        SessionCommands.State running = idle(true, false);
        assertFalse(AgentTabCommands.changes(Optional.empty(), false, running, NAME, true).visible(),
                "an unknown checkout fact hides Changes rather than offering a blind click");
        AgentTabCommands.Shown healthy =
                AgentTabCommands.changes(Optional.of(false), false, running, NAME, true);
        assertTrue(healthy.visible());
        assertTrue(healthy.enabled());
        assertEquals("Changes", healthy.text());
        AgentTabCommands.Shown gone =
                AgentTabCommands.changes(Optional.of(true), false, running, NAME, true);
        assertTrue(gone.visible());
        assertFalse(gone.enabled(), "a gone checkout disables Changes");
        SessionCommands.State moving = busy("moving");
        assertFalse(AgentTabCommands.changes(Optional.of(false), false, moving, NAME, true).enabled(),
                "a move in flight disables Changes");
        AgentTabCommands.Shown hosted =
                AgentTabCommands.changes(Optional.of(false), true, running, NAME, true);
        assertEquals("Changes ↗", hosted.text());
        assertEquals(SessionCommands.Command.CHANGES.icon(), hosted.icon());
    }

    @Test
    void thePopupOnlyCommandsHideInTheHeader() {
        SessionCommands.State state = idle(true, false);
        assertFalse(AgentTabCommands.changes(Optional.of(false), false, state, NAME, false).visible());
        assertFalse(AgentTabCommands.transfer(true, state, NAME, false).visible());
        assertFalse(AgentTabCommands.rename(state, NAME, false).visible());
        assertTrue(AgentTabCommands.ownProject(true, state, NAME, false).visible(),
                "the header carries the own-project shortcut");
        assertTrue(AgentTabCommands.lifecycle(state, NAME, false).visible(),
                "the header carries the lifecycle shortcut");
        assertTrue(AgentTabCommands.done(state, NAME, false).visible(),
                "the header carries the Done shortcut");
    }

    @Test
    void ownProjectAppearsOnlyWhenHosted() {
        SessionCommands.State state = idle(true, false);
        assertFalse(AgentTabCommands.ownProject(false, state, NAME, true).visible(),
                "already in the owning project: nothing to jump to");
        AgentTabCommands.Shown popup = AgentTabCommands.ownProject(true, state, NAME, true);
        assertEquals("Open in Own Project Window", popup.text());
        AgentTabCommands.Shown header = AgentTabCommands.ownProject(true, state, NAME, false);
        assertEquals(SessionCommands.Command.OWN_PROJECT.label(), header.text());
        assertEquals(SessionCommands.Command.OWN_PROJECT.icon(), popup.icon());
    }

    @Test
    void transferKeepsTheStripRules() {
        SessionCommands.State running = idle(true, false);
        assertTrue(AgentTabCommands.transfer(true, running, NAME, true).visible());
        assertEquals("Transfer…", AgentTabCommands.transfer(true, running, NAME, true).text());
        assertEquals(SessionCommands.Command.TRANSFER.icon(), AgentTabCommands.transfer(true, running, NAME, true).icon());
        assertFalse(AgentTabCommands.transfer(false, running, NAME, true).visible(),
                "no transfer available: nothing to offer");
        assertFalse(AgentTabCommands.transfer(true, idle(false, true), NAME, true).visible(),
                "a stopped session has no live agent to transfer");
        assertFalse(AgentTabCommands.transfer(true, busy("transferring"), NAME, true).visible(),
                "a transfer in flight is not offered again");
        assertFalse(AgentTabCommands.transfer(true, busy("moving"), NAME, true).enabled(),
                "a move in flight disables Transfer");
    }

    @Test
    void renameAndDoneSayWhatTheyDo() {
        AgentTabCommands.Shown rename = AgentTabCommands.rename(idle(true, false), NAME, true);
        assertTrue(rename.visible());
        assertTrue(rename.enabled());
        assertEquals("Rename…", rename.text());
        assertNull(rename.icon(), "Rename has no agreed icon; the popup shows text only");
        AgentTabCommands.Shown done = AgentTabCommands.done(idle(true, false), NAME, false);
        assertEquals("Done…", done.text());
        assertEquals(SessionCommands.Command.DONE.icon(), done.icon());
    }
}
