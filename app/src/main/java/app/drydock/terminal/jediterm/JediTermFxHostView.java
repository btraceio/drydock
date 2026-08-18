package app.drydock.terminal.jediterm;

import app.drydock.terminal.api.TerminalHostView;
import javafx.scene.Node;

import java.util.Optional;

/**
 * The JediTermFX {@link TerminalHostView}: a pure-JavaFX terminal, whose
 * "host view" is just the widget's own pane added to the JavaFX scene graph
 * by the owning tab.
 *
 * <p>Every method that positions, shows, focuses, or feeds raw input to a
 * native overlay view is a no-op here. That is not laziness -- it is the
 * correct implementation: a JavaFX {@code Node} in the scene graph is laid out,
 * shown, hidden, and given keyboard focus by JavaFX itself, and JediTermFX
 * consumes its own key/mouse/scroll events through the scene graph's normal
 * input machinery. The macOS path routes raw AppKit events because its
 * terminal is a native NSView outside the scene graph; this backend has no
 * such view, so there is nothing to route and nothing to position. The one
 * thing this host carries that the native one does not is the embedded
 * {@link Node} itself, via {@link #embeddedNode()}.</p>
 *
 * <p>The node is set by the runtime during {@code openSurface} (the widget is
 * created there, after the host exists), so the constructor takes none.</p>
 */
public final class JediTermFxHostView implements TerminalHostView {

    private Node node;

    void setEmbeddedNode(Node node) {
        this.node = node;
    }

    @Override
    public Optional<Node> embeddedNode() {
        return Optional.ofNullable(node);
    }

    @Override
    public void setFrame(double x, double y, double width, double height) {
        // No-op: the node is in the scene graph; JavaFX lays it out.
    }

    @Override
    public void setVisible(boolean visible) {
        // No-op: the node's visibility follows its place in the scene graph.
    }

    @Override
    public void setFocused(boolean focused) {
        // No-op: the widget manages its own focus.
    }

    @Override
    public void setKeyEventListener(KeyEventListener listener) {
        // No-op: the widget handles keyboard input itself.
    }

    @Override
    public void setScrollEventListener(ScrollEventListener listener) {
        // No-op: the widget handles scroll itself.
    }

    @Override
    public void setMousePosEventListener(MousePosEventListener listener) {
        // No-op.
    }

    @Override
    public void setMouseButtonEventListener(MouseButtonEventListener listener) {
        // No-op.
    }

    @Override
    public void close() {
        // No-op: the surface owns the widget + process lifecycle.
    }
}