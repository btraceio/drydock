package app.drydock.ui;

import app.drydock.app.UnknownSessionException;
import app.drydock.domain.ManagedSessionId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The startedWithTask marker is set on delivery, not on intent: the
 * {@link MainWorkspace#deliverThenMark} seam the review flow's prompt
 * delivery runs through.
 */
class MainWorkspacePromptDeliveryTest {

    @Test
    void aDeliveredPromptMarksTheSession() {
        List<String> events = new ArrayList<>();
        boolean delivered = MainWorkspace.deliverThenMark(() -> events.add("send"), () -> events.add("mark"));

        assertTrue(delivered);
        assertEquals(List.of("send", "mark"), events);
    }

    @Test
    void anUndeliveredPromptLeavesNoMarker() {
        List<String> events = new ArrayList<>();
        BooleanSupplier refused = () -> {
            events.add("send");
            return false;
        };

        boolean delivered = MainWorkspace.deliverThenMark(refused, () -> events.add("mark"));

        assertFalse(delivered);
        assertEquals(List.of("send"), events);
    }

    @Test
    void markingASessionThatVanishedIsSwallowed() {
        Runnable vanished = () -> {
            throw new UnknownSessionException(ManagedSessionId.newId());
        };

        boolean delivered = assertDoesNotThrow(() -> MainWorkspace.deliverThenMark(() -> true, vanished));

        assertTrue(delivered);
    }

    @Test
    void otherMarkFailuresStillPropagate() {
        Runnable broken = () -> {
            throw new IllegalStateException("boom");
        };

        assertThrows(IllegalStateException.class, () -> MainWorkspace.deliverThenMark(() -> true, broken));
    }

    @Test
    void markNeverRunsBeforeSend() {
        List<String> events = new ArrayList<>();
        BooleanSupplier send = () -> {
            assertTrue(events.isEmpty(), "nothing may run before the send: " + events);
            events.add("send");
            return true;
        };

        MainWorkspace.deliverThenMark(send, () -> events.add("mark"));

        assertEquals(List.of("send", "mark"), events);
    }
}
