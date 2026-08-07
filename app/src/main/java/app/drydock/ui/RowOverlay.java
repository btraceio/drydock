package app.drydock.ui;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

/**
 * Floats a row's hover actions above it instead of beside it.
 *
 * <p>The sidebar's action buttons bind their visibility to the row's hover
 * state but stay <em>managed</em>, so they reserve their width on every row
 * at all times -- 70px of a 320px sidebar on a session row whose name had
 * ~84px to live in. Putting them in an overlay layer returns that width
 * permanently, and keeps the list still while the cursor crosses it: an
 * unmanaged-when-hidden fix would reclaim the same width but reflow every
 * row twice per crossing.
 */
final class RowOverlay {

    private RowOverlay() { }

    /**
     * {@code row} with {@code actions} floating over its trailing edge.
     *
     * <p>The stack is {@code pickOnBounds = false} so only the buttons
     * themselves are click targets: the rest of the strip's area passes
     * clicks through to the row beneath, which is what opens the session.
     * Without that, the right-hand third of every row would silently stop
     * responding.
     */
    static StackPane wrap(Region row, Node actions) {
        // The cell reports a preferred width of 1 so the virtual flow sizes
        // every cell to the viewport; the wrapper must not reintroduce a
        // preferred width of its own, and the row must be free to fill it.
        row.setMaxWidth(Double.MAX_VALUE);

        StackPane stack = new StackPane(row, actions);
        stack.setPickOnBounds(false);
        stack.setMaxWidth(Double.MAX_VALUE);
        StackPane.setAlignment(actions, Pos.CENTER_RIGHT);
        if (actions instanceof Region region) {
            region.setMaxWidth(Region.USE_PREF_SIZE);
            region.setPickOnBounds(false);
            region.getStyleClass().add("row-overlay-actions");
        }
        return stack;
    }
}
