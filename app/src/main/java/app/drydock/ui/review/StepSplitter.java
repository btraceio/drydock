package app.drydock.ui.review;

import javafx.scene.Cursor;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;

/**
 * The draggable edge between the code and the tour's step panel.
 *
 * <p>The step panel is where the reader does their reading, and a fixed 336px
 * is a narrow column for prose; this lets them trade code width for reading
 * width. It owns no width itself: it asks its {@link Host} how wide the panel
 * is and how wide it may get, tells it each new width as the pointer moves,
 * and says when the gesture is over so the host can persist the result once,
 * not on every mouse event.</p>
 *
 * <p>A pointer affordance with a keyboard equivalent: focusable, {@code ←}
 * widens the panel and {@code →} narrows it (the divider moves with the
 * arrow), {@code ⇧} for bigger steps, {@code Home} or a double-click for the
 * default. The board's own key filter leaves arrows alone, so they reach it
 * while it has focus.</p>
 */
final class StepSplitter extends Region {

    static final double THICKNESS = 7;
    static final double KEY_STEP = 24;
    static final double KEY_STEP_LARGE = 96;

    /** What the splitter needs from the board it sits in. */
    interface Host {
        /** The panel's current width. */
        double panelWidth();

        /**
         * The widest the panel may be right now, because the code column keeps
         * its floor. Never below {@link #panelWidth}.
         */
        double maxPanelWidth();

        /** The panel is to be {@code width} wide; called repeatedly during a drag. */
        void resizeTo(double width);

        /** The gesture is over: the width is final. */
        void commit();

        /** Back to the default width; already final. */
        void reset();
    }

    private final Host host;
    private double pressSceneX;
    private double pressWidth;
    private double pressMax;
    private boolean dragged;

    StepSplitter(Host host) {
        this.host = host;
        getStyleClass().add("review-splitter");
        setMinWidth(THICKNESS);
        setPrefWidth(THICKNESS);
        setMaxWidth(THICKNESS);
        setCursor(Cursor.H_RESIZE);
        setFocusTraversable(true);
        setAccessibleText("Resize the step panel");
        Tooltip.install(this, new Tooltip(
                "Drag to resize the step panel; ← → when focused, double-click to reset"));
        setOnMousePressed(this::onPressed);
        setOnMouseDragged(this::onDragged);
        setOnMouseReleased(this::onReleased);
        setOnKeyPressed(this::onKey);
    }

    private void onPressed(MouseEvent event) {
        if (event.getButton() != MouseButton.PRIMARY) {
            return;
        }
        requestFocus();
        pressSceneX = event.getSceneX();
        pressWidth = host.panelWidth();
        pressMax = Math.max(pressWidth, host.maxPanelWidth());
        dragged = false;
        getStyleClass().add("dragging");
        event.consume();
    }

    private void onDragged(MouseEvent event) {
        if (event.getButton() != MouseButton.PRIMARY || !getStyleClass().contains("dragging")) {
            return;
        }
        dragged = true;
        // The panel is to the right: dragging the edge left widens it.
        double width = pressWidth - (event.getSceneX() - pressSceneX);
        host.resizeTo(Math.clamp(width, StepPanel.MIN_WIDTH, Math.max(StepPanel.MIN_WIDTH, pressMax)));
        event.consume();
    }

    private void onReleased(MouseEvent event) {
        getStyleClass().remove("dragging");
        if (event.getButton() != MouseButton.PRIMARY) {
            return;
        }
        if (dragged) {
            host.commit();
        } else if (event.getClickCount() == 2) {
            host.reset();
        }
        dragged = false;
        event.consume();
    }

    private void onKey(KeyEvent event) {
        double step = event.isShiftDown() ? KEY_STEP_LARGE : KEY_STEP;
        double current = host.panelWidth();
        switch (event.getCode()) {
            case LEFT -> {
                host.resizeTo(Math.min(current + step, Math.max(current, host.maxPanelWidth())));
                host.commit();
            }
            case RIGHT -> {
                host.resizeTo(Math.max(current - step, StepPanel.MIN_WIDTH));
                host.commit();
            }
            case HOME -> host.reset();
            default -> {
                return;
            }
        }
        event.consume();
    }
}
