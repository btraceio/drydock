package app.drydock.ui.review;

import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The splitter's gestures, against a host that only records what it is told. */
class StepSplitterTest extends ApplicationTest {

    private final List<String> log = new ArrayList<>();
    private double width = 400;
    private double max = 460;
    private StepSplitter splitter;

    @Override
    public void start(Stage stage) {
        splitter = new StepSplitter(new StepSplitter.Host() {
            @Override public double panelWidth() { return width; }
            @Override public double maxPanelWidth() { return max; }
            @Override public void resizeTo(double newWidth) {
                width = newWidth;
                log.add("resize " + (int) newWidth);
            }
            @Override public void commit() { log.add("commit"); }
            @Override public void reset() {
                width = StepPanel.EXPANDED_WIDTH;
                log.add("reset");
            }
        });
        Region content = new Region();
        HBox.setHgrow(content, javafx.scene.layout.Priority.ALWAYS);
        stage.setScene(new Scene(new HBox(content, splitter), 600, 200));
        stage.show();
    }

    @Test
    void draggingTheEdgeLeftWidensThePanelAndCommitsOnceOnRelease() {
        drag(splitter).dropBy(-30, 0);
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals("commit", log.getLast());
        assertEquals(1, log.stream().filter("commit"::equals).count(), "one write per gesture, not per mouse event");
        assertTrue(width > 400 && width <= 460, "widened, within the max: " + width);
    }

    @Test
    void theCodeFloorStopsTheDragAtTheMax() {
        drag(splitter).dropBy(-300, 0);
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(460, width, "the host said 460 is as wide as the code allows");
    }

    @Test
    void draggingRightNarrowsThePanelButNeverBelowItsMinimum() {
        width = StepPanel.MIN_WIDTH + 10;

        drag(splitter).dropBy(60, 0);
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(StepPanel.MIN_WIDTH, width, "60px right would go below the minimum; it stops there");
        assertEquals("commit", log.getLast());
    }

    @Test
    void aPlainClickResizesNothingAndWritesNothing() {
        clickOn(splitter);
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(List.of(), log);
    }

    @Test
    void aDoubleClickResetsToTheDefault() {
        doubleClickOn(splitter);
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(List.of("reset"), log);
        assertEquals(StepPanel.EXPANDED_WIDTH, width);
    }

    @Test
    void arrowKeysMoveTheEdgeWhenItHasFocus() {
        clickOn(splitter);
        log.clear();

        type(KeyCode.LEFT);
        assertEquals(List.of("resize 424", "commit"), log, "← widens by one step");

        log.clear();
        type(KeyCode.RIGHT);
        assertEquals(List.of("resize 400", "commit"), log, "→ narrows");
    }

    @Test
    void shiftTakesBiggerStepsAndTheMaxStillHolds() {
        clickOn(splitter);
        log.clear();
        max = 480;

        press(KeyCode.SHIFT).press(KeyCode.LEFT).release(KeyCode.LEFT).release(KeyCode.SHIFT);
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(480, width, "+96 would pass the max, so it stops at it");
    }

    @Test
    void homeResetsAndTheNarrowestIsTheMinimum() {
        clickOn(splitter);
        log.clear();
        width = StepPanel.MIN_WIDTH + 10;

        type(KeyCode.RIGHT);
        assertEquals(StepPanel.MIN_WIDTH, width, "→ cannot go below the minimum");

        log.clear();
        type(KeyCode.HOME);
        assertEquals(List.of("reset"), log);
    }

    @Test
    void otherKeysAreLeftAlone() {
        clickOn(splitter);
        log.clear();

        type(KeyCode.A);

        assertEquals(List.of(), log);
    }
}
