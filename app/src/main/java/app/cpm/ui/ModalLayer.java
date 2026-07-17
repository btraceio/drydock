package app.cpm.ui;

import javafx.geometry.Pos;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

/**
 * The in-scene modal layer (design handoff sections 7/8): a dimmed
 * backdrop stacked over the whole app shell, hosting one centered modal
 * at a time. Esc or a click on the backdrop closes the topmost modal;
 * clicks inside the modal itself do not.
 *
 * <p>Implemented as a scene-graph overlay rather than a second {@code
 * Stage} so the backdrop dims exactly the app window (including the custom
 * title bar) and theming CSS applies unchanged.</p>
 */
public final class ModalLayer extends StackPane {

    private Runnable onClosed = () -> { };

    ModalLayer() {
        getStyleClass().add("modal-backdrop");
        // Stays managed: the parent StackPane sizes it to fill the scene
        // (an unmanaged overlay is NOT laid out by its parent -- it sat at
        // 0x0, so the "centered" modal hung off the window's top-left with
        // only its lower-right corner visible). visible=false while closed
        // already removes it from painting and mouse picking.
        setVisible(false);
        setAlignment(Pos.CENTER);

        addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
            if (event.getTarget() == this) {
                close();
            }
        });
        addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                close();
                event.consume();
            }
        });
    }

    public boolean isShowingModal() {
        return isVisible();
    }

    /** Shows {@code modal} centered; replaces any modal already showing. */
    public void show(Region modal, Runnable onClosed) {
        this.onClosed = onClosed == null ? () -> { } : onClosed;
        getChildren().setAll(modal);
        setVisible(true);
        modal.requestFocus();
    }

    public void show(Region modal) {
        show(modal, null);
    }

    public void close() {
        if (!isVisible()) {
            return;
        }
        setVisible(false);
        getChildren().clear();
        Runnable callback = onClosed;
        onClosed = () -> { };
        callback.run();
    }

}
