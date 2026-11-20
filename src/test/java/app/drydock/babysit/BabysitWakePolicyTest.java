package app.drydock.babysit;

import app.drydock.babysit.BabysitWakePolicy.Decision;
import app.drydock.domain.SessionActivity;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BabysitWakePolicyTest {

    @Test
    void aStartInFlightIsAlwaysBusy() {
        for (boolean running : new boolean[] {true, false}) {
            for (boolean bound : new boolean[] {true, false}) {
                assertEquals(Decision.SKIP_BUSY, BabysitWakePolicy.decide(running, true,
                        Optional.of(SessionActivity.IDLE), bound));
            }
        }
    }

    @Test
    void aRunningAgentIsTypedIntoOnlyWhenItReportsIdle() {
        assertEquals(Decision.WAKE_VIA_SUBMIT,
                BabysitWakePolicy.decide(true, false, Optional.of(SessionActivity.IDLE), true));
        assertEquals(Decision.WAKE_VIA_SUBMIT,
                BabysitWakePolicy.decide(true, false, Optional.of(SessionActivity.IDLE), false),
                "typing into a live agent needs no bound id");
        assertEquals(Decision.SKIP_BUSY,
                BabysitWakePolicy.decide(true, false, Optional.of(SessionActivity.BUSY), true));
        assertEquals(Decision.SKIP_BUSY,
                BabysitWakePolicy.decide(true, false, Optional.of(SessionActivity.UNKNOWN), true));
        assertEquals(Decision.SKIP_BUSY,
                BabysitWakePolicy.decide(true, false, Optional.of(SessionActivity.NEEDS_ATTENTION), true),
                "a paste into a permission prompt would answer it");
        assertEquals(Decision.SKIP_BUSY, BabysitWakePolicy.decide(true, false, Optional.empty(), true),
                "Codex reports no activity: busy is unknowable, so the monitor retries");
    }

    @Test
    void aStoppedAgentIsResumedOnlyWithABoundConversation() {
        assertEquals(Decision.WAKE_VIA_RESUME, BabysitWakePolicy.decide(false, false, Optional.empty(), true));
        assertEquals(Decision.WAKE_VIA_RESUME,
                BabysitWakePolicy.decide(false, false, Optional.of(SessionActivity.BUSY), true),
                "a stale activity of an ended agent does not hold the resume off");
        assertEquals(Decision.SKIP_UNBOUND, BabysitWakePolicy.decide(false, false, Optional.empty(), false));
    }

    @Test
    void aWakePointerIsOneCleanLine() {
        assertTrue(BabysitWakePolicy.isWakePointer("Read /tmp/babysit/delta-3.md"));
        assertFalse(BabysitWakePolicy.isWakePointer(null));
        assertFalse(BabysitWakePolicy.isWakePointer("  "));
        assertFalse(BabysitWakePolicy.isWakePointer("Read\nthis"));
        assertFalse(BabysitWakePolicy.isWakePointer("Read\rthis"));
        assertFalse(BabysitWakePolicy.isWakePointer("Read \u001b[201~ this"));
        assertFalse(BabysitWakePolicy.isWakePointer("Read\tthis"));
    }
}
