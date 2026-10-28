package app.drydock.intellij.agent;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The command layer's decision table. These run without an IDE because the
 * whole point of {@link SessionCommands} is that the decisions are pure: any
 * drift between the tab, the strip and the tree starts here, in a table a
 * test can pin. {@code stateOf} itself reads the execution registry and is
 * covered where a registry exists (the platform tests); what needs pinning
 * here is what each state <em>means</em>.
 */
class SessionCommandsTest {

    private static SessionCommands.State state(boolean running, boolean resumable) {
        return new SessionCommands.State(running, resumable, false, false, false, false, false);
    }

    private static SessionCommands.State with(boolean starting, boolean stopping,
                                              boolean moving, boolean transferring) {
        return new SessionCommands.State(false, false, starting, stopping, moving, transferring, false);
    }

    /** A globally live session this window does not show: the detached fact. */
    private static SessionCommands.State detached(boolean resumable, boolean unsupported) {
        return new SessionCommands.State(true, resumable, false, false, false, false, unsupported, true);
    }

    @Test
    void aRunningSessionDeactivatesWhateverItMightResume() {
        assertEquals(SessionCommands.Lifecycle.DEACTIVATE, SessionCommands.lifecycle(state(true, false)));
        assertEquals(SessionCommands.Lifecycle.DEACTIVATE, SessionCommands.lifecycle(state(true, true)));
    }

    @Test
    void aGloballyLiveSessionThisWindowDoesNotShowIsShownNotDeactivated() {
        // The user-approved detached rule (A6, reconnect first): the tab is
        // open here but the execution runs elsewhere or without a window,
        // so the primary offers Show agent — never Deactivate, never a start.
        assertEquals(SessionCommands.Lifecycle.SHOW_AGENT,
                SessionCommands.lifecycle(detached(false, false)));
        // Whatever there might be to resume: the agent runs, so the choice
        // is about showing it, not about a stopped conversation.
        assertEquals(SessionCommands.Lifecycle.SHOW_AGENT,
                SessionCommands.lifecycle(detached(true, false)));
    }

    @Test
    void theGlobalScopeStillDeactivatesALiveSessionWhereverItRuns() {
        // The task tree's deliberate Deactivate (A6): the seven-argument
        // state is globally scoped, so a live session is never detached.
        SessionCommands.State global = new SessionCommands.State(true, false,
                false, false, false, false, false);
        assertFalse(global.detachedFromThisWindow());
        assertEquals(SessionCommands.Lifecycle.DEACTIVATE, SessionCommands.lifecycle(global));
    }

    @Test
    void aStoppedSessionResumesWhenItHasAConversationAndStartsWhenItDoesNot() {
        assertEquals(SessionCommands.Lifecycle.RESUME, SessionCommands.lifecycle(state(false, true)));
        assertEquals(SessionCommands.Lifecycle.START, SessionCommands.lifecycle(state(false, false)));
    }

    @Test
    void aDetachedSessionCannotShowWhileAnOperationIsInFlight() {
        // Busy still wins over Show agent: the session belongs to the
        // in-flight operation, wherever that operation runs.
        for (int flag = 0; flag < 4; flag++) {
            SessionCommands.State busy = new SessionCommands.State(true, false,
                    flag == 0, flag == 1, flag == 2, flag == 3, false, true);
            assertFalse(SessionCommands.lifecycleEnabled(busy), "flag " + flag);
        }
        assertTrue(SessionCommands.lifecycleEnabled(detached(false, false)));
    }

    @Test
    void aDetachedSessionCanShowEvenWhenItsAgentIsUnsupported() {
        // Showing an existing execution is a question about the present, like
        // Deactivate: whether this build could have started the agent
        // (the stopped rule) does not gate it.
        assertTrue(SessionCommands.lifecycleEnabled(detached(false, true)));
    }

    @Test
    void theLifecycleLabelsAndIconsAreTheAgreedOnes() {
        assertEquals("Deactivate", SessionCommands.Lifecycle.DEACTIVATE.label());
        assertEquals("Resume", SessionCommands.Lifecycle.RESUME.label());
        assertEquals("Start", SessionCommands.Lifecycle.START.label());
        assertEquals("Show agent", SessionCommands.Lifecycle.SHOW_AGENT.label());
        for (SessionCommands.Lifecycle lifecycle : SessionCommands.Lifecycle.values()) {
            assertTrue(lifecycle.icon() != null, lifecycle + " has no icon");
            assertFalse(lifecycle.label().isBlank(), lifecycle + " has no label");
        }
    }

    @Test
    void theCommandLabelsAndIconsAreTheAgreedOnes() {
        assertEquals("Project window", SessionCommands.Command.OWN_PROJECT.label());
        assertEquals("Changes", SessionCommands.Command.CHANGES.label());
        assertEquals("Transfer…", SessionCommands.Command.TRANSFER.label());
        assertEquals("Done…", SessionCommands.Command.DONE.label());
        for (SessionCommands.Command command : SessionCommands.Command.values()) {
            assertTrue(command.icon() != null, command + " has no icon");
            assertFalse(command.label().isBlank(), command + " has no label");
        }
    }

