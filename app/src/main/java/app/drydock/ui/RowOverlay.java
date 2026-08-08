package app.drydock.ui;

import javafx.geometry.Pos;
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
     * <p>The strip is split into two nodes so the fade and the click target
     * cannot fight each other. {@code pickOnBounds=false} only makes a
     * Region click-through where it paints <em>no background</em> -- with
     * one, the whole strip's bounding box is picked regardless of the flag.
     * So {@code actions} itself carries no background at all: genuine gaps
     * between its buttons then fall through to {@code row} beneath. The
     * fade gradient lives on a second, separate Region that is {@code
     * setMouseTransparent(true)} so it can never be a click target no
     * matter what it paints; it tracks {@code actions}' width, stretches to
     * the stack's full height, and shares {@code actions}' visibility, so
     * the two appear and disappear together.
     *
     * <p>As a side effect, {@code row}'s max width is set to {@code
     * Double.MAX_VALUE} so it fills the stack instead of reporting its own
     * preferred width.
     */
    static StackPane wrap(Region row, Region actions) {
        // The cell reports a preferred width of 1 so the virtual flow sizes
        // every cell to the viewport; the wrapper must not reintroduce a
        // preferred width of its own, and the row must be free to fill it.
        row.setMaxWidth(Double.MAX_VALUE);

        actions.setMaxWidth(Region.USE_PREF_SIZE);
        actions.setPickOnBounds(false);
        actions.getStyleClass().add("row-overlay-actions");

        Region fade = new Region();
        fade.getStyleClass().add("row-overlay-fade");
        fade.setMouseTransparent(true);
        fade.minWidthProperty().bind(actions.widthProperty());
        fade.maxWidthProperty().bind(actions.widthProperty());
        // An empty Region's preferred height is 0, and StackPane sizes an
        // unconstrained child to its preferred size -- a purely
        // CSS-styled node with no content and no explicit height silently
        // paints nothing, at zero pixels tall. Stretch it to the row's
        // full height (not just the 22px action buttons') so the wash
        // covers the row band those buttons sit on, not a sliver behind
        // them.
        fade.setMaxHeight(Double.MAX_VALUE);
        fade.visibleProperty().bind(actions.visibleProperty());
        // The active/hovered row color the fade must match is not reachable
        // from a descendant selector -- the strip is a StackPane sibling of
        // `row`, not its child -- so mirror the class here instead.
        if (row.getStyleClass().contains("active")) {
            fade.getStyleClass().add("active");
        }

        StackPane stack = new StackPane(row, fade, actions);
        stack.setMaxWidth(Double.MAX_VALUE);
        StackPane.setAlignment(fade, Pos.CENTER_RIGHT);
        StackPane.setAlignment(actions, Pos.CENTER_RIGHT);
        return stack;
    }
}
