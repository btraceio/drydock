package app.drydock.ui;

import app.drydock.domain.SessionStatus;

import app.drydock.testing.FxTest;
import javafx.animation.Animation;
import javafx.animation.ParallelTransition;
import javafx.scene.CacheHint;
import javafx.scene.Scene;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The status dot's pulse is an INDEFINITE animation, and an INDEFINITE
 * animation that nobody can see is the purest CPU waste an app can produce
 * (the 2026-10-01 high-CPU investigation: one pulsing sidebar dot kept the
 * render thread at ~55% CPU re-rendering the scene graph at pulse rate for
 * the app's entire lifetime). These tests pin the two guardrails: the pulse
 * runs only while the window can actually show it, and the dot renders from
 * a cache so each tick is a texture transform instead of a re-render.
 *
 * <p>Driven through the headless JavaFX harness like the other real-Stage
 * tests ({@code OpenSessionTabReviewSubTabTest}). One deliberate gap: the
 * Monocle harness never reports a window unfocused (a second shown stage
 * leaves both stages {@code focused=true}, probed 2026-10-01), so the
 * focus half of the gate cannot be driven headlessly -- it is the same
 * eligibility check the hiding tests below exercise, differing only in the
 * window property consulted.</p>
 */
class SessionStatusStylesTest extends FxTest {

    private Stage stage;
    private StackPane root;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        this.root = new StackPane();
        TestStages.show(stage, new Scene(root, 200, 200));
        interact(() -> stage.requestFocus());
    }

    private static ParallelTransition pulseOf(Region dot) {
        return (ParallelTransition) dot.getProperties().get("drydock.pulse");
    }

    private Region runningDot() {
        Region[] holder = new Region[1];
        interact(() -> {
            Region dot = SessionStatusStyles.createDot(8, SessionStatus.RUNNING);
            root.getChildren().add(dot);
            holder[0] = dot;
        });
        return holder[0];
    }

    @Test
    void aRunningDotOnAShownFocusedWindowPulses() {
        Region dot = runningDot();
        assertTrue(stage.isShowing(), "precondition: the stage is showing");
        assertEquals(Animation.Status.RUNNING, pulseOf(dot).getStatus(),
                "a running dot on the focused, showing window pulses");
    }

    @Test
    void hidingTheWindowStopsThePulseAndParksTheDot() {
        Region dot = runningDot();
        assertEquals(Animation.Status.RUNNING, pulseOf(dot).getStatus());
        interact(() -> stage.hide());
        assertEquals(Animation.Status.STOPPED, pulseOf(dot).getStatus(),
                "a hidden window must not animate");
        assertEquals(1.0, dot.getOpacity(), "the parked dot rests at full opacity, not mid-fade");
        assertEquals(1.0, dot.getScaleX(), "the parked dot rests at full scale, not mid-shrink");
        assertEquals(1.0, dot.getScaleY(), "the parked dot rests at full scale, not mid-shrink");
    }

    @Test
    void showingTheWindowAgainResumesThePulse() {
        Region dot = runningDot();
        interact(() -> stage.hide());
        assertEquals(Animation.Status.STOPPED, pulseOf(dot).getStatus());
        interact(() -> stage.show());
        assertEquals(Animation.Status.RUNNING, pulseOf(dot).getStatus(),
                "the window is back, the pulse resumes");
    }

    @Test
    void anIdleDotNeverPulses() {
        Region[] holder = new Region[1];
        interact(() -> {
            Region dot = SessionStatusStyles.createDot(8, SessionStatus.INACTIVE);
            root.getChildren().add(dot);
            holder[0] = dot;
        });
        Region dot = holder[0];
        assertNotEquals(Animation.Status.RUNNING, pulseOf(dot).getStatus());
        assertEquals(1.0, dot.getOpacity());
    }

    @Test
    void detachingTheDotStopsItsPulse() {
        Region dot = runningDot();
        assertEquals(Animation.Status.RUNNING, pulseOf(dot).getStatus());
        interact(() -> root.getChildren().remove(dot));
        assertEquals(Animation.Status.STOPPED, pulseOf(dot).getStatus(),
                "a detached dot must not keep animating");
        interact(() -> root.getChildren().add(dot));
        assertEquals(Animation.Status.RUNNING, pulseOf(dot).getStatus(),
                "re-attached and still wanted, the pulse resumes");
    }

    @Test
    void aDotNeverShownStaysStoppedUntilItIs() {
        Region[] holder = new Region[1];
        interact(() -> holder[0] = SessionStatusStyles.createDot(8, SessionStatus.RUNNING));
        Region dot = holder[0];
        assertEquals(Animation.Status.STOPPED, pulseOf(dot).getStatus(),
                "no window, no pulse -- even for a running session");
        interact(() -> root.getChildren().add(dot));
        assertEquals(Animation.Status.RUNNING, pulseOf(dot).getStatus(),
                "shown and focused now, the pulse starts");
    }

    @Test
    void movingTheDotToAnotherSceneOnTheSameWindowKeepsGating() {
        Region dot = runningDot();
        interact(() -> root.requestFocus());
        assertEquals(Animation.Status.RUNNING, pulseOf(dot).getStatus());

        interact(() -> stage.setScene(new Scene(new StackPane(dot), 100, 100)));
        assertEquals(Animation.Status.RUNNING, pulseOf(dot).getStatus(),
                "the gate follows the dot across scenes on the same window");

        interact(() -> stage.hide());
        assertEquals(Animation.Status.STOPPED, pulseOf(dot).getStatus(),
                "and still stops when that window can no longer show it");
    }

    @Test
    void theDotRendersFromACachedSnapshot() {
        Region dot = runningDot();
        assertTrue(dot.isCache(), "the pulse animates transforms; without a cache every tick re-renders");
        assertEquals(CacheHint.SCALE, dot.getCacheHint(), "SCALE tells Prism the snapshot only moves/scales");
    }
}
