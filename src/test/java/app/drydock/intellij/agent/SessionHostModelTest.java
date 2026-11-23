package app.drydock.intellij.agent;

import app.drydock.domain.ManagedSessionId;
import app.drydock.intellij.agent.SessionHostModel.Outcome;
import app.drydock.intellij.agent.SessionHostModel.Slot;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Primary/held/previous transitions of the Sessions document, off-platform. */
class SessionHostModelTest {

    private final SessionHostModel model = new SessionHostModel();
    private final ManagedSessionId a = ManagedSessionId.newId();
    private final ManagedSessionId b = ManagedSessionId.newId();
    private final ManagedSessionId c = ManagedSessionId.newId();

    @Test
    void holdMovesPrimaryToHeldAndLeavesPrimaryEmpty() {
        model.show(a);
        assertTrue(model.hold());
        assertEquals(Optional.of(a), model.held());
        assertEquals(Optional.empty(), model.primary());
        assertEquals(Slot.HELD, model.focusedSlot());
    }

    @Test
    void showingTheHeldSessionFocusesItInPlace() {
        model.show(a);
        model.hold();
        model.show(b);
        assertEquals(Outcome.FOCUSED_HELD, model.show(a));
        assertEquals(Optional.of(a), model.held());
        assertEquals(Optional.of(b), model.primary());
        assertEquals(Slot.HELD, model.focusedSlot());
    }

    @Test
    void holdIsDisabledWithAReasonWhileTheHeldSlotIsOccupied() {
        model.show(a);
        model.hold();
        model.show(b);
        assertFalse(model.holdState().enabled());
        assertFalse(model.holdState().reason().isBlank());
        assertFalse(model.hold());
        assertEquals(Optional.of(a), model.held());
    }

    @Test
    void closingHeldFocusesNothingAndNeverAnotherSession() {
        model.show(a);
        model.hold();
        model.show(b);
        model.show(a);
        model.closeHeld();
        assertEquals(Optional.empty(), model.held());
        assertEquals(Slot.NONE, model.focusedSlot());
        assertEquals(Optional.of(b), model.primary());
        assertTrue(model.holdState().enabled());
    }

    @Test
    void previousTogglesBetweenTwoSessions() {
        model.show(a);
        model.show(b);
        assertEquals(Outcome.SHOWN_IN_PRIMARY, model.showPrevious());
        assertEquals(Optional.of(a), model.primary());
        model.showPrevious();
        assertEquals(Optional.of(b), model.primary());
        assertEquals(Optional.of(a), model.previous());
    }

