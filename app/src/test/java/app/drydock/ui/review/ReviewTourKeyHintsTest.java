package app.drydock.ui.review;

import app.drydock.testing.FxSync;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The hint strip on the real board: what it says, h, and the persisted choice. */
class ReviewTourKeyHintsTest extends ReviewTourFixture {

    private void key(KeyCode code) {
        press(code).release(code);
        FxSync.waitForFxEvents();
    }

    private Node strip() {
        return lookup(".tour-key-strip").query();
    }

    @Test
    void theTourShowsTheKeysThatWorkWhileAPredictIsOpen() {
        assertTrue(strip().isVisible());
        assertTrue(lookup("answer the check").tryQuery().isPresent());
        assertTrue(lookup("approve").tryQuery().isEmpty(), "approve is not what an open PREDICT asks for");
    }

    @Test
    void answeringChangesWhatTheStripOffers() {
        key(KeyCode.DIGIT2);

        assertTrue(lookup("answer the check").tryQuery().isEmpty());
        assertTrue(lookup("approve").tryQuery().isPresent());
        assertTrue(lookup("request changes").tryQuery().isPresent());
    }

    @Test
    void hOnTheKeyboardHidesTheHintsAndLeavesOnlyTheShortcutsButton() {
        key(KeyCode.H);

        assertTrue(lookup("answer the check").tryQuery().isEmpty());
        assertEquals("Shortcuts", lookup(".tour-key-strip-toggle").queryAs(Button.class).getText());
        assertTrue(strip().isVisible(), "the way back stays on screen");

        key(KeyCode.H);
        assertTrue(lookup("answer the check").tryQuery().isPresent());
    }

    @Test
    void theChoiceIsReportedToItsPersistedHomeAndReadBackOnTheNextRender() {
        List<Boolean> saved = new ArrayList<>();
        AtomicBoolean stored = new AtomicBoolean(false);
        interact(() -> view.setKeyHintsPreference(stored::get, hidden -> {
            saved.add(hidden);
            stored.set(hidden);
        }));

        key(KeyCode.H);
        assertEquals(List.of(true), saved);

        // Another board hid the hints meanwhile: this one follows at its next render.
        interact(() -> stored.set(false));
        key(KeyCode.DIGIT2);
        assertTrue(lookup("approve").tryQuery().isPresent(), "hints are shown again, now for the settled check");
    }

    @Test
    void theHunkDiffHasNoStripAndHDoesNothingThere() throws Exception {
        key(KeyCode.V);
        focusDiffColumn();

        assertFalse(strip().isManaged());
        key(KeyCode.H);
        key(KeyCode.V);
        assertTrue(lookup("answer the check").tryQuery().isPresent(), "h in the hunk diff changed nothing");
    }
}