    @Test
    void eachInFlightSpanAloneMakesTheSessionBusy() {
        assertTrue(SessionCommands.busy(with(true, false, false, false)));
        assertTrue(SessionCommands.busy(with(false, true, false, false)));
        assertTrue(SessionCommands.busy(with(false, false, true, false)));
        assertTrue(SessionCommands.busy(with(false, false, false, true)));
        assertFalse(SessionCommands.busy(with(false, false, false, false)));
    }

    @Test
    void aBusySessionCannotRunItsLifecycle() {
        // Running, so DEACTIVATE is the lifecycle and would otherwise be
        // clickable; each in-flight span alone must hold it off.
        for (int flag = 0; flag < 4; flag++) {
            SessionCommands.State busy = new SessionCommands.State(true, false,
                    flag == 0, flag == 1, flag == 2, flag == 3, false);
            assertFalse(SessionCommands.lifecycleEnabled(busy), "flag " + flag);
        }
    }

    @Test
    void aStoppedSupportedSessionCanStart() {
        assertTrue(SessionCommands.lifecycleEnabled(state(false, false)));
        assertTrue(SessionCommands.lifecycleEnabled(state(false, true)));
    }

    @Test
    void aStoppedUnsupportedSessionHasNoLifecycleAtAll() {
        // Nothing to resume into: the resume would pick a provider at random
        // or fail at the command builder, so the command never appears.
        SessionCommands.State unsupported =
                new SessionCommands.State(false, false, false, false, false, false, true);
        assertFalse(SessionCommands.lifecycleEnabled(unsupported));
    }

    @Test
    void aRunningSessionCanBeDeactivatedEvenWhenItsAgentIsUnsupported() {
        // The agent runs here now; ending it is a question about the present,
        // not about whether this build could have started it.
        SessionCommands.State unsupported =
                new SessionCommands.State(true, false, false, false, false, false, true);
        assertTrue(SessionCommands.lifecycleEnabled(unsupported));
    }

    @Test
    void doneAndOwnProjectFollowTheBusyGuardAndNothingElse() {
        for (int flag = 0; flag < 4; flag++) {
            SessionCommands.State busy = new SessionCommands.State(false, false,
                    flag == 0, flag == 1, flag == 2, flag == 3, false);
            assertFalse(SessionCommands.doneEnabled(busy), "flag " + flag);
            assertFalse(SessionCommands.ownProjectEnabled(busy), "flag " + flag);
        }
        // Neither the run state, the resumability nor the agent kind gates
        // them: a stopped, unsupported session can still be tidied up and
        // its project window still opened.
        SessionCommands.State idleUnsupported =
                new SessionCommands.State(false, false, false, false, false, false, true);
        assertTrue(SessionCommands.doneEnabled(idleUnsupported));
        assertTrue(SessionCommands.ownProjectEnabled(idleUnsupported));
        assertTrue(SessionCommands.doneEnabled(state(true, false)));
        assertTrue(SessionCommands.ownProjectEnabled(state(true, false)));
    }

    @Test
    void theBusyLabelNamesTheFirstCauseAmongOverlappingSpans() {
        assertEquals(Optional.of("Starting…"),
                SessionCommands.busyLabel(with(true, true, true, true)));
        assertEquals(Optional.of("Deactivating…"),
                SessionCommands.busyLabel(with(false, true, true, true)));
        assertEquals(Optional.of("Opening project window…"),
                SessionCommands.busyLabel(with(false, false, true, true)));
        assertEquals(Optional.of("Transferring…"),
                SessionCommands.busyLabel(with(false, false, false, true)));
        assertEquals(Optional.empty(), SessionCommands.busyLabel(with(false, false, false, false)));
    }

    @Test
    void aColdSessionWithARecordedIdIsResumableThroughTheRealStripRules() {
        // The one sanctioned cold fallback: a provider that cannot be asked
        // keeps Resume, because the id it was given at launch is real.
        assertTrue(SessionCommands.resumable(Optional.empty(), true));
    }

    @Test
    void aColdSessionWithNoRecordedIdStartsInstead() {
        assertFalse(SessionCommands.resumable(Optional.empty(), false));
    }

    @Test
    void aKnownAnswerBeatsTheColdFallback() {
        // A warmed cache that found no transcript wins over the id: Claude
        // gets its id at launch, and stopped before its first turn it has an
        // id and nothing on disk.
        assertFalse(SessionCommands.resumable(Optional.of(false), true));
        // And a warmed "resumable" stands even for a session whose id is not
        // recorded — the cache answered the question the id only approximates.
        assertTrue(SessionCommands.resumable(Optional.of(true), false));
    }
}