    @Test
    void previousTogglesRepeatedlyAcrossTwentySessions() {
        List<ManagedSessionId> ids = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            ids.add(ManagedSessionId.newId());
            model.show(ids.get(i));
        }
        ManagedSessionId last = ids.get(19);
        ManagedSessionId beforeLast = ids.get(18);
        for (int i = 0; i < 10; i++) {
            model.showPrevious();
            assertEquals(Optional.of(beforeLast), model.primary());
            model.showPrevious();
            assertEquals(Optional.of(last), model.primary());
        }
        assertEquals(Optional.of(beforeLast), model.previous());
    }

    @Test
    void previousCanTargetTheHeldSessionAndFocusesItInPlace() {
        model.show(a);
        model.hold();
        model.show(b);
        assertEquals(Outcome.FOCUSED_HELD, model.showPrevious());
        assertEquals(Optional.of(a), model.held());
        assertEquals(Optional.of(b), model.primary());
        assertEquals(Outcome.SHOWN_IN_PRIMARY, model.showPrevious());
        assertEquals(Optional.of(b), model.primary());
        assertEquals(Slot.PRIMARY, model.focusedSlot());
    }

    @Test
    void repeatingTheSameIdNeverPushesHistory() {
        model.show(a);
        model.show(b);
        model.show(b);
        model.show(b);
        assertEquals(Optional.of(a), model.previous());
        assertEquals(Optional.of(b), model.current());
    }

    @Test
    void onlyIntentionalEventsMoveThePair() {
        model.show(a);
        model.show(b);
        model.hold();
        model.closeHeld();
        model.invalidate(c);
        model.revalidate(c);
        model.holdState();
        assertEquals(Optional.of(b), model.current());
        assertEquals(Optional.of(a), model.previous());
    }

    @Test
    void focusingAPaneIsIntentionalButAnEmptyPaneIsNot() {
        model.show(a);
        model.hold();
        model.show(b);
        model.focusSlot(Slot.HELD);
        assertEquals(Optional.of(a), model.current());
        assertEquals(Optional.of(b), model.previous());
        model.closeHeld();
        model.focusSlot(Slot.HELD);
        assertEquals(Slot.NONE, model.focusedSlot());
        assertEquals(Optional.of(a), model.current());
    }

    @Test
    void aMissingTargetStaysUnavailableWithoutSubstitution() {
        model.show(a);
        model.show(b);
        model.invalidate(a);
        assertEquals(Outcome.UNAVAILABLE, model.showPrevious());
        assertEquals(Optional.of(b), model.primary());
        assertEquals(Optional.of(b), model.current());
        assertEquals(Optional.of(a), model.previous());
        assertFalse(model.isAvailable(a));
        assertEquals(Outcome.UNAVAILABLE, model.show(a));
        model.revalidate(a);
        assertEquals(Outcome.SHOWN_IN_PRIMARY, model.showPrevious());
    }

    @Test
    void invalidatingOneSessionEmptiesOnlyItsPane() {
        model.show(a);
        model.hold();
        model.show(b);
        model.invalidate(b);
        assertEquals(Optional.empty(), model.primary());
        assertEquals(Optional.of(a), model.held());
        model.show(c);
        model.invalidate(a);
        assertEquals(Optional.empty(), model.held());
        assertEquals(Optional.of(c), model.primary());
    }

    @Test
    void invalidatingTheCurrentSessionKeepsTheIntentPairAndEmptiesOnlyItsSlot() {
        model.show(a);
        model.show(b);
        model.invalidate(b);
        assertEquals(Optional.empty(), model.primary());
        assertEquals(Slot.NONE, model.focusedSlot());
        assertEquals(Optional.of(b), model.current());
        assertEquals(Optional.of(a), model.previous());
        assertEquals(Outcome.SHOWN_IN_PRIMARY, model.showPrevious());
        assertEquals(Optional.of(a), model.primary());
        assertEquals(Outcome.UNAVAILABLE, model.showPrevious());
        assertEquals(Optional.of(a), model.primary());
    }

    @Test
    void passiveRestorePlacesPrimaryWithNeutralFocusAndNoIntentHistory() {
        assertTrue(model.restorePrimary(a));
        assertEquals(Optional.of(a), model.primary());
        assertEquals(Slot.NONE, model.focusedSlot());
        assertEquals(Optional.empty(), model.held());
        assertEquals(Optional.empty(), model.current());
        assertEquals(Optional.empty(), model.previous());
        assertEquals(Outcome.NO_PREVIOUS, model.showPrevious());
    }

    @Test
    void passiveRestoreRefusesAKnownUnavailableTargetWithoutChange() {
        model.invalidate(a);
        assertFalse(model.restorePrimary(a));
        assertEquals(Optional.empty(), model.primary());
        assertEquals(Slot.NONE, model.focusedSlot());
    }

    @Test
    void passiveRestoreNeverOverwritesActivePrimaryHeldOrHistoryOnlyState() {
        model.show(a);
        assertFalse(model.restorePrimary(b));
        assertEquals(Optional.of(a), model.primary());
        assertEquals(Slot.PRIMARY, model.focusedSlot());

        model.hold();
        assertFalse(model.restorePrimary(b));
        assertEquals(Optional.of(a), model.held());
        assertEquals(Optional.empty(), model.primary());

        model.closeHeld();
        model.show(b);
        model.invalidate(b);
        assertEquals(Optional.empty(), model.primary());
        assertFalse(model.restorePrimary(c));
        assertEquals(Optional.empty(), model.primary());
        assertEquals(Optional.of(b), model.current());
        assertEquals(Optional.of(a), model.previous());
    }
}
