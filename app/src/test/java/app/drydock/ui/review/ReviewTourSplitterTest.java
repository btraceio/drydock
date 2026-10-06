package app.drydock.ui.review;

import app.drydock.testing.FxSync;
import javafx.scene.Node;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Region;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The tour's step panel resized on the real board: drag, floor, persistence, reset. */
class ReviewTourSplitterTest extends ReviewTourFixture {

    private Region panel() {
        return lookup(".step-panel").query();
    }

    private Region codeColumn() {
        return lookup(".review-body").query();
    }

    private Node splitter() {
        return lookup(".review-splitter").query();
    }

    @Test
    void theTourHasASplitterAndTheDefaultWidthIsUnchanged() {
        assertTrue(splitter().isVisible());
        assertEquals(StepPanel.EXPANDED_WIDTH, panel().getWidth(), 0.5);
    }

    @Test
    void draggingTheSplitterLeftWidensTheStepPanelAndTakesTheWidthFromTheCode() {
        double codeBefore = codeColumn().getWidth();

        drag(splitter()).dropBy(-120, 0);
        FxSync.waitForFxEvents();

        assertEquals(StepPanel.EXPANDED_WIDTH + 120, panel().getWidth(), 2);
        assertEquals(codeBefore - 120, codeColumn().getWidth(), 2);
    }

    @Test
    void theCodeColumnKeepsItsFloorHoweverFarTheSplitterIsDragged() {
        drag(splitter()).dropBy(-900, 0);
        FxSync.waitForFxEvents();

        assertTrue(codeColumn().getWidth() >= RailLayout.CODE_MIN_WIDTH - 1,
                "code is " + codeColumn().getWidth());
        assertTrue(panel().getWidth() > StepPanel.EXPANDED_WIDTH, "it did widen, up to the floor");
    }

    @Test
    void theFinalWidthIsReportedOnceWhenTheDragEnds() {
        List<Double> saved = new ArrayList<>();
        interact(() -> view.setStepPanelWidthPreference(() -> 0, saved::add));

        drag(splitter()).dropBy(-80, 0);
        FxSync.waitForFxEvents();

        assertEquals(1, saved.size(), "one write per gesture");
        assertEquals(panel().getWidth(), saved.getFirst(), 1);
    }

    @Test
    void aStoredWidthIsAppliedWhenTheTourShowsAgain() {
        AtomicReference<Double> stored = new AtomicReference<>(0.0);
        interact(() -> view.setStepPanelWidthPreference(stored::get, ignored -> { }));
        assertEquals(StepPanel.EXPANDED_WIDTH, panel().getWidth(), 0.5);

        interact(() -> stored.set(480.0));
        press(KeyCode.V).release(KeyCode.V);
        FxSync.waitForFxEvents();
        press(KeyCode.V).release(KeyCode.V);
        FxSync.waitForFxEvents();

        assertEquals(480, panel().getWidth(), 1);
    }

    @Test
    void aStoredWidthBeyondWhatAPanelCanBeIsClamped() {
        interact(() -> view.setStepPanelWidthPreference(() -> 99999, ignored -> { }));
        FxSync.waitForFxEvents();

        assertTrue(panel().getWidth() <= StepPanel.MAX_WIDTH + 0.5);
        assertTrue(codeColumn().getWidth() >= RailLayout.CODE_MIN_WIDTH - 1,
                "and the window still gives the code its floor");
    }

    @Test
    void doubleClickingTheSplitterGoesBackToTheDefaultAndReportsZero() {
        List<Double> saved = new ArrayList<>();
        interact(() -> view.setStepPanelWidthPreference(() -> 0, saved::add));
        drag(splitter()).dropBy(-100, 0);
        FxSync.waitForFxEvents();
        saved.clear();

        doubleClickOn(splitter());
        FxSync.waitForFxEvents();

        assertEquals(StepPanel.EXPANDED_WIDTH, panel().getWidth(), 0.5);
        assertEquals(List.of(0.0), saved, "0 is the persisted form of \"the default\"");
    }

    @Test
    void theArrowKeysResizeTheFocusedSplitter() {
        clickOn(splitter());
        double before = panel().getWidth();

        type(KeyCode.LEFT);

        assertEquals(before + StepSplitter.KEY_STEP, panel().getWidth(), 2);
    }

    @Test
    void theHunkDiffHasNoSplitterAndAFocusedPanelStripHasNone() throws Exception {
        press(KeyCode.M).release(KeyCode.M);
        FxSync.waitForFxEvents();
        assertFalse(splitter().isManaged(), "a collapsed panel is a strip: nothing to drag");

        press(KeyCode.M).release(KeyCode.M);
        FxSync.waitForFxEvents();
        assertTrue(splitter().isManaged());

        press(KeyCode.V).release(KeyCode.V);
        focusDiffColumn();
        assertFalse(splitter().isManaged(), "the hunk diff's margin keeps its fixed width");
    }
}
