package app.cpm.ui;

import javafx.scene.Cursor;
import javafx.scene.Parent;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;

/**
 * Edge/corner drag-resize for an undecorated stage ({@code
 * StageStyle.UNDECORATED} loses the OS resize borders entirely on macOS).
 * Installs event filters on the scene root: within {@value #GRIP_PX}px of
 * a window edge the cursor changes and a press-drag resizes instead of
 * reaching the underlying controls.
 *
 * <p>The top edge is deliberately excluded: it is the title bar's drag
 * region, and a 6px grip there would make grabbing the window to move it
 * unreliable. Left/right/bottom edges plus both bottom corners resize.</p>
 */
final class StageResizer {

    private static final double GRIP_PX = 6;

    private final Stage stage;
    private final double minWidth;
    private final double minHeight;

    private boolean resizingEast;
    private boolean resizingWest;
    private boolean resizingSouth;

    private StageResizer(Stage stage, double minWidth, double minHeight) {
        this.stage = stage;
        this.minWidth = minWidth;
        this.minHeight = minHeight;
    }

    static void install(Stage stage, Parent root, double minWidth, double minHeight) {
        StageResizer resizer = new StageResizer(stage, minWidth, minHeight);
        root.addEventFilter(MouseEvent.MOUSE_MOVED, resizer::updateCursor);
        root.addEventFilter(MouseEvent.MOUSE_PRESSED, resizer::onPressed);
        root.addEventFilter(MouseEvent.MOUSE_DRAGGED, resizer::onDragged);
        root.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> resizer.stopResizing(root));
        root.addEventFilter(MouseEvent.MOUSE_EXITED, e -> {
            if (!resizer.active()) {
                root.setCursor(Cursor.DEFAULT);
            }
        });
    }

    private boolean active() {
        return resizingEast || resizingWest || resizingSouth;
    }

    private void updateCursor(MouseEvent event) {
        if (active() || stage.isMaximized()) {
            return;
        }
        boolean east = event.getSceneX() >= stage.getWidth() - GRIP_PX;
        boolean west = event.getSceneX() <= GRIP_PX;
        boolean south = event.getSceneY() >= stage.getHeight() - GRIP_PX;
        Cursor cursor = Cursor.DEFAULT;
        if (south && east) {
            cursor = Cursor.SE_RESIZE;
        } else if (south && west) {
            cursor = Cursor.SW_RESIZE;
        } else if (east) {
            cursor = Cursor.E_RESIZE;
        } else if (west) {
            cursor = Cursor.W_RESIZE;
        } else if (south) {
            cursor = Cursor.S_RESIZE;
        }
        ((Parent) event.getSource()).setCursor(cursor);
    }

    private void onPressed(MouseEvent event) {
        if (stage.isMaximized()) {
            return;
        }
        resizingEast = event.getSceneX() >= stage.getWidth() - GRIP_PX;
        resizingWest = event.getSceneX() <= GRIP_PX;
        resizingSouth = event.getSceneY() >= stage.getHeight() - GRIP_PX;
        if (active()) {
            event.consume();
        }
    }

    private void onDragged(MouseEvent event) {
        if (!active()) {
            return;
        }
        if (resizingEast) {
            stage.setWidth(Math.max(minWidth, event.getSceneX()));
        }
        if (resizingWest) {
            double newWidth = stage.getX() + stage.getWidth() - event.getScreenX();
            if (newWidth >= minWidth) {
                stage.setX(event.getScreenX());
                stage.setWidth(newWidth);
            }
        }
        if (resizingSouth) {
            stage.setHeight(Math.max(minHeight, event.getSceneY()));
        }
        event.consume();
    }

    private void stopResizing(Parent root) {
        if (active()) {
            resizingEast = false;
            resizingWest = false;
            resizingSouth = false;
            root.setCursor(Cursor.DEFAULT);
        }
    }
}
