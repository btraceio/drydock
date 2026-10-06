package app.drydock.testing;

import javafx.scene.Node;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.testfx.api.FxRobotInterface;
import org.testfx.service.query.NodeQuery;

import java.util.concurrent.Callable;
import java.util.function.Predicate;

/**
 * Base class for view tests: the shared-toolkit replacement for TestFX's
 * {@code ApplicationTest}.
 *
 * <p>What changed and why. {@code ApplicationTest} re-ran {@code
 * FxToolkit.setupApplication} before every test -- a new Application, a
 * stage set-up loop, and TestFX's blind {@code waitForFxEvents} polling --
 * which across 253 test classes dominated the suite's wall time. Here the
 * toolkit is started once per JVM ({@link FxSync#ensurePlatform}), every
 * test gets a <strong>fresh</strong> stage (more isolation than TestFX,
 * which reused one primary stage whose size leaked between classes -- see
 * {@code TestStages}), every window the test left open is closed after it,
 * and pressed keys/buttons are released so robot state cannot leak into the
 * next test.</p>
 *
 * <p>Subclasses keep their {@code start(Stage)} override with the
 * {@code Application} signature; it is called with the fresh stage before
 * each test, exactly the hook they used with TestFX. The robot methods
 * ({@code lookup}, {@code from}, {@code clickOn}, {@code moveTo}, {@code
 * press}/{@code release}, {@code drag}, {@code dropBy}, {@code interact})
 * delegate to one shared {@link FxRobot} with the same names and shapes, so
 * call sites are unchanged. The robot stays TestFX's real one because the
 * production code depends on the real Glass gesture pipeline.</p>
 */
public abstract class FxTest {

    /** One robot per JVM: stateless apart from pressed keys/buttons, which {@link #fxCloseTestWindows} releases. */
    private static final FxRobotInterface ROBOT = new org.testfx.api.FxRobot();

    /** The fresh stage this test's {@code start} received. */
    protected Stage stage;

    @BeforeAll
    static void startFxToolkitOnce() {
        FxSync.ensurePlatform();
    }

    @BeforeEach
    final void fxCreateFreshStage() throws Exception {
        FxSync.ensurePlatform();
        stage = FxSync.interact((Callable<Stage>) Stage::new);
        // start() touches scene/stage state, so it runs on the FX thread --
        // exactly where TestFX's setupApplication invoked it.
        FxSync.interact(() -> {
            try {
                start(stage);
            } catch (Exception e) {
                throw new RuntimeException("test start() failed", e);
            }
        });
    }

    @AfterEach
    final void fxCloseTestWindows() {
        // Release whatever the test left pressed, or the next test inherits
        // a held shift key / mouse button (TestFX's internalAfter did this).
        ROBOT.release(new javafx.scene.input.KeyCode[0]);
        ROBOT.release(new javafx.scene.input.MouseButton[0]);
        FxSync.closeAllStageWindows();
        stage = null;
    }

    /**
     * Builds and shows the test's UI on {@code stage}. Called before every
     * test with a stage no other test has touched. Same signature as
     * {@code javafx.application.Application#start}, so overrides ported from
     * {@code ApplicationTest} keep their {@code throws Exception}.
     */
    protected void start(Stage stage) throws Exception {
        // Default: no UI. A test that only needs the toolkit running
        // overrides this with an empty body, same as with TestFX.
    }

    /** Runs {@code action} on the FX thread and returns after it completed. */
    protected final void interact(Runnable action) {
        FxSync.interact(action);
    }

    /** {@link #interact(Runnable)} with a value. */
    protected final <T> T interact(Callable<T> action) {
        return FxSync.interact(action);
    }

    /** CSS selector query across the robot's target windows. */
    protected final NodeQuery lookup(String selector) {
        return ROBOT.lookup(selector);
    }

    /** Predicate query across the robot's target windows. */
    protected final NodeQuery lookup(Predicate<Node> predicate) {
        return ROBOT.lookup(predicate);
    }

    /** Node-scoped query (the TestFX {@code from}). */
    protected final NodeQuery from(Node... parents) {
        return ROBOT.from(parents);
    }

    /** Node-scoped query starting from another query's matches. */
    protected final NodeQuery from(NodeQuery parentQuery) {
        return ROBOT.from(parentQuery);
    }

    /** Real-robot click: picking, full press/release gesture, the works. */
    protected final FxRobotInterface clickOn(Node target) {
        return ROBOT.clickOn(target);
    }

    /** Real-robot click on whatever matches the selector. */
    protected final FxRobotInterface clickOn(String selector) {
        return ROBOT.clickOn(selector);
    }

    /** Moves the real mouse to the node matching the selector. */
    protected final FxRobotInterface moveTo(String selector) {
        return ROBOT.moveTo(selector);
    }

    /** Moves the real mouse to the node. */
    protected final FxRobotInterface moveTo(Node node) {
        return ROBOT.moveTo(node);
    }

    /** Presses keys (KEY_PRESSED events on the focused node). */
    protected final FxRobotInterface press(javafx.scene.input.KeyCode... keys) {
        return ROBOT.press(keys);
    }

    /** Presses mouse buttons at the current mouse position. */
    protected final FxRobotInterface press(javafx.scene.input.MouseButton... buttons) {
        return ROBOT.press(buttons);
    }

    /** Releases keys (KEY_RELEASED events). */
    protected final FxRobotInterface release(javafx.scene.input.KeyCode... keys) {
        return ROBOT.release(keys);
    }

    /** Releases mouse buttons. */
    protected final FxRobotInterface release(javafx.scene.input.MouseButton... buttons) {
        return ROBOT.release(buttons);
    }

    /** Starts a real drag gesture: move to the node and press. */
    protected final FxRobotInterface drag(Node source) {
        return ROBOT.drag(source);
    }

    /** Finishes a drag: move by the offset (MOUSE_DRAGGED) and release. */
    protected final FxRobotInterface dropBy(double x, double y) {
        return ROBOT.dropBy(x, y);
    }

    /** Double-click on the node (real robot: two click gestures). */
    protected final FxRobotInterface doubleClickOn(Node target) {
        return ROBOT.doubleClickOn(target);
    }

    /** Presses, types, and releases the keys (the TestFX {@code type}). */
    protected final FxRobotInterface type(javafx.scene.input.KeyCode... keys) {
        return ROBOT.type(keys);
    }

    /** Types the given text character by character (the TestFX {@code write}). */
    protected final FxRobotInterface write(String text) {
        return ROBOT.write(text);
    }

    /** Presses, types, and releases the keys in one gesture (TestFX {@code push}). */
    protected final FxRobotInterface push(javafx.scene.input.KeyCode... keys) {
        return ROBOT.push(keys);
    }

    /** Robot sleep; keeps call sites readable between gesture steps. */
    protected final FxRobotInterface sleep(long millis) {
        return ROBOT.sleep(millis);
    }
}
