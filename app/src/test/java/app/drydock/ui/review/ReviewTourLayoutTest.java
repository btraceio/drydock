package app.drydock.ui.review;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Region;
import javafx.scene.input.KeyCode;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tour's columns as they render under the real stylesheets (dark theme):
 * styled, full height, and the step panel kept at widths a 1280px window
 * actually leaves the Review view.
 */
class ReviewTourLayoutTest extends ReviewTourFixture {

    /** theme-dark.css's -drydock-chrome and -drydock-text. */
    private static final Color CHROME = Color.web("#191817");
    private static final Color TEXT = Color.web("#ece9e5");

    private static boolean treeVisible(Node node) {
        for (Node at = node; at != null; at = at.getParent()) {
            if (!at.isVisible()) {
                return false;
            }
        }
        return true;
    }

    private static Paint background(Region region) {
        return region.getBackground() == null || region.getBackground().getFills().isEmpty()
                ? null
                : region.getBackground().getFills().getFirst().getFill();
    }

    private void resizeWindow(double width) {
        Window window = view.getScene().getWindow();
        interact(() -> window.setWidth(width));
        WaitForAsyncUtils.waitForFxEvents();
        interact(() -> view.layout());
        WaitForAsyncUtils.waitForFxEvents();
    }

    private void key(KeyCode code) {
        press(code).release(code);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private boolean railsCollapsed(boolean outlineCollapsed, boolean stepPanelCollapsed) {
        return ReviewDiagFxThread.call(() -> view.diagOutline().collapsed() == outlineCollapsed
                && view.diagStepPanel().collapsed() == stepPanelCollapsed);
    }

    private boolean marginCollapsed() {
        ReviewFindingsMargin margin = (ReviewFindingsMargin) lookup(node -> node instanceof ReviewFindingsMargin)
                .query();
        return ReviewDiagFxThread.call(margin::collapsed);
    }

    @Test
    void theStepPanelAndTheOutlineAreStyledWithTheTheme() {
        StepPanel panel = ReviewDiagFxThread.call(view::diagStepPanel);
        TourOutline outline = ReviewDiagFxThread.call(view::diagOutline);

        assertEquals(CHROME, ReviewDiagFxThread.call(() -> background(panel)), "the step panel's surface");
        assertEquals(CHROME, ReviewDiagFxThread.call(() -> background(outline)), "the outline's surface");
        Label header = (Label) from(panel).lookup(".step-panel-header").query();
        assertEquals(TEXT, ReviewDiagFxThread.call(header::getTextFill));
        Label narrative = (Label) from(panel).lookup(".step-panel-narrative").query();
        assertNotEquals(Color.BLACK, ReviewDiagFxThread.call(narrative::getTextFill));
        Button choice = from(panel).lookup(".step-choice").queryButton();
        assertEquals(Pos.CENTER_LEFT, ReviewDiagFxThread.call(choice::getAlignment), "choices read left-aligned");
        assertNotEquals(Color.WHITE, ReviewDiagFxThread.call(() -> background(choice)));

        List<Button> rows = from(outline).lookup(".tour-outline-row").queryAllAs(Button.class).stream().toList();
        Button current = rows.stream().filter(row -> row.getStyleClass().contains("tour-outline-row-current"))
                .findFirst().orElseThrow();
        Button other = rows.stream().filter(row -> row != current).findFirst().orElseThrow();
        assertNotEquals(ReviewDiagFxThread.call(() -> background(other)),
                ReviewDiagFxThread.call(() -> background(current)), "the current step stands out");
    }

    @Test
    void theStepPanelsScrollFillsTheColumn() {
        StepPanel panel = ReviewDiagFxThread.call(view::diagStepPanel);
        ScrollPane scroll = from(panel).lookup(".scroll-pane").queryAs(ScrollPane.class);

        double panelHeight = ReviewDiagFxThread.call(panel::getHeight);
        double scrollHeight = ReviewDiagFxThread.call(scroll::getHeight);
        assertTrue(scrollHeight >= panelHeight - 2,
                "the scroll pane is " + scrollHeight + " of the panel's " + panelHeight + ": content below is clipped");
    }

    @Test
    void atTheWidthA1280WindowLeavesTheStepPanelStaysAndTheOutlineGoes() {
        // A 1280px window minus the session sidebar leaves Review about 1000px.
        try {
            resizeWindow(1000);
            StepPanel panel = ReviewDiagFxThread.call(view::diagStepPanel);
            assertFalse(ReviewDiagFxThread.call(panel::collapsed), "the step panel is the tour's primary content");
            assertTrue(ReviewDiagFxThread.call(() -> view.diagOutline().collapsed()), "the outline gives way first");
            Button choice = from(panel).lookup("2  it returns").queryButton();
            assertTrue(ReviewDiagFxThread.call(() -> treeVisible(choice) && choice.getWidth() > 0));
        } finally {
            resizeWindow(1400);
        }
    }

    @Test
    void aCollapsedStepPanelIsDarkAndOffersARealExpandButton() {
        try {
            resizeWindow(800);
            StepPanel panel = ReviewDiagFxThread.call(view::diagStepPanel);
            assertTrue(ReviewDiagFxThread.call(panel::collapsed));
            assertEquals(CHROME, ReviewDiagFxThread.call(() -> background(panel)), "never a white strip");
            Button expand = from(panel).lookup(".step-panel-expand").queryButton();
            assertTrue(ReviewDiagFxThread.call(() -> treeVisible(expand)));

            clickOn(expand);
            WaitForAsyncUtils.waitForFxEvents();
            Optional<String> notice = ReviewDiagFxThread.call(view::diagNotice);
            assertTrue(notice.isPresent(), "too narrow to expand, and it says so rather than doing nothing");
        } finally {
            resizeWindow(1400);
        }
    }

    @Test
    void aCollapsedOutlineShowsTheCurrentStepAndARealExpandButton() {
        try {
            resizeWindow(1000);
            TourOutline outline = ReviewDiagFxThread.call(view::diagOutline);
            assertTrue(ReviewDiagFxThread.call(outline::collapsed));
            Button expand = from(outline).lookup(".tour-outline-expand").queryButton();
            assertTrue(ReviewDiagFxThread.call(() -> treeVisible(expand) && expand.getWidth() > 0),
                    "never a blank strip");
            Label step = from(outline).lookup(".tour-outline-collapsed-step").queryAs(Label.class);
            assertTrue(ReviewDiagFxThread.call(() -> treeVisible(step)));
            assertEquals("1", ReviewDiagFxThread.call(step::getText));
            assertEquals(TEXT, ReviewDiagFxThread.call(expand::getTextFill), "the glyph reads like the theme's text");
            assertNotEquals(ReviewDiagFxThread.call(() -> background(outline)),
                    ReviewDiagFxThread.call(step::getTextFill), "the number stands off the strip");

            clickOn(expand);
            WaitForAsyncUtils.waitForFxEvents();
            assertTrue(ReviewDiagFxThread.call(outline::collapsed), "the width forces it");
            Optional<String> notice = ReviewDiagFxThread.call(view::diagNotice);
            assertTrue(notice.isPresent(), "too narrow to expand, and it says so rather than doing nothing");
        } finally {
            resizeWindow(1400);
        }
    }

    @Test
    void theCollapsedOutlinesExpandButtonUndoesAFocusModeCollapse() {
        TourOutline outline = ReviewDiagFxThread.call(view::diagOutline);
        key(KeyCode.F);
        assertTrue(ReviewDiagFxThread.call(outline::collapsed));

        clickOn(from(outline).lookup(".tour-outline-expand").queryButton());
        WaitForAsyncUtils.waitForFxEvents();

        assertFalse(ReviewDiagFxThread.call(outline::collapsed));
    }

    @Test
    void focusModeIsOneStateAcrossTheModeSwitch() {
        key(KeyCode.F);
        assertTrue(railsCollapsed(true, true), "f in the tour collapses both rails");
        key(KeyCode.V);
        assertTrue(marginCollapsed(), "the hunk diff is in focus mode too");

        key(KeyCode.F);
        assertFalse(marginCollapsed());
        key(KeyCode.V);
        assertTrue(railsCollapsed(false, false), "f left focus mode, so the tour's rails are back as well");

        key(KeyCode.V);
        key(KeyCode.F);
        assertTrue(marginCollapsed());
        key(KeyCode.V);
        assertTrue(railsCollapsed(true, true), "f in the hunk diff is focus mode in the tour too");
        key(KeyCode.F);
        assertTrue(railsCollapsed(false, false), "one f undoes it");
    }
}
