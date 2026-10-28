package app.drydock.intellij.agent;

import app.drydock.domain.SessionActivity;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The single-session stop policy, as one pure table. The surfaces that stop
 * a session — the tree, the ribbon, the tab header, the popup — all route
 * through {@link SessionCommandHandlers#stopStep}, so this table is the
 * policy everywhere; what a real dialog looks like is verification-stage
 * work (A5, A6, A11), not something a headless test can pin.
 */
class SessionCommandHandlersTest {

    private static final List<Optional<SessionActivity>> READABLE = List.of(
            Optional.of(SessionActivity.IDLE), Optional.of(SessionActivity.NEEDS_ATTENTION),
            Optional.of(SessionActivity.BUSY), Optional.of(SessionActivity.UNKNOWN), Optional.empty());

    @Test
    void anAgentAtItsPromptOrWaitingForTheUserJustStops() {
        assertEquals(SessionCommandHandlers.StopStep.STOP,
                SessionCommandHandlers.stopStep(false, true, Optional.of(SessionActivity.IDLE)));
        assertEquals(SessionCommandHandlers.StopStep.STOP,
                SessionCommandHandlers.stopStep(false, true, Optional.of(SessionActivity.NEEDS_ATTENTION)));
    }

    @Test
    void aBusyOrUnreadableAgentAsksFirst() {
        assertEquals(SessionCommandHandlers.StopStep.ASK,
                SessionCommandHandlers.stopStep(false, true, Optional.of(SessionActivity.BUSY)));
        assertEquals(SessionCommandHandlers.StopStep.ASK,
                SessionCommandHandlers.stopStep(false, true, Optional.of(SessionActivity.UNKNOWN)));
        assertEquals(SessionCommandHandlers.StopStep.ASK,
                SessionCommandHandlers.stopStep(false, true, Optional.empty()));
    }

    @Test
    void aSessionBeingTransferredIsNeverStoppedFromHere() {
        for (Optional<SessionActivity> activity : READABLE) {
            assertEquals(SessionCommandHandlers.StopStep.NOTHING,
                    SessionCommandHandlers.stopStep(true, true, activity), "transferring, " + activity);
        }
    }

    @Test
    void aSessionThatAlreadyEndedIsLeftAlone() {
        for (Optional<SessionActivity> activity : READABLE) {
            assertEquals(SessionCommandHandlers.StopStep.NOTHING,
                    SessionCommandHandlers.stopStep(false, false, activity), "stopped, " + activity);
        }
    }
}
