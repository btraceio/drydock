package app.drydock.ui;

import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The helper every rendering test now goes through is itself a place a stage
 * can end up the wrong size -- and a helper that promises "sizes the stage to
 * it" and quietly produces 0x0 would fail exactly the way this whole round
 * exists to stop: a class laying out at nothing, naming no width.
 */
class TestStagesTest extends ApplicationTest {

    @Override
    public void start(Stage stage) {
        TestStages.show(stage, new Scene(new StackPane(new Label("host")), 400, 300));
    }

    /**
     * A scene built WITHOUT dimensions has none to copy. Copying them anyway
     * pins the stage at 0x0; {@code TestStages} falls back to
     * {@code sizeToScene()}, which is what the plain {@code stage.show()} it
     * replaced would have done.
     */
    @Test
    void anUnsizedSceneStillGetsAStageWithSizeInIt() {
        double[] size = new double[2];
        interact(() -> {
            Stage extra = new Stage();
            TestStages.show(extra, new Scene(new StackPane(new Label("a label with real width"))));
            size[0] = extra.getWidth();
            size[1] = extra.getHeight();
            extra.hide();
        });
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(size[0] > 0 && size[1] > 0,
                "an unsized scene must still yield a stage with size in it, got "
                        + Math.round(size[0]) + "x" + Math.round(size[1]));
    }

    /** The ordinary case: the stage takes the size the scene declares. */
    @Test
    void aSizedSceneSetsTheStageToItsOwnDimensions() {
        double[] size = new double[2];
        interact(() -> {
            Stage extra = new Stage();
            TestStages.show(extra, new Scene(new StackPane(), 640, 480));
            size[0] = extra.getWidth();
            size[1] = extra.getHeight();
            extra.hide();
        });
        WaitForAsyncUtils.waitForFxEvents();

        assertTrue(Math.abs(size[0] - 640) < 1 && Math.abs(size[1] - 480) < 1,
                "the stage must take the scene's own size, got "
                        + Math.round(size[0]) + "x" + Math.round(size[1]));
    }
}
