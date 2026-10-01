package app.drydock.ui;

import app.drydock.domain.SessionStatus;
import app.drydock.domain.SessionStatusFacet;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.beans.value.ChangeListener;
import javafx.css.PseudoClass;
import javafx.scene.CacheHint;
import javafx.scene.Node;
import javafx.scene.layout.Region;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * Shared visual vocabulary for session status (design handoff: "Session
 * status drives the sidebar dot, tab dot, and header pill consistently").
 * The three design statuses ({@code :running}, {@code :error}, and neither
 * for the design's "idle") are driven entirely by {@link SessionStatusFacet},
 * the single status-to-facet mapping -- see that type for which
 * {@link SessionStatus} values land in which bucket.
 */
final class SessionStatusStyles {

    static final PseudoClass RUNNING = PseudoClass.getPseudoClass("running");
    static final PseudoClass ERROR = PseudoClass.getPseudoClass("error");

    private SessionStatusStyles() {
    }

    static boolean isRunning(SessionStatus status) {
        return SessionStatusFacet.of(status) == SessionStatusFacet.RUNNING;
    }

    static boolean isError(SessionStatus status) {
        return SessionStatusFacet.of(status) == SessionStatusFacet.ERROR;
    }

    /** The design's three-value status label text. */
    static String designLabel(SessionStatus status) {
        if (isRunning(status)) {
            return "running";
        }
        return isError(status) ? "error" : "idle";
    }

    /** Applies the matching {@code :running}/{@code :error} pseudo-class state to {@code node}. */
    static void applyStatus(Node node, SessionStatus status) {
        node.pseudoClassStateChanged(RUNNING, isRunning(status));
        node.pseudoClassStateChanged(ERROR, isError(status));
    }

    /**
     * A status dot Region: {@code .status-dot .dot-<size>} with a running
     * pulse (2s ease-in-out, opacity 1&rarr;.45 + scale 1&rarr;.82,
     * auto-reversing -- handoff "Animations") that plays only while the
     * status is running.
     */
    static Region createDot(int sizePx, SessionStatus initialStatus) {
        return createDot(sizePx, initialStatus, true);
    }

    /**
     * As {@link #createDot(int, SessionStatus)}, but {@code filled=false}
     * renders a hollow ring (the design's idle treatment) instead of a solid
     * dot. Error color always fills -- a FAILED idle session still shows a
     * filled error dot, never hollow, so the failure signal is never
     * weakened by the idle treatment.
     */
    static Region createDot(int sizePx, SessionStatus initialStatus, boolean filled) {
        Region dot = new Region();
        dot.getStyleClass().addAll("status-dot", "dot-" + sizePx);
        if (!filled && !isError(initialStatus)) {
            dot.getStyleClass().add("dot-hollow");
        }
        // The pulse animates opacity and scale at pulse rate (60fps while it
        // runs). Rendered uncached, every tick re-rasterizes the node and
        // re-uploads its mask texture; cached, both properties become
        // transforms of a fixed texture (SCALE hint), and the per-frame cost
        // collapses to a composite. The dot is a few px across, so the
        // snapshot is negligible.
        dot.setCache(true);
        dot.setCacheHint(CacheHint.SCALE);

        FadeTransition fade = new FadeTransition(Duration.seconds(1), dot);
        fade.setFromValue(1.0);
        fade.setToValue(0.45);
        ScaleTransition scale = new ScaleTransition(Duration.seconds(1), dot);
        scale.setFromX(1.0);
        scale.setFromY(1.0);
        scale.setToX(0.82);
        scale.setToY(0.82);
        ParallelTransition pulse = new ParallelTransition(fade, scale);
        pulse.setAutoReverse(true);
        pulse.setCycleCount(Animation.INDEFINITE);
        dot.getProperties().put("drydock.pulse", pulse);

        // The pulse is INDEFINITE, so a dot discarded while running (the
        // sidebar rebuilds all rows on every refresh) would leave its
        // transition animating a detached node forever. It also has no
        // business ticking while the window cannot show it: the gate stops
        // the pulse when the dot detaches, when its window stops showing
        // (minimized/closed), and when the window loses focus -- and parks
        // the dot at its rest state instead of freezing mid-fade.
        dot.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (oldScene != null) {
                unwatchWindow(dot);
                oldScene.windowProperty().removeListener(dotWindowListener(dot));
            }
            if (newScene != null) {
                newScene.windowProperty().addListener(dotWindowListener(dot));
                watchWindow(dot, newScene.getWindow());
            }
            refreshPulse(dot);
        });

        updateDot(dot, initialStatus);
        return dot;
    }

    private static ChangeListener<Window> dotWindowListener(Region dot) {
        return (obs, oldWindow, newWindow) -> {
            unwatchWindow(dot);
            watchWindow(dot, newWindow);
            refreshPulse(dot);
        };
    }

    /** Starts gating the dot's pulse on {@code window}'s showing/focused state. */
    private static void watchWindow(Region dot, Window window) {
        if (window == null) {
            return;
        }
        ChangeListener<Boolean> gate = (obs, was, is) -> refreshPulse(dot);
        window.showingProperty().addListener(gate);
        window.focusedProperty().addListener(gate);
        dot.getProperties().put("drydock.pulse.window", window);
        dot.getProperties().put("drydock.pulse.gate", gate);
    }

    /** Undoes {@link #watchWindow} for whatever window the dot currently tracks. */
    @SuppressWarnings("unchecked")
    private static void unwatchWindow(Region dot) {
        if (dot.getProperties().remove("drydock.pulse.window") instanceof Window window
                && dot.getProperties().remove("drydock.pulse.gate") instanceof ChangeListener<?> gate) {
            ChangeListener<Boolean> typed = (ChangeListener<Boolean>) gate;
            window.showingProperty().removeListener(typed);
            window.focusedProperty().removeListener(typed);
        }
    }

    /**
     * Plays the pulse only when it is wanted and the window can actually
     * show it; stops it (parked at the rest state) otherwise. Called from
     * every lifecycle event: status change, scene attach/detach, window
     * swap, window show/hide, window focus.
     */
    private static void refreshPulse(Region dot) {
        if (!(dot.getProperties().get("drydock.pulse") instanceof ParallelTransition pulse)) {
            return;
        }
        if (!Boolean.TRUE.equals(dot.getProperties().get("drydock.pulsing"))) {
            pulse.stop();
            park(dot);
            return;
        }
        Window window = dot.getScene() == null ? null : dot.getScene().getWindow();
        if (window != null && window.isShowing() && window.isFocused()) {
            if (pulse.getStatus() != Animation.Status.RUNNING) {
                pulse.play();
            }
        } else if (pulse.getStatus() == Animation.Status.RUNNING) {
            pulse.stop();
            park(dot);
        }
    }

    /** Restores the dot's rest state -- a stopped pulse must not freeze mid-fade. */
    private static void park(Region dot) {
        dot.setOpacity(1.0);
        dot.setScaleX(1.0);
        dot.setScaleY(1.0);
    }

    /** Re-applies status pseudo-classes on a dot created by {@link #createDot} and re-evaluates its pulse. */
    static void updateDot(Region dot, SessionStatus status) {
        applyStatus(dot, status);
        // Remembered separately from the transition's own state so the
        // scene/window listeners in createDot can resume after a
        // detach/attach or a focus loss.
        dot.getProperties().put("drydock.pulsing", isRunning(status));
        refreshPulse(dot);
    }
}
