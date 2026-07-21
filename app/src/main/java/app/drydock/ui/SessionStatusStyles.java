package app.drydock.ui;

import app.drydock.domain.SessionStatus;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.layout.Region;
import javafx.util.Duration;

/**
 * Shared visual vocabulary for session status (design handoff: "Session
 * status drives the sidebar dot, tab dot, and header pill consistently").
 * The three design statuses map from the richer domain enum:
 * running/starting {@code -> :running}, failed/missing {@code -> :error},
 * inactive/exited {@code -> } neither (the design's "idle").
 */
final class SessionStatusStyles {

    static final PseudoClass RUNNING = PseudoClass.getPseudoClass("running");
    static final PseudoClass ERROR = PseudoClass.getPseudoClass("error");

    private SessionStatusStyles() {
    }

    static boolean isRunning(SessionStatus status) {
        return status == SessionStatus.RUNNING || status == SessionStatus.STARTING;
    }

    static boolean isError(SessionStatus status) {
        return status == SessionStatus.FAILED || status == SessionStatus.MISSING_WORKING_DIRECTORY;
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
        Region dot = new Region();
        dot.getStyleClass().addAll("status-dot", "dot-" + sizePx);

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
        // transition animating a detached node forever -- stop it when the
        // dot leaves the scene, and resume when it is re-attached still
        // wanting to pulse (e.g. the collapsed sidebar returning via ⌘0).
        dot.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null) {
                pulse.stop();
            } else if (Boolean.TRUE.equals(dot.getProperties().get("drydock.pulsing"))) {
                pulse.play();
            }
        });

        updateDot(dot, initialStatus);
        return dot;
    }

    /** Re-applies status pseudo-classes on a dot created by {@link #createDot} and starts/stops its pulse. */
    static void updateDot(Region dot, SessionStatus status) {
        applyStatus(dot, status);
        if (dot.getProperties().get("drydock.pulse") instanceof ParallelTransition pulse) {
            boolean pulsing = status == SessionStatus.RUNNING || status == SessionStatus.STARTING;
            // Remembered separately from the transition's own state so the
            // scene listener in createDot can resume after a detach/attach.
            dot.getProperties().put("drydock.pulsing", pulsing);
            if (pulsing) {
                if (pulse.getStatus() != Animation.Status.RUNNING && dot.getScene() != null) {
                    pulse.play();
                }
            } else {
                pulse.stop();
                dot.setOpacity(1.0);
                dot.setScaleX(1.0);
                dot.setScaleY(1.0);
            }
        }
    }
}
